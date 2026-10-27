# Release readiness - 2026-10-15

## Current 1.5 gate

See [1.5.0-rc1](RELEASE_1_5_0_RC1.md). Source candidate publication is permitted after
review/privacy/tests/CI; stable 1.5.0 remains blocked on physical departure, observed robot
start, audible local Spotify and repeated same-day departure acceptance. Earlier evidence
below does not establish these new checks. No backend deployment or map build is included.

## Current: 1.4.0-rc1 / code 25

Detailed Routebook analytics run on Home Dev; Android changes only the location panel.
See [scope and delivery](RELEASE_1_4_0_RC1.md), [analytics contract](../routebook/contract/ANALYTICS.md)
and VERIFICATION.md. Existing radios retain ingestion v1 and their current provisioning.
Versioned Android build/lint and synthetic emulator panel/safety checks passed locally.
Combined backend/PostGIS, UI/browser, analytics acceptance and actual Android HTTPS
integration checks passed. Final privacy and PR/main/tag gates are enforced before release.
Home Dev deployment requires a verified private backup and preserved archive/configuration.
Radio installation belongs to the separate update thread; this candidate does not certify
new physical panel interaction, driving or vendor wake behavior.

## Historical: 1.3.0-rc3 / code 23

The candidate adds early three-second movement in any direction, independent persisted
parking opportunities, schema-2 locality evidence and private rejection diagnostics.
Exact conditions and limits are in [release notes](RELEASE_1_3_0_RC3.md), FUNCTIONAL.md and
WHERE_AM_I.md. Cleaning/return thresholds and all phone safeguards remain unchanged.

Code-23 build/lint, detector/progress/trip/parser, diagnostic/installer/navigation/privacy,
Routebook and full emulator checks passed; synthetic backup/update/import/quota preservation
and overlay visual checks passed. Final incremental SafetyChecks after failed-write latch
hardening passed. Extended exact consumed departure-cycle preservation across backed-up
update/import and process restart passed. The national schema-2 pack completed from source
timestamp 2026-09-27T20:23:36Z; full validation, atomic Android 36 emulator installation and
sampled national lookup passed. Private read-only diagnostic collection also passed; the
synthetic stopped state did not verify a live monitor dump. See VERIFICATION.md for measurements.

