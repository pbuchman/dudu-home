import assert from 'node:assert/strict';
import { test } from 'node:test';
import { randomUUID } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';

// This suite deliberately uses the production derivation/API and real database. It does
// not import owner-authored fixtures or replace HTTP/SQL with a mocked analytics store.
const root = resolve(process.env.ROUTEBOOK_ACCEPTANCE_SOURCE ?? fileURLToPath(new URL('../..', import.meta.url)));
const backend = (name) => import(pathToFileURL(resolve(root, 'backend/dist', name)));
const { analytics, journeyDetail, analyticsBounds } = await backend('analytics.js');
const device = '00000000-0000-4000-8000-000000008001';
const id = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const snapshot = (points, overrides = {}) => ({ device_id: device, revision: 4, metadata_revision: 0, points, places: [], has_before: false, has_after: false, ...overrides });
const near = (actual, expected, tolerance = 1e-7) => assert.ok(Math.abs(actual - expected) < tolerance, `${actual} differs from ${expected}`);
function recording(start = '2026-10-05T08:00:00.000Z') {
  let t = Date.parse(start), lon = 0, sequence = 1, direction = 1;
  const points = [];
  function point(speed = 0, segment = id(8000), accuracy = 5) {
    points.push({ device_id: device, event_id: id(sequence++), segment_id: segment, measured_at: new Date(t).toISOString(), received_at: '2026-11-01T00:00:00.000Z', lat: 0, lon, accuracy_m: accuracy, speed_mps: speed });
    t += 10_000;
  }
  return {
    points,
    park(seconds = 600) { if (points.length && points.at(-1).speed_mps !== 0) lon += direction * .0009; for (let i = 0; i <= seconds / 10; i++) point(); },
    move(count = 6, options = {}) { direction = options.direction ?? 1; for (let i = 0; i < count; i++) { lon += direction * .0009; point(options.nullSpeed ? null : 10, options.segment, options.accuracy); } },
    wait(seconds) { t += seconds * 1000; },
  };
}
const selected = (r, from = '2026-10-05', to = from) => analytics(snapshot(r.points), from, to);

test('acceptance: parking thresholds, technical boundaries and time denominators', () => {
  for (const seconds of [180, 590, 600]) {
    const r = recording(); r.park(); r.move(); r.park(seconds); r.move(); r.park();
    const a = selected(r);
    assert.equal(a.journeys.length, seconds === 600 ? 2 : 1);
    assert.equal(a.visits.length, 3);
    assert.ok(a.journeys.every(j => j.complete));
    near(a.totals.stopped_seconds, seconds === 600 ? 0 : seconds);
    assert.equal(a.days.reduce((count, day) => count + day.stop_count, 0), 3);
    assert.ok(a.totals.elapsed_seconds < 1_000, 'boundary parking must not become travel time');
    for (const j of a.journeys) {
      near(j.elapsed_seconds, j.moving_seconds + j.stopped_seconds + j.unknown_seconds);
      near(j.moving_average_mps, j.moving_meters / j.moving_seconds);
      near(j.overall_average_mps, j.meters / j.elapsed_seconds);
      assert.equal(j.stop_count, seconds === 600 ? 0 : 1);
    }
  }
  const r = recording(); r.park(); r.move(); r.move(6, { segment: id(8001) }); r.park();
  // The trailing parking uses the first source segment, intentionally producing another
  // technical break. Neither change is a physical journey boundary or geometry bridge.
  const a = selected(r); assert.equal(a.journeys.length, 1);
  const j = a.journeys[0], d = journeyDetail(snapshot(r.points), '2026-10-05', '2026-10-05', j.journey_id);
  assert.equal(j.complete, false); assert.equal(j.overall_average_mps, null);
  assert.equal(j.unknown_seconds, 20); assert.equal(d.segments.length, 3);
  assert.equal(d.points.length, j.point_count);
  for (const i of d.timeline.filter(i => i.state === 'unknown')) assert.equal(i.meters, 0);
});

