# Dudu Home 1.4.0-rc1 / code 25

## Release scope

Detailed journey analysis belongs to the private Routebook application on Home Dev.
The Android change is limited to the current-location card: drag, hide, explicit restore
and persistent bounded position/visibility. Hiding the card does not stop measurement.
Existing GPS collection, immutable upload payloads and the offline queue remain unchanged.

Routebook adds an observed journey diary, moving/stopped/unknown time, completeness,
speed timeline and map replay, daily/weekly/monthly summaries, named places and visits,
and directional comparisons between named endpoints. Cost estimates, extra GPS fields,
map matching and radio-side statistics are outside this release.

The private analytics contract is [ANALYTICS.md](../routebook/contract/ANALYTICS.md).
Long observed parking or observation gaps separate journeys; gaps never become parking
or geometry bridges. Missing evidence remains visible. Existing v1 ingestion and map
responses remain compatible. Names are private backend metadata, not raw point changes.

## Delivery and acceptance

One merged source revision identifies Android, backend and web UI. Source publication
contains no APK, map database, runtime configuration, signing key or actual location data.
The signed APK and its provenance are prepared privately. Code 24 belongs to a separate
local street-stability candidate; this release does not incorporate that uncommitted work.

Release gates are local Android/Routebook/privacy verification, PR checks, merged-main
checks and tag checks. Backend/UI deployment follows a verified database/config/source
backup and preserves the existing device binding, credentials and raw archive. Existing
radios continue to use v1 ingestion. Production smoke tests never insert synthetic points.

The separate radio-update thread owns physical installation. This release does not
install on the radio or change its configuration. Physical panel touch, GNSS and vendor
sleep/wake acceptance remain distinct from emulator, browser and server evidence.

Local integrated Android, backend/PostGIS, browser and HTTPS delivery checks passed;
see VERIFICATION.md for measured coverage and limits. Final PR/main/tag CI references,
merged source revision and Home Dev deployment outcome are recorded on the release page.
Private backup and APK receipts remain outside the public repository.
