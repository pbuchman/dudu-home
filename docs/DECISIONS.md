# Design decisions

## Stable 1.5 promotion - October 27

- The owner explicitly requested stable 1.5.0 after confirming both acceptance scenarios on
  installed RC1/code34: automatic gate → observed robot start → Yanosik → audible Spotify,
  and a later same-day quota skip without another cleaning. Attribute this evidence to the
  owner confirmation on 2026-10-27; no new raw logs or broader hardware coverage are implied.
- Allocate code35/1.5.0 for the stable artifact. Change version metadata and documentation
  only; preserve RC application behavior, safety rules and release history. Its separate
  radio installation remains unverified until performed.
- Keep review, privacy and final PR/main/tag CI gates. Publish source only; no APK, private
  map/configuration/keys, backend deployment or installer behavior change is included.

## Full departure continuation - 1.5.0

A successful own automatic DEPARTURE_STARTED call queues independent Full Cleaning,
then Yanosik and Spotify, including while stationary without an earlier stop or checkpoint.
The captured attempt, runtime generation and driving cycle must still match. Duplicated,
manual, return, failed, cancelled and stale gate results cannot create a continuation.
OUTBOUND_CHECKPOINT remains passive route evidence and has no command authority or cleaning countdown.

Cleaning has a separate attempt and cancellation scope. Cancelling media does not cancel
cleaning; cancelling cleaning permits remaining media after transport cleanup. No robot stop
command is introduced. The gate retains its cleanup and five-second success display;
Spotify waits ten seconds after a Yanosik launch request. Existing manual UI priority remains.
RAM tasks expire after 120 seconds and are never replayed after restart/configuration/cycle reset.

Automatic cleaning skips/errors/uncertain results display for 2.5 seconds and never open a
persistent error or credentials form. Both that presentation and transport cleanup must end
before media. Accepted cleaning says only "Zlecono sprzątanie"; it is not robot completion.
Used quota says "Dzisiejsza próba sprzątania została już wykorzystana".
Manual cleaning and manual-only Mop retain their existing behavior.

ProgressBus uses SEND_ONLY reservation policy for automatic cleaning; other actions retain
the existing policy. DailyCleaning commits synchronously at claimSend immediately before
HTTP admission. Unsent cancellation, expiry, missing configuration or failure does not consume
the day and does not retry this occurrence. A later real automatic departure may try.
After admission, retain the reservation even for server error, timeout or uncertainty.
Failed persistence prevents sending. Check Europe/Warsaw date again at admission, including
a UI dispatch before midnight. Preserve historical last_attempt_day conservatively.

One source line includes reviewed code33 street stability, schema3 map reading, compact panels,
window layout and durable private diagnostics, plus guarded no-backup update tooling.
Preserve RC5 history and all Binder, endpoint, permission and protocol safeguards.
At RC preparation, publication was source-only and stable promotion was held on physical
departure/robot/audio/repeat-limit acceptance and final CI. The owner confirmation and stable
decision above supersede that acceptance hold; private APK/maps/configuration/keys remain excluded.
No backend deployment, map build or private routine change is included.

## Departure continuation - October 27, RC5

- A successful automatic DEPARTURE_STARTED call creates the existing Yanosik/Spotify
  group without a second standstill/motion test, even while waiting at the gate.
- Capture the attempt, runtime generation and journey cycle at admission. Duplicate,
  cancelled and obsolete callbacks cannot recreate work. Manual and return calls retain
  their existing area-release behavior without this new trigger.
- Queueing is distinct from sending: retain fresh location, action cleanup, five-second
  result presentation, manual UI/Maps, cleaning priority and ten-second Yanosik grace.
- Preserve cancellation before queue creation and between steps. Only qualified fresh
  standstill plus motion can rearm cancelled media; successful gate calls cannot.
- Keep process-local queues, 120-second media expiry and independent reservations at
  external sending. No migration, durable commands, new permissions or Binder changes.
- Release only this fix over public main. APKs and separate local candidates remain private;
  publication and emulator tests do not establish installation or road/audio acceptance.

## Installer accessibility and map exclusion - October 19

- Use the existing connected Wispr adapter's redacted ProbeReceiver tree, requiring a fresh
  device-monotonic timestamp and complete roots/children/counts. Never attach UIAutomator or
  change accessibility settings on the radio. Missing evidence fails closed.
