# Verification ledger

## 0.5.6 implementation and current installation status - 2026-09-24

Final local acceptance passed: assembleDebug, assembleDebugAndroidTest, lintDebug, detector
and coordinator checks, progress differential checks (20,000 home and 20,000 movement samples,
zero private replay files in this run), seven private-tool tests, five fresh-installer tests,
and full emulator instrumentation plus the first-run form/no-dial check. A new integration
fixture initially left an orphan WAITING snapshot by clearing its queue directly; replacing
that shortcut with runtime cleanup restored the downstream banner-expiry check. The complete
emulator run was repeated successfully. CI builds are separate from this actual local run.

Correction to the historical entry below: installation of 0.5.5/code 12 was confirmed on
September 14. Full first-wake acceptance was not completed. Version 0.5.6/code 13 has not
been installed on the radio; the read-only connection check finds no authorized physical radio.

Local instrumentation exercises fake media transport and timers, including existing playback,
single resume, buffering, missing/late/remote/ambiguous sessions, permission loss, timeouts,
manual pause and late callbacks. No real music, gate call or robot action is performed.
Scheduling tests cover four-task priority, gate interruption of the Yanosik allowance, queue
expiry, day rollover, UI exclusion and generations. The monitor integration checks verify
cleaning quota reservation while the shared executor is occupied, duplicate suppression and
no queue replay or quota restoration in a replacement runtime.

Remaining physical checks: cold Spotify session availability, audible radio playback, Yanosik
warnings behind Spotify, gate/cleaning contention, manual-menu protection, real first and
subsequent sleep/wake, and a short stop without duplicate startup. Local checks and CI cannot
certify any of these. Existing screenshots remain unchanged and are not new hardware evidence.

## 0.5.5 local first-wake regression - 2026-09-13

Build and Android test APK passed. Emulator instrumentation passed parser framing, invalid
and oversized outputs, failed/timed-out/interrupted synthetic processes, cold-zero-to-first-
wake rearming, duplicate/rollback/migration cases, and in-memory edits followed by commit
failure across session instances. Isolated monitor callbacks passed initial unavailable
fallback, late zero preserving movement, verified wake cancelling pending HOME and clearing
evidence without changing route flags, plus stopped-instance rejection without modifying
the new session, progress or pending HOME. Existing safety/UI/presence checks also passed.
No real executor or GPS injection was used in these tests.

Detector and progress checks passed on 20,000 home and 20,000 motion synthetic samples;
no private recordings were replayed in this run. Seven private-tool and five fresh-installer
tests passed. Existing public UI images were not replaced with private radio screenshots.
The pre-change work is preserved separately in dbf806c and an owner-only snapshot.

At that September 13 checkpoint radio became unreachable, including a reconnect attempt.
The later September 14 installation of code 12 supersedes that installation status, not the
outstanding full first-wake test. Required hardware sequence is documented in STARTUP_DIAGNOSTICS
and OPERATIONS. Local tests and CI do not establish manufacturer wake acceptance.

## Code 11 real reboot and sleep observations - 2026-09-13

The signature-preserving update/import to 0.5.4 passed, including installed APK hash and
private configuration comparison. After full reboot, monitoring and fresh GPS recovered
without opening the menu. Sustained motion launched Yanosik once, then requested HOME after
10 seconds. Private screenshots verified the desktop with actual Yanosik hazard overlays.
Build/lint and full emulator checks passed for code 11.

A simultaneous shell HOME/wake stress probe passed once and deferred its second wake
Activity. This was not the original recent-task-trimmed failure and is not a passed wake
test. Monitoring was explicitly restored; that restoration is not automatic acceptance.

Subsequent real vendor sleep terminated both apps. Wake restored monitoring and fresh GPS,
but the first numeric cycle only established a baseline while the previous navigation
attempt stayed consumed. Cold boot had left vendor properties empty and no saved wake_id.
The second numeric cycle rearmed correctly. A later short ignition interruption did not
advance the counter or interrupt GPS; subsequent movement launched Yanosik once and actual
desktop warnings were verified again. First-wake baseline repair remains required.
Raw captures and screenshots remain in owner-only archives outside Git.

## Live intermittent-start diagnosis and code 10 update - 2026-09-13

