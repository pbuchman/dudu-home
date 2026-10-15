import { test } from 'node:test';
import { analyticsExample } from '../../contract/analytics-example.js';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { analytics, type AnalyticsSnapshot } from '../src/analytics.js';
import { day, dayBounds, nextDate, range } from '../src/derive.js';
import { recordedDays, recordedIntervals, recordedTimeDerivation } from '../src/recorded-time.js';
import type { Snapshot, StoredPoint } from '../src/types.js';
import { createServers } from '../src/server.js';
import { fixtureDevice, MemoryStore, syntheticPoint, token } from './helpers.js';
const id=(n:number)=>`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
const epoch=Date.parse('2026-10-14T08:00:00.000Z');
const point=(seconds:number,segment=1,n=seconds*1000):StoredPoint=>({...syntheticPoint(),device_id:fixtureDevice,received_at:'2026-10-15T20:00:00.000Z',event_id:id(n+10000000),segment_id:id(segment),measured_at:new Date(epoch+seconds*1000).toISOString()});
const snapshot=(points:StoredPoint[]):AnalyticsSnapshot=>({device_id:fixtureDevice,revision:4,metadata_revision:0,has_before:false,has_after:false,places:[],points});
const summary=(points:StoredPoint[])=>range(snapshot(points),'2026-10-14','2026-10-14');

test('recorded time includes 20m movement, 15m stationary and 10m movement; filters and journeys do not remove it',()=>{
  const points=Array.from({length:91},(_,i)=>({...point(i*30),speed_mps:i<40||i>70?null:0,accuracy_m:50,lon:i<40||i>70?(i%2)*10:0}));
  const s=snapshot(points),d=day(s,'2026-10-14'),r=summary(points),a=analytics(s,'2026-10-14','2026-10-14');
  assert.equal(d.recorded_seconds,2700);assert.equal(r.recorded_seconds,2700);assert.equal(a.totals.recorded_seconds,2700);
  assert.equal(a.totals.point_count,91);assert.equal(a.days[0].point_count,91);
  assert.deepEqual(d.recorded_time_derivation,recordedTimeDerivation);assert.deepEqual(a.recorded_time_derivation,recordedTimeDerivation);
  assert.ok(a.totals.elapsed_seconds<2700,'existing journey elapsed excludes boundary parking and rejected geometry');
});

test('exactly30s qualifies, 30.001s and segment boundaries do not; no edge padding or inferred hour',()=>{
  assert.equal(summary([point(0),point(30)]).recorded_seconds,30);
  assert.equal(summary([point(0),point(30.001)]).recorded_seconds,0);
  assert.equal(summary([point(0),point(30,2)]).recorded_seconds,0);
  assert.equal(summary([point(0),point(30),point(3630),point(3660)]).recorded_seconds,60);
  for(const points of [[],[point(0)]])assert.equal(summary(points).recorded_seconds,0);
});

test('deduplication is per timestamp/segment; interleaved and nested segments are unioned without double time',()=>{
  const points=[point(0),point(30),point(60),point(0,1,2),point(30,1,3),point(5,2),point(20,2),point(40,2),point(65,2),point(15,3),point(25,3)];
  assert.equal(summary(points.reverse()).recorded_seconds,65);
  assert.deepEqual(recordedIntervals(points),[{start:epoch,end:epoch+65000}]);
  const unrelated=point(10,4);assert.equal(summary([point(0),unrelated,point(30)]).recorded_seconds,30);
  assert.equal(summary([point(0),point(30,2),point(60)]).recorded_seconds,0,'interleaving must not bridge 60s within segment');
});

test('midnight clipping, partial months, range/day/analytics periods and duplicate raw counts agree',()=>{
  const boundary=dayBounds('2026-11-01').start;
  const points=[-20,-10,0,10,20].map((delta,i)=>({...point(i),measured_at:new Date(boundary+delta*1000).toISOString()}));
  points.push({...points[2],event_id:id(222)});
  const s=snapshot(points),r=range(s,'2026-10-31','2026-11-01'),a=analytics(s,'2026-10-31','2026-11-01');
  assert.equal(r.recorded_seconds,40);assert.deepEqual(r.days.map(d=>d.recorded_seconds),[20,20]);assert.deepEqual(r.months.map(m=>m.recorded_seconds),[20,20]);
  assert.deepEqual(a.days.map(d=>d.recorded_seconds),[20,20]);assert.equal(a.weeks.reduce((n,d)=>n+d.recorded_seconds,0),40);
  assert.deepEqual(a.months.map(m=>m.recorded_seconds),[20,20]);assert.equal(a.totals.recorded_seconds,40);assert.equal(a.totals.point_count,6);
  assert.deepEqual(a.days.map(d=>d.point_count),[2,4]);assert.equal(a.weeks[0].point_count,6);assert.deepEqual(a.months.map(d=>d.point_count),[2,4]);
  for(const d of r.days)assert.equal(d.recorded_seconds,day(s,d.date).recorded_seconds);
});

test('both Warsaw DST days use actual 23/25h and half-open midnight, without rounding fractions',()=>{
  for(const date of ['2026-03-29','2026-10-25']){
    const bounds=dayBounds(date),points=Array.from({length:bounds.seconds/30+1},(_,i)=>({...point(i*30),measured_at:new Date(bounds.start+i*30000).toISOString()}));
    const s=snapshot(points);assert.equal(day(s,date).recorded_seconds,bounds.seconds);assert.equal(range(s,date,date).recorded_seconds,bounds.seconds);
    assert.equal(day(s,nextDate(date)).recorded_seconds,0);const a=analytics(s,date,date);assert.equal(a.totals.recorded_seconds,bounds.seconds);assert.equal(a.totals.point_count,points.length-1);
  }
  assert.equal(summary([point(0),point(1.234)]).recorded_seconds,1.234);
});

test('full archive summary takes precedence over geometry snapshot and late backfill recomputes',()=>{
  const raw=[point(0),point(60)],s=snapshot(raw);assert.equal(day(s,'2026-10-14').recorded_seconds,0);
  s.points.push(point(30));s.revision++;assert.equal(day(s,'2026-10-14').recorded_seconds,60);
  const archived:Snapshot={...s,points:[],recorded_days:{'2026-10-14':65.123}};assert.equal(day(archived,'2026-10-14').recorded_seconds,65.123);
  assert.deepEqual(recordedDays(archived,'2026-10-14','2026-10-15'),{'2026-10-14':65.123,'2026-10-15':0});
});

test('HTTP day/range expose numeric zero for empty/single samples; calculation failure is an error body',async()=>{
  const store=new MemoryStore(),{privateApp,publicApp}=createServers(store,{devices:[{device_id:fixtureDevice,token}]});
  try{
    for(const points of [[],[point(0)]]){store.states.set(fixtureDevice,{...snapshot(points)});
      for(const path of [`/v1/day?device_id=${fixtureDevice}&date=2026-10-14`,`/v1/range?device_id=${fixtureDevice}&from=2026-10-14&to=2026-10-14`]){
        const response=await privateApp.inject(path);assert.equal(response.statusCode,200);assert.equal(response.json().recorded_seconds,0);assert.deepEqual(response.json().recorded_time_derivation,recordedTimeDerivation);
      }
    }
    store.snapshot=async()=>{throw Error('synthetic failure');};const response=await privateApp.inject(`/v1/day?device_id=${fixtureDevice}&date=2026-10-14`);
    assert.equal(response.statusCode,503);assert.deepEqual(response.json(),{code:'database_unavailable'});assert.equal('recorded_seconds' in response.json(),false);
  }finally{await privateApp.close();await publicApp.close();}
});


test('checked-in synthetic contract projections match computed recorded time and raw counts',async()=>{
  const examples=JSON.parse(await readFile(new URL('../../contract/recorded-time-examples.json',import.meta.url),'utf8'));
  assert.equal(examples.synthetic,true);assert.deepEqual(examples.recorded_time_derivation,recordedTimeDerivation);
  const points=Array.from({length:91},(_,i)=>point(i*30));
  points.push(...Array.from({length:21},(_,i)=>({...point(i*30,2,100000+i),measured_at:new Date(epoch+86400000+i*30000).toISOString()})));
  const s=snapshot(points),r=range(s,'2026-10-14','2026-10-15'),a=analytics(s,'2026-10-14','2026-10-15');
  assert.equal(r.recorded_seconds,examples.stationary_and_gap.range.recorded_seconds);
  assert.deepEqual(r.days.map(({date,recorded_seconds,point_count})=>({date,recorded_seconds,point_count})),examples.stationary_and_gap.range.days);
  for(const kind of ['days','weeks','months'] as const)assert.deepEqual(a[kind].map(({period,recorded_seconds,point_count})=>({period,recorded_seconds,point_count})),examples.stationary_and_gap.analytics[kind]);
  assert.equal(a.totals.point_count,examples.stationary_and_gap.analytics.totals.point_count);
  for(const example of examples.gap_boundary)assert.equal(summary(example.consecutive_seconds.map((time:number)=>point(time))).recorded_seconds,example.recorded_seconds);
  const midnight=snapshot(examples.midnight.same_segment_measured_at.map((measured_at:string,i:number)=>({...point(i),measured_at})));
  assert.deepEqual(range(midnight,'2026-10-31','2026-11-01').days.map(({date,recorded_seconds})=>({date,recorded_seconds})),examples.midnight.days);
  assert.equal(analyticsExample.totals.recorded_seconds,1305);assert.equal(analyticsExample.totals.point_count,262);
});
