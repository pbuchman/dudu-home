import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { day, latest, range, dayBounds } from '../src/derive.js';
import { envelope, record, uuid, validPoint, samePoint } from '../src/validation.js';
import { HttpError, type Envelope, type Snapshot, type Store, type StoredPoint, type Result } from '../src/types.js';
export const fixtures=JSON.parse(await readFile(resolve(process.env.ROUTEBOOK_FIXTURES??'../contract/fixtures.json'),'utf8'));
export const token='synthetic_test_token_abcdefghijklmnopqrstuvwxyz';
export const fixtureDevice=fixtures.device_id;
export class MemoryStore implements Store {
  states=new Map<string,Snapshot>();failCommit=false;events=0;
  constructor(ids=[fixtureDevice]){for(const id of ids)this.states.set(id,{device_id:id,revision:0,points:[]});}
  async health(){if(this.failCommit)throw Error('unavailable');}
  async latestSnapshot(id:string){const s=await this.snapshot(id);s.points=s.points.sort((a,b)=>a.measured_at.localeCompare(b.measured_at)||a.event_id.localeCompare(b.event_id)).slice(-1);return s;}
  async snapshot(id:string){const s=this.states.get(id);if(!s)throw new HttpError(404,'unknown_device');return structuredClone(s);}
  async ingest(e:Envelope,receivedAt:string){const s=await this.snapshot(e.device_id),results:Result[]=[];let added=0;
    e.points.forEach((p,index)=>{const event_id=record(p)&&uuid(p.event_id)?p.event_id:null;
      if(!validPoint(p,receivedAt)){results.push({index,event_id,status:'rejected',code:'invalid_point'});return;}
      const old=s.points.find(x=>x.event_id===p.event_id);if(old){const same=samePoint(old,p);results.push({index,event_id,status:same?'duplicate':'rejected',code:same?null:'event_conflict'});return;}
      s.points.push({...p,device_id:e.device_id,received_at:receivedAt});added++;results.push({index,event_id,status:'accepted',code:null});
    });if(this.failCommit)throw Error('commit failed');if(added)s.revision++;this.states.set(e.device_id,s);
    return {ack:{version:1 as const,device_id:e.device_id,revision:s.revision,received_at:receivedAt,results},event:added?{snapshot:s,affected_dates:['2026-10-05']}:null};
  }
}
function meters(actual:number,expected:number){assert.ok(Math.abs(actual-expected)<=fixtures.meter_tolerance,`${actual} != ${expected}`);}
export function checkDay(s:Snapshot,q:any){const d=day(s,q.date);assert.equal(d.point_count,q.point_count);meters(d.meters,q.meters);
  if(q.run_point_ids)assert.deepEqual(d.segments.map(r=>r.points.map(p=>p.event_id)),q.run_point_ids);
  if(q.edge_from_ids)assert.deepEqual(d.segments.map(r=>r.edge_from?.event_id??null),q.edge_from_ids);
  if(q.stops)assert.deepEqual(d.stops,q.stops);
  if(q.stop_projection)assert.deepEqual(d.stops.map(({stop_id,start_at,end_at,duration_seconds,overlap_seconds})=>({stop_id,start_at,end_at,duration_seconds,overlap_seconds})),q.stop_projection);
}
export async function checkCase(store:Store,c:any,id=fixtureDevice) {
  for(let i=0;i<c.deliveries.length;i++){const delivery=c.deliveries[i];const e=envelope({...delivery.request,device_id:id},id);
    const {ack}=await store.ingest(e,delivery.received_at);assert.equal(ack.revision,delivery.expected.revision);assert.deepEqual(ack.results,delivery.expected.results);assert.equal(ack.received_at,delivery.received_at);
    const s=await store.snapshot(id);assert.equal(s.points.length,delivery.expected.stored_point_count);assert.equal(latest(s,delivery.received_at).point?.event_id,delivery.expected.latest_event_id);
    if(c.expected_after_deliveries)meters(day(s,'2026-10-05').meters,c.expected_after_deliveries[i].meters);
  }
  const s=await store.snapshot(id),q=c.expected_queries;
  if(q?.day)checkDay(s,q.day);for(const d of q?.days??[])checkDay(s,d);
  if(q?.range){const r=range(s,q.range.from,q.range.to);meters(r.meters,q.range.meters);assert.deepEqual(r.months,q.range.months);}
  if(q?.stored_received_at)for(const [event,time] of Object.entries(q.stored_received_at))assert.equal(s.points.find(p=>p.event_id===event)?.received_at,time);
  for(const b of c.expected_day_bounds??[]){const a=dayBounds(b.date);assert.deepEqual({date:b.date,start_at:a.start_at,end_at:a.end_at,seconds:a.seconds},b);}
  for(const l of c.expected_latest_at??[]){const a=latest(s,l.server_time);assert.equal(a.fresh,l.fresh);assert.equal(a.clock_state,l.clock_state);}
}
export function syntheticPoint(overrides:Partial<StoredPoint>={}) {return {event_id:'00000000-0000-4000-8000-000000000002',segment_id:'00000000-0000-4000-8000-000000000003',measured_at:'2026-10-05T08:00:00.000Z',lat:0,lon:0,accuracy_m:5,speed_mps:0,...overrides};}