test('acceptance: missing observations never establish parking or a complete journey', () => {
  const r = recording(); r.move(); r.wait(600); r.move();
  const a = selected(r); assert.equal(a.journeys.length, 2); assert.equal(a.visits.length, 0);
  assert.equal(a.journeys[0].end_boundary, 'gps_gap'); assert.equal(a.journeys[1].start_boundary, 'gps_gap');
  assert.ok(a.journeys.every(j => !j.complete && j.overall_average_mps === null));
  const context = analytics(snapshot(r.points, { has_before: true, has_after: true }), '2026-10-05', '2026-10-05');
  assert.equal(context.journeys[0].start_boundary, 'context_limit');
  assert.equal(context.journeys.at(-1).end_boundary, 'context_limit');
  const onlyParking = recording(); onlyParking.park(1800);
  const p = selected(onlyParking); assert.equal(p.journeys.length, 0); assert.equal(p.visits[0].duration_seconds, 1800);
  assert.equal(p.totals.elapsed_seconds, 0); assert.equal(p.totals.overall_average_mps, null);
});

test('acceptance: Warsaw DST/midnight/month/week clipping preserves independent numerators', () => {
  // Both dates are transition Sundays. Movement straddles local midnight, not an
  // assumed UTC-day or 24-hour period. The two selected days remain one ISO week.
  for (const [from, to, utc] of [
    ['2026-03-28', '2026-03-29', '2026-03-28T22:59:40.000Z'],
    ['2026-10-24', '2026-10-25', '2026-10-24T21:59:40.000Z'],
    ['2026-08-31', '2026-09-01', '2026-08-31T21:59:40.000Z'],
  ]) {
    const r = recording(utc); r.move(6);
    const a = selected(r, from, to), second = selected(r, to);
    near(a.totals.elapsed_seconds, 50); near(a.days[0].elapsed_seconds, 20); near(a.days[1].elapsed_seconds, 30);
    near(second.totals.moving_seconds, 30);
    near(second.totals.moving_average_mps, a.journeys[0].moving_average_mps);
    assert.equal(second.journeys.length, 1); near(second.journeys[0].elapsed_seconds, 50);
    for (const list of [a.days, a.weeks, a.months]) {
      near(list.reduce((sum, row) => sum + row.meters, 0), a.totals.meters);
      near(list.reduce((sum, row) => sum + row.elapsed_seconds, 0), 50);
    }
  }
  assert.equal(analyticsBounds('2026-03-29', '2026-03-29').end_at, '2026-03-30T22:00:00.000Z');
  assert.equal(analyticsBounds('2026-10-25', '2026-10-25').start_at, '2026-10-23T22:00:00.000Z');
  // A complete journey may depart before the selected period. Its clipped time is
  // still complete evidence, even though departure-attributed journey_count is zero.
  for (const [start, day] of [['2026-10-04T21:49:00.000Z', '2026-10-05'], ['2026-08-31T21:49:00.000Z', '2026-09-01']]) {
    const r = recording(start); r.park(); r.move(); r.park(); const a = selected(r, day);
    assert.equal(a.journeys[0].complete, true); assert.equal(a.days[0].journey_count, 0);
    for (const row of [a.days[0], a.weeks[0], a.months[0]]) {
      assert.notEqual(row.overall_average_mps, null, 'complete overlap must retain the period average without a departure in that period');
      near(row.overall_average_mps, a.totals.overall_average_mps);
    }
  }
  const r = recording(); r.move();
  assert.equal(selected(r, '2026-10-01', '2026-10-31').days.length, 31);
  assert.throws(() => selected(r, '2026-10-01', '2026-11-01'), /analytics_limit/);
});

test('acceptance: null, outlier, poor-accuracy and isolated speed samples cannot invent maxima', () => {
  for (const options of [{ nullSpeed: true }, { accuracy: 26 }]) {
    const r = recording(); r.move(8, options); const a = selected(r);
    assert.ok(a.journeys[0].moving_meters > 0); assert.equal(a.journeys[0].observed_max_mps, null);
    const d = journeyDetail(snapshot(r.points), '2026-10-05', '2026-10-05', a.journeys[0].journey_id);
    assert.ok(d.speed_samples.every(s => !s.accepted));
    if (options.nullSpeed) assert.ok(d.speed_samples.every(s => s.speed_mps === null));
  }
  const r = recording(); r.move(8); r.points[3].speed_mps = 75;
  const a = selected(r); assert.equal(a.journeys[0].observed_max_mps, 10);
  const d = journeyDetail(snapshot(r.points), '2026-10-05', '2026-10-05', a.journeys[0].journey_id);
  assert.equal(d.speed_samples[3].speed_mps, 75); assert.equal(d.speed_samples[3].accepted, false);
  assert.equal(analytics(snapshot([r.points[3]]), '2026-10-05', '2026-10-05').journeys.length, 0);
});

