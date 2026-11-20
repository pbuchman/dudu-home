# Dudu Home 1.5.1

Android package `com.pbuchman.duduhome`, versionCode 38. Source-only distribution under
MIT. The APK, signing key, private configuration, maps and operational records stay local.

## Changes

- **First movement after boot or wake.** An increase in the stored Android boot or DUDU
  wake counter allows movement detection to start from the first usable GPS fix. Outside
  the gate area, ten seconds of qualified motion and at least 15 m displacement can queue
  Yanosik and Spotify. Before an observed stop, displacement must also exceed the sum
  of endpoint accuracies. The parking and gate-first conditions still govern gate-related
  departures. [Detection rules](AUTOMATION_SEQUENCE.md).
- **Diagnostics and Routebook.** The local journals correlate GPS subscription and first
  callback, the motion decision and individual media requests. The collector evaluates
  GPS age at callback receipt and reports the GPS and power rows still pending after
  acknowledgements. The SQLite queue retains unacknowledged rows and retry evidence through
  process restart. The v1 ingest contract stays compatible.
  [Private diagnostic export](DIAGNOSTIC_JOURNALS.md) · [Routebook contract](ROUTEBOOK.md).
- **Test internetu.** The manual shortcut starts an HTTP latency, download and upload
  measurement over HTTPS on the radio's current default network. The bundled Cloudflare
  client has a 45-second attempt limit and up to 16 MiB of measurement payload per
  direction. The UI identifies the transport. Cancelling, leaving the activity or changing
  default networks ends the attempt. Automation retains priority and the test holds no
  action lease. [Functional contract](FUNCTIONAL.md#test-internetu-w-151).
- **Gdzie jestem.** The floating street card is up to 286 dp wide with a 75% opaque
  background, 18.75 sp street text and 16 sp locality. The first street row is static. A second
  row stays static if its text fits and uses native marquee when it overflows. Tap the address
  to open the full passive view. The independent ×, 350 ms hold-and-drag and saved placement
  continue to work within safe window bounds. [Trip and overlay contract](WHERE_AM_I.md).

## Evidence

The [current gallery](UI_GALLERY.md#current-151-gallery) shows the standard 1.5.1 menu and
location views on an emulator. The running and completed speed captures come from a separately
identified loopback TLS fixture app. The [verification ledger](VERIFICATION.md) records
release checks and their scope, with code-36 and code-37 candidate evidence retained separately.

Hardware observations from earlier versions retain their original attribution. Physical
code-38 installation, DUDU7 street readability and touch, and natural first-fix departures
need separate verification. The [guarded installation procedure](OPERATIONS.md) applies to
any later radio update.
