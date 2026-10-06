import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fixtures,MemoryStore,checkCase,syntheticPoint,fixtureDevice } from './helpers.js';
import { strictJson, validPoint, envelope } from '../src/validation.js';
import { derive, day, range, latest, affectedDates } from '../src/derive.js';
for(const c of fixtures.cases)test(`normative logic: ${c.name}`,async()=>checkCase(new MemoryStore(),c));
test('strict JSON rejects duplicate escaped keys, nested duplicates, invalid UTF8, invalid JSON and nonfinite exponent',()=>{
  for(const s of ['{"a":1,"a":2}','{"a":1,"\\u0061":2}','{"p":{"a":1,"a":2}}','{"a":01}','{"a":1e400}','[1,]','null x'])assert.throws(()=>strictJson(Buffer.from(s)));
  assert.throws(()=>strictJson(Buffer.from([0xc3,0x28])));
  assert.doesNotThrow(()=>strictJson(Buffer.from('['.repeat(10000)+'0'+']'.repeat(10000))));
  assert.deepEqual(strictJson(Buffer.from('{"b":-0.0,"a":[1e1,"x"]}')),{b:-0,a:[10,'x']});
});
test('point validation covers calendar, types, bounds, keys, old history and future tolerance',()=>{
  const now='2026-10-05T08:00:00.000Z',p=syntheticPoint();assert.ok(validPoint(p,now));
  for(const x of [{lat:'0'},{speed_mps:81},{accuracy_m:0},{accuracy_m:51},{lat:NaN},{lon:Infinity},{measured_at:'2026-02-30T00:00:00.000Z'},{measured_at:'2026-10-05T08:00:00Z'},{measured_at:'1999-12-31T23:59:59.999Z'},{measured_at:'2026-10-05T08:05:00.001Z'},{event_id:p.event_id.toUpperCase().replace('00000000','ABCDEF00')},{extra:true}])assert.equal(validPoint({...p,...x},now),false,JSON.stringify(x));
  assert.ok(validPoint({...p,measured_at:'2000-01-01T00:00:00.000Z',speed_mps:null},now));
  assert.ok(validPoint({...p,measured_at:'2026-10-05T08:05:00.000Z'},now));
});
test('empty device, zero days/months and invalid range/date',()=>{
  const s={device_id:fixtureDevice,revision:0,points:[]};assert.equal(latest(s,'2026-10-05T08:00:00.000Z').clock_state,'empty');
  const r=range(s,'2026-09-30','2026-10-02');assert.equal(r.days.length,3);assert.deepEqual(r.months,[{month:'2026-09',meters:0},{month:'2026-10',meters:0}]);
  assert.throws(()=>range(s,'2026-10-02','2026-10-01'));assert.throws(()=>range(s,'2025-01-01','2026-01-02'));assert.throws(()=>day(s,'2026-02-30'));
});
test('equal-time UUID sorting breaks continuity; speed and source changes split runs',()=>{
  const p=(x:any)=>({...syntheticPoint(),device_id:fixtureDevice,received_at:'2026-10-05T08:00:01.000Z',...x});
  const a=p({event_id:'00000000-0000-4000-8000-000000000002'}),b=p({event_id:'00000000-0000-4000-8000-000000000004'});
  assert.equal(derive([b,a]).runs.length,2);assert.equal(latest({device_id:fixtureDevice,revision:1,points:[b,a]},a.measured_at).point?.event_id,b.event_id);
  const fast=p({event_id:'00000000-0000-4000-8000-000000000005',measured_at:'2026-10-05T08:00:01.000Z',lon:1});assert.equal(derive([a,fast]).runs.length,2);
  assert.equal(derive([a,p({...fast,lon:0,segment_id:fast.event_id})]).runs.length,2);
  assert.ok(affectedDates([a]).includes('2026-10-04'));
});
test('validation before duplicate lookup and input order first wins',async()=>{
 const store=new MemoryStore(),p=syntheticPoint(),e=envelope({version:1,device_id:fixtureDevice,lane:'backfill',points:[p,p,{...p,lat:1},{...p,lat:91}]},fixtureDevice);
 assert.deepEqual((await store.ingest(e,'2026-10-05T08:10:00.000Z')).ack.results.map(x=>[x.status,x.code]),[['accepted',null],['duplicate',null],['rejected','event_conflict'],['rejected','invalid_point']]);
});
test('range summaries match day semantics including midnight stops and zero days',async()=>{
 for(const c of fixtures.cases){const store=new MemoryStore();for(const d of c.deliveries)await store.ingest(envelope(d.request,fixtureDevice),d.received_at);
  const s=await store.snapshot(fixtureDevice),r=range(s,'2026-10-03','2026-10-07');
  for(const x of r.days){const d=day(s,x.date);assert.equal(x.meters,d.meters);assert.equal(x.point_count,d.point_count);assert.equal(x.stop_count,d.stops.length);}
 }
});
