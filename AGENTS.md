# Dudu Home contributor instructions

Read README.md, docs/TECHNICAL_NOTES.md and docs/DECISIONS.md completely before edits.
The owner authorized public source publication only to pbuchman/dudu-home, under MIT.
Never copy history from the private predecessor. Never publish APKs, credentials, private
configuration, actual phone numbers (including suffixes), real street names or coordinates,
raw recordings, private screenshots, signing keys or device-specific connection addresses.
Before every commit/push, inspect the staged tree and run the public-tree privacy check.
Private local denylist verification must also be performed by the maintainer before publication.
Do not copy the predecessor's calibration module into this repository.

Preserve Java 17, platform Android Views/XML and local signing key. The owner approved
the new application ID com.pbuchman.duduhome with explicit migration from the old package.
The owner authorized radio installation and source publication on 2026-09-09 after verification.
Do not publish private configuration or APKs; outstanding wake checks must stay explicit.
No AndroidX, dependency injection framework, analytics or fake DUDU backend. Routebook is the explicitly authorized GPS archive
backend in routebook/; it never intermediates gate or Roborock actions.
The owner explicitly authorized direct native HTTPS to Roborock for Full Cleaning and manual-only
Full Mop. No location hook may dispatch Mop. Missing Mop configuration must never fall back to
Full Cleaning. Success screens now last five seconds; errors remain user-dismissed.
No Python runtime on Android, MQTT, status polling, stop/pause/dock, Google Home or
Home Assistant dependency. Computer-only Python bootstrap is read-only after email login.
Use the raw SYU Binder implementation. No ACTION_CALL, Accessibility, UI dialing or radio SIM.
Register callbacks before dial; dial only on fresh idle. Never hang up a pre-existing call.
One dial per attempt, no protocol fallback after dial, persistent 60-second reservation before
transact, singleTask plus process lock. Success requires own dial, outgoing and later idle.
No assertion of ringback, controller reception or physical gate opening.
Do not launch the baseline app during installation: launching with a saved number places a call.

Verify with ./gradlew assembleDebug lintDebug and scripts/run-emulator-check.sh.
Hardware verification is separate from emulator checks. Do not claim unavailable radio tests.
The owner authorized publication of the current development source on 2026-09-08 after the
privacy/documentation audit. Public main may include automation with explicitly pending hardware
checks; the baseline remains tagged. The owner confirmed existing functionality on September 27
and explicitly authorized stable 1.1 after verification of the third navigation destination.
This supersedes the earlier blanket final-tag hold; publish only after that device check and
normal build/test/privacy gates pass. Preserve historical unobserved journey/ignition checks
as limitations, not completed tests. Never publish APKs.
Preserve unrelated dirty files and the private predecessor without deleting or rewriting history.

Current functional contract: docs/FUNCTIONAL.md. Architecture/auth: docs/ROBOROCK.md.
Design/assets: docs/DESIGN.md. Yanosik progress/blocker: docs/YANOSIK.md.
Migration and feature boundaries: docs/MIGRATION.md and docs/PACKAGES.md.
Do not wire an unverified screen/GPS-gap signal to ignition rearming or claim wake support.
Installation/recovery: docs/OPERATIONS.md. Evidence and outstanding radio checks:
docs/VERIFICATION.md and docs/LOCAL_DEVELOPMENT.md. Keep these current with every material change.
Automatic cleaning reserves one attempt per Europe/Warsaw day BEFORE execution; failure, missing
configuration or an expired queued action consume that opportunity. Since 0.5.6 a busy gate
defers cleaning in a bounded in-memory queue; no persisted queue or automatic retry. Manual
cleaning remains independent. Saving any configuration must never execute an action or reset quota.
Preserve shared action exclusion until actual Binder/HTTP cleanup, not just until the UI closes.
Stage complete private configuration outside Git; compare radio phone and APK signature before
update, back up with checksums, keep maintenance until one-time import completes. Do not print
headers, account payloads, routine IDs, connection addresses or raw firmware logs.
Do not bypass a browser security refusal to store secrets. Google Password Manager backup is
not completed; the local owner-only credential bundle is the working source of configuration.

## Configuring navigation without disclosing places

Follow docs/NAVIGATION.md and docs/OPERATIONS.md. Resolve PRIVATE_DIR locally outside Git
(0700; JSON files 0600). Configure only its full navigation.json; preserve unrelated slots.
Schema 2 uses group label/icon and 0..12 destinations per slot. Zero is empty, one launches
Maps directly, multiple opens a chooser. Schema 1 remains compatible; preserve navigate_by.
Validate privately, install code 17+ before importing schema 2, then import and verify on the
radio. Import replaces all slots and never executes an action. Back up before installation.
Never embed actual labels, address fragments, coordinates or Maps links in public tests,
docs, PR text, screenshots, CI inputs or APKs. All examples/test data must be invented.
Run the scanner locally against the entire private navigation file, including all group
members; manually review pixels and ambiguous generic-word matches. See NAVIGATION.md for
exact validation/import commands and rollback order. A private mockup is not a public image.
For an explicitly authorized automatic installation, proceed when verified ADB is available
without asking about driving/parking. Check for active calls/actions before stopping the app;
wait for cleanup instead of interrupting a call. Never substitute data clearing for recovery.

For a third or later member, append to the existing slot's destinations array, retaining the
first members, slot metadata, other slots and navigate_by modes. Do not change the 3-slot
limit or hard-code a particular group in Java. Current capacity is 12 places per slot.
Validate and reimport the full file; verify the count, order, third-row selection and actual
Maps destination/guidance privately. Update the private handoff's canonical source pointer
so a later installation cannot restore a stale shorter list. Public release notes describe
capabilities and sanitized evidence only, never the configured places or their Maps links.

## Where am I

Read docs/WHERE_AM_I.md for the independent trip contract. Preserve all action safety and
priority. The trip screen is passive; its overlay yields while counting continues. Never add
Google geocoding, a second GPS provider or an action lease to this feature. Actual maps and
APK/private installation receipts remain outside Git. Build local OSM packs on the computer;
validate before atomic installation. Review every current-gallery screenshot for private
pixels. Capture runner/instrumentation must refuse physical devices and never dispatch actions.
Unattended updates require verified existing ADB and completed idle preflight, not user prompts.
Keep missing device access and unobserved driving/wake tests explicit in the verification ledger.

## Combined Routebook source

The owner authorized consolidating Android, Routebook backend/UI and deployment source on
GitHub and main on 2026-10-06. No private state, deployment identifiers, APKs or GPS archives
are publication inputs. Keep contract/fixtures reproducible in routebook/contract and keep
all runtime credentials outside Git. One main commit identifies the combined test build.
Run scripts/check-routebook.sh plus routebook npm build/test and existing Android checks.
Do not replace the deployed server or pair a radio merely by publishing code.
