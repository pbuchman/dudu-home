import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {PgStore} from '../../backend/dist/store.js';
import {createServers} from '../../backend/dist/server.js';
import {resolve} from 'node:path';
import https from 'node:https';
import {randomUUID,createHash} from 'node:crypto';
import {createRequire} from 'node:module';
const require=createRequire(new URL('../../backend/package.json',import.meta.url)),pg=require('pg');
const {chromium}=createRequire(new URL('../../ui/package.json',import.meta.url))('playwright');
const dir=process.env.ROUTEBOOK_INTEGRATION_PRIVATE,cfg=JSON.parse(await readFile(resolve(dir,'harness.json'),'utf8'));
const base='http://127.0.0.1:18580',q=`device_id=${cfg.device_id}`;let checks=0;
const ok=(v,msg)=>{assert.ok(v,msg);checks++;console.log('PASS '+msg);};
const get=async path=>{const r=await fetch(base+path);assert.equal(r.status,200);return r.json();};
const ledger=await get('/__test/ledger');const writes=ledger.ledger.filter(r=>r.method==='POST'&&r.status===200);
ok(writes.every(r=>r.commit_visible),'every successful ACK visible from independent DB client before forwarding');
ok(writes.slice(0,5).map(r=>r.lane).join(',')==='latest,latest,backfill,latest,backfill','lost ACK/restart: latest, retry latest, backfill, new latest, backfill');
ok(writes[0].ids[0]===writes[1].ids[0]&&writes[1].results[0].status==='duplicate','same persisted event ID retried; server duplicate after lost ACK');
ok(writes[2].ids.length===200&&writes[4].ids.length===39,'200-point backfill bounded; new point prioritized before remaining backlog');
ok(writes.some(r=>r.results.some(p=>p.status==='rejected'&&p.code==='invalid_point')),'real partial rejection ACK crosses HTTPS');
ok(writes.at(-1).results[0].status==='duplicate','invalid ACK recovery receives duplicate');
const pool=new pg.Pool({connectionString:cfg.database_url});
const row=(await pool.query('SELECT count(*)::int AS n,count(DISTINCT event_id)::int AS unique FROM routebook_points WHERE device_id=$1',[cfg.device_id])).rows[0];
ok(row.n===244&&row.unique===244,'244 unique committed points; retries did not duplicate');
const day=await get('/v1/day?'+q+'&date=2026-10-05');ok(day.point_count===244,'private day reads actual Android-uploaded points');
const latest=await get('/v1/latest?'+q);ok(latest.point.event_id==='00000000-0000-0000-0000-0000000001f5','latest never regresses when old backlog arrives');
for(const path of ['/','/index.html','/routebook-config.json','/health','/v1/latest?'+q,'/v1/day?'+q+'&date=2026-10-05','/v1/live?'+q]){
 const r=await fetch('http://127.0.0.1:18791'+path,{headers:{Authorization:'Bearer '+cfg.token}});ok(r.status===404,'public listener returns 404: '+path.split('?')[0]);
}
ok((await fetch('http://127.0.0.1:18791/v1/ingest',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).status===401,'public unauthenticated ingest 401');
ok((await get('/health')).status==='ok','private DB health works');
const emptyStore=new PgStore(pool);await emptyStore.bootstrap([]);
const emptyServers=createServers(emptyStore,{devices:[]});
await emptyServers.publicApp.listen({host:'127.0.0.1',port:18793});await emptyServers.privateApp.listen({host:'127.0.0.1',port:18794});
try {
 ok((await fetch('http://127.0.0.1:18793/v1/ingest',{method:'POST',headers:{Authorization:'Bearer '+cfg.token,'Content-Type':'application/json'},body:'{}'})).status===401,'unpaired backend rejects every ingest token');
 ok((await fetch('http://127.0.0.1:18794/health')).status===200,'bootstrap([]) and unpaired private health work');
}finally{await emptyServers.publicApp.close();await emptyServers.privateApp.close();}
const ca=await readFile(resolve(dir,'ca.pem'));
async function ingest(point){return new Promise((resolve,reject)=>{
 const body=JSON.stringify({version:1,device_id:cfg.device_id,lane:'latest',points:[point]});
 const req=https.request('https://localhost:18443/v1/ingest',{ca,method:'POST',headers:{Authorization:'Bearer '+cfg.token,'Content-Type':'application/json','Content-Length':Buffer.byteLength(body)}},async res=>{const b=[];for await(const c of res)b.push(c);try{assert.equal(res.statusCode,200);resolve(JSON.parse(Buffer.concat(b)));}catch(e){reject(e);}});req.on('error',reject);req.end(body);
});}
const browser=await chromium.launch({headless:true,args:['--enable-webgl','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
try{
 const page=await browser.newPage({viewport:{width:1440,height:950}}),errors=[],auth=[],requests=[];
 let disconnectRequested=false;page.on('pageerror',e=>errors.push({text:e.message,expected:false}));page.on('console',m=>{if(['error','warning'].includes(m.type())){const text=m.text(),url=m.location().url;errors.push({text,url,expected:(m.type()==='warning'&&text.startsWith('[.WebGL-')&&text.includes('GPU stall due to ReadPixels'))||(disconnectRequested&&url.includes('/v1/live?')&&text==='Failed to load resource: net::ERR_INCOMPLETE_CHUNKED_ENCODING')});}});
 page.on('request',r=>{if(r.url().includes('/v1/')){requests.push(r.url());if(r.headers().authorization)auth.push(r.url());}});
 // Only the map provider style/logo are replaced. All /v1 reads and SSE use actual backend sockets.
 await page.route('https://api.maptiler.com/**',async route=>{if(route.request().url().includes('/style.json'))await route.fulfill({json:{version:8,name:'Local test style (no MapTiler verification)',sources:{},layers:[{id:'background',type:'background',paint:{'background-color':'#eff4f5'}}]}});else await route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="170" height="26"><text x="0" y="18">LOCAL TEST STYLE</text></svg>'});});
 await page.clock.setFixedTime(new Date('2026-10-05T12:00:00.000Z'));
 await page.goto(base);assert.equal(await page.title(),'Routebook · prywatna historia');ok(page.url()===base+'/','page identity and actual private UI origin');
 await page.getByLabel('Wybrany dzień').fill('2026-10-05');
 await page.waitForFunction(()=>document.querySelector('.metrics')?.textContent.includes('244'));
 await page.waitForFunction(()=>document.querySelector('canvas')&& !document.querySelector('.map-state'));
 ok(await page.getByText('Prywatne archiwum',{exact:true}).isVisible()&&await page.getByText('Scenariusze demonstracyjne',{exact:true}).count()===0,'production API adapter, demo absent');
 ok(await page.locator('.connection').textContent()==='Połączono','native EventSource connected to real Fastify');
 await page.screenshot({path:'tests/integration/evidence/desktop.png',fullPage:true});
 const oldRequestCount=requests.length;
 const event=randomUUID(),point={event_id:event,segment_id:randomUUID(),measured_at:'2026-10-05T09:00:00.000Z',lat:0,lon:.026,accuracy_m:5,speed_mps:2};
 const ack=await ingest(point);
 await page.waitForFunction(()=>document.querySelector('.metrics')?.textContent.includes('245'));
 ok(requests.length>oldRequestCount,'committed real SSE update refetches selected day');
 ok((await get('/v1/latest?'+q)).revision===ack.revision,'ACK revision visible in committed read');
 await page.getByLabel('Wybrany dzień').fill('2026-10-04');
 await page.waitForFunction(()=>document.querySelector('.metrics')?.textContent.includes('0,00'));
 const before=requests.length;disconnectRequested=true;await fetch(base+'/__test/disconnect');
 await page.waitForFunction(()=>document.querySelector('.connection')?.textContent==='Połączono',null,{timeout:15000});
 await page.waitForFunction(()=>document.querySelector('.connection')?.textContent==='Połączono');
 // Wait for reconnect snapshot's actual selected-day fetch, not merely connection open.
 for(let i=0;i<100&&requests.length<=before;i++)await new Promise(r=>setTimeout(r,100));
 ok(requests.length>before&&await page.getByLabel('Wybrany dzień').inputValue()==='2026-10-04','real SSE reconnect refetches and preserves selected historical day');
 await page.getByRole('button',{name:'Zakres',exact:true}).click();

 await page.waitForFunction(()=>document.querySelector('.range-progress')?.textContent.includes('2/2 dni · pobrano wszystkie dni'));
 ok((await get('/__test/ledger')).maxDayActive<=2,'actual private day sockets: max two concurrent requests');
 await page.screenshot({path:'tests/integration/evidence/range.png',fullPage:true});
 await page.setViewportSize({width:390,height:844});await page.getByRole('button',{name:'Dzień',exact:true}).click();await page.getByLabel('Wybrany dzień').fill('2026-10-05');
 await page.waitForFunction(()=>document.querySelector('.metrics')?.textContent.includes('245'));
 ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'mobile 390x844 has no horizontal overflow');
 await page.screenshot({path:'tests/integration/evidence/mobile.png',fullPage:true});
 await writeFile('tests/integration/evidence/browser-console.json',JSON.stringify(errors,null,2));ok(errors.every(e=>e.expected),'no unexpected app errors or framework overlay: '+errors.filter(e=>!e.expected).map(e=>e.text+' '+e.url).join('; '));ok(auth.length===0,'no ingest Authorization in UI API requests');
 const runtime=JSON.stringify(await get('/routebook-config.json'));ok(!runtime.includes(cfg.token)&&!runtime.includes('token'),'ingest token absent from UI runtime');
 await writeFile(resolve(dir,'fault.json'),JSON.stringify({mode:'waiting'}),{mode:0o600});const requestStart=requests.length;await page.reload();
 await page.getByRole('heading',{name:'Oczekiwanie na połączenie radia'}).waitFor();await page.getByRole('button',{name:'Odśwież',exact:true}).click();await page.getByRole('heading',{name:'Oczekiwanie na połączenie radia'}).waitFor();
 ok(requests.length===requestStart,'unpaired UI waits without any API/SSE calls');await page.screenshot({path:'tests/integration/evidence/waiting.png',fullPage:true});
}catch(e){throw e;}finally{await browser.close();await pool.end();}
console.log(`PASS real integration ${checks} checks; MapTiler style substituted, provider unverified`);
