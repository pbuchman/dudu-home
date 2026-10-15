# Dudu Home 1.4.0-rc2 / code 26

## Recorded time on Home Dev

Day, range, daily-table and analytics period summaries show **Czas w samochodzie**,
based on the continuity of archived GPS observations. Stationary observations count,
including parking longer than ten minutes that separates journeys. This metric is
independent of journey elapsed time and map simplification. Existing radios need no
update or reprovisioning to supply the observations used by this feature.

Within each source segment, consecutive distinct measurement times up to thirty seconds
apart form observed intervals. Their union prevents overlap from counting twice. Larger
gaps and segment boundaries do not contribute time; neither edge is extrapolated.
Europe/Warsaw day boundaries preserve midnight crossings and daylight-saving changes.
Late uploads update the calculation from immutable raw records. Missing observations
are not proof that the radio was off, and the metric does not certify ignition time.

The API adds recorded seconds and derivation metadata. Analytics period observation
counts distinguish no data from zero confirmed duration. Existing journey metrics and
the ingest protocol retain their meaning. No new GPS measurements or cost estimates.

## Manual session on the radio

The passive **Gdzie jestem** screen adds **Czas sesji**, including fresh stationary GPS
observations. **Start/Pauza** continues or pauses the existing time and distance;
**Reset** clears both while preserving whether the session is active. A nonzero reset
requires confirmation. The separate Resume and End controls are removed.

Session state and totals persist together. Sleep, process restart and observation gaps
cannot add the intervening downtime. Reset invalidates older queued work. Existing
distance is retained on upgrade; a session without historical duration displays
**Czas od aktualizacji** until Reset. Sudden power loss may lose measurements since the
last completed checkpoint.

Manual session controls never alter the independent Routebook archive. Fresh position
observations rejected for distance can still confirm local session time, so manual
totals and automatic archive summaries need not match. The movable location card and
gate/cleaning/media priority are preserved. No second GPS subscription or service.

## Delivery and acceptance

One merged source revision identifies Android, backend and UI. Local verification,
PR checks, merged-main checks and tag checks are separate release gates. The current
evidence is recorded in [VERIFICATION.md](VERIFICATION.md); final CI and deployment
references belong on the release page and private artifact receipt.

Back up database, configuration, source and runtime images before Home Dev deployment.
Preserve device binding, credentials and every raw observation. Roll back application
components without overwriting newer measurements with an older database backup.

Publish source only. Keep the APK, signing key, configuration, raw GPS and live screenshots
private. Physical radio installation and observed GNSS/sleep/wake acceptance remain in
the separate radio-update workstream. This release does not incorporate the unrelated
street-stability working tree.
