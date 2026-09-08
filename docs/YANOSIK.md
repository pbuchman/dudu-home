# Yanosik movement hook — local implementation and remaining integration

## Implemented

The existing foreground location service supplies an independent MotionDetector: at least
10 seconds, speed >= 1 m/s, displacement >= 15 m, accuracy <= 15 m, age <= 3 seconds.
Missing speed, bad/mock positions, a gap > 3 seconds or stopping clear motion evidence.
An implausible jump resets evidence. No home geometry is needed for this hook. Home actions
still require their private geometry and enabled configuration. All monitoring respects
permissions and maintenance/import. No new tile, UI setting or periodic launch timer.

MotionHook waits while HomeActions or a setup/error/result screen blocks external navigation.
It only reevaluates on a valid GPS fix; a stop or outage cancels waiting. Home events have
priority on the same fix. The launch does not call, send a robot command or change a daily quota.

YanosikLauncher uses `getLaunchIntentForPackage` for `pl.neptis.yanosik`. The package was
observed in previous **physical radio** location-service captures, not guessed from its label.
The component is resolved at runtime instead of hardcoded; current launcher resolution and
foreground success still require the radio. The manifest queries only that package and SYU.
No Accessibility, shell launches, fake radio coordinates or QUERY_ALL_PACKAGES.

JourneySession persists reservation before resolving/starting. Missing app, thrown failure
or silent Android background denial consume the session attempt: no retry loop. A log saying
`launch requested` is not proof that Android brought the app forward. Tests must observe it.

## Important unfinished part: manufacturer wake

Cold system boot is identified by Android BOOT_COUNT. Process/service restart and package
replacement keep the same reservation. Unknown/rolled-back boot identity fails closed.
An internal `verifiedWake(cycleId)` seam is tested for durable deduplication but deliberately
has **no production caller yet**. The approved behavior needs an observed DUDU ignition/wake
cycle identifier. The radio was unavailable during read-only inspection in this session.

Do not treat HomeWakeActivity invocations, screen on/off, GPS gaps, traffic stops or elapsed
time as verified ignition. HomeWakeActivity currently only ensures monitoring; it does not
rearm Yanosik. Consequently the current APK supports once per cold boot, **not yet the full
once-per-ignition/wake contract**. It is not ready for full radio acceptance until this adapter
is implemented against an observed manufacturer signal. No broad exported reset endpoint.

## Verification and next exact step

- Pure Java MotionChecks: duration/displacement, bad accuracy/age/mock/missing speed, stop,
  gap, jump, deferred and cancelled execution.
- Android NavigationChecks: durable reservation/reconstruction, boot changes, duplicate
  verified wake IDs, missing target and launch exception with no retry; fake launch only.
- Existing UI tests assert setup/error/five-second success do not allow external launch.
- Read actual launcher component and observe two real ignition sequences/wake cycles through
  read-only radio diagnostics. Identify the trusted, deduplicatable signal; implement its adapter
  into JourneySession, then repeat tests before reporting full installation readiness.
- Future authorized radio tests: parked idle, one launch after moving, traffic stop with no
  repeat, manual Yanosik closure with no repeat, action priority, next ignition permits launch.

No new code is installed on the radio or pushed publicly during this local stage.
