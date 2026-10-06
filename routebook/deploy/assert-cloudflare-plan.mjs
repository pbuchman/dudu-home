#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';
const defaultPolicy={host:'routebook-ingest.example.com',healthHost:'health-connect.example.com',matrixHost:'matrix-outbound.example.com'};
const configAddress='cloudflare_zero_trust_tunnel_cloudflared_config.home_dev';
const dnsAddress='cloudflare_dns_record.retained["routebook_ingest"]';
function resources(module) { return [...(module?.resources??[]),...(module?.child_modules??[]).flatMap(resources)]; }
export function validate(plan, policy=defaultPolicy) {
  const {host,healthHost,matrixHost}=policy;
  assert([host,healthHost,matrixHost].every(v=>typeof v==='string' && v.length>0));
  assert.equal(new Set([host,healthHost,matrixHost]).size,3);
  assert.equal(plan.errored??false,false,'plan errored');
  const prior=resources(plan.prior_state?.values?.root_module);
  for(const address of ['cloudflare_zero_trust_tunnel_cloudflared.home_dev',configAddress])
    assert(prior.some(r=>r.address===address),'missing authoritative tunnel/config coverage');
  const managed=(plan.resource_changes??[]).filter(r=>r.mode!=='data');
  assert(managed.every(r=>!r.change.importing),'no imports in publication plan; reconcile separately');
  const changes=managed.filter(r=>JSON.stringify(r.change.actions)!=='["no-op"]');
  assert.equal(changes.length,2,'exactly route config update and Routebook DNS creation allowed');
  assert(changes.every(r=>!r.change.importing),'no imports in publication plan; reconcile separately');
  const c=changes.find(r=>r.address===configAddress),d=changes.find(r=>r.address===dnsAddress);
  assert(c&&d,'unexpected resource changes');
  assert.deepEqual(c.change.actions,['update']);assert.deepEqual(d.change.actions,['create']);
  const before=structuredClone(c.change.before),after=structuredClone(c.change.after);
  const old=before.config.ingress,next=after.config.ingress;
  assert(!old.some(r=>r.hostname===host),'Routebook already present; fresh no-op/update review required');
  assert.equal(next.length,old.length+1);
  const added=next.filter(r=>r.hostname===host);assert.equal(added.length,1);
  assert.equal(added[0].service,'http://127.0.0.1:8791');assert(!added[0].path);
  assert(!added[0].origin_request?.access?.required,'interactive Access blocks machine ingest');
  const health=old.findIndex(r=>r.hostname===healthHost);
  assert(health>=0&&old.some(r=>r.hostname===matrixHost),'live Health Connect/Matrix coverage required');
  assert.equal(next.findIndex(r=>r.hostname===host),health,'insert immediately before Health Connect');
  assert.equal(old.at(-1).service,'http_status:404');assert(!old.at(-1).hostname&&!old.at(-1).path);
  after.config.ingress=next.filter(r=>r.hostname!==host);
  // Terraform provider may emit computed version changes. All routing/options stay exact.
  delete before.version;delete after.version;
  // Provider 5.24.0 marks created_at as computed-only and unknown on config update.
  if(c.change.after_unknown?.created_at===true) {
    assert(!Object.hasOwn(after,'created_at'),'computed timestamp must actually be unknown');
    delete before.created_at;
  }
  assert.deepEqual(after,before,'unrelated tunnel/origin fields changed');
  const dns=d.change.after;
  assert.equal(dns.name,host);assert.equal(dns.type,'CNAME');assert.equal(dns.proxied,true);assert.equal(dns.ttl,1);
  assert.equal(dns.content,`${before.tunnel_id}.cfargotunnel.com`);
  const existingHealth=prior.find(r=>r.type==='cloudflare_dns_record'&&r.values?.name===healthHost);
  assert(existingHealth,'reconcile Health Connect DNS ownership first');
  assert.equal(dns.zone_id,existingHealth.values.zone_id,'Routebook must use inventoried existing zone');
  return 'Routebook-only publication delta accepted; apply authorization is separate';
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href) {
  try { assert.equal(process.argv.length,4,'provide private plan and policy JSON paths'); console.log(validate(JSON.parse(readFileSync(process.argv[2],'utf8')),JSON.parse(readFileSync(process.argv[3],'utf8')))); }
  catch { console.error('Rejected Cloudflare plan: reconcile current state and inspect Routebook-only delta privately');process.exitCode=1; }
}
