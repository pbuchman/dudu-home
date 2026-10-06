# Routebook contract v1

Status: implementation contract, 2026-10-05. Synthetic fixtures are normative examples.
Scope: automatic Dudu Home GPS archive, durable delivery, day/live map, observed stops >=3 min,
daily kilometres, date range and monthly chart. No route clustering, map matching, odometer
claim, rollback system, migration framework or backup system. New databases start at v1;
never clear an existing database to make a test pass. Preserve existing Dudu Home settings.

## Transport and access

Public origin exposes ONLY `POST /v1/ingest`; all other paths/methods return 404, including
history, UI and health. Dedicated Routebook Bearer token maps to exactly one device_id.
Reject missing/wrong token with 401 before application payload processing. HTTPS only,
system TLS, no redirects. Never reuse Health Connect credentials. Token, origin and server
secrets live outside repositories/APKs; import to private Android storage through a separate
private provisioning file. Disabled/missing config stops upload, not local GPS persistence.
Private origin is reachable only through Tailscale and serves UI, read API, SSE and `/health`.
Read routes must not be reachable on the public ingest listener, even by a valid ingest token.
CORS on ingest disabled; private frontend and API use the same origin. Cloudflare transports
GPS payloads; it is not a durable queue. MapTiler receives tile requests; no GPS ingest token
or track is sent to it. Its browser map key is distinct from ingest credentials.

## Point, envelope and identities

`POST /v1/ingest`, Content-Type `application/json`, envelope:

```json
{"version":1,"device_id":"00000000-0000-4000-8000-000000000001","lane":"latest","points":[{"event_id":"00000000-0000-4000-8000-000000000002","segment_id":"00000000-0000-4000-8000-000000000003","measured_at":"2026-10-05T08:00:00.000Z","lat":0,"lon":0,"accuracy_m":5,"speed_mps":null}]}
```

All fields shown are required; additional keys are rejected. UUIDs are lowercase canonical
hyphenated UUID strings, any UUID version. device_id is random installation identity, persisted
before first point, stable through process restart/APK update, never MAC/Android ID/account ID.
Clearing app data creates a new identity and requires reprovisioning its token binding.
event_id is generated once per fix and persisted WITH its payload in one SQLite transaction;
retry never regenerates it. segment_id is generated at collector start/restart, confirmed vendor
wake, provider loss or monotonic fix gap >30 s; not from TripController commands. Process
restart always begins a new segment. Segment UUID is not a day or manual trip identifier.

| Field | Validation |
|---|---|
| measured_at | GPS measurement UTC, exact `YYYY-MM-DDTHH:mm:ss.SSSZ`, valid Gregorian instant >=2000-01-01 and <=server request_received_at+5 min |
| lat / lon | finite JSON numbers, -90..90 / -180..180 degrees WGS84 |
| accuracy_m | finite number >0 and <=50 m |
| speed_mps | null when unavailable, otherwise finite number 0..80 m/s |

No coercion of strings to numbers; no NaN/Infinity, duplicate JSON object keys or invalid UTF-8.
No rejection for old but otherwise valid history. Server assigns `received_at` to first insert
using one UTC request_received_at for the batch, never trusts a sender-supplied receive time.
Point is immutable. Unique database key `(device_id,event_id)`; different device tokens cannot
write another device. Same measurement time with different IDs remains two records.
Normalize parsed numeric values and timestamps for duplicate equality; object key order and
JSON number spelling do not matter. Compare every point field, including segment_id. Identical
key+payload is duplicate; key+different payload is conflict, original unchanged. Within one batch
process input order, so first valid occurrence wins. Validation precedes duplicate lookup.

## Batch, ACK and failure

