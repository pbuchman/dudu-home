# Verification ledger

## Current implementation — 2026-09-08

Scope: local `0.2.0-local` / versionCode 3 on `codex/home-automation`.
This ledger separates executable evidence from pending physical acceptance.

| Check | Evidence / outcome |
|---|---|
| Build, lint, instrumentation compilation | `assembleDebug assembleDebugAndroidTest lintDebug` passed with JDK 17 / SDK 36; no external Android dependencies |
| Gate setup and cooldown | Emulator: validation, save without dial, menu, persistent setup/dial blocks, rollback and expiry |
| Gate UI | Emulator: missing SYU error, explicit close to menu, automatic error with previous menu preserved; not a successful Bluetooth call |
| Roborock signing | Fixed synthetic Hawk vector independently calculated in Python matches Java |
| Roborock transport | In-memory HTTPS connection: one empty POST, expected path/header, no redirects, timeout settings, bounded body, cleanup, no retry after IOException |
| Result interpretation | Boolean success, HTTP 401, known auth error, generic 403, redirect, 429/5xx and malformed JSON covered |
| Credentials | Reject unsafe host/port/path/header fields/noninteger ID; Keystore encrypted round-trip and rejection/replacement persistence |
| Daily quota | Same day/earlier day rejected, next day allowed, credential save does not reset; shared lease rejects concurrent work |
| Cleaning UI | Missing/rejected credentials form, save with no request, manual result to menu, success delay, automatic result closes Activity; result renderer tested without cloud |
| Import | Invalid geometry rejected before phone mutation; one-time encrypted import, staging deletion, repeat launch inert |
| Installer helper tests | Six Python tests: schema, optional fields, geometry, endpoint/credentials, private path, minimal bootstrap extraction |
| Real installer on emulator | Mismatched phone rejected; cert-checked APK/data backup and update; encrypted staging consumed; daily state survives update/process restart; synthetic values only |
| Detector | Synthetic departure/return/controls, alternate parking, restart deduplication, stale/poor/mock fixes passed |
| Eight private replays | Repeated against actual Java detector: 3 departures + checkpoints/exits, 2 returns, 3 controls with no events |
| Real private config | `configure-device.py --dry-run` with both private files passed; does not validate server authorization or current radio number |
| UI visual review | Landscape emulator menu inspected, both tiles/settings visible; only a sanitized menu screenshot is public |
| Privacy | Working tree/index/history scanned against private phone/GPS/terms and Roborock fields; inspect again before each commit/push |
| APK privacy/signing | Uncompressed APK entries checked against private values; none found. Local signing certificate matches the preserved baseline certificate; installed-radio comparison still pending |
| Radio availability | No physical radio in ADB; bounded connection to last recorded address unsuccessful. No device write or real cleaning command made |

The emulator transport tests use a test-only in-memory `HttpsURLConnection`, not a fake DUDU
service and not the real Roborock API. UI renderer tests are not a full cloud end-to-end check.
Timeout settings/IO failure are covered; vendor network behavior and actual TLS/cloud acceptance
still need radio evidence. The bootstrap extraction helper is tested locally; a fresh email
login through the newly published helper was not requested/performed because a private bundle exists.

Earlier local location-monitor experiments on Android 16 showed background dispatch and
BOOT_COMPLETED recovery with synthetic fixes. That is historical emulator evidence, not proof
of the current combined build on DUDU Android 13. Full calibration replay does not exercise services.

## Fresh-install safety regression — 2026-09-08

The review reproduced a fail-open bug in the old `pm path | grep` condition: an ADB query
failure could fall through to `install -r`. Fixed by checking command status separately,
validating the full package-list response (including retained data), requiring one explicit
target and removing replacement mode from the fresh installer.

`python3 scripts/test-fresh-installer.py` passed five test methods / thirteen scenarios:
ADB errors with/without output, empty/malformed output, stderr diagnostics, existing package,
missing/empty/ambiguous target, confirmed absence, failed build, failed install and a package
appearing after preflight. Tests execute the complete shell script using offline ADB/Gradle
fixtures. They verify call order, no install after a failed check/build, no `-r`, retry or launch,
and no success message after a failed install. Six private-tool tests and shell syntax checks
also passed again. This patch changes tooling/docs only, not the APK or radio acceptance status.

## Physical acceptance still required

- [ ] Radio identity and current phone checked; matching signing certificate; checksum-backed update without uninstall/data clearing.
- [ ] All phone/GPS/Roborock data installed and staging removed; correct grants/GPS; menu start and save trigger nothing.
- [ ] Manual gate on radio: own dial/outgoing/hangup/idle; return to menu; no duplicate or interruption of existing call.
- [ ] Manual Full Cleaning on radio: one routine request, accepted UI, proper routine observable in phone app; **no automatic stop**.
- [ ] Departure: early gate action, later outward checkpoint cleaning, expected automatic screen/hide behavior.
- [ ] Return: gate only, correct approach, adjacent parking accepted.
- [ ] Second same-day outward checkpoint: no second automatic cleaning; manual tile independent.
- [ ] Radio restart, both ignition sequences and vendor wake task: monitoring returns, quota persists, no startup action/replayed call.
- [ ] Sanitized record of physical results, then publication/final tag. Until then keep public baseline unchanged.

## Baseline evidence

Original physical DUDU7 gate tests on DUDUOS 3.7 build 260210 confirmed the single-phone Toolkit
path and idle → dial → outgoing → five seconds → hangup → idle, plus disconnected-phone refusal.
See [TECHNICAL_NOTES.md](TECHNICAL_NOTES.md). Publication/branding and the later extension do not
re-certify those results. Fallback Module, multi-device callback formats and firmware updates
remain explicitly unverified. Prior computer-side Roborock routine experiments do not meet
the requirement to test both actions after this app is installed on the radio.
