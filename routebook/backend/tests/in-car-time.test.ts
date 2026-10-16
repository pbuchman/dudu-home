import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { calculateCorrection, correctionMetrics, correctionIntervals, type PowerProfile } from '../src/in-car-time.js';
import { analytics, analyticsBounds, journeyDetail } from '../src/analytics.js';
import { day, range, dayBounds } from '../src/derive.js';
import type { Snapshot, StoredObservation, StoredPoint } from '../src/types.js';
const device=randomUUID(),segment=randomUUID(),epoch=randomUUID(),boot=randomUUID(),base=Date.parse('2026-10-14T08:00:00.000Z');
const at=(seconds:number)=>new Date(base+seconds*1000).toISOString();
const point=(seconds:number,overrides:Partial<StoredPoint>={}):StoredPoint=>({device_id:device,event_id:randomUUID(),segment_id:segment,measured_at:at(seconds),received_at:at(seconds+1),lat:0,lon:0,speed_mps:0,accuracy_m:5,...overrides});
const tail=(duration=300)=>[point(-20,{lon:-.0004,speed_mps:2.2}),point(-10,{lon:-.0002,speed_mps:2.2}),...Array.from({length:duration/5+1},(_,i)=>point(i*5))];
const profile:PowerProfile={profile_id:'synthetic-physical-v1',firmware_id:'synthetic-firmware',validation_id:'synthetic-evidence-1',validated_at:'2026-01-01T00:00:00.000Z',states:[{acc_state:0,module1_state:0,state:'off'},{acc_state:1,module1_state:1,state:'on'}]};
const observation=(seconds:number,overrides:Partial<StoredObservation>={}):StoredObservation=>({device_id:device,event_id:randomUUID(),observer_epoch:epoch,sequence:seconds/30,observed_at:at(seconds),received_at:at(seconds+1),elapsed_realtime_ms:100000+seconds*1000,boot_id:boot,kind:'snapshot',acc_state:0,module1_state:0,interpretation_profile:profile.profile_id,firmware_id:profile.firmware_id,...overrides});
const calculate=(points=tail(),events:StoredObservation[]=[],profiles:PowerProfile[]=[],now=2000)=>calculateCorrection(points,events,profiles,at(now));
const metric=(...args:Parameters<typeof calculate>)=>correctionMetrics(calculate(...args));

