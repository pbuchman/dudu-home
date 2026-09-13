# Automation progress: UI and background contract

Source version `0.5.5-local`, versionCode 12, progress based on `911d18b`. Java 17, native Views/XML,
no added runtime libraries, map editor, exporter, menu redesign or executor architecture.
Physical acceptance is separate from the local checks described here.

## Presentation

One top-centred banner, maximum 480 dp, at least 16 dp from available window edges.
WindowManager fits normal system bars; no fullscreen/layout-no-limits flags. Wrap-content
supports larger text. Title 22 sp, detail 18 sp, current platform font and dark background.
Sand identifies gate events, mint cleaning, blue navigation. Icon and text accompany colour.
No percentages, sound, buttons, flashing, focus or screen wake. An open, focused menu hosts
the same layout inline. Only one host owns it at a time.

| Detection | Title | Detail | Evidence |
|---|---|---|---|
| Departure | Wyjazd z domu | Potwierdzam ruch w stronę bramy | Qualified movement duration toward gate / existing 3 s requirement |
| Return before turn | Powrót do domu | Sprawdzam trasę powrotu | Supported road approach, reducing distance to junction |
| Return after turn | Powrót do domu | Potwierdzam dojazd do bramy | Junction sequence plus inward movement, then inbound checkpoint |
| Cleaning | Pełne sprzątanie | Potwierdzam wyjazd z osiedla | Consumed departure plus outward movement, then outward checkpoint |
| Navigation | Yanosik | Potwierdzam jazdę | Minimum of qualified duration and displacement requirements |

Return fills 0-30% as junction distance reduces from the existing 300 m approach boundary to
35 m. The inward stage fills 30-95% from junction-to-checkpoint distance down to the existing
35 m trigger radius, clamped to that range. These are geometric stages, not time remaining,
probability or intent. Only the unchanged return event produces 100% and immediate dispatch.
A traffic stop holds observed fill; departing the route cancels. Late GPS starts at the actual
stage rather than replaying a fictitious approach. Cleaning still uses the half-fill sequence.
Departure starts only after existing
displacement/direction gates; navigation uses the existing 10 s requirement. All fill values
come from the detector. Time passing in UI cannot increase them. Radio start alone gives no
prediction. Manual buttons bypass detection; Mop stays manual only.

No animation delays dispatch. Full fill means recognized event, not successful execution.
Gate/cleaning immediately use the existing full screen: success 5 s, errors until retry/close,
return to previously open menu or hide. Since 0.5.1 navigation launches normally and requests
the desktop once after a grace period (10 s since 0.5.4). Its request result then lasts 2 s and does
not claim service, warning icon or hazard-display confirmation. Brief target UI is acceptable.
Version 0.5.2 checks fresh notification presence before launch: detected work skips silently
without STARTED or HOME; unknown status produces a short skipped message, not a launch.

## Communication and ownership

`GPS evaluation -> DetectionProgress -> ProgressBus/ProgressModel -> one banner host`

`existing event -> HomeActions -> existing executor -> correlated action outcome`

- Immutable DetectionProgress: kind, detector-local run ID, phase, fill, monotonic validity
  and cancellation category, plus Stage NONE/APPROACHING_JUNCTION/APPROACHING_GATE. Stage survives
  model delivery and cancellation; the renderer selects copy without doing GPS maths.
  Its tracker is a side channel inside the same accept evaluation.
  Observers cannot affect returned events, persistent flags or detection thresholds.
- ProgressModel.State adds a process-local attempt/presentation ID, generation and display
  expiry. Candidate/action maps are separate; completed attempt history is bounded to 16.
- ProgressBus serializes delivery on the main thread, posting worker outcomes there. Old
  generations, older evidence IDs/timestamps, backwards action states and repeated terminal
  results are ignored. Observer failure cannot prevent action handling.
- Phases distinguish CANDIDATE, CONFIRMED, CANCELLED, REQUESTED, ACCEPTED, STARTED, SKIPPED,
  SUCCEEDED, ERROR and UNKNOWN. Executor success means its existing protocol result, never
  physical gate/robot observation. UNKNOWN preserves transport/lifecycle uncertainty.
- Reading, subscribing, recreating a menu or redelivering a snapshot never sends a command.
  Neither progress nor actions are restored from the journal after process restart.

