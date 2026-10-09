# Handoff - local Full Cleaning implementation

## Current candidate: 1.3.0-rc2 / code 22

The release candidate adds a wrapping 390 dp street card (24 sp locality, 32 sp road, three
lines and explicit overflow expansion) and current-attempt cancellation across detection,
queue and execution. Unsent user-cancelled attempts keep no durable history/quota;
sent commands retain safety reservations and cleanup. Media cancellation groups Yanosik
and Spotify, and qualified fresh baseline evidence is required to arm a new occurrence.
Version is 1.3.0-rc2/code 22. The owner authorized source-only PR, green Actions, merge and
prerelease publication. Radio installation and private configuration changes are separate.
Build/lint, synthetic detector/progress/trip and full emulator checks passed before this
version bump; current validation results belong in VERIFICATION.md. Final artifact and CI
checks must be recorded by the release coordinator. Do not infer hardware acceptance.
See WHERE_AM_I.md, PROGRESS_UI.md and AUTOMATION_SEQUENCE.md for the current contracts.

## Current integration baseline

Use the exact merged main revision for 1.3.0-rc2/code 22 after the cancellation PR merges.
Combined 1.3.0-rc1/code 21 is the preceding baseline.
Do not build older feature worktrees for radio updates. Android, Routebook backend/UI and
fixtures are together; follow INTEGRATED_RELEASE.md and ROUTEBOOK.md. The records below
are historical. Existing private radio configuration remains authoritative.


## 1.2 trip development

Build/lint both APKs, run check-trip.sh and test-radio-update.py in addition to every existing
check, then execute instrumentation on an emulator. `osmium==4.3.1` is a computer-only optional
builder dependency; install it to exercise the synthetic OSM parser. Prepare real regional
map files outside Git and keep their manifest. `capture-readme.py` creates the current full-app
gallery; review images before copying them into documentation. The installer automatically
opens and verifies the updated menu. A radio connection failure is a deployment blocker,
not an invitation to clear data or ask for unplanned device reconfiguration.


