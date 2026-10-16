import type { RadioObservation } from '../../contract/analytics.js';
import { HttpError, type Envelope, type Point } from './types.js';
export const uuid = (x: unknown): x is string => typeof x === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(x);
export const record = (x: unknown): x is Record<string, unknown> => x !== null && typeof x === 'object' && !Array.isArray(x);
export const exact = (x: Record<string, unknown>, keys: string[]) => Object.keys(x).length === keys.length && keys.every(k => Object.hasOwn(x,k));
export function instant(x: unknown): x is string {
  if (typeof x !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(x)) return false;
  const n = Date.parse(x); return Number.isFinite(n) && new Date(n).toISOString() === x;
}
const num = (x: unknown, min: number, max: number): x is number => typeof x === 'number' && Number.isFinite(x) && x >= min && x <= max;
export function validPoint(x: unknown, receivedAt: string): x is Point {
  return record(x) && exact(x,['event_id','segment_id','measured_at','lat','lon','accuracy_m','speed_mps']) &&
    uuid(x.event_id) && uuid(x.segment_id) && instant(x.measured_at) && x.measured_at >= '2000-01-01T00:00:00.000Z' &&
    Date.parse(x.measured_at) <= Date.parse(receivedAt)+300000 && num(x.lat,-90,90) && num(x.lon,-180,180) &&
    num(x.accuracy_m,Number.MIN_VALUE,50) && (x.speed_mps === null || num(x.speed_mps,0,80));
}
export function envelope(x: unknown, deviceId: string): Envelope {
  if(!record(x)||(x.version!==1&&x.version!==2))throw new HttpError(422,'invalid_envelope');
  const key=x.version===1?'points':'events',items=x[key];
  if(!exact(x,['version','device_id','lane',key])||!uuid(x.device_id)||!['latest','backfill'].includes(String(x.lane))||!Array.isArray(items)||items.length<1||items.length>200||(x.lane==='latest'&&items.length!==1))throw new HttpError(422,'invalid_envelope');
  if(x.device_id!==deviceId)throw new HttpError(403,'device_binding');return x as unknown as Envelope;
}
export const observationKeys=['event_id','observer_epoch','sequence','observed_at','elapsed_realtime_ms','boot_id','kind','acc_state','module1_state','interpretation_profile','firmware_id'] as const;
const safe=(x:unknown)=>typeof x==='number'&&Number.isSafeInteger(x)&&x>=0;
export const rawState=(x:unknown)=>x===null||(typeof x==='number'&&Number.isInteger(x)&&x>=-(2**31)&&x<=2**31-1);
export const identifier=(x:unknown,max:number)=>typeof x==='string'&&x.length>=1&&x.length<=max&&/^[A-Za-z0-9._:-]+$/.test(x);
export function validObservation(x:unknown,receivedAt:string):x is RadioObservation {
  return record(x)&&exact(x,[...observationKeys])&&uuid(x.event_id)&&uuid(x.observer_epoch)&&uuid(x.boot_id)&&safe(x.sequence)&&safe(x.elapsed_realtime_ms)&&instant(x.observed_at)&&x.observed_at>='2000-01-01T00:00:00.000Z'&&Date.parse(x.observed_at)<=Date.parse(receivedAt)+300000&&['snapshot','transition','observer_lost'].includes(String(x.kind))&&rawState(x.acc_state)&&rawState(x.module1_state)&&identifier(x.interpretation_profile,80)&&identifier(x.firmware_id,120);
}
export const sameObservation=(a:RadioObservation,b:RadioObservation)=>observationKeys.every(k=>a[k]===b[k]);
export function samePoint(a: Point,b: Point): boolean {
  return (['event_id','segment_id','measured_at','lat','lon','accuracy_m','speed_mps'] as const).every(k => a[k] === b[k]);
}
/** Parse JSON grammar while tracking decoded object keys, before JSON.parse can erase duplicates. */
export function strictJson(bytes: Buffer): unknown {
  try {
    const source = new TextDecoder('utf-8',{fatal:true}).decode(bytes); let i=0;
    const ws=()=>{while (/[\x20\t\r\n]/.test(source[i] ?? '') && i<source.length) i++;};
    const string=(): string=>{const start=i++; while(i<source.length) { const c=source[i++]; if(c==='"') return JSON.parse(source.slice(start,i)); if(c==='\\') i++; } throw Error();};
    type Frame={kind:'object'|'array';state:'keyOrEnd'|'key'|'colon'|'valueOrEnd'|'value'|'commaOrEnd';keys:Set<string>};
    const frames:Frame[]=[];let rootDone=false;
    const value=():void=>{
      ws();const c=source[i];
      if(c==='{'||c==='['){i++;frames.push({kind:c==='{'?'object':'array',state:c==='{'?'keyOrEnd':'valueOrEnd',keys:new Set()});return;}
      if(c==='"'){string();return;}
      const token=/^(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(source.slice(i));
      if(!token)throw Error();if(!['true','false','null'].includes(token[0])&&!Number.isFinite(Number(token[0])))throw Error();i+=token[0].length;
    };
    // Iterative grammar avoids an arbitrary nesting-depth policy or call-stack overflow.
    while(true){ws();const f=frames.at(-1);
      if(!f){if(rootDone)break;rootDone=true;value();continue;}
      if(f.kind==='object'){
        if(f.state==='keyOrEnd'&&source[i]==='}'){i++;frames.pop();continue;}
        if(f.state==='keyOrEnd'||f.state==='key'){if(source[i]!=='"')throw Error();const k=string();if(f.keys.has(k))throw Error();f.keys.add(k);f.state='colon';continue;}
        if(f.state==='colon'){if(source[i++]!==':')throw Error();f.state='value';continue;}
      }else if(f.state==='valueOrEnd'&&source[i]===']'){i++;frames.pop();continue;}
      if(f.state==='value'||f.state==='valueOrEnd'){f.state='commaOrEnd';value();continue;}
      const end=source[i++];if(end===(f.kind==='object'?'}':']')){frames.pop();continue;}
      if(end!==',')throw Error();f.state=f.kind==='object'?'key':'value';
    }
    ws();if(i!==source.length)throw Error();return JSON.parse(source);
  } catch { throw new HttpError(422,'invalid_json'); }
}
