import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { validObservation, envelope, sameObservation } from '../src/validation.js';
import { createServers } from '../src/server.js';
import { MemoryStore, fixtureDevice, token } from './helpers.js';
const fixture=JSON.parse(await readFile(new URL('../../contract/radio-observations-examples.json',import.meta.url),'utf8'));
const now='2026-10-14T10:00:00.000Z';
const event=()=>({event_id:randomUUID(),observer_epoch:randomUUID(),sequence:0,observed_at:'2026-10-14T08:00:00.000Z',elapsed_realtime_ms:12345,boot_id:randomUUID(),kind:'snapshot',acc_state:0,module1_state:0,interpretation_profile:'diagnostic-v1',firmware_id:'sha256:'+ '0'.repeat(64)});
test('v2 strict raw schema, safe numeric/UUID/time boundaries; sender state and unknown keys rejected',()=>{
  assert.equal(validObservation(event(),now),true);assert.equal(validObservation(fixture.request.events[0],fixture.ack.received_at),true);assert.equal(envelope(fixture.request,fixture.request.device_id).version,2);
  for(const changed of [{sequence:-1},{sequence:1.2},{elapsed_realtime_ms:2**53},{module1_state:2**31},{acc_state:'0'},{interpretation_profile:''},{firmware_id:'bad space'},{observer_epoch:'not-uuid'},{observed_at:'2026-02-30T08:00:00.000Z'},{observed_at:'2026-10-14T10:05:00.001Z'},{kind:'on'},{radio_state:'OFF'}])assert.equal(validObservation({...event(),...changed},now),false);
  assert.equal(validObservation({...event(),acc_state:null,module1_state:null},now),true);
  const e=event();assert.equal(sameObservation(e,{...e}),true);assert.equal(sameObservation(e,{...e,sequence:1}),false);
  assert.throws(()=>envelope({version:2,device_id:fixtureDevice,lane:'latest',events:[e,e]},fixtureDevice));
  assert.throws(()=>envelope({version:2,device_id:fixtureDevice,lane:'latest',points:[e]},fixtureDevice));
});
test('v1 and v2 share auth/body/binding/commit safeguards while ACK preserves version and immutable payload',async()=>{
  const store=new MemoryStore(),{publicApp,privateApp}=createServers(store,{devices:[{device_id:fixtureDevice,token}],now:()=>now});
  const payload={version:2,device_id:fixtureDevice,lane:'backfill',events:[event()]},headers={authorization:`Bearer ${token}`};
  const post=(p:unknown=payload,h=headers)=>publicApp.inject({url:'/v1/ingest',method:'POST',headers:h,payload:p as any});
  try{
    assert.equal((await post(payload,{} as any)).statusCode,401);
    assert.equal((await post({...payload,device_id:randomUUID()})).statusCode,403);
    assert.equal((await post({...payload,events:[]})).statusCode,422);
    assert.equal((await post({...payload,events:Array(201).fill(payload.events[0])})).statusCode,422);
    assert.equal((await publicApp.inject({url:'/v1/ingest',method:'POST',headers:{...headers,'content-type':'application/json'},payload:' '.repeat(256*1024+1)})).statusCode,413);
    let r=await post();assert.equal(r.statusCode,200);assert.equal(r.json().version,2);assert.equal(r.json().revision,1);assert.equal(r.json().results[0].status,'accepted');
    r=await post();assert.equal(r.json().revision,1);assert.equal(r.json().results[0].status,'duplicate');
    r=await post({...payload,events:[{...payload.events[0],acc_state:1},{...event(),module1_state:'0'}]});assert.deepEqual(r.json().results.map((x:any)=>x.code),['event_conflict','invalid_observation']);
    assert.equal((await store.snapshot(fixtureDevice)).points.length,0);assert.equal((await store.snapshot(fixtureDevice)).observations!.length,1);
    store.failCommit=true;r=await post({...payload,events:[event()]});assert.equal(r.statusCode,503);assert.deepEqual(r.json(),{code:'database_unavailable'});store.failCommit=false;assert.equal((await store.snapshot(fixtureDevice)).revision,1);
  }finally{await publicApp.close();await privateApp.close();}
});
