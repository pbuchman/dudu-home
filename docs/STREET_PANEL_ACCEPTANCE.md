# Street display and floating-panel acceptance

This source candidate changes retained presentation, typed unnamed-road evidence and panel
input handling. It does not establish physical DUDU7 road correctness, radio touch behavior,
GNSS reception or gate/controller acceptance. All checked-in names are invented.

## Contract and verification

| Case | Expected result | Evidence |
| --- | --- | --- |
| Ambiguous/unknown/missing road after confirmation | Plain confirmed name remains; raw quality stays uncertain | Host `StreetStabilityChecks`, Android session checks |
| First different named/unnamed candidate | Previous display remains | Host checks |
| Two distinct valid fixes | New road replaces the old display; two sources on one fix count once | Host checks |
| Confirmed unnamed road | Empty street row, locality remains; Photon cannot invent a nearby road | Host checks, schema-3 Android SQLite checks, native card layout |
| One noisy fix | No immediate display clear, no timer renewal | Host and Android session checks |
| More than 10 seconds without valid GPS | Clear place generation; returning GPS needs new confirmations | Android session checks with deterministic clock boundary |
| Provider/session boundary and old callback | Clear display; stale generation cannot revive it | Android session checks |
| Actual presentation/reset trace | Coalesced surface/visibility/labels with exact snapshot fix; GPS expiry/new boundary use new generation and null fix | Native overlay/session journal assertions |
| Raw quality versus retained label | `street_quality` remains raw; `display_street_quality` reports retention | Android session diagnostic assertion and private replay |
| Location card sizing | 286 dp/min112dp;25sp street,16sp locality;adjacent48dp×;no separate handle;natural wrap/overflow | Android native measurements at font scales 1.0/1.4 |
| Whole floating panel | Hold non-button area 350 ms, visible dimming, horizontal movement only | Native location/automation input checks |
| Buttons and release | Native button-origin stream retained; non-button hold crossing Cancel cannot cancel; 349 ms horizontal movement is inert, 350 ms owns release | Native input checks plus existing cancellation suite |
| Bounds and restart | Whole card reachable; relative position survives restart, resize and temporary suppression | `tripOverlay seed/restore/visible` at multiple viewport sizes |
| Location × | Only hide preference changes; trip state, generation and nonzero total remain | Focused native overlay checks |
| Automation banner | 350dp/min91dp,48dp Anuluj; separate position; inline menu fixed; cancellation token unchanged | Native progress/cancellation checks |

Run the host suite with `bash scripts/check-trip.sh`. The existing emulator-guarded
`SafetyChecks` runner executes the native matching, session, cancellation, layout and input
checks. Its `tripSession` mode runs matching/session tests; `tripOverlay seed`, `restore`,
then `visible` verify the location card across force-stops. Use a dedicated emulator, verify
`ro.kernel.qemu=1`, and always pass its explicit serial and ADB server. Keep GPS permission
disabled for the focused overlay modes. As in `scripts/run-emulator-check.sh`, grant
POST_NOTIFICATIONS on API33+ before the complete suite; its notification-cancellation fixture
requires a delivered notice. These checks never feed a fix to automation or dispatch an action.

The current local run passed build, Android-test compilation, lint, host trip checks, the
complete native SafetyChecks suite and all three focused overlay phases (including viewport
resize). Both computer OSM parser suites passed with the optional osmium dependency enabled
(5 and 7 tests, no skips). Exact output, private presentation/reset trace and synthetic captures
are recorded with the maintainer's private implementation receipt. Source-only evidence does
not imply an installation on the radio.

## Retained evidence replay

A private replay runs the actual previous/new Java stabilizers over the same final schema-3
road-match results and event order. It reuses map results; it does not rematch altered geometry.
Raw road scores and ambiguity outcomes are unchanged. Explicit unnamed roads become typed
`UNNAMED` evidence instead of a made-up display string.

| Selected records | Old blank / retained | New unknown blank / unnamed / retained | Text changes old → new |
| --- | --- | --- | --- |
| 721 live records, actual GPS bearing | 8 / 11 | 1 / 5 / 15 | 11 → 1 |
| 473 archive records, approximate derived bearing | 93 / 0 | 9 / 3 / 83 | 39 → 13 |

Blank counts distinguish intentionally hidden unnamed-road rows from missing names. The replay
retains available process/generation or archive-segment boundaries. It does not infer GPS loss
from missing selected resolver rows; the controller's freshness/reset rules have separate native
tests. Fewer display changes do not prove a better raw street match or ground truth. Archive UI
was unobserved; physical road/gesture/persistence and route-background acceptance remain pending.
