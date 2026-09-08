# Local automation — not yet hardware-verified

Public `main` and `v0.1.0-baseline` remain the source-only calling baseline. The local working
branch adds one large gate button, setup cooldown, event detection and background monitoring.
It has not been published or installed on the radio.

## Configuration and installation

Copy `config.example.json` outside the repository and fill the private values there. The public
example has no usable locations or number. Existing private archives must never be copied in.
The phone number must be checked against the currently configured radio before setting
`gate_number_verified_on_current_device` to true. Historical values are not verified values.

Build/install the local APK using `scripts/install-on-device.sh DEVICE_SERIAL`, then run:

```sh
python3 scripts/configure-device.py DEVICE_SERIAL /absolute/private/config.json --enable-automation
```

This uses authorized ADB/run-as on the debug build to write a private `no_backup/home-config.json`
atomically via stdin. It never puts the phone/coordinates in command arguments. It grants location,
background-location, notification and overlay access when automation is explicitly enabled.
It does not launch the app or call. Open the app once: a verified imported number is saved with a
60-second setup cooldown; otherwise the normal number form remains. Location configuration is
optional for manual calls. No location editor, cloud configuration or REST integration exists.

## Runtime contract

- `HomeDetector`: pure local-metre geometry, `Fix` input and `HomeEvent` output; no side effects.
- `HomeActions`: maps departure/return to a gate call. Checkpoint and journey-exit events have
  no action yet. Ephemeral internal request tokens prevent exported intents from initiating calls.
- `GateCallCoordinator`: unchanged calling protocol and busy-call protection; a single guarded
  executor for either origin. A blocked automatic request is discarded, not queued.
- `HomeMonitorService`: platform GPS foreground service with a persistent notification. Fresh,
  non-mock, accurate fixes only; a watchdog re-subscribes after an interruption. Persist consumed
  events before dispatch. Never infer departure merely from startup or restored state.
- Menu launch never dials. Saving a number never dials. Manual actions return to menu; automatic
  actions close unless the menu was already visible. Normal errors wait for retry/close.
- Setup and post-dial cooldowns are persistent and combined by the later expiry. Expiry is passive.

Boot and package replacement start monitoring when fully configured and permitted. DUDU ignition
task integration must target `.HomeWakeActivity`, not `.MainActivity`: the wake entry has no UI
and starts no call. Its availability/configuration in the vendor task UI is **not yet verified**.
If the vendor kills a process without a boot broadcast or configured wake task, a general Android
service alone cannot guarantee recovery. Do not claim otherwise.

Background UI uses the explicitly granted overlay permission to allow the normal Activity to
start; it does not imitate a phone dialer or use Accessibility/full-screen alarm notifications.
Permission behavior must be confirmed on the actual DUDU firmware.

## Current local evidence

- Baseline build/lint, number setup without dial and persistent cooldown checks passed.
- GitHub baseline build/privacy workflows passed.
- Pure detector synthetic tests cover stationary drift, nearby parking, sustained departure,
  return, passing straight, approach from the other road, invalid fixes and consumed-event restart.
- Private replay uses the **same Java detector**, not a separately reimplemented approximation.
  All eight captures match their expected gate-event roles: three departures, two returns,
  three negative controls. Directional outbound checkpoints and onward exits appear on departures.
- Every completed capture has its declared number of parsed location fixes. Two malformed lines
  in older recording data are preserved separately; none reduce the completed captures' counts.
- Emulator tests exercise setup cooldown and manual/automatic error navigation without a vendor
  Bluetooth service. They cannot establish that an actual call succeeds.
- A synthetic GPS departure on the emulator triggered the real monitor → internal request →
  Activity path while the launcher was visible. The expected missing-SYU error appeared; closing
  it left the monitor running. A full emulator reboot restarted the foreground service and GPS
  subscription through BOOT_COMPLETED without a call. These checks used Android 16, not DUDU13.

Replay threshold choices were checked against this small campaign, not a statistically independent
validation set. It cannot guarantee intention detection for every unrecorded parking manoeuvre.

## Still required on the radio

Verify the current phone configuration, compare installed signing certificate, install as an
update without clearing data, enable runtime permissions, check boot and both ignition sequences,
and verify the wake-task component. Perform one real departure and return with visible progress,
outgoing/hangup/idle and no duplicate calls. Confirm that a parking manoeuvre does not open the gate.
Do not publish this branch as validated automation before those checks pass.

## Local checks

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
bash scripts/check-detector.sh
./scripts/run-emulator-check.sh
python3 scripts/check-public-tree.py --working-tree --all-history --private-config /absolute/private/config.json
```

All emulator tests clear only this app's emulator data, never radio data. Private replay files and
its detailed audit stay outside this repository. No raw GPS or number is logged by the new app.
