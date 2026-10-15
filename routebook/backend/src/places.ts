import type { NamedPlace, PlacesResponse } from '../../contract/analytics.js';
import { HttpError } from './types.js';
import { exact, record } from './validation.js';
import type { AnalyticsSnapshot } from './analytics.js';
export interface AnalyticsStore {
  analyticsSnapshot(deviceId:string,bounds:{start_at:string;end_at:string}):Promise<AnalyticsSnapshot>;
  places(deviceId:string):Promise<PlacesResponse>;
  writePlace(deviceId:string,placeId:string|null,input:PlaceInput|null,expectedRevision:number):Promise<PlacesResponse>;
}
export type PlaceInput=Omit<NamedPlace,'place_id'>;
export function placeBody(body:unknown,deleting=false):{input:PlaceInput|null;expectedRevision:number} {
  if(!record(body)||!Number.isSafeInteger(body.expected_metadata_revision)||Number(body.expected_metadata_revision)<0)throw new HttpError(422,'invalid_place');
  const expectedRevision=Number(body.expected_metadata_revision);
  if(deleting){if(!exact(body,['expected_metadata_revision']))throw new HttpError(422,'invalid_place');return {input:null,expectedRevision};}
  if(!exact(body,Object.hasOwn(body,'radius_m')?['expected_metadata_revision','name','lat','lon','radius_m']:['expected_metadata_revision','name','lat','lon']))throw new HttpError(422,'invalid_place');
  const {name,lat,lon}=body,radius_m=Object.hasOwn(body,'radius_m')?body.radius_m:100;
  if(typeof name!=='string'||name!==name.trim()||Array.from(name).length<1||Array.from(name).length>80||/[\p{Cc}\p{Cf}]/u.test(name)||
    typeof lat!=='number'||!Number.isFinite(lat)||lat< -90||lat>90||typeof lon!=='number'||!Number.isFinite(lon)||lon< -180||lon>180||
    typeof radius_m!=='number'||!Number.isFinite(radius_m)||radius_m<25||radius_m>1000)throw new HttpError(422,'invalid_place');
  return {input:{name,lat,lon,radius_m},expectedRevision};
}
