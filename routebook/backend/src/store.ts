import { calculateCorrection, type PowerProfile } from './in-car-time.js';
import { recordedTimeSql } from './recorded-time.js';
import pg from 'pg';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { analyticsLimits, type AnalyticsSnapshot } from './analytics.js';
import type { AnalyticsStore, PlaceInput } from './places.js';
import type { PlacesResponse } from '../../contract/analytics.js';
import { connected, localDate, nextDate } from './derive.js';
import { record, samePoint, uuid, validPoint, validObservation, sameObservation } from './validation.js';
import { HttpError, type Envelope, type Store, type Snapshot, type StoredPoint, type Result, type StoredObservation, defaults, type Derivation } from './types.js';
const columns='device_id,event_id,segment_id,measured_at,received_at,lat,lon,accuracy_m,speed_mps';
const observationColumns='device_id,event_id,observer_epoch,sequence,observed_at,received_at,elapsed_realtime_ms,boot_id,kind,acc_state,module1_state,interpretation_profile,firmware_id';
function observation(row:Record<string,any>):StoredObservation{return {...row,sequence:Number(row.sequence),elapsed_realtime_ms:Number(row.elapsed_realtime_ms),observed_at:row.observed_at.toISOString(),received_at:row.received_at.toISOString()} as StoredObservation;}
function point(row:Record<string,any>):StoredPoint {return {...row,measured_at:row.measured_at.toISOString(),received_at:row.received_at.toISOString()} as StoredPoint;}
export class PgStore implements Store,AnalyticsStore {
  constructor(readonly pool:pg.Pool,readonly derivation:Derivation=defaults,readonly powerProfiles:PowerProfile[]=[],readonly now:()=>string=()=>new Date().toISOString()) {}
  async bootstrap(deviceIds:string[]) {
    const c=await this.pool.connect();try {await c.query('BEGIN');await c.query('SELECT pg_advisory_xact_lock($1)', [726_268_001]);await c.query(await readFile(new URL('../sql/schema-v1.sql',import.meta.url),'utf8'));
      for(const id of deviceIds)await c.query('INSERT INTO routebook_devices(device_id) VALUES($1) ON CONFLICT DO NOTHING',[id]);
      await c.query(await readFile(new URL('../sql/schema-analytics-v1.sql',import.meta.url),'utf8'));await c.query(await readFile(new URL('../sql/schema-observations-v2.sql',import.meta.url),'utf8'));
      for(const profile of this.powerProfiles){await c.query('INSERT INTO routebook_power_profile_audit(profile_id,body) VALUES($1,$2::jsonb) ON CONFLICT DO NOTHING',[profile.profile_id,JSON.stringify(profile)]);const check=await c.query('SELECT body=$2::jsonb same FROM routebook_power_profile_audit WHERE profile_id=$1',[profile.profile_id,JSON.stringify(profile)]);if(!check.rows[0].same)throw Error('Power profile identity is immutable');}
      await c.query('COMMIT');
    }catch(e){await c.query('ROLLBACK').catch(()=>{});throw e;}finally{c.release();}
  }
  async assertPowerProfiles(){for(const profile of this.powerProfiles){const check=await this.pool.query('SELECT body=$2::jsonb same FROM routebook_power_profile_audit WHERE profile_id=$1',[profile.profile_id,JSON.stringify(profile)]);if(!check.rowCount||!check.rows[0].same)throw Error('Power profile requires matching immutable audit registration');}}
  async assertDurability(){const settings=await this.pool.query("SELECT current_setting('fsync') fsync,current_setting('full_page_writes') full_page_writes");if(settings.rows[0].fsync!=='on'||settings.rows[0].full_page_writes!=='on')throw Error('Durable WAL settings required');}
  async health(){await this.pool.query('SELECT 1');}
  async analyticsSnapshot(deviceId:string,bounds:{start_at:string;end_at:string}):Promise<AnalyticsSnapshot> {
    const c=await this.pool.connect();try {
      await c.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');await c.query("SET LOCAL statement_timeout = '8s'");
      const device=await c.query('SELECT d.revision,m.metadata_revision FROM routebook_devices d JOIN routebook_metadata m USING(device_id) WHERE device_id=$1',[deviceId]);
      if(!device.rowCount)throw new HttpError(404,'unknown_device');
      const rows=await c.query(`SELECT ${columns} FROM routebook_points WHERE device_id=$1 AND measured_at >= $2 AND measured_at < $3 ORDER BY measured_at,event_id LIMIT $4`,[deviceId,bounds.start_at,bounds.end_at,analyticsLimits.max_points+1]);
      if(rows.rows.length>analyticsLimits.max_points)throw new HttpError(422,'analytics_limit');
      const places=await c.query('SELECT place_id,name,lat,lon,radius_m FROM routebook_places WHERE device_id=$1 ORDER BY place_id',[deviceId]);
      const outside=await c.query('SELECT EXISTS(SELECT 1 FROM routebook_points WHERE device_id=$1 AND measured_at<$2) has_before,EXISTS(SELECT 1 FROM routebook_points WHERE device_id=$1 AND measured_at>=$3) has_after',[deviceId,bounds.start_at,bounds.end_at]);
      const correction=await this.readCorrection(c,deviceId,bounds,rows.rows.map(point),!outside.rows[0].has_before&&!outside.rows[0].has_after);
      await c.query('COMMIT');return {correction,device_id:deviceId,revision:Number(device.rows[0].revision),metadata_revision:Number(device.rows[0].metadata_revision),points:rows.rows.map(point),places:places.rows,...outside.rows[0]};
    }catch(e){await c.query('ROLLBACK').catch(()=>{});throw e;}finally{c.release();}
  }
  private async readCorrection(c:pg.PoolClient,deviceId:string,bounds:{start_at:string;end_at:string},knownPoints?:StoredPoint[],archiveComplete=false) {
    // Full-resolution source segments, not map sampling or bounded journey windows. The
    // indexed seed finds every source with possible coverage in the requested bounds.
    if(knownPoints&&!archiveComplete){const first=knownPoints[0]?.measured_at??bounds.start_at,last=knownPoints.at(-1)?.measured_at??bounds.end_at;
      const outside=await c.query(`WITH outer_bounds AS (SELECT EXISTS(SELECT 1 FROM routebook_points WHERE device_id=$1 AND (measured_at,event_id)<($2::timestamptz,$4::uuid)) OR EXISTS(SELECT 1 FROM routebook_points WHERE device_id=$1 AND (measured_at,event_id)>($3::timestamptz,$5::uuid)) remaining) SELECT remaining FROM outer_bounds`,[deviceId,first,last,knownPoints[0]?.event_id??'00000000-0000-0000-0000-000000000000',knownPoints.at(-1)?.event_id??'00000000-0000-0000-0000-000000000000']);archiveComplete=!outside.rows[0].remaining;}
    const rows=archiveComplete?null:await c.query(`WITH source_segments AS (
      SELECT DISTINCT segment_id FROM routebook_points WHERE device_id=$1 AND measured_at >= $2::timestamptz - interval '30 seconds' AND measured_at < $3::timestamptz + interval '30 seconds'
    ), archive AS (
      SELECT p.* FROM routebook_points p JOIN source_segments s USING(segment_id) WHERE p.device_id=$1
    ), following AS (
      SELECT next.* FROM (SELECT segment_id,max(measured_at) end_at FROM archive GROUP BY segment_id) tails
      CROSS JOIN LATERAL (SELECT p.* FROM routebook_points p WHERE p.device_id=$1 AND p.measured_at>=tails.end_at AND p.segment_id<>tails.segment_id ORDER BY measured_at,event_id LIMIT 1) next
    ), following_strict AS (
      SELECT next.* FROM (SELECT segment_id,max(measured_at) end_at FROM archive GROUP BY segment_id) tails
      CROSS JOIN LATERAL (SELECT p.* FROM routebook_points p WHERE p.device_id=$1 AND p.measured_at>tails.end_at ORDER BY measured_at,event_id LIMIT 1) next
    ) SELECT ${columns} FROM (SELECT ${columns} FROM archive UNION ALL SELECT ${columns} FROM following UNION ALL SELECT ${columns} FROM following_strict) full_raw ORDER BY measured_at,event_id`,[deviceId,bounds.start_at,bounds.end_at]);
    const points=rows?[...new Map(rows.rows.map(row=>{const p=point(row);return [p.event_id,p];})).values()]:knownPoints!,start=points[0]?.measured_at??bounds.start_at,end=points.at(-1)?.measured_at??bounds.end_at;
    const events=await c.query(`SELECT ${observationColumns} FROM routebook_observations WHERE device_id=$1 AND observed_at >= $2::timestamptz - interval '45 seconds' AND observed_at <= $3::timestamptz + interval '45 seconds' ORDER BY observed_at,event_id`,[deviceId,start,end]);
    return calculateCorrection(points,events.rows.map(observation),this.powerProfiles,this.now());
  }
  async places(deviceId:string):Promise<PlacesResponse> {
    const c=await this.pool.connect();try{await c.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
      const device=await c.query('SELECT metadata_revision FROM routebook_metadata WHERE device_id=$1',[deviceId]);if(!device.rowCount)throw new HttpError(404,'unknown_device');
      const places=await c.query('SELECT place_id,name,lat,lon,radius_m FROM routebook_places WHERE device_id=$1 ORDER BY place_id',[deviceId]);
      await c.query('COMMIT');return {version:1,device_id:deviceId,metadata_revision:Number(device.rows[0].metadata_revision),places:places.rows};
    }catch(e){await c.query('ROLLBACK').catch(()=>{});throw e;}finally{c.release();}
  }
  async writePlace(deviceId:string,placeId:string|null,input:PlaceInput|null,expectedRevision:number):Promise<PlacesResponse> {
    const c=await this.pool.connect();try{await c.query('BEGIN');await c.query("SET LOCAL synchronous_commit = on");await c.query("SET LOCAL statement_timeout = '8s'");await c.query("SET LOCAL lock_timeout = '5s'");
      const device=await c.query('SELECT metadata_revision FROM routebook_metadata WHERE device_id=$1 FOR UPDATE',[deviceId]);if(!device.rowCount)throw new HttpError(404,'unknown_device');
      if(Number(device.rows[0].metadata_revision)!==expectedRevision)throw new HttpError(409,'metadata_conflict');
      if(placeId){const found=await c.query('SELECT 1 FROM routebook_places WHERE device_id=$1 AND place_id=$2',[deviceId,placeId]);if(!found.rowCount)throw new HttpError(404,'place_not_found');}
      if(input){if(!placeId){const count=await c.query('SELECT count(*)::int count FROM routebook_places WHERE device_id=$1',[deviceId]);if(count.rows[0].count>=200)throw new HttpError(422,'place_limit');}
        await c.query('INSERT INTO routebook_places(device_id,place_id,name,lat,lon,radius_m) VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT(device_id,place_id) DO UPDATE SET name=excluded.name,lat=excluded.lat,lon=excluded.lon,radius_m=excluded.radius_m',[deviceId,placeId??randomUUID(),input.name,input.lat,input.lon,input.radius_m]);
      }else{if(!placeId)throw new HttpError(422,'invalid_place');await c.query('DELETE FROM routebook_places WHERE device_id=$1 AND place_id=$2',[deviceId,placeId]);}
      const revision=await c.query('UPDATE routebook_metadata SET metadata_revision=metadata_revision+1 WHERE device_id=$1 RETURNING metadata_revision',[deviceId]);
      const places=await c.query('SELECT place_id,name,lat,lon,radius_m FROM routebook_places WHERE device_id=$1 ORDER BY place_id',[deviceId]);
      await c.query('COMMIT');return {version:1,device_id:deviceId,metadata_revision:Number(revision.rows[0].metadata_revision),places:places.rows};
    }catch(e){await c.query('ROLLBACK').catch(()=>{});throw e;}finally{c.release();}
  }
  async latestSnapshot(deviceId:string):Promise<Snapshot> { return this.readSnapshot(deviceId,'latest'); }
  async snapshot(deviceId:string,bounds?:{start_at:string;end_at:string}):Promise<Snapshot> {return this.readSnapshot(deviceId,bounds??'all');}
  private async readSnapshot(deviceId:string,scope:'latest'|'all'|{start_at:string;end_at:string}):Promise<Snapshot> {
    const c=await this.pool.connect();try {await c.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
      await c.query("SET LOCAL statement_timeout = '8s'");
      const device=await c.query('SELECT revision FROM routebook_devices WHERE device_id=$1',[deviceId]);if(!device.rowCount)throw new HttpError(404,'unknown_device');
      let points:StoredPoint[];
      if(scope==='latest') {
        const rows=await c.query(`SELECT ${columns} FROM routebook_points WHERE device_id=$1 ORDER BY measured_at DESC,event_id DESC LIMIT 1`,[deviceId]);points=rows.rows.map(point);
      }else if(scope==='all') {
        // Explicit full snapshot is used by synthetic integration inspection, never by HTTP handlers.
        const rows=await c.query(`SELECT ${columns} FROM routebook_points WHERE device_id=$1 ORDER BY measured_at,event_id`,[deviceId]);points=rows.rows.map(point);
      }else {
        const rows=await c.query(`SELECT ${columns} FROM routebook_points WHERE device_id=$1 AND measured_at >= $2 AND measured_at < $3 ORDER BY measured_at,event_id`,[deviceId,scope.start_at,scope.end_at]);points=rows.rows.map(point);
        // Always seed predecessor/successor: needed for an edge/stop crossing a boundary.
        const before=await c.query(`SELECT ${columns} FROM routebook_points WHERE device_id=$1 AND measured_at < $2 ORDER BY measured_at DESC,event_id DESC LIMIT 1`,[deviceId,scope.start_at]);
        const after=await c.query(`SELECT ${columns} FROM routebook_points WHERE device_id=$1 AND measured_at >= $2 ORDER BY measured_at,event_id LIMIT 1`,[deviceId,scope.end_at]);
        if(before.rowCount)points.unshift(point(before.rows[0]));if(after.rowCount)points.push(point(after.rows[0]));
        // Extend only the adjacent contiguous runs. Run IDs and arbitrarily long stationary
        // candidates require their real anchor, rather than a fixed +/-180 second padding.
        for(const direction of ['before','after'] as const) {
          let cursor=direction==='before'?points[0]:points.at(-1);if(!cursor||points.length<2)continue;
          if(direction==='before'&&!connected(points[0],points[1],this.derivation))continue;
          if(direction==='after'&&!connected(points[points.length-2],points[points.length-1],this.derivation))continue;
          while(true) {
            const backwards=direction==='before';
            const batch=await c.query(`SELECT ${columns} FROM routebook_points WHERE device_id=$1 AND (measured_at,event_id) ${backwards?'<':'>'} ($2::timestamptz,$3::uuid) ORDER BY measured_at ${backwards?'DESC':'ASC'},event_id ${backwards?'DESC':'ASC'} LIMIT 256`,[deviceId,cursor.measured_at,cursor.event_id]);
            let stopped=false;
            for(const row of batch.rows){const p=point(row);if(!(backwards?connected(p,cursor,this.derivation):connected(cursor,p,this.derivation))){stopped=true;break;}if(backwards)points.unshift(p);else points.push(p);cursor=p;}
            if(stopped||(batch.rowCount??0)<256)break;
          }
        }
      }
      // Use the same repeatable-read revision as distance/geometry; read only +/-30s
      // raw context per segment rather than relying on geometry's adjacent-run context.
      const recorded = typeof scope === 'object' ? await c.query(recordedTimeSql,[deviceId,scope.start_at,scope.end_at]) : null;
      const recorded_days = recorded ? Object.fromEntries(recorded.rows.map(row=>[row.date,row.recorded_seconds])) : undefined;
      const correction=typeof scope==='object'?await this.readCorrection(c,deviceId,scope,points):undefined;
      const hasObservationTable=scope==='all'?(await c.query("SELECT to_regclass('routebook_observations') present")).rows[0].present:false;
      const events=hasObservationTable?await c.query(`SELECT ${observationColumns} FROM routebook_observations WHERE device_id=$1 ORDER BY observed_at,event_id`,[deviceId]):null;
      const extra=events?.rowCount?{observations:events.rows.map(observation),power_profiles:this.powerProfiles}:{};
      await c.query('COMMIT');return {...extra,...(correction?{correction}:{}),device_id:deviceId,revision:Number(device.rows[0].revision),points,...(recorded_days===undefined?{}:{recorded_days})};
    }catch(e){await c.query('ROLLBACK').catch(()=>{});throw e;}finally{c.release();}
  }
  async ingest(e:Envelope,receivedAt:string) {
    // Individual validation occurs before any duplicate lookup, even for known IDs.
    if(e.version===2)return this.ingestObservations(e,receivedAt);
    const validated=e.points.map(p=>validPoint(p,receivedAt)?p:null);
    const c=await this.pool.connect();try {
      await c.query('BEGIN');await c.query("SET LOCAL synchronous_commit = on");await c.query("SET LOCAL statement_timeout = '8s'");await c.query("SET LOCAL lock_timeout = '5s'");
      const device=await c.query('SELECT revision FROM routebook_devices WHERE device_id=$1 FOR UPDATE',[e.device_id]);
      if(!device.rowCount)throw new HttpError(403,'device_binding');
      const results:Result[]=[];let added=0;const touched=new Set<string>();
      for(let index=0;index<e.points.length;index++) {
        const input=e.points[index];const p=validated[index];const event_id=record(input)&&uuid(input.event_id)?input.event_id:null;
        if(!p){results.push({index,event_id,status:'rejected',code:'invalid_point'});continue;}
        const stored=await c.query(`SELECT ${columns} FROM routebook_points WHERE device_id=$1 AND event_id=$2`,[e.device_id,p.event_id]);
        if(stored.rowCount){const same=samePoint(p,point(stored.rows[0]));results.push({index,event_id,status:same?'duplicate':'rejected',code:same?null:'event_conflict'});continue;}
        touched.add(p.segment_id);
        // A late insertion may split an existing run with a different source segment.
        // Inspect the old immediate edge before insertion, not the entire history.
        const prior=await c.query(`SELECT ${columns} FROM routebook_points WHERE device_id=$1 AND (measured_at,event_id) < ($2::timestamptz,$3::uuid) ORDER BY measured_at DESC,event_id DESC LIMIT 1`,[e.device_id,p.measured_at,p.event_id]);
        const following=await c.query(`SELECT ${columns} FROM routebook_points WHERE device_id=$1 AND (measured_at,event_id) > ($2::timestamptz,$3::uuid) ORDER BY measured_at,event_id LIMIT 1`,[e.device_id,p.measured_at,p.event_id]);
        if(prior.rowCount){const previous=point(prior.rows[0]);if(previous.segment_id!==p.segment_id&&previous.speed_mps!==null&&previous.speed_mps<=.7){
          const tail=await c.query("SELECT count(*)::int count FROM routebook_points WHERE device_id=$1 AND segment_id=$2 AND measured_at >= $3::timestamptz - interval '330 seconds' AND measured_at <= $3",[e.device_id,previous.segment_id,previous.measured_at]);if(tail.rows[0].count>=40)touched.add(previous.segment_id);
        }}
        if(prior.rowCount&&following.rowCount){const a=point(prior.rows[0]),b=point(following.rows[0]);if(connected(a,b,this.derivation))touched.add(a.segment_id);}
        await c.query(`INSERT INTO routebook_points (${columns}) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`,[e.device_id,p.event_id,p.segment_id,p.measured_at,receivedAt,p.lat,p.lon,p.accuracy_m,p.speed_mps]);
        added++;results.push({index,event_id,status:'accepted',code:null});
      }
      let revision=Number(device.rows[0].revision);let event=null;
      if(added){const updated=await c.query('UPDATE routebook_devices SET revision=revision+1 WHERE device_id=$1 RETURNING revision',[e.device_id]);revision=Number(updated.rows[0].revision);
        const newest=await c.query(`SELECT ${columns} FROM routebook_points WHERE device_id=$1 ORDER BY measured_at DESC,event_id DESC LIMIT 1`,[e.device_id]);
        const snapshot={device_id:e.device_id,revision,points:newest.rows.map(point)};
        // Segment-scoped date superset. Disconnected unrelated history is not invalidated.
        const affected=new Set<string>();
        for(const segment of touched) {
          const firstRow=await c.query('SELECT measured_at FROM routebook_points WHERE device_id=$1 AND segment_id=$2 ORDER BY measured_at,event_id LIMIT 1',[e.device_id,segment]);
          const lastRow=await c.query('SELECT measured_at FROM routebook_points WHERE device_id=$1 AND segment_id=$2 ORDER BY measured_at DESC,event_id DESC LIMIT 1',[e.device_id,segment]);
          const first=nextDate(localDate(firstRow.rows[0].measured_at.toISOString()),-1),last=nextDate(localDate(lastRow.rows[0].measured_at.toISOString()));
          for(let d=first;d<=last;d=nextDate(d))affected.add(d);
        }
        event={snapshot,affected_dates:[...affected].sort()};
      }
      // Nothing escapes the store before the server has acknowledged durable COMMIT.
      await c.query('COMMIT');
      return {ack:{version:1 as const,device_id:e.device_id,received_at:receivedAt,revision,results},event};
    }catch(e){await c.query('ROLLBACK').catch(()=>{});throw e;}finally{c.release();}
  }
  private async ingestObservations(e:Extract<Envelope,{version:2}>,receivedAt:string) {
    const validated=e.events.map(p=>validObservation(p,receivedAt)?p:null),c=await this.pool.connect();
    try{
      await c.query('BEGIN');await c.query("SET LOCAL synchronous_commit = on");await c.query("SET LOCAL statement_timeout = '8s'");await c.query("SET LOCAL lock_timeout = '5s'");
      const device=await c.query('SELECT revision FROM routebook_devices WHERE device_id=$1 FOR UPDATE',[e.device_id]);if(!device.rowCount)throw new HttpError(403,'device_binding');
      const results:Result[]=[],affected=new Set<string>();let added=0;
      for(let index=0;index<e.events.length;index++){
        const input=e.events[index],p=validated[index],event_id=record(input)&&uuid(input.event_id)?input.event_id:null;
        if(!p){results.push({index,event_id,status:'rejected',code:'invalid_observation'});continue;}
        const old=await c.query(`SELECT ${observationColumns} FROM routebook_observations WHERE device_id=$1 AND event_id=$2`,[e.device_id,p.event_id]);
        if(old.rowCount){const same=sameObservation(p,observation(old.rows[0]));results.push({index,event_id,status:same?'duplicate':'rejected',code:same?null:'event_conflict'});continue;}
        await c.query(`INSERT INTO routebook_observations (${observationColumns}) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)`,[e.device_id,p.event_id,p.observer_epoch,p.sequence,p.observed_at,receivedAt,p.elapsed_realtime_ms,p.boot_id,p.kind,p.acc_state,p.module1_state,p.interpretation_profile,p.firmware_id]);
        const date=localDate(p.observed_at);for(const d of [nextDate(date,-1),date,nextDate(date)])affected.add(d);added++;results.push({index,event_id,status:'accepted',code:null});
      }
      let revision=Number(device.rows[0].revision),event=null;
      if(added){const update=await c.query('UPDATE routebook_devices SET revision=revision+1 WHERE device_id=$1 RETURNING revision',[e.device_id]);revision=Number(update.rows[0].revision);
        const newest=await c.query(`SELECT ${columns} FROM routebook_points WHERE device_id=$1 ORDER BY measured_at DESC,event_id DESC LIMIT 1`,[e.device_id]);
        event={snapshot:{device_id:e.device_id,revision,points:newest.rows.map(point)},affected_dates:[...affected].sort()};}
      await c.query('COMMIT');return {ack:{version:2 as const,device_id:e.device_id,received_at:receivedAt,revision,results},event};
    }catch(error){await c.query('ROLLBACK').catch(()=>{});throw error;}finally{c.release();}
  }

}
