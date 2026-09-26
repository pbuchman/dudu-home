# Ordered automation and local Spotify playback

## Gate-area precondition (1.0.0-rc3)

Before the generic driving countdown, evaluate the current GPS fix with the existing home
detector. Its separate `GateArea` observation has no authority to dial or change consumed flags.
The departure area reuses the detector's 85-metre envelope around the configured parking point,
including the fix's accuracy margin. It covers alternate parking and manoeuvring before a
directional candidate. The return precondition reuses the approach/junction route evidence,
including traffic stops, without requiring a visible progress candidate or ready call event.
Movement away from the junction on the external road does not hold media as a return.

In either area, `GatePrecondition` blocks media detection, reservations and pending launches
until the gate executor reports a successful call. A request, consumed route flag, skipped call,
timeout or dismissed error is not success. Executor cleanup and the result/error/menu screen
keep their existing independent locks. A manual successful gate call can satisfy the same
area, but cannot let media cover its menu. Outside the applicable area, fresh GPS allows the
usual ten-second/fifteen-metre motion test without waiting for home actions.

No timer bypasses an applicable gate area. Bad/missing GPS means UNKNOWN, not outside; it
clears generic movement and hides that countdown. A successful call in the same area survives
a temporary GPS gap, but media still waits for a fresh valid fix. Leaving and re-entering an
area requires a new decision. Wake/configuration reset and service replacement discard the
process-local completion, and old executor callbacks cannot satisfy the new generation.
No/disabled home configuration preserves independent driving startup and manual actions.

The barrier does not invent a call when the existing detector cannot arm: departure still
requires its stationary baseline and sustained directional movement. If no call succeeds,
media remains deferred while the area applies. Investigate missing calls independently;
do not weaken Bluetooth/cooldown rules or reset consumed events to force a result.

Safe diagnostics add `MEDIA_GATE_AREA_UNKNOWN/NONE/DEPARTURE/RETURN` on transitions and
`MEDIA_GATE_CALL_COMPLETED` when a current-area call releases the barrier. No positions,
phone data or extra raw GPS logger. The queued-action deadlines below remain unchanged;
media is not queued/reserved merely because motion occurred inside the blocked area.

## Scheduling contract

`AutomationCoordinator` has explicit numeric priorities: GATE 0, CLEANING 1, YANOSIK 2,
SPOTIFY 3. Qualified events compete after the gate-area precondition; driving elsewhere never
waits for home events.
`AutomationRuntime` batches offers from one GPS callback before draining on the main thread.
The queue is process-local, deduplicates pending attempt IDs, and never replays commands after restart.
Detectors and UI snapshots cannot directly launch media or bypass the existing executors.

Gate events expire 5 seconds after recognition. Cleaning and media expire after 120 seconds
of waiting. Expiry skips the attempt without retry. The separate 5-second Activity-delivery
token starts only when a home action is selected. Pending tokens are cancelled by their owner,
so an obsolete runtime cannot cancel a newer request. Generation changes discard queued media
and callbacks on verified wake, configuration changes, maintenance and monitor destruction.

An active phone/HTTP transport is never interrupted for priority. Executor exclusion lasts
until transport cleanup; screen exclusion independently lasts through the five-second result,
or until the user dismisses an error/configuration view. Home actions may use an open menu
and return to it. Media waits until the manual menu is left. Neither a banner nor a media
startup owns the gate/cleaning executor lock. Higher-priority work can run during the
ten-second Yanosik grace, with no repeated Yanosik launch afterward.

Daily cleaning is reserved at enqueue, including while a gate action is busy. A later failure,
missing configuration, expiry or process death does not restore it. Crossing the calendar-day
boundary expires pending cleaning. Manual cleaning is independent and Mop remains manual only.
No route thresholds, Bluetooth safety checks, cooldown or Roborock protocol are changed.

## One motion event, separate durable reservations

Qualified motion remains ten seconds and at least fifteen metres, with the existing GPS
quality checks. `JourneySession.consumed` still means Yanosik; `spotify_consumed` independently
guards Spotify. Reserve before enqueue, and atomically rearm both on a new boot or verified
increasing wake counter. Baseline-zero, conservative legacy migration and failed-write locking
are preserved. Missing Spotify state on update permits one attempt only after fresh motion.
Notification grants, process restarts and manual music pauses never create another attempt.

Yanosik uses its existing notification-presence guard. Working, missing or unknown Yanosik
does not block Spotify. A sent launch request starts a ten-second allowance, not a readiness
claim. `YanosikLauncher` no longer schedules HOME; Spotify becomes the foreground application.

## Spotify transport

`SpotifyController` uses the normal launcher intent and MediaSessionManager under the existing
notification-listener component. No new permission, OAuth, SDK, Web API, global media key,
notification action or Accessibility integration is introduced.

Only package `com.spotify.music` is considered. Require exactly one stable session token and
`PLAYBACK_TYPE_LOCAL`. Remote, ambiguous, changed or inaccessible sessions end without a play
command. Already-playing local playback succeeds without a command. PAUSED or STOPPED with
ACTION_PLAY permits exactly one `TransportControls.play()`, reserved before IPC. Buffering
waits; it neither succeeds nor sends another play. No metadata, accounts or track titles are read.

Wait up to 15 seconds for a session, then up to 10 seconds for readiness/playback; sending play
starts a bounded 10-second confirmation window. Callback-based timers never block the main thread.
Higher-priority action or manual UI prevents a pending play; existing media timeouts remain
bounded and may report timeout while blocked. No retry or re-opening Spotify follows a timeout.
Only observed local PLAYING reports success. Listener/timer cleanup occurs on every terminal path.

Android's local-session flag is the available transport signal, not a guarantee about every
Spotify Connect implementation. Actual sound on the radio must be checked separately.

References: [MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager),
[TransportControls.play](https://developer.android.com/reference/android/media/session/MediaController.TransportControls#play()).

## UI, privacy and acceptance

One shared motion banner is followed by task-specific messages. WAITING has no fill animation.
Spotify reports startup, resume request and, only after PLAYING, playback. Its result lasts
two seconds without closing Spotify. Existing menu, errors and home result views retain priority.
Reading/replaying snapshots is never a command.

Existing bounded Diagnostics records safe task categories, attempt IDs, queue entry/start/expiry
and results. Never record session tokens, track metadata, notification contents, account data,
coordinates, phone numbers or HTTP payloads.

Local tests cover queue ordering, expiry, generation reset, presentation exclusion, independent
durable reservations and fake media transports. Emulator success cannot prove that the installed
Spotify publishes a session after cold launch. First hardware check: full restart, no manual
Spotify launch, qualified motion, then verify session availability and local sound. If absent,
stop at that documented limitation rather than adding an unapproved SDK or UI workaround.

Then test working-Yanosik skip, gate/cleaning contention, manual-menu protection, real sleep/wake
and a short stop without rearming. Use existing backup/signature/import procedures, never clear
radio data. Confirm audible radio output and Yanosik warnings, not just process presence.
