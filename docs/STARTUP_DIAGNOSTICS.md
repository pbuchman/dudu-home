# DUDU startup recovery and private diagnostics

## First-wake baseline repair - 0.5.5, hardware acceptance pending

Code 11 recovered monitoring after sleep but retained a consumed Yanosik attempt at the
first numeric counter. Cold boot had returned empty vendor properties and never persisted
wake_id. The next numeric increase rearmed successfully. This is distinct from the earlier
pre-onCreate task trimming. Neither finding proves the cause of every historical failure.

DuduCycle now returns AWAKE_COUNTER, ABSENT_COLD_BASELINE or UNAVAILABLE. The fixed shell
command checks every getprop exit status, frames the output, waits at most 500 ms for the
process and bounds each output stream to 512 bytes. It reads boot-completed, both sleep
flags and the counter twice. Numeric readings require completed boot, zero sleep flags
and equal counters. Only a complete pair of boot-completed=1 observations with all six
vendor fields empty establishes the cold baseline zero. Stderr, timeout, failure,
truncation, mixed/unstable fields or malformed values remain unavailable, never zero.
The analyzed SYU implementation increments the missing counter from default zero at sleep.
No hidden API, system-property write, new permission or alarm is used.

JourneySession keeps the existing boot/wake_id/consumed keys. Missing baseline plus verified
empty cold state writes zero without changing consumed. Missing baseline plus first numeric
value writes that value without rearming. Only a strictly increasing numeric value atomically
writes wake_id and consumed=false. Empty observations cannot overwrite an existing baseline.
Updates never reconstruct a missed baseline from logs or elapsed time. A failed commit
blocks that preferences object across all session instances for the process lifetime;
even its possibly modified in-memory values cannot grant an attempt. No automatic restart.

The monitor retains asynchronous single-flight reads and ignores callbacks after destruction.
The first completed read releases the initial cycle barrier even if unavailable, preserving
cold-boot behavior. Only REARMED cancels pending HOME and clears transient evidence/progress,
not persisted route flags, phone cooldown or cleaning quotas. Baseline observations are inert.

Diagnostics: VENDOR_BASELINE_ZERO, VENDOR_BASELINE_COUNTER, VENDOR_WAKE_REARM,
VENDOR_CYCLE_UNAVAILABLE and VENDOR_CYCLE_INVALID_BOOT are category transitions with numeric
cycle/ELIGIBLE fields only. Late successful baseline writes are recorded. Repeated unchanged
reads are suppressed. YANOSIK_SESSION_STATE_WRITE_FAILED is emitted once per failed store
and process. Existing two-file rotation remains unchanged; no raw property output is logged.

Acceptance: update with preserved data/signature, full reboot, confirm automatic monitoring,
fresh GPS and VENDOR_BASELINE_ZERO; drive to consume once; real sleep; first wake must record
REARM with cycle 1, then one navigation attempt after fresh qualified movement. Repeat for
cycle 2. A short interruption without a counter increase must not rearm. If Yanosik already
works, expect no launch/HOME. Inspect the service and actual warning overlay, not only a
launch-request log. Do not inject counters, clear quotas or manually start the app as proof.

Version 0.5.5 is not yet hardware accepted. Private failure evidence is archived outside Git.
The following sections document earlier versions and their separate results.

## Intermittent wake race - 2026-09-13

The owner clarified that failures also follow short and medium stops, not only overnight
parking. A read-only capture before opening/updating the app found an existing process but
no HomeMonitorService. The last two vendor wake launches created HomeWakeActivity tasks,
started/bound the process, then destroyed the tasks with `recent-task-trimmed` before any
app-side WAKE_ENTRY or MONITOR_CREATE. The last manual Yanosik launch came from the vendor
launcher, not Dudu Home. Raw event/system logs and both journals are archived privately with
verified checksums. This establishes a concrete failure path, not every reported occurrence.

Version 0.5.3 removes manifest-time `excludeFromRecents` from the isolated NoDisplay entry.
Android's [RecentTasks policy](https://github.com/aosp-mirror/platform_frameworks_base/blob/android13-release/services/core/java/com/android/server/wm/RecentTasks.java)
can trim excluded tasks when another startup task takes the most recent position. The entry
now calls `finishAndRemoveTask()` only after requesting monitoring. It still does not show
the menu, reserve actions or infer a new ignition from its invocation. No retry loop, alarm,
Application-level action dispatch or broad battery-policy change is added.

The backed-up code 10 update, import and actual notification-listener binding passed.
Fresh GPS then produced YANOSIK_ALREADY_RUNNING without another launch or HOME request.
Full reboot and repeated manufacturer sleep/wake acceptance are recorded separately below
or in VERIFICATION.md; this fix alone is not proof of universal wake reliability.

## Initial report - 2026-09-13, superseded by the investigation above

The owner reports neither home actions nor Yanosik started after overnight parking. The
radio was unavailable for read-only inspection, so the specific failure point is unknown.
Earlier short-wake/full-reboot successes do not establish overnight reliability. Preserve
the failed state and collect journal/system evidence before manual app start on next access.
The 0.5.2 presence/progress changes do not repair this separate startup issue.

## Confirmed incident

