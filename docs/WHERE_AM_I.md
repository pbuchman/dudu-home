# Where am I

Introduced in **1.2.0-rc1 / code 19**. Polish UI: **Gdzie jestem**. The fourth action tile
opens a passive screen; opening it never starts a trip. This feature does not use Google
location data, Android Geocoder or Play Services. Existing manual Google Maps navigation
remains independent.

## User contract

**Start** begins or continues the manual session, preserving its totals. **Pauza** holds both
counters. **Reset** zeros time and distance together while retaining the active/paused state;
nonzero totals require confirmation **Wyzerować czas i dystans?**, and **Anuluj** is inert.
There are no separate Resume or End controls. A change appears only after durable storage
succeeds; a storage error remains visible and disables counting and controls until restart.
Backgrounding or turning the screen off does not pause. The full screen shows locality,
street, kilometres (one decimal) and **Czas sesji** as HH:MM:SS (hours may exceed 24).
The time counter is on this main screen only; the existing location card remains address-only.
Automatic Routebook recording, offline queue and ingest remain independent of all session controls.
The card starts at the top right, is at most 286 dp wide and shows locality at 16 sp above
the primary street at 25 sp, with a minimum height of 112 dp. The 48 dp × button sits beside
the address; there is no visible drag handle/instruction row. Hold any non-button area for 350 ms, then move horizontally.
The held card visibly dims; releasing a drag never opens the trip. The independent
**×** button hides only the card. **Pokaż panel w tle** / **Ukryj panel w tle** on the passive
trip screen changes that preference, including before a trip starts. Hiding stays in effect
until explicitly restored there, across service/process restarts and later trips. Counting,
shared GPS monitoring, Routebook collection and the ongoing notification continue unchanged.
The private UI preferences contain visibility and a normalized position, with no location data.
Close/toggle and drag release synchronously commit that small file before completing the UI
change; individual movement events never write. A failed save keeps/restores the prior choice
and shows an error instead of claiming the new choice will survive restart.

Street names wrap naturally on word boundaries, using up to three lines and the required card height;
text does not shrink or scroll automatically. If the measured layout overflows, an ellipsis
and visible **Pełna nazwa** button open the passive full screen. Tapping the card does the
same. The accessibility label retains the entire address. The full screen keeps unlimited
street lines in a ScrollView. The silent ongoing notification includes the complete address
in its expanded BigText presentation and one content intent.

Without a fresh fix, show **Brak sygnału GPS** instead of claiming the last name is current.
Before the first confirmation, missing map/online evidence shows an unknown locality/street
while qualified GPS movement continues to count. A confirmed street remains visible through
unknown, ambiguous or unconfirmed results until another road has two distinct valid fixes.
It has no time/distance retention limit or extra prefix. Confirmed unnamed roads hide the
street row, leaving locality only. The retained label is presentation state, not a fresh match. A point settlement without containment/address evidence is
labelled **Okolice …**. A municipality is not substituted for a town or village.

RC3 matches locality independently of street selection. A reliable containing locality
boundary outranks a nearby hamlet; a road can be unknown while the locality remains certain,
and a matched road can remain visible while locality is unknown. Municipality/gmina, district
and neighbourhood labels are never promoted to primary locality names. A nearest
city/town/village point without containment/address evidence stays **Okolice …**.

## Safety and presentation priority

The existing location foreground service supplies one immutable copy of each GPS fix after
home action processing, including the media gate's early-return path. Trip work never takes
`HomeActions`' execution lease, changes gate conditions, reserves cleaning, consumes media
opportunities, or launches Maps. A session pause/reset does not stop the shared monitor.

The separate TripActivity is passive: unlike the existing manual menu it does not register
as protected manual UI. Gate/cleaning can cover it and media startup can proceed. The
location overlay is hidden while automation presentation, protected UI or execution owns
the screen. A notification tap routes through MainActivity and waits for a safe menu state
before opening the trip. Merely changing a name never launches an Activity.