HomeActions creates an attempt before checking existing guards and preserves daily cleaning
reservation before other checks. DISPATCH remains intent, not proof of execution. Valid token
delivery or a ready visible Activity acknowledges the request. Gate startup/HTTP worker
publishes STARTED. Completion reports independently of a closed view. View closure never
releases the shared Binder/HTTP lease early or reopens a screen on a late outcome.

An undelivered Activity token expires after the existing 5 s window, emits correlated
SKIPPED/UI_UNAVAILABLE and cannot execute when delivered late. No automatic retry. Diagnostic
IDs are counters, never authorizing tokens. Combined baseline categories stay combined in UI;
daily-limit-or-storage and busy-or-maintenance cannot be presented as a more specific certainty.

## Cancellation and concurrency

Evidence loss reports the available reason: stop, unreliable GPS, changed conditions, new
cycle, changed configuration or service stop. Cancellation lasts 2 s; a new valid candidate
may replace it sooner. Evidence reset creates a new run, never resumes the old one.
Missing UI updates expire presentation only, not detector evidence. A fresh snapshot may
restore presentation if the detector itself did not reset. The 250 ms presentation tick
only removes stale/expired UI; it never advances fill or sends actions.

Priority: gate, cleaning, navigation. Equal priority retains the current candidate; active
evidence replaces a terminal message at equal priority. Hidden candidates continue being
evaluated. Existing execution/result/error/configuration views and locks suppress the banner.
It does not masquerade as an Activity or change allowsExternalLaunch().

ProgressOverlay uses the existing grant and TYPE_APPLICATION_OVERLAY with FLAG_NOT_FOCUSABLE
and FLAG_NOT_TOUCHABLE. Alpha is capped at the system maximum obscuring opacity for
[touch pass-through](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE).
It never opens MainActivity for progress. Existing action delivery remains subject to
[background-launch restrictions](https://developer.android.com/guide/components/activities/background-starts).
Overlay creation failure is isolated, with at most one retry per 5 s, never an action retry.
Android or the foreground app can hide overlays on protected screens (including some system
settings). An attached window is not proof of visible pixels; never bypass that protection.

## Wake and diagnostics

The existing asynchronous DuduCycle read and initial cycleReady action gate remain. First,
repeated, rolled-back or unavailable cycles and ordinary UI visibility never rearm navigation.
Missing properties preserve cold-boot behavior. JourneySession's read-only availability
only filters presentation. A verified new cycle clears detector evidence and advances the
presentation generation. GPS disable/watchdog, configuration changes and teardown also
invalidate presentation. Late cycle results after teardown are ignored. Force-stop may skip
teardown; Android removes that process's window. This does not replace the ignition-task fix.

The existing two private journal files of about 128 KiB remain the only logger. Added safe
categories: PROGRESS_kind_phase_reason with ID, PROGRESS_RESET_reason with GENERATION,
ACTION_REQUESTED_kind and ACTION_kind_phase_reason with ID, ACTION_UI_EXPIRED and
PROGRESS_OVERLAY_UNAVAILABLE. Record state transitions, not each fill change. No positions,
streets, phone fragments, credentials or server payloads. UI never parses logs.

## Verification and remaining acceptance

- `python3 scripts/check-progress.py`: pure state checks and comparison against pre-UI source
  for 20,000 home and 20,000 motion samples. Requires Git history with 911d18b. Optional private
  TSV arguments compare recorded routes without printing coordinates or paths.
- `bash scripts/check-detector.sh`: retained departure/alternate parking, north/east return,
  through-road/reversal negatives, bad fixes, sustained motion and action-count checks.
- `scripts/run-emulator-check.sh`: retained gate/Roborock/navigation safety, host switching,
  overlay flags, cancellation, stale generation, observer isolation, 5 s nondelivery without
  dial, late cycle callback after stop and diagnostic rotation/input filtering.
- Synthetic screenshots are generated in ignored build/ui-checks. Reviewed copies belong in
  docs/images. The action screenshot is a renderer preview, without a call or HTTP request.

Hardware update: 0.5.4/code 11 is the last verified installation. Existing-Yanosik skip,
automatic desktop return after 10 s and actual warning overlays passed. Version 0.5.5 adds
typed cycle observations: baseline establishment never resets UI; only a verified increase
resets old progress. Its installation/first-wake test remains pending, as do early/inward
return presentation and remaining outbound/cleaning acceptance checks.
Verify real warning-overlay behavior, menu restoration and no duplicate
banner/actions. Do not repeat calibration without a specific evidence gap. Local success
does not establish physical acceptance or manufacturer wake recovery.
