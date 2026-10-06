# Routebook POC

Dudu Home route archive. This directory and the Android application are versioned together.
`backend/` owns the canonical HTTP API/derivation; `ui/` owns the frontend.
The normative v1 contract and fictitious equatorial fixtures are in `contract/`.
No deployment or radio installation is implied by local build/tests.

## Install and build

Node.js >=22, npm, PostgreSQL with PostGIS. Backend dependencies: Fastify 5.12.5,
pg 8.23.1, Temporal polyfill 0.5.1. Development: TypeScript 5.9, tsx 4, Node/pg types.

The shared root lockfile is ready. Install and verify from this directory:

```sh
npm ci
npm run build
npm run check:contract
npm test
```

Without the dedicated synthetic DB variables, real PostgreSQL tests are explicitly skipped.
For full backend verification set `ROUTEBOOK_TEST_DB_URL` and
`ROUTEBOOK_TEST_RESTART_CONTAINER` as documented below. UI unit tests use
`ROUTEBOOK_FIXTURES_PATH`; backend uses `ROUTEBOOK_FIXTURES`. Both override the bundled `contract/fixtures.json`. Workspace commands remain `build:backend`, `test:backend`, `start:backend`,
`dev:ui`, `build:ui`, and `test:ui`. Node26/npm11 was exercised; Node>=22 remains the declared minimum.

## Private runtime configuration

Create `~/.config/routebook/` with mode 0700 and `config.json` with mode 0600, outside
this checkout. Backend accepts only distinct loopback ports. Candidate ports below require
host inventory before deployment. `ROUTEBOOK_CONFIG` contains the absolute config path,
never credentials. The process never logs config, tokens, HTTP headers, GPS payloads or
raw database errors. Each configured token maps to one random installation device UUID.
Generate at least 32 random bytes as base64url for each new dedicated Routebook token;
never reuse Health Connect or map credentials. Place the database password and token only
inside this private file, through a private editor/provisioning channel, not shell arguments.

Configuration shape (replace placeholders; this is not a working credential file):

```json
{
  "database_url": "postgresql://ROUTEBOOK_ROLE:PRIVATE_PASSWORD@127.0.0.1:5432/routebook",
  "public": { "host": "127.0.0.1", "port": 8791 },
  "private": { "host": "127.0.0.1", "port": 8792 },
  "devices": [{
    "device_id": "00000000-0000-4000-8000-000000000001",
    "token": "REPLACE_WITH_PRIVATE_RANDOM_BASE64URL_TOKEN"
  }],
  "derivation": {},
  "rate_limit_per_minute": 120
}
```

`derivation` optionally overrides any positive numeric v1 threshold: `gap_seconds`=30,
`max_edge_speed_mps`=80, `stationary_speed_mps`=0.7, `min_edge_m`=5,
`stop_radius_m`=25, `stop_seconds`=180, `earth_radius_m`=6371000. Defaults are not hardware
calibration. Responses for day/range expose the effective values. Set the same derivation
config for read and write processing, as the CLI does.

Create a dedicated database/role with PostGIS available. Bootstrap requires permission to
create the extension/tables/indexes/immutable-point trigger; alternatively a database owner
can pre-create the extension. There is one idempotent v1 bootstrap, no migration framework
or destructive reset. The web can start before radio installation: set backend `devices: []` and UI runtime
`{"mode":"api"}` without `deviceId`. No ingest token is created in that state; every public
ingest request receives401. The UI displays "Oczekiwanie na połączenie radia" and sends no
read/SSE requests. Private health and idempotent bootstrap work without a device. Never invent
a production UUID. After the user installs the APK, bind its real persisted installation UUID
through private provisioning and reload backend/UI configuration, preserving database/settings.
Provision configured devices before ingest, including devices with no fixes:

```sh
cd backend
npm run build
ROUTEBOOK_CONFIG="$HOME/.config/routebook/config.json" npm run bootstrap
ROUTEBOOK_CONFIG="$HOME/.config/routebook/config.json" npm start
```

Runtime delivery needs `backend/dist/`, `backend/sql/schema-v1.sql`, package dependencies
and the private config. Keep PostgreSQL data in a persistent volume outside Git, with
`fsync=on`, `full_page_writes=on` and durable WAL. CLI checks `fsync` and `full_page_writes` before bootstrap/start; ingest enforces `synchronous_commit=on`.
Use a single backend process serving both listeners; SSE notifications are in-process and
are not a multi-writer notification bus. SIGTERM/SIGINT close SSE, listeners and the pool.

Public listener exposes only POST `/v1/ingest`. All other paths/methods return404, even with
a valid token. Authentication runs before parsing. Strict UTF-8/JSON rejects duplicate keys,
additional fields and coercion. Limit256 KiB /200 points. Error bodies expose only a code.
Per-device fixed-window rate limit is configurable,429 carries Retry-After60; DB failures503
carry Retry-After2. A row lock serializes each device's writes, conflict decisions and revision.
ACK and SSE update happen only after COMMIT. Duplicate-only batches neither advance revision
nor emit an update. Point UPDATE/DELETE is forbidden by a database trigger.

Private listener exposes latest/day/range/live SSE and `/health`, with no ingest route.
Deployment must serve frontend and private API on one Tailscale-only origin; route `/v1/*`
and `/health` to the private listener and UI assets to the frontend host. Public Cloudflare
HTTPS forwards solely to the public loopback listener. Do not expose private8792 through
the tunnel, do not enable Tailscale Funnel, and do not put tokens in frontend config. No TLS
or redirects are implemented on loopback: HTTPS terminates at the authorized ingress.
Deployment and UI serving belong to the infrastructure/UI workstreams, not this backend.

