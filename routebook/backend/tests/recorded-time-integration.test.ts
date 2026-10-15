import { test } from 'node:test';
import assert from 'node:assert/strict';
import pg from 'pg';
import { randomUUID } from 'node:crypto';
import { PgStore } from '../src/store.js';
import { day, dayBounds, nextDate, range } from '../src/derive.js';
import { analytics, analyticsBounds } from '../src/analytics.js';
import { recordedDays, recordedTimeSql } from '../src/recorded-time.js';
import { createServers } from '../src/server.js';
import { syntheticPoint, token } from './helpers.js';
import type { Point } from '../src/types.js';
const db=process.env.ROUTEBOOK_TEST_DB_URL;

test('recorded time PostGIS: full archive SQL parity, scoped context, late backfill, DST and repeatable read', {skip:!db,timeout:120000},async(t)=>{
  if(!db||!new URL(db).pathname.startsWith('/routebook_synthetic'))throw Error('Dedicated synthetic database required');
  const pool=new pg.Pool({connectionString:db,max:6}),store=new PgStore(pool);
  const point=(time:string,segment:string,overrides:Partial<Point>={}):Point=>({...syntheticPoint(),event_id:randomUUID(),segment_id:segment,measured_at:time,...overrides});
  const post=(device:string,points:Point[])=>store.ingest({version:1,device_id:device,lane:'backfill',points},'2027-01-02T20:00:00.000Z');
  try{
    await t.test('interleaved midnight segments and duplicate timestamps are independent of geometry neighbours',async()=>{
      const device=randomUUID(),a=randomUUID(),b=randomUUID(),boundary=dayBounds('2026-11-01').start;await store.bootstrap([device]);
      const p=(seconds:number,segment=a)=>point(new Date(boundary+seconds*1000).toISOString(),segment,{lon:seconds%2?10:0,speed_mps:null,accuracy_m:50});
      const points=[p(-25),p(5),p(35),p(-15,b),p(15,b),p(45,b),p(5),p(75),p(75.001)];
      await post(device,points);const full=await store.snapshot(device),expected=recordedDays(full,'2026-10-31','2026-11-01');
      assert.deepEqual(expected,{'2026-10-31':25,'2026-11-01':45.001});
      for(const date of ['2026-10-31','2026-11-01']){const scoped=await store.snapshot(device,dayBounds(date));assert.equal(day(scoped,date).recorded_seconds,expected[date]);}
      const bounds={start_at:dayBounds('2026-10-31').start_at,end_at:dayBounds('2026-11-01').end_at};
      const r=range(await store.snapshot(device,bounds),'2026-10-31','2026-11-01');assert.equal(r.recorded_seconds,70.001);
      const as=await store.analyticsSnapshot(device,analyticsBounds('2026-10-31','2026-11-01')),aresult=analytics(as,'2026-10-31','2026-11-01');
      assert.equal(aresult.totals.recorded_seconds,r.recorded_seconds);assert.equal(aresult.totals.point_count,points.length);assert.deepEqual(aresult.days.map(d=>d.recorded_seconds),r.days.map(d=>d.recorded_seconds));
    });
    await t.test('no-data zero, exactly30s boundary context and 30.001s exclusion',async()=>{
      const device=randomUUID(),segment=randomUUID(),boundary=dayBounds('2026-10-14').start;await store.bootstrap([device]);
      assert.equal(day(await store.snapshot(device,dayBounds('2026-10-14')),'2026-10-14').recorded_seconds,0);
      const points=[-30,0,30,60.001,90.002].map(seconds=>point(new Date(boundary+seconds*1000).toISOString(),segment));
      await post(device,points);assert.equal(day(await store.snapshot(device,dayBounds('2026-10-13')),'2026-10-13').recorded_seconds,30);
      assert.equal(day(await store.snapshot(device,dayBounds('2026-10-14')),'2026-10-14').recorded_seconds,30);
    });
    await t.test('late backfill and duplicate retries recompute time without changing previous raw points',async()=>{
      const device=randomUUID(),segment=randomUUID();await store.bootstrap([device]);const base=Date.parse('2026-10-14T08:00:00.000Z');
      const points=[0,30,60].map(seconds=>point(new Date(base+seconds*1000).toISOString(),segment));await post(device,[points[0],points[2]]);
      let d=day(await store.snapshot(device,dayBounds('2026-10-14')),'2026-10-14');assert.equal(d.revision,1);assert.equal(d.recorded_seconds,0);
      await post(device,[points[1]]);d=day(await store.snapshot(device,dayBounds('2026-10-14')),'2026-10-14');assert.equal(d.revision,2);assert.equal(d.recorded_seconds,60);
      const unchanged=await store.snapshot(device);await post(device,points);assert.deepEqual(await store.snapshot(device),unchanged);
    });
    await t.test('both DST transitions match real elapsed UTC seconds and adjacent-day sums',async()=>{
      for(const date of ['2026-03-29','2026-10-25']){
        const device=randomUUID(),segment=randomUUID(),bounds=dayBounds(date);await store.bootstrap([device]);
        await pool.query(`INSERT INTO routebook_points(device_id,event_id,segment_id,measured_at,received_at,lat,lon,accuracy_m,speed_mps)
          SELECT $1::uuid,md5($1::text||i::text)::uuid,$2::uuid,$3::timestamptz+i*interval '30 seconds','2027-01-02',0,0,5,0 FROM generate_series(0,$4::int) i`,[device,segment,bounds.start_at,bounds.seconds/30]);
        const d=day(await store.snapshot(device,bounds),date);assert.equal(d.recorded_seconds,bounds.seconds);
        const from=nextDate(date,-1),to=nextDate(date),r=range(await store.snapshot(device,{start_at:dayBounds(from).start_at,end_at:dayBounds(to).end_at}),from,to);
        assert.deepEqual(r.days.map(d=>d.recorded_seconds),[0,bounds.seconds,0]);assert.equal(r.recorded_seconds,bounds.seconds);
      }
    });
    await t.test('concurrent ingest after revision read cannot mix new time with old distance/revision',async()=>{
      for(const analyticsRead of [false,true]){
        const device=randomUUID(),segment=randomUUID();await store.bootstrap([device]);const base=Date.parse('2026-10-14T08:00:00.000Z');
        const points=[0,30,60].map(seconds=>point(new Date(base+seconds*1000).toISOString(),segment,{lon:seconds*.00001,speed_mps:2}));await post(device,points.slice(0,2));
        let injected=false;const connect=pool.connect.bind(pool);
        const wrapped={connect:async()=>{const c=await connect();return {query:async(sql:string,args?:unknown[])=>{
          const result=await c.query(sql,args);
          if(!injected&&sql.includes('FROM routebook_devices')){injected=true;await post(device,[points[2]]);}return result;
        },release:()=>c.release()};}} as unknown as pg.Pool;
        const observed=new PgStore(wrapped),bounds=dayBounds('2026-10-14');
        if(analyticsRead){const old=analytics(await observed.analyticsSnapshot(device,analyticsBounds('2026-10-14','2026-10-14')),'2026-10-14','2026-10-14');assert.equal(old.source_revision,1);assert.equal(old.totals.recorded_seconds,30);assert.equal(old.totals.point_count,2);}
        else{const old=day(await observed.snapshot(device,bounds),'2026-10-14');assert.equal(old.revision,1);assert.equal(old.recorded_seconds,30);assert.equal(old.point_count,2);assert.ok(old.meters>0);}
        assert.equal(injected,true);const current=day(await store.snapshot(device,bounds),'2026-10-14');assert.equal(current.revision,2);assert.equal(current.recorded_seconds,60);assert.equal(current.point_count,3);
      }
    });
    await t.test('bounded +/-30s raw SQL shares transaction; calculation failures return503 without zero',async()=>{
      const device=randomUUID();await store.bootstrap([device]);const queries:{sql:string;args?:unknown[]}[]=[],connect=pool.connect.bind(pool);let fail=false;
      const wrapped={connect:async()=>{const c=await connect();return {query:async(sql:string,args?:unknown[])=>{queries.push({sql,args});if(fail&&sql===recordedTimeSql)throw Error('synthetic calculation failure');return c.query(sql,args);},release:()=>c.release()};}} as unknown as pg.Pool;
      const observed=new PgStore(wrapped),bounds=dayBounds('2026-10-14');await observed.snapshot(device,bounds);
      assert.ok(queries[0].sql.includes('REPEATABLE READ'));const q=queries.find(q=>q.sql===recordedTimeSql)!;assert.deepEqual(q.args,[device,bounds.start_at,bounds.end_at]);assert.match(q.sql,/interval '30 seconds'/);assert.equal(queries.at(-1)!.sql,'COMMIT');
      fail=true;const {privateApp,publicApp}=createServers(observed,{devices:[{device_id:device,token}]});
      try{const response=await privateApp.inject(`/v1/day?device_id=${device}&date=2026-10-14`);assert.equal(response.statusCode,503);assert.deepEqual(response.json(),{code:'database_unavailable'});assert.equal(queries.at(-1)!.sql,'ROLLBACK');}finally{await privateApp.close();await publicApp.close();}
    });
  }finally{await pool.end();}
});

