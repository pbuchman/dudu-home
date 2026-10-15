import { recordedDays, recordedTimeDerivation } from './recorded-time.js';
import { dates, dayBounds, localDate, nextDate, timezone } from './calendar.js';
export { date, dates, dayBounds, localDate, nextDate, timezone } from './calendar.js';
import { defaults, type Derivation, type Snapshot, type StoredPoint } from './types.js';
export function haversine(a: Pick<StoredPoint,'lat'|'lon'>,b: Pick<StoredPoint,'lat'|'lon'>,r=defaults.earth_radius_m) {
  const rad=Math.PI/180,dl=(b.lat-a.lat)*rad,dn=(b.lon-a.lon)*rad;
  const h=Math.sin(dl/2)**2+Math.cos(a.lat*rad)*Math.cos(b.lat*rad)*Math.sin(dn/2)**2;
  return 2*r*Math.asin(Math.sqrt(Math.min(1,Math.max(0,h))));
}
export const sortPoints=(points: StoredPoint[])=>[...points].sort((a,b)=>a.measured_at<b.measured_at?-1:a.measured_at>b.measured_at?1:a.event_id<b.event_id?-1:a.event_id>b.event_id?1:0);
export function latest(s:Snapshot,serverTime:string) {
  const point=sortPoints(s.points).at(-1)??null;
  const age=point?Date.parse(serverTime)-Date.parse(point.measured_at):null;
  const clock_state=age===null?'empty':age<0?'future_clock':age<=30000?'fresh':'stale';
  return {version:1,device_id:s.device_id,revision:s.revision,server_time:serverTime,point,fresh:clock_state==='fresh',clock_state};
}
interface Stop {stop_id:string;lat:number;lon:number;start_at:string;end_at:string;duration_seconds:number}
export function connected(a:StoredPoint,b:StoredPoint,cfg:Derivation=defaults) {
  const dt=(Date.parse(b.measured_at)-Date.parse(a.measured_at))/1000;
  return a.segment_id===b.segment_id && dt>0 && dt<=cfg.gap_seconds && haversine(a,b,cfg.earth_radius_m)/dt<=cfg.max_edge_speed_mps;
}
export function derive(points:StoredPoint[],cfg:Derivation=defaults) {
  const runs:StoredPoint[][]=[];const edges=new Map<string,number>();const stops:Stop[]=[];
  for(const p of sortPoints(points)) {
    const run=runs.at(-1),prev=run?.at(-1);let connected=false;let distance=0;
    if(prev){const dt=(Date.parse(p.measured_at)-Date.parse(prev.measured_at))/1000;distance=haversine(prev,p,cfg.earth_radius_m);
      connected=p.segment_id===prev.segment_id&&dt>0&&dt<=cfg.gap_seconds&&distance/dt<=cfg.max_edge_speed_mps;}
    if(connected&&prev&&run){run.push(p);const still=prev.speed_mps!==null&&p.speed_mps!==null&&prev.speed_mps<cfg.stationary_speed_mps&&p.speed_mps<cfg.stationary_speed_mps;
      edges.set(p.event_id,still||distance<Math.max(cfg.min_edge_m,(prev.accuracy_m+p.accuracy_m)/2)?0:distance);
    }else runs.push([p]);
  }
  for(const run of runs) {
    let anchor:StoredPoint|undefined,last:StoredPoint|undefined;
    const finish=()=>{if(anchor&&last){const duration=(Date.parse(last.measured_at)-Date.parse(anchor.measured_at))/1000;if(duration>=cfg.stop_seconds)stops.push({stop_id:anchor.event_id,lat:anchor.lat,lon:anchor.lon,start_at:anchor.measured_at,end_at:last.measured_at,duration_seconds:duration});}anchor=undefined;last=undefined;};
    for(const p of run){const stationary=p.speed_mps===null||p.speed_mps<=cfg.stationary_speed_mps;
      if(anchor&&(!stationary||haversine(anchor,p,cfg.earth_radius_m)>cfg.stop_radius_m))finish();
      if(stationary){anchor??=p;last=p;}
    }finish();
  }
  return {runs,edges,stops};
}
export function day(s:Snapshot,d:string,cfg:Derivation=defaults,derived=derive(s.points,cfg)) {
  const b=dayBounds(d);let meters=0,point_count=0;
  const segments=[];
  for(const run of derived.runs){const pts=run.filter(p=>p.measured_at>=b.start_at&&p.measured_at<b.end_at);if(!pts.length)continue;
    point_count+=pts.length;for(const p of pts)meters+=derived.edges.get(p.event_id)??0;
    const i=run.indexOf(pts[0]);segments.push({run_id:run[0].event_id,source_segment_id:run[0].segment_id,points:pts,edge_from:i>0?run[i-1]:null});
  }
  const stops=derived.stops.flatMap(stop=>{const overlap=(Math.min(b.end,Date.parse(stop.end_at))-Math.max(b.start,Date.parse(stop.start_at)))/1000;return overlap>0?[{...stop,overlap_seconds:overlap}]:[];});
  return {version:1,device_id:s.device_id,revision:s.revision,date:d,timezone,derivation:cfg,recorded_time_derivation:recordedTimeDerivation,recorded_seconds:recordedDays(s,d,d)[d],meters,point_count,segments,stops};
}
export function range(s:Snapshot,from:string,to:string,cfg:Derivation=defaults) {
  const requested=dates(from,to),derived=derive(s.points,cfg),recorded=recordedDays(s,from,to);
  const summaries=new Map(requested.map(date=>[date,{date,meters:0,point_count:0,stop_count:0,recorded_seconds:recorded[date]}]));
  const windows=requested.map(date=>({date,...dayBounds(date)}));
  const start=windows[0].start_at,end=windows.at(-1)!.end_at;let windowIndex=0;
  // Single pass over points, rather than scanning an entire selected range for each day.
  for(const run of derived.runs)for(const p of run){if(p.measured_at<start||p.measured_at>=end)continue;while(windows[windowIndex].end_at<=p.measured_at)windowIndex++;const d=summaries.get(windows[windowIndex].date)!;d.point_count++;d.meters+=derived.edges.get(p.event_id)??0;}
  for(const stop of derived.stops){const first=localDate(stop.start_at)>from?localDate(stop.start_at):from,last=localDate(stop.end_at)<to?localDate(stop.end_at):to;
    for(let d=first;d<=last;d=nextDate(d)){const b=dayBounds(d);if(Math.min(b.end,Date.parse(stop.end_at))>Math.max(b.start,Date.parse(stop.start_at)))summaries.get(d)!.stop_count++;}
  }
  const days=[...summaries.values()],totals=new Map<string,{meters:number;recorded_seconds:number}>();for(const d of days){const month=d.date.slice(0,7),total=totals.get(month)??{meters:0,recorded_seconds:0};total.meters+=d.meters;total.recorded_seconds+=d.recorded_seconds;totals.set(month,total);}
  return {version:1,device_id:s.device_id,revision:s.revision,timezone,from,to,derivation:cfg,recorded_time_derivation:recordedTimeDerivation,recorded_seconds:days.reduce((n,d)=>n+d.recorded_seconds,0),days,months:[...totals].map(([month,total])=>({month,...total})),meters:days.reduce((n,d)=>n+d.meters,0)};
}
/** Conservative invalidation: every observed local day plus neighbours, including long stops whose anchor changes. */
export function affectedDates(points:StoredPoint[]) {return [...new Set(points.flatMap(p=>{const d=localDate(p.measured_at);return [nextDate(d,-1),d,nextDate(d)];}))].sort();}

export type LatestResponse = ReturnType<typeof latest>;
export type DayResponse = ReturnType<typeof day>;
export type RangeResponse = ReturnType<typeof range>;
export interface LiveResponse { version: 1; device_id: string; revision: number; latest: LatestResponse; affected_dates: string[] }
