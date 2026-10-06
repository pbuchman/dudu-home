# Routebook Android and server integration

Candidate `1.3.0-rc1`, code 21. Android, backend, map UI and deployment tools share this
repository. Contract and normative fixtures: `routebook/contract/`. See
[server setup](../routebook/README.md) and [combined handoff](INTEGRATED_RELEASE.md).

The existing HomeMonitorService GPS subscription offers copied primitive measurements to
Routebook in `finally` after home automation and manual trip processing. Disk work runs on
one serial worker; HTTPS runs on another. No new service, provider, runtime library, UI,
action lease, automation reservation, geocoder or Tailscale client was added. The manual
TripController, TripStore and TripDistance remain independent. Permission/maintenance guards
still apply; missing/disabled home geometry does not disable this collector.

The installation UUID lives in SQLite metadata before first point. `routebook.sqlite` is
in private no-backup storage with schema v1 and synchronous FULL transactions. Event UUID,
segment UUID and immutable payload are inserted together; pending survives process restart.
There is no destructive version fallback. Schema errors/full storage report degraded collection.
Pending and terminal rejected rows have no expiry. Accepted/duplicate rows are deleted only
in a local transaction after a fully validated commit ACK. Backend retains the acknowledged
archive. Counts, categories and installation UUID are available in service dump; positions,
tokens, account data and payloads are absent from diagnostics.

The worker queue holds at most 256 incoming callbacks; this is a transient pre-commit queue,
not the durable buffer. Overflow reports degraded collection and breaks continuity. SQLite
pending has no row-count cap. Defaults: <=1 stored fix per5 seconds, age<=5 seconds,
accuracy<=50m, no mock/nonfinite fixes, speed0..80m/s. Thresholds can be configured privately
within contract maxima. GPS gaps>30 seconds, provider loss, confirmed vendor wake and service
restart break segments. Invalid/regressing measurement or wall clock suspends acceptance
until recovery; repeated monotonic fixes are not duplicated. No observations are synthesized.

On each upload pass the newest pending tuple is sent as `latest`, then oldest batches of
up to200. Before every backfill, a point newer than the pass high-water tuple takes priority.
Only one HTTP request runs at a time. Failed/lost/invalid ACK retains identical IDs/payloads;
network/429/5xx uses2,4,8,...120 seconds plus0..20% jitter. Larger Retry-After is honored up to
15 minutes. Other HTTP statuses block upload for15 minutes and retain pending. Reprovisioning
requires a fresh safe service instance. No RAM timer is needed to retain delivery state.

HTTPS uses the exact privately configured `/v1/ingest` URL, system TLS/hostname validation,
no redirects, no cleartext, no credentials in URL, bounded request/response bodies and a
10-second disconnect deadline plus socket timeouts. JSON ACK parsing rejects duplicate keys,
malformed UTF-8, extra/missing fields, mismatched device/IDs/indexes, invalid timestamps,
statuses and codes before any queue mutation. Fixture ACKs are read from the shared fixture
file, not duplicated as another contract.

## Private provisioning

Do not install this candidate on the radio until backend/UI integration and a separate
hardware handoff. Verify the currently installed code and signing certificate before updating;
package remains `com.pbuchman.duduhome` and the existing local Android debug key is used.

With monitoring already authorized, read its `device_id` from the private service dump:

```sh
adb -s DEVICE shell dumpsys activity service com.pbuchman.duduhome/.location.HomeMonitorService
```

Bind a NEW dedicated Routebook token to that UUID on the backend. Clearing application data
changes identity and requires reprovisioning. Never reuse Health Connect/Roborock credentials.
The backend owner supplies the final public HTTPS ingest endpoint; it is not embedded in APK.

On the computer, create an owner-only directory outside every Git repository. Prepare the
file with the helper, which prompts invisibly for the token (or accepts one line over stdin):

```sh
python3 scripts/provision-routebook.py prepare \
  --output /PRIVATE/routebook/config.json --device-id INSTALLATION_UUID \
  --endpoint https://CONFIGURED_INGEST_HOST/v1/ingest
python3 scripts/provision-routebook.py stage \
  --file /PRIVATE/routebook/config.json --serial DEVICE
```

The file/directory must be0600/0700. There is no token CLI argument. The stage command sends
bytes over adb stdin into a separate app-private `no_backup/routebook-import.json` via temp
file and atomic rename. It never launches an Activity, stops/restarts the app, calls, cleans,
or changes Roborock configuration/maintenance. On the next safe NEW monitor service instance,
the disk worker validates identity/URL/thresholds, writes an AtomicFile in no-backup storage,
and removes staging. Bad staging blocks upload but collection continues. Arrange a normal
service restart only after existing actions have completed; do not force-stop an active call.
The final ingest hostname/token are still supplied by backend/runtime integration.

Private config fields: version1, device_id, endpoint, token, enabled:boolean, sample_ms5000,
max_age_ms5000, gap_ms30000, accuracy_m50. Exact keys are required. Set enabled:false to keep
local collection while disabling upload. No provisioning goes into the Roborock bundle.

## Reproduce verification

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
bash scripts/check-routebook.sh
bash scripts/check-detector.sh
bash scripts/check-trip.sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
./scripts/run-emulator-check.sh
./gradlew assembleDebugAndroidTest \
  -ProutebookRunner=com.pbuchman.duduhome.routebook.RoutebookInstrumentation
```

Install the two test artifacts only on an emulator. Grant its existing monitor permissions
(fine/background location, notification and overlay). Run the custom instrumentation component
with `-e phase checks`, `seed`, `resume`, `integration`, or `staged`. Between seed/resume,
force-stop the target package to prove process restart. The staged test expects only the
synthetic fixed UUID from fixtures; never run this runner against radio data. It refuses
physical hardware. Integration uses attached service objects with no GPS registration and a
busy synthetic action lease, testing actual callbacks and production collector into a separate
synthetic database. It covers OFF/PAUSED and the automation early return. No actual phone,
robot, geocoder or HTTP endpoint is contacted. Provision helper was exercised with a dummy
token and synthetic.invalid URL on that emulator.

Physical GNSS, vendor sleep/wake, OTA, power-loss durability, background HTTPS, real storage
exhaustion and current radio certificate acceptance remain unverified. Synthetic transport
checks do not replace Android->actual backend->UI integration or a real TLS ingest check.
