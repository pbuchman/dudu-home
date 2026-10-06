import pg from 'pg';
import { readFile } from 'node:fs/promises';
import { connected, localDate, nextDate } from './derive.js';
import { record, samePoint, uuid, validPoint } from './validation.js';
import { HttpError, type Envelope, type Store, type Snapshot, type StoredPoint, type Result, defaults, type Derivation } from './types.js';
const columns='device_id,event_id,segment_id,measured_at,received_at,lat,lon,accuracy_m,speed_mps';
function point(row:Record<string,any>):StoredPoint {return {...row,measured_at:row.measured_at.toISOString(),received_at:row.received_at.toISOString()} as StoredPoint;}
export class PgStore implements Store {
  constructor(readonly pool:pg.Pool,readonly derivation:Derivation=defaults) {}
  async bootstrap(deviceIds:string[]) {
    const c=await this.pool.connect();try {await c.query('BEGIN');await c.query('SELECT pg_advisory_xact_lock($1)', [726_268_001]);await c.query(await readFile(new URL('../sql/schema-v1.sql',import.meta.url),'utf8'));
      for(const id of deviceIds)await c.query('INSERT INTO routebook_devices(device_id) VALUES($1) ON CONFLICT DO NOTHING',[id]);await c.query('COMMIT');
    }catch(e){await c.query('ROLLBACK').catch(()=>{});throw e;}finally{c.release();}
  }
  async assertDurability(){const settings=await this.pool.query("SELECT current_setting('fsync') fsync,current_setting('full_page_writes') full_page_writes");if(settings.rows[0].fsync!=='on'||settings.rows[0].full_page_writes!=='on')throw Error('Durable WAL settings required');}
  async health(){await this.pool.query('SELECT 1');}
  async latestSnapshot(deviceId:string):Promise<Snapshot> { return this.readSnapshot(deviceId,'latest'); }
  async snapshot(deviceId:string,bounds?:{start_at:string;end_at:string}):Promise<Snapshot> {return this.readSnapshot(deviceId,bounds??'all');}
  private async readSnapshot(deviceId:string,scope:'latest'|'all'|{start_at:string;end_at:string}):Promise<Snapshot> {
    const c=await this.pool.connect();try {await c.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
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
      await c.query('COMMIT');return {device_id:deviceId,revision:Number(device.rows[0].revision),points};
    }catch(e){await c.query('ROLLBACK').catch(()=>{});throw e;}finally{c.release();}
  }
  async ingest(e:Envelope,receivedAt:string) {
    // Individual validation occurs before any duplicate lookup, even for known IDs.
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
}
