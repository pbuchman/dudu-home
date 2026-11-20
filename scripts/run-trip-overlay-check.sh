#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! "$1" =~ ^emulator-[0-9]+$ ]]; then
    echo "Usage: bash scripts/run-trip-overlay-check.sh emulator-PORT" >&2
    echo "Use a booted, disposable emulator. The check clears app data and sets the compact display profile." >&2
    exit 1
fi

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DUDU_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
adb=("$DUDU_SDK_ROOT/platform-tools/adb" -s "$1")

if [[ "$("${adb[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]] \
        || [[ "$("${adb[@]}" shell getprop sys.boot_completed | tr -d '\r')" != 1 ]]; then
    echo "The selected target must be a booted Android emulator." >&2
    exit 1
fi

PACKAGE=com.pbuchman.duduhome
cd "$PROJECT_DIR"
APP_APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
if [[ ! -f "$APP_APK" || ! -f "$TEST_APK" ]]; then
    echo "Build the APKs with ./gradlew assembleDebug assembleDebugAndroidTest." >&2
    exit 1
fi

"${adb[@]}" shell wm size 480x800
"${adb[@]}" shell wm density 160
"${adb[@]}" shell wm fixed-to-user-rotation enabled
"${adb[@]}" shell wm user-rotation lock 1
"${adb[@]}" shell settings put system font_scale 1.0
# The native marquee pauses when Android puts the display to sleep.
"${adb[@]}" shell svc power stayon true
"${adb[@]}" shell settings put system screen_off_timeout 1800000
"${adb[@]}" shell input keyevent KEYCODE_WAKEUP
"${adb[@]}" shell wm dismiss-keyguard
"${adb[@]}" install -r "$APP_APK"
"${adb[@]}" install -r "$TEST_APK"
if [[ "$("${adb[@]}" shell pm clear "$PACKAGE" | tr -d '\r')" != Success ]]; then
    echo "Could not clear app data for the overlay check." >&2
    exit 1
fi
"${adb[@]}" shell appops set "$PACKAGE" SYSTEM_ALERT_WINDOW allow

if ! report="$("${adb[@]}" shell am instrument -w -e tripMarquee true \
        "$PACKAGE.test/$PACKAGE.SafetyChecks" | tr -d '\r')"; then
    echo "Could not run trip overlay instrumentation." >&2
    exit 1
fi
if [[ "$report" == *"FAIL:"* ]] \
        || ! grep -q '^INSTRUMENTATION_RESULT: result=PASS: focused trip marquee;' <<<"$report" \
        || ! grep -Fxq 'INSTRUMENTATION_CODE: -1' <<<"$report"; then
    grep -E '^INSTRUMENTATION_(RESULT|CODE):' <<<"$report" >&2 || true
    "${adb[@]}" shell dumpsys power | grep -m 1 'mWakefulness=' >&2 || true
    echo "The trip overlay did not pass the focused instrumentation check." >&2
    exit 1
fi
grep '^INSTRUMENTATION_RESULT: result=PASS: focused trip marquee;' <<<"$report"
