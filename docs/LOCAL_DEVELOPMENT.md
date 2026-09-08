# Handoff — local Full Cleaning implementation

Read this file first when resuming without the conversation. The accepted behavior is in
[FUNCTIONAL.md](FUNCTIONAL.md), implementation details in [ROBOROCK.md](ROBOROCK.md),
and exact deployment/recovery steps in [OPERATIONS.md](OPERATIONS.md).

## Where development stands

- Preserved baseline: `v0.1.0-baseline`, commit `bde6c0a`. Public `main` now carries the
  development implementation by the owner's explicit publication instruction.
- Local branch: `codex/home-automation`. The menu/GPS/cooldown predecessor was preserved in
  `e91b342` before adding Full Cleaning. See `git log` and [CHANGELOG](../CHANGELOG.md) for later milestones.
- `39eca9d`: app integration and its tests; `d813550`: private deployment/bootstrap tooling and tests.
- Current local APK: `0.2.0-local`, versionCode 3, same `pl.piotrbuchman.dudugate` ID and debug-signing scheme.
- Code includes two tiles, native Roborock, daily reservation, encrypted credentials, settings,
  one-time private import, safe update tooling, tests and handoff documentation.
- **Radio deployment and both manual tiles passed on 2026-09-08:** gate SUCCESS and Roborock
  ACCEPTED, both returning to menu. Full journey/ignition acceptance remains incomplete.
  Source publication is authorized after privacy/documentation review; a final release tag
  or full hardware-verification claim still requires the remaining evidence.

## Private state — do not copy into this repo

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

All eight captures were replayed against the same production Java `HomeDetector`:
three departure traces emit departure/checkpoint/journey-exit, two return traces emit return,
three negative controls emit no action events. Declared fix counts match the completed captures.
Two malformed lines in older data do not belong to those completed capture counts.
Geometry and thresholds remain unchanged by the Roborock extension.

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
