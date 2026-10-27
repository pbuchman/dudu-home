# Dudu Home 1.5.0-rc1 - full departure continuation

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
Each deadline starts at enqueue; cleaning expiry never revives media whose own deadline expired.

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
Only source prereleases are published; the private APK, maps/configuration/keys are excluded.
Stable 1.5.0 requires physical departure/robot/audio/repeat-limit acceptance and final CI.
No backend deployment, map build or private routine change is included.

## Verification and physical acceptance

Current local/CI evidence is recorded in VERIFICATION.md. Build and mocks do not verify
Roborock cloud acceptance, physical robot motion or vendor Bluetooth. ADB was unavailable
during preparation, so no installed version or completed update is asserted.

Acceptance requires a real departure without an initial GPS standstill: one successful gate
call, one cleaning request and observed robot start, Yanosik and audible local Spotify PLAYING.
A later same-day departure must show the quota message without a second cleaning send.
Do not reset daily protection to force testing. Stable 1.5.0 receives a new monotonic code
(default35 if no further RC) only after this acceptance and green final-commit checks.
