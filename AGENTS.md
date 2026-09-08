# Dudu Home contributor instructions

Read README.md, docs/TECHNICAL_NOTES.md and docs/DECISIONS.md completely before edits.
The owner authorized public source publication only to pbuchman/dudu-home, under MIT.
Never copy history from the private predecessor. Never publish APKs, credentials, private
configuration, actual phone numbers (including suffixes), real street names or coordinates,
raw recordings, private screenshots, signing keys or device-specific connection addresses.
Before every commit/push, inspect the staged tree and run the public-tree privacy check.
Private local denylist verification must also be performed by the maintainer before publication.
Do not copy the predecessor's calibration module into this repository.

Preserve Java 17, platform Android Views/XML, the existing application ID and local signing key.
No AndroidX, dependency injection framework, analytics, intermediary cloud server, or fake DUDU backend.
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
checks; the baseline remains tagged. Do not claim full acceptance or create a final release
tag before the remaining journey/ignition checks pass. Never publish APKs.
Preserve unrelated dirty files and the private predecessor without deleting or rewriting history.

Current functional contract: docs/FUNCTIONAL.md. Architecture/auth: docs/ROBOROCK.md.
Design/assets: docs/DESIGN.md. Next queued hook: docs/ROADMAP.md (Yanosik after sustained movement).
Installation/recovery: docs/OPERATIONS.md. Evidence and outstanding radio checks:
docs/VERIFICATION.md and docs/LOCAL_DEVELOPMENT.md. Keep these current with every material change.
Automatic cleaning reserves one attempt per Europe/Warsaw day BEFORE execution; failure, missing
configuration or a busy action consume that opportunity. No automatic retry or queue. Manual
cleaning remains independent. Saving any configuration must never execute an action or reset quota.
Preserve shared action exclusion until actual Binder/HTTP cleanup, not just until the UI closes.
Stage complete private configuration outside Git; compare radio phone and APK signature before
update, back up with checksums, keep maintenance until one-time import completes. Do not print
headers, account payloads, routine IDs, connection addresses or raw firmware logs.
Do not bypass a browser security refusal to store secrets. Google Password Manager backup is
not completed; the local owner-only credential bundle is the working source of configuration.