Before any app launch/update, twelve private evidence files were captured and SHA-256
verified. Code 8 had a process but no location service. Two recent vendor launches of
HomeWakeActivity ended in recent-task-trimmed before onCreate/WAKE_ENTRY. The journal stopped
at an earlier vendor force-stop; the saved cycle was behind the actual counter. The last
Yanosik launch in that failed journey was attributed to the vendor launcher, matching the
owner's manual-start report. Short/medium stops are affected too, not just overnight parking.

The code 10 update preserved signature/data and imported the complete private bundle.
Installed APK hash, configured phone, geometry and enabled automation matched local inputs.
The notification listener was system-bound. On real motion, an already-running Yanosik was
detected and skipped without launch/HOME. Fresh GPS deliveries continued.

After one full radio reboot, the vendor wake entry reached onCreate, started monitoring and
delivered fresh GPS before BOOT_COMPLETED, without manual app startup. About eleven seconds
of motion produced one Yanosik launch; its foreground service ran. The 5 s HOME request was
delivered, but Yanosik opened its dashboard roughly 0.9 s later and subsequently its map.
Background presentation therefore FAILED this run. Code 11 extends the same one-shot grace
to 10 s; it does not add repeated hiding. Acceptance of that change is separate.

Local build/lint, detector/progress checks and the full emulator suite passed for code 10,
including wake manifest flags, isolated task cleanup and no entry-driven journey reset.
Actual short/medium ignition cycles still require verification, not simulated property writes.

## 0.5.2 local checks - 2026-09-13, not installed

Build, Android test APK and lint passed. The full emulator safety suite passed, including
system notification-listener grant, bind, revoke and reconnect. A fresh snapshot without
access returns unknown; an empty connected snapshot allows the existing launch path.
Synthetic notification fixtures distinguish the exact Yanosik foreground-service flag from
ordinary/ongoing notices and other packages. Existing-service and unknown-status paths do
not launch an Activity or request HOME and do not retry after a later status change.
These checks do not establish the notification behavior of the installed Yanosik on DUDU7.

Detector tests passed, including earlier return presentation, one run through the junction,
stationary frozen fill, cancellation when continuing elsewhere and fresh evidence after GPS
loss. Equivalence checks retained events, times and flags for 22,644 home samples (including
all eight private recordings) and 20,000 motion samples. Seven private-tool tests and five
fresh-installer tests passed. No live gate call or cloud routine was performed.

The two new public return screenshots were captured on the synthetic landscape emulator
and visually reviewed. The early and inbound messages, matching real supplied fill and
overlay layout passed instrumentation checks. No radio connection, installation or ignition
test was performed for this version. The overnight startup failure reported on this date
remains unresolved; these presence/presentation changes are not a claimed wake repair.

## 0.5.1 update and local checks - 2026-09-12

Build/lint and the emulator safety suite passed, including delayed HOME ordering, single
delivery, missing/denied launch, cancellation and HOME failure without retry. Pure detector,
motion and 40,000-sample progress equivalence checks passed. No GPS thresholds changed.
The guarded installer backed up the current APK/data, compared the radio phone and signature,
then updated to code 8 without clearing data. One-time import completed; installed APK hash,
private geometry, enabled automation and phone matched the local sources. GPS subscription
and vendor cycle read resumed. A full radio reboot then automatically restarted monitoring
and fresh GPS without opening the menu. At the captured stationary state, no navigation
attempt had fired. The real-motion desktop-return result is not yet claimed here.
Backup manifest hashes and all eight regular tar payloads were independently verified.
All eight private recordings retained identical events, times and flags on replay.
No public APK or private radio screenshots.

## Combined real journey results - 2026-09-12 evening

After debugging authorization, the journal confirmed NoDisplay wake entry and monitoring
after full reboot, then a second wake entry after a real ignition off/on. Fresh GPS arrived
without manually opening Dudu Home. One sustained-motion candidate confirmed after about
eleven seconds, displayed the real banner and requested Yanosik once. Its foreground service
ran, but DashboardPackActivity and subsequently the map came forward. This fails the desired
background presentation despite an earlier successful behind-task test.

The return sequence then produced one accepted/started gate attempt, own dial, outgoing,
five-second delay, hangup and idle success. The owner independently confirmed physical gate
opening. This is one successful return, not acceptance of every route or negative control.
The owner approved a simpler follow-up: normal Yanosik launch, then one desktop request after
five seconds. Source 0.5.1 implements it; installation/hardware acceptance must be recorded
separately. Raw journal, system logs and location-bearing screenshots remain private.

Still pending: repeated wake, outbound/cleaning and daily deduplication, plus the new Yanosik
desktop/service/overlay sequence without manually pressing HOME. Do not reset real quotas
or fake motion to turn these into apparent passes.

