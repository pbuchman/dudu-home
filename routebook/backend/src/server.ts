import Fastify, { type FastifyRequest } from 'fastify';
import { createHash, timingSafeEqual } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { day, dayBounds, date, dates, latest, range } from './derive.js';
import { envelope, strictJson, uuid } from './validation.js';
import { HttpError, defaults, type CommitEvent, type Derivation, type Store } from './types.js';
interface Options { devices:{device_id:string;token:string}[]; derivation?:Derivation; rate_limit_per_minute?:number; now?:()=>string }
export function createServers(store:Store,opts:Options) {
  const now=opts.now??(()=>new Date().toISOString()),cfg=opts.derivation??defaults;
  const events=new EventEmitter();events.setMaxListeners(0);
  const clientErrorHandler:NonNullable<import('fastify').FastifyServerOptions['clientErrorHandler']>=(_error,socket)=>{
    const body='{"code":"invalid_framing"}';socket.end(`HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\nContent-Length: ${Buffer.byteLength(body)}\r\nConnection: close\r\n\r\n${body}`);
  };
  const publicApp=Fastify({logger:false,clientErrorHandler,bodyLimit:256*1024,exposeHeadRoutes:false});
  const privateApp=Fastify({logger:false,clientErrorHandler,exposeHeadRoutes:false});
  for(const app of [publicApp,privateApp]) {
    app.setNotFoundHandler((_req,reply)=>reply.code(404).send({code:'not_found'}));
    app.setErrorHandler((err,_req,reply)=>{const status=err instanceof HttpError?err.statusCode:(err as any).statusCode===413?413:(err as any).statusCode===415?415:(err as any).statusCode===400?400:503;
      reply.code(status);if(status===503)reply.header('Retry-After','2');reply.send({code:err instanceof HttpError?err.code:status===413?'body_too_large':status===415?'unsupported_media_type':status===400?'invalid_framing':'database_unavailable'});
    });
  }
  const bindings=opts.devices.map(d=>({...d,digest:createHash('sha256').update(d.token).digest()}));
  const authenticated=new WeakMap<FastifyRequest,string>(),received=new WeakMap<FastifyRequest,string>();
  const rate=new Map<string,{window:number;count:number}>();
  publicApp.addHook('onRequest',async(req,reply)=>{
    // Unknown routes/methods never enter auth, body parsing or application processing.
    if(req.method!=='POST'||req.url.split('?')[0]!=='/v1/ingest')return reply.code(404).send({code:'not_found'});
    received.set(req,now());
    const header=req.headers.authorization;const token=typeof header==='string'&&header.startsWith('Bearer ')?header.slice(7):'';
    const digest=createHash('sha256').update(token).digest();let device:string|undefined;
    for(const d of bindings)if(timingSafeEqual(digest,d.digest))device=d.device_id;
    if(!device)throw new HttpError(401,'unauthorized');authenticated.set(req,device);
    const window=Math.floor(Date.parse(received.get(req)!)/60000),old=rate.get(device);const bucket=old?.window===window?old:{window,count:0};bucket.count++;rate.set(device,bucket);
    if(bucket.count>(opts.rate_limit_per_minute??120)){reply.header('Retry-After','60');throw new HttpError(429,'rate_limited');}
    if(!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(req.headers['content-type']??''))throw new HttpError(415,'unsupported_media_type');
  });
  publicApp.removeAllContentTypeParsers();
  publicApp.addContentTypeParser('application/json',{parseAs:'buffer'},(_req,body,done)=>{try{done(null,strictJson(body as Buffer));}catch(e){done(e as Error);}});
  publicApp.post('/v1/ingest',async(req)=>{
    const e=envelope(req.body,authenticated.get(req)!);const committed=await store.ingest(e,received.get(req)!);
    if(committed.event)events.emit(e.device_id,committed.event);
    return committed.ack;
  });
  function params(req:FastifyRequest,fields:string[]) {
    const p=new URLSearchParams(req.url.split('?')[1]??'');
    if([...p.keys()].some(k=>!['device_id',...fields].includes(k))||['device_id',...fields].some(k=>p.getAll(k).length!==1)||!uuid(p.get('device_id')))throw new HttpError(422,'invalid_params');
    return Object.fromEntries(p) as Record<string,string>;
  }
  privateApp.get('/health',async()=>{await store.health();return {status:'ok'};});
  privateApp.get('/v1/latest',async(req)=>{const p=params(req,[]);return latest(await store.latestSnapshot(p.device_id),now());});
  privateApp.get('/v1/day',async(req)=>{const p=params(req,['date']);date(p.date);return day(await store.snapshot(p.device_id,dayBounds(p.date)),p.date,cfg);});
  privateApp.get('/v1/range',async(req)=>{const p=params(req,['from','to']);dates(p.from,p.to);return range(await store.snapshot(p.device_id,{start_at:dayBounds(p.from).start_at,end_at:dayBounds(p.to).end_at}),p.from,p.to,cfg);});
  const streamClosers=new Set<()=>void>();
  privateApp.addHook('preClose',async()=>{for(const close of streamClosers)close();});
  privateApp.get('/v1/live',async(req,reply)=>{
    const p=params(req,[]);let initializing=true;let pending:CommitEvent[]=[];let closed=false;let revision=-1;
    let timer:ReturnType<typeof setInterval>|undefined;
    const close=()=>{if(closed)return;closed=true;if(timer)clearInterval(timer);events.off(p.device_id,onUpdate);streamClosers.delete(close);reply.raw.end();};
    const write=(name:string,event:CommitEvent)=>{
      if(closed||event.snapshot.revision<=revision)return;revision=event.snapshot.revision;
      const data={version:1,device_id:p.device_id,revision,latest:latest(event.snapshot,now()),affected_dates:event.affected_dates};
      // Never buffer an unbounded stream for a slow client.
      if(!reply.raw.write(`id: ${revision}\nevent: ${name}\ndata: ${JSON.stringify(data)}\n\n`))close();
    };
    const onUpdate=(e:CommitEvent)=>{if(initializing){if(pending.length>=16)close();else pending.push(e);}else write('update',e);};
    events.on(p.device_id,onUpdate);streamClosers.add(close);
    try {
      const snapshot=await store.latestSnapshot(p.device_id);
      if(closed)return;
      reply.hijack();reply.raw.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache','X-Accel-Buffering':'no'});
      reply.raw.on('close',close);write('snapshot',{snapshot,affected_dates:[]});initializing=false;
      for(const e of pending)write('update',e);pending=[];
      if(closed)return;
      timer=setInterval(()=>{if(!closed&&!reply.raw.write(': heartbeat\n\n'))close();},15000);timer.unref();
    }catch(e){events.off(p.device_id,onUpdate);streamClosers.delete(close);throw e;}
  });
  return {publicApp,privateApp};
}
