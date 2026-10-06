import { readFile, stat, realpath } from 'node:fs/promises';
import { resolve, relative, isAbsolute, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { defaults, type Derivation } from './types.js';
import { exact, record, strictJson, uuid } from './validation.js';
export interface Config { database_url:string; public:{host:string;port:number}; private:{host:string;port:number}; devices:{device_id:string;token:string}[]; derivation:Derivation; rate_limit_per_minute:number }
export async function loadConfig(path:string):Promise<Config> {
  const root=fileURLToPath(new URL('../../',import.meta.url));const p=await realpath(resolve(path));const rel=relative(root,p);
  if(rel!=='..'&&!rel.startsWith('..'+sep)&&!isAbsolute(rel))throw Error('Runtime config must be outside repository');
  const s=await stat(p);if(!s.isFile()||(s.mode&0o077)!==0)throw Error('Runtime config requires mode 0600');
  const x=strictJson(await readFile(p));
  if(!record(x)||!exact(x,['database_url','public','private','devices','derivation','rate_limit_per_minute'])||typeof x.database_url!=='string'||!/^postgres(?:ql)?:\/\//.test(x.database_url)||!Array.isArray(x.devices))throw Error('Invalid runtime config');
  for(const listener of [x.public,x.private])if(!record(listener)||!exact(listener,['host','port'])||listener.host!=='127.0.0.1'||!Number.isInteger(listener.port)||Number(listener.port)<1||Number(listener.port)>65535)throw Error('Listeners require distinct loopback ports');
  if((x.public as any).port===(x.private as any).port)throw Error('Listeners require distinct loopback ports');
  const tokens=new Set<string>(),ids=new Set<string>();for(const d of x.devices) {
    if(!record(d)||!exact(d,['device_id','token'])||!uuid(d.device_id)||typeof d.token!=='string'||!/^[A-Za-z0-9_-]{32,256}$/.test(d.token)||tokens.has(d.token)||ids.has(d.device_id))throw Error('Invalid token/device binding');tokens.add(d.token);ids.add(d.device_id);
  }
  if(!record(x.derivation)||Object.keys(x.derivation).some(k=>!Object.hasOwn(defaults,k)))throw Error('Invalid derivation');
  const derivation={...defaults,...x.derivation};for(const v of Object.values(derivation))if(typeof v!=='number'||!Number.isFinite(v)||v<=0)throw Error('Invalid derivation');
  if(!Number.isInteger(x.rate_limit_per_minute)||Number(x.rate_limit_per_minute)<1)throw Error('Invalid rate limit');
  return {...x,derivation} as unknown as Config;
}
