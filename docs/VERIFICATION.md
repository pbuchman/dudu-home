# Verification ledger

## Manual Mop and visual refresh — local versionCode 4

Separate from the earlier versionCode 3 radio session below. Full Mop has its own optional
private identifier and manual tile. Synthetic Android checks cover exact endpoint selection,
no Cleaning fallback, invalid/duplicate ID rejection, encrypted persistence/import, automatic
Mop refusal, unchanged daily quota and five-second result presentation. Seven private-tool
test methods cover installer/bootstrap validation. New illustrations are original generated
assets, not photographs or maps of the owner's home. Menu reviewed on a landscape emulator.

Build, lint, Android safety checks, detector checks and the real update/import installer on
the emulator passed. The installer preserved the synthetic Mop ID and daily quota. Public
menu/result screenshots use synthetic data; the result screenshot is not cloud acceptance.

The actual Full Mop identifier was retrieved using the computer bootstrap after email login,
alongside the unchanged Full Cleaning identifier on the same device. The full bundle was
saved outside Git with owner-only access and the previous bundle preserved.
Radio acceptance update: installed versionCode 4 as a same-signature update, with checksum-backed
previous APK/data and complete private phone/GPS/Roborock import. A single owner-authorized tap
on Full Mop produced exactly one new `MOP result ACCEPTED` log and returned to the menu.
No stop or second routine request was sent. This proves cloud acceptance, not physical robot movement.
The owner then requested removal of the header and tile subtitles. The presentation-only patch
was rebuilt/linted and installed with the same backup/import procedure; no repeated Mop test.
README's new image is a real radio capture. Journey/ignition checks below remain pending.
Yanosik remains queued.

## Current implementation — 2026-09-08

Scope: local `0.2.0-local` / versionCode 3 on `codex/home-automation`.
This ledger separates executable evidence from pending physical acceptance.

Publication audit repeated on 2026-09-08: tracked/working files and all reachable history
passed the private-value scan, including an additional check of commit metadata, phone suffix,
rounded coordinates and the device connection address. Both documentation images were visually
reviewed and contained no text/EXIF metadata chunks. Local documentation links resolved.
Build/lint, Android test compilation, six private-tool tests, five fresh-installer test methods
and synthetic detector checks passed again. No new emulator instrumentation or radio action was
performed during this source-publication audit; earlier runtime evidence remains below.

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
| APK privacy/signing | Uncompressed APK entries checked against private values; none found. Certificate matched the installed radio APK before the update |
| Radio deployment | DUDU7 Android 13 updated from versionCode 1 to 3 with matching certificate and checksum-backed APK/data archive; phone/GPS/Roborock imported |

The emulator transport tests use a test-only in-memory `HttpsURLConnection`, not a fake DUDU
service and not the real Roborock API. UI renderer tests are not a full cloud end-to-end check.
Timeout settings/IO failure are covered; real TLS/cloud acceptance is now recorded below,
while adverse vendor network behavior remains untested. The bootstrap extraction helper is tested locally; a fresh email
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

- [x] Radio identity and current phone checked; matching signing certificate; checksum-backed update without uninstall/data clearing.
- [x] All phone/GPS/Roborock data installed and staging removed; correct grants and fresh GPS deliveries, including after hiding UI; import/menu start caused no action.
- [x] Manual gate on radio: one own dial/outgoing/hangup/idle success; return to menu.
- [x] Manual Full Cleaning on radio: one tile invocation, native HTTPS result `ACCEPTED`, return to menu; **no stop command**. Manual action did not consume daily quota.
- [ ] Physical robot/routine confirmation in phone app; accepted response alone does not prove robot movement.
- [ ] Radio number-form save/restart cooldown and existing-call refusal in the combined build (local tests and historical executor evidence are separate).
- [ ] Departure: early gate action, later outward checkpoint cleaning, expected automatic screen/hide behavior.
- [ ] Return: gate only, correct approach, adjacent parking accepted.
- [ ] Second same-day outward checkpoint: no second automatic cleaning; manual tile independent.
- [ ] Radio restart, both ignition sequences and vendor wake task: monitoring returns, quota persists, no startup action/replayed call.
- [x] Sanitized record of completed physical results; owner authorized development-source publication after privacy/documentation audit.
- [ ] Final acceptance/release tag after the outstanding physical checks. Source availability does not imply full hardware verification.

## Radio session — 2026-09-08

Installed the unchanged `0.2.0-local` APK (SHA-256
`886e11dba7075290049cf9cca45fa4fb62d4016c976ad2a7ead147e586c17e7c`).
The owner explicitly requested remote installation and both manual tile tests during the trip,
without driver interaction. This was not a parked ignition/journey acceptance session.
No radio reboot, uninstall or data clearing was performed. Automation was disabled for the
two controlled manual tests, then explicitly enabled with the existing private geometry.

Gate log: STARTING → BINDING → CHECKING_PHONE → READY → DIAL_REQUESTED → OUTGOING →
WAITING_BEFORE_HANGUP → HANGING_UP → SUCCESS. No coordinator error; subsequent UI dump
confirmed the menu. Full Cleaning produced `ACCEPTED`, then the menu, without a second
invocation or stop. No claim of physical gate opening or robot movement follows from these logs.

After enabling automation, foreground location service and fine/background/notification/overlay
grants were verified. Android recorded 124 GPS deliveries to the app by the final check;
the service remained active after UI dismissal. No action event or crash appeared in that
background observation. This does not validate future route events or vendor wake behavior.
No app-specific wake target was found in the three standard Android settings namespaces;
vendor-managed wake configuration remains unverified, not proven absent.

Raw logs, UI XML, location diagnostics, backup checksums and session summary remain outside Git
in the owner's private archive; see the private handoff for their exact paths.

## Baseline evidence

Original physical DUDU7 gate tests on DUDUOS 3.7 build 260210 confirmed the single-phone Toolkit
path and idle → dial → outgoing → five seconds → hangup → idle, plus disconnected-phone refusal.
See [TECHNICAL_NOTES.md](TECHNICAL_NOTES.md). Publication/branding and the later extension do not
re-certify those results. Fallback Module, multi-device callback formats and firmware updates
remain explicitly unverified. Prior computer-side Roborock routine experiments do not meet
the requirement to test both actions after this app is installed on the radio.
