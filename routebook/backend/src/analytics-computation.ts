import { Worker } from 'node:worker_threads';
import { HttpError,type Derivation } from './types.js';
import type { AnalyticsSnapshot } from './analytics.js';
import type { AnalyticsResponse,JourneyDetailResponse } from '../../contract/analytics.js';
/** CPU derivation stays off the ingest/SSE event loop, with one bounded job per listener. */
export async function computeAnalytics(snapshot:AnalyticsSnapshot,from:string,to:string,derivation:Derivation,journey_id?:string,signal?:AbortSignal):Promise<AnalyticsResponse|JourneyDetailResponse> {
  if(signal?.aborted)throw new HttpError(503,'analytics_unavailable');
  const source=import.meta.url.endsWith('.ts');
  const worker=new Worker(new URL(source?'./analytics-worker.ts':'./analytics-worker.js',import.meta.url),{
    workerData:{snapshot,from,to,derivation,journey_id},
    execArgv:source?['--import','tsx']:[],resourceLimits:{maxOldGenerationSizeMb:1024}
  });
  let timer:ReturnType<typeof setTimeout>|undefined;
  let abort:(()=>void)|undefined;
  try{return await new Promise((resolve,reject)=>{
    timer=setTimeout(()=>reject(new HttpError(503,'analytics_timeout')),20000);timer.unref();
    worker.once('message',message=>message.error?reject(new HttpError(message.error.status,message.error.code)):resolve(message.response));
    worker.once('error',()=>reject(new HttpError(503,'analytics_unavailable')));
    worker.once('exit',code=>{if(code!==0)reject(new HttpError(503,'analytics_unavailable'));});
    abort=()=>reject(new HttpError(503,'analytics_unavailable'));signal?.addEventListener('abort',abort,{once:true});
  });}finally{if(timer)clearTimeout(timer);if(abort)signal?.removeEventListener('abort',abort);await worker.terminate();}
}
