# Parking-tail correction: implementation and acceptance

The approved extension keeps detailed analysis on Home Dev. The radio adds a passive
power observer, an independent event queue and a bounded gate for its manual session
timer. It does not change GPS sampling, gate/robot/media actions or the v1 GPS protocol.
Fuel costs and physical-radio installation are outside this delivery.

## Meaning of the metric

`recorded_seconds` remains the union of observed GPS intervals, including stationary
observations, with the existing thirty-second continuity limit. `in_car_seconds`
subtracts only the union of qualifying parking-tail intervals intersected with that
coverage. Missing observations cannot contribute time. No field proves human occupancy.

Confirmed corrections require a physically validated radio interpretation profile.
ACC OFF alone and a dark screen do not establish radio OFF: intentional waiting with
the radio still operating counts. Unvalidated firmware signals remain diagnostic.
All shipped profile registries start without an approved hardware interpretation.

The observer records changes and fresh snapshots every thirty seconds. Evidence expires
after forty-five seconds and cannot cross an observer restart or connection loss.
OFF requires three seconds of stable evidence, effective from the first OFF observation.
Subtract only concurrent stationary GPS coverage: successive samples at most fifteen
seconds apart, both speeds at most 0.7 m/s, accuracies at most 25 m and displacement at
most 15 m. Movement contradicts OFF; missing speed does not confirm standstill.
The first snapshot cannot reconstruct time before observation. New ON ends exclusion.

## Historical estimator

Without trusted radio observations for a candidate, all of these conditions are required:

- An existing observed stop lasts at least 270 and less than 330 seconds and ends its
  source segment. The next measured sample is at least 600 seconds later. For an open
  archive tail, both the last measurement and its receipt must be at least 600 seconds old.
- At least forty samples; every speed is present and at most 0.7 m/s, and every accuracy
  is at most 25 m. At least ninety percent of speeds are at most 0.3 m/s.
- Median accuracy is at most 10 m, radius from the first sample at most 15 m and every
  internal sampling interval at most fifteen seconds.
- The preceding 120 seconds of the same segment contain at least two movement samples
  with speed at least 2 m/s and accuracy at most 15 m, belonging to accepted moving
  geometry edges. Fixed-coordinate speed spikes do not establish preceding driving.

Remove at most 300 seconds from the end, never more than the stop or actual coverage.
These corrections are estimated, with open archive tails additionally provisional.
Reliable ON vetoes the heuristic. Trusted telemetry uses its observed coverage without
filling unknown portions with an estimate. Late GPS or power uploads can undo a correction.
No user approval is needed for each place. There is no blanket five-minute deduction.

## Integration contract

GPS v1 stays compatible. Radio observations use version 2 at the existing authenticated
ingest endpoint, with durable idempotent acknowledgement and separate server storage.
The Android power outbox is separate from the original version-1 GPS database, so an older
APK can still open and drain that queue. Event failures cannot block GPS upload, and event
delivery must work with an empty GPS queue. Reuse device identity and provisioning.

Read APIs add corrected time, excluded time, confirmed/estimated/provisional breakdown,
algorithm version, calculation time and the next time-dependent refresh. All day, range,
table, period, journey-detail and comparison views must agree for the same source revision.
Use the complete raw archive, not sampled map geometry or a truncated analytics context.
Union before subtraction, clip to Europe/Warsaw calendar days, and preserve DST boundaries.
Power events alone invalidate affected results; provisional maturity refreshes without
requiring another GPS sample. Existing distance, journey segmentation and averages retain
their original meaning. Raw routes and observed stops remain available.

The radio manual session stays independent: Start/Pauza/Reset, with waiting counted while
ON. Power gating changes duration only. Reopening ON resumes accrual only if the user left
the session active. Reset, pause and observer generations protect against delayed OFF
confirmation changing a later session. Persist the timer and its correction boundary
atomically, never bridging unobserved restart time. No historical heuristic runs on Android.

## Validation and delivery

Test stationary ON with ACC OFF, screen-only OFF, real OFF with continued GPS, short OFF,
lost ON, epoch changes, clock jumps, offline backlog, duplicates, reordering, GPS noise,
missing speed, midnight/DST, late correction reversal, reset/pause races, expiry, migration
and old/new client/server rollback combinations. Verify database and pure-calculation parity,
cross-view consistency, browser desktop/mobile behavior, Android build/lint/emulator,
integration and public/private-data scans. Synthetic fixtures only enter source control.

The quality target is precision of at least 95 percent on the first one hundred consecutive
detections after freezing the rules, using independent reference observations outside the
calibration archive. A correct event requires true radio shutdown and a boundary error no
greater than thirty seconds. Report false deducted time and missed parking separately.
Missing reference labels cannot count as successes. An RC may expose labelled estimates
before this study, but must not advertise the target as achieved.

The private, offline study format and evaluator are documented in
[the parking acceptance guide](../routebook/tests/parking/README.md). Its synthetic tests
verify the reporting gate; they are not independent reference observations.

Backend, UI and Android are implemented in separate worktrees by gpt-6.1-sol workers at
xhigh, high and xhigh effort, respectively, after the backend contract is frozen. An
independent gpt-6.1-sol/high integration reviewer follows, with at most three workers active.
The coordinator owns integration, documentation and all release gates: PR checks, merged
main checks, tag checks, then a source-only release. APK and runtime evidence remain private.

The owner approved this Home Dev update without a new backup. Preserve raw points and
configuration; use additive metadata only. Retain old application image identifiers and
roll back code without restoring a database over new observations. Home Dev deployment does
not depend on the separate radio-update workstream. Physical mapping acceptance and the
independent one-hundred-event quality study remain explicit outstanding gates.