- Require explicit action_busy=false for a running Dudu process; absence of service/logs is
  not idle evidence. Passive trip smoke proves visible control structure, not redacted text.
- Hold changed/missing map replacement before writing until an enforced reader/process gate
  exists. The persistent admission block is not a SQLite lock; process restart is possible.
  Equal-hash no-transfer updates retain explicit process-absence checks around cleanup.
  This safety hold supersedes the enabled direct replacement/repair path below.

## Single-file map updates - October 18

- Normal updates create no APK/data/map backups, saved installation receipts or automatic
  rollback. This supersedes earlier update/backup guidance below; namespace migration is distinct.
- Keep one designated `places.sqlite` in each explicitly managed host/radio directory. Validate
  SQLite schema/integrity/source policy and APK compatibility before mutation (schema 2: code23+;
  schema3: code29+). Existing schema2/3 packs need no companion manifest. Compute SHA at runtime.
- Equal hash transfers zero map bytes. Changed/missing maps overwrite the final path directly
  under the persistent installation block, then verify its hash. A partial failure stays blocked;
  an explicit retry repairs it. Do not launch an incomplete database or restore old safety state.
- Build replaces its designated output; all source metadata and short-heading source proof stay
  in SQLite. Disposable OSM scratch is removed on success/failure. Cleanup removes only exact
  recognized historical artifacts in the selected managed directory, never arbitrary extensions.
- Preserve app data, configuration, daily quota, dial cooldown and journey/trip state. Observe
  idle, block new admission and observe idle again before force-stop. Errors retain the block
  without interrupting any later call. Local signature verification plus `install -r` enforces
  package identity; no uninstall or downgrade. Hardware acceptance remains separate.

## Content-sized windows - October 18 local layout candidate

- Widen automation progress to at most 560 dp floating and 720 dp inline. Keep natural
  content height, readable message width and a responsive adjacent/below cancellation control.
  Bound overlay height to system-bar/cutout-safe space; overflow text scrolls while controls
  remain reachable. Preserve attempt tokens, all action timing and the 350 ms horizontal drag.
- Give native alerts explicit reading widths while retaining platform scrolling/buttons.
  Reduce navigation chooser decoration and remeasure its scroll area at actual window bounds.
  Full group names scroll beside the close control when necessary; destination text stays whole.
- Preserve the selected 286 dp, street 25 sp/locality 16 sp passive card and its X semantics.
  Full-screen content retains scrolling. Below 600 dp available height, reduce action
  decoration and gaps to keep ordinary error controls in view; preserve text/button sizes.
  Android/vendor notification and Toast geometry is outside this application-owned scope.
- Allocate local code 31 separately from frozen code 30. Emulator layout evidence and radio
  touch/display acceptance are separate; no publication or physical action follows from preview.

## Confirmed street presentation and durable queue evidence - October 16 RC4

- Keep raw matching quality separate from the street shown on screen. Retain a confirmed
  street through uncertain observations without a time/distance presentation grace or
  last-known prefix. Confirm another named or unnamed road with two distinct valid fixes.
  A confirmed unnamed road hides the street row. This supersedes the local grace below.
- Reset display memory at a new trip, provider disable, or ten seconds without a valid fix.
  Older resolver generations cannot restore a pre-reset label. These presentation resets
  never rearm gate, cleaning or media opportunities.
- The compact card uses max286dp width, 25sp street and 16sp locality. Floating surfaces
  support a 350ms non-button hold and horizontal drag with bounded persisted positions;
  inline controls retain their existing behavior. Hiding the trip card never stops collection.
- The owner explicitly requested on-device diagnostic evidence before dispatch and across
  process restarts. Keep cancelled unsent occurrences in a private bounded journal while
  preserving their existing quota/reservation semantics. This supersedes the historical
  no-durable-cancellation-history rule, not any action safety rule.
- Generic journals retain at most eight1MiB files for seven days. Private location decisions
  and displayed labels retain at most32MiB for seven days, enabled by default and never
  uploaded automatically. Credential and message contents remain excluded.
- Do not add Wispr-based admission or delayed-Spotify suppression to the Dudu queue.
  The separately maintained adapter owns its local dictation/media session.
- Offline tests, replay, installation and physical acceptance are independent gates. Retained
  labels are not proof of location correctness; a natural radio failure needs causal evidence.

## Source-proven short-fragment heading candidate - October 16