## Read cost and derivation

Latest and SSE snapshot fetch only the indexed maximum `(measured_at,event_id)`. Ingest
uses primary-key duplicate reads and indexed neighbour/segment bound probes, never an archive
payload scan. Update `affected_dates` conservatively covers touched source-segment date bounds
plus adjacent days, including an old segment whose edge a late insertion split. A new
unrelated segment does not invalidate historical routes. The matching segment index is in
schema-v1.sql. Shared source segments spanning disconnected runs can over-invalidate dates.

Day/range fetch the selected measurement interval, one predecessor/successor and extend
adjacent continuous runs in indexed batches256 until continuity breaks. This preserves
canonical run IDs and stop anchors/full intervals, including long midnight stops. A continuous
run spanning many days requires that additional context; this is an explicit POC cost, not a
fixed180s approximation. Queries use one REPEATABLE READ snapshot and do not change revision.
Range derives once and builds daily summaries in one point pass; it returns no track geometry.
The explicit full `snapshot()` without bounds exists only for synthetic inspection, not HTTP.
Memory/latency for very long continuous runs or a full366-day range needs deployment-scale
measurement; no cached aggregates or general processing pipeline are included.

Distances use the contract's spherical haversine, not PostGIS spheroid distance. Geometry is
stored as `geometry(Point,4326)`. Europe/Warsaw bounds use IANA next-local-midnight through
Temporal, including23/25h DST days. Latest `future_clock` never reports fresh until the
measurement instant arrives. SSE emits snapshot/update, decimal revision ID, heartbeat15s,
no replay; slow consumers are disconnected. UI reconnect must refetch selected views.

## Synthetic verification

Tests read the bundled fixtures via `ROUTEBOOK_FIXTURES` (absolute path recommended):

```sh
cd backend
npm run build
ROUTEBOOK_FIXTURES=/absolute/path/to/fixtures.json npm test
```

Without an explicitly supplied synthetic DB URL, PostgreSQL/process tests are marked skipped;
logic/HTTP/config tests still run. Real integration refuses database names not beginning
`routebook_synthetic`. It registers fresh random devices each run and never clears a database.
Only synthetic points/credentials are used. Tests run sequentially because an optional real
DB-container restart intentionally changes the dedicated DB's availability.

Local disposable synthetic DB (loopback, trust auth only for this isolated test container):

```sh
docker run -d --platform linux/amd64 --name routebook-backend-synthetic-20261005 \
  -e POSTGRES_HOST_AUTH_METHOD=trust -e POSTGRES_DB=routebook_synthetic \
  -p 127.0.0.1:55439:5432 postgis/postgis:17-3.5
# Wait for docker exec <name> pg_isready -U postgres to report accepting connections.
ROUTEBOOK_FIXTURES=/absolute/path/to/fixtures.json \
ROUTEBOOK_TEST_DB_URL=postgresql://postgres@127.0.0.1:55439/routebook_synthetic \
ROUTEBOOK_TEST_RESTART_CONTAINER=routebook-backend-synthetic-20261005 npm test
```

Optional restart variable accepts only `routebook-backend-synthetic-*` container names.
The compiled CLI test creates its synthetic0600 config in a temporary directory outside Git,
boots the process twice and closes an active SSE stream during shutdown. SQL integration
checks real deferred COMMIT failure, concurrent retries, immutable geometry, first receipt,
revision persistence, all normative fixtures, scoped queries and conservative invalidation.
Evidence and runtime limitations: `../docs/VERIFICATION.md`.

## Real local Android/backend/UI acceptance

The integration runner exercises actual emulator HTTPS upload, real PostgreSQL/PostGIS,
production API/SSE and MapLibre rendering. Results are recorded in `../docs/VERIFICATION.md`. Reproduce with the emulator already running,
Android APK built, dedicated synthetic container available and Chromium installed for UI QA:

```sh
docker start routebook-backend-synthetic-20261005
npm run build
npm run test:integration
```

The runner refuses physical devices, builds only the test instrumentation, installs on the explicit
emulator, generates one new synthetic device/DB filename per run and never clears existing data.
TLS keys, CA and random token exist only in an owner-only temporary directory outside Git and
app-private test files removed afterwards. Listeners bind only loopback18791/18792/18443/18580.
The test CA is trusted solely in the instrumentation process. Production `Uploader.https`,
Outbox, Protocol and hostname verification are used. Privileged443 is unavailable on this emulator;
a test-only reflection override sets endpoint port18443 after production config parsing. Neither
production code/config validation nor application APK changes. Untrusted CA and incorrect hostname
both fail. This is real HTTPS with a local test CA, not final Cloudflare TLS acceptance.

The repeatable integration suite uses Playwright Chromium at desktop1440x950 and mobile390x844.
Only MapTiler style/logo requests are substituted with a labelled empty local style. All API/SSE
requests reach real Fastify/PostGIS; no API fixtures/demo adapter or ingest token is used by UI.
The MapTiler provider/key and final home-dev runtime remain unverified. Headless WebGL ReadPixels
warnings and the deliberate SSE disconnect network error are recorded as expected harness events.

Application and backend/UI must be built from the same Git revision. Record the private APK
hash in its release receipt; do not infer installed-radio identity from a source commit.
Deployment prerequisites are in `docs/HOME_DEV.md`. Hardware GNSS, wake and background delivery
must be accepted separately on the radio.
