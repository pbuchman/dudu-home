# Ordered automation and local Spotify playback (0.5.6)

## Scheduling contract

`AutomationCoordinator` has explicit numeric priorities: GATE 0, CLEANING 1, YANOSIK 2,
SPOTIFY 3. Only already-qualified events compete; driving elsewhere never waits for home events.
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
