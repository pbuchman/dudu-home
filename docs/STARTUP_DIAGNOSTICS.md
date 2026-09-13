# DUDU startup recovery and private diagnostics

## Latest report - 2026-09-13

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