test('acceptance: repeated directions, named visits, stable ties and departure-hour samples', () => {
  const r = recording(); r.park(); r.move(); r.park(); r.move(6, { direction: -1 }); r.park(); r.move(); r.park();
  const places = [
    { place_id: id(8102), name: 'Synthetic Beta', lat: 0, lon: .0063, radius_m: 100 },
    { place_id: id(8101), name: 'Synthetic Alpha', lat: 0, lon: 0, radius_m: 100 },
    { place_id: id(8103), name: 'Synthetic tie loser', lat: 0, lon: 0, radius_m: 100 },
  ];
  const a = analytics(snapshot(r.points, { places }), '2026-10-05', '2026-10-05');
  assert.equal(a.journeys.length, 3); assert.equal(a.comparisons.length, 2);
  const ab = a.comparisons.find(c => c.from_place.place_id === id(8101));
  const ba = a.comparisons.find(c => c.from_place.place_id === id(8102));
  assert.equal(ab.to_place.place_id, id(8102)); assert.equal(ab.count, 2); assert.equal(ab.complete_count, 2);
  assert.equal(ba.to_place.place_id, id(8101)); assert.equal(ba.count, 1);
  assert.equal(ab.departures.reduce((sum, d) => sum + d.count, 0), 2);
  assert.ok(ab.departures.every(d => d.hour === 10));
  near(ab.mean_seconds, ab.median_seconds);
  assert.ok(a.visits.every(v => v.place && v.place.place_id !== id(8103)));
});

