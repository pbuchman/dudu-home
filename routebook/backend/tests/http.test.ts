import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServers } from '../src/server.js';
import { MemoryStore,fixtureDevice,token,syntheticPoint } from './helpers.js';
const now=()=> '2026-10-05T08:10:00.000Z';
const payload=()=>({version:1,device_id:fixtureDevice,lane:'latest',points:[syntheticPoint()]});
test('HTTP separation, auth before parsing, status codes and safe errors',async()=>{
 const store=new MemoryStore();const {publicApp,privateApp}=createServers(store,{devices:[{device_id:fixtureDevice,token}],now});
 try {
  const headers={authorization:`Bearer ${token}`,'content-type':'application/json'};
  for(const url of ['/health','/v1/day?device_id='+fixtureDevice+'&date=2026-10-05','/','/v1/latest','/v1/live'])for(const method of ['GET','POST','HEAD','OPTIONS'] as const)assert.equal((await publicApp.inject({url,method,headers,payload:'invalid json'})).statusCode,404);
  assert.equal((await publicApp.inject({url:'/v1/ingest',method:'GET',headers})).statusCode,404);
  assert.equal((await privateApp.inject({url:'/v1/ingest',method:'POST',headers,payload:payload()})).statusCode,404);
  assert.equal((await publicApp.inject({url:'/v1/ingest',method:'POST',payload:'not JSON',headers:{'content-type':'text/plain'}})).statusCode,401);
  const ingest=(body:any,h=headers)=>publicApp.inject({url:'/v1/ingest',method:'POST',headers:h,payload:body});
  assert.equal((await ingest({...payload(),device_id:'00000000-0000-4000-8000-000000000001'})).statusCode,403);
  for(const body of [{...payload(),extra:1},{...payload(),points:[]},{...payload(),version:2},'{"a":1,"a":2}'])assert.equal((await ingest(body)).statusCode,422);
  assert.equal((await ingest('x'.repeat(262145))).statusCode,413);
  assert.equal((await ingest('x',{authorization:`Bearer ${token}`,'content-type':'text/plain'})).statusCode,415);
  assert.equal((await ingest(Buffer.from([0xc3,0x28]))).statusCode,422);
  let res=await ingest(payload());assert.equal(res.statusCode,200);assert.equal(res.json().revision,1);assert.equal(res.json().results[0].status,'accepted');
  res=await ingest(payload());assert.equal(res.json().results[0].status,'duplicate');assert.equal(res.json().revision,1);
  store.failCommit=true;res=await ingest(payload());assert.equal(res.statusCode,503);assert.deepEqual(res.json(),{code:'database_unavailable'});assert.equal(res.headers['retry-after'],'2');
  assert.equal((await privateApp.inject('/health')).statusCode,503);store.failCommit=false;
  assert.equal((await privateApp.inject('/health')).statusCode,200);
  assert.equal((await privateApp.inject(`/v1/latest?device_id=${fixtureDevice}`)).json().clock_state,'stale');
  for(const url of ['/v1/latest','/v1/day?device_id='+fixtureDevice+'&date=2026-02-30','/v1/latest?device_id='+fixtureDevice+'&extra=1','/v1/latest?device_id='+fixtureDevice+'&device_id='+fixtureDevice])assert.equal((await privateApp.inject(url)).statusCode,422);
  assert.equal((await privateApp.inject('/v1/latest?device_id=00000000-0000-4000-8000-000000000001')).statusCode,404);
 }finally{await publicApp.close();await privateApp.close();}
});
test('authenticated per-device rate limit',async()=>{
 const {publicApp,privateApp}=createServers(new MemoryStore(),{devices:[{device_id:fixtureDevice,token}],now,rate_limit_per_minute:1});
 try{for(const status of [200,429])assert.equal((await publicApp.inject({method:'POST',url:'/v1/ingest',headers:{authorization:`Bearer ${token}`},payload:payload()})).statusCode,status);}finally{await publicApp.close();await privateApp.close();}
});
test('malformed HTTP framing returns only a safe code',async()=>{
 const {publicApp,privateApp}=createServers(new MemoryStore(),{devices:[{device_id:fixtureDevice,token}],now});
 try{
  await publicApp.listen({host:'127.0.0.1',port:0});const addr=publicApp.server.address();assert.ok(addr&&typeof addr!=='string');
  const {connect}=await import('node:net');const response=await new Promise<string>((resolve,reject)=>{const socket=connect(addr.port,'127.0.0.1',()=>socket.write('POST /v1/ingest HTTP/1.1\r\nHost: localhost\r\nContent-Length: nope\r\n\r\n'));
   let text='';socket.setTimeout(1000,()=>socket.destroy(Error('timeout')));socket.on('data',b=>text+=b);socket.on('end',()=>resolve(text));socket.on('error',reject);
  });assert.match(response,/400 Bad Request/);assert.deepEqual(JSON.parse(response.split('\r\n\r\n')[1]),{code:'invalid_framing'});
 }finally{await publicApp.close();await privateApp.close();}
});
