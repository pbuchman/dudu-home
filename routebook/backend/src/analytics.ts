import { recordedDays, recordedTimeDerivation } from './recorded-time.js';
import { Temporal } from '@js-temporal/polyfill';
import { connected, dates, dayBounds, derive, haversine, localDate, sortPoints, timezone } from './derive.js';
import { defaults, HttpError, type Derivation, type Snapshot, type StoredPoint } from './types.js';
import type { AnalyticsProvenance, AnalyticsResponse, BoundaryReason, DirectionalComparison, Journey, JourneyDetailResponse, Metrics, NamedPlace, PeriodSummary, SpeedSample, TimelineInterval, Visit } from '../../contract/analytics.js';

export const analyticsVersion='routebook-analytics-1';
export const analyticsLimits=Object.freeze({max_days:31,max_points:650000,max_detail_points:100000,context_hours:24});
export interface AnalyticsSnapshot extends Snapshot {
  places:NamedPlace[];metadata_revision:number;has_before:boolean;has_after:boolean;
}
export function analyticsBounds(from:string,to:string) {
  if(dates(from,to).length>analyticsLimits.max_days)throw new HttpError(422,'analytics_limit');
  return {start_at:new Date(dayBounds(from).start-86400000).toISOString(),end_at:new Date(dayBounds(to).end+86400000).toISOString()};
}
export function matchPlace(p:{lat:number;lon:number},places:NamedPlace[]):NamedPlace|null {
  return places.map(place=>({place,distance:haversine(p,place)})).filter(x=>x.distance<=x.place.radius_m)
    .sort((a,b)=>a.distance-b.distance||a.place.place_id.localeCompare(b.place.place_id))[0]?.place??null;
}
interface Interval extends TimelineInterval {end_id:string}
interface Detailed {journey:Journey;points:StoredPoint[];intervals:Interval[];speeds:SpeedSample[];visits:Visit[]}
const empty=():Metrics=>({meters:0,moving_meters:0,moving_seconds:0,stopped_seconds:0,unknown_seconds:0,elapsed_seconds:0,moving_average_mps:null,overall_average_mps:null,observed_max_mps:null});
function averages<T extends Metrics>(m:T,complete:boolean):T {
  m.elapsed_seconds=m.moving_seconds+m.stopped_seconds+m.unknown_seconds;
  m.moving_average_mps=m.moving_seconds>0?m.moving_meters/m.moving_seconds:null;
  m.overall_average_mps=complete&&m.elapsed_seconds>0?m.meters/m.elapsed_seconds:null;
  return m;
}
function metrics(intervals:Interval[],speeds:SpeedSample[],complete:boolean):Metrics {
  const m=empty();for(const i of intervals){m.meters+=i.meters;const seconds=(Date.parse(i.end_at)-Date.parse(i.start_at))/1000;
    m[`${i.state}_seconds`]+=seconds;if(i.state==='moving')m.moving_meters+=i.meters;}
  for(const sample of speeds)if(sample.accepted&&sample.speed_mps!==null)m.observed_max_mps=Math.max(m.observed_max_mps??0,sample.speed_mps);
  return averages(m,complete);
}
function provenance(s:AnalyticsSnapshot,from:string,to:string):AnalyticsProvenance {
  return {version:1,device_id:s.device_id,source_revision:s.revision,metadata_revision:s.metadata_revision,
    algorithm_version:analyticsVersion,timezone,from,to,context:{...analyticsBounds(from,to),max_days:analyticsLimits.max_days,max_points:analyticsLimits.max_points}};
}
function build(s:AnalyticsSnapshot,from:string,to:string,cfg:Derivation) {
  const bounds=analyticsBounds(from,to);if(s.points.length>analyticsLimits.max_points)throw new HttpError(422,'analytics_limit');
  const points=sortPoints(s.points);
  const derived=derive(points,cfg),indices=new Map(points.map((p,i)=>[p.event_id,i]));
  const visits:Visit[]=derived.stops.map(({stop_id,...stop})=>({visit_id:stop_id,...stop,place:matchPlace(stop,s.places),overlap_seconds:0}));
  const timeIndices=new Map<string,number>();points.forEach((p,i)=>{const key=p.segment_id+':'+p.measured_at;if(!timeIndices.has(key))timeIndices.set(key,i);});
  const longStops=new Map(derived.stops.filter(x=>x.duration_seconds>=600).map(stop=>{const start=indices.get(stop.stop_id)!;return [start,timeIndices.get(points[start].segment_id+':'+stop.end_at)!];}));
  const intervals:Interval[]=[],speeds:SpeedSample[]=points.map(p=>({event_id:p.event_id,measured_at:p.measured_at,speed_mps:p.speed_mps,accepted:false}));
  let stopIndex=0;
  for(let i=1;i<points.length;i++) {
    const a=points[i-1],b=points[i],dt=(Date.parse(b.measured_at)-Date.parse(a.measured_at))/1000;
    while(stopIndex<derived.stops.length&&derived.stops[stopIndex].end_at<a.measured_at)stopIndex++;
    const stop=derived.stops[stopIndex],isStop=!!stop&&a.measured_at>=stop.start_at&&b.measured_at<=stop.end_at&&a.segment_id===b.segment_id;
    const meters=derived.edges.get(b.event_id)??0,valid=connected(a,b,cfg);
    const still=a.speed_mps!==null&&b.speed_mps!==null&&a.speed_mps<=cfg.stationary_speed_mps&&b.speed_mps<=cfg.stationary_speed_mps;
    const state=valid?(isStop||still?'stopped':meters>0?'moving':'unknown'):'unknown';
    intervals.push({start_at:a.measured_at,end_at:b.measured_at,state,meters,end_id:b.event_id});
    if(state==='moving'&&a.accuracy_m<=25&&b.accuracy_m<=25) {
      const geometric=haversine(a,b,cfg.earth_radius_m)/dt;
      for(const index of [i-1,i]){const speed=points[index].speed_mps;if(speed!==null&&speed>=cfg.stationary_speed_mps&&Math.abs(speed-geometric)<=Math.max(5,geometric*.5))speeds[index].accepted=true;}
    }
  }
  const detailed:Detailed[]=[];let visitCursor=0;
  const add=(start:number,end:number,start_boundary:BoundaryReason,end_boundary:BoundaryReason)=>{
    if(end<=start)return;
    const slice=points.slice(start,end+1),parts=intervals.slice(start,end),samples=speeds.slice(start,end+1);
    if(!parts.some(i=>i.state==='moving'))return;
    const complete=start_boundary==='confirmed_stop'&&end_boundary==='confirmed_stop'&&!parts.some(i=>i.state==='unknown');
    const first=slice[0],last=slice.at(-1)!;
    while(visitCursor<visits.length&&visits[visitCursor].start_at<first.measured_at)visitCursor++;
    const stops:Visit[]=[];for(let at=visitCursor;at<visits.length&&visits[at].start_at<=last.measured_at;at++)if(visits[at].end_at<=last.measured_at)stops.push({...visits[at],overlap_seconds:visits[at].duration_seconds});
    const journey:Journey={...metrics(parts,samples,complete),journey_id:first.event_id,start_at:first.measured_at,end_at:last.measured_at,
      point_count:slice.length,stop_count:stops.length,complete,start_boundary,end_boundary,
      start:{lat:first.lat,lon:first.lon,place:matchPlace(first,s.places)},end:{lat:last.lat,lon:last.lon,place:matchPlace(last,s.places)},
      departure_hour:Temporal.Instant.from(first.measured_at).toZonedDateTimeISO(timezone).hour};
    detailed.push({journey,points:slice,intervals:parts,speeds:samples,visits:stops});
  };
  let start=0,startBoundary:BoundaryReason=s.has_before?'context_limit':'recording_boundary';
  for(let i=0;i<points.length;i++) {
    if(i>start&&(Date.parse(points[i].measured_at)-Date.parse(points[i-1].measured_at))>=600000) {
      add(start,i-1,startBoundary,'gps_gap');start=i;startBoundary='gps_gap';
    }
    const parkingEnd=longStops.get(i);
    if(parkingEnd!==undefined) {add(start,i,startBoundary,'confirmed_stop');start=parkingEnd;startBoundary='confirmed_stop';i=parkingEnd;continue;}
  }
  add(start,points.length-1,startBoundary,s.has_after?'context_limit':'recording_boundary');
  const selectedStart=dayBounds(from).start,selectedEnd=dayBounds(to).end;
  const selected=detailed.filter(d=>Date.parse(d.journey.end_at)>selectedStart&&Date.parse(d.journey.start_at)<selectedEnd);
  const selectedVisits=visits.flatMap(v=>{const overlap=(Math.min(selectedEnd,Date.parse(v.end_at))-Math.max(selectedStart,Date.parse(v.start_at)))/1000;return overlap>0?[{...v,overlap_seconds:overlap}]:[];});
  return {bounds,selected,selectedVisits,derived};
}
const mean=(values:number[])=>values.reduce((a,b)=>a+b,0)/values.length;
function median(values:number[]) {const sorted=[...values].sort((a,b)=>a-b),mid=Math.floor(sorted.length/2);return sorted.length%2?sorted[mid]:(sorted[mid-1]+sorted[mid])/2;}
function comparisons(journeys:Journey[]):DirectionalComparison[] {
  const groups=new Map<string,Journey[]>();for(const j of journeys){const a=j.start.place,b=j.end.place;if(!a||!b)continue;const key=a.place_id+':'+b.place_id;const g=groups.get(key)??[];g.push(j);groups.set(key,g);}
  return [...groups.values()].map(group=>({from_place:group[0].start.place!,to_place:group[0].end.place!,count:group.length,complete_count:group.filter(j=>j.complete).length,
    mean_seconds:mean(group.map(j=>j.elapsed_seconds)),mean_meters:mean(group.map(j=>j.meters)),mean_stop_count:mean(group.map(j=>j.stop_count)),
    median_seconds:median(group.map(j=>j.elapsed_seconds)),median_meters:median(group.map(j=>j.meters)),median_stop_count:median(group.map(j=>j.stop_count)),
    min_seconds:Math.min(...group.map(j=>j.elapsed_seconds)),max_seconds:Math.max(...group.map(j=>j.elapsed_seconds)),
    departures:Array.from({length:24},(_,hour)=>{const g=group.filter(j=>j.departure_hour===hour);return {hour,g};}).filter(x=>x.g.length).map(({hour,g})=>({hour,count:g.length,mean_seconds:mean(g.map(j=>j.elapsed_seconds)),mean_meters:mean(g.map(j=>j.meters)),median_seconds:median(g.map(j=>j.elapsed_seconds)),median_meters:median(g.map(j=>j.meters))}))}));
}
function aggregate(days:PeriodSummary[],key:(d:string)=>string):PeriodSummary[] {
  const groups=new Map<string,PeriodSummary>(),complete=new Map<string,boolean>();for(const d of days){const period=key(d.period),m=groups.get(period)??{...empty(),recorded_seconds:0,point_count:0,period,journey_count:0,complete_journey_count:0,stop_count:0};
    for(const k of ['meters','moving_meters','moving_seconds','stopped_seconds','unknown_seconds','recorded_seconds','point_count','journey_count','complete_journey_count','stop_count'] as const)m[k]+=d[k];
    if(d.elapsed_seconds>0&&d.overall_average_mps===null)complete.set(period,false);
    if(d.observed_max_mps!==null)m.observed_max_mps=Math.max(m.observed_max_mps??0,d.observed_max_mps);groups.set(period,m);}
  return [...groups.values()].map(m=>({...averages(m,complete.get(m.period)!==false)}));
}
export function analytics(s:AnalyticsSnapshot,from:string,to:string,cfg:Derivation=defaults):AnalyticsResponse {
  const {selected,selectedVisits}=build(s,from,to,cfg);
  const windows=dates(from,to).map(date=>({date,...dayBounds(date)})),selectedStart=windows[0].start,selectedEnd=windows.at(-1)!.end;
  const recorded=recordedDays(s,from,to);
  const days=new Map(windows.map(({date:period})=>[period,{...empty(),recorded_seconds:recorded[period],point_count:0,period,journey_count:0,complete_journey_count:0,stop_count:0} as PeriodSummary]));
  const incompleteDays=new Set<string>();
  // At most 31 calendar windows; avoid invoking timezone parsing for every stored sample.
  const windowIndex=(time:number)=>{let low=0,high=windows.length;while(low<high){const mid=(low+high)>>>1;if(windows[mid].end<=time)low=mid+1;else high=mid;}return low<windows.length&&windows[low].start<=time?low:-1;};
  const bucket=(time:string)=>{const index=windowIndex(Date.parse(time));return index<0?undefined:days.get(windows[index].date);};
  for(const p of s.points){const m=bucket(p.measured_at);if(m)m.point_count++;}
  for(const d of selected) {
    const departure=localDate(d.journey.start_at),counter=days.get(departure);
    if(counter){counter.journey_count++;if(d.journey.complete)counter.complete_journey_count++;}
    for(const interval of d.intervals) {
      const start=Date.parse(interval.start_at),finish=Date.parse(interval.end_at),ending=bucket(interval.end_at);if(ending)ending.meters+=interval.meters;
      let cursor=Math.max(start,selectedStart),end=Math.min(finish,selectedEnd);
      while(cursor<end){const window=windows[windowIndex(cursor)],limit=Math.min(end,window.end),m=days.get(window.date)!,seconds=(limit-cursor)/1000;m[`${interval.state}_seconds`]+=seconds;
        if(!d.journey.complete)incompleteDays.add(window.date);
        if(interval.state==='moving')m.moving_meters+=interval.meters*seconds/((finish-start)/1000);cursor=limit;}
    }
    for(const speed of d.speeds){const m=bucket(speed.measured_at);if(m&&speed.accepted&&speed.speed_mps!==null)m.observed_max_mps=Math.max(m.observed_max_mps??0,speed.speed_mps);}
  }
  for(const v of selectedVisits){const m=days.get(localDate(v.start_at));if(m)m.stop_count++;}
  const list=[...days.values()].map(m=>averages(m,!incompleteDays.has(m.period)) as PeriodSummary);
  const weeks=aggregate(list,d=>{const date=Temporal.PlainDate.from(d);return date.subtract({days:date.dayOfWeek-1}).toString();});
  const months=aggregate(list,d=>d.slice(0,7)),totalPeriod=aggregate(list,()=> 'total')[0];
  const {period:_period,journey_count:_journeys,complete_journey_count:_complete,stop_count:_stops,...totals}=totalPeriod;
  // Period counts attribute a journey/visit once, on its observed start date. Duration/distance
  // still include the selected portion of cross-midnight journeys that started earlier.
  return {...provenance(s,from,to),recorded_time_derivation:recordedTimeDerivation,totals,journeys:selected.map(d=>d.journey),days:list,weeks,months,visits:selectedVisits,places:s.places,comparisons:comparisons(selected.map(d=>d.journey))};
}
export function journeyDetail(s:AnalyticsSnapshot,from:string,to:string,id:string,cfg:Derivation=defaults):JourneyDetailResponse {
  const {selected}=build(s,from,to,cfg),d=selected.find(d=>d.journey.journey_id===id);if(!d)throw new HttpError(404,'journey_not_found');
  if(d.points.length>analyticsLimits.max_detail_points)throw new HttpError(422,'analytics_limit');
  return {...provenance(s,from,to),journey:d.journey,points:d.points,segments:derive(d.points,cfg).runs.map(run=>({run_id:run[0].event_id,points:run})),
    timeline:d.intervals.map(({end_id,...i})=>i),speed_samples:d.speeds,visits:d.visits};
}
