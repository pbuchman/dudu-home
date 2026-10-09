# Change history

## 1.3.0-rc2 (code 22) - 2026-10-09

- Wrap long street names at a fixed readable size, with an explicit full-name action for
  overflow, an unlimited passive screen and expanded full-address notification.
- Add current-attempt cancellation during detection, queue waiting and execution; group
  Yanosik and Spotify and provide a bounded touchable banner and notification fallback.
- Discard unsent user-cancelled work and buffered diagnostics without consuming quotas.
  Preserve sent-command reservations, gate cooldown and shared leases through actual cleanup.
- Require fresh qualified standstill/route evidence before recognizing a new cancelled occurrence.
- Keep legacy non-user failure/expiry limits, passive trip priority and private configuration.
- Extend synthetic detector, cancellation, notification and street-layout checks.
- Source-only prerelease; physical cancellation, GPS and sleep/wake acceptance remain pending.
  See [release notes](docs/RELEASE_1_3_0_RC2.md) and [verification](docs/VERIFICATION.md).


## 1.3.0-rc1 (code 21)

- Consolidate Android, Routebook backend, private map UI, deployment tooling and contract fixtures.
- Add automatic GPS outbox and newest-first HTTPS delivery, independent of manual trip sessions.
- Include all 1.2 location/overlay and earlier navigation/automation changes.
- Require private operator inputs for deployment; keep source builds reproducible.
- Hardware acceptance pending; Wispr Flow is separate and no APK is published.


## 1.2.0-rc1 (code 19)

Merged into main through PR #7 on 2026-10-06. The cumulative local rebuild retains
this version and the original application source; radio acceptance is still pending.
See [release handoff](docs/RELEASE_MAIN_20261006.md).

- Add Gdzie jestem as the fourth action, with explicit trip sessions and background distance.
- Show locality/street through a minimal two-line overlay and silent tappable notification.
- Add read-only local OSM indexes and bounded keyless Photon assistance; no Google location data.
- Preserve gate/cleaning/media priority and all existing action reservations.
- Add map preparation, automatic radio discovery/update checks, trip tests and full-app UI captures.
- Hardware driving/wake acceptance is tracked separately; source-only candidate, no public APK.


## 1.1.0 (code 18) - 2026-09-27

- Promote configurable navigation groups with up to 12 ordered destinations per slot.
  Adding a third choice requires only a private file update and reimport.
- Cover third-row selection, exact target order and preservation of other slots/modes with
  synthetic tests; expand README and the agent configuration and acceptance runbooks.
- Owner accepted existing functionality. The third target, its resolved Maps name/address
  and driving guidance passed on installed code 18; see the verification ledger for limits.
- Source-only distribution, no actual locations, private configuration or APK assets.

## 1.0.0-rc4

- Support privately configured destination groups in all three navigation slots, with a native
  chooser for multiple places and direct navigation for one. Preserve schema 1 and address mode.
- Extend strict validation, private installation compatibility checks, agent runbook and privacy
  scanning to group labels and all members. No real destinations are distributed.
- Verification status and pending hardware acceptance remain in docs/VERIFICATION.md.


## 1.0.0-rc3 (code 16) - 2026-09-26

- Check the applicable gate area before generic driving progress and media reservations,
  rather than giving priority only to already-ready gate jobs.
- Hold Yanosik/Spotify through manoeuvring and return evidence until a successful gate call
  or fresh outside-area evidence. Unknown/stale GPS and failed/skipped calls cannot bypass it.
- Keep generation-safe executor completion separate from UI observation, with no timer-based
  bypass, auto-retry, call threshold, quota or Maps-navigation regression.
- Add synthetic area and Android scheduling regressions. Physical rc3 acceptance is pending.

## 1.0.0-rc2 (code 15) - 2026-09-26

- Allow an individual navigation destination to use its full address when Maps associates
  its coordinates with an unwanted business name. Existing files still use coordinates.
- Validate the optional target mode and require address text; encode the query and preserve
  the other slots. Add synthetic parser, round-trip and intent regression checks.

## 1.0.0-rc1 (code 14) - 2026-09-24

- Add three file-configured Google Maps driving destinations below the existing actions,
  with generic illustrations and outlined empty states. No actual destinations in the APK.
- Import a bounded, strictly validated UTF-8 document using the Android picker or private
  installer. Atomic no-backup storage; cancellation and invalid input preserve saved places.
- Persist manual navigation priority over pending Yanosik/Spotify startup, including after
  process restart and delayed vendor wake observations. Existing playback is not stopped.
- Extend private-value auditing to navigation labels, addresses and encoded/rounded values.
  Add parser, intent, media-priority, document import and complete installer tests.
- Candidate version only: repeated manufacturer wake and remaining journey acceptance are
  still open. No final 1.0 tag or public APK.
- Installed on DUDU7 on September 26 with a private rollback backup and preserved data.
  All three tiles started Google Maps turn-by-turn guidance; see the verification ledger.

## 0.5.6-local - ordered automation and native Spotify resume

- Explicit in-memory gate, cleaning, Yanosik, Spotify priorities with bounded waiting.
- Reserve cleaning at enqueue and preserve its daily limit through failures and restarts.
- Independent durable Spotify attempt; retain verified first-wake and failed-write protections.
- Replace Yanosik's delayed HOME with a ten-second allowance before Spotify launch.
- Exact-package local media-session control, at most one play, callback timeouts and cleanup.
- Preserve executor safety, manual menu, result/error precedence and all route thresholds.
- Add scheduling and fake-media regressions. Hardware acceptance remains pending.
- Correct installation history: 0.5.5 was installed September 14; full wake acceptance was not completed.

