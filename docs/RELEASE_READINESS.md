# Release readiness - 2026-09-26

New development candidate: **1.0.0-rc3 / versionCode 16**, with a gate-area barrier before
generic media motion. Its local tests are recorded in VERIFICATION; it is not installed on
the radio. All physical rc2 evidence below remains historical and does not certify rc3.
In addition to the remaining checks below, verify that manoeuvring near home does not start
the media countdown before a successful gate call, and that ordinary driving away from home
still starts the media sequence. A skipped/failed call must not silently release the barrier.

Previous candidate: **1.0.0-rc2 / versionCode 15**. Application source was merged to main at
`694adb5af2d8d9a427e0ff75be2ce7bb2fe61878` through PR #3. PRs #1 and #2 were already merged.
The fresh merged-main build is byte-identical to the APK installed on DUDU7. Subsequent
release-preparation documentation does not change that application artifact.

**Final 1.0 acceptance is incomplete.** Prepare a source-only candidate draft; do not publish
a final 1.0 release until the remaining physical checks below are recorded. No APK, signing
key, real configuration, device address, raw log or actual destination capture is a release asset.

## Checks completed against the merged application

| Check | Evidence |
|---|---|
| Build and lint | Debug APK, instrumentation APK and lint passed in a fresh checkout |
| Merged-main CI | [Build and privacy passed](https://github.com/pbuchman/dudu-home/commit/694adb5af2d8d9a427e0ff75be2ce7bb2fe61878/checks) |
| Detector and scheduling | Stationary/quality/direction controls, priorities, expiry, presentation locks and generation checks passed |
| Recorded-route regression | Eight private recordings plus synthetic input: identical events and flags over 22,644 home and 20,000 motion samples |
| Private tooling | Four navigation, seven private-tool, five fresh-installer and one privacy-scanner test methods passed |
| Android emulator | Full safety, navigation, document import, media-priority, progress UI and first-run/no-dial checks passed |
| Update/import | Actual installer tested on an emulator: mismatched phone refused, signature/backup verified, encrypted import and quota preserved across restart |
| Privacy | Tree/history denylist passed; manually reviewed historical ordinary-word match. Candidate APK ZIP/DEX scans passed during rc2 verification; merged-main APK is identical |
| Physical full boot | Boot counter increased; wake entry and boot receiver restored monitoring without opening the menu. Fresh GPS and cold baseline zero followed |
| Manual gate on code 15 | One own dial, outgoing, five-second delay, hangup and success; menu restored. Physical gate movement was not independently observed |
| Manual Full Cleaning on code 15 | One UI request returned cloud ACCEPTED; menu restored and automatic daily quota unchanged. Physical robot movement was not independently observed |
| Maps | Three tile guidance checks passed in rc1; corrected address target and retained other slots passed on installed rc2 |

The radio was not given mock locations, injected wake counters, cleared quotas or a synthetic
ignition signal. No robot stop/pause/dock command was issued. The standalone Mop check remains
the historical hardware result in VERIFICATION; it was not repeated after the cleaning request.

## Physical checks still required for final 1.0

1. **Real sleep and wake twice:** after consuming the media opportunity on a real drive,
   switch ignition off and allow actual vendor sleep. On the first and second wakes, confirm
   automatic monitoring, increasing vendor cycle and exactly one fresh-motion opportunity.
   A short stop without a cycle increase must not rearm. A full reboot is a separate test.
2. **Music and foreground behavior:** verify audible Spotify output and working Yanosik
   warnings after motion. Check already-running Yanosik, manual pause, the open menu and a
   manually selected Maps route. Media must not override manual UI in the current cycle.
3. **Real departure, return and second departure:** observe the configured directional
   sequence and one action per event. Confirm physical gate/robot effects. The second
   same-day outward checkpoint must not repeat automatic cleaning, including after restart.
4. **Overlapping work and call protection:** observe gate/cleaning/media priorities on the
   device when real events overlap; verify number-save/restart cooldown and refusal to
   interfere with an existing call. Synthetic coverage is already green, but does not
   replace those hardware checks.

Debug access alone cannot create a real ignition cycle, drive the car, hear its speakers or
observe the gate/robot. These checks need the corresponding physical events and observations.
Capture diagnostics before manually opening the app after a wake. Keep all raw evidence in
the private archive and add only sanitized results to this ledger.

## Release contents

The candidate includes the gate/routine menu, local route automation, ordered Yanosik/Spotify
startup and three private file-configured Maps destinations, including optional address mode.
See [CHANGELOG](../CHANGELOG.md), [NAVIGATION](NAVIGATION.md) and [VERIFICATION](VERIFICATION.md).
The prepared release notes must retain the incomplete hardware-acceptance status. When all
remaining checks pass, make the final version change, rerun the affected gates and verify the
final installed artifact before publishing a source-only 1.0 tag.
