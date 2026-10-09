# Dudu Home 1.3.0-rc2 / code 22

Source-only prerelease candidate, prepared October 9, 2026. Includes the combined Android and
Routebook source baseline; no APK, maps, credentials, actual locations or private configuration
are release assets. The last recorded radio-verified stable release remains 1.1.0/code 18.

## What changes

- Long street names wrap at 32 sp beneath a 24 sp locality in a card at most 390 dp wide.
  Up to three lines remain readable without shrinking or scrolling. Measured overflow exposes
  **Pełna nazwa**; the full passive screen and expanded notification retain the complete name.
- **Anuluj tę próbę** is available during detection, queue wait and execution, in a bounded
  touchable banner/status screen and immutable service notification action. Stale and repeated
  clicks cannot affect a newer attempt. Yanosik and Spotify form one cancellation group.
- Cancelling before external sending discards that occurrence, pending steps and buffered
  diagnostics without durable history or quota consumption. The same signal stays suppressed
  in RAM until qualified fresh baseline evidence, then a new occurrence can start from zero.
- Already sent or uncertain commands retain reservations. Cancellation stops remaining local
  work and waits for actual cleanup; it does not undo robot commands or close launched apps.
  Own-call cleanup and the durable 60-second dial block remain. Existing pre-call conversations
  cannot be interrupted. Gate-area priority, manual Mop and non-user failure/expiry limits stay.

## Validation

The versioned code 22 app/instrumentation build, lint and complete emulator suite passed.
The unchanged implementation was also validated before the version bump (code 21):

- Debug app, instrumentation APK and lint passed.
- Detector/progress checks passed, including cancellation/rearming and 20,000 home plus
  20,000 motion sample equivalence outside the new cancellation path.
- Full emulator safety/UI and first-run no-dial checks passed at 2000 x 1200. Synthetic tests
  cover repeated/stale/process tokens, pre-send diagnostic discard, delayed reservations,
  partial media completion, cleanup, notification actions and qualified rearming.
- Street cards were checked at 390/260 dp and font scales 1.0/1.4. Attached-window assertions
  verify the full 76 dp overflow action remains visible after a measured-height fix.
- Trip checks passed; optional pyosmium parsing was skipped because that local dependency
  was unavailable. Routebook backend and physical GPS were not retested by this UI change.

The [verification ledger](VERIFICATION.md) records the evidence and the implementation APK
SHA-256. Three reviewed [synthetic screenshots](UI_GALLERY.md) have their own capture manifest
with base commit, dirty-tree flag and code 21 provenance; they are not code 22 rebuild captures.
The staged tree and history passed the private configuration denylist scan; generic schema
vocabulary matches were manually reviewed. PR and merged-main Actions must be green before
publication; their final run links are recorded on the GitHub release page.

## Pending hardware acceptance

Real DUDU overlay readability/touch, notification fallback, own-call interruption and idle
confirmation, robot transport cleanup, actual Yanosik/Spotify behavior and qualified rearming
on a real journey remain unverified. Existing GPS, authenticated Routebook delivery and vendor
sleep/wake acceptance remain separate. Emulator/CI success cannot prove physical gate opening,
robot movement, warnings or audible playback. No physical radio update is part of this release
preparation. Preserve private configuration and reservations during any separately authorized
update; follow [the installation runbook](OPERATIONS.md).
