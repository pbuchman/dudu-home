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
No AndroidX, dependency injection framework, analytics, cloud service, or fake DUDU backend.
Use the raw SYU Binder implementation. No ACTION_CALL, Accessibility, UI dialing or radio SIM.
Register callbacks before dial; dial only on fresh idle. Never hang up a pre-existing call.
One dial per attempt, no protocol fallback after dial, persistent 60-second reservation before
transact, singleTask plus process lock. Success requires own dial, outgoing and later idle.
No assertion of ringback, controller reception or physical gate opening.
Do not launch the baseline app during installation: launching with a saved number places a call.

Verify with ./gradlew assembleDebug lintDebug and scripts/run-emulator-check.sh.
Hardware verification is separate from emulator checks. Do not claim unavailable radio tests.
Keep new automation local until replay and hardware verification; public main is the baseline.
Preserve unrelated dirty files and the private predecessor without deleting or rewriting history.
