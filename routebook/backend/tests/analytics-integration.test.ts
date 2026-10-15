import { test } from 'node:test';
import assert from 'node:assert/strict';
import pg from 'pg';
import { randomUUID } from 'node:crypto';
import { readFile,writeFile,mkdtemp,rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { PgStore } from '../src/store.js';
import { createServers } from '../src/server.js';
import { analyticsBounds } from '../src/analytics.js';
import { syntheticPoint,token } from './helpers.js';
const db=process.env.ROUTEBOOK_TEST_DB_URL;
const run=promisify(execFile);
test('real v1-only database upgrades additively with existing points and metadata survives bootstrap',{skip:!db,timeout:30000},async()=>{
  if(!db||!new URL(db).pathname.startsWith('/routebook_synthetic'))throw Error('Dedicated synthetic database required');
  const database='routebook_synthetic_migration_'+randomUUID().replaceAll('-',''),admin=new pg.Pool({connectionString:db});let pool:pg.Pool|undefined;
  try{
    await admin.query('CREATE DATABASE '+database);const url=new URL(db);url.pathname='/'+database;pool=new pg.Pool({connectionString:url.toString()});
    await pool.query(await readFile(new URL('../sql/schema-v1.sql',import.meta.url),'utf8'));const device=randomUUID();await pool.query('INSERT INTO routebook_devices(device_id) VALUES($1)',[device]);
    const store=new PgStore(pool);await store.ingest({version:1,device_id:device,lane:'latest',points:[syntheticPoint()]},'2026-10-15T08:00:00.000Z');const before=await store.snapshot(device);
    await store.bootstrap([]);assert.deepEqual(await store.snapshot(device),before);assert.deepEqual((await store.places(device)).places,[]);
    await store.writePlace(device,null,{name:'Synthetic Migration',lat:0,lon:0,radius_m:100},0);await store.bootstrap([device]);assert.equal((await store.places(device)).metadata_revision,1);assert.deepEqual(await store.snapshot(device),before);
  }finally{if(pool)await pool.end();await admin.query('DROP DATABASE IF EXISTS '+database);await admin.end();}
});
test('analytics PostGIS: additive migration, persistence, revisions, late backfill and private HTTP writes', {skip:!db,timeout:120000},async(t)=>{
  if(!db||!new URL(db).pathname.startsWith('/routebook_synthetic'))throw Error('Dedicated synthetic database required');
  let pool=new pg.Pool({connectionString:db}),store=new PgStore(pool);const device=randomUUID(),other=randomUUID();
  await store.bootstrap([device,other]);
  const {publicApp,privateApp}=createServers(store,{devices:[{device_id:device,token}],now:()=> '2026-10-15T08:00:00.000Z'});
  const url=`/v1/places?device_id=${device}`,headers={'x-routebook-write':'1','content-type':'application/json',host:'routebook.example.test:444',origin:'https://routebook.example.test:444'};
  const body={expected_metadata_revision:0,name:'Synthetic Alpha',lat:0,lon:0};let placeId='';
  try {
    await t.test('strict mutation boundary, CSRF, exact validation and public isolation',async()=>{
      for(const method of ['GET','POST','PUT','DELETE','OPTIONS','HEAD'] as const)for(const path of [url,`/v1/analytics?device_id=${device}&from=2026-10-05&to=2026-10-05`,`/v1/places/${other}?device_id=${device}`])assert.equal((await publicApp.inject({method,url:path,headers,payload:method==='POST'?body:undefined})).statusCode,404);
      for(const h of [{}, {...headers,'x-routebook-write':''},{...headers,origin:'https://attacker.example.test:444'},{...headers,'sec-fetch-site':'cross-site'},{...headers,origin:'null'},{...headers,origin:'https://routebook.example.test'}])assert.equal((await privateApp.inject({method:'POST',url,headers:h,payload:body})).statusCode,403);
      assert.equal((await privateApp.inject({method:'POST',url,headers,payload:{...body,extra:true}})).statusCode,422);
      assert.equal((await privateApp.inject({method:'POST',url,headers,payload:'{"expected_metadata_revision":0,"name":"a","name":"b","lat":0,"lon":0}'})).statusCode,422);
      assert.deepEqual((await store.places(device)).places,[]);
      const created=await privateApp.inject({method:'POST',url,headers,payload:body});assert.equal(created.statusCode,200);const a=created.json();placeId=a.places[0].place_id;assert.equal(a.metadata_revision,1);assert.equal(a.places[0].radius_m,100);
      assert.equal((await privateApp.inject({method:'POST',url,headers,payload:body})).statusCode,409);
      assert.equal((await privateApp.inject({method:'PUT',url:`/v1/places/${placeId}?device_id=${other}`,headers,payload:{...body,expected_metadata_revision:0}})).statusCode,404);
    });
    await t.test('concurrent metadata updates serialize and do not change source revision or raw data',async()=>{
      const updates=await Promise.all([1,2].map(i=>privateApp.inject({method:'PUT',url:`/v1/places/${placeId}?device_id=${device}`,headers,payload:{...body,expected_metadata_revision:1,name:`Synthetic Beta ${i}`}})));
      assert.deepEqual(updates.map(x=>x.statusCode).sort(),[200,409]);assert.equal((await store.places(device)).metadata_revision,2);assert.equal((await store.snapshot(device)).revision,0);
      const points=Array.from({length:20},(_,i)=>syntheticPoint({event_id:randomUUID(),measured_at:new Date(Date.parse('2026-10-05T08:00:00.000Z')+i*5000).toISOString(),lon:i*.00045,speed_mps:10}));
      await store.ingest({version:1,device_id:device,lane:'backfill',points:[points[0],points.at(-1)!]},'2026-10-15T08:00:00.000Z');
      const selected=`/v1/analytics?device_id=${device}&from=2026-10-05&to=2026-10-05`;
      const before=(await privateApp.inject(selected)).json();assert.equal(before.source_revision,1);assert.equal(before.metadata_revision,2);assert.equal(before.journeys.length,0);
      await store.ingest({version:1,device_id:device,lane:'backfill',points:points.slice(1,-1)},'2026-10-15T08:00:00.000Z');
      const after=(await privateApp.inject(selected)).json();assert.equal(after.source_revision,2);assert.equal(after.metadata_revision,2);assert.equal(after.journeys.length,1);
      const detail=await privateApp.inject(`/v1/analytics/journey?device_id=${device}&from=2026-10-05&to=2026-10-05&journey_id=${after.journeys[0].journey_id}`);assert.equal(detail.statusCode,200);assert.equal(detail.json().points.length,20);
      assert.equal((await privateApp.inject(`/v1/analytics?device_id=${device}&from=2026-01-01&to=2026-02-01`)).statusCode,422);
      await assert.rejects(pool.query('UPDATE routebook_points SET lat=1 WHERE device_id=$1',[device]),/immutable/);
      await assert.rejects(pool.query('DELETE FROM routebook_points WHERE device_id=$1',[device]),/immutable/);
      const queried:string[]=[];const connect=pool.connect.bind(pool);
      const wrapped={connect:async()=>{const c=await connect();return {query:async(sql:string,args?:unknown[])=>{queried.push(sql);return c.query(sql,args);},release:()=>c.release()};}} as unknown as pg.Pool;
      await new PgStore(wrapped).analyticsSnapshot(device,analyticsBounds('2026-10-05','2026-10-05'));
      assert.ok(queried.some(q=>q.includes('LIMIT $4')));assert.ok(!queried.some(q=>/^SELECT .*routebook_points WHERE device_id=\$1 ORDER BY/.test(q)));
    });
    await t.test('idempotent migration/pool restart preserve metadata and immutable points; deletion removes only metadata',async()=>{
      const before=await store.snapshot(device),metadata=await store.places(device);await store.bootstrap([device]);assert.deepEqual(await store.snapshot(device),before);assert.deepEqual(await store.places(device),metadata);
      const reconnect=new pg.Pool({connectionString:db});try{const fresh=new PgStore(reconnect);assert.deepEqual(await fresh.places(device),metadata);assert.deepEqual(await fresh.snapshot(device),before);}finally{await reconnect.end();}
      const deleted=await privateApp.inject({method:'DELETE',url:`/v1/places/${placeId}?device_id=${device}`,headers,payload:{expected_metadata_revision:2}});assert.equal(deleted.statusCode,200);assert.equal(deleted.json().metadata_revision,3);assert.deepEqual(deleted.json().places,[]);assert.deepEqual(await store.snapshot(device),before);
    });
    await t.test('real nginx forwards original Host including port; same-origin writes succeed and cross-origin writes fail',{skip:process.env.ROUTEBOOK_TEST_PROXY!=='1'},async()=>{
      await privateApp.listen({host:'0.0.0.0',port:0});const addr=privateApp.server.address();assert.ok(addr&&typeof addr!=='string');
      const temp=await mkdtemp(join(tmpdir(),'routebook-synthetic-proxy-')),container='routebook-synthetic-proxy-'+randomUUID();
      const template=await readFile(new URL('../../deploy/templates/nginx.conf',import.meta.url),'utf8');
      const config=template.replace('listen 127.0.0.1:8792;','listen 80;').replaceAll('127.0.0.1:8794',`host.docker.internal:${addr.port}`);
      const path=join(temp,'nginx.conf');await writeFile(path,config);
      try {
        await run('docker',['run','-d','--name',container,'-p','127.0.0.1::80','-v',`${path}:/etc/nginx/nginx.conf:ro`,'nginx:1.28-alpine']);
        const {stdout}=await run('docker',['port',container,'80/tcp']);const base='http://'+stdout.trim();
        let ready=false;for(let i=0;i<30;i++){try{if((await fetch(base+'/health')).ok){ready=true;break;}}catch{}await new Promise(r=>setTimeout(r,100));}assert.equal(ready,true);
        const send=(origin:string)=>fetch(base+url,{method:'POST',headers:{'Content-Type':'application/json','X-Routebook-Write':'1',Origin:origin},body:JSON.stringify({...body,expected_metadata_revision:3})});
        assert.equal((await send('https://attacker.example.test')).status,403);
        const accepted=await send(base);assert.equal(accepted.status,200);assert.equal((await accepted.json()).metadata_revision,4);
      }finally{await run('docker',['rm','-f',container]).catch(()=>{});await rm(temp,{recursive:true,force:true});}
    });
  }finally{await publicApp.close();await privateApp.close();await pool.end();}
});