Defaults: max 200 points and 256 KiB encoded request; lane=`latest` requires exactly one
point, lane=`backfill` permits 1..200. Invalid envelope/version/device binding/JSON -> 422
(device binding mismatch ->403), framing/type ->400/415, oversized ->413. These reject
whole request without writes. Authentication ->401. Rate limited ->429; unavailable database
or failed transaction ->503, never a successful ACK. Error body is `{code: string}` without
payloads or secrets. 429/503 may supply Retry-After seconds.

A structurally valid envelope can contain invalid individual points. Insert valid new records
and resolve duplicates/conflicts in ONE database transaction; return HTTP 200 only AFTER
COMMIT, even for all-rejected/all-duplicate batches. On rollback do not send item successes.
Response:

```json
{"version":1,"device_id":"00000000-0000-4000-8000-000000000001","received_at":"2026-10-05T08:00:01.000Z","revision":1,"results":[{"index":0,"event_id":"00000000-0000-4000-8000-000000000002","status":"accepted","code":null}]}
```

One result per input index. status=`accepted|duplicate|rejected`; code=null for first two,
else `invalid_point|event_conflict`. event_id=null if missing/malformed, otherwise input UUID.
`received_at` in ACK is request receipt, not a duplicate's stored first receipt. revision is
per-device durable integer, starts 0, increments once per committed batch containing >=1 new
point; duplicates/rejections alone do not increment. Transactions serialize this increment.

Android verifies version/device, complete indexes, IDs and statuses before applying an ACK.
Only accepted/duplicate items are removed from pending in a local transaction. Rejected items
are retained in the same SQLite database with terminal rejected status/code and excluded from
retry, with category/count diagnostics. Envelope/config errors retain pending and report blocked
upload; never retry in a tight loop. A network timeout/lost ACK/invalid ACK leaves pending and
may repeat the identical points. 429, 5xx and network failures use bounded exponential retry
2,4,8,...120 s with 0..20% jitter, honor larger Retry-After up to 15 min; reset after success.
Retry survives process restart via persisted points (timer may restart); never relies on RAM.
No automatic expiry of pending/rejected records. Storage exhaustion/failure must report degraded
collection and break the next segment; do not pretend unsaved fixes are durable.

## Android collection and latest-first scheduling

Reuse HomeMonitorService's GPS subscription; do not add a second service/provider or depend
on the manual TripController state. Submit immutable copied fixes AFTER home automation opportunity,
including early-return paths. SQLite and HTTPS run off the UI thread on separate serial workers.
GPS persistence cannot wait for upload, geocoding, MapTiler, robot or gate execution. Routebook
never acquires the action lease, rearms automation or opens an Activity/overlay on a fix.
Existing service permission/maintenance and vendor wake restrictions remain in effect.

Initial configurable collector defaults: request interval 1 s (existing service), store at
most one accepted fix per 5 s, age <=5 s by monotonic clock, accuracy <=50 m, reject mock/non-finite
fixes. No fix when radio sleeps is synthesized. Wall-clock regression or invalid GPS clock breaks
segment and suspends point acceptance until valid non-regressing measurement time returns.
Repeated callbacks for the same monotonic fix do not create another event. DB synchronous durable
commit before pending visibility; no destructive version reset. This is not proof of power-loss
survival on the physical radio's storage.

At reconnect, select newest pending by `(measured_at,event_id)` descending and send lane=latest
first, then oldest pending backfill batches. One HTTP request in flight, timeout 10 s; before EACH
backfill request check for a newer pending point not yet attempted in this upload pass and send
it as latest. A failed request enters backoff; at next eligible attempt reselect newest before
backfill. Under normal operation prioritize the newest fix, then drain bounded backfill. lane
is scheduling metadata only, not authority to replace server latest. Sustained connectivity
and sufficient throughput are necessary to drain a growing backlog.

## Canonical server derivation

