# Dudu Home 1.5.0

Stable version **1.5.0 / code35** promotes the application behavior of 1.5.0-rc1/code34.
This change updates version metadata and release documentation only. The RC records remain
in [1.5.0-rc1](RELEASE_1_5_0_RC1.md) and [RC5](RELEASE_1_4_0_RC5.md).

## Included behavior

A successful own automatic departure call continues with **Full Cleaning → Yanosik → Spotify**,
including while stationary at the gate, without another standstill or outbound checkpoint.
Cleaning has independent cancellation and a daily Europe/Warsaw reservation committed only
at send admission. Unsent skips, cancellation, expiry and missing configuration leave the day
available for a later genuine departure; sent or uncertain requests keep the reservation.
Automatic failures/skips have bounded feedback before still-live media can continue.

The release retains the reviewed street/schema3 map support, compact panels, window layouts
and private bounded diagnostics. Manual Cleaning/Mop, calling safety, independent media
reservations, manual UI/Maps priority and existing queue limits remain unchanged.

## Hardware acceptance and attribution

On **2026-10-27 the owner confirmed both scenarios on the installed RC1/code34**:

| Scenario | Owner-confirmed observation |
| --- | --- |
| Full automatic departure | Successful gate call, observed robot start, Yanosik and audible Spotify in sequence. |
| Later departure on the same day | Cleaning was skipped by the daily quota, with no additional cleaning. |

The RC installation verified the exact code34 artifact, preservation of the existing map,
configuration/navigation and safety/session state, and healthy monitoring. Owner observations
are separate from installation checks and earlier emulator/fake-transport tests. No new raw
hardware logs, recordings or broader acceptance campaign are asserted by this promotion.
Observed robot start does not mean completed cleaning, and call success does not detect
ringback, controller reception or physical gate opening.

Code35 has not yet been installed on the radio. Acceptance of the unchanged code34 behavior
is the basis for stable promotion; it is not a performed code35 installation or device test.

## Verification and remaining boundaries

Stable debug/test-APK builds and lint passed with JDK17/SDK36; the built APK reports
code35/version1.5.0. Results and earlier synthetic coverage are recorded in
[VERIFICATION.md](VERIFICATION.md). Review, privacy and final PR/main/tag CI gates must pass
before publication. This is source-only distribution: no public APK, map, private configuration,
credentials, signing material or runtime captures.

The RC update required private operator compatibility handling for firmware process/dump
formatting, maintenance observer processes and accessibility-probe availability. This release
does not fix generic installer tooling. Unobserved cancellation, two-phone/firmware/OTA,
repeated wake and location-accuracy cases remain explicit in the verification ledger.
No backend deployment, map build, routine change or new physical action is part of this release.