The location overlay is at most 286 dp wide, bounded by the available window width minus 32 dp,
with 16 dp margins inside current system bars/cutout bounds. Its height wraps its content up
to the available safe height; a short viewport scrolls the address while the close button remains reachable beside it. The saved relative position adapts to width, height, inset and name changes,
keeping the whole card reachable, and survives temporary automation suppression. It is non-focusable,
receives touches within its own window, and has no full-screen transparent input surface.
The compact floating automation banner is at most 350 dp wide and at least 91 dp high,
with a short **Anuluj** button. It uses the same 350 ms non-button hold and horizontal drag,
with its own persistent position. Its inline menu banner stays in place. **Anuluj tę próbę**
keeps the captured attempt token and native tap behavior; a drag release cannot activate it.
Moving either surface changes presentation only. The automation banner receives cancellation
touches within its own bounded window. System apps may suppress overlays;
this feature does not bypass such restrictions.

## Distance and persistence

`TripDistance` is pure Java. It sums successive qualified segments, preserving turns through
a bounded 64-fix queue. A separate latest-position resolver cannot delay this queue. Queue
overflow breaks the segment instead of drawing a shortcut across missing samples.

Initial quality limits: age <=5 s, accuracy <=25 m, gap <=10 s, plausible displacement speed
<=80 m/s. Mock, non-finite and out-of-order samples cannot add distance. Reported GPS speed
below 0.7 m/s suppresses stationary drift; movement with reported speed requires 2 m displacement.
Without speed, displacement must exceed max(5 m, average endpoint accuracy). This is a driving
estimate, not a calibrated odometer; slow movement and poor GPS can be undercounted.

`TripTime` independently accepts fresh, increasing non-mock GPS observations with valid
coordinates and finite nonnegative accuracy. Stationary and poor-accuracy observations may
add time even when the distance filters reject them. Each adjacent observation must be at
most 30 seconds apart; exactly 30 seconds qualifies. No time is extrapolated before the first
or after the last observation. Receive elapsed-realtime and uptime clocks are captured before
queueing; interval time uses the smaller received/awake monotonic delta, while the GPS measurement
delta validates ordering and continuity. Receive jitter is not repeatedly discarded. Deep-sleep
clock divergence breaks continuity, and suspended time is never included. System wall time
is not an input. The result measures observed session time, not driver presence.

A separate passive vendor observer can remove confirmed radio-OFF stationary intervals
from time only. The default production profile is diagnostic **UNKNOWN**, so the candidate
ACC/MCU values do not yet change totals. After explicit physical validation for the exact
firmware, OFF needs three stable seconds and two qualified stationary fixes at most fifteen
seconds apart (speed <=0.7 m/s, accuracy <=25 m, displacement <=15 m). Motion contradicts OFF
and requires a fresh OFF observation. ACC OFF or screen OFF alone cannot cut time; waiting
with the radio ON counts. Freshness expires after forty-five seconds. An active manual
session resumes counting with known ON; a user-paused session stays paused.

The three-second decision is provisional in RAM: durable totals never need retrospective
subtraction. A brief false OFF counts normally. Pause/continuity boundaries conservatively
count unsettled evidence; Reset and input-generation guards prevent an old OFF from changing
a new session. Distance and the v1 GPS archive remain independent. Restart starts UNKNOWN
without bridging power history. [Observer, profile activation and separate v2 outbox](RADIO_POWER.md).

Pause, Reset, GPS loss, service shutdown, verified wake and process restart begin fresh
segments. Queued fixes carry an input epoch; stale work and asynchronous place results cannot
restore pre-reset time, distance or address. Never join positions across missing data.
`trip-state.json` in no-backup storage atomically contains state, generation, total meters,
`time_ms` and `partial_time`, not a track. The store syncs the file and verifies the committed
contents before acknowledging transitions. The periodic worker attempts a checkpoint after
at least five seconds since the previous save. Scheduling and storage latency can extend
that interval; sudden power loss can lose measurements since the last completed save.
A failed transition retains the prior totals/activity and reports an error; counting is disabled.

Legacy ACTIVE stays active; legacy PAUSED, FINISHED and OFF become paused with their metres
preserved. Unknown historic time starts at zero and remains labelled **Czas od aktualizacji**
until Reset; no file timestamp is used to invent a duration. Missing state starts paused at
zero without this partial-history label. All restarted sessions require a new GPS anchor.

## Offline data

