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
  await write(config);assert.deepEqual((await loadConfig(path)).derivation,defaults);assert.deepEqual((await loadConfig(path)).power_profiles,[]);
  const profile={profile_id:'synthetic-calibrated',firmware_id:'synthetic-firmware',validation_id:'synthetic-evidence',validated_at:'2026-01-01T00:00:00.000Z',states:[{acc_state:0,module1_state:0,state:'off'},{acc_state:1,module1_state:1,state:'on'}]};
  await write({...config,power_profiles:[profile]});assert.equal((await loadConfig(path)).power_profiles.length,1);
  for(const bad of [{...profile,profile_id:'diagnostic-v1'},{...profile,states:[{acc_state:0,module1_state:null,state:'off'},profile.states[1]]},{...profile,states:[profile.states[0],profile.states[0]]},{...profile,validated_at:'2999-01-01T00:00:00.000Z'},{...profile,validation_id:''}]){await write({...config,power_profiles:[bad]});await assert.rejects(loadConfig(path));}
  await write({...config,devices:[]});assert.deepEqual((await loadConfig(path)).devices,[]);
  await chmod(path,0o644);await assert.rejects(loadConfig(path),/0600/);
  for(const x of [{...config,public:{host:'0.0.0.0',port:8791}},{...config,private:config.public},{...config,devices:[...config.devices,...config.devices]},{...config,devices:[{device_id:fixtureDevice,token:'short'}]},{...config,derivation:{gap_seconds:0}},{...config,extra:1}]){await write(x);await assert.rejects(loadConfig(path));}
 }finally{await rm(dir,{recursive:true});}
});
