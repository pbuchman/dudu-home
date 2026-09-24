# Design decisions

## Ordered automation and Spotify - 0.5.6

- Supersedes earlier no-queue and delayed-HOME decisions below: ready gate, cleaning,
  Yanosik and Spotify actions use an explicit bounded in-memory priority queue.
- Reserve daily cleaning at enqueue, including while gate execution is busy; no retry or replay.
- Preserve executor cleanup and screen ownership independently. Never cover a manual menu,
  configuration or error with media startup. Gate wait is 5 s; other waiting tasks expire at 120 s.
- Separate durable Spotify attempt in JourneySession, rearmed atomically with Yanosik.
- Launch Spotify after Yanosik's 10 s allowance; no HOME. Resume only an exact-package,
  unambiguous local media session, with one play and bounded callback waits.
- Existing notification-listener grant/component is reused. No SDK, OAuth or global media keys.
- Native session availability after cold Spotify launch requires a physical test; do not add
  a workaround without agreement. See AUTOMATION_SEQUENCE for the full contract.

## First-wake baseline - 0.5.5

- A complete successful read with boot-completed guards and consistently empty vendor
  properties is an observed baseline zero on the analyzed firmware, not an unknown read.
- Baseline establishment preserves consumed. Only a later increasing counter rearms; first
  numeric observation without an earlier baseline remains conservative on update/restart.
- Failed JourneySession commits latch that store closed across instances until process exit.
  Cached preference changes are not proof of durable storage. No retry/reset UI is added.
- Only REARMED clears existing movement/progress and cancels pending HOME. Invalid reads
  finish the initial read barrier without disabling the existing cold-boot opportunity.
- Preserve the wake-task repair, presence guard, 10-second HOME grace and all home actions.
- Full boot followed by the first actual vendor sleep/wake is the required hardware check.

## Wake task trimming - 0.5.3

- Preserve read-only failure evidence before opening the app or updating the radio.
- Do not exclude the isolated wake task before its onCreate callback. Remove it explicitly
  after requesting monitoring; keep NoDisplay and the existing cycle-based rearming rules.
- A process without a foreground location service is not successful monitor startup.
- Test real vendor sleep separately from full reboot. Short and medium stops are in scope.

## Presence guard and earlier return - 0.5.2

- Owner approved notification access and a return banner before the turn.
- Fresh service-notification evidence, not a guessed PID or persisted "running" flag.
- Work detected: no launch/HOME. Unknown: skip cycle with short explanation, no retries.
- Settings is the only permission entry point; no content logging or other-app analysis.
- Return-only observation expands to approach/inward stages with distance-derived fill.
  Existing call events, timings and flags remain identical. No animation-delayed calls.
- Car access/testing waits for the owner. Overnight monitoring startup remains unresolved.

## One-shot Yanosik desktop return - 0.5.1

- The owner accepted a simple launch followed by one desktop request, five seconds later.
- Replace unreliable behind-task startup. Brief Yanosik UI is acceptable; do not track other
  foreground apps or restore a prior navigation app. No Accessibility or simulated keys.
- Keep one reservation per boot/wake, no retries and no hiding a later manual Yanosik launch.
- Cancel the pending desktop callback when monitoring stops or a new verified cycle arrives.
- A requested desktop is not proof of Yanosik service/overlay readiness. Verify on the radio.

## Observational automation progress - 0.5

- Keep the 0.4.1 detector, vendor cycle adapter, diagnostics and executor safety as the baseline.
- Publish immutable observations; UI can neither dispatch nor reserve/rearm an action.
- Use one native banner, inline in the focused menu or a non-touchable overlay, without an
  Activity launch for detection. Protected apps may hide overlays; never bypass this.
- Distinguish recognition, dispatch, acceptance, actual attempt and outcome. Correlate using
  numeric IDs, not authorization tokens. Undelivered requests expire without retry after 5 s.
- Detector evidence supplies fill; 2 s cancellation/request outcomes do not alter action timing.
- No new configuration/export UI, libraries, logger, private data or public APK.
- See [PROGRESS_UI.md](PROGRESS_UI.md); real radio presentation and ignition acceptance remain pending.

## Startup recovery - 0.4.1

- Fix the proven vendor force-stop with an explicit DUDU ignition shortcut to the existing
  NoDisplay wake Activity; do not add another menu tile or pretend sticky services survive it.
- Identify wake by the observed vendor sleep counter, with awake-state guards and a
  non-rearming first baseline. Never treat shortcut invocation or screen/GPS gaps as ignition.
- Keep bounded private category-only diagnostics, no export UI or full route recording.
- Accept eastern return as well as north/south approaches, preserving inbound sequence checks.
- Retain all call safety, manual-only Mop and daily-cleaning reservation rules.

## Local 0.4 - approved namespace migration and navigation