`scripts/build-place-index.py` runs on a computer with **osmium 4.3.1** and Python SQLite.
It reads a Geofabrik OSM extract, uses a disk-backed node index and writes:

- road segments with names, references and explicit locality tags;
- city/town/village and subordinate settlement points, plus settlement areas;
- named Polish `boundary=administrative`, `admin_level=8` locality polygons, regardless of
  whether their relations also carry a `place` tag, with explicit kind/provenance;
- locality-bearing address points/building centroids;
- grid lookup tables, schema, provenance, date, licensing and embedded heading source proof.

Android opens the resulting SQLite file read-only with platform APIs. Nearby road geometry,
heading and ambiguity checks select a street. Administrative locality boundaries, primary
settlement areas and nearby consistent addresses supply locality evidence independently.
Reliable primary containment wins over hamlet evidence; weaker nearby city/town/village points
remain explicit hints. Locality quality is UNKNOWN, AMBIGUOUS, NEARBY, ADDRESS or CONTAINMENT;
street quality is tracked separately, including MATCHED. Each component records its own
source category and stabilizes across distinct fixes. This is a bounded
local matcher, not a full navigation/routing engine. Missing or conflicting OSM data stays unknown.

```sh
python3 -m venv /path/outside/git/map-tools
/path/outside/git/map-tools/bin/pip install osmium==4.3.1
/path/outside/git/map-tools/bin/python scripts/build-place-index.py \
  /path/outside/git/poland.osm.pbf /path/outside/git/maps/places.sqlite \
  --region Poland --data-date YYYY-MM-DD
```

Use the actual source-data date. Keep source and output outside Git. The builder replaces its
one designated `places.sqlite` and emits no manifest or heading-audit sidecar. SQLite metadata
contains source hashes, timestamp, licensing and policy; `road_heading_sources` stores source
way/node/degree proof. OSM scratch is disposable and removed on completion/failure. Failed
builds remove the incomplete output; there is no retained previous map or automatic recovery.

The installer validates the actual database and computes SHA-256 at runtime. An identical
radio hash skips transfer. Otherwise, while the app is idle/stopped and installation blocked,
it directly overwrites `no_backup/places.sqlite`, then verifies SHA-256. A failed transfer
leaves the block in place and requires an explicit retry; it never launches the incomplete map.
Cleanup is limited to exact historical artifact names in the selected managed map directory.

The builder writes **map schema 3**. Existing schema2/3 packs remain valid without sidecars;
legacy schema1 remains readable by Android but is not accepted for normal installation.
Schema2 requires code23+ and schema3 requires code29+. Check the target APK before replacement.
Map schema is independent of navigation JSON schema2. National maps are local, never APK assets.

### Source-proven short heading (local code 29)

Schema 3 adds `road_short_headings(road_id INTEGER PRIMARY KEY, heading REAL,
support_length REAL)` without replacing road geometry or locality tables. Only nonzero
fragments at most 5 m can receive support. The computer follows ordered segments within
one original OSM way, at most 10 m/eight edges each way, traversing only source nodes with
exactly two eligible-road endpoint incidences. Way ends and branches stop extension;
missing/zero geometry, repeated nodes/cycles, an edge limit or a directed deviation above
15 degrees rejects support. Both chain and endpoint chord must reach 10 m.

Heading is degrees clockwise from north using a local equirectangular projection with
111195 metres per degree and longitude scaled at the fragment midpoint latitude. Runtime
treats it bidirectionally, only with actual GPS bearing and reported speed above 2 m/s.
Support must be finite, at least 10 m and at most 25 m (one micrometre numerical tolerance);
heading must be finite and in [0,360). Invalid/absent support or an unknown policy retains
the old score. No road class/name is filtered and all existing ambiguity/confirmation,
query, locality and online freshness rules remain intact. Display retention is separate from
these raw matching rules, as described below.

Metadata binds `same-osm-way-degree2-v1`, its exact policy SHA-256, original PBF SHA-256
and deterministic map generation. The embedded `road_heading_sources` table records way ID,
segment ordinal, support range, source node IDs and interior degree proof. These private map
rows are never part of public source or the APK. The builder uses a disk SQLite incidence pass (32 MiB page cache, at most
100000 pending distinct nodes), then the existing disk-backed located OSM pass. The
incidence pass conservatively includes zero/missing edges and duplicate ways, which can
reject support but cannot create an unproved continuation. Keep enough disk for the one map,
node/incidence scratch and indexes. Do not overwrite the source.