Backend owns map segments, distance and stops. UI consumes results, never independently applies
TripDistance (its thresholds intentionally differ). Recompute affected results from immutable
points on read in the POC; late points may change historical totals/stops/segments. No cached
aggregate migration or generic processing framework. Sort by `(measured_at,event_id)` ascending,
lexicographic lowercase UUID tie-breaker, per device. Latest is the maximum of the same tuple,
regardless of receipt order/lane. Old backfill never moves it backward. At equal time only the
maximum UUID is latest. Equal-time edges break continuity.

Derived contiguous runs split when adjacent points have different segment_id, time delta <=0
or >30 s, or haversine distance / delta >80 m/s. No line/distance/stop bridges these breaks.
Run ID is first point event_id, can change when backfill arrives; UI must replace its data.
Haversine uses R=6371000 m, coordinate angles in radians. Values in API are unrounded floating
point meters; fixtures compare with 0.01 m tolerance. PostGIS stores geometry(Point,4326), but
spheroid ST_Distance is not substituted for the defined spherical metric.

For each valid adjacent edge, count 0 m if BOTH endpoint speeds are non-null and <0.7 m/s,
or distance <max(5 m, average endpoint accuracy); otherwise count haversine distance. Assign
ENTIRE edge distance to Europe/Warsaw calendar date of ending point, including midnight crossings.
No inferred route across gaps or interpolation at midnight. This is estimated GPS distance,
not a calibrated odometer. Monthly bar = sum of daily meters, km=meters/1000, round only display.

Stops: scan each contiguous run in chronological order. Candidate anchor is first point with
speed null or <=0.7; extend while each point is <=25 m from anchor and speed null or <=0.7.
A moving/outside point ends candidate at its LAST stationary point and can start another only
if itself stationary. Run end ends candidate at last point. Emit if last-first >=180 s;
no gap >30 s allowed, minimum observation span exactly 180 s qualifies. Center=anchor lat/lon,
stop_id=anchor event_id, start_at/end_at=observed endpoints, duration_seconds=(end-start)/1000.
No claim of physical arrival/departure or ongoing stop beyond last measurement. No joining
stops across a GPS gap. A crossing-midnight stop appears in each overlapping day, with unchanged
full interval/duration and day `overlap_seconds`; do not sum full durations across daily pages.
Use half-open overlap intervals, include only positive overlap. Stop candidates need surrounding
run points across day boundaries; clipping first would miss midnight stops.

All thresholds above are configurable defaults, not verified DUDU hardware thresholds. Backend
read responses include effective `derivation` values: gap_seconds, max_edge_speed_mps,
stationary_speed_mps, min_edge_m, stop_radius_m, stop_seconds, earth_radius_m.

## Private read API v1

All reads require `device_id` UUID; unknown device ->404, invalid params ->422, DB failure ->503.
UTC instants same encoding as points; dates strict `YYYY-MM-DD`, only timezone `Europe/Warsaw`.
Day bounds use IANA timezone, next LOCAL midnight (23/24/25 h), not fixed 86400 s. Read point shape
is ingest point plus device_id and stored received_at. Revision accompanies every read. Responses
are derived from one database snapshot; GET never changes revision. Empty known device: null latest,
empty segments/stops, 0 totals. Provisioning registers device before its first ingest.

| Route | Response |
|---|---|
| GET /v1/latest?device_id=... | `{version,device_id,revision,server_time,point:Point\|null,fresh:boolean,clock_state:empty\|future_clock\|fresh\|stale}`; fresh iff point age at server_time is between 0 and 30 s inclusive; empty for null, future_clock for negative age, stale for age>30 s |
| GET /v1/day?device_id=...&date=... | `{version,device_id,revision,date,timezone,derivation,meters,point_count,segments:[{run_id,source_segment_id,points:Point[],edge_from:Point\|null}],stops:[{stop_id,lat,lon,start_at,end_at,duration_seconds,overlap_seconds}]}` |
| GET /v1/range?device_id=...&from=...&to=... | inclusive calendar dates, max366 days: `{version,device_id,revision,timezone,from,to,derivation,days:[{date,meters,point_count,stop_count}],months:[{month:YYYY-MM,meters}],meters}`; include zero days and all months touched |
| GET /v1/live?device_id=... | SSE described below |
| GET /health | private only, `{status:ok}` iff DB connectivity works, else503; no coordinates/counts |

