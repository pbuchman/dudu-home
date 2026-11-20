# Application gallery

These are emulator screenshots of the Android app using invented data. The gallery records
rendering, the location-card tap and a controlled HTTPS test. Physical DUDU7 acceptance is
tracked in the [verification ledger](VERIFICATION.md).

## Current 1.5.1 gallery

Captured at 2000 × 1200, 240 dpi, pl-PL app locale and font scale 1.0. The menu, street card
and passive trip screen use the standard source build of **1.5.1 / code 38**. The three card
images use an invented street plan drawn by the test APK. The full screen followed a tap on
the scrolling row. The trip names and totals are synthetic renderer inputs.

![Dudu Home menu with the Test internetu shortcut](images/1.5.1/home-speed.png)

| Speed test in progress | Completed speed test |
| --- | --- |
| ![Running measurement labeled Pomiar syntetyczny · serwer lokalny](images/1.5.1/speed-running.png) | ![Result labeled Pomiar syntetyczny · serwer lokalny](images/1.5.1/speed-result.png) |

The speed screens show real WebView measurements against a controlled loopback HTTPS server.
The fixture app adds emulator-only trust for the temporary certificate. Instrumentation
replaces the provider label with **Pomiar syntetyczny · serwer lokalny** in both captures.
The values describe the fixture run. The [manifest](images/1.5.1/manifest.json) records the
installed standard and fixture APK hashes separately, along with the base commit, working-tree
status, build and capture source digest, emulator build fingerprint and PNG hashes.

| Two fitting street rows | Full passive view after tapping the scrolling street |
| --- | --- |
| ![Static two-line street above a synthetic map](images/1.5.1/street-two-line.png) | ![Full passive screen showing the complete long street](images/1.5.1/street-full-after-tap.png) |

| Same overflowing street, first frame | Same overflowing street, later frame |
| --- | --- |
| ![First marquee frame with fixed first row](images/1.5.1/street-marquee-a.png) | ![Later marquee frame with the second row further along](images/1.5.1/street-marquee-b.png) |

Run the changed-views capture on a dedicated Android API 36 emulator:

```sh
python3 scripts/capture-readme.py emulator-5554 --release
```

The runner builds and installs the app and test APKs, clears their emulator data and applies
the capture settings. It requires the normal Android build environment, OpenSSL and an
emulator with validated Wi-Fi. GPS permission stays off. It compares screenshot pixels
across street frames, verifies the tap-through and checks each PNG's dimensions and each
installed APK hash. The output is in `build/ui-checks/gallery-1.5.1`. Review all pixels
before copying captures into `docs/images/1.5.1`.

## Historical street layout prepared for 1.3.0-rc2

These reviewed synthetic captures show the October 9 layout. A long street was readable
without tapping; extreme overflow exposes **Pełna nazwa**; the full passive screen retains
unlimited lines. They were captured during implementation before the version bump, using
`1.3.0-rc1` / code 21 with local changes over `c04a686`. The [capture manifest](images/cancellation/manifest.json)
records the exact APK hash and dirty-tree provenance. They are not captures of a code 22
rebuild and do not prove physical radio readability or touch behavior.

| Wrapped long street | Overflow with full-name action |
| --- | --- |
| ![Full invented long street in location card](images/cancellation/trip-overlay-long-name.png) | ![Invented extreme street name with visible expansion button](images/cancellation/trip-overlay-overflow.png) |

![Full passive street screen with invented long names](images/cancellation/trip-long-names.png)

**Anuluj tę próbę** is now available for detection, queued and running work, including a
notification fallback. The current control behavior is documented in [PROGRESS_UI.md](PROGRESS_UI.md)
and covered by synthetic instrumentation. The older automation screenshots below predate
that control and should not be interpreted as the current cancellation UI.

## Historical full-app gallery: 1.2.0-rc1

The remaining images are the earlier full-app baseline, including its old two-line street
overlay and observational automation banners. Captured at 2000 x 1200, density 240, Polish
locale and font scale 1.0. The [historical capture manifest](images/current/manifest.json) records
its exact APK hash, base commit and dirty-tree flag. The original PNGs and manifest remain historical. To generate the same broad set of fixture
screens with the current app, run `python3 scripts/capture-readme.py emulator-5554` without
`--release`. Its output in `build/ui-checks/gallery` reflects the current implementation.

### Historical home and navigation

#### Home complete

![Home complete](images/current/home-complete.png)

#### Navigation chooser

![Navigation chooser](images/current/navigation-chooser.png)

#### Navigation empty all

![Navigation empty all](images/current/navigation-empty-all.png)


### Historical gate

#### Gate executing

![Gate executing](images/current/gate-executing.png)

#### Gate success

![Gate success](images/current/gate-success.png)

#### Gate error

![Gate error](images/current/gate-error.png)

#### Gate cooldown

![Gate cooldown](images/current/gate-cooldown.png)

#### Gate setup empty

![Gate setup empty](images/current/gate-setup-empty.png)


### Historical cleaning and setup

#### Cleaning accepted

![Cleaning accepted](images/current/cleaning-accepted.png)

#### Mop accepted

![Mop accepted](images/current/mop-accepted.png)

#### Routine uncertain

![Routine uncertain](images/current/routine-uncertain.png)

#### Robot setup empty

![Robot setup empty](images/current/robot-setup-empty.png)

#### Settings menu

![Settings menu](images/current/settings-menu.png)

#### Media access

![Media access](images/current/media-access.png)


### Historical automation and media

#### Detection departure

![Detection departure](images/current/detection-departure.png)

#### Detection return

![Detection return](images/current/detection-return.png)

#### Detection cleaning

![Detection cleaning](images/current/detection-cleaning.png)

#### Detection yanosik

![Detection yanosik](images/current/detection-yanosik.png)

#### Yanosik requested

![Yanosik requested](images/current/yanosik-requested.png)

#### Yanosik already running

![Yanosik already running](images/current/yanosik-already-running.png)

#### Spotify resuming

![Spotify resuming](images/current/spotify-resuming.png)

#### Spotify playing

![Spotify playing](images/current/spotify-playing.png)


### Historical trip session

#### Trip off

![Trip off](images/current/trip-off.png)

#### Trip active

![Trip active](images/current/trip-active.png)

#### Trip paused

![Trip paused](images/current/trip-paused.png)

#### Trip finished

![Trip finished](images/current/trip-finished.png)

#### Trip no map

![Trip no map](images/current/trip-no-map.png)

#### Trip no gps

![Trip no gps](images/current/trip-no-gps.png)

#### Trip long names

![Trip long names](images/current/trip-long-names.png)


### Historical background and priority

#### Trip overlay

![Trip overlay](images/current/trip-overlay.png)

#### Trip notification

![Trip notification](images/current/trip-notification.png)

#### Trip automation priority

![Trip automation priority](images/current/trip-automation-priority.png)

The navigation hero includes a single destination, a multi-destination group and an empty slot. The chooser and all-empty screen cover their alternate states. Settings and setup images cover entry to private configuration; the automated installer test verifies encrypted import without publishing private configuration screens.