Private opt-in trace adds supported-heading count, the best candidate's heading source and
support length, and map generation. It retains the same bounded queue/files and privacy rules.
This candidate needs an exact schema-3-pack replay, held-out route evidence and physical
presentation/latency acceptance; synthetic geometry and score gains are not correctness labels.

Sources: [Geofabrik Poland](https://download.geofabrik.de/europe/poland.html),
[OSM settlement model](https://wiki.openstreetmap.org/wiki/Key:place).
Map data is **© OpenStreetMap contributors, ODbL 1.0**, independently of the MIT application
license. Attribution is available in Settings > Dane map OSM and in pack metadata.
[License and attribution](https://www.openstreetmap.org/copyright).

## Optional Internet assistance

The keyless [public Photon endpoint](https://github.com/komoot/photon/blob/master/README.md)
is used only when local evidence is incomplete. One request is in flight; requests require
at least 30 s and 100 m since the previous request, with a persisted 300-request UTC-day
budget and persisted restart rate guard. These are application limits, not an advertised
Photon allowance. Photon permits reasonable use but provides no SLA and may throttle/block it.

Native HTTPS uses platform TLS, a descriptive User-Agent, no redirects, 3 s connect and 5 s
read timeouts, a 64 KiB response cap, 429/Retry-After handling and exponential backoff.
Only coordinates for the requested current position are sent; no phone, robot configuration
or trip total is sent. Responses are bounded, checked for proximity and treated as stale when
the session/position changes. Names stabilize separately for locality and street across
distinct fixes. Photon may fill a missing street without replacing a certain offline locality;
a nearby/ambiguous online label cannot downgrade reliable containment or address evidence.
A small in-memory result
cache is an optimization; the downloaded map is the actual offline fallback.

### Confirmed street display and raw evidence

Offline lookup and optional Photon HTTP use separate latest-only workers. An online response
can enrich only the exact current offline fix and place generation. Online cache evidence
expires after 30 seconds and 40 metres from its original fix; cache reuse never renews that
origin. Existing request/quota limits, schema-3 heading support, distance/margin ambiguity and
locality rules are unchanged.

A new named or explicitly unnamed road needs two distinct valid GPS fixes. Offline and online
results for one fix count once. A changed candidate keeps the previous confirmed display until
its second fix. Missing coverage, ambiguous roads, a different locality and isolated noisy
fixes do not erase a confirmed street. Retained named display uses `LAST_KNOWN` internally
and shows the plain name; raw `UNKNOWN`/`AMBIGUOUS` remains visible in diagnostics and trace.
This must never be counted as an improved raw matcher score. A typed `UNNAMED` road contains
no made-up street string and suppresses the street row only after confirmation. Photon cannot
replace this explicit offline evidence with a nearby named street.

The current 10-second valid-GPS freshness rule controls loss: invalid/noisy fixes do not renew
its timer or clear a name immediately. Provider disablement, an explicit session/journey
boundary, or more than 10 seconds without a valid fix clears place confirmation and advances
the callback generation. GPS return requires two new fixes; late responses cannot revive a
pre-loss street. Street retention changes no gate, cleaning, media or route-collection decision.

Private place traces retain separate raw and displayed labels, qualities, fix identity and
resolver timing. Coalesced `presentation` rows record actual overlay/full-screen/notification
labels and visibility with the exact rendered snapshot generation/fix reference. Reset rows
carry the new generation and no stale fix reference, even when no resolver result arrives. The approved diagnostics implementation keeps them automatically in bounded
app-private files, never logcat, Routebook payload or public source. No coordinates, names or
trace files belong in a public test fixture or screenshot. Collect evidence only into an
owner-only directory outside Git. Queue drops and write failures remain explicit; an absent
row does not prove that a lookup/display transition did not occur. See the implementation
acceptance matrix in [STREET_PANEL_ACCEPTANCE.md](STREET_PANEL_ACCEPTANCE.md).

[Geoapify Free](https://www.geoapify.com/pricing/) is an alternative with 3000 credits/day
(reverse geocoding uses one credit), but requires an account/key. It is not implemented or
required by this version. Public Nominatim is deliberately excluded because of its
[vehicle-tracking and periodic-request restrictions](https://operations.osmfoundation.org/policies/nominatim/).

## Verification and diagnosis

Focused emulator-only overlay checks use the existing SafetyChecks runner with
`-e tripOverlay seed`, then a force-stop and `-e tripOverlay restore`; `-e tripOverlay visible`
checks the restored choice after another force-stop. Keep GPS permission disabled. The checks
seed an invented nonzero trip total, exercise whole-card hold/horizontal drag/no-open, hide/restore and automation
suppression, verify process identity changes and safe card/control bounds, and write synthetic
`trip-overlay-moved-<phase>.png` captures to app external files. Resize the emulator between
phases to verify saved-position adaptation. These modes never dispatch actions or supply GPS.

The pure trip suite covers eleven minutes of stationary poor-accuracy observations, exact
30-second and excessive gaps, stale/mock/order rejection, awake/sleep clocks, restart and
100-hour formatting. Emulator instrumentation adds schema migration, atomic state/time/distance,
queued reset work, stale resolver results, injected storage failures and Reset confirmation/cancel.
The radio installer smoke check only opens/scrolls the passive screen, checks Start/Pauza, Reset and
time text, and returns. It never touches a session control or changes the user's totals/state.

Focused persistence regression (emulator serial only):

```sh
adb -s emulator-5554 shell am instrument -w -e tripSession true \
  com.pbuchman.duduhome.test/com.pbuchman.duduhome.SafetyChecks
```

Run `bash scripts/check-trip.sh`, `python3 scripts/test-radio-update.py`, the normal build/lint
and emulator suite. Installing osmium also enables the synthetic parser test. Instrumentation
covers offline lookup, uncertain settlements, Photon parsing/quota/backoff, lifecycle and durable
transitions without contacting the public endpoint. Gallery capture uses synthetic renderer
snapshots and is not proof of driving, calls, robot movement or sound.

The existing service supports read-only diagnostics:

```sh
adb -s DEVICE shell dumpsys activity service com.pbuchman.duduhome/.location.HomeMonitorService
```

It reports monitor registration, action-busy state, trip state, GPS freshness, map readiness and
storage health. RC3 reports map schema, locality/street quality and source independently;
`map=ready` alone does not prove schema-2 boundary coverage. It does not print positions,
names, phone numbers or credentials.

See [verification](VERIFICATION.md), [installation](OPERATIONS.md) and the
[complete UI gallery](UI_GALLERY.md). Real route accuracy, radio background presentation and
vendor sleep/wake require separate physical evidence. Deep sleep without GPS cannot be measured.

### Validated RC3 Poland artifact

The national schema-2 pack was rebuilt from the existing PBF snapshot with source timestamp
**2026-09-27T20:23:36Z**. This is a new index format over existing source data, not a latest
OSM download. It contains 3,469,164,544 bytes, 30,345,794 roads, 109,263 settlement points,
8,622,804 addresses and 26,879 areas, including 26,791 administrative-locality areas.
Input and output remain outside Git. These are historical artifact measurements; the current
installer uses one final map and a computed runtime hash, without a backup or staged second map.

Full manifest/integrity validation and staged/hash/atomic installation passed on a dedicated
Android 36 emulator. NationalMapChecks covered 64/64 sampled roads, matched 59 streets with
zero wrong streets (p95 43 ms, maximum 91 ms) and matched 32/32 sampled administrative-level-8
interiors with zero ambiguity. These samples do not guarantee national coverage or radio
performance. Actual city/village/drive acceptance remains pending; see VERIFICATION.md.

### Historical schema-1 Poland artifact

The September 28 build uses the PBF replication timestamp 2026-09-27T20:23:36Z. The builder reads that timestamp when available; the supplied date is only a fallback. The resulting database is 3,277,447,168 bytes. These measurements describe historical tooling; current updates keep only the one final map. See VERIFICATION.md for sample-based Android resolver measurements.