test('strict historical270..329.999 tail is removed from end, estimated; open ends provisional, closed gaps stable',()=>{
  for(const duration of [270,300,325]){const m=metric(tail(duration));assert.equal(m.recorded_seconds,duration+20);assert.equal(m.excluded_tail_seconds,Math.min(300,duration));assert.equal(m.confirmed_excluded_seconds,0);assert.equal(m.provisional_excluded_seconds,m.estimated_excluded_seconds);assert.equal(m.in_car_seconds,m.recorded_seconds-m.excluded_tail_seconds);}
  for(const duration of [265,330,600])assert.equal(metric(tail(duration)).excluded_tail_seconds,0);
  const m=metric([...tail(),point(900,{segment_id:randomUUID()})]);assert.equal(m.estimated_excluded_seconds,300);assert.equal(m.provisional_excluded_seconds,0);
});
test('clock-only maturity requires both measured and receipt ages, advertises next refresh and recomputes',()=>{
  const points=tail();points.at(-1)!.received_at=at(1000);
  assert.equal(metric(points,[],[],1599).excluded_tail_seconds,0);
  assert.equal(calculate(points,[],[],1599).next_refresh_at,at(1600));
  assert.equal(metric(points,[],[],1600).provisional_excluded_seconds,300);
  assert.equal(metric(tail(),[],[],899).excluded_tail_seconds,0);
});
test('late continuing GPS, filled gap or extended stop can undo previous estimate without changing raw records',()=>{
  const points=tail(),before=JSON.stringify(points);assert.equal(metric(points).excluded_tail_seconds,300);
  for(const newPoints of [[point(305,{speed_mps:3,lon:.0002})],[point(600,{segment_id:randomUUID()})],Array.from({length:12},(_,i)=>point(305+i*5))])assert.equal(metric([...points,...newPoints]).excluded_tail_seconds,0);
  assert.equal(JSON.stringify(points),before);
});
test('whole existing observed stop reliability: bad accuracy/null/weak speeds/radius/sparse intervals cannot create valid suffix',()=>{
  const bads=[(p:StoredPoint[])=>{p[35].accuracy_m=26;},(p:StoredPoint[])=>{p[35].speed_mps=null;},(p:StoredPoint[])=>{for(const x of p.slice(2))x.speed_mps=.4;},(p:StoredPoint[])=>{p[30].lon=.00016;},(p:StoredPoint[])=>{for(const x of p.slice(2))x.accuracy_m=11;},(p:StoredPoint[])=>{p.splice(20,3);}];
  for(const mutate of bads){const p=tail();mutate(p);assert.equal(metric(p).excluded_tail_seconds,0);}
  const long=tail(600);long[60].accuracy_m=30;assert.equal(metric(long).excluded_tail_seconds,0);
  const radius=tail(600);for(const p of radius.slice(62))p.lon=.00016;assert.equal(metric(radius).excluded_tail_seconds,0,'tighter15m reset cannot truncate existing25m stop');
});
test('two fixed-coordinate speed spikes, prior different segment and old driving cannot stand in for preceding motion',()=>{
  const p=tail();p[0].lon=0;p[1].lon=0;assert.equal(metric(p).excluded_tail_seconds,0);
  const q=tail();q[0].segment_id=randomUUID();q[1].segment_id=randomUUID();assert.equal(metric(q).excluded_tail_seconds,0);
  const r=tail();r[0].measured_at=at(-200);r[1].measured_at=at(-190);assert.equal(metric(r).excluded_tail_seconds,0);
});
test('raw diagnostic or wrong firmware mappings never confirm, known ON stationary waits veto heuristic',()=>{
  const events=[observation(0,{acc_state:1,module1_state:1})];
  assert.equal(metric(tail(),events).estimated_excluded_seconds,300);
  assert.equal(metric(tail(),events,[profile]).excluded_tail_seconds,0);
  assert.equal(metric(tail(),events.map(e=>({...e,firmware_id:'other'})),[profile]).estimated_excluded_seconds,300);
  assert.equal(metric(tail(),[observation(0)],[{...profile,states:[{acc_state:0,module1_state:null,state:'off'}]}]).confirmed_excluded_seconds,0);
});
test('OFF needs3s debounce and overlaps only qualified stationary GPS, never retrospective or beyond45s',()=>{
  const points=Array.from({length:25},(_,i)=>point(i*5));
  assert.equal(metric(points,[observation(0)],[profile],2).confirmed_excluded_seconds,0);
  assert.equal(metric(points,[observation(0)],[profile],3).confirmed_excluded_seconds,0,'second GPS fix is not yet observed');
  assert.equal(metric(points,[observation(0)],[profile],5).confirmed_excluded_seconds,5);
  assert.equal(metric(points,[observation(30)],[profile]).confirmed_excluded_seconds,45);
  assert.equal(correctionIntervals(calculate(points,[observation(30)],[profile]),0,Infinity)[0].start_at,at(30));
  const poor=points.map(p=>({...p,accuracy_m:26}));assert.equal(metric(poor,[observation(0)],[profile]).confirmed_excluded_seconds,0);
});
test('OFF snapshots maintain bounded coverage; ON/lost/epoch/boot/sequence/clock anomalies break it',()=>{
  const points=Array.from({length:25},(_,i)=>point(i*5)),events=[0,30,60,90].map(s=>observation(s));
  assert.equal(metric(points,events,[profile]).confirmed_excluded_seconds,120);
  const cases=[{kind:'observer_lost' as const},{acc_state:1,module1_state:1},{observer_epoch:randomUUID()},{boot_id:randomUUID()},{sequence:9},{elapsed_realtime_ms:110000}];
  for(const change of cases){const m=metric(points,[events[0],{...events[1],...change}],[profile]);assert.ok(m.confirmed_excluded_seconds<120);assert.ok(m.confirmed_excluded_seconds<=75);}
  const lostOn=[observation(0),observation(30,{sequence:2})];const c=calculate(points,lostOn,[profile]);assert.equal(c.confirmed[0].start,base+30000,'missing sequence cannot retain preceding OFF');
  const backward=[observation(30,{sequence:0}),observation(0,{sequence:1})];assert.equal(metric(points,backward,[profile]).confirmed_excluded_seconds,0);
  const contradictory=[observation(0),observation(0,{observer_epoch:randomUUID(),acc_state:1,module1_state:1})];assert.equal(metric(points,contradictory,[profile]).confirmed_excluded_seconds,0);
});
test('contradictory moving GPS breaks OFF until next snapshot; trusted holes receive no heuristic fill',()=>{
  const points=tail();points.find(p=>p.measured_at===at(15))!.speed_mps=3;
  const c=calculate(points,[observation(0),observation(30)],[profile]);assert.ok(c.confirmed.every(i=>i.end<=base+15000||i.start>=base+30000));assert.equal(c.estimated.length,0);
  const missing=metric(tail(),[observation(0)],[profile]);assert.equal(missing.confirmed_excluded_seconds,45);assert.equal(missing.estimated_excluded_seconds,0);
});
test('canonical union across segments avoids double subtraction; Warsaw midnight and DST clip identically',()=>{
  const original=tail(),duplicated=[...original,...original.map(p=>({...p,event_id:randomUUID(),segment_id:randomUUID()}))];
  // Interleaving technical segments cannot inflate raw or excluded seconds.
  const c=calculate(duplicated,[observation(0)],[profile]);const m=correctionMetrics(c);assert.equal(m.recorded_seconds,320);assert.equal(m.confirmed_excluded_seconds,45);
  for(const date of ['2026-03-29','2026-10-25']){const boundary=dayBounds(date).start,shift=boundary-base-150000;
    const p=tail().map(p=>({...p,measured_at:new Date(Date.parse(p.measured_at)+shift).toISOString(),received_at:new Date(Date.parse(p.received_at)+shift).toISOString()}));
    const correction=calculateCorrection(p,[],[],new Date(boundary+2000000).toISOString()),s:Snapshot={device_id:device,revision:1,points:p,correction};
    const d=day(s,date),r=range(s,date,date);assert.equal(d.excluded_tail_seconds,150);assert.equal(r.excluded_tail_seconds,d.excluded_tail_seconds);assert.equal(r.months[0].in_car_seconds,d.in_car_seconds);
  }
});
test('day/range/period/journey/detail/comparison share correction while preserving elapsed and averages',()=>{
  const points=tail(),correction=calculate(points),s={device_id:device,revision:1,points,correction,places:[{place_id:randomUUID(),name:'Synthetic start',lat:0,lon:-.0004,radius_m:25},{place_id:randomUUID(),name:'Synthetic end',lat:0,lon:0,radius_m:25}],metadata_revision:0,has_before:false,has_after:false};
  const d=day(s,'2026-10-14'),r=range(s,'2026-10-14','2026-10-14'),a=analytics(s,'2026-10-14','2026-10-14');
  assert.equal(a.totals.in_car_seconds,d.in_car_seconds);assert.equal(a.weeks[0].excluded_tail_seconds,300);assert.equal(a.months[0].excluded_tail_seconds,r.excluded_tail_seconds);
  assert.equal(a.journeys[0].recorded_seconds,320);assert.equal(a.journeys[0].in_car_seconds,20);assert.equal(a.journeys[0].elapsed_seconds,320);
  const detail=journeyDetail(s,'2026-10-14','2026-10-14',a.journeys[0].journey_id);assert.equal(detail.journey.in_car_seconds,20);assert.equal(detail.correction_intervals.length,1);
  assert.equal(a.comparisons[0].median_in_car_seconds,20);assert.equal(a.comparisons[0].median_seconds,320);assert.equal(a.comparisons[0].median_recorded_seconds,320);
});
