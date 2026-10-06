import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp,writeFile,rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn,type ChildProcess } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:net';
import { randomUUID } from 'node:crypto';
import { syntheticPoint,token } from './helpers.js';
const db=process.env.ROUTEBOOK_TEST_DB_URL;
const freePort=async()=>{const s=createServer();s.listen(0,'127.0.0.1');await once(s,'listening');const a=s.address();assert.ok(a&&typeof a!=='string');const port=a.port;await new Promise<void>(r=>s.close(()=>r()));return port;};
test('compiled CLI bootstrap and real backend process restart retain ACK/revision; graceful shutdown closes SSE',{skip:!db,timeout:20000},async()=>{
 if(!new URL(db!).pathname.startsWith('/routebook_synthetic'))throw Error('Dedicated synthetic database required');
 const dir=await mkdtemp(join(tmpdir(),'routebook-runtime-test-')),configPath=join(dir,'config.json'),device=randomUUID();const publicPort=await freePort(),privatePort=await freePort();
 await writeFile(configPath,JSON.stringify({database_url:db,public:{host:'127.0.0.1',port:publicPort},private:{host:'127.0.0.1',port:privatePort},devices:[{device_id:device,token}],derivation:{},rate_limit_per_minute:120}),{mode:0o600});
 const start=(args:string[]=[])=>spawn(process.execPath,['dist/main.js',...args],{env:{...process.env,ROUTEBOOK_CONFIG:configPath},stdio:['ignore','pipe','pipe']});
 const ready=async(c:ChildProcess)=>{let output='';for await(const chunk of c.stdout!){output+=chunk;if(output.includes('listeners started'))return;}throw Error('Backend failed startup');};
 const stop=async(c:ChildProcess)=>{const exited=once(c,'exit');c.kill('SIGTERM');const [code]=await exited;assert.equal(code,0);};
 let child:ChildProcess|undefined;let stream:AbortController|undefined;
 try {
  const bootstrap=start(['--bootstrap']);const [code]=await once(bootstrap,'exit');assert.equal(code,0);
  child=start();await ready(child);
  const p=syntheticPoint({measured_at:new Date().toISOString()});const payload={version:1,device_id:device,lane:'latest',points:[p]};
  const post=()=>fetch(`http://127.0.0.1:${publicPort}/v1/ingest`,{method:'POST',headers:{authorization:`Bearer ${token}`,'content-type':'application/json'},body:JSON.stringify(payload)});
  let res=await post();assert.equal(res.status,200);assert.equal((await res.json() as any).results[0].status,'accepted');
  await stop(child);child=undefined;child=start();await ready(child);
  res=await post();const ack:any=await res.json();assert.equal(ack.results[0].status,'duplicate');assert.equal(ack.revision,1);
  const read=await fetch(`http://127.0.0.1:${privatePort}/v1/latest?device_id=${device}`);const latest:any=await read.json();assert.equal(latest.point.event_id,p.event_id);assert.equal(latest.revision,1);
  assert.equal((await fetch(`http://127.0.0.1:${publicPort}/health`,{headers:{authorization:`Bearer ${token}`}})).status,404);
  stream=new AbortController();const live=await fetch(`http://127.0.0.1:${privatePort}/v1/live?device_id=${device}`,{signal:stream.signal});assert.equal(live.status,200);
  await stop(child);child=undefined;
 }finally{stream?.abort();if(child){child.kill('SIGKILL');await once(child,'exit');}await rm(dir,{recursive:true});}
});