## 0.5.5-local - preserve the first vendor wake opportunity

- Distinguish verified empty cold-start properties from uncertain reads; persist baseline zero.
- Keep first numeric migration conservative and rearm only on a verified counter increase.
- Fail closed across session instances after persistence failure, without stopping home monitoring.
- Preserve asynchronous reads, late-callback rejection, route flags and executor safety.
- Add parser/process, persistence-failure and isolated monitor integration regressions.
- Source verified locally; installed September 14. Full first-wake acceptance remains pending.

## 0.5.4-local - allow cold Yanosik startup before one desktop return

- Extend one-shot HOME grace from 5 to 10 s after captured late dashboard startup on DUDU7.
- Still no retries, repeated hiding or foreground-app tracking; existing-work skip is unchanged.
- Keep the 0.5.3 wake entry repair and all calling/session protections.

## 0.5.3-local - wake entry survives concurrent startup tasks

- Fix captured pre-onCreate trimming of the excluded NoDisplay wake task.
- Keep the isolated entry eligible until handoff, then finish and remove its own task.
- Preserve session counters, action safety and no-menu wake behavior.
- Add emulator regression for task flags, cleanup and no journey rearming by entry alone.
- Installed as a backed-up code 10 update. Existing-Yanosik skip passed on the physical radio.
- Intermittent failures include short/medium stops; repeated vendor wake acceptance is separate.

## 0.5.2-local - Yanosik presence and earlier return progress, not installed

- Skip launch and desktop navigation when Yanosik's foreground-service notification exists.
- Explicit unknown state and no-retry skip when notification access/connection is unavailable.
- Settings entry for the owner-approved system notification grant, without reading contents.
- Return banner before the turn, then inward distance-derived progress instead of fixed 50%.
- Preserve route events/timing, call safety, daily cleaning quota and wake semantics.
- Overnight monitor startup is still unresolved; radio tests wait for owner availability.

## 0.5.1-local - one-shot desktop return after Yanosik startup

- Normal Yanosik launch followed by one desktop request after five seconds, as approved.
- No foreground-app tracking, repeated hiding, extra permissions or launch retry.
- Cancel a pending desktop request on monitor stop/new cycle; do not replay after restart.
- Preserve motion thresholds, session reservation and all gate/cleaning protections.
- Real 0.5 session proved full-boot and one ignition recovery, the movement banner and an
  automatic return call; the owner confirmed gate opening. Yanosik foreground presentation
  failed in that session, motivating this fix. New hardware acceptance remains separate.

## 0.5.0-local - observational automation progress, radio checks pending

- Native top-centred non-interactive overlay and shared inline menu banner.
- Immutable detection snapshots, correlated action outcomes, two-second cancellation reasons.
- Detector-derived fill and honest Yanosik launch-request result; no action from observation.
- Expire undelivered five-second Activity requests without retry or late execution.
- Reuse bounded diagnostics; preserve executor locks, daily quota and wake adapter.
- Baseline-equivalent events/times/flags on all eight private replays, plus synthetic checks.
- Synthetic screenshots and emulator coverage. Subsequent backed-up DUDU7 update, private
  import and fresh GPS verified; ignition shortcut/task saved. Actual wake and journey
  acceptance still pending. Package/signing unchanged; source only, no public APK.

## 0.4.1-local - startup recovery work, physical wake acceptance pending

- Identified the vendor force-stop that leaves monitoring stopped after head-unit sleep.
- Read the observed vendor sleep counter with awake-state guards and a non-rearming baseline.
- Keep private bounded diagnostic categories for startup, GPS delivery, events, blocked actions
  and results, without location values, phone fragments or account payloads.
- Include the eastern return approach while retaining directional inbound sequence checks.
- Preserve calling safety, once-per-day automatic cleaning and manual-only Mop.
- Build, lint, detector and emulator regression checks passed; a backed-up same-signature
  update, private import, fresh GPS and app-side cycle reading passed on DUDU7.
- DUDU ignition shortcut/task configuration and actual wake/background acceptance remain
  pending. No final release tag or public APK.

## 0.4.0-local - installed development source, not a final release

- Functional Java packages and new application ID `com.pbuchman.duduhome`.
- Explicit guarded legacy migration with emulator-backed state/import/resume tests.
- Independent sustained-motion hook, Yanosik launcher and persistent cold-boot reservation.
- No new UI setting; existing actions/setup/result screens take priority.
- Manufacturer wake adapter still requires actual radio evidence; full ignition support is pending.
- Radio migration, configuration preservation and full-boot monitor recovery verified.
- Correct Yanosik package and behind-task launch verified with real motion and warning overlay.
- Polish menu labels, refreshed radio screenshot and source publication/privacy audit.

## 0.3.0-local - manual Mop and visual refresh

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

## 0.2.0-local - published development source, not a final release

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

## 0.1.0-baseline - published source baseline

- Fresh clean repository `pbuchman/dudu-home`, MIT, visible Dudu Home branding.
- Preserved single-phone DUDU/SYU calling protocol and package/signing compatibility.
- Number configured outside APK; no number fragments in app logs; safe installer without launch.
- Source-only publication, private predecessor/history/logs/signing key kept outside this repo.
