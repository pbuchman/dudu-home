# Change history

## 0.4.0-local — not installed or published

- Functional Java packages and new application ID `com.pbuchman.duduhome`.
- Explicit guarded legacy migration with emulator-backed state/import/resume tests.
- Independent sustained-motion hook, Yanosik launcher and persistent cold-boot reservation.
- No new UI setting; existing actions/setup/result screens take priority.
- Manufacturer wake adapter still requires actual radio evidence; full ignition support is pending.

## 0.3.0-local — manual Mop and visual refresh

- Installed as a backed-up, same-signature update on DUDU7; full private configuration imported.
- One owner-authorized manual Full Mop request returned ACCEPTED and the menu reappeared.
- Removed redundant header/tile subtitles at the owner's request; centered artwork/title groups
  in equal 280 dp tiles. README uses the actual radio screenshot. No robot stop or repeat request.

- Work began after publication of `78d1da8` to public main.
- Manual-only Full Mop tile with an optional, separately validated private routine ID.
- Shared encrypted bundle, explicit routine selection, no fallback to Cleaning and no automatic Mop path.
- Computer bootstrap discovers exact Full Mop on the same robot; ambiguity is rejected.
- Three illustrated tiles, scalable home icon, coordinated action screens and five-second successes.
- Regression checks for Mop selection/absence, encrypted persistence/import, automatic rejection,
  unchanged daily quota, and result duration. Yanosik movement hook recorded in the roadmap only.

## 0.2.0-local — published development source, not a final release

### Source publication review

- Audited tracked files, all reachable history/commit metadata and documentation images against
  private configuration, credentials and location values; no private matches found.
- Updated README, implementation plan and handoff to distinguish public development source,
  completed manual radio tests and pending automatic journey/ignition acceptance.
- Original baseline tag preserved; no APK, private archives or configuration published.

### First combined radio deployment

- Same-signature update with verified private APK/data backup and full private configuration.
- Both manual tiles exercised on DUDU7: gate call SUCCESS and native Full Cleaning ACCEPTED,
  with return to menu. No stop command; manual cleaning did not consume the daily quota.
- Automation enabled; permissions and continuing GPS delivery after UI dismissal verified.
- Route-triggered actions, physical robot confirmation and ignition/vendor wake remain pending.

### Installer safety correction

- Fresh-install helper now stops on ADB errors and empty/unrecognized package lists, checks
  retained-data packages, requires an explicit device and never invokes replacement mode.
- Offline whole-script regression tests cover thirteen success/error/race scenarios and run in CI.
- Updated installation instructions and verification ledger. No application behavior or APK change.

### Preserved development checkpoint

- `e91b342`: preserved previously local menu/location work before the Roborock extension.
- External private geometry, pure Java detector, foreground GPS monitor, boot/wake entry,
  internal event dispatch, manual menu and persistent number-setup cooldown.
- Eight-capture private replay and explicit outstanding radio tests.

### Full Cleaning

- `39eca9d`: application, native executor, settings, daily reservation, tests and functional/protocol contract.
- `d813550`: private bootstrap, verified backup/update/import, installer tests and privacy tooling.
- Second large tile; manual and automatic origin-aware UI; small number/Roborock settings.
- Native routine POST/Hawk signing, strict regional endpoint validation, no Android Python.
- Keystore encrypted minimal credentials; rejected authorization returns to setup.
- One automatic attempt per Europe/Warsaw day at outbound checkpoint, including failed/skipped
  attempts; manual actions independent. No robot-state precheck, auto-retry, queue or stop commands.
- Shared action lease through actual network/Binder cleanup, including lifecycle closure.
- One-time private import; number verification against radio, signature comparison, private
  checksum-backed update, maintenance during deployment, no action from installation/save.
- Synthetic instrumentation/transport/crypto/quota/UI/import tests, real installer exercised on
  emulator, computer-only credential bootstrap and configuration tests, updated CI local checks.
- Functional contract, protocol, installation/recovery, test ledger and agent handoff.
- Radio installation/manual acceptance now recorded above; complete acceptance remains pending;
  no public automation APK/tag.

## 0.1.0-baseline — published source baseline

- Fresh clean repository `pbuchman/dudu-home`, MIT, visible Dudu Home branding.
- Preserved single-phone DUDU/SYU calling protocol and package/signing compatibility.
- Number configured outside APK; no number fragments in app logs; safe installer without launch.
- Source-only publication, private predecessor/history/logs/signing key kept outside this repo.