- Schema 3 adds `road_short_headings(road_id,heading,support_length)` and binds its policy,
  source hash and generation. Preserve schemas 1/2 and schema-2 locality evidence.
- Only nonzero fragments at most 5 m can use contiguous support from the same original
  OSM way. Extend at most 10 m/eight edges from each end; traverse only source nodes with
  eligible-road incidence exactly two. Stop at way ends/branches, reject curves above
  15 degrees, repeated nodes, missing/zero geometry or exhausted edge limits. Require
  both supported chain and chord at least 10 m. Never join ways by equal coordinates.
- Keep source way IDs, segment ordinals, source node IDs and degree proof inside the private
  SQLite pack alongside orientation. Count node incidence on disk with
  bounded batches/cache; the full source/map remain outside Git.
- Runtime requires actual bearing and speed above 2 m/s. Invalid/absent row or policy
  retains old scoring. All road candidates, distance/margins/query guards, two-fix
  confirmation, locality/online generation rules and 3 s/40 m retention remain unchanged.
- This is a local candidate. Exact new-pack replay, held-out ground truth and physical
  presentation/latency acceptance are separate gates; score changes alone prove none of them.

## Local street stability integration - October 16

- Keep offline matching responsive while optional Photon HTTP is in flight. Both lanes
  coalesce pending fixes; only an exact current offline fix/session accepts online results.
- Retain a recently confirmed street only as explicit `Ostatnio: …` / `LAST_KNOWN`,
  bounded by 3 seconds and 40 metres. A contradictory named road or certain locality,
  missing map coverage, invalid GPS or segment break clears it. Do not weaken road
  ambiguity or locality boundaries to make a test appear stable.
- Raw per-fix correlation requires a separate private runtime-file opt-in, bounded local
  trace and no automatic export. Normal category-only diagnostics remain unchanged.
- Code 28 combines the local code-24 street contract with the code-27 session timer,
  movable card and passive diagnostic power observer. Keep atomic checkpoints, input
  epochs, the separate power outbox and diagnostic UNKNOWN profile unchanged.
- Code 28 is a local candidate only; build checks and approximate archived-route analysis
  are distinct from new physical-radio acceptance. No release or publication is implied.

## Parking-tail corrections and passive power observations - October 16 RC3

- Keep raw recorded GPS time unchanged and expose a separate corrected in-car estimate.
  Only unioned exclusions intersecting recorded coverage can reduce it. Full-archive
  evidence, Warsaw calendar clipping and late-data reversal apply consistently across views.
- The conservative five-minute-tail estimator is labelled estimated, with open archive
  tails additionally provisional. A measured stop or a GPS gap does not prove radio OFF.
  Waiting with the radio active counts even with ACC OFF. No blanket five-minute deduction.
- Add passive module-0 observations and a separate durable Android outbox. GPS database
  version 1 and ingest v1 stay compatible with an older APK/backend. No second GPS service,
  vendor command, ignition rearming or change to gate/cleaning/media behavior.
- Trust only an explicitly audited firmware-bound interpretation; shipped defaults are
  diagnostic. Bound OFF coverage and invalidate it on loss/restart/motion. The manual
  timer preserves Start/Pauza/Reset and generation-safe atomic checkpoints.
- The 95-percent precision target needs one hundred independent labelled detections after
  freezing the algorithm. Implementation tests and historical estimates do not establish it.
- The owner explicitly approved this Home Dev deployment without a new backup. Preserve
  raw observations/configuration and use additive metadata plus application-only rollback.
  Physical radio installation and signal acceptance remain in a separate workstream.
  The detailed approved rules are in [PARKING_TIME.md](PARKING_TIME.md).

## Recorded time and manual session controls - October 15 RC2

- Home Dev sums the union of observed GPS time intervals independently of journey
  segmentation. Stationary time, including long parking, counts. Per-segment timestamp
  pairs up to thirty seconds apart establish continuity; larger gaps and segment
  boundaries are excluded. No extrapolated leading/trailing time or geometry filtering.
- Clip unioned intervals to Europe/Warsaw calendar boundaries before period aggregation.
  Keep immutable raw points and ingest v1 unchanged; late uploads update the result.
  Add observation counts to analytics periods so no data remains distinct from zero time.