test('acceptance: real PostGIS + API + nginx metadata isolation and immutable ingest', { skip: !process.env.ROUTEBOOK_ANALYTICS_TEST_DB, timeout: 120_000 }, async (t) => {
  const db = new URL(process.env.ROUTEBOOK_ANALYTICS_TEST_DB);
  assert.ok(['127.0.0.1', 'localhost'].includes(db.hostname) && db.pathname.startsWith('/routebook_synthetic'), 'only a dedicated local synthetic DB is allowed');
  const require = createRequire(resolve(root, 'backend/package.json'));
  const pg = require('pg'); const pool = new pg.Pool({ connectionString: db.href });
  const { PgStore } = await backend('store.js'); const { createServers } = await backend('server.js');
  // Keep calculation provenance deterministic while comparing complete read bodies.
  const store = new PgStore(pool, undefined, [], () => '2026-11-01T00:00:00.000Z'), testDevice = randomUUID(), token = 'synthetic_analytics_acceptance_abcdefghijklmnopqrstuvwxyz';
  await store.bootstrap([testDevice]);
  const apps = createServers(store, { devices: [{ device_id: testDevice, token }], now: () => '2026-11-01T00:00:00.000Z' });
  const dir = await mkdtemp(resolve(tmpdir(), 'routebook-analytics-qa-'));
  let nginx;
  try {
    await apps.publicApp.listen({ host: '127.0.0.1', port: 0 }); await apps.privateApp.listen({ host: '127.0.0.1', port: 0 });
    const pub = `http://127.0.0.1:${apps.publicApp.server.address().port}`, priv = `http://127.0.0.1:${apps.privateApp.server.address().port}`;
    // An unprivileged local nginx process exercises the checked-in forwarding template,
    // including the browser Host/Origin authority. No Home Dev runtime is touched.
    const { createServer } = await import('node:net'); const reserve = createServer();
    await new Promise(r => reserve.listen(0, '127.0.0.1', r)); const port = reserve.address().port; await new Promise(r => reserve.close(r));
    const web = `http://127.0.0.1:${port}`;
    const template = await readFile(resolve(root, 'deploy/templates/nginx.conf'), 'utf8');
    // Node uses a socket for piped child stderr on Linux. nginx must inherit that fd
    // directly: reopening /dev/stderr can fail with ENXIO. Production stays unchanged.
    const config = template.replace('pid /tmp/nginx.pid;', `pid ${dir}/nginx.pid;`).replace('include /etc/nginx/mime.types;', 'types { text/html html; text/css css; application/javascript js; application/json json; }')
      .replace('error_log /dev/stderr warn;', 'error_log stderr warn;')
      .replaceAll('/tmp/client', `${dir}/client`).replaceAll('/tmp/proxy', `${dir}/proxy`).replaceAll('/tmp/fastcgi', `${dir}/fastcgi`).replaceAll('/tmp/uwsgi', `${dir}/uwsgi`).replaceAll('/tmp/scgi', `${dir}/scgi`)
      .replace('listen 127.0.0.1:8792;', `listen 127.0.0.1:${port};`).replaceAll('http://127.0.0.1:8794', priv)
      .replace('/usr/share/nginx/html', resolve(process.env.ROUTEBOOK_ACCEPTANCE_UI_SOURCE ?? root, 'ui/dist')).replace('/run/routebook/routebook-config.json', `${dir}/runtime.json`);
    await writeFile(`${dir}/runtime.json`, JSON.stringify({ mode: 'api', deviceId: testDevice, mapTilerKey: 'synthetic-no-network' })); await writeFile(`${dir}/nginx.conf`, config);
    nginx = spawn(process.env.ROUTEBOOK_NGINX ?? 'nginx', ['-p', dir, '-c', `${dir}/nginx.conf`, '-g', 'daemon off;'], { stdio: ['ignore', 'ignore', 'pipe'] });
    let nginxErrors = ''; nginx.stderr.on('data', data => { nginxErrors += data; });
    for (let i = 0; ; i++) { try { const res = await fetch(`${web}/health`); if (res.ok) break; } catch {} if (i === 40) throw Error(`synthetic nginx did not start: ${nginxErrors}`); await new Promise(r => setTimeout(r, 100)); }
    const url = (base, path) => `${base}/v1/${path}${path.includes('?') ? '&' : '?'}device_id=${testDevice}`;
    const get = async (path) => { const res = await fetch(url(web, path)); assert.equal(res.status, 200); return res.json(); };
    const write = (method, path, body, headers = {}) => fetch(url(web, path), { method, headers: { Origin: web, 'X-Routebook-Write': '1', 'Content-Type': 'application/json', ...headers }, body: typeof body === 'string' ? body : JSON.stringify(body) });
    const post = async (points) => { const response = await fetch(`${pub}/v1/ingest`, { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ version: 1, device_id: testDevice, lane: 'backfill', points: points.map(({ device_id, received_at, ...point }) => point) }) }); assert.equal(response.status, 200); const ack = await response.json(); assert.ok(ack.results.every(r => r.status === 'accepted')); return ack; };
    let placeA, placeB;
    await t.test('metadata JSON writes succeed through production nginx Host forwarding; cross-site/public writes fail', async () => {
      for (const path of ['analytics?from=2026-10-05&to=2026-10-05', 'places']) assert.equal((await fetch(url(pub, path))).status, 404);
      const body = { expected_metadata_revision: 0, name: 'Synthetic Alpha', lat: 0, lon: 0, radius_m: 100 };
      assert.equal((await fetch(url(pub, 'places'), { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Routebook-Write': '1' }, body: JSON.stringify(body) })).status, 404);
      assert.equal((await fetch(`${web}/v1/ingest`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).status, 404);
      assert.equal((await write('POST', 'places', body, { Origin: 'http://foreign.invalid' })).status, 403);
      assert.equal((await write('POST', 'places', body, { 'Sec-Fetch-Site': 'cross-site' })).status, 403);
      assert.equal((await write('POST', 'places', body, { 'X-Routebook-Write': '' })).status, 403);
      assert.equal((await write('POST', 'places', JSON.stringify(body).replace('"name":', '"name":"duplicate","name":'))).status, 422);
      const created = await write('POST', 'places', body); assert.equal(created.status, 200); placeA = (await created.json()).places[0];
      assert.equal((await write('POST', 'places', body)).status, 409);
      assert.equal((await get('places')).metadata_revision, 1);
      const second = await write('POST', 'places', { ...body, expected_metadata_revision: 1, name: 'Synthetic Beta', lon: .0063 }); assert.equal(second.status, 200); placeB = (await second.json()).places.find(p => p.place_id !== placeA.place_id);
    });
    const r = recording(); r.park(); r.move(); r.park(); r.move(6, { direction: -1 }); r.park();
    await t.test('additive analytics reads preserve normative v1 points/distance and metadata cannot rewrite GPS', async () => {
      await post(r.points); const oldDay = await get('day?date=2026-10-05');
      const a = await get('analytics?from=2026-10-05&to=2026-10-05'); assert.equal(a.journeys.length, 2); assert.equal(a.source_revision, 1); assert.equal(a.metadata_revision, 2);
      near(a.totals.meters, oldDay.meters); assert.equal(a.comparisons.length, 2);
      // Continuous raw observations include all three ten-minute parking periods,
      // independently of the shorter journey elapsed time and map geometry.
      assert.equal(new Set(r.points.map(point => point.segment_id)).size, 1);
      assert.ok(r.points.slice(1).every((point, index) => {
        const gap = Date.parse(point.measured_at) - Date.parse(r.points[index].measured_at);
        return gap > 0 && gap <= 30_000;
      }));
      const observedSeconds = (Date.parse(r.points.at(-1).measured_at) - Date.parse(r.points[0].measured_at)) / 1000;
      assert.equal(observedSeconds, 1940);
      assert.equal(oldDay.recorded_seconds, observedSeconds);
      assert.equal(a.totals.recorded_seconds, observedSeconds);
      assert.equal(a.totals.point_count, r.points.length);
      assert.ok(a.totals.recorded_seconds > a.totals.elapsed_seconds);
      const range = await get('range?from=2026-10-05&to=2026-10-05');
      assert.equal(range.recorded_seconds, observedSeconds);
      assert.equal(range.days[0].recorded_seconds, observedSeconds);
      assert.equal(range.months[0].recorded_seconds, observedSeconds);
      for (const periods of [a.days, a.weeks, a.months]) assert.equal(periods[0].recorded_seconds, observedSeconds);
      const d = await get(`analytics/journey?from=2026-10-05&to=2026-10-05&journey_id=${a.journeys[0].journey_id}`); assert.equal(d.source_revision, a.source_revision); assert.equal(d.metadata_revision, a.metadata_revision);
      const changed = await write('PUT', `places/${placeA.place_id}`, { expected_metadata_revision: 2, ...placeA, place_id: undefined, name: 'Synthetic Revised Alpha' }); assert.equal(changed.status, 200);
      assert.deepEqual(await get('day?date=2026-10-05'), oldDay);
      assert.equal((await get('analytics?from=2026-10-05&to=2026-10-05')).metadata_revision, 3);
      const [race1, race2] = await Promise.all([write('DELETE', `places/${placeB.place_id}`, { expected_metadata_revision: 3 }), write('DELETE', `places/${placeB.place_id}`, { expected_metadata_revision: 3 })]);
      assert.deepEqual([race1.status, race2.status].sort(), [200, 409]); assert.equal((await get('places')).metadata_revision, 4);
      assert.deepEqual(await get('day?date=2026-10-05'), oldDay);
    });
    await t.test('late backfill revises historical journeys while latest remains monotonic', async () => {
      const start = '2026-10-06T08:00:00.000Z', late = recording(start); late.move(10);
      late.points.forEach(point => { point.event_id = randomUUID(); });
      await post([late.points[0], late.points.at(-1)]);
      const before = await get('analytics?from=2026-10-06&to=2026-10-06'); assert.equal(before.journeys.length, 0);
      const latest = await get('latest'); await post(late.points.slice(1, -1));
      const after = await get('analytics?from=2026-10-06&to=2026-10-06'); assert.equal(after.journeys.length, 1); assert.equal(after.source_revision, before.source_revision + 1);
      assert.equal((await get('latest')).point.event_id, latest.point.event_id);
      assert.equal(after.journeys[0].complete, false); assert.ok(after.totals.meters > before.totals.meters);
    });
    if (process.env.ROUTEBOOK_ANALYTICS_BROWSER === '1') await browserAcceptance(t, require, web, testDevice, post, r, get);
  } finally {
    if (nginx && nginx.exitCode === null && nginx.signalCode === null) { const exited = new Promise(r => nginx.once('exit', r)); nginx.kill('SIGTERM'); await exited; }
    await apps.publicApp.close(); await apps.privateApp.close(); await pool.end(); await rm(dir, { recursive: true, force: true });
  }
});

async function browserAcceptance(t, require, web, testDevice, post, archive, get) {
  const { chromium } = require('@playwright/test');
  const browser = await chromium.launch({ headless: true, args: [
    '--enable-webgl', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
  ] });
  try {
    await t.test('browser: real archive, chart cursor, metadata CRUD, stale detail exclusion and SSE refresh', async () => {
      const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
      // Only map styling is synthetic. Every archive and metadata request still reaches
      // the real nginx/API/PostGIS stack; no request can reach the billed map provider.
      await page.route('https://api.maptiler.com/**', route => route.request().url().includes('/resources/logo.svg')
        ? route.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="110" height="24"><text x="2" y="17" font-size="12">Synthetic map style</text></svg>' })
        : route.fulfill({ json: { version: 8, sources: {}, layers: [{ id: 'synthetic-background', type: 'background', paint: { 'background-color': '#e9edf2' } }] } }));
      const errors = [], busy = []; page.on('pageerror', error => errors.push(error.message));
      page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
      page.on('response', response => { if (response.status() === 429) busy.push(response.url()); });
      await page.goto(web); await page.getByLabel('Wybrany dzień').fill('2026-10-05'); await page.getByRole('button', { name: 'Analizy', exact: true }).click();
      await page.getByRole('heading', { name: 'Dziennik podróży' }).waitFor();
      await page.locator('.journey-row').first().waitFor(); await page.getByLabel('Pozycja w podróży', { exact: true }).waitFor();
      assert.equal(await page.title(), 'Routebook · prywatna historia'); assert.equal(await page.locator('.journey-row').count(), 2);
      assert.equal(await page.locator('.recorded-metric dd').first().innerText(), '32 min 20 s', 'real archive parking time must reach the rendered analytics metric');
      const recordedEvidence = process.env.ROUTEBOOK_ANALYTICS_EVIDENCE ?? tmpdir();
      await mkdir(recordedEvidence, { recursive: true });
      await page.screenshot({ path: resolve(recordedEvidence, 'routebook-recorded-parking-desktop.png'), fullPage: true });
      await page.getByRole('button', { name: 'Tydzień', exact: true }).click();
      await page.locator('.period-caption').filter({ hasText: /2026-10-05\s.*2026-10-11/ }).waitFor();
      await page.getByLabel('Pozycja w podróży', { exact: true }).waitFor();
      await page.getByRole('button', { name: 'Miesiąc', exact: true }).click();
      await page.locator('.period-caption').filter({ hasText: /2026-10-01\s.*2026-10-31/ }).waitFor();
      await page.getByLabel('Pozycja w podróży', { exact: true }).waitFor();
      await page.getByRole('button', { name: 'Dzień', exact: true }).last().click();
      await page.getByLabel('Data okresu', { exact: true }).fill('2026-10-01');
      await page.getByText('Brak podróży w tym okresie. Brak obserwacji nie oznacza postoju.', { exact: true }).waitFor();
      assert.equal(await page.getByLabel('Pozycja w podróży', { exact: true }).count(), 0, 'empty selection must clear prior geometry and replay');
      await page.getByLabel('Data okresu', { exact: true }).fill('2026-10-05'); await page.getByLabel('Pozycja w podróży', { exact: true }).waitFor();
      const slider = page.getByLabel('Pozycja w podróży', { exact: true }); await slider.fill('2'); assert.equal(await slider.inputValue(), '2');
      await page.getByRole('button', { name: 'Odtwórz podróż', exact: true }).click(); await page.getByRole('button', { name: 'Wstrzymaj odtwarzanie', exact: true }).waitFor();
      await page.getByRole('button', { name: 'Wstrzymaj odtwarzanie', exact: true }).click();
      await page.getByRole('button', { name: 'Dodaj miejsce', exact: true }).click(); await page.getByLabel('Nazwa', { exact: true }).fill('Synthetic Browser Place');
      await page.getByLabel('Szerokość geograficzna').fill('0'); await page.getByLabel('Długość geograficzna').fill('0.05');
      await page.getByRole('button', { name: 'Zapisz miejsce', exact: true }).click(); await page.locator('.places-list strong').filter({ hasText: 'Synthetic Browser Place' }).waitFor();
      const saved = await get('places'); assert.ok(saved.places.some(p => p.name === 'Synthetic Browser Place'));
      await page.getByRole('button', { name: 'Edytuj Synthetic Browser Place', exact: true }).click();
      await page.getByLabel('Nazwa', { exact: true }).fill('Synthetic Browser Revised'); await page.getByRole('button', { name: 'Zapisz miejsce', exact: true }).click();
      await page.locator('.places-list strong').filter({ hasText: 'Synthetic Browser Revised' }).waitFor();
      assert.ok((await get('places')).places.some(p => p.name === 'Synthetic Browser Revised'));
      await page.getByRole('button', { name: 'Usuń Synthetic Browser Revised', exact: true }).click(); await page.getByRole('button', { name: 'Potwierdź usunięcie', exact: true }).click();
      await page.locator('.places-list strong').filter({ hasText: 'Synthetic Browser Revised' }).waitFor({ state: 'detached' });
      assert.ok(!(await get('places')).places.some(p => p.name === 'Synthetic Browser Revised'));
      // Deliberately deliver one stale detail response from the real API. The UI must
      // refuse to combine its geometry/metrics with a newer list revision.
      const detailRoute = '**/v1/analytics/journey?**';
      await page.route(detailRoute, async route => {
        const response = await route.fetch(); const body = await response.json(); body.source_revision--;
        await route.fulfill({ response, json: body });
      });
      await page.getByRole('button', { name: 'Odśwież analizy', exact: true }).click();
      await page.getByText('Historia została uzupełniona. Odśwież analizy, aby zsynchronizować podróż.', { exact: true }).waitFor({ timeout: 15_000 }).catch(async error => { throw Error(`${error}\nRendered state: ${await page.locator('body').innerText()}`); });
      assert.equal(await page.getByLabel('Pozycja w podróży', { exact: true }).count(), 0);
      await page.unroute(detailRoute); await page.getByRole('button', { name: 'Odśwież analizy', exact: true }).click();
      await page.getByLabel('Pozycja w podróży', { exact: true }).waitFor();
      // Backfill on another date still invalidates analytics because context can change.
      const requested = page.waitForRequest(request => request.url().includes('/v1/analytics?'));
      const p = { ...archive.points[0], event_id: randomUUID(), segment_id: randomUUID(), measured_at: '2026-09-15T08:00:00.000Z' }; await post([p]); await requested;
      await page.getByLabel('Pozycja w podróży', { exact: true }).waitFor();
      const gap = recording('2026-10-07T08:00:00.000Z'); gap.move(3); gap.wait(70); gap.move(3, { segment: id(8801) }); gap.points.forEach(p => { p.event_id = randomUUID(); });
      await post(gap.points); await page.getByLabel('Data okresu', { exact: true }).fill('2026-10-07');
      const chart = page.getByRole('slider', { name: 'Kursor wykresu prędkości', exact: true });
      await chart.waitFor(); assert.equal(await page.locator('.speed-path').count(), 2); assert.equal(await page.locator('.chart-gap').count(), 1);
      await chart.press('End'); assert.equal(await slider.inputValue(), '5'); await chart.press('Home'); assert.equal(await slider.inputValue(), '0');
      const map = page.locator('.journey-map canvas'); await map.waitFor(); const box = await map.boundingBox(); assert.ok(box);
      await map.click({ position: { x: box.width * .8, y: box.height / 2 } });
      assert.ok(Number(await slider.inputValue()) > 0, 'map selection updates the shared observed-sample cursor');
      await slider.fill('2'); await page.getByLabel('Tempo', { exact: true }).selectOption('120');
      await page.getByRole('button', { name: 'Odtwórz podróż', exact: true }).click();
      await page.locator('.replay-note').filter({ hasText: 'Pominięto przerwę obserwacji' }).waitFor();
      assert.ok(Number(await slider.inputValue()) >= 3, 'replay jumps to an actual sample after the gap');
      assert.ok((await page.locator('.historical-label').innerText()).includes('Pozycja historyczna'));
      assert.ok(await page.locator('.journey-map img').evaluateAll(images => images.every(image => image.complete && image.naturalWidth > 0)), 'local map style assets must load');
      assert.equal(await page.locator('vite-error-overlay').count(), 0);
      assert.ok(busy.every(url => new URL(url).pathname.startsWith('/v1/analytics')), 'only the bounded analytics worker may return expected busy responses');
      const expectedBusyConsole = /Failed to load resource: the server responded with a status of 429/;
      assert.deepEqual(errors.filter(error => !(busy.length && expectedBusyConsole.test(error))), []);
      if (busy.length) t.diagnostic(`${busy.length} expected analytics worker busy responses; all target interactions recovered through bounded GET retries`);
      const screenshotDir = process.env.ROUTEBOOK_ANALYTICS_EVIDENCE ?? tmpdir();
      await mkdir(screenshotDir, { recursive: true });
      await page.screenshot({ path: resolve(screenshotDir, 'routebook-analytics-desktop.png'), fullPage: true });
      await page.setViewportSize({ width: 390, height: 844 }); await page.screenshot({ path: resolve(screenshotDir, 'routebook-analytics-mobile.png'), fullPage: true });
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'mobile document overflows horizontally');
      await page.close();
    });
    await t.test('browser: corrected parking reads and late GPS reversal through real nginx/PostGIS', async () => {
      const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
      await page.route('https://api.maptiler.com/**', route => route.request().url().includes('/resources/logo.svg')
        ? route.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="110" height="24"><text x="2" y="17" font-size="12">Synthetic map style</text></svg>' })
        : route.fulfill({ json: { version: 8, sources: {}, layers: [{ id: 'synthetic-background', type: 'background', paint: { 'background-color': '#e9edf2' } }] } }));
      const errors = [], busy = []; page.on('pageerror', error => errors.push(error.message));
      page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
      page.on('response', response => { if (response.status() === 429) busy.push(response.url()); });
      const start = Date.parse('2026-10-08T08:00:00.000Z'), segment = randomUUID();
      const point = (seconds, lon = 0, speed = 0, segment_id = segment) => ({ ...archive.points[0], event_id: randomUUID(), segment_id,
        measured_at: new Date(start + seconds * 1000).toISOString(), lat: 0, lon, accuracy_m: 5, speed_mps: speed });
      const points = [point(-20, -.0004, 2.2), point(-10, -.0002, 2.2), ...Array.from({ length: 61 }, (_, i) => point(i * 5)), point(900, 0, 0, randomUUID())];
      await post(points);
      const day = await get('day?date=2026-10-08'); assert.equal(day.recorded_seconds, 320); assert.equal(day.in_car_seconds, 20); assert.equal(day.estimated_excluded_seconds, 300);
      await page.goto(web); await page.getByLabel('Wybrany dzień').fill('2026-10-08');
      const primary = page.locator('.recorded-metric dd').first();
      await primary.filter({ hasText: /^20 s$/ }).waitFor();
      await page.getByText('Zapis GPS i korekta', { exact: true }).click();
      assert.ok((await page.locator('.correction-detail').innerText()).includes('5 min 20 s'));
      await page.getByRole('button', { name: 'Zakres', exact: true }).click();
      await page.getByLabel('Od', { exact: true }).fill('2026-10-08'); await page.getByLabel('Do', { exact: true }).fill('2026-10-08');
      await page.getByRole('button', { name: 'Pokaż zakres', exact: true }).click();
      await primary.filter({ hasText: /^20 s$/ }).waitFor();
      await page.getByText('Dane dzienne (1)', { exact: true }).click();
      assert.ok((await page.getByRole('table').innerText()).includes('20 s'));
      await page.getByRole('button', { name: 'Analizy', exact: true }).click();
      await primary.filter({ hasText: /^20 s$/ }).waitFor();
      await page.locator('.journey-detail .recorded-metric').filter({ hasText: '20 s' }).waitFor();
      assert.ok((await page.locator('.period-card').innerText()).includes('Czas szacowany'));
      const evidence = process.env.ROUTEBOOK_ANALYTICS_EVIDENCE ?? tmpdir();
      await page.screenshot({ path: resolve(evidence, 'routebook-corrected-real-desktop.png'), fullPage: true });
      await page.setViewportSize({ width: 390, height: 844 });
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
      await page.screenshot({ path: resolve(evidence, 'routebook-corrected-real-mobile.png'), fullPage: true });
      // A late fix shortens the separating gap below ten minutes and reverses the
      // estimate. Native SSE must refresh both list and detail without navigation.
      await post([point(305)]);
      await primary.filter({ hasText: /^5 min 25 s$/ }).waitFor();
      await page.locator('.journey-detail .recorded-metric').filter({ hasText: '5 min 25 s' }).waitFor();
      const after = await get('day?date=2026-10-08'); assert.equal(after.recorded_seconds, 325); assert.equal(after.excluded_tail_seconds, 0);
      assert.equal(await page.locator('vite-error-overlay').count(), 0);
      assert.ok(busy.every(url => new URL(url).pathname.startsWith('/v1/analytics')));
      assert.deepEqual(errors.filter(error => !(busy.length && /Failed to load resource: the server responded with a status of 429/.test(error))), []);
      await page.close();
    });
  } finally { await browser.close(); }
}
