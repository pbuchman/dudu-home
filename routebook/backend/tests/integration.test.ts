import { test } from 'node:test';
import assert from 'node:assert/strict';
import pg from 'pg';
import { randomUUID } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { PgStore } from '../src/store.js';
import { createServers } from '../src/server.js';
import { envelope } from '../src/validation.js';
import { day,dayBounds,latest } from '../src/derive.js';
import { fixtures,checkCase,checkDay,syntheticPoint,token } from './helpers.js';
const db=process.env.ROUTEBOOK_TEST_DB_URL;
test('real PostgreSQL/PostGIS: normative fixtures, concurrency, commit failure, immutable points, SSE and restart', {skip:!db,timeout:120000},async(t)=>{
 if(!db||!new URL(db).pathname.startsWith('/routebook_synthetic'))throw Error('Tests require a dedicated routebook_synthetic database');
 let pool=new pg.Pool({connectionString:db,max:8,connectionTimeoutMillis:5000});let store=new PgStore(pool);
 const ids=fixtures.cases.map(()=>randomUUID()),concurrentId=randomUUID(),failedId=randomUUID(),liveId=randomUUID(),emptyId=randomUUID();
 await store.bootstrap([...ids,concurrentId,failedId,liveId,emptyId]);
 try {
  for(let i=0;i<fixtures.cases.length;i++)await t.test(`PostGIS fixture ${fixtures.cases[i].name}`,async()=>{
    const c=fixtures.cases[i];await checkCase(store,c,ids[i]);const q=c.expected_queries;
    for(const d of [q?.day,...(q?.days??[])].filter(Boolean))checkDay(await store.snapshot(ids[i],dayBounds(d.date)),d);
  });
  await t.test('concurrent duplicate requests accept exactly once; revision once; first receipt durable',async()=>{
    const e=envelope({version:1,device_id:concurrentId,lane:'latest',points:[syntheticPoint()]},concurrentId);
    const responses=await Promise.all(Array.from({length:8},()=>store.ingest(e,'2026-10-05T08:10:00.000Z')));
    assert.equal(responses.filter(x=>x.ack.results[0].status==='accepted').length,1);assert.equal(responses.filter(x=>x.event).length,1);
    assert.ok(responses.every(x=>x.ack.revision===1));assert.equal((await store.snapshot(concurrentId)).points.length,1);
    await assert.rejects(pool.query('UPDATE routebook_points SET lat=1 WHERE device_id=$1',[concurrentId]),/immutable/);
    const geo=await pool.query('SELECT ST_SRID(geom) srid,ST_X(geom) lon,ST_Y(geom) lat FROM routebook_points WHERE device_id=$1',[concurrentId]);assert.deepEqual(geo.rows,[{srid:4326,lon:0,lat:0}]);
  });
  await t.test('ingest and latest index queries never fetch full archive; day scopes request',async()=>{
    const queries:string[]=[];const connect=pool.connect.bind(pool);
    const wrapped={connect:async()=>{const c=await connect();return {query:async(sql:string,values?:any[])=>{queries.push(sql);return c.query(sql,values);},release:()=>c.release()};}} as unknown as pg.Pool;
    const observed=new PgStore(wrapped);const e=envelope({version:1,device_id:emptyId,lane:'latest',points:[syntheticPoint()]},emptyId);
    const result=await observed.ingest(e,'2026-10-05T08:10:00.000Z');assert.equal(result.event?.snapshot.points.length,1);
    await observed.latestSnapshot(emptyId);
    for(const q of queries.filter(x=>/^SELECT .*FROM routebook_points/.test(x)))assert.ok(/LIMIT 1$/.test(q)||q.includes('AND event_id=$2'));
    queries.length=0;await observed.snapshot(emptyId,dayBounds('2026-10-05'));
    assert.ok(queries.some(x=>x.includes('measured_at >= $2 AND measured_at < $3')));
    assert.ok(queries.filter(x=>/^SELECT .*FROM routebook_points/.test(x)).every(x=>x.includes('measured_at >= $2 AND measured_at < $3')||/LIMIT (1|256)$/.test(x)));
  });
  await t.test('today new segment does not invalidate disconnected week-old history; late insertion invalidates broken old run',async()=>{
    const id=randomUUID();await store.bootstrap([id]);
    const post=async(points:any[])=>store.ingest(envelope({version:1,device_id:id,lane:'backfill',points},id),'2026-10-05T08:10:00.000Z');
    const segment=randomUUID();await post([syntheticPoint({event_id:randomUUID(),segment_id:segment,measured_at:'2026-09-28T08:00:00.000Z'}),syntheticPoint({event_id:randomUUID(),segment_id:segment,measured_at:'2026-09-28T08:00:20.000Z'})]);
    const today=await post([syntheticPoint({event_id:randomUUID(),segment_id:randomUUID()})]);assert.deepEqual(today.event?.affected_dates,['2026-10-04','2026-10-05','2026-10-06']);
    const inserted=await post([syntheticPoint({event_id:randomUUID(),segment_id:randomUUID(),measured_at:'2026-09-28T08:00:10.000Z'})]);assert.ok(inserted.event?.affected_dates.includes('2026-09-28'));assert.ok(!inserted.event?.affected_dates.includes('2026-10-05'));
    assert.equal(day(await store.snapshot(id,dayBounds('2026-09-28')),'2026-09-28').segments.length,3);
  });
  await t.test('long midnight stop retains original anchor across multiple indexed context batches',async()=>{
    const id=randomUUID(),segment=randomUUID();await store.bootstrap([id]);const boundary=Date.parse('2026-10-04T22:00:00.000Z');
    const points=Array.from({length:280},(_,i)=>syntheticPoint({event_id:randomUUID(),segment_id:segment,measured_at:new Date(boundary+(i-270)*30000).toISOString()}));
    for(let i=0;i<points.length;i+=200)await store.ingest(envelope({version:1,device_id:id,lane:'backfill',points:points.slice(i,i+200)},id),'2026-10-05T08:10:00.000Z');
    const scoped=await store.snapshot(id,dayBounds('2026-10-05')),d=day(scoped,'2026-10-05');assert.equal(scoped.points.length,280);assert.equal(d.point_count,10);assert.equal(d.segments[0].run_id,points[0].event_id);
    assert.equal(d.stops[0].stop_id,points[0].event_id);assert.equal(d.stops[0].duration_seconds,8370);assert.equal(d.stops[0].overlap_seconds,270);
  });
  await t.test('deferred real COMMIT failure produces HTTP503, no points/revision and no successful ACK',async()=>{
    const suffix=failedId.replaceAll('-','');
    await pool.query(`CREATE FUNCTION synthetic_fail_${suffix}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.device_id = '${failedId}'::uuid THEN RAISE EXCEPTION 'synthetic deferred commit failure'; END IF; RETURN NEW; END $$`);
    await pool.query(`CREATE CONSTRAINT TRIGGER synthetic_fail_${suffix} AFTER INSERT ON routebook_points DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION synthetic_fail_${suffix}()`);
    const {publicApp,privateApp}=createServers(store,{devices:[{device_id:failedId,token}],now:()=> '2026-10-05T08:10:00.000Z'});
    try{const res=await publicApp.inject({url:'/v1/ingest',method:'POST',headers:{authorization:`Bearer ${token}`},payload:{version:1,device_id:failedId,lane:'latest',points:[syntheticPoint()]}});
      assert.equal(res.statusCode,503);assert.deepEqual(res.json(),{code:'database_unavailable'});assert.deepEqual(await store.snapshot(failedId),{device_id:failedId,revision:0,points:[]});
    }finally{await publicApp.close();await privateApp.close();}
  });
  await t.test('SSE snapshot, update after commit, duplicate quiet, reconnect, latest never regresses',async()=>{
    const {publicApp,privateApp}=createServers(store,{devices:[{device_id:liveId,token}],now:()=> '2026-10-05T08:10:00.000Z'});
    await privateApp.listen({host:'127.0.0.1',port:0});const address=privateApp.server.address();assert.ok(address&&typeof address!=='string');
    const connect=async()=>{const ac=new AbortController();const res=await fetch(`http://127.0.0.1:${address.port}/v1/live?device_id=${liveId}`,{signal:ac.signal});assert.equal(res.status,200);const reader=res.body!.getReader();let buffer='';
      return {ac,read:async()=>{while(!buffer.includes('\n\n')){const r=await reader.read();assert.ok(!r.done);buffer+=new TextDecoder().decode(r.value);}const at=buffer.indexOf('\n\n');const text=buffer.slice(0,at);buffer=buffer.slice(at+2);return {text,data:JSON.parse(text.split('\n').find(x=>x.startsWith('data: '))!.slice(6))};}};};
    const post=(p:any)=>publicApp.inject({url:'/v1/ingest',method:'POST',headers:{authorization:`Bearer ${token}`},payload:{version:1,device_id:liveId,lane:'latest',points:[p]}});
    let conn=await connect();
    try{
      let event=await conn.read();assert.match(event.text,/event: snapshot/);assert.equal(event.data.revision,0);assert.equal(event.data.latest.point,null);
      const p=syntheticPoint();assert.equal((await post(p)).statusCode,200);event=await conn.read();assert.match(event.text,/id: 1\nevent: update/);assert.equal(event.data.latest.point.event_id,p.event_id);assert.equal((await store.latestSnapshot(liveId)).revision,1);assert.ok(event.data.affected_dates.includes('2026-10-04'));
      await post(p);await post({...p,event_id:randomUUID(),measured_at:'2026-10-04T08:00:00.000Z'});event=await conn.read();assert.equal(event.data.revision,2);assert.equal(event.data.latest.point.event_id,p.event_id);
      conn.ac.abort();conn=await connect();event=await conn.read();assert.match(event.text,/event: snapshot/);assert.equal(event.data.revision,2);assert.deepEqual(event.data.affected_dates,[]);
    }finally{conn.ac.abort();await publicApp.close();await privateApp.close();}
  });
  await t.test('bootstrap is idempotent; store reconnection retains committed points and revision',async()=>{
    const before=await store.snapshot(concurrentId);await store.bootstrap([concurrentId]);assert.deepEqual(await store.snapshot(concurrentId),before);
    await pool.end();pool=new pg.Pool({connectionString:db});store=new PgStore(pool);assert.deepEqual(await store.snapshot(concurrentId),before);
    const container=process.env.ROUTEBOOK_TEST_RESTART_CONTAINER;
    if(container){if(!/^routebook-backend-synthetic-/.test(container))throw Error('Only dedicated synthetic container restart allowed');
      await pool.end();await promisify(execFile)('docker',['restart',container]);
      for(let i=0;i<30;i++){try{const {stdout}=await promisify(execFile)('docker',['exec',container,'pg_isready','-U','postgres']);if(stdout.includes('accepting connections'))break;}catch{}await new Promise(r=>setTimeout(r,200));}
      pool=new pg.Pool({connectionString:db});store=new PgStore(pool);assert.deepEqual(await store.snapshot(concurrentId),before);
    }else t.diagnostic('Database-container restart not requested; only process/pool reconnect verified');
  });
 }finally{await pool.end();}
});