- Owner explicitly chose new application ID and functional packages `com.pbuchman.duduhome`.
- Preserve private state via backed-up migration, new Keystore encryption, old package disabled
  until new functional/wake verification; only then explicit removal. Migration is now installed.
- Yanosik: sustained GPS motion, one attempt per ignition/wake, home actions and result UI first.
- Cold boot is implemented; manufacturer wake must be observed, not inferred from screen/GPS gaps.
- Owner authorized source publication on 2026-09-09 after the radio/background check and
  privacy audit. Do not claim full ignition/wake readiness while its adapter is unresolved.

## Manual Mop and visual refresh - after source publication

- Public source checkpoint `78d1da8` was pushed before beginning these changes.
- Add Full Mop as a third, manual-only tile. No automatic dispatch or daily quota for Mop.
- Keep the existing bundle compatible; optional distinct Mop ID, no fallback to another routine.
- Three illustrated touch targets, a home/spark launcher mark and cohesive dark surroundings.
- Gate and routine success display now lasts 5000 ms; errors and cooldown behavior are unchanged.
- Yanosik after roughly ten seconds of sustained GPS movement is the next queued hook,
  not implemented as part of this visual/routine change. No UI setting for that future hook.

The following records describe earlier milestones; the approved five-second duration above
supersedes their original success timing.

- **Stable baseline first:** preserve the hardware-tested gate-call behavior, changing only
  public branding, removal of number fragments from logs and safe install tooling.
- **Privacy boundary:** a fresh Git history, no calibration module, no actual installation
  configuration in the public tree. Synthetic examples only. Local private archives are not Git repos.
- **Compatibility:** retain the technical application ID and local Android signing key. The
  visible name is Dudu Home. No public APK or new release-signing scheme in this phase.
- **Small Android implementation:** Java 17, Views/XML and platform APIs, without AndroidX,
  analytics or an imitation of the vendor Bluetooth service. The baseline had no Internet;
  the approved Full Cleaning extension adds direct native HTTPS only for that routine.
- **Calling safety:** keep raw Binder, pre-dial idle verification, one call per attempt,
  persistent 60-second cooldown and cleanup of only calls initiated by this attempt.
- **Setup is not a call:** the baseline saves a number and closes without dial. A new launch
  is required to call. The later menu version adds a configuration cooldown and returns to menu.
- **Honest feedback:** retain outgoing-plus-delay behavior; no claim of first ringback or
  physical opening. Success is shown for 1350 ms; information for 2500 ms. Normal errors
  remain until user action. The firmware dependency is documented, not hidden.
- **Verification:** build, lint and small emulator checks. Real Binder behavior, power cycles
  and future location-triggered UI must be validated separately on the radio.
- **Current architecture:** location events and manual commands are independent inputs to
  guarded executors. The approved scope now includes Full Cleaning, but no music/other devices.

## Approved Full Cleaning extension - 2026-09-08

- **Two real tiles:** gate and Full Cleaning. Small settings entry for phone and one-paste
  Roborock bundle; location remains private installer configuration, no editor.
- **Origin-aware result:** manual and explicit retry return to menu. Automatic work hides
  afterward unless the menu was visible. Keep baseline success/information durations and
  user-dismissed errors. Never interrupt configuration/error UI with another automation.
- **One automatic attempt/day:** reserve Europe/Warsaw date on outbound checkpoint before
  dispatch. Failure, missing credentials, busy UI/executor and offline state do not allow
  another automatic attempt that day. No queue or automatic retry. Manual actions neither
  consume nor obey this daily limit.
- **No robot-state checks:** send the existing routine as requested. Never stop, pause or dock
  after starting it, including during tests. Phone app remains responsible for robot control.
- **Minimal credentials:** RRiot u/s/h + regional HTTPS host + routine ID/name, not a lone account
  token. No documented expiry guarantee, no invented refresh flow. Known auth rejection opens
  replacement setup; save is inert. No Python in the APK and no intermediary server.
- **Defense in depth:** allowlisted HTTPS endpoints, strict IDs/header fields, system TLS,
  no redirects, bounded requests, encrypted Keystore storage and no secret logs/backups.
- **Ambiguous network result:** report uncertainty, never interpret timeout as “did not start”.
  Do not replay automatically. Shared lease lasts through transport/Binder cleanup after UI closure.
- **Safe updates:** verify current radio number and signing certificate, private backup with
  checksums, maintenance before install, atomic one-time staging and cleanup after import.
  Keep existing daily and location state. Never reinstall by uninstalling to bypass signature checks.
- **Handoff and release:** commit local milestones and factual verification. The public baseline
  was initially kept unchanged pending full acceptance. The owner subsequently authorized current
  development source publication on 2026-09-08 after a privacy/documentation audit. Preserve
  `v0.1.0-baseline`; publish current main with pending journey/wake checks, not a final release claim.
  No public APK. Google Password Manager storage remains uncompleted; never bypass its security refusal.
