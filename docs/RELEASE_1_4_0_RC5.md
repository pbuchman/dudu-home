# Dudu Home 1.4.0-rc5 - departure continuation

After a successful automatic departure gate call, Dudu Home queues Yanosik and Spotify
without another standstill or movement countdown. This also works while waiting at the gate.
The trigger is confirmed completion of this app's call, not physical gate opening.

## Preserved safeguards

- Wait for call cleanup, the five-second result screen, fresh location and available UI.
- Keep gate/cleaning priority and ten seconds after a Yanosik launch request before Spotify.
- Keep cancellation, manual Maps preference and independent durable send reservations.
- Reject repeated/stale callbacks and never replay work after reset or process restart.
- Manual and return gate calls do not gain automatic continuation; no Binder changes.

## Delivery and limitations

Version code 32. Source-only prerelease based on public RC3; separate local street/layout
candidates are excluded. No public APK, configuration, map, signing key or raw device evidence.
No backend deployment or data migration is needed. Verification is recorded in VERIFICATION.md.
Physical radio installation, uninterrupted-departure behavior, audible playback and no-repeat
behavior after a traffic stop remain pending. ADB was unavailable during initial preparation.
A later update must inspect installed version/features and signing identity, preserve data/maps
and avoid replacing a newer local feature set with the older public baseline.
