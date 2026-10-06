import test from 'node:test';
import assert from 'node:assert/strict';
import { validate } from './assert-cloudflare-plan.mjs';
const configAddress='cloudflare_zero_trust_tunnel_cloudflared_config.home_dev';
function fixture() {
 const ingress=[{hostname:'existing.example',service:'http://localhost:80'}, {hostname:'health-connect.example.com',service:'http://localhost:8790'}, {hostname:'matrix-outbound.example.com',service:'http://localhost:80'}, {service:'http_status:404'}];
 const before={tunnel_id:'synthetic-tunnel',account_id:'synthetic-account',config:{origin_request:{},ingress}};
 const after=structuredClone(before);after.config.ingress.splice(1,0,{hostname:'routebook-ingest.example.com',service:'http://127.0.0.1:8791'});
 return {prior_state:{values:{root_module:{resources:[{address:'cloudflare_zero_trust_tunnel_cloudflared.home_dev'}, {address:configAddress}, {type:'cloudflare_dns_record',values:{name:'health-connect.example.com',zone_id:'synthetic-zone'}}]}}},resource_changes:[
 {address:configAddress,change:{actions:['update'],before,after}},
 {address:'cloudflare_dns_record.retained["routebook_ingest"]',change:{actions:['create'],before:null,after:{name:'routebook-ingest.example.com',type:'CNAME',content:'synthetic-tunnel.cfargotunnel.com',zone_id:'synthetic-zone',proxied:true,ttl:1}}}]};
}
test('only dedicated route and DNS accepted',()=>assert.match(validate(fixture()),/accepted/));
test('computed-only unknown config timestamp accepted',()=>{
 const p=fixture();p.resource_changes[0].change.before.created_at='2026-10-05T00:00:00Z';
 p.resource_changes[0].change.after_unknown={created_at:true};
 assert.match(validate(p),/accepted/);
});
for(const [name,mutate] of [
 ['other route modified',p=>p.resource_changes[0].change.after.config.ingress[0].service='http://evil'],
 ['private listener exposed',p=>p.resource_changes[0].change.after.config.ingress[1].service='http://127.0.0.1:8792'],
 ['unexpected delete',p=>p.resource_changes.push({address:'foreign',change:{actions:['delete']}})],
 ['missing current state',p=>p.prior_state.values.root_module.resources=[]],
 ['wrong zone',p=>p.resource_changes[1].change.after.zone_id='wrong'],
 ['route order changed',p=>p.resource_changes[0].change.after.config.ingress.reverse()],
 ['interactive Access',p=>p.resource_changes[0].change.after.config.ingress[1].origin_request={access:{required:true}}],
 ['import mixed with publish',p=>p.resource_changes[1].change.importing={id:'foreign'}],
 ['no-op import mixed with publish',p=>p.resource_changes.push({address:'context',change:{actions:['no-op'],importing:{id:'existing'}}})],
 ['explicit config timestamp edit',p=>p.resource_changes[0].change.after.created_at='different'],
]) test(`reject ${name}`,()=>{const p=fixture();mutate(p);assert.throws(()=>validate(p));});
