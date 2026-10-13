# Dudu Home 1.3.0-rc3 / code 23

The previous departure detector could suppress an early gate event because it required a
five-second stop and movement toward a nearby configured gate. The old locality index
omitted administrative locality boundaries and could favour a nearby subordinate place.
These are identified code/data causes; current radio logs and its installed version are
unavailable, so they do not establish the exact cause of the reported physical failures.

RC3 recognizes departure after three continuous seconds of qualified GPS speed >=0.7 m/s
in any direction, including reversing. The first good fix must be within 45 m of parking;
movement stays within the 85 m departure area, accuracy <=15 m, age <=3 s and fix gap <=2 s.
Stops, gaps or bad fixes restart the interval. Initial parking evidence can arrive while
already moving and needs no preceding five-second stop, direction or displacement test.
After this cycle's valid parking baseline, recovery within the 85 m departure area can
restart the movement interval without returning inside 45 m. A first valid cycle fix outside
45 m or pre-send cancellation still requires its qualified return/parking baseline.

A new boot or confirmed increasing vendor wake permits a fresh parking opportunity. First
cycle observation is conservative; process restart, APK update, menu entry and GPS gaps
cannot reset a used opportunity. First good GPS outside parking closes the startup
opportunity. Genuine return plus five-second parking standstill and pre-send cancellation
rearming remain. This state is independent of media/manual Maps baselines. Cleaning and
return thresholds are unchanged. Three seconds recognizes an event: callback registration,
phone snapshot and fresh idle still precede dial. All one-call, own-call cleanup, outgoing/idle
success and persistent 60-second safety rules remain.

Map schema 2 adds Polish administrative level-8 locality polygons regardless of place tags,
with kind and provenance. Reliable locality containment beats a nearby hamlet; gmina,
district and neighbourhood labels are not primary substitutes. Locality and street quality
and stabilization are independent. Nearest city/town/village is explicitly approximate;
Photon cannot downgrade a certain offline locality. Schema 1 remains readable, but the fix
requires both this APK and a new schema-2 pack. The national pack reuses the source timestamp
2026-09-27T20:23:36Z; it is not a new source download.

Private diagnostics add bounded asynchronous categories, rejection reasons, dump state
and correlation for recognition, reservations and execution. `scripts/automation-diagnostics.py`
provides read-only private collection and local reports for the next radio session;
[exact commands](OPERATIONS.md). Unsent user-cancelled occurrences
retain no durable cancellation history. Existing layout and historical gallery remain.

## Validation and delivery status

The code-23 app/instrumentation build and lint passed. Detector/progress, trip/parser,
private diagnostic/installer/navigation/privacy tooling, Routebook and full emulator safety
checks passed, including first-launch no-dial, locality evidence and departure cycle state.
Synthetic backup/update/import/quota preservation and overlay visual checks passed.
The final incremental SafetyChecks run after failed-write latch hardening also passed.
The extended installer check passed exact consumed departure-cycle preservation across
backed-up update/import and process restart, alongside existing quota/navigation/signature checks.
Two local PostGIS cases were skipped; initial PR/branch CI ran PostGIS and browser checks successfully. Exact counts and limits
are in [VERIFICATION.md](VERIFICATION.md); previous RC2 results are separate historical evidence.

The national schema-2 pack completed with 26,879 areas, including 26,791 administrative
localities. Full manifest validation and staged/hash/atomic installation passed on Android 36
emulator. National lookup covered 64/64 roads, matched 59 streets with zero wrong streets,
and matched 32/32 sampled administrative-level-8 interiors without ambiguity. Read-only
diagnostic collection passed with 803 records/three attempts; its stopped synthetic state
did not provide a live monitor dump. Pack/hash/manifest remain private; APK signer matches
the known key. The final merged-source APK/hash manifest is prepared privately after merge.

[PR #10](https://github.com/pbuchman/dudu-home/pull/10) initial commit `0e43336` passed both
PR and branch CI. Final PR, merged-main and prerelease-tag gates are enforced separately;
the release page records final results. Main/tag completion is not asserted here.

Physical radio access via ADB is unavailable. No code-23 installation or current installed
version is confirmed. Required acceptance includes departure in both directions/reversing,
stop/bad-fix/gap controls, used-opportunity restart protection, genuine return/rearm, two
real vendor wake cycles, and city/village/street comparison with the schema-2 pack.
Gate-call success still does not prove physical gate movement; emulator/CI cannot certify
vendor IPC, GPS reception or wake reliability.

Publish only reviewed source under MIT after PR, main and tag checks pass. APK, map files,
hash manifests containing private paths, configuration, real names/coordinates/numbers,
keys and raw diagnostics stay outside Git and release assets. See [installation](OPERATIONS.md)
and [combined handoff](INTEGRATED_RELEASE.md).
