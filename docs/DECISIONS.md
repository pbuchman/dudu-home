# Design decisions

- **Stable baseline first:** preserve the hardware-tested gate-call behavior, changing only
  public branding, removal of number fragments from logs and safe install tooling.
- **Privacy boundary:** a fresh Git history, no calibration module, no actual installation
  configuration in the public tree. Synthetic examples only. Local private archives are not Git repos.
- **Compatibility:** retain the technical application ID and local Android signing key. The
  visible name is Dudu Home. No public APK or new release-signing scheme in this phase.
- **Small Android implementation:** Java 17, Views/XML and platform APIs, without AndroidX,
  analytics, network access or an imitation of the vendor Bluetooth service.
- **Calling safety:** keep raw Binder, pre-dial idle verification, one call per attempt,
  persistent 60-second cooldown and cleanup of only calls initiated by this attempt.
- **Setup is not a call:** the baseline saves a number and closes without dial. A new launch
  is required to call. The later menu version adds a configuration cooldown and returns to menu.
- **Honest feedback:** retain outgoing-plus-delay behavior; no claim of first ringback or
  physical opening. Success is shown for 1350 ms; information for 2500 ms. Normal errors
  remain until user action. The firmware dependency is documented, not hidden.
- **Verification:** build, lint and small emulator checks. Real Binder behavior, power cycles
  and future location-triggered UI must be validated separately on the radio.
- **Future architecture:** location events and manual commands are independent inputs to
  one guarded action executor. No future vacuum or music implementation in this iteration.
