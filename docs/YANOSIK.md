# Yanosik movement hook - local implementation and remaining integration

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

YanosikLauncher uses `getLaunchIntentForPackage` for `pl.neptis.yanosik.mobi.android`.
The complete package and launcher were verified on the physical radio on 2026-09-09;
the earlier shortened package name was incorrect and could not resolve the installed app.
The component is resolved at runtime instead of hardcoded. Resolution and cold-boot background
startup passed on the radio as recorded below. The manifest queries only that package and SYU.
No Accessibility, shell launches, fake radio coordinates or QUERY_ALL_PACKAGES.

JourneySession persists reservation before resolving/starting. Missing app, thrown failure
or silent Android background denial consume the session attempt: no retry loop. A log saying
`launch requested` is not proof that Android brought the app forward. Tests must observe it.

## Manufacturer wake recovery - 0.4.1

Cold system boot is identified by Android BOOT_COUNT. Process/service restart and package
replacement keep the same reservation. Unknown/rolled-back boot identity fails closed.
Radio evidence on 2026-09-12 proved SYU force-stops the app at sleep, leaving `stopped=true`.
An ordinary sticky service or BOOT_COMPLETED receiver cannot by itself repair this wake path.
DUDU Tasks exposes Vehicle Ignition and Shortcuts exposes an explicit Activity selection.
The task needs to start HomeWakeActivity, not the menu. See [startup recovery](STARTUP_DIAGNOSTICS.md).

Do not treat HomeWakeActivity invocations, screen on/off, GPS gaps, traffic stops or elapsed
time as verified ignition. HomeWakeActivity currently only ensures monitoring; it does not
rearm Yanosik directly. DuduCycle reads the manufacturer's `sys.sleeptimes` counter off the
main thread, bracketed by awake-state checks. Analysis of the installed SYU APK identified
the increment at real sleep entry; a real car off/on sequence increased it without changing
BOOT_COUNT. First observation only establishes a baseline and cannot reset a consumed boot.
An increasing counter within the same boot rearms one launch and clears old motion evidence.
Missing/malformed/sleeping state does not rearm. No property writes, root, hidden APIs or
exported reset endpoint. The read-only command has a bounded timeout and fixed arguments.

The adapter is implemented, synthetic tests pass and the installed 0.4.1 app successfully
read a valid cycle on the radio. Actual task configuration and next-wake background launch
remain hardware acceptance requirements. Access ended before the shortcut/task was saved.

## Verification and next exact step

- Pure Java MotionChecks: duration/displacement, bad accuracy/age/mock/missing speed, stop,
  gap, jump, deferred and cancelled execution.
- Android NavigationChecks: durable reservation/reconstruction, boot changes, duplicate
  verified wake IDs, missing target and launch exception with no retry; fake launch only.
- Existing UI tests assert setup/error/five-second success do not allow external launch.
- Configure and verify the actual DUDU ignition shortcut and two real wake sequences before
  reporting full wake readiness. App-side cycle access alone has already passed.
- Future authorized radio tests: parked idle, one launch after moving, traffic stop with no
  repeat, manual Yanosik closure with no repeat, action priority, next ignition permits launch.

## Radio investigation - 2026-09-09

The corrected package was installed with a private backup/import. After a full reboot,
BOOT_COMPLETED started HomeMonitorService without manually opening Dudu Home, and the
motion hook logged one launch request. Yanosik actually appeared and displayed a nearby
roadwork warning. This normal launcher path left its full map in front: it did not satisfy
the requested background-only presentation.

After a manual HOME navigation, Yanosik's CommonService remained foreground and its overlay
showed a warning above the radio launcher. Overlay permission was already allowed. This
confirms the installed Yanosik can work in the background, not yet that our automatic launch
preserves the previous screen.

A subsequent variant requests Android ActivityOptions.makeTaskLaunchBehind with
FLAG_ACTIVITY_NEW_DOCUMENT. On the next full reboot the monitor started automatically,
the movement hook requested Yanosik, CommonService ran foreground, and a private screenshot
showed the radio launcher still visible with Yanosik's small warning overlay. No manual HOME
or app launch was sent after that reboot. The owner confirmed the vehicle actually moved
and that he was a passenger. The production detector requires at least ten seconds of
qualified motion; duration/quality/reset boundaries also passed the synthetic motion checks.
The exact wall-clock delay from the vehicle's first movement was not independently timed.

The installed APK hash matched the current local build. Build/lint, emulator safety/navigation
checks, motion checks and private installer tests passed. The session reservation remained
consumed after the observed stop. This is physical cold-boot/background presentation evidence,
not a manufacturer ignition/wake test or a guarantee against future Yanosik changes.
No accessibility, delayed HOME press or fabricated GPS is used. Full cold boot, application
update and manufacturer wake remain separate cases. Source publication was subsequently
authorized after the privacy/documentation audit; this is not full ignition/wake acceptance.

References: [Android behind-task launch](https://developer.android.com/reference/android/app/ActivityOptions#makeTaskLaunchBehind())
and [Yanosik background/overlay requirements](https://yanosik.pl/faq/pixel).
