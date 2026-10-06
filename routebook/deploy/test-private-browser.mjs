#!/usr/bin/env node
// Existing Playwright dependency only; no new browser/dependency installation.
import { chromium } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const origin=process.argv[2];assert(origin,'provide the checked private origin');
const evidence=new URL('./evidence/',import.meta.url);await mkdir(evidence,{recursive:true});
const browser=await chromium.launch({headless:true});
try {
 const page=await browser.newPage({viewport:{width:1440,height:950},ignoreHTTPSErrors:false});
 const errors=[],apiRequests=[];
 page.on('console',m=>{if(['error','warning'].includes(m.type()))errors.push({type:m.type(),text:m.text()});});
 page.on('pageerror',e=>errors.push({type:'pageerror',text:e.message}));
 page.on('request',r=>{if(new URL(r.url()).pathname.startsWith('/v1/'))apiRequests.push(new URL(r.url()).pathname);});
 const response=await page.goto(origin,{waitUntil:'networkidle'});assert.equal(response.status(),200);
 await page.getByRole('heading',{name:'Oczekiwanie na połączenie radia',exact:true}).waitFor();
 assert.equal(await page.title(),'Routebook · prywatna historia');
 assert.equal(await page.locator('vite-error-overlay').count(),0);
 await page.screenshot({path:new URL('h3-waiting-desktop.png',evidence).pathname,fullPage:true});
 await page.getByRole('button').click();await page.waitForLoadState('networkidle');
 await page.getByRole('heading',{name:'Oczekiwanie na połączenie radia',exact:true}).waitFor();
 await page.setViewportSize({width:390,height:844});
 assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
 await page.screenshot({path:new URL('h3-waiting-mobile.png',evidence).pathname,fullPage:true});
 assert.equal(apiRequests.length,0);assert.deepEqual(errors,[]);
 const result={origin,transport:origin.startsWith('https:')?'verified_https':'temporary_ssh_loopback_http',browser_plugin:'not_available',runner:'existing Playwright Chromium',page_identity:true,waiting_state:true,refresh_preserves_waiting:true,no_overlay:true,no_api_or_sse_requests:true,console_errors:0,viewports:['1440x950','390x844'],mobile_overflow:false};
 await writeFile(new URL('h3-browser.json',evidence),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
} finally {await browser.close();}
