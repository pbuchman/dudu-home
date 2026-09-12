# Handoff - local Full Cleaning implementation

Read this file first when resuming without the conversation. The accepted behavior is in
[FUNCTIONAL.md](FUNCTIONAL.md), implementation details in [ROBOROCK.md](ROBOROCK.md),
and exact deployment/recovery steps in [OPERATIONS.md](OPERATIONS.md).

## Where development stands

**Current 0.4.1 local work (2026-09-12):** proven SYU force-stop at sleep, missing restart
task, eastern return omission fixed, private bounded diagnostics and vendor cycle adapter
implemented. Local checks pass. VersionCode 6 is installed with a verified private import;
fresh GPS and app-side vendor cycle reads passed. DUDU shortcut/task configuration was not
saved before radio access ended. Finish that integration and physical wake verification
when the owner makes the radio available again. Do not repeat installation just to reconnect.
Read [STARTUP_DIAGNOSTICS.md](STARTUP_DIAGNOSTICS.md) and the latest verification entry.
The following 0.4/0.3 notes are historical, not current acceptance of wake recovery.

LOCAL 0.4 update: functional packages and new installation ID `com.pbuchman.duduhome`;
see PACKAGES.md and MIGRATION.md. Migration passed on an emulator with synthetic data.
Movement-based Yanosik launch and cold-boot deduplication are implemented locally. The verified
manufacturer wake adapter is NOT implemented; see YANOSIK.md before claiming readiness.
Update 2026-09-09: 0.4 is now installed on the radio after verified migration; old package
is disabled, not removed. Menu labels are “Pełne sprzątanie” and “Mopowanie”. Yanosik package
identity is corrected and Android behind-task launch passed a real full-reboot/movement check:
monitor autostart, Yanosik background service and warning overlay with the radio launcher
remaining visible. Owner confirmed actual movement as a passenger. See YANOSIK.md and
VERIFICATION.md for scope and the still-unimplemented manufacturer wake adapter. The owner
authorized publication after a privacy/documentation audit on 2026-09-09. The following describes version 0.3 history.

Newest local work is `0.3.0-local` / versionCode 4: manual-only Full Mop, optional private
`full_mop_routine_id`, three illustrated tiles and 5000 ms successes. The real Mop identifier
was retrieved through fresh email login and read-only discovery on the same robot; the updated
owner-only bundle is outside Git. No routine was executed during discovery. Version 4 is now
installed on the radio: one manual Full Mop request returned ACCEPTED and then the menu.
The subsequent presentation-only patch removes redundant subtitles; no robot stop/repeated Mop.
See [DESIGN.md](DESIGN.md) for generated assets/prompts and [ROADMAP.md](ROADMAP.md) for the
queued Yanosik hook; do not accidentally implement Mop as an automatic action.

- Preserved baseline: `v0.1.0-baseline`, commit `bde6c0a`. Public `main` now carries the
  development implementation by the owner's explicit publication instruction.
- Local branch: `codex/home-automation`. The menu/GPS/cooldown predecessor was preserved in
  `e91b342` before adding Full Cleaning. See `git log` and [CHANGELOG](../CHANGELOG.md) for later milestones.
- `39eca9d`: app integration and its tests; `d813550`: private deployment/bootstrap tooling and tests.
- Installed radio APK and current local APK: `0.3.0-local`, versionCode 4.
  Same `pl.piotrbuchman.dudugate` ID and debug-signing scheme.
- Code includes three tiles, native Roborock, daily reservation, encrypted credentials, settings,
  one-time private import, safe update tooling, tests and handoff documentation.
- **Radio deployment and both manual tiles passed on 2026-09-08:** gate SUCCESS and Roborock
  ACCEPTED, both returning to menu. Full journey/ignition acceptance remains incomplete.
  Source publication is authorized after privacy/documentation review; a final release tag
  or full hardware-verification claim still requires the remaining evidence.

## Private state - do not copy into this repo

The owner's sibling `dudu-home-private` directory contains `config.json`,
`roborock/routine-credentials.json`, the original archives and private replay tooling.
Read its `CURRENT_IMPLEMENTATION.md` for local paths and capture provenance. Inputs are 0600,
directory 0700. Config metadata includes a historical ADB address and historical gate number;
the installer requires a match to actual radio settings before using that number.
The minimal Roborock bundle was accepted by the real API from the installed radio app on
2026-09-08; this is not a guarantee of future validity. Renew only if necessary; never invoke the robot from
the computer to replace the requested radio acceptance test.

The Google Password Manager copy is **not complete** (prior browser security refusal).
Do not bypass that refusal. Local private storage is the current source of deployment data.

## Calibration evidence

All eight captures were replayed against the production Java `HomeDetector`. In 0.4.1,
three departure traces emit departure/checkpoint/journey-exit, three return traces emit
return, and two negative controls emit no events. The former eastern-entry negative is
now a required return case under the owner's updated scope; its recorded path was checked.
The other seven captures retain their earlier event sequence and timing.
Declared fix counts match the completed captures.
Two malformed lines in older data do not belong to those completed capture counts.
Geometry and thresholds were unchanged by the Roborock extension; 0.4.1 adds eastern arming.

The recordings helped choose thresholds; they are not an independent statistical validation
set. No promise of perfect intent detection on all parking manoeuvres. Do not invent coordinates
or repeat eight drives when only a particular hardware check is missing.

## Next concrete work

1. Check [VERIFICATION.md](VERIFICATION.md), `git status` and that the expected local APK is built.
2. The combined APK is already installed with all private sections, automation enabled and
   background GPS delivery verified. Do not repeat working manual actions unnecessarily.
3. Remaining: physical robot confirmation, one outbound/return and a second outbound checkpoint
   for the same-day block. On a safe stop check both ignition/wake paths, setup cooldown and
   busy-call protection. Do not reboot the radio while the owner is driving.
4. Record results privately, add only sanitized summaries to VERIFICATION. Fix actual failures
   with scoped changes; rerun relevant checks. Never add stop/pause/status polling as a testing convenience.
5. Keep public documentation explicit about pending checks; create a final release tag only
   once radio criteria pass. Do not alter the original private repository, upload an APK or
   leak calibration/account data.

If unavailable, distinguish completed installation/manual calls/cloud acceptance from outstanding
journeys, automatic background presentation and vendor boot/wake. Do not equate a green emulator
suite, accepted cloud request, or the old calibration 8/8 counter with completion of those tests.
