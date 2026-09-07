# Dudu Home implementation plan

## First: publish the working baseline

Preserve the private predecessor, APKs and all calibration recordings with checksum verification.
Store installation configuration privately outside Git. Copy only the working calling app into
a fresh repository; retain package/signing compatibility. Rename the visible app to Dudu Home.
Remove all phone fragments from logs; never launch the app from the installer. Publish source
to pbuchman/dudu-home under MIT after build, lint, emulator checks and privacy/history inspection.
Publish no APK. README must distinguish existing hardware evidence from unverified behavior.

## Second: local automation development

- One large manual gate button, expandable later without dummy vacuum/music buttons.
- Manual action returns to menu. Automatic action uses the same progress/error screen and
  closes on success unless a menu was already open, in which case it returns there.
- Normal errors keep the baseline retry/close behavior. No automatic redial.
- Saving a missing number returns to menu without call and persistently blocks calls for
  60 seconds. Keep the existing post-dial cooldown. Expiry never replays blocked events.
- External private location configuration; no coordinates compiled into the APK and no map editor.
  Missing location configuration disables automation, not manual calls.
- Separate event detection, event/action mapping and the existing guarded call executor.
  Detect sustained departure toward the gate and return approach. Emit directional checkpoint
  events for future actions without implementing those actions now.
- Analyze all eight private captures before selecting thresholds. Reject stale/poor fixes,
  stationary drift and duplicates; tolerate nearby parking spaces and power interruptions.
- Use platform foreground location monitoring, boot/wake recovery and authorized background UI.
  Radio startup itself must never place a call.

## Acceptance

Build/lint; setup with no call; cooldown across process restart; manual/automatic navigation;
busy-call protection; replay positive and negative captures. On the radio verify update,
startup/wake, foreground UI, one departure and one return. Keep public baseline intact until
hardware checks pass. If radio/driver are unavailable, finish local checks and explicitly list
remaining hardware verification. Do not claim production detection from an 8/8 capture counter.