test('maximum366-day range and dense day use bounded full archive efficiently',{skip:!db,timeout:120000},async(t)=>{
  if(!db||!new URL(db).pathname.startsWith('/routebook_synthetic'))throw Error('Dedicated synthetic database required');
  const pool=new pg.Pool({connectionString:db}),store=new PgStore(pool),device=randomUUID();
  try{
    await store.bootstrap([device]);
    // Two hours of5-second observations on every day, plus a full24h first day.
    // 543245 archived points exercise the actual HTTP path and preserve all raw rows.
    await pool.query(`INSERT INTO routebook_points(device_id,event_id,segment_id,measured_at,received_at,lat,lon,accuracy_m,speed_mps)
      SELECT $1::uuid,md5($1::text||':'||d::text||':'||i::text)::uuid,md5($1::text||':'||d::text)::uuid,
        (('2026-01-01'::date+d)::timestamp AT TIME ZONE 'Europe/Warsaw') + i*interval '5 seconds','2027-01-02',0,0,5,0
      FROM generate_series(0,365) d CROSS JOIN LATERAL generate_series(0,CASE WHEN d=0 THEN 17279 ELSE 1440 END) i`,[device]);
    await pool.query('ANALYZE routebook_points');
    const {privateApp,publicApp}=createServers(store,{devices:[{device_id:device,token}]});
    try{
      let started=performance.now();const response=await privateApp.inject(`/v1/range?device_id=${device}&from=2026-01-01&to=2027-01-01`),rangeMs=performance.now()-started;
      assert.equal(response.statusCode,200,response.body);const result=response.json();assert.equal(result.days.length,366);assert.equal(result.recorded_seconds,86395+365*7200);assert.equal(result.days.reduce((n:number,d:any)=>n+d.point_count,0),543245);
      assert.equal(result.days.reduce((n:number,d:any)=>n+d.recorded_seconds,0),result.recorded_seconds);assert.equal(result.months.reduce((n:number,d:any)=>n+d.recorded_seconds,0),result.recorded_seconds);assert.ok(rangeMs<20000,`max range took ${rangeMs}ms`);
      started=performance.now();const d=await privateApp.inject(`/v1/day?device_id=${device}&date=2026-01-01`),dayMs=performance.now()-started;assert.equal(d.statusCode,200);assert.equal(d.json().point_count,17280);assert.equal(d.json().recorded_seconds,86395);assert.ok(dayMs<5000,`dense day took ${dayMs}ms`);
      const bounds=dayBounds('2026-06-01'),plan=await pool.query('EXPLAIN (ANALYZE,FORMAT JSON) '+recordedTimeSql,[device,bounds.start_at,bounds.end_at]);
      assert.match(JSON.stringify(plan.rows),/Index|Bitmap/,'selective day timestamps should use the existing measured index');
      assert.equal(Number((await pool.query('SELECT count(*) FROM routebook_points WHERE device_id=$1',[device])).rows[0].count),543245);
      t.diagnostic(`Synthetic PostGIS17/amd64 Docker:366days/543245points ${rangeMs.toFixed(0)}ms;17280point day ${dayMs.toFixed(0)}ms;selective recorded SQL indexed`);
    }finally{await privateApp.close();await publicApp.close();}
  }finally{await pool.end();}
});
