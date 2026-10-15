import { parentPort,workerData } from 'node:worker_threads';
import { analytics,journeyDetail } from './analytics.js';
import { HttpError } from './types.js';
try {
  const {snapshot,from,to,journey_id,derivation}=workerData;
  const response=journey_id?journeyDetail(snapshot,from,to,journey_id,derivation):analytics(snapshot,from,to,derivation);
  parentPort!.postMessage({response});
}catch(error){parentPort!.postMessage({error:error instanceof HttpError?{status:error.statusCode,code:error.code}:{status:503,code:'analytics_unavailable'}});}
