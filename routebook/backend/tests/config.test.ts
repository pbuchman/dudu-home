import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp,writeFile,chmod,rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { loadConfig } from '../src/config.js';
import { fixtureDevice,token } from './helpers.js';
import { defaults } from '../src/types.js';
test('private runtime config validates permissions, binding, loopback and thresholds',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'routebook-config-test-')),path=join(dir,'config.json');
 const config={database_url:'postgresql://postgres@127.0.0.1/routebook_synthetic',public:{host:'127.0.0.1',port:8791},private:{host:'127.0.0.1',port:8792},devices:[{device_id:fixtureDevice,token}],derivation:{},rate_limit_per_minute:120};
 const write=async(x:any)=>{await writeFile(path,JSON.stringify(x),{mode:0o600});await chmod(path,0o600);};
 try {
  await write(config);assert.deepEqual((await loadConfig(path)).derivation,defaults);
  await write({...config,devices:[]});assert.deepEqual((await loadConfig(path)).devices,[]);
  await chmod(path,0o644);await assert.rejects(loadConfig(path),/0600/);
  for(const x of [{...config,public:{host:'0.0.0.0',port:8791}},{...config,private:config.public},{...config,devices:[...config.devices,...config.devices]},{...config,devices:[{device_id:fixtureDevice,token:'short'}]},{...config,derivation:{gap_seconds:0}},{...config,extra:1}]){await write(x);await assert.rejects(loadConfig(path));}
 }finally{await rm(dir,{recursive:true});}
});
