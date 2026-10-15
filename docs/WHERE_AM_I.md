# Where am I

Introduced in **1.2.0-rc1 / code 19**. Polish UI: **Gdzie jestem**. The fourth action tile
opens a passive screen; opening it never starts a trip. This feature does not use Google
location data, Android Geocoder or Play Services. Existing manual Google Maps navigation
remains independent.

## User contract

Start creates a new trip at zero. Pause preserves the total and stops counting; Resume begins
a new GPS segment. End retains the last total; the next Start resets it. Backgrounding the
app does not pause. The full screen shows locality, street and kilometres (one decimal).
The card starts at the top right and shows locality at 24 sp above the primary street at 32 sp.
Its **Przesuń** handle moves the card; releasing a drag never opens the trip. The independent
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
Without a matching map/online result, show an unknown locality/street while continuing to
count qualified GPS movement. A point settlement without containment/address evidence is
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
opportunities, or launches Maps. A trip pause/end does not stop the shared monitor.

The separate TripActivity is passive: unlike the existing manual menu it does not register
as protected manual UI. Gate/cleaning can cover it and media startup can proceed. The
location overlay is hidden while automation presentation, protected UI or execution owns
the screen. A notification tap routes through MainActivity and waits for a safe menu state
before opening the trip. Merely changing a name never launches an Activity.

The overlay is at most 390 dp wide, bounded by the available window width minus 32 dp,
with 16 dp margins inside current system bars/cutout bounds. Its height wraps its content up
to the available safe height; a short viewport scrolls the address while move/hide controls
stay at the top. The saved relative position adapts to width, height, inset and name changes,
keeping the whole card reachable, and survives temporary automation suppression. It is non-focusable,
receives touches within its own window, and has no full-screen transparent input surface.
The automation banner receives cancellation touches within its own bounded window. System apps may suppress overlays;
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

Pause, GPS loss, service shutdown, verified wake and restart begin a fresh segment. Never join
positions across missing data. `trip-state.json` in no-backup storage contains only state,
generation and total meters, not a track. Atomic checkpoints run every 5 s and at transitions;
pause/end are reflected after durable save. Sudden power loss can lose the last checkpoint
interval. Storage failure stops counting and reports an error instead of claiming a saved total.

## Offline data

`scripts/build-place-index.py` runs on a computer with **osmium 4.3.1** and Python SQLite.
It reads a Geofabrik OSM extract, uses a disk-backed node index and writes:

- road segments with names, references and explicit locality tags;
- city/town/village and subordinate settlement points, plus settlement areas;
- named Polish `boundary=administrative`, `admin_level=8` locality polygons, regardless of
  whether their relations also carry a `place` tag, with explicit kind/provenance;
- locality-bearing address points/building centroids;
- grid lookup tables, schema, provenance, date, licensing and a SHA-256 manifest.

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
  /path/outside/git/poland.osm.pbf /path/outside/git/poland.sqlite \
  --region Poland --data-date YYYY-MM-DD
```

Use the actual source-data date. Download and keep the input outside Git. The output and
matching `.manifest.json` must be kept together. Existing output is never overwritten by the
builder. The installer validates checksums, schema, integrity, counts and available storage,
streams a temporary file, verifies its device checksum, then atomically renames it. A failed
upload leaves the old index intact. National maps are installed locally, not bundled in APKs
or published as source assets. Refresh by building a new dated pack and rerunning the installer.

The builder now writes **map schema 2**. RC3 can still read schema 1, but an old pack lacks
the new administrative-boundary evidence: fixing locality requires both the code-23 APK
and a schema-2 pack. Ordinary installation validates schema 2; legacy acceptance is reserved
for backed-up recovery, not a normal installation override. Keep the previous APK and pack
together for rollback. This map schema is independent of navigation JSON schema 2.

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

[Geoapify Free](https://www.geoapify.com/pricing/) is an alternative with 3000 credits/day
(reverse geocoding uses one credit), but requires an account/key. It is not implemented or
required by this version. Public Nominatim is deliberately excluded because of its
[vehicle-tracking and periodic-request restrictions](https://operations.osmfoundation.org/policies/nominatim/).

## Verification and diagnosis

Focused emulator-only overlay checks use the existing SafetyChecks runner with
`-e tripOverlay seed`, then a force-stop and `-e tripOverlay restore`; `-e tripOverlay visible`
checks the restored choice after another force-stop. Keep GPS permission disabled. The checks
seed an invented nonzero trip total, exercise drag/no-open, hide/restore and automation
suppression, verify process identity changes and safe card/control bounds, and write synthetic
`trip-overlay-moved-<phase>.png` captures to app external files. Resize the emulator between
phases to verify saved-position adaptation. These modes never dispatch actions or supply GPS.

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
Input, output, hash and validated manifest remain outside Git. Reserve space for old and
staged new maps plus the application backup.

Full manifest/integrity validation and staged/hash/atomic installation passed on a dedicated
Android 36 emulator. NationalMapChecks covered 64/64 sampled roads, matched 59 streets with
zero wrong streets (p95 43 ms, maximum 91 ms) and matched 32/32 sampled administrative-level-8
interiors with zero ambiguity. These samples do not guarantee national coverage or radio
performance. Actual city/village/drive acceptance remains pending; see VERIFICATION.md.

### Historical schema-1 Poland artifact

The September 28 build uses the PBF replication timestamp 2026-09-27T20:23:36Z. The builder reads that timestamp when available; the supplied date is only a fallback. The resulting database is 3,277,447,168 bytes. Reserve space for both old and staged map copies during updates, plus the private application backup. See VERIFICATION.md for sample-based Android resolver measurements.
