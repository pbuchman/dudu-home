# Application gallery

These are real emulator screenshots of the production Views, rendered with deterministic synthetic fixtures. They demonstrate UI states, not successful physical calls, robot actions, driving or radio sleep/wake. No external action executors or online services are used by the capture scenarios.

Captured at 2000 × 1200, density 240, Polish locale and font scale 1.0. [Capture manifest](images/current/manifest.json) records the exact capture APK hash and its base commit plus an explicit dirty-tree flag. Later resolver-only performance changes do not change these Views. Reproduce with `python3 scripts/capture-readme.py --help` on an explicitly selected emulator.

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
