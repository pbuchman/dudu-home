# Combined main candidate: 1.3.0-rc3 / code 23

## Current change over the combined baseline

RC3 adds early three-second departure in any direction, persisted independent parking
opportunities, schema-2 locality boundaries/quality and private rejection diagnostics over
RC2/code 22. It retains full street wrapping/expansion and current-attempt cancellation.
Unsent user-cancelled work has no durable quota/history; sent operations retain safety
reservations and cleanup. See [release notes](RELEASE_1_3_0_RC3.md) for exact scope/limits.

The owner authorized source-only PR/checks/merge/prerelease. Code-23 build/lint, local
synthetic/emulator/tooling/Routebook and privacy checks passed as recorded in VERIFICATION.md.
Final incremental SafetyChecks after failed-write latch hardening passed; the extended
exact consumed departure-cycle preservation check across backed-up update/import and process restart passed;
two local PostGIS cases were skipped. PR, main and tag validation remain pending.
Prepare a signed local APK,
schema-2 Poland pack, matching hashes and installation/diagnostic instructions outside Git.
The map reuses source timestamp 2026-09-27T20:23:36Z; final measurements remain pending.
Physical ADB is unavailable, so no current radio version or RC3 update is confirmed. Two
wake cycles and city/village/drive acceptance remain pending. Source publication does not
establish radio installation, server deployment, pairing or a private configuration change.

## Historical consolidation and current installation candidate

The integration branch consolidates current main (including PR #7), the local Android
Routebook collector and the standalone Routebook backend, UI and deployment tools.
All older feature branches were compared against fetched main; their application changes
were already represented. The remaining UI worktree differences were historical documents.
The private predecessor, radio configuration, actual journeys, map database and keys remain
outside this repository. Third-party apps remain separately installed packages.

Main is the testing baseline after the integration PR merges. Build the APK from that exact
main commit and record source SHA, version, signing-certificate fingerprint and APK SHA-256
in the private release manifest. Source-only GitHub prereleases contain no APK or credentials.
Use the versioned code 23 artifact with its schema-2 pack. Code 22 is the preceding candidate;
do not use the earlier code 19 or detached code 20 artifacts as its replacement.

Included Android features: gate/Roborock actions and safeguards, gate-before-media priority,
Yanosik/Spotify, three navigation groups, trip distance, locality/street resolution and overlay,
and durable Routebook collection with newest-first upload and bounded backfill.
`routebook/` contains the server, private map UI, database schema, synthetic fixtures and
operator tooling. The backend is a separately deployed service, not embedded in the APK.

## Radio installation and passenger acceptance

1. Discover the authorized radio and check actual Android API level, installed package/version,
   signature, free storage, foreground navigation and idle call/action state. Refuse a version
   or signature mismatch before updating; do not clear application data or uninstall.
2. Back up the installed APK and private state. Preserve the entire ordered navigation config,
   gate cooldown, daily quota and consumed journey state using the existing updater in
   OPERATIONS.md. Use maintenance only while idle and finish by removing it.
3. Install the exact main code-23 APK. Transfer the separately stored schema-2 Poland index atomically after
   validating its manifest and sufficient temporary/final disk space. Verify installed APK and
   index hashes, import completion, config equality, permissions and monitor recovery.
4. Test the menu and trip lifecycle, wrapped street card/full-name expansion and notification.
   Check explicit cancellation before sending and after own execution admission, notification
   fallback, retained cleanup locks and fresh rearming evidence. Sent robot commands are not
   undone; use normal phone-app control if needed. During a passenger
   ride compare city/village/street names, component quality, movement distance and GPS quality
   against the route; verify three-second departure including reversing, used-opportunity
   protection and genuine-return rearm. Observe two real vendor wake cycles.
   Do not trigger gate calls or robot routines as incidental smoke tests.
5. Read Routebook's installation UUID privately. Check the existing server and private map
   deployment, back up its config, then bind that UUID with a dedicated token and an explicit
   HTTPS origin. Stage provisioning with scripts/provision-routebook.py and activate it on a
   safe new monitor instance. Preserve existing server history and credentials.
6. Verify fresh GPS arrives on the private map, then exercise a bounded internet interruption
   without losing the maintenance connection: pending grows, restores newest position first,
   drains without duplicates and keeps observation gaps visible. Inspect only sanitized counts
   in the public handoff. Do not equate an HTTP ACK with physical GPS accuracy.
7. Install/check Wispr Flow through its official distribution if the radio meets its Android
   requirements. Complete account/microphone/overlay/accessibility setup; disable optional
   training-data sharing. First verify Polish dictation into the already-focused context field
   and text review/paste. Do not automatically send the resulting message.
8. Inspect the actual steering-wheel event and available Wispr external actions. Map a genuine
   start/stop action only if supported and verify repeated press, stop, cancel and duplicate
   presses. Opening the app alone does not satisfy the one-button dictation goal. If no external
   action is exposed, record the limitation before implementing a separate bridge.
9. At a planned stop observe real sleep/wake, monitoring and dictation readiness. Report exact
   installed versions, passed checks and remaining hardware issues against this main baseline.

Account login, a physical button press and a spoken sample can require the passenger. The
rest of the supported installation is automated after authorized radio connectivity exists.
Do not promise unattended completion of unsupported external app actions.

## Wispr boundary checked on 2026-10-06

Official [Scratchpad documentation](https://docs.wisprflow.ai/articles/%39%36%31%38%32%33%37%30%38%32-using-the-scratchpad-to-save-and-edit-notes)
lists Mac and Windows, with a separate iPhone Notes experience, not Android Scratchpad.
Official [Android dictation instructions](https://docs.wisprflow.ai/articles/%35%39%30%36%31%39%38%31%32%39-starting-your-first-dictation-on-android)
require Android 13 or later and an internet connection and describe the floating Flow Bubble
in a focused text field. Hardware-button start/stop is not established by those instructions.
Wispr and new-context creation are not implemented by this release.

## Reproducible checks

From the repository root run assembleDebug, assembleDebugAndroidTest, lintDebug and the
existing safety/privacy scripts, including scripts/check-routebook.sh. From routebook run
npm ci, npm run build, npm test and npm run check:contract. CI also starts synthetic PostGIS
and runs browser/deployment tests. Emulator-only HTTPS acceptance is npm run test:integration
with a running emulator and the dedicated synthetic PostGIS instance described in the runner.
Results and hardware limitations are recorded in VERIFICATION.md.
