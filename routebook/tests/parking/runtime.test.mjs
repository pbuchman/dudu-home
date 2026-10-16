import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

const root = resolve(process.env.ROUTEBOOK_ACCEPTANCE_SOURCE ?? fileURLToPath(new URL('../..', import.meta.url)));
const backend = name => import(pathToFileURL(resolve(root, 'backend/dist', name)));
const db = process.env.ROUTEBOOK_TEST_DB_URL;
const base = Date.parse('2026-10-14T08:00:00.000Z');
const at = seconds => new Date(base + seconds * 1000).toISOString();
const profile = { profile_id: 'synthetic-quality-profile', firmware_id: 'synthetic-quality-firmware', validation_id: 'synthetic-reference-only', validated_at: '2026-01-01T00:00:00.000Z',
  states: [{ acc_state: 0, module1_state: 0, state: 'off' }, { acc_state: 0, module1_state: 1, state: 'on' }] };
function archive() {
  const segment = randomUUID();
  const point = (seconds, lon = 0, speed = 0) => ({ event_id: randomUUID(), segment_id: segment, measured_at: at(seconds), lat: 0, lon, speed_mps: speed, accuracy_m: 5 });
  return [point(-20, -.0004, 2.2), point(-10, -.0002, 2.2), ...Array.from({ length: 61 }, (_, i) => point(i * 5))];
}
function event(seconds, overrides = {}) {
  return { event_id: randomUUID(), observer_epoch: randomUUID(), sequence: 0, observed_at: at(seconds), elapsed_realtime_ms: 100000 + seconds * 1000,
    boot_id: randomUUID(), kind: 'snapshot', acc_state: 0, module1_state: 1, interpretation_profile: profile.profile_id, firmware_id: profile.firmware_id, ...overrides };
}

