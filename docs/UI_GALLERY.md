# Application gallery

These are real emulator screenshots of the production Views, rendered with deterministic synthetic fixtures. They demonstrate UI states, not successful physical calls, robot actions, driving or radio sleep/wake. No external action executors or online services are used by the capture scenarios.

## Street layout added for 1.3.0-rc2

The following reviewed synthetic captures show the new layout: a long street is readable
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
its exact APK hash, base commit and dirty-tree flag. Preserve this evidence rather than
presenting it as a new capture. Reproduce with `python3 scripts/capture-readme.py --help`
on an explicitly selected emulator.

## Home and navigation

### Home complete

![Home complete](images/current/home-complete.png)

### Navigation chooser

![Navigation chooser](images/current/navigation-chooser.png)

### Navigation empty all

![Navigation empty all](images/current/navigation-empty-all.png)


## Gate

### Gate executing

![Gate executing](images/current/gate-executing.png)

### Gate success

![Gate success](images/current/gate-success.png)

### Gate error

![Gate error](images/current/gate-error.png)

### Gate cooldown

![Gate cooldown](images/current/gate-cooldown.png)

### Gate setup empty

![Gate setup empty](images/current/gate-setup-empty.png)


## Cleaning and setup

### Cleaning accepted

![Cleaning accepted](images/current/cleaning-accepted.png)

### Mop accepted

![Mop accepted](images/current/mop-accepted.png)

### Routine uncertain

![Routine uncertain](images/current/routine-uncertain.png)

### Robot setup empty

![Robot setup empty](images/current/robot-setup-empty.png)

### Settings menu

![Settings menu](images/current/settings-menu.png)

### Media access

![Media access](images/current/media-access.png)


## Automation and media

### Detection departure

![Detection departure](images/current/detection-departure.png)

### Detection return

![Detection return](images/current/detection-return.png)

### Detection cleaning

![Detection cleaning](images/current/detection-cleaning.png)

### Detection yanosik

![Detection yanosik](images/current/detection-yanosik.png)

### Yanosik requested

![Yanosik requested](images/current/yanosik-requested.png)

### Yanosik already running

![Yanosik already running](images/current/yanosik-already-running.png)

### Spotify resuming

![Spotify resuming](images/current/spotify-resuming.png)

### Spotify playing

![Spotify playing](images/current/spotify-playing.png)


## Trip session

### Trip off

![Trip off](images/current/trip-off.png)

### Trip active

![Trip active](images/current/trip-active.png)

### Trip paused

![Trip paused](images/current/trip-paused.png)

### Trip finished

![Trip finished](images/current/trip-finished.png)

### Trip no map

![Trip no map](images/current/trip-no-map.png)

### Trip no gps

![Trip no gps](images/current/trip-no-gps.png)

### Trip long names

![Trip long names](images/current/trip-long-names.png)


## Background and priority

### Trip overlay

![Trip overlay](images/current/trip-overlay.png)

### Trip notification

![Trip notification](images/current/trip-notification.png)

### Trip automation priority

![Trip automation priority](images/current/trip-automation-priority.png)

The navigation hero includes a single destination, a multi-destination group and an empty slot. The chooser and all-empty screen cover their alternate states. Settings and setup images cover entry to private configuration; the automated installer test verifies encrypted import without publishing private configuration screens.
