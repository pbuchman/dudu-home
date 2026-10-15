import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { createServers } from '../src/server.js';
import { MemoryStore,fixtureDevice,syntheticPoint,token } from './helpers.js';
import type { AnalyticsSnapshot } from '../src/analytics.js';
import type { PlacesResponse } from '../../contract/analytics.js';
class SyntheticAnalyticsStore extends MemoryStore {
  broken=false;
  points=Array.from({length:100000},(_,i)=>({...syntheticPoint(),device_id:fixtureDevice,event_id:`00000000-0000-4000-8000-${String(i).padStart(12,'0')}`,measured_at:new Date(Date.parse('2026-10-01T00:00:00.000Z')+i*5000).toISOString(),received_at:'2026-10-15T00:00:00.000Z',lon:(i%1000)*.00009,speed_mps:2}));
  async places():Promise<PlacesResponse>{return {version:1,device_id:fixtureDevice,metadata_revision:0,places:[]};}
  async writePlace(){return this.places();}
  async analyticsSnapshot():Promise<AnalyticsSnapshot>{return {device_id:fixtureDevice,revision:1,metadata_revision:0,places:[],has_before:false,has_after:false,points:this.broken?null as any:this.points};}
}
const url=`/v1/analytics?device_id=${fixtureDevice}&from=2026-10-01&to=2026-10-06`;
test('large background analytics keeps health/ingest responsive and bounds concurrent reads',{timeout:15000},async()=>{
  const store=new SyntheticAnalyticsStore(),{privateApp,publicApp}=createServers(store,{devices:[{device_id:fixtureDevice,token}],now:()=> '2026-10-15T00:00:00.000Z'});
  try{
    let finished=false;const pending=privateApp.inject(url).then(r=>{finished=true;return r;});await delay(20);
    const start=performance.now();assert.equal((await privateApp.inject('/health')).statusCode,200);
    const ingest=await publicApp.inject({url:'/v1/ingest',method:'POST',headers:{authorization:`Bearer ${token}`},payload:{version:1,device_id:fixtureDevice,lane:'latest',points:[syntheticPoint()]}});
    assert.equal(ingest.statusCode,200);assert.ok(performance.now()-start<500,'health and ingest should not wait for CPU derivation');assert.equal(finished,false);
    const busy=await privateApp.inject(url);assert.equal(busy.statusCode,429);assert.deepEqual(busy.json(),{code:'analytics_busy'});assert.equal(busy.headers['retry-after'],'1');
    assert.equal((await pending).statusCode,200);
  }finally{await privateApp.close();await publicApp.close();}
});
test('worker failures are sanitized and release the next calculation; server close terminates active worker',{timeout:15000},async()=>{
  const store=new SyntheticAnalyticsStore(),{privateApp,publicApp}=createServers(store,{devices:[{device_id:fixtureDevice,token}]});
  try{
    store.broken=true;const failed=await privateApp.inject(url);assert.equal(failed.statusCode,503);assert.deepEqual(failed.json(),{code:'analytics_unavailable'});
    store.broken=false;assert.equal((await privateApp.inject(url)).statusCode,200);
    const pending=privateApp.inject(url);await delay(20);const start=performance.now();await privateApp.close();assert.equal((await pending).statusCode,503);assert.ok(performance.now()-start<2500,'close must terminate worker without lingering');
  }finally{await privateApp.close();await publicApp.close();}
});