test('parking integration: additive migration, v1/v2 durability, cross-view parity and power-only invalidation', { skip: !db, timeout: 120000 }, async t => {
  const url = new URL(db);
  assert.ok(['localhost', '127.0.0.1'].includes(url.hostname) && url.pathname.startsWith('/routebook_synthetic'), 'dedicated local synthetic database required');
  const require = createRequire(resolve(root, 'backend/package.json')), pg = require('pg');
  const pool = new pg.Pool({ connectionString: db });
  const { PgStore } = await backend('store.js'), { createServers } = await backend('server.js');
  const { dayBounds } = await backend('derive.js'), { analyticsBounds } = await backend('analytics.js');
  let now = at(2000);
  const device = randomUUID(), emptyDevice = randomUUID(), token = 'synthetic_parking_quality_token_abcdefghijklmnopqrstuvwxyz';
  const points = archive(), store = new PgStore(pool, undefined, [profile], () => now);
  let apps;
  try {
    await t.test('legacy GPS rows survive additive migration and repeated bootstrap', async () => {
      const migration = await pool.connect();
      try {
        await migration.query('BEGIN'); await migration.query('SELECT pg_advisory_xact_lock($1)', [726_268_001]);
        await migration.query(await readFile(resolve(root, 'backend/sql/schema-v1.sql'), 'utf8'));
        await migration.query('INSERT INTO routebook_devices(device_id) VALUES($1)', [device]);
        const p = points[0];
        await migration.query('INSERT INTO routebook_points(device_id,event_id,segment_id,measured_at,received_at,lat,lon,accuracy_m,speed_mps) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)', [device,p.event_id,p.segment_id,p.measured_at,at(500),p.lat,p.lon,p.accuracy_m,p.speed_mps]);
        await migration.query('COMMIT');
      } catch (error) { await migration.query('ROLLBACK'); throw error; }
      finally { migration.release(); }
      const old = (await pool.query('SELECT * FROM routebook_points WHERE device_id=$1', [device])).rows;
      await store.bootstrap([device, emptyDevice]); await store.bootstrap([device, emptyDevice]);
      assert.deepEqual((await pool.query('SELECT * FROM routebook_points WHERE device_id=$1', [device])).rows, old);
      assert.equal(Number((await pool.query('SELECT count(*) count FROM routebook_observations WHERE device_id=$1', [device])).rows[0].count), 0);
    });
    apps = createServers(store, { devices: [{ device_id: device, token }], now: () => now });
    const post = (body, headers = {}) => apps.publicApp.inject({ method: 'POST', url: '/v1/ingest', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', ...headers }, payload: body });
    const v1 = points => ({ version: 1, device_id: device, lane: 'backfill', points });
    const v2 = events => ({ version: 2, device_id: device, lane: 'backfill', events });
    const get = async path => {
      const res = await apps.privateApp.inject(`/v1/${path}${path.includes('?') ? '&' : '?'}device_id=${device}`);
      assert.equal(res.statusCode, 200, res.body); return res.json();
    };
    await t.test('v2 preserves authentication binding, strict body bounds and empty-GPS delivery', async () => {
      assert.equal((await post(v2([event(0)]), { authorization: '' })).statusCode, 401);
      assert.equal((await post({ ...v2([event(0)]), device_id: emptyDevice })).statusCode, 403);
      assert.equal((await post(v2(Array.from({ length: 201 }, () => event(0))))).statusCode, 422);
      assert.equal((await post(v2([event(0)]), { 'content-type': 'text/plain' })).statusCode, 415);
      assert.equal((await post('{"version":2,"version":2}')).statusCode, 422);
      assert.equal((await post(' '.repeat(262145))).statusCode, 413);
      const only = await store.ingest({ version: 2, device_id: emptyDevice, lane: 'latest', events: [event(0)] }, now);
      assert.equal(only.ack.results[0].status, 'accepted');
      assert.equal((await store.latestSnapshot(emptyDevice)).points.length, 0);
    });
    await t.test('v1 collector still drains GPS; day/range/period/journey/comparison corrections agree', async () => {
      now = at(500);
      const res = await post(v1(points)); assert.equal(res.statusCode, 200);
      now = at(2000);
      assert.equal(res.json().version, 1); assert.equal(res.json().results[0].status, 'duplicate');
      await store.writePlace(device, null, { name: 'Synthetic quality start', lat: 0, lon: -.0004, radius_m: 25 }, 0);
      await store.writePlace(device, null, { name: 'Synthetic quality end', lat: 0, lon: 0, radius_m: 25 }, 1);
      const d = await get('day?date=2026-10-14'), r = await get('range?from=2026-10-14&to=2026-10-14'), a = await get('analytics?from=2026-10-14&to=2026-10-14');
      assert.equal(d.recorded_seconds, 320); assert.equal(d.in_car_seconds, 20); assert.equal(d.estimated_excluded_seconds, 300);
      for (const metric of [r, r.days[0], r.months[0], a.totals, a.days[0], a.weeks[0], a.months[0], a.journeys[0]]) {
        assert.equal(metric.recorded_seconds, 320); assert.equal(metric.in_car_seconds, 20); assert.equal(metric.excluded_tail_seconds, 300);
      }
      const detail = await get(`analytics/journey?from=2026-10-14&to=2026-10-14&journey_id=${a.journeys[0].journey_id}`);
      assert.equal(detail.journey.in_car_seconds, 20); assert.equal(detail.correction_intervals.length, 1);
      assert.equal(a.comparisons[0].median_in_car_seconds, 20); assert.equal(a.comparisons[0].median_recorded_seconds, 320);
      const before = (await pool.query('SELECT * FROM routebook_points WHERE device_id=$1 ORDER BY measured_at,event_id', [device])).rows;
      await get('analytics?from=2026-10-14&to=2026-10-14');
      assert.deepEqual((await pool.query('SELECT * FROM routebook_points WHERE device_id=$1 ORDER BY measured_at,event_id', [device])).rows, before);
    });
    await t.test('trusted ON with ACC OFF reverses estimated exclusion and changes SSE without a new GPS fix', async () => {
      await apps.privateApp.listen({ host: '127.0.0.1', port: 0 });
      const address = apps.privateApp.server.address(), abort = new AbortController();
      const response = await fetch(`http://127.0.0.1:${address.port}/v1/live?device_id=${device}`, { signal: abort.signal });
      const reader = response.body.getReader(), decoder = new TextDecoder();
      async function frame() {
        let result = '';
        while (!result.includes('\n\n')) { const part = await reader.read(); assert.equal(part.done, false); result += decoder.decode(part.value); }
        return JSON.parse(result.split('\n').find(line => line.startsWith('data: ')).slice(6));
      }
      try {
        const initial = await frame(), latest = await get('latest'), on = event(0);
        const res = await post(v2([on])); assert.equal(res.statusCode, 200); assert.equal(res.json().version, 2);
        const update = await frame(); assert.equal(update.revision, initial.revision + 1); assert.ok(update.affected_dates.includes('2026-10-14'));
        assert.equal(update.latest.point.event_id, latest.point.event_id);
        const d = await get('day?date=2026-10-14'); assert.equal(d.recorded_seconds, 320); assert.equal(d.in_car_seconds, 320); assert.equal(d.excluded_tail_seconds, 0);
        const restarted = new PgStore(pool, undefined, [profile], () => now);
        const duplicate = await restarted.ingest(v2([on]), now); assert.equal(duplicate.ack.results[0].status, 'duplicate'); assert.equal(duplicate.ack.revision, update.revision);
        const conflict = await post(v2([{ ...on, module1_state: 0 }])); assert.equal(conflict.json().results[0].code, 'event_conflict');
        assert.equal((await get('latest')).revision, update.revision);
      } finally { abort.abort(); await reader.cancel().catch(() => {}); }
    });
    await t.test('clock-only maturity invalidates server cache without changing source revision', async () => {
      const matureDevice = randomUUID(), p = archive(); await store.bootstrap([matureDevice]);
      now = at(500); await store.ingest({ version: 1, device_id: matureDevice, lane: 'backfill', points: p }, now);
      const bounded = async () => {
        const s = await store.snapshot(matureDevice, dayBounds('2026-10-14'));
        const { day } = await backend('derive.js'); return day(s, '2026-10-14');
      };
      now = at(1099); const before = await bounded(); assert.equal(before.in_car_seconds, 320); assert.equal(before.correction_next_refresh_at, at(1100));
      now = at(1100); const after = await bounded(); assert.equal(after.in_car_seconds, 20); assert.equal(after.revision, before.revision);
      const { analytics } = await backend('analytics.js');
      assert.equal(analytics(await store.analyticsSnapshot(matureDevice, analyticsBounds('2026-10-14','2026-10-14')), '2026-10-14', '2026-10-14').totals.in_car_seconds, 20);
    });
    await t.test('v2 rate limits use the same authenticated device bucket as v1', async () => {
      const limited = createServers(store, { devices: [{ device_id: device, token }], rate_limit_per_minute: 1, now: () => now });
      try {
        const headers = { authorization: `Bearer ${token}`, 'content-type': 'application/json' };
        assert.equal((await limited.publicApp.inject({ method:'POST',url:'/v1/ingest',headers,payload:v2([event(0)]) })).statusCode, 200);
        const res = await limited.publicApp.inject({ method:'POST',url:'/v1/ingest',headers,payload:v1([points[0]]) });
        assert.equal(res.statusCode, 429); assert.ok(res.headers['retry-after']);
      } finally { await limited.publicApp.close(); await limited.privateApp.close(); }
    });
    await t.test('bounded raw archive retains every sample and long stationary coverage without blanket deduction', async () => {
      const denseDevice = randomUUID(), segment = randomUUID(); await store.bootstrap([denseDevice]);
      await pool.query(`INSERT INTO routebook_points(device_id,event_id,segment_id,measured_at,received_at,lat,lon,accuracy_m,speed_mps)
        SELECT $1::uuid,md5($1::text||i::text)::uuid,$2::uuid,$3::timestamptz+i*interval '5 seconds',
        '2026-10-16T00:00:00Z',0,0,5,0 FROM generate_series(0,20000) i`, [denseDevice, segment, at(0)]);
      now = '2026-10-17T00:00:00.000Z';
      const started = performance.now(), bounds = { start_at: dayBounds('2026-10-14').start_at, end_at: dayBounds('2026-10-15').end_at };
      const { range } = await backend('derive.js');
      const result = range(await store.snapshot(denseDevice, bounds), '2026-10-14', '2026-10-15');
      const ms = performance.now() - started;
      assert.equal(result.recorded_seconds, 100000); assert.equal(result.in_car_seconds, 100000); assert.equal(result.excluded_tail_seconds, 0);
      assert.equal(Number((await pool.query('SELECT count(*) count FROM routebook_points WHERE device_id=$1', [denseDevice])).rows[0].count), 20001);
      assert.ok(ms < 8000, `20,001-point synthetic range took ${Math.round(ms)}ms`);
      t.diagnostic(`20,001 raw points: two-day range ${Math.round(ms)}ms; raw rows unchanged. Synthetic local timing only.`);
    });
  } finally { if (apps) { await apps.publicApp.close(); await apps.privateApp.close(); } await pool.end(); }
});
