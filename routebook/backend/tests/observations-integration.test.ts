import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import pg from 'pg';
import { PgStore } from '../src/store.js';
import { createServers } from '../src/server.js';
import { calculateCorrection, correctionMetrics, type PowerProfile } from '../src/in-car-time.js';
import { analytics, analyticsBounds } from '../src/analytics.js';
import { day, range, dayBounds } from '../src/derive.js';
import { token } from './helpers.js';
import type { RadioObservation } from '../../contract/analytics.js';
import type { Point } from '../src/types.js';
const db=process.env.ROUTEBOOK_TEST_DB_URL;
test('radio observation PostGIS: migration, immutable delivery, durable ACK, source revision, full archive parity and SSE',{skip:!db,timeout:120000},async(t)=>{
  if(!db||!new URL(db).pathname.startsWith('/routebook_synthetic'))throw Error('Dedicated synthetic database required');
  const pool=new pg.Pool({connectionString:db,max:8}),base=Date.parse('2026-10-14T08:00:00.000Z'),at=(s:number)=>new Date(base+s*1000).toISOString();
  const profile:PowerProfile={profile_id:'synthetic-'+randomUUID(),firmware_id:'synthetic-firmware',validation_id:'synthetic-physical-validation',validated_at:'2026-01-01T00:00:00.000Z',states:[{acc_state:0,module1_state:0,state:'off'},{acc_state:1,module1_state:1,state:'on'}]};
  let now=at(2000);const store=new PgStore(pool,undefined,[profile],()=>now);
  const raw=(s:number,overrides:Partial<RadioObservation>={}):RadioObservation=>({event_id:randomUUID(),observer_epoch:randomUUID(),sequence:0,observed_at:at(s),elapsed_realtime_ms:100000+s*1000,boot_id:randomUUID(),kind:'snapshot',acc_state:0,module1_state:0,interpretation_profile:profile.profile_id,firmware_id:profile.firmware_id,...overrides});
  const post=(device:string,events:RadioObservation[])=>store.ingest({version:2,device_id:device,lane:'backfill',events},at(2000));
  const gps=(device:string,points:Point[])=>store.ingest({version:1,device_id:device,lane:'backfill',points},at(2000));
  const points=(duration=300)=>{const segment=randomUUID(),p=(s:number,overrides:Partial<Point>={}):Point=>({event_id:randomUUID(),segment_id:segment,measured_at:at(s),lat:0,lon:0,speed_mps:0,accuracy_m:5,...overrides});return [p(-20,{lon:-.0004,speed_mps:2.2}),p(-10,{lon:-.0002,speed_mps:2.2}),...Array.from({length:duration/5+1},(_,i)=>p(i*5))];};
  try{
    await t.test('additive bootstrap preserves GPS rows; audited profile IDs cannot be rewritten',async()=>{
      const device=randomUUID();await store.bootstrap([device]);await store.assertPowerProfiles();await gps(device,points());const before=await store.snapshot(device);await store.bootstrap([device]);assert.deepEqual(await store.snapshot(device),before);
      const changed=new PgStore(pool,undefined,[{...profile,validation_id:'other-validation'}]);await assert.rejects(changed.bootstrap([device]),/immutable/);await assert.rejects(changed.assertPowerProfiles(),/matching immutable/);
      const tables=await pool.query("SELECT to_regclass('routebook_points') points,to_regclass('routebook_observations') observations");assert.ok(tables.rows[0].points&&tables.rows[0].observations);
    });
    await t.test('mixed version archive, concurrent retries, immutable conflicts and first receipt survive reconnect',async()=>{
      const device=randomUUID();await store.bootstrap([device]);const p=points()[0];await gps(device,[p]);const e=raw(0);
      const responses=await Promise.all(Array.from({length:6},()=>post(device,[e])));assert.equal(responses.filter(r=>r.ack.results[0].status==='accepted').length,1);assert.equal(responses.filter(r=>r.event).length,1);assert.ok(responses.every(r=>r.ack.version===2&&r.ack.revision===2));
      const conflict=await post(device,[{...e,acc_state:1}]);assert.equal(conflict.ack.results[0].code,'event_conflict');assert.equal(conflict.event,null);
      const bad=await store.ingest({version:2,device_id:device,lane:'backfill',events:[{...e,sequence:'0'},raw(30)]},at(2000));assert.deepEqual(bad.ack.results.map(r=>r.code),['invalid_observation',null]);
      await assert.rejects(pool.query('UPDATE routebook_observations SET acc_state=1 WHERE device_id=$1',[device]),/immutable/);
      const snapshot=await new PgStore(pool).snapshot(device);assert.equal(snapshot.points.length,1);assert.equal(snapshot.observations!.length,2);assert.equal(snapshot.observations![0].received_at,at(2000));
    });
    await t.test('deferred observation COMMIT failure returns503 without ACK/revision/event or durable row',async()=>{
      const device=randomUUID();await store.bootstrap([device]);const suffix=device.replaceAll('-','');
      await pool.query(`CREATE FUNCTION synthetic_observation_fail_${suffix}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.device_id='${device}'::uuid THEN RAISE EXCEPTION 'synthetic deferred failure'; END IF; RETURN NEW; END $$`);
      await pool.query(`CREATE CONSTRAINT TRIGGER synthetic_observation_fail_${suffix} AFTER INSERT ON routebook_observations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION synthetic_observation_fail_${suffix}()`);
      const {publicApp,privateApp}=createServers(store,{devices:[{device_id:device,token}],now:()=>at(2000)});
      try{const response=await publicApp.inject({method:'POST',url:'/v1/ingest',headers:{authorization:`Bearer ${token}`},payload:{version:2,device_id:device,lane:'latest',events:[raw(0)]}});assert.equal(response.statusCode,503);assert.deepEqual(response.json(),{code:'database_unavailable'});assert.equal((await store.snapshot(device)).revision,0);assert.equal(Number((await pool.query('SELECT count(*) FROM routebook_observations WHERE device_id=$1',[device])).rows[0].count),0);}finally{await publicApp.close();await privateApp.close();}
    });
    await t.test('full pure archive/scoped SQL/day/range/analytics agree; late ON undoes heuristic; received clock maturity refreshes',async()=>{
      const device=randomUUID();await store.bootstrap([device]);await gps(device,points());
      now=at(2599);let d=day(await store.snapshot(device,dayBounds('2026-10-14')),'2026-10-14');assert.equal(d.excluded_tail_seconds,0);assert.equal(d.correction_next_refresh_at,at(2600));
      now=at(2600);d=day(await store.snapshot(device,dayBounds('2026-10-14')),'2026-10-14');assert.equal(d.provisional_excluded_seconds,300);
      const full=await store.snapshot(device),pure=correctionMetrics(calculateCorrection(full.points,full.observations??[],[profile],now));assert.equal(pure.in_car_seconds,d.in_car_seconds);
      const a=analytics(await store.analyticsSnapshot(device,analyticsBounds('2026-10-14','2026-10-14')),'2026-10-14','2026-10-14');assert.equal(a.totals.in_car_seconds,d.in_car_seconds);
      const previousRevision=d.revision;const update=await post(device,[raw(0,{acc_state:1,module1_state:1})]);assert.equal(update.ack.revision,previousRevision+1);assert.ok(update.event!.affected_dates.includes('2026-10-14'));
      d=day(await store.snapshot(device,dayBounds('2026-10-14')),'2026-10-14');assert.equal(d.excluded_tail_seconds,0);assert.equal(d.recorded_seconds,320);assert.equal(d.in_car_seconds,320);
      const r=range(await store.snapshot(device,{start_at:dayBounds('2026-10-14').start_at,end_at:dayBounds('2026-10-14').end_at}),'2026-10-14','2026-10-14');assert.equal(r.in_car_seconds,d.in_car_seconds);
    });
    await t.test('correction context outlives bounded journey/map windows; unrelated older archives do not affect pure parity',async()=>{
      const device=randomUUID();await store.bootstrap([device]);const p=points();await gps(device,p);await gps(device,[{...points()[0],measured_at:at(900),segment_id:randomUUID()}]);
      const bounds={start_at:at(150),end_at:at(301)},snapshot=await store.snapshot(device,bounds),full=await store.snapshot(device),pure=correctionMetrics(calculateCorrection(full.points,[],[],at(3000)),base+150000,base+301000);
      const scoped=correctionMetrics(snapshot.correction!,base+150000,base+301000);assert.equal(scoped.excluded_tail_seconds,pure.excluded_tail_seconds);assert.equal(scoped.excluded_tail_seconds,150);
      await gps(device,[{...points()[0],measured_at:'2026-01-01T00:00:00.000Z',segment_id:randomUUID()}]);assert.equal(correctionMetrics((await store.snapshot(device,bounds)).correction!,base+150000,base+301000).excluded_tail_seconds,150);
    });
    await t.test('same-timestamp overlap cannot hide next5s fix; unrelated receipt does not change day/range/analytics maturity',async()=>{
      const device=randomUUID();await store.bootstrap([device]);await gps(device,points());now=at(3000);
      await gps(device,[{...points()[0],measured_at:at(300),segment_id:randomUUID()},{...points()[0],measured_at:at(305),segment_id:randomUUID()}]);
      const bounds=dayBounds('2026-10-14'),s=await store.snapshot(device,bounds),full=await store.snapshot(device);assert.equal(correctionMetrics(s.correction!).excluded_tail_seconds,0);assert.equal(correctionMetrics(calculateCorrection(full.points,[],[],now)).excluded_tail_seconds,0);
      const other=randomUUID();await store.bootstrap([other]);await gps(other,points());now=at(2600);
      await store.ingest({version:1,device_id:other,lane:'latest',points:[{...points()[0],measured_at:'2026-01-01T00:00:00.000Z',segment_id:randomUUID()}]},at(2599));
      const d=day(await store.snapshot(other,bounds),'2026-10-14'),r=range(await store.snapshot(other,bounds),'2026-10-14','2026-10-14'),a=analytics(await store.analyticsSnapshot(other,analyticsBounds('2026-10-14','2026-10-14')),'2026-10-14','2026-10-14');assert.equal(d.provisional_excluded_seconds,300);assert.equal(r.excluded_tail_seconds,d.excluded_tail_seconds);assert.equal(a.totals.excluded_tail_seconds,d.excluded_tail_seconds);
    });
    await t.test('power-only SSE increments revision and date invalidation with same latest GPS; duplicate stays quiet',async()=>{
      const device=randomUUID();await store.bootstrap([device]);await gps(device,[points()[0]]);
      const {publicApp,privateApp}=createServers(store,{devices:[{device_id:device,token}],now:()=>at(2000)});await privateApp.listen({host:'127.0.0.1',port:0});const address=privateApp.server.address();assert.ok(address&&typeof address!=='string');
      const abort=new AbortController(),response=await fetch(`http://127.0.0.1:${address.port}/v1/live?device_id=${device}`,{signal:abort.signal}),reader=response.body!.getReader();let buffer='';
      const read=async()=>{while(!buffer.includes('\n\n')){const r=await reader.read();buffer+=new TextDecoder().decode(r.value);}const at=buffer.indexOf('\n\n'),text=buffer.slice(0,at);buffer=buffer.slice(at+2);return JSON.parse(text.split('\n').find(s=>s.startsWith('data: '))!.slice(6));};
      try{const initial=await read(),event=raw(0),send=()=>publicApp.inject({method:'POST',url:'/v1/ingest',headers:{authorization:`Bearer ${token}`},payload:{version:2,device_id:device,lane:'latest',events:[event]}});await send();const update=await read();assert.equal(update.revision,initial.revision+1);assert.deepEqual(update.latest.point,initial.latest.point);assert.ok(update.affected_dates.includes('2026-10-14'));assert.equal((await send()).json().revision,update.revision);}finally{abort.abort();await publicApp.close();await privateApp.close();}
    });
  }finally{await pool.end();}
});
