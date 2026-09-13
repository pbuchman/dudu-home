# Yanosik movement hook - local implementation and remaining integration

## Implemented

### First-wake session fix - 0.5.5, installation and radio test pending

The cycle adapter distinguishes a fully verified empty cold-start observation from a
failed/partial read. It records baseline zero without rearming a consumed attempt. The first
later numeric increase can then rearm normally. Legacy state without a baseline still treats
the first numeric observation as baseline only. Failed session persistence blocks navigation
eligibility for the process, not the independent home monitor. See STARTUP_DIAGNOSTICS.

On installed 0.5.4, automatic launch plus the 10-second desktop return and real hazard overlay
passed. Its first vendor wake retained an old consumed reservation; a subsequent cycle
worked. The 0.5.5 source fixes that specific gap, not every possible manufacturer startup fault.

### Presence guard - introduced in 0.5.2, installed with 0.5.3

The owner approved NotificationListenerService access. YanosikPresence reads a fresh complete
active-notification snapshot only while connected and granted access. It filters the exact
Yanosik package before examining FLAG_FOREGROUND_SERVICE. No extras/text, PendingIntent,
notification action, account data or other-package contents are read or stored. The service
is system-bound, permission-protected, and never starts monitoring or actions itself.

Three states: WORK_DETECTED, NO_WORK_SIGNAL, UNKNOWN. Existing foreground-service notification
means skip without launch/HOME. NO_WORK_SIGNAL permits the existing movement-triggered launch;
it does not prove every cached process is absent. UNKNOWN (unbound, revoked, null snapshot or
exception) skips with a two-second message. All outcomes consume the current boot/wake attempt.
Late grant/reconnect or notification removal cannot retry. Reads occur before action and use
current notifications, including those posted before Dudu Home started. No disk-cached presence.

Settings exposes grant status and the system notification-access screen, never an automatic
permission dialog on the road. Android's grant is broad, even though our processing is narrow.
No Accessibility, usage-history permission, shell process query or private service bypass.
The installed Yanosik foreground-service signal was validated on the radio: real motion
skipped an already-running service, with no launch/HOME. Service flags alone do not prove
GNSS/warning readiness. The separate intermittent monitor startup fault includes shorter stops;
0.5.3 addresses the captured pre-onCreate task trimming, not through this listener.

Reference: [Android notification listener lifecycle](https://developer.android.com/reference/android/service/notification/NotificationListenerService).

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
The component is resolved at runtime instead of hardcoded. Version 0.5.1 uses a normal launch,
then one ACTION_MAIN/CATEGORY_HOME request. Since 0.5.4 its grace is ten seconds: the radio's
cold dashboard appeared just after the earlier five-second HOME. The owner explicitly accepted
brief startup UI and returning to the desktop instead of restoring another app. No foreground
app tracking, repeated hiding or launch retry. Monitor destruction/new verified cycle cancels
the pending callback; process restart cannot replay it. Missing/throwing launch does not schedule
HOME. HOME failure is logged once without retry. The manifest queries only Yanosik and SYU.
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

The adapter is implemented, synthetic tests pass and the installed app successfully read
a valid cycle on the radio. The later 0.5.0 update and exact ignition shortcut/task are now
installed/saved. After renewed debugging authorization, the private journal confirmed automatic
monitor startup on full boot and again after one real ignition off/on, with fresh GPS and no
manual menu launch. Sustained movement displayed the real banner and started Yanosik's service.
However its dashboard opened in front, so this run failed background presentation acceptance.

## Verification and next exact step

The owner confirmed the automatic return call physically opened the gate. For 0.5.5, verify
the first true sleep/wake after full boot, normal launch, exactly one desktop return and
the continuing service/warning overlay without manually pressing HOME.
Ten seconds is a startup allowance, not a readiness callback;
a future/slower Yanosik could still open another Activity afterward. No guarantee of universal
compatibility. Current request outcomes do not claim a running service or available warnings.

- Pure Java MotionChecks: duration/displacement, bad accuracy/age/mock/missing speed, stop,
  gap, jump, deferred and cancelled execution.
- Android NavigationChecks: durable reservation/reconstruction, boot changes, duplicate
  verified wake IDs, missing target and launch exception with no retry; fake launch only.
  One delayed HOME, no early/duplicate HOME, cancellation and HOME exception are also tested.
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
That historical variant used no delayed HOME request. Version 0.5.1 replaces it after the
2026-09-12 foreground regression and the owner's explicit simplification. Full cold boot, application
update and manufacturer wake remain separate cases. Source publication was subsequently
authorized after the privacy/documentation audit; this is not full ignition/wake acceptance.

References: [Android behind-task launch](https://developer.android.com/reference/android/app/ActivityOptions#makeTaskLaunchBehind())
and [Yanosik background/overlay requirements](https://yanosik.pl/faq/pixel).