Day segments contain only points with measured_at inside day in chronological runs. For each
run subset `edge_from` is immediate predecessor outside day ONLY if its valid counted/zero edge
ends at first in-day point; otherwise null. Render that boundary edge separately. point_count
excludes edge_from. A one-point run still appears as a point. Day meters include valid edges
ending in day, even if predecessor is prior day. stop_count counts stops with positive day overlap.
Range uses exactly the same daily semantics, months contain only requested days (label partial
months in UI), range meters=sum(days.meters). No points pagination in first POC: >=5 s sampling
bounds a day to ~17280 points; range never returns track points. Range from>to rejects.

SSE sends `event: snapshot` on connect and `event: update` after commits with new points.
Data for both: `{version,device_id,revision,latest:<full /v1/latest response>,affected_dates:[dates]}`.
Snapshot affected_dates=[]; update contains dates whose day/stops may change, including any
adjacent local day affected by a boundary edge/stop. Conservative superset allowed. Publish only
after commit; duplicate-only batch sends no update. SSE id is decimal revision; every event's
latest uses committed maximum, even on backfill. Heartbeat comment every15 s, no point payload.
No replay log: Last-Event-ID is not a resume guarantee. On every connect/reconnect UI refetches
selected day/range and applies snapshot; ignores lower revisions within a connection, refetches
when revision skips. Slow clients are disconnected rather than holding database transactions.
Fresh/stale/future_clock is recomputed using server_time offset plus client elapsed time, even with no new event. A future measurement within the 5 min validation tolerance can be stored and be latest, but must never be labelled live before its time arrives. UI shows a clock warning and measurement time, rather than advancing a live marker as if this were a reliable current fix.

## Range map and observation gaps

/v1/range supplies summaries, not geometry. To map the selected inclusive range (max366 days),
UI fetches /v1/day for each requested date with a worker queue of at most TWO concurrent
requests and a visible completed-days/total-days progress indicator. No Promise.all over all
days. Cancel queued/in-flight work on selection change; ignore results from older selection
generations. Load in chronological chunks of7 days. Each day response is released after its
geometry is added to the map; stop fetching when the view is cancelled. POC may render at most
1000 evenly sampled points per day across runs (always preserve every run first/last point,
edge_from and stop markers; if mandatory endpoints exceed the target, preserve them). Sampling
is display-only, never joins separate runs and never alters backend distance/stops. A visible
label states that a long-range map is simplified; day view shows full points. Label partial
loading and failed dates, never claim complete coverage from a partial map. Range summaries
come from /range regardless of rendering samples. No new bulk geometry API is required.

A gap between adjacent observations that breaks continuity is absence of measurement, not
a stop. UI shows a route break and an interval labelled "Brak obserwacji GPS" with last/next
measurement times for observed gaps. Before first and after last available point there is no
known duration of parking: show "Ostatnio widziany" and stale/missing position. Radio off,
vendor sleep, missing permission or poor fixes cannot certify >=3 min stationary behaviour.
Do not infer parking or draw a connecting line through these intervals. Stops shown by UI
are only the measured stationary intervals defined above.

## Acceptance boundary

fixtures.json uses fictitious equatorial coordinates and deterministic identities, not GPS logs.
Must pass: lost-ACK retry, conflict/invalid partial response, late history recomputation without
latest regression, gap splitting, exactly3 min stop, midnight totals/stops and DST day bounds.
Backend restart must retain committed points/revision; Android restart must retain pending IDs.
Ordinary emulator/synthetic tests cannot prove DUDU wake, radio GNSS, background delivery,
physical route accuracy or installed APK acceptance. No radio installation in this stage.