- The radio keeps a separate manual session. Start/Pauza continues or pauses both totals;
  Reset confirms a nonzero clear and preserves active/paused state. Remove Resume/End.
  Preserve old distance and mark unavailable historical duration until the first Reset.
- Local duration uses fresh observations and monotonic awake time, independently of
  distance quality filters. Reset, pause, gaps, restart and verified wake break anchors.
  Persist time/state/distance atomically and reject queued work from older generations.
- Publish and deploy Home Dev independently of physical radio installation. A GPS-derived
  interval is evidence of observations, not a direct measurement of ignition or occupancy.

## Home Dev journey analytics - October 15

- Detailed statistics and their UI belong to the private Routebook backend/web application.
  Android changes only the existing location card presentation; GPS collection, queue and
  ingest v1 remain unchanged. No cost estimates, extra telemetry or map matching.
- Derive journeys from complete stored samples, preserving raw records and canonical v1
  map responses. Technical GPS segments are not journeys. Confirmed parking or gaps of
  ten minutes separate journeys; shorter observation gaps remain unknown elapsed time.
- Period summaries describe journey time; long boundary parking appears separately as
  observed visits. Named places are durable private metadata with independent revision.
  Backfill may replace derived journeys, so user names attach to places, not journey IDs.
- Publish one combined source revision and deploy Home Dev independently of the radio-update
  thread. Public release is source-only; private APK and physical acceptance stay separate.

## Movable and dismissible location card - October 15

- Give the passive location card a dedicated drag handle and independent hide button.
  A drag release cannot open the full screen. Keep address tapping and Pełna nazwa passive.
- Persist visibility and relative bounded position in private UI preferences. Start at the
  top right; explicitly restore a hidden card from Gdzie jestem. Neither control alters
  trip state/totals, the shared GPS monitor, Routebook or action priorities.
- Recompute safe system-bar/cutout bounds after resize, inset or content-height changes.
  Keep move/hide controls above a scrollable address when the available height is short.
  Temporary automation suppression preserves the user's visibility/position preference.
- Add no statistics collection or analytics in this change. Radio touch and driving/wake
  acceptance remain separate from build/static and synthetic emulator checks.

## Early departure, locality evidence and diagnostics - October 13

- The owner chose three seconds of GPS movement in any direction, including reversing,
  starting immediately with good GPS in the parking area. The initial opportunity needs no
  five-second stop, gate direction or minimum displacement. Keep strict GPS qualification.
- Store departure cycle eligibility separately from media/manual Maps baselines. A new boot
  or confirmed increasing vendor counter permits one fresh parking opportunity; first cycle
  observation stays conservative. App restart/update/menu/GPS gaps never rearm a used one.
  A first good fix outside parking closes the startup opportunity; later parking requires
  genuine-return evidence and five seconds of standstill. Preserve cancellation rearming.
- Preserve cleaning and return thresholds, gate-before-media priority and every Binder rule.
  Three seconds recognizes an event; fresh idle verification still precedes any dial.
- Map schema 2 indexes Polish administrative level-8 locality boundaries without requiring
  place tags. Locality matching is independent of street selection. Reliable boundary evidence
  outranks nearby hamlets; municipality, district and neighbourhood names are not primary
  locality substitutes. Nearest city/town/village remains explicitly approximate.
- Track quality and stabilize locality/street separately. Photon may fill missing components
  but cannot downgrade a certain offline locality. Schema 1 remains readable; new evidence
  requires the APK and a schema-2 pack. Reuse the existing September 27 OSM snapshot.
- Record bounded private categories/reasons, dump state and correlation asynchronously.
  Read-only collection/reporting does not start actions or change state. Unsent user-cancelled
  occurrences retain no durable cancellation history. New hardware acceptance stays pending.

## Full street names and current-attempt cancellation - October 9

- Preserve overlay position and priority; use locality 24 sp, primary road 32 sp with up to
  three natural lines and maximum 390 dp width. Measured overflow exposes Pełna nazwa;
  the full passive screen and expanded notification retain all address text.
- Give detection, queued and running work an explicit captured process/attempt token.
  Cancellation is idempotent, visible in bounded touchable UI and notification fallback.
- Keep unsent reservations and diagnostics in RAM; user cancellation discards them.
  Commit durable protection at sending, preserving conservative non-user failure/expiry limits.
- Rearm a cancelled occurrence only after qualified fresh baseline evidence. Media is one
  Yanosik/Spotify group; cancellation never bypasses gate-area priority or a sent reservation.
