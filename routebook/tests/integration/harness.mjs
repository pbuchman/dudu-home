// Local synthetic harness: real Fastify/PostGIS, loopback-only TLS and private UI proxy.
import { readFile, writeFile } from 'node:fs/promises';
import { createServer as httpServer, request } from 'node:http';
import { createServer as tlsServer } from 'node:https';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { PgStore } from '../../backend/dist/store.js';
import { createServers } from '../../backend/dist/server.js';
const require=createRequire(new URL('../../backend/package.json',import.meta.url)),pg=require('pg');
const dir=process.env.ROUTEBOOK_INTEGRATION_PRIVATE;
if(!dir)throw Error('private test directory required');
const cfg=JSON.parse(await readFile(resolve(dir,'harness.json'),'utf8'));
if(!new URL(cfg.database_url).pathname.startsWith('/routebook_synthetic'))throw Error('synthetic DB only');
const pool=new pg.Pool({connectionString:cfg.database_url});
const store=new PgStore(pool);await store.assertDurability();await store.bootstrap([cfg.device_id]);
const apps=createServers(store,{devices:[{device_id:cfg.device_id,token:cfg.token}],rate_limit_per_minute:1000});
await apps.publicApp.listen({host:'127.0.0.1',port:18791});await apps.privateApp.listen({host:'127.0.0.1',port:18792});
const ledger=[];let sequence=0,dayActive=0,maxDayActive=0;const streams=new Set();
async function fault(){return JSON.parse(await readFile(resolve(dir,'fault.json'),'utf8'));}
const tls=tlsServer({key:await readFile(resolve(dir,'server.key')),cert:await readFile(resolve(dir,'server.pem'))},async(req,res)=>{
  const mode=await fault();
  if(mode.mode==='redirect'){res.writeHead(302,{Location:'https://localhost/forbidden-redirect'});res.end();return;}
  const chunks=[];for await(const c of req)chunks.push(c);const body=Buffer.concat(chunks);
  const upstream=request({host:'127.0.0.1',port:18791,path:req.url,method:req.method,headers:{...req.headers,host:'localhost'}},async answer=>{
    const response=[];for await(const c of answer)response.push(c);let bytes=Buffer.concat(response);
    let envelope;try{envelope=JSON.parse(body);}catch{}
    let ack;try{ack=JSON.parse(bytes);}catch{}
    const committedIds=ack?.results?.filter(r=>r.status==='accepted'||r.status==='duplicate').map(r=>r.event_id)??[];
    const visible=committedIds.length?(await pool.query('SELECT count(*)::int AS n FROM routebook_points WHERE device_id=$1 AND event_id=ANY($2::uuid[])',[cfg.device_id,committedIds])).rows[0].n:0;
    ledger.push({commit_visible:visible===new Set(committedIds).size,sequence:++sequence,method:req.method,path:req.url,lane:envelope?.lane,ids:envelope?.points?.map(p=>p.event_id),status:answer.statusCode,results:ack?.results,revision:ack?.revision});
    if(mode.mode==='lost'&&answer.statusCode===200){await writeFile(resolve(dir,'fault.json'),JSON.stringify({mode:'normal'}),{mode:0o600});res.destroy();return;}
    if(mode.mode==='invalid'&&answer.statusCode===200)bytes=Buffer.from('{}');
    res.writeHead(answer.statusCode,{'Content-Type':'application/json',...(answer.headers['retry-after']?{'Retry-After':answer.headers['retry-after']}:{})});res.end(bytes);
  });upstream.on('error',()=>{res.writeHead(503);res.end('{"code":"test_upstream_unavailable"}');});upstream.end(body);
});await new Promise(r=>tls.listen(18443,'127.0.0.1',r));
const ui=httpServer(async(req,res)=>{
  if(req.url==='/routebook-config.json'){const mode=await fault();res.setHeader('Content-Type','application/json');res.end(JSON.stringify(mode.mode==='waiting'?{mode:'api'}:{mode:'api',deviceId:cfg.device_id,mapTilerKey:'local-style-test-only'}));return;}
  if(req.url==='/__test/ledger'){res.setHeader('Content-Type','application/json');res.end(JSON.stringify({ledger,maxDayActive}));return;}
  if(req.url==='/__test/disconnect'){for(const s of streams)s.destroy();res.end('ok');return;}
  if(req.url?.startsWith('/v1/')||req.url==='/health'){
    const isDay=req.url.startsWith('/v1/day'),isLive=req.url.startsWith('/v1/live');if(isDay){dayActive++;maxDayActive=Math.max(maxDayActive,dayActive);}
    let counted=isDay;const finished=()=>{if(counted){counted=false;dayActive--;}};
    const upstream=request({host:'127.0.0.1',port:18792,path:req.url,headers:{Accept:req.headers.accept??'*/*'}},answer=>{
      res.writeHead(answer.statusCode,answer.headers);answer.pipe(res);if(isLive)streams.add(answer);
      answer.on('close',()=>{streams.delete(answer);finished();if(isLive)res.destroy();});
      res.on('close',()=>answer.destroy());
    });res.on('close',()=>{finished();upstream.destroy();});upstream.on('error',()=>{finished();if(!res.destroyed){res.writeHead(503);res.end();}});upstream.end();return;
  }
  try{const pathname=new URL(req.url,'http://localhost').pathname;const asset=resolve('ui/dist','.'+pathname);if(pathname!=='/'&&!asset.startsWith(resolve('ui/dist')+'/'))throw Error();
    const path=pathname==='/'?resolve('ui/dist/index.html'):asset;const bytes=await readFile(path);
    res.setHeader('Content-Type',path.endsWith('.js')?'text/javascript':path.endsWith('.css')?'text/css':path.endsWith('.html')?'text/html':'application/octet-stream');res.end(bytes);
  }catch{res.writeHead(404);res.end();}
});await new Promise(r=>ui.listen(18580,'127.0.0.1',r));
await writeFile(resolve(dir,'ready'),'ready',{mode:0o600});console.log('Synthetic integration harness ready');
async function close(){await writeFile(resolve(dir,'ledger.json'),JSON.stringify({ledger,maxDayActive},null,2),{mode:0o600});for(const s of streams)s.destroy();ui.close();tls.close();await Promise.all([apps.publicApp.close(),apps.privateApp.close()]);await pool.end();}
process.once('SIGTERM',()=>void close());process.once('SIGINT',()=>void close());