## Combined 0.5 radio installation and ignition setup - 2026-09-12 evening

Before manually opening the app, the radio had 0.4.1/code 6 and an already-running monitor.
The journal contained an earlier successful gate call and navigation launch request, but
Yanosik's full map was foreground. The observed state does not establish how those apps were
opened or prove automatic background acceptance. Raw evidence was archived privately first.

Rebuilt/linted 0.5/code 7 and repeated both private source/history denylist checks. The guarded
installer compared the configured phone and signing certificate, archived the previous APK
and app data, and installed without uninstalling or clearing data. Backup manifest hashes
and all eight regular tar payloads were independently verified. Import completed; maintenance
and pending staging were absent, encrypted routine configuration remained present, and fresh
good GPS deliveries continued. No test call or cloud routine was deliberately dispatched.

Saved a DUDU shortcut named `Dudu`, explicitly targeting HomeWakeActivity, then a zero-delay
Vehicle Ignition -> Open the App task targeting that shortcut. Persisted list entries were
visually verified and captured privately. The six-character draft name was not accepted by
the device; the shorter name worked. This is configuration evidence, not ignition execution.

A full radio reboot was requested after returning to the launcher. Before acceptance capture,
no manual app launch or HOME navigation was issued afterward. Renewed debugging authorization
initially blocked inspection; the subsequent results are recorded above.

## Progress UI 0.5.0-local - local checks, 2026-09-12

Built from verified origin/main `911d18b` in a separate worktree/branch. Application ID and
debug-signing scheme unchanged, versionCode 7. The last verified installed radio version
remains 0.4.1/code 6. No radio was attached for this implementation; no installation, real
call, cloud routine, ignition or road-test acceptance is claimed here.

Pure checks compare every sample's events and persistent flags to the pre-UI Java source:
20,000 synthetic home samples, 20,000 motion samples and 2,644 samples across all eight
private replays. No event, timing or flag divergence. The reference remains three departures,
three returns including east, and two genuine negatives. Existing detector/motion checks
retain through-road and home-road reversal controls, alternate parking and action counts.

New pure tests cover detector-derived fill, no clock-driven increase, freshness expiry,
cancellation, generations, priorities, consumed evidence, duplicate/backwards outcomes,
terminal immutability, empty fresh-process state and bounded attempt history.

Emulator instrumentation passes existing gate/Roborock/navigation checks plus one inline host,
overlay handoff, no focus/touch/screen-wake flags, capped opacity, unchanged action eligibility,
observer-failure isolation, cancellation, stale generation rejection and timed disappearance.
A context that deliberately does not deliver the Activity verifies the real five-second
token expiry, correlated refusal, rejection of late delivery and no dial reservation.
A late cycle callback on a stopped monitor leaves state unchanged. Read-only session checks
do not grant attempts. The existing two-file diagnostic rotation and input filter also pass.

Screenshots use synthetic observations on a landscape emulator; the execution screenshot is
a renderer preview without Bluetooth or HTTP. Android Settings can suppress overlays even
while the window remains attached, so the public overlay capture uses the normal launcher.
Pixel inspection is required in addition to attachment/flag assertions.

Build/lint, pure checks, seven private-tool and five fresh-installer test methods passed.
Private-value scans of working files and reachable history passed, supplemented with number
suffixes, rounded coordinates, commit metadata and unpacked APK checks. The signing certificate
matches the preceding local 0.4.1 APK. Public captures contain no text/EXIF metadata and were
visually reviewed. Current files contain no em dash; historical typography was not rewritten.

Still required: final backed-up 0.5 installation, parked overlay/touch-through check without
executors, then real automatic presentation after the separate DUDU ignition task is saved
and verified. No need to repeat all eight calibration drives unless a specific gap is found.

## Startup diagnosis and 0.4.1 installation - 2026-09-12

Confirmed vendor force-stop at sleep, stopped package after wake and missing DUDU task;
details in [startup diagnostics](STARTUP_DIAGNOSTICS.md). A real off/on cycle incremented
the vendor sleep counter without changing Android boot count. This is signal evidence,
not yet app-side wake recovery acceptance. Corrected the eastern return approach and added
bounded private category diagnostics plus a conservative cycle adapter.

