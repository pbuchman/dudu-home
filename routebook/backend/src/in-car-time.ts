import { derive } from './derive.js';
import { recordedIntervals } from './recorded-time.js';
import type { Snapshot, StoredPoint, StoredObservation } from './types.js';
import type { CorrectionInterval, CorrectionMetadata, CorrectionMetrics } from '../../contract/analytics.js';
export interface TimeInterval {start:number;end:number}
export interface PowerProfile {
  profile_id:string;firmware_id:string;validation_id:string;validated_at:string;
  states:{acc_state:number|null;module1_state:number|null;state:'on'|'off'}[];
}
export interface CorrectionCalculation {
  recorded:TimeInterval[];confirmed:TimeInterval[];estimated:TimeInterval[];provisional:TimeInterval[];
  computed_at:string;next_refresh_at:string|null;
}
export const correctionVersion='routebook-in-car-1' as const;
export function union(intervals:TimeInterval[]):TimeInterval[] {
  const result:TimeInterval[]=[];
  for(const i of [...intervals].filter(i=>i.end>i.start).sort((a,b)=>a.start-b.start||a.end-b.end)) {
    const last=result.at(-1);if(last&&i.start<=last.end)last.end=Math.max(last.end,i.end);else result.push({...i});
  }return result;
}
export function intersect(a:TimeInterval[],b:TimeInterval[]):TimeInterval[] {
  const result:TimeInterval[]=[];let i=0,j=0;
  while(i<a.length&&j<b.length){const start=Math.max(a[i].start,b[j].start),end=Math.min(a[i].end,b[j].end);if(end>start)result.push({start,end});if(a[i].end<b[j].end)i++;else j++;}return union(result);
}
function subtract(a:TimeInterval[],b:TimeInterval[]):TimeInterval[] {
  const result:TimeInterval[]=[];let j=0;
  for(const x of a){let start=x.start;while(j<b.length&&b[j].end<=start)j++;for(let k=j;k<b.length&&b[k].start<x.end;k++){if(b[k].start>start)result.push({start,end:Math.min(x.end,b[k].start)});start=Math.max(start,b[k].end);}if(start<x.end)result.push({start,end:x.end});}return result;
}
function distance(a:StoredPoint,b:StoredPoint){const r=Math.PI/180,h=Math.sin((b.lat-a.lat)*r/2)**2+Math.cos(a.lat*r)*Math.cos(b.lat*r)*Math.sin((b.lon-a.lon)*r/2)**2;return 12742000*Math.asin(Math.sqrt(Math.min(1,h)));}
const stationary=(p:StoredPoint)=>p.speed_mps!==null&&p.speed_mps<=.7&&p.accuracy_m<=25;
const median=(xs:number[])=>{const x=[...xs].sort((a,b)=>a-b),m=x.length>>>1;return x.length%2?x[m]:(x[m-1]+x[m])/2;};
function lowerBound(xs:number[],value:number){let a=0,b=xs.length;while(a<b){const m=(a+b)>>>1;if(xs[m]<value)a=m+1;else b=m;}return a;}
export function calculateCorrection(points:StoredPoint[],events:StoredObservation[]=[],profiles:PowerProfile[]=[],computedAt=new Date().toISOString()):CorrectionCalculation {
  const now=Date.parse(computedAt),ordered=[...points].sort((a,b)=>a.measured_at.localeCompare(b.measured_at)||a.event_id.localeCompare(b.event_id));
  const recorded=recordedIntervals(ordered),stationaryEdges:TimeInterval[]=[],moving:number[]=[];
  const segments=new Map<string,StoredPoint[]>();
  for(const p of ordered){const list=segments.get(p.segment_id)??[];list.push(p);segments.set(p.segment_id,list);if(p.accuracy_m<=25&&p.speed_mps!==null&&p.speed_mps>.7)moving.push(Date.parse(p.measured_at));}
  for(const segment of segments.values())for(let i=1;i<segment.length;i++){
    const a=segment[i-1],b=segment[i],start=Date.parse(a.measured_at),end=Date.parse(b.measured_at);
    if(end<=now&&end>start&&end-start<=15000&&stationary(a)&&stationary(b)&&distance(a,b)<=15)stationaryEdges.push({start,end});
    else if(end>start&&end-start<=15000&&stationary(a)&&stationary(b)&&distance(a,b)>15)moving.push(end);
  }
  moving.sort((a,b)=>a-b);
  const off:TimeInterval[]=[],onCoverage:TimeInterval[]=[],trusted:TimeInterval[]=[],trustedTimes:number[]=[],onTimes:number[]=[];let nextRefresh=Infinity;
  const refresh=(time:number)=>{if(time>now)nextRefresh=Math.min(nextRefresh,time);};
  // Profiles are explicitly installed on the server. Sender profile/firmware claims alone grant no trust.
  const state=(e:StoredObservation)=>{if(e.kind==='observer_lost')return null;const p=profiles.find(p=>p.profile_id===e.interpretation_profile&&p.firmware_id===e.firmware_id);const mapped=p?.states.find(s=>s.acc_state===e.acc_state&&s.module1_state===e.module1_state);return mapped?.state==='off'&&e.module1_state===null?null:mapped?.state??null;};
  const orderedEvents=[...events].sort((a,b)=>a.observer_epoch.localeCompare(b.observer_epoch)||a.sequence-b.sequence||a.event_id.localeCompare(b.event_id));
  const chronological=[...events].sort((a,b)=>a.observed_at.localeCompare(b.observed_at)||a.event_id.localeCompare(b.event_id));
  const interruptions=chronological.filter((e,i)=>e.kind==='observer_lost'||(i>0&&(e.observer_epoch!==chronological[i-1].observer_epoch||e.boot_id!==chronological[i-1].boot_id))).map(e=>Date.parse(e.observed_at));
  const observations=orderedEvents.map(e=>({e,time:Date.parse(e.observed_at),state:state(e)}));
  const invalidClock=new Set<number>();
  for(let i=1;i<observations.length;i++){const a=observations[i-1],b=observations[i];if(a.e.observer_epoch!==b.e.observer_epoch||a.e.boot_id!==b.e.boot_id)continue;
    const wall=b.time-a.time,mono=b.e.elapsed_realtime_ms-a.e.elapsed_realtime_ms;
    if(a.e.sequence===b.e.sequence||wall<=0||mono<=0||Math.abs(wall-mono)>1000){invalidClock.add(i-1);invalidClock.add(i);}}

  let offStart:number|null=null,offEnd=0;
  const finishOff=()=>{if(offStart!==null&&offEnd-offStart>=3000)off.push({start:offStart,end:offEnd});offStart=null;};
  for(let i=0;i<observations.length;i++){
    const {e,time,state:current}=observations[i],next=observations[i+1];
    if(current&&time<=now){trustedTimes.push(time);if(current==='on')onTimes.push(time);}
    if(!current||time>now||invalidClock.has(i)){finishOff();continue;}
    let end=Math.min(time+45000,now),telemetryEnd=end;let continuous=false;
    if(next){const wall=next.time-time,mono=next.e.elapsed_realtime_ms-e.elapsed_realtime_ms;
      continuous=next.e.observer_epoch===e.observer_epoch&&next.e.boot_id===e.boot_id&&next.e.sequence===e.sequence+1&&wall>0&&mono>0&&Math.abs(wall-mono)<=1000&&next.e.interpretation_profile===e.interpretation_profile&&next.e.firmware_id===e.firmware_id;
      // Even an invalid clock/sequence cannot carry an OFF observation across its boundary.
      if(next.e.observer_epoch===e.observer_epoch){if(next.time>time)telemetryEnd=Math.min(telemetryEnd,next.time);if(!continuous){end=time;finishOff();}else end=Math.min(end,next.time);}
    }
    const interrupted=interruptions[lowerBound(interruptions,time+1)];if(interrupted!==undefined){end=Math.min(end,interrupted);telemetryEnd=Math.min(telemetryEnd,interrupted);}
    // Presence of trusted telemetry vetoes heuristic filling even if a later sequence
    // hole makes OFF coverage unusable. ON can only veto, never subtract time.
    if(telemetryEnd>time){trusted.push({start:time,end:telemetryEnd});if(current==='on')onCoverage.push({start:time,end:telemetryEnd});}
    if(current==='off'){
      const contradiction=moving[lowerBound(moving,time)];if(contradiction!==undefined)end=Math.min(end,contradiction);
      if(offStart===null||offEnd!==time){finishOff();offStart=time;}offEnd=Math.max(time,end);
      if(!next||!continuous||next.state!=='off'||next.time>offEnd)finishOff();
      refresh(time+3000);refresh(time+45000);
    }else finishOff();
  }finishOff();
  const confirmed=intersect(intersect(subtract(union(off),union(onCoverage)),union(stationaryEdges)),recorded),estimated:TimeInterval[]=[],provisional:TimeInterval[]=[];
  const allTimes=ordered.map(p=>Date.parse(p.measured_at));
  const byId=new Map(ordered.map(p=>[p.event_id,p])),timeSegments=new Map<string,Set<string>>();for(const p of ordered){const set=timeSegments.get(p.measured_at)??new Set<string>();set.add(p.segment_id);timeSegments.set(p.measured_at,set);}
  const existing=derive(ordered),movingEvidence=new Set<string>();
  for(const run of existing.runs)for(let i=1;i<run.length;i++){const a=run[i-1],b=run[i];if((existing.edges.get(b.event_id)??0)>0&&a.speed_mps!==null&&b.speed_mps!==null&&a.speed_mps>=2&&b.speed_mps>=2&&a.accuracy_m<=15&&b.accuracy_m<=15){movingEvidence.add(a.event_id);movingEvidence.add(b.event_id);}}
  trustedTimes.sort((a,b)=>a-b);onTimes.sort((a,b)=>a-b);
  const telemetry=union(trusted);
  for(const stop of existing.stops){
    // Apply reliability filters to the existing complete observed stop. Never invent a
    // short suffix of a longer stop after a bad sample or a tighter-radius reset.
    if(stop.duration_seconds<270||stop.duration_seconds>=330)continue;
    const segment=segments.get(byId.get(stop.stop_id)!.segment_id)!;
    if(segment.at(-1)!.measured_at!==stop.end_at)continue;
    const candidate=segment.filter(p=>p.measured_at>=stop.start_at&&p.measured_at<=stop.end_at);
    if(candidate.length<40)continue;const first=candidate[0],last=candidate.at(-1)!,start=Date.parse(first.measured_at),end=Date.parse(last.measured_at),duration=(end-start)/1000;
    if(candidate.some((p,i)=>!stationary(p)||distance(first,p)>15||(i>0&&(Date.parse(p.measured_at)<=Date.parse(candidate[i-1].measured_at)||Date.parse(p.measured_at)-Date.parse(candidate[i-1].measured_at)>15000)))||candidate.filter(p=>p.speed_mps!<=.3).length/candidate.length<.9||median(candidate.map(p=>p.accuracy_m))>10)continue;
    if(segment.filter(p=>Date.parse(p.measured_at)>=start-120000&&Date.parse(p.measured_at)<start&&movingEvidence.has(p.event_id)).length<2)continue;
    // Any trusted observation/coverage suppresses heuristic filling for this whole candidate.
    const trustedAt=trustedTimes[lowerBound(trustedTimes,start)],onAt=onTimes[lowerBound(onTimes,start)];
    if((trustedAt!==undefined&&trustedAt<=end)||(onAt!==undefined&&onAt<=end)||telemetry.some(t=>t.start<end&&t.end>start))continue;
    if(timeSegments.get(last.measured_at)!.size>1)continue;
    const next=allTimes[lowerBound(allTimes,end+1)];let open=false;
    if(next!==undefined){if(next-end<600000)continue;if(next>now){refresh(next);continue;}}else{open=true;const maturity=Math.max(end+600000,...candidate.map(p=>Date.parse(p.received_at)+600000));if(now<maturity){refresh(maturity);continue;}}
    const interval={start:end-Math.min(300,duration)*1000,end};estimated.push(interval);if(open)provisional.push(interval);
  }
  const canonicalEstimated=intersect(subtract(union(estimated),confirmed),recorded);
  return {recorded,confirmed,estimated:canonicalEstimated,provisional:intersect(union(provisional),canonicalEstimated),computed_at:computedAt,next_refresh_at:Number.isFinite(nextRefresh)?new Date(nextRefresh).toISOString():null};
}
const calculations=new WeakMap<Snapshot,CorrectionCalculation>();
export function correctionFor(s:Snapshot,computedAt?:string):CorrectionCalculation{if(s.correction)return s.correction;if(!computedAt){const cached=calculations.get(s);if(cached&&(!cached.next_refresh_at||Date.now()<Date.parse(cached.next_refresh_at)))return cached;}const c=calculateCorrection(s.points,s.observations??[],s.power_profiles??[],computedAt);if(!computedAt)calculations.set(s,c);return c;}
export function correctionMetadata(c:CorrectionCalculation):CorrectionMetadata{return {correction_algorithm_version:correctionVersion,correction_computed_at:c.computed_at,correction_next_refresh_at:c.next_refresh_at};}
export function correctionMetrics(c:CorrectionCalculation,start=-Infinity,end=Infinity):CorrectionMetrics & {recorded_seconds:number} {
  const seconds=(intervals:TimeInterval[])=>intervals.reduce((n,i)=>n+Math.max(0,Math.min(end,i.end)-Math.max(start,i.start))/1000,0);
  const recorded_seconds=seconds(c.recorded),confirmed_excluded_seconds=seconds(c.confirmed),estimated_excluded_seconds=seconds(c.estimated),excluded_tail_seconds=confirmed_excluded_seconds+estimated_excluded_seconds;
  return {...correctionMetadata(c),recorded_seconds,in_car_seconds:Math.max(0,recorded_seconds-excluded_tail_seconds),excluded_tail_seconds,confirmed_excluded_seconds,estimated_excluded_seconds,provisional_excluded_seconds:seconds(c.provisional)};
}
export function correctionIntervals(c:CorrectionCalculation,start:number,end:number):CorrectionInterval[]{return [...c.confirmed.map(i=>({...i,reason:'confirmed_power_off' as const})),...subtract(c.estimated,c.provisional).map(i=>({...i,reason:'estimated_archive_tail' as const})),...c.provisional.map(i=>({...i,reason:'provisional_archive_tail' as const}))].flatMap(i=>{const a=Math.max(start,i.start),b=Math.min(end,i.end);return b>a?[{start_at:new Date(a).toISOString(),end_at:new Date(b).toISOString(),reason:i.reason}]:[];}).sort((a,b)=>a.start_at.localeCompare(b.start_at));}
