# Explicit installation-ID migration

Local tools only; do not execute on a radio until a parked installation session is authorized.
Read OPERATIONS.md safety rules. Old ID: `pl.piotrbuchman.dudugate`; new ID:
`com.pbuchman.duduhome`. Keep the same signing key. Standard configure-device now targets
the NEW ID; do not use it to update the old package.

```sh
python3 scripts/migrate-device.py stage DEVICE_SERIAL \
  --config "$PRIVATE_DIR/config.json" --roborock "$PRIVATE_DIR/roborock/routine-credentials.json" \
  --apk app/build/outputs/apk/debug/app-debug.apk --backup-dir "$PRIVATE_DIR/radio-backups"
```

The tool checks package absence, matching signing certificate and current phone; creates a
checksum-backed archive; disables the legacy package and sets maintenance before installing
the new package. It copies only gate settings/cooldowns, daily quota and detector preferences.
No archive extraction, old ciphertext or Keystore key migration. The complete private bundle
is staged for re-encryption by the NEW app. No app is launched and no action is executed.

Open `com.pbuchman.duduhome/.ui.MainActivity` while parked. Import consumes staging and
maintenance without an action. The existing 60-second save rule applies if the number changes;
migration itself preserves existing cooldown timestamps. Never reset the daily quota.

For `resume`, `verify` and `finalize`, supply the same arguments, but `--backup-dir` must be
the exact private `migration-*` directory printed by stage. Resume permits interrupted staging,
not an already initialized target; it must not roll back a live application's daily state.
Verify checks imported storage, current phone, non-regressed daily quota, target APK hash/signature
and disabled legacy package. It does not prove physical operation or vendor wake.

Manually update DUDU's configured wake target to the NEW `.startup.HomeWakeActivity` and verify
its actual behavior. Existing system settings/permissions belong to the old ID and do not
automatically transfer. The importer grants standard GPS/notification/overlay permissions.

Only after functional and wake checks, use `finalize` with both
`--confirm-functional-tested --confirm-wake-tested`. This removes ONLY the legacy package.
Its private backup remains, but its old Keystore key is lost on uninstall. Recovery requires
the private plain credential bundle, not restoration of the old encrypted file.

Failure before finalize: do not delete either package or clear data. Keep both stopped/disabled
until the failure is understood. To roll back, disable the new package first; then restore the
old package's pre-migration maintenance state and vendor wake target, and enable only the old
package. Never run both automation services. Backups contain sensitive data and stay private.

## Local evidence

`test-migration-emulator.py emulator-5554 --legacy-apk PRIVATE_LEGACY_APK` exercises real
ADB installation using synthetic data: preserved preferences, interrupted staging/resume,
encrypted import, persistent quota, rejection of live-target resume and guarded removal.
It refuses non-emulator hardware. The legacy fixture must be a signed old-ID build; it is
not committed or published. No physical robot, calls, GPS injection into a radio or wake claims.