[PR #10](https://github.com/pbuchman/dudu-home/pull/10) initial commit `0e43336` passed PR
and branch CI, including actual PostGIS/browser checks that were skipped locally. Enforce
final PR, merged-main and prerelease-tag gates separately and record final results on the
release page. Prepare the final merged-source signed APK/hash manifest privately after
merge; map/manifest and installation/diagnostic documentation stay outside public assets.

No current radio ADB access or installed version is confirmed. A backed-up same-signature
update, two real wake cycles, good GPS at parking, three-second forward/reversing departure,
restart/stop/bad-fix/gap controls, genuine return/rearm and city/village/street acceptance
remain pending. Preserve previous unresolved RC2 cancellation and Routebook acceptance;
a green local/CI run does not resolve them. Historical stable 1.1.0/code 18 is the last
recorded accepted radio version, not a fresh installed-version observation.

## Historical 1.3.0-rc2 / code 22

Readable street wrapping/full-name expansion and current-attempt cancellation introduced in
RC2 remain included. Its versioned build/lint, synthetic/emulator and privacy checks passed
as recorded in VERIFICATION.md. Those results do not validate RC3. Real touch/overlay,
notification fallback, own-call and HTTP cleanup cancellation, media startup and qualified
rearming acceptance remain pending. Source-only delivery includes no APK/maps/private assets.

## Historical 1.3.0-rc1 / code 21, combined test baseline

Includes all merged Android features and the former local Routebook collector, backend,
map UI and deployment tooling. Wispr Flow remains a separate installation/integration task.
See INTEGRATED_RELEASE.md for source inventory, validation and sequential radio acceptance.
This is a test candidate, with GPS/wake and real authenticated delivery still pending.


## 1.2.0-rc1 / code 19

PR #7 is merged into main at `39874ef`. That historical package retained
`1.2.0-rc1` / code 19; it is superseded by code 21 above.
Routebook and Wispr Flow were not included in that revision.
See [the cumulative release handoff](RELEASE_MAIN_20261006.md).

Historical pre-merge verification: Build/lint, local emulator and update/import checks passed; see [verification](VERIFICATION.md) for measured offline-index results and exact limitations. The full Poland pack is prepared outside the repository. Both CI checks passed for application commit `8d226ed`. The bounded unattended installer then stopped during discovery because no reachable authorized radio was found. No radio update or configuration change was attempted. The prepared APK hash and application/evidence commit distinction are recorded in VERIFICATION.md. A successful emulator run does not establish radio installation, driving accuracy or sleep/wake acceptance.


## 1.1.0 / code 18

The owner confirmed existing functionality and requested stable 1.1 after verifying the
third configured destination on DUDU7. This explicit acceptance supersedes the historical
blanket final-tag hold below. The third-target check and normal build, test, CI and privacy
gates must pass before publication. Build/lint, full emulator, installer and private-data
checks passed. Backed-up code 18 installation and APK hash match, private import, three-row
UI, resolved Maps destination/address and active guidance passed on DUDU7. PR #6 was merged and stable v1.1.0 was published after CI passed. No APK or private assets are released.

The historical physical checks below remain unobserved individually, unless newer evidence
in VERIFICATION.md says otherwise. General owner acceptance is not proof that every wake,
journey, audio or physical-effect test was performed. These remain documented limitations.

## Historical 1.0 candidate assessment


Historical candidate: **1.0.0-rc4 / versionCode 17**, adding private destination groups.
Installed on DUDU7 with a verified private import and physical modal check. Real launches
of the new destinations remain pending because existing Maps guidance was preserved.
See VERIFICATION.md for build, emulator, installer and hardware results.

The preceding **1.0.0-rc3 / versionCode 16** introduced a gate-area barrier before
generic media motion. Its code is included in rc4; physical gate-area acceptance is still
pending. All physical rc2 evidence below remains historical and does not certify that change.
In addition to the remaining checks below, verify that manoeuvring near home does not start
the media countdown before a successful gate call, and that ordinary driving away from home
still starts the media sequence. A skipped/failed call must not silently release the barrier.

Previous candidate: **1.0.0-rc2 / versionCode 15**. Application source was merged to main at
`694adb5af2d8d9a427e0ff75be2ce7bb2fe61878` through PR #3. PRs #1 and #2 were already merged.
The fresh merged-main build is byte-identical to the APK installed on DUDU7. Subsequent
release-preparation documentation does not change that application artifact.

**Final 1.0 acceptance is incomplete.** Prepare a source-only candidate draft; do not publish
a final 1.0 release until the remaining physical checks below are recorded. No APK, signing
key, real configuration, device address, raw log or actual destination capture is a release asset.

## Checks completed against the merged application

| Check | Evidence |
|---|---|
| Build and lint | Debug APK, instrumentation APK and lint passed in a fresh checkout |
| Merged-main CI | [Build and privacy passed](https://github.com/pbuchman/dudu-home/commit/694adb5af2d8d9a427e0ff75be2ce7bb2fe61878/checks) |
| Detector and scheduling | Stationary/quality/direction controls, priorities, expiry, presentation locks and generation checks passed |
| Recorded-route regression | Eight private recordings plus synthetic input: identical events and flags over 22,644 home and 20,000 motion samples |
| Private tooling | Four navigation, seven private-tool, five fresh-installer and one privacy-scanner test methods passed |
| Android emulator | Full safety, navigation, document import, media-priority, progress UI and first-run/no-dial checks passed |
| Update/import | Actual installer tested on an emulator: mismatched phone refused, signature/backup verified, encrypted import and quota preserved across restart |
| Privacy | Tree/history denylist passed; manually reviewed historical ordinary-word match. Candidate APK ZIP/DEX scans passed during rc2 verification; merged-main APK is identical |
| Physical full boot | Boot counter increased; wake entry and boot receiver restored monitoring without opening the menu. Fresh GPS and cold baseline zero followed |
| Manual gate on code 15 | One own dial, outgoing, five-second delay, hangup and success; menu restored. Physical gate movement was not independently observed |
| Manual Full Cleaning on code 15 | One UI request returned cloud ACCEPTED; menu restored and automatic daily quota unchanged. Physical robot movement was not independently observed |
| Maps | Three tile guidance checks passed in rc1; corrected address target and retained other slots passed on installed rc2 |

The radio was not given mock locations, injected wake counters, cleared quotas or a synthetic
ignition signal. No robot stop/pause/dock command was issued. The standalone Mop check remains
the historical hardware result in VERIFICATION; it was not repeated after the cleaning request.

## Physical checks still required for final 1.0

1. **Real sleep and wake twice:** after consuming the media opportunity on a real drive,
   switch ignition off and allow actual vendor sleep. On the first and second wakes, confirm
   automatic monitoring, increasing vendor cycle and exactly one fresh-motion opportunity.
   A short stop without a cycle increase must not rearm. A full reboot is a separate test.
2. **Music and foreground behavior:** verify audible Spotify output and working Yanosik
   warnings after motion. Check already-running Yanosik, manual pause, the open menu and a
   manually selected Maps route. Media must not override manual UI in the current cycle.
3. **Real departure, return and second departure:** observe the configured directional
   sequence and one action per event. Confirm physical gate/robot effects. The second
   same-day successful automatic departure must show the used-limit message and not repeat automatic cleaning, including after restart.
4. **Overlapping work and call protection:** observe gate/cleaning/media priorities on the
   device when real events overlap; verify number-save/restart cooldown and refusal to
   interfere with an existing call. Synthetic coverage is already green, but does not
   replace those hardware checks.

Debug access alone cannot create a real ignition cycle, drive the car, hear its speakers or
observe the gate/robot. These checks need the corresponding physical events and observations.
Capture diagnostics before manually opening the app after a wake. Keep all raw evidence in
the private archive and add only sanitized results to this ledger.

## Release contents

The candidate includes the gate/routine menu, local route automation, ordered Yanosik/Spotify
startup and three private file-configured Maps destinations, including optional address mode.
See [CHANGELOG](../CHANGELOG.md), [NAVIGATION](NAVIGATION.md) and [VERIFICATION](VERIFICATION.md).
The prepared release notes must retain the incomplete hardware-acceptance status. When all
remaining checks pass, make the final version change, rerun the affected gates and verify the
final installed artifact before publishing a source-only 1.0 tag.