Historical main before the combined release: 1.2.0-rc1/code 19 (PR #7).
Last verified radio installation: 1.1.0/code 18, the owner-authorized stable release.
Three-place modal, exact private import and actual third-target Maps guidance/name passed. Follow NAVIGATION.md
for the private full-file workflow and compatible schema migration; never use actual places
in fixtures or docs. Current validation/install evidence is in VERIFICATION.md.


Read this file first when resuming without the conversation. The accepted behavior is in
[FUNCTIONAL.md](FUNCTIONAL.md), implementation details in [ROBOROCK.md](ROBOROCK.md),
and exact deployment/recovery steps in [OPERATIONS.md](OPERATIONS.md).

## Current release candidate

Previous development: `1.0.0-rc3` / code 16, merged through PR #5.
Adds a gate-area precondition before media movement detection/reservation, with success-only
release or fresh outside evidence. No fixed timer, route threshold or call-safety change.
See AUTOMATION_SEQUENCE and VERIFICATION. This code is included in the rc4 installation; its physical gate-area behavior remains unverified.

`1.0.0-rc2` / code 15 is a historical verified installation on DUDU7.
Three Maps tiles, private import, manual-media priority and per-destination address mode are
verified as recorded in VERIFICATION. The merged-main build matches the installed APK exactly.
Fresh build/lint, emulator, installer, recorded-route replay and privacy checks passed.
Full reboot/automatic monitoring, manual gate and Full Cleaning cloud acceptance also passed
on code 15. See [RELEASE_READINESS.md](RELEASE_READINESS.md) for the remaining physical checks.
Use the latest private navigation file, including the one destination's address-mode setting;
older rollback files must not replace it. Keep configuration and device data outside Git.

## Previous source and radio checkpoint

**Historical source and radio installation: 0.5.6-local / code 13 (September 24).**
Branch codex/spotify-priority, based on public main 04a767c. Ordered automation, separate Spotify
reservation and native local resume are implemented. See AUTOMATION_SEQUENCE. No HOME request
remains in YanosikLauncher. Backed-up update/import and full reboot passed on September 24.
Without manual app startup, monitoring recovered, cold baseline zero was persisted, and
movement started Yanosik followed by Spotify after about ten seconds. Spotify exposed a
local session and reported PLAYING. Warnings were visible; audible sound is not confirmed.
See VERIFICATION for evidence and outstanding real sleep/wake and action-contention checks.

**Historical implementation checkpoint for 0.5.5 (before its September 14 installation):**
The typed cycle reader distinguishes confirmed empty cold-start properties from unavailable
reads. An observed zero establishes the missing baseline without changing consumed state;
the first increasing numeric wake rearms once. Failed session writes block that store for
the rest of the process. No gate/cleaning/presence/timing changes. See STARTUP_DIAGNOSTICS
for the read protocol, conservative migration and acceptance sequence.
Radio access ended during implementation. Installation and new hardware acceptance are
pending. The next test is full boot with baseline zero, then real sleep and first wake with
fresh motion. Short ignition interruption alone cannot cover the defect.
Source branch: codex/first-wake-baseline; dbf806c preserves the preceding dirty work.
The entries below are historical checkpoints, not current source version declarations.

**Preceding source and installed: 0.5.4-local / code 11.** One-shot HOME now waits 10 seconds.
Desktop plus real Yanosik warning overlays passed. After real sleep, monitoring recovered;
the first observed vendor counter did not rearm the consumed navigation attempt. Counter 2
did rearm and subsequent movement launched Yanosik once. This first-baseline defect is open.
An artificial simultaneous HOME/wake probe deferred one Activity; monitoring was restored
explicitly afterward. Do not count that restoration as automatic wake acceptance.

**Preceding 0.5.3/code 10 evidence:** Before-update radio evidence captured
two wake entries destroyed by recent-task-trimmed before onCreate, leaving an empty process.
Manifest exclusion is removed; finishAndRemoveTask performs cleanup after monitor handoff.
Build/lint and full emulator checks passed. Backed-up update/import and fresh GPS passed;
the notification presence guard skipped an already-running Yanosik. See latest VERIFICATION
and STARTUP_DIAGNOSTICS for subsequent reboot/wake checks. Reports include shorter stops.

**Previous source: 0.5.2-local / code 9, not installed separately.** Notification-based Yanosik presence
guard and earlier return banner are implemented on top of the 0.5.1 work below.
Read YANOSIK.md/PROGRESS_UI.md for the grant, unknown-state behavior and return stages.
Owner reported no automation after overnight parking on 2026-09-13. No radio connection
was available to diagnose that occurrence. Do not call wake reliable or use these changes
as its fix. Hardware access must wait for the owner's explicit availability message.

**Previous source 0.5.1-local / versionCode 8:** one-shot Yanosik desktop return added to GPS progress,
developed on `codex/automation-progress` from verified `origin/main` (`911d18b`). See
[PROGRESS_UI.md](PROGRESS_UI.md) and the latest verification entry. Package/signing unchanged.
Version 0.5/code 7 was installed with verified backup, import and fresh GPS. The exact
DUDU ignition shortcut/task is saved; full reboot and one real wake restarted monitoring.
Real movement displayed the banner. An automatic return call passed and the owner confirmed
physical opening. Yanosik started its service but brought the dashboard forward. The owner
approved normal launch then desktop after five seconds. Version 0.5.1 is installed with
verified backup/import/APK hash; full reboot restarted monitoring and fresh GPS. The car
was stationary at capture; real motion plus automatic desktop return remains pending.
The notes below describe earlier versions and are superseded by this checkpoint.

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
   once the current owner-authorized 1.1 criteria in RELEASE_READINESS pass. Do not alter the original private repository, upload an APK or
   leak calibration/account data.

If unavailable, distinguish completed installation/manual calls/cloud acceptance from outstanding
journeys, automatic background presentation and vendor boot/wake. Do not equate a green emulator
suite, accepted cloud request, or the old calibration 8/8 counter with completion of those tests.
