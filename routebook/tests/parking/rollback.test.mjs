import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

const root = resolve(process.env.ROUTEBOOK_ACCEPTANCE_SOURCE ?? fileURLToPath(new URL('../..', import.meta.url)));
const oldRoot = process.env.ROUTEBOOK_PARKING_OLD_SOURCE;
const db = process.env.ROUTEBOOK_TEST_DB_URL;
test('actual RC2 backend rollback preserves the upgraded archive, accepts v1 and refuses v2 without ACK', { skip: !db || !oldRoot }, async () => {
  const url = new URL(db);
  assert.ok(['localhost', '127.0.0.1'].includes(url.hostname) && url.pathname.startsWith('/routebook_synthetic'));
  const load = (dir, name) => import(pathToFileURL(resolve(dir, 'backend/dist', name)));
  const require = createRequire(resolve(root, 'backend/package.json')), { Pool } = require('pg');
  const pool = new Pool({ connectionString: db }), device = randomUUID();
  const { PgStore: CurrentStore } = await load(root, 'store.js'), { PgStore: OldStore } = await load(oldRoot, 'store.js');
  const { createServers: oldServers } = await load(oldRoot, 'server.js');
  const { dayBounds } = await load(root, 'derive.js');
  const current = new CurrentStore(pool), old = new OldStore(pool), now = '2026-10-16T00:00:00.000Z';
  const point = { event_id: randomUUID(), segment_id: randomUUID(), measured_at: '2026-10-14T08:00:00.000Z', lat: 0, lon: 0, accuracy_m: 5, speed_mps: 0 };
  const observation = { event_id: randomUUID(), observer_epoch: randomUUID(), sequence: 0, boot_id: randomUUID(),
    observed_at: point.measured_at, elapsed_realtime_ms: 1000, kind: 'snapshot', acc_state: 0, module1_state: 0, interpretation_profile: 'diagnostic-v1', firmware_id: 'synthetic' };
  const token = 'synthetic_rollback_token_abcdefghijklmnopqrstuvwxyz';
  let apps;
  try {
    await current.bootstrap([device]);
    await current.ingest({ version: 1, device_id: device, lane: 'latest', points: [point] }, now);
    await current.ingest({ version: 2, device_id: device, lane: 'latest', events: [observation] }, now);
    const before = await current.snapshot(device);
    await old.bootstrap([device]); // Uses the actual preceding release's SQL/code.
    apps = oldServers(old, { devices: [{ device_id: device, token }], now: () => now });
    const headers = { authorization: `Bearer ${token}`, 'content-type': 'application/json' };
    const v2 = await apps.publicApp.inject({ method: 'POST', url: '/v1/ingest', headers, payload: { version: 2, device_id: device, lane: 'latest', events: [observation] } });
    assert.equal(v2.statusCode, 422); assert.ok(!Object.hasOwn(v2.json(), 'results'));
    const next = { ...point, event_id: randomUUID(), measured_at: '2026-10-14T08:00:05.000Z' };
    const v1 = await apps.publicApp.inject({ method: 'POST', url: '/v1/ingest', headers, payload: { version: 1, device_id: device, lane: 'latest', points: [next] } });
    assert.equal(v1.statusCode, 200); assert.equal(v1.json().results[0].status, 'accepted');
    const after = await current.snapshot(device);
    assert.equal(after.points.length, before.points.length + 1); assert.deepEqual(after.observations, before.observations);
    const oldRead = await apps.privateApp.inject(`/v1/day?device_id=${device}&date=2026-10-14`);
    assert.equal(oldRead.statusCode, 200); assert.equal(oldRead.json().recorded_seconds, 5);
    const { day } = await load(root, 'derive.js');
    assert.equal(day(await current.snapshot(device, dayBounds('2026-10-14')), '2026-10-14').recorded_seconds, oldRead.json().recorded_seconds);
    await current.bootstrap([device]); assert.deepEqual((await current.snapshot(device)).observations, before.observations);
  } finally { if (apps) { await apps.publicApp.close(); await apps.privateApp.close(); } await pool.end(); }
});