- Cleanup keeps shared leases until actual completion; no robot undo operation is introduced.
  Prior reserve-at-enqueue and non-touchable-banner decisions below are historical.


## Combined test baseline, October 6

Android, Routebook server/UI, deployment tooling and synthetic contract fixtures share one
repository. Version 1.3.0-rc1/code 21 supersedes separate local candidates without claiming
hardware acceptance. Operator-specific origins and evidence remain private; deployment
requires explicit runtime input. Wispr Flow is separate software, not implemented in this APK.


## Independent trip session and OSM data - 1.2 candidate

- Fourth tile and explicit session; opening the screen is inert. Background counting continues.
- Reuse the existing GPS foreground service, with separate counter and resolver workers.
- Prefer downloaded OSM evidence; keyless Photon is optional best-effort assistance, not an SLA.
  Exclude public Nominatim vehicle polling, Android Geocoder and Google location data.
- Settlement points are nearby hints, not invented boundaries; ambiguity remains visible.
- Minimal two-line touchable overlay; no action buttons or distance. Existing automation wins.
- Store only totals/state, break missing segments and keep real-drive acceptance distinct from tests.
- Publish source plus synthetic emulator captures; maps, APKs and installation evidence stay local.


## Stable 1.1 and third destination - September 27

- Append private configuration within the existing 12-place capacity; no new slot or UI fork.
- Add synthetic third-row selection/order coverage and document the complete-file update.
- Owner confirms existing functionality and authorizes stable 1.1 after the third target is
  verified on the radio. This supersedes the earlier blanket hold on a final tag. Keep
  historical unobserved hardware scenarios explicit; owner acceptance is not a new test log.
- Stable publication remains source-only; no private destination or APK release assets.

## Configurable destination groups - 1.0.0-rc4

- Schema 2 defines tile metadata and a bounded list; support legacy singleton documents unchanged.
- Empty/single/multiple behavior is derived from list length, shared by all navigation slots.
- Use a native in-app Dialog with fixed header, bounded scrolling and manual-UI exclusion.
  Cancel is inert; only a chosen target invokes the existing durable Maps launch reservation.
- Keep group labels and all members private. Synthetic examples/tests only; scan both schemas.
- Update the APK before importing schema 2; retain the compatible private rollback file.


## Gate area before generic motion - 1.0.0-rc3

- Ready-action queue priority alone cannot protect a gate event that is not ready yet.
  Owner requested a geographic precondition before Yanosik/Spotify motion recognition.
- Reuse detector geometry and return evidence, not street names, another GPS subscription,
  new configuration or UI-owned thresholds. Consumed event flags are not proof of a call.
- Inside the applicable area wait for executor success; otherwise require fresh outside
  evidence. No elapsed-time shortcut. Unknown GPS does not mean outside.
- Start fresh generic movement recognition after resolution. Preserve call safety, result
  screens, manual menu and Maps suppression, cleaning quota and all route event thresholds.
- Completion is process-local and generation guarded. No new retry or persisted command.

## Per-destination Maps address target - 1.0.0-rc2

- Preserve coordinate navigation as the default, including for existing files with address text.
- Optional `navigate_by: address` uses that destination's validated, URI-encoded full address.
  Maps resolves it; require explicit opt-in and verify the name and endpoint on the device.
- Keep the other slots, manual media priority and private import behavior unchanged. No fallback,
  public place edits, custom-name guarantee or real address fixture.

## Private manual Maps destinations - 1.0.0-rc1

- Three stable slots below the existing actions; configuration is an external JSON document,
  never a source constant. Generic home, sport and pin art; address labels are supported.
- Validate before replacing an AtomicFile in no-backup storage. Android's document picker
  gives access only to the selected file; no broad storage permission or retained URI grant.
- Explicit Android Maps driving intent with coordinates, no browser, SDK, key or geocoding.
- Manual navigation suppresses pending media startup durably. Rebaseline the next vendor
  read to avoid a stale current-wake result immediately undoing that choice. Home actions
  remain independent; do not stop already-playing music or launch anything during import.
- Keep all real configuration and screenshots private. Public examples are empty or synthetic.
- Use a 1.0 release candidate while historical wake/journey acceptance remains incomplete.
  See NAVIGATION for the contract and VERIFICATION for evidence.

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
