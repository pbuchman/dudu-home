# Handoff — local Full Cleaning implementation

Read this file first when resuming without the conversation. The accepted behavior is in
[FUNCTIONAL.md](FUNCTIONAL.md), implementation details in [ROBOROCK.md](ROBOROCK.md),
and exact deployment/recovery steps in [OPERATIONS.md](OPERATIONS.md).

## Where development stands

- Public baseline: `main`, `v0.1.0-baseline`, commit `bde6c0a`.
- Local branch: `codex/home-automation`. The menu/GPS/cooldown predecessor was preserved in
  `e91b342` before adding Full Cleaning. See `git log` and [CHANGELOG](../CHANGELOG.md) for later milestones.
- `39eca9d`: app integration and its tests; `d813550`: private deployment/bootstrap tooling and tests.
- Current local APK: `0.2.0-local`, versionCode 3, same `pl.piotrbuchman.dudugate` ID and debug-signing scheme.
- Code includes two tiles, native Roborock, daily reservation, encrypted credentials, settings,
  one-time private import, safe update tooling, tests and handoff documentation.
- **No physical radio deployment or full combined acceptance has been completed. No push of
  this extension or final release tag is authorized as “verified” before that evidence exists.**

## Private state — do not copy into this repo

The owner's sibling `dudu-home-private` directory contains `config.json`,
`roborock/routine-credentials.json`, the original archives and private replay tooling.
Read its `CURRENT_IMPLEMENTATION.md` for local paths and capture provenance. Inputs are 0600,
directory 0700. Config metadata includes a historical ADB address and historical gate number;
the installer requires a match to actual radio settings before using that number.
The minimal Roborock bundle exists and passes syntax validation; current server acceptance
is not proven by that check. Renew only if actually necessary; never invoke the robot from
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
2. When radio and parked driver are available, follow the full private backup/update/import runbook.
3. Verify gate and Full Cleaning from the radio menu; then one outbound/return and a second
   outbound checkpoint for the same-day block. Check both ignition/wake paths and busy-call protection.
4. Record results privately, add only sanitized summaries to VERIFICATION. Fix actual failures
   with scoped changes; rerun relevant checks. Never add stop/pause/status polling as a testing convenience.
5. Publish the verified commits and tag only once radio criteria pass. Do not alter the original
   private repository, upload an APK or leak calibration/account data.

If unavailable, state precisely that installation, Bluetooth action, cloud routine from radio,
background presentation and vendor boot/wake remain outstanding. Do not equate a green emulator
suite, accepted cloud request, or the old calibration 8/8 counter with completion of those tests.