On 2026-09-12, read-only inspection before opening Dudu Home found no app process or location
subscription, and package state `stopped=true`. Location permissions were granted. Archived
Android events identify the installed SYU service as the caller that force-stopped the app at
sleep, stopped HomeMonitorService and removed its notification. A preceding manual launch
had started monitoring; no subsequent wake start appeared. DUDU's Tasks list was empty.

This explains the common failure of independent gate, cleaning and navigation hooks: no
monitor was running to produce their input. It does not independently prove every historical
missed gate event. A separate detector omission rejected an eastern approach to the junction;
0.4.1 includes it while retaining the junction-then-inbound-checkpoint sequence.

## Required device integration

The radio's foreground service does not survive a vendor force-stop. Restart it through the
device's supported automation UI, not through repeated phone calls, an alarm loop or a fake
launcher identity. The following controls were observed on DUDUOS 3.7; the end-to-end task
has been saved on the radio and restarted monitoring after one actual ignition cycle:

1. DUDU Settings > More Features > Advanced Settings > Shortcuts.
2. Add a shortcut for Dudu Home's `com.pbuchman.duduhome.startup.HomeWakeActivity`.
   This existing NoDisplay entry point only starts monitoring, never a menu or an action.
3. In Tasks, select Vehicle Ignition and Open the App, targeting that shortcut.
   Verify that the picker accepts the shortcut and persists the exact Activity.
4. Verify full boot separately; add a Launcher Start task for the same shortcut if needed.
   BOOT_COMPLETED alone is insufficient when the package is already force-stopped.
5. Check monitoring and the private journal after a real off/on cycle without opening the
   menu. Then check sustained motion, Yanosik's service/overlay and the previous radio screen.

Invoking the shortcut is not proof of ignition and does not reset any quota. The service
separately observes the real vendor sleep counter while awake. Missing vendor properties
retain cold-boot behavior. A first baseline does not grant another attempt on app update.
Repeated wake entry, ordinary screen toggles, GPS gaps and traffic stops do not rearm it.

## Journal

Version 0.5 adds [correlated UI states](PROGRESS_UI.md) to this same journal. DISPATCH still
precedes final acceptance. ACTION categories and IDs distinguish requested, accepted, started,
skipped and final outcomes. Expired screen delivery is explicit and never retried. A banner
does not prove the ignition task is configured or that Yanosik is actually running.

App-private no-backup storage contains `diagnostics.txt` and one rotated previous file,
approximately 128 KiB each. Each row contains wall time, monotonic time and category/counters.
No position, bearing, street, phone fragment, credentials, request headers or response bodies
are recorded. No export UI, cloud service or analytics is introduced.

- `BOOT_RECEIVED`, `WAKE_ENTRY`, `MONITOR_CREATE`, `MONITOR_START`: how monitoring began.
- `MONITOR_START_PERMISSIONS`, `MONITOR_START_MAINTENANCE`, `MONITOR_START_SYSTEM_BLOCKED`:
  why a start was refused. `MONITOR_DESTROY` is best effort; force-stop can skip lifecycle code.
- `GPS_SUBSCRIBED`, `GPS_GAP_RESUBSCRIBE`, `GPS_SUMMARY`: registration, gaps and aggregated
  delivery/poor-quality counts. A subscription alone is not evidence of fresh location.
- `VENDOR_CYCLE`, `VENDOR_WAKE_REARM`: observed cycle and actual deduplicated rearm.
- `EVENT`, `DISPATCH`, `SKIP`: detector output and blocked/missing-config/cooldown/daily-limit paths.
- `GATE_STATE`, `GATE_ERROR`, routine results and `YANOSIK_LAUNCH_REQUESTED`: executor evidence.
  A launch request is not proof of service/overlay presentation; cloud acceptance is not
  physical cleaning and the gate-call result is not proof of gate movement.

Read through an authorized debug connection, directing output to an owner-only directory
outside Git. Raw firmware logs can contain private data even when this journal does not.
Back up before updates; never clear app data to investigate a missing event.

## Acceptance status

Local build/lint and emulator checks pass, including persistent baseline/increasing/duplicate/
rolled-back cycle handling and diagnostic input filtering. Synthetic detector controls cover
eastern return, duplicate return, through-road passage and a reversal on the home road.
Eight private recordings were also replayed. Seven are unchanged; the former eastern-entry
control now correctly emits one return under the updated requirement. Two remaining negative
controls emit nothing. This does not replace checking the installed service on a real journey.
VersionCode 6 / 0.4.1-local was installed through the signature-checked, backed-up update
path. Private import completed, fresh GPS arrived and the app's journal confirmed a valid
vendor cycle read. No new real gate call or robot routine was invoked to test these changes.
In the subsequent combined session, versionCode 7 / 0.5.0-local was installed through the
same guarded procedure. Import and fresh GPS passed. A shortcut named `Dudu` was saved with
the exact HomeWakeActivity component, followed by Vehicle Ignition -> Open the App -> Dudu,
zero delay. Both persisted list entries were inspected and captured privately.
Following renewed debugging authorization, the journal proved automatic monitoring after full
reboot and one real ignition cycle, with fresh GPS and no manual app launch. The real movement
banner and automatic return call passed; the owner confirmed gate opening. Yanosik's service
started but its dashboard came forward. Version 0.5.1 replaces behind-task startup with the
owner-approved normal launch plus one desktop request. Repeat wake and that new presentation
require separate acceptance; one successful recovery is not proof of all power-cycle cases.
