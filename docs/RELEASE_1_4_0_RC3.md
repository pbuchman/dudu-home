# Dudu Home 1.4.0-rc3 / code 27

Home Dev now separates recorded GPS time from corrected time in the car. A conservative
estimator excludes qualifying stationary recording tails after likely terminal parking.
Day, range, summary-table, analytics-period and trip/comparison views expose corrected time,
the original observation time and excluded time. Estimates and provisional archive tails
are labelled; late uploads can reverse a correction automatically. Raw routes/stops remain.

Waiting with an active radio, including traffic and ACC-off waiting, still counts. ACC OFF,
a dark screen and GPS disappearance alone do not prove that the radio stopped operating.
The exact rules and the independent precision target are in [PARKING_TIME.md](PARKING_TIME.md).
The release does not claim ninety-five-percent accuracy or direct measurement of occupancy.

Android adds passive power observations through a separate durable queue. The existing GPS
queue schema and v1 ingestion stay compatible with an older APK and backend. A new v2
observation envelope uses the existing authenticated endpoint. Failures of event delivery
cannot block GPS upload. The manual Start/Pauza/Reset session stays independent.

Both local timer gating and confirmed server corrections require an explicitly validated,
firmware-bound interpretation profile. Shipped defaults are diagnostic. The observer never
sends vendor control commands or changes ignition rearming, gate, cleaning or media rules.
Physical-radio installation and validation of these signals remain separately pending.

This update is an additive metadata migration. The owner approved deployment without a new
backup; preserve raw archive and provisioning, retain image/source identifiers, and roll back
application components without overwriting newer measurements. Home Dev delivery is
independent of the separate physical-radio update. Publish source only; APKs, private GPS,
firmware evidence, configuration and signing keys remain outside Git and release assets.

Verification and outstanding boundaries are recorded in [VERIFICATION.md](VERIFICATION.md).
PR, merged-main and tag checks are separate publication gates; final CI and deployment
receipts identify the exact delivered revision.
