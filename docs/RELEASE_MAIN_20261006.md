# Historical 1.2 main rebuild, 2026-10-06

Superseded as the installation target by INTEGRATED_RELEASE.md. Retained only as evidence
of the earlier main-only build; do not follow its exclusion of Routebook for code 21.

## Identity and scope

Application source: main merge `39874ef`, version `1.2.0-rc1`, code 19.
Application files are unchanged from that commit. Documentation corrections are separate.
This is a private installation artifact with the existing debug signing identity, not a
new release signing scheme or a claim of stable hardware acceptance.

Included: existing gate and Roborock actions, gate-before-media priority, Yanosik/Spotify,
three configurable navigation groups, and the fourth Where am I tile with trip lifecycle,
background distance, locality/street overlay, notification and OSM/Photon resolution.

Not included: the separate local Routebook collector, backend pairing, Wispr Flow or
steering-wheel dictation. Do not label this APK 1.3 or install it over a newer code 20 build.
Routebook must be integrated and verified against this main before a single combined
tracking release can replace this artifact. Never substitute a dirty worktree APK silently.

## Documentation audit

Corrected LOCAL_DEVELOPMENT's stale statement that current source was 1.1.0.
Updated README and RELEASE_READINESS to record the merge and distinguish current source
from the last verified radio installation. Historical evidence remains historical.
TECHNICAL_NOTES and DECISIONS require no behavior changes for this merge-only rebuild.

## Radio connection sequence

1. Discover an authorized physical radio using existing ADB configuration; inspect actual
   package version, signature, storage, call/action idle and foreground navigation.
2. Preserve current private configuration, complete ordered navigation groups, cooldown,
   daily quota and consumed journey state. Use the backed-up update path in OPERATIONS.
   Never clear data, uninstall, or restore an older navigation file.
3. Validate the external Poland index and its manifest; allow space for temporary plus
   final copies. The approximately 3.28 GB index is not embedded in APK or downloaded by radio.
4. Enter maintenance only after idle, install the exact checked artifact through the existing
   unattended installer, atomically install the index, and verify installed APK/index hashes.
5. Compare all configuration and safety state; verify location/notification/overlay access,
   import completion, maintenance removal, menu and monitoring. Do not invoke gate or robot.
6. Exercise Where am I start/background/overlay/notification/pause/resume/end; verify local
   resolution without severing ADB. Observe real names, GPS and distance during passenger ride.
7. On a stop, observe actual sleep/wake and monitoring recovery. Do not infer wake acceptance
   from reboot alone; do not interrupt active navigation with an unplanned reboot.
8. Separately inspect/install Wispr Flow from its official distribution and test microphone,
   dictation into the focused context field, available external start/stop actions, and the
   physical steering-wheel trigger. Scratchpad is not a confirmed Android feature. Login,
   physical button and speech may require the passenger. App launch alone is not dictation.
9. Report exact installed identity, configuration preservation, passed checks and remaining
   hardware gaps. Tracking is pending a combined source integration, not enabled by this APK.

Installation success is not real GPS/wake acceptance. If device version or state differs,
resolve it before mutation. Use existing recovery tooling only within its supported boundary;
never rewind safety reservations or delete data to force an installation.
