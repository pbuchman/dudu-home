import { test } from 'node:test';
import assert from 'node:assert/strict';
import { analytics, analyticsBounds, analyticsLimits, journeyDetail, matchPlace, type AnalyticsSnapshot } from '../src/analytics.js';
import { derive, day, dayBounds } from '../src/derive.js';
import type { StoredPoint } from '../src/types.js';
import { placeBody } from '../src/places.js';
import { fixtureDevice } from './helpers.js';

export const id=(n:number)=>`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
export function recording(start='2026-10-05T08:00:00.000Z') {
  const points:StoredPoint[]=[];let seconds=0,lon=0;
  const add=(speed:number|null=0,delta=0,segment=900)=>{points.push({device_id:fixtureDevice,event_id:id(points.length+1),segment_id:id(segment),measured_at:new Date(Date.parse(start)+seconds*1000).toISOString(),received_at:'2026-10-15T08:00:00.000Z',lat:0,lon,accuracy_m:5,speed_mps:speed});seconds+=5;lon+=delta;};
  const park=(span=600)=>{for(let i=0;i<=span/5;i++)add(0);};
  const move=(count=20,speed:number|null=10,segment=900)=>{lon+=.00045;for(let i=0;i<count;i++)add(speed,.00045,segment);};
  return {points,add,park,move,get seconds(){return seconds;}};
}
export function snapshot(points:StoredPoint[],extra:Partial<AnalyticsSnapshot>={}):AnalyticsSnapshot {return {device_id:fixtureDevice,revision:7,metadata_revision:2,places:[],points,has_before:false,has_after:false,...extra};}
test('confirmed 600-second parking splits complete journeys; details preserve all samples and averages match intervals',()=>{
  const r=recording();r.park();r.move();r.park();r.move();r.park();const s=snapshot(r.points),a=analytics(s,'2026-10-05','2026-10-05');
  assert.equal(a.journeys.length,2);assert.equal(a.journeys[0].complete,true);assert.equal(a.journeys[1].complete,true);
  assert.equal(a.visits.length,3);assert.equal(a.journeys[0].stop_count,0);assert.equal(a.journeys[0].stopped_seconds,0);
  const j=a.journeys[0],detail=journeyDetail(s,'2026-10-05','2026-10-05',j.journey_id);
  assert.equal(detail.points.length,j.point_count);assert.equal(detail.timeline.length,j.point_count-1);
  assert.equal(j.moving_average_mps,j.moving_meters/j.moving_seconds);assert.equal(j.overall_average_mps,j.meters/j.elapsed_seconds);
  assert.equal(j.observed_max_mps,10);assert.ok(j.elapsed_seconds<120);
});
test('180-second observed stop displays without journey split; 595s remains internal; exactly 600 splits',()=>{
  for(const duration of [180,595,600]){const r=recording();r.move();r.park(duration);r.move();const a=analytics(snapshot(r.points),'2026-10-05','2026-10-05');
    assert.equal(a.visits.length,1);assert.equal(a.journeys.length,duration===600?2:1);
    assert.equal(a.journeys[0].overall_average_mps,null);
    if(duration<600)assert.equal(a.journeys[0].stop_count,1);
  }
});
test('technical segment and short GPS gap keep journey, retain unknown duration and disconnected geometry; >=600s gap splits',()=>{
  const r=recording();r.move(5);r.move(5,10,901);const base=r.points.at(-1)!;
  const shifted=r.points.map((p,i)=>i>=5?{...p,measured_at:new Date(Date.parse(p.measured_at)+60000).toISOString()}:p);
  const a=analytics(snapshot(shifted),'2026-10-05','2026-10-05');assert.equal(a.journeys.length,1);assert.equal(a.journeys[0].unknown_seconds,65);
  const d=journeyDetail(snapshot(shifted),'2026-10-05','2026-10-05',a.journeys[0].journey_id);assert.equal(d.segments.length,2);assert.equal(d.timeline.filter(x=>x.state==='unknown').length,1);
  const long=r.points.map((p,i)=>i>=5?{...p,measured_at:new Date(Date.parse(p.measured_at)+600000).toISOString()}:p);
  const b=analytics(snapshot(long),'2026-10-05','2026-10-05');assert.equal(b.journeys.length,2);assert.equal(b.journeys[0].end_boundary,'gps_gap');assert.equal(b.journeys[1].start_boundary,'gps_gap');
  assert.equal(base.speed_mps,10);
});
test('null speeds preserve observed moving distance without invented maxima; jitter/spikes and slow traffic',()=>{
  const nulls=recording();nulls.move(20,null);const a=analytics(snapshot(nulls.points),'2026-10-05','2026-10-05');assert.equal(a.journeys.length,1);assert.equal(a.journeys[0].observed_max_mps,null);assert.ok(a.journeys[0].moving_average_mps!>0);
  const r=recording();r.move(5);r.points[2].speed_mps=79;const b=analytics(snapshot(r.points),'2026-10-05','2026-10-05');assert.equal(b.journeys[0].observed_max_mps,10);
  const jitter=recording();for(let i=0;i<50;i++)jitter.add(null,i%2?-.00001:.00001);assert.equal(analytics(snapshot(jitter.points),'2026-10-05','2026-10-05').journeys.length,0);
  const slow=recording();for(let i=0;i<20;i++)slow.add(1.2,.000055);const c=analytics(snapshot(slow.points),'2026-10-05','2026-10-05');assert.ok(c.journeys[0].moving_seconds>0);assert.equal(c.journeys[0].stopped_seconds,0);
});
test('quality-filtered speed excludes poor accuracy and implausible teleport edges',()=>{
  const r=recording();r.move(10);const bad=r.points.map(p=>({...p,accuracy_m:40}));assert.equal(analytics(snapshot(bad),'2026-10-05','2026-10-05').journeys[0].observed_max_mps,null);
  r.points[4].lon=10;const a=analytics(snapshot(r.points),'2026-10-05','2026-10-05');assert.equal(a.journeys.length,1);assert.equal(a.journeys[0].unknown_seconds,10);assert.equal(a.journeys[0].complete,false);
});
test('midnight and DST split elapsed time exactly, retain v1 edge assignment and avoid summary double-counting',()=>{
  for(const date of ['2026-03-29','2026-10-25']){const boundary=dayBounds(date);assert.equal(boundary.seconds,date.includes('03-29')?82800:90000);
    const r=recording(new Date(boundary.start-5000).toISOString());r.move(6);const a=analytics(snapshot(r.points),date==='2026-03-29'?'2026-03-28':'2026-10-24',date);
    assert.equal(a.days.reduce((n,d)=>n+d.elapsed_seconds,0),a.totals.elapsed_seconds);assert.equal(a.weeks.reduce((n,d)=>n+d.meters,0),a.totals.meters);assert.equal(a.months.reduce((n,d)=>n+d.meters,0),a.totals.meters);
    assert.equal(a.days[1].meters,day(snapshot(r.points),date).meters);assert.ok(Math.abs(a.days[1].moving_average_mps!-a.journeys[0].moving_average_mps!)<.00001);
  }
});
test('named place nearest match/tie stable, directions separated, medians/departure hours and metadata revision exposed',()=>{
  const r=recording();r.park();r.move();r.park();const first=r.points[0],last=r.points.at(-1)!;
  const places=[{place_id:id(701),name:'Synthetic A',lat:0,lon:first.lon,radius_m:100},{place_id:id(702),name:'Synthetic B',lat:0,lon:last.lon,radius_m:100}];
  assert.equal(matchPlace(first,[{...places[0],place_id:id(703)},places[0]])?.place_id,id(701));
  const a=analytics(snapshot(r.points,{places}),'2026-10-05','2026-10-05');assert.equal(a.comparisons.length,1);assert.equal(a.comparisons[0].count,1);assert.equal(a.comparisons[0].median_seconds,a.journeys[0].elapsed_seconds);assert.equal(a.comparisons[0].departures[0].hour,10);assert.equal(a.metadata_revision,2);assert.equal(a.source_revision,7);
});
test('A to B and B to A remain separate comparisons with once-attributed period counts',()=>{
  const r=recording();r.park();r.move();r.park();const original=[...r.points],end=original.at(-1)!,span=Date.parse(end.measured_at)-Date.parse(original[0].measured_at)+5000;
  const reverse=original.map((p,i)=>({...p,event_id:id(1000+i),lon:end.lon-p.lon,measured_at:new Date(Date.parse(p.measured_at)+span).toISOString()}));
  const places=[{place_id:id(701),name:'Synthetic A',lat:0,lon:0,radius_m:100},{place_id:id(702),name:'Synthetic B',lat:0,lon:end.lon,radius_m:100}];
  const a=analytics(snapshot([...original,...reverse],{places}),'2026-10-05','2026-10-05');assert.equal(a.journeys.length,2);assert.equal(a.comparisons.length,2);
  assert.equal(a.comparisons[0].from_place.place_id,a.comparisons[1].to_place.place_id);assert.equal(a.comparisons[0].to_place.place_id,a.comparisons[1].from_place.place_id);
  assert.equal(a.days[0].journey_count,2);assert.equal(a.weeks[0].journey_count,2);assert.equal(a.months[0].journey_count,2);
});
test('an incomplete day does not hide another complete day average; weeks preserve incomplete recording',()=>{
  const partial=recording();partial.move();const full=recording('2026-10-06T08:00:00.000Z');full.park();full.move();full.park();
  const a=analytics(snapshot([...partial.points,...full.points.map((p,i)=>({...p,event_id:id(2000+i)}))]),'2026-10-05','2026-10-06');
  assert.equal(a.days[0].overall_average_mps,null);assert.ok(a.days[1].overall_average_mps!>0);assert.equal(a.weeks[0].overall_average_mps,null);assert.equal(a.totals.overall_average_mps,null);
});
test('backfill changes historical boundaries, limits explicit and context/archive boundaries remain incomplete',()=>{
  const r=recording();r.move(10);const old=analytics(snapshot([r.points[0],r.points.at(-1)!]),'2026-10-05','2026-10-05');assert.equal(old.journeys.length,0);
  const newer=analytics(snapshot(r.points,{revision:8,has_before:true,has_after:true}),'2026-10-05','2026-10-05');assert.equal(newer.source_revision,8);assert.equal(newer.journeys[0].start_boundary,'context_limit');assert.equal(newer.journeys[0].overall_average_mps,null);
  assert.throws(()=>analyticsBounds('2026-01-01','2026-02-01'),/analytics_limit/);assert.equal(analyticsBounds('2026-10-01','2026-10-31').start_at,'2026-09-29T22:00:00.000Z');
  assert.throws(()=>analytics(snapshot(Array.from({length:analyticsLimits.max_points+1},()=>r.points[0])),'2026-10-05','2026-10-05'),/analytics_limit/);
});
test('place write validation is exact and safe; Unicode names, defaults and revision accepted',()=>{
  const p={expected_metadata_revision:0,name:'Synthetic Alpha',lat:0,lon:0};assert.equal(placeBody(p).input?.radius_m,100);
  for(const x of [{...p,name:' x '},{...p,name:'bad\u0000'},{...p,lat:'0'},{...p,radius_m:1},{...p,radius_m:null},{...p,extra:true},{...p,expected_metadata_revision:-1}])assert.throws(()=>placeBody(x),/invalid_place/);
  assert.deepEqual(placeBody({expected_metadata_revision:1},true),{input:null,expectedRevision:1});
});