Build, lint, pure detector/motion tests and emulator safety/navigation checks passed. A
complete readable private app archive was verified and its configured phone matched the
private installation bundle. An initial update attempt stopped on lost connectivity before
backup/install. After reconnecting, the guarded installer successfully backed up APK/data,
checked the signature and installed versionCode 6 / 0.4.1-local. Opening the menu consumed
the complete private import. The journal confirmed monitoring, fresh GPS fixes and a valid
vendor cycle read from the app's own process. No test call or robot routine was requested.

DUDU shortcut/task configuration is not saved yet. Access to the radio ended before this
integration and its physical wake test; the owner confirmed later access is required.
Next: configure the exact NoDisplay shortcut, verify recovery without opening the menu,
then observe real motion, Yanosik's background service/overlay and the previous screen.
This installation does not establish wake acceptance or successful automatic home journeys.

All eight private calibration recordings were replayed against the updated production
detector. Seven retain exactly their previous event sequence and timing. The former eastern
entry control now emits one return event: inspection confirms an eastern approach, then the
junction, then the inbound checkpoint. This is the owner's newly required return case, not
a remaining negative control. Two genuine negative controls still emit no events. Raw traces
and replay outputs remain private; this is recorded-input verification, not a new road test.

## Yanosik cold-boot/background acceptance - 2026-09-09

Corrected the installed Yanosik package identity and verified normal launch on the radio.
Normal launching left the map in front, so the final variant uses Android behind-task launch.
After its backed-up installation and completed import, a full radio reboot started the monitor
without manual app opening. Real vehicle motion triggered Yanosik; its foreground service and
small warning overlay were visible while the radio launcher remained on screen. No manual
HOME command or app opening followed this reboot. The owner confirmed real movement as a
passenger. The ten-second threshold is enforced by the tested detector, not a separately
timed roadside measurement. The session reservation remained consumed after stopping.

Installed APK bytes matched the local build. Build/lint, emulator safety/navigation checks,
synthetic motion checks, seven private-tool tests and five fresh-installer tests passed.
Private screenshots and APK analysis stay outside Git. Manufacturer sleep/wake, next-ignition
rearming and automatic home journeys remain unverified; this is not full product acceptance.

## Radio migration - 2026-09-09

After the owner confirmed a parked installation session, versionCode 5 / 0.4.0-local
was installed under the new application ID. The migration tool verified the matching
certificate and phone, archived the predecessor APK/data with checksums, and disabled
the predecessor without uninstalling it. Import verification passed: private storage,
phone, non-regressed daily quota, exact installed APK and predecessor exclusion.
The three menu tiles were present, HomeMonitorService was foreground, and the inspected
recent logs contained no new-package fatal crash or dial/Cleaning/Mop execution markers.
No physical action was deliberately triggered. This verifies installation/import/menu/service,
not fresh GPS delivery, journeys, Yanosik presentation or ignition/wake. Those checks remain
pending; the legacy package must not be finalized/removed yet. Current launcher resolution
for the configured Yanosik package returned no activity and needs investigation.

The subsequent label-only update passed build/lint and the backed-up installation/import
procedure. Radio UI inspection confirmed “Pełne sprzątanie” and “Mopowanie”; the README
menu screenshot was replaced with a visually reviewed actual radio capture. No call or
robot command was needed for these presentation checks. Private-value source/history scan passed.

## Historical local 0.4 checks before radio installation

Functional package refactor passed build/lint, existing Android safety/Roborock tests,
detector checks and Python installer checks. The real migration CLI passed on an emulator:
allowlisted state copying, disabled predecessor, interrupted staging/resume, encrypted
private import, preserved daily quota, refusal to reset a live target and guarded removal.
All emulator configuration is synthetic. No robot request or call is part of these tests.

Motion checks cover ten-second evidence, minimum displacement, stop/gap/jump/bad fixes and
deferred/cancelled action. Android checks cover session persistence, boot change, duplicate
verified-cycle IDs, missing app and failed launch without retry. UI checks cover setup,
error and the five-second result taking priority over navigation.

The actual Yanosik package is present in earlier radio location-service captures. Current
launcher resolution/background presentation and manufacturer wake were not checked: radio
read-only connection unavailable. `verifiedWake` is a tested seam without a production
adapter, NOT completed ignition support. See YANOSIK.md. Published/radio version 0.3 below
remains unchanged; this stage is not ready for full ignition/wake acceptance.

## Manual Mop and visual refresh - local versionCode 4

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

## Current implementation - 2026-09-08

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

## Fresh-install safety regression - 2026-09-08

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

## Radio session - 2026-09-08

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
