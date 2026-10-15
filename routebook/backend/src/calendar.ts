import { Temporal } from '@js-temporal/polyfill';
import { HttpError } from './types.js';
export const timezone='Europe/Warsaw';
export function date(x: unknown): string {
  try {if(typeof x!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(x)||Temporal.PlainDate.from(x).toString()!==x)throw Error();return x;}
  catch {throw new HttpError(422,'invalid_date');}
}
export const nextDate=(d:string,days=1)=>Temporal.PlainDate.from(d).add({days}).toString();
export const localDate=(t:string)=>Temporal.Instant.from(t).toZonedDateTimeISO(timezone).toPlainDate().toString();
export function dayBounds(d:string) {
  const start=Temporal.PlainDate.from(date(d)).toZonedDateTime(timezone).epochMilliseconds;
  const end=Temporal.PlainDate.from(nextDate(d)).toZonedDateTime(timezone).epochMilliseconds;
  return {start,end,start_at:new Date(start).toISOString(),end_at:new Date(end).toISOString(),seconds:(end-start)/1000};
}
export function dates(from:string,to:string) {date(from);date(to);if(from>to)throw new HttpError(422,'invalid_range');const result=[];for(let d=from;d<=to;d=nextDate(d)){result.push(d);if(result.length>366)throw new HttpError(422,'invalid_range');}return result;}
