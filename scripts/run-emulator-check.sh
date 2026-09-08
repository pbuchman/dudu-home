#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DUDU_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
DUDU_ADB="$DUDU_SDK_ROOT/platform-tools/adb"
DUDU_EMULATOR="$DUDU_SDK_ROOT/emulator/emulator"
DUDU_AVD_NAME="${DUDU_AVD_NAME:-medium_phone}"

if [[ ! -x "$DUDU_ADB" || ! -x "$DUDU_EMULATOR" ]]; then
    echo "Brak Android SDK, platform-tools albo emulatora w: $DUDU_SDK_ROOT" >&2
    echo "Wykonaj instrukcję 'Przygotowanie emulatora na Macu' z README.md." >&2
    exit 1
fi

DUDU_SERIAL="$($DUDU_ADB devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')"
if [[ -z "$DUDU_SERIAL" ]]; then
    echo "Uruchamiam emulator $DUDU_AVD_NAME. Pierwszy start może potrwać kilka minut."
    "$DUDU_EMULATOR" -avd "$DUDU_AVD_NAME" -no-snapshot-save \
        > /tmp/dudu-gate-emulator.log 2>&1 &

fi

for _ in $(seq 1 180); do
    DUDU_SERIAL="$($DUDU_ADB devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')"
    if [[ -n "$DUDU_SERIAL" ]] \
            && [[ "$($DUDU_ADB -s "$DUDU_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
        break
    fi
    sleep 1
done

if [[ -z "$DUDU_SERIAL" ]] \
        || [[ "$($DUDU_ADB -s "$DUDU_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
    echo "Emulator nie uruchomił się. Log: /tmp/dudu-gate-emulator.log" >&2
    exit 1
fi

cd "$PROJECT_DIR"
./gradlew assembleDebug assembleDebugAndroidTest

"$DUDU_ADB" -s "$DUDU_SERIAL" shell settings put system accelerometer_rotation 0
"$DUDU_ADB" -s "$DUDU_SERIAL" shell settings put system user_rotation 1
"$DUDU_ADB" -s "$DUDU_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
"$DUDU_ADB" -s "$DUDU_SERIAL" shell pm clear com.pbuchman.duduhome >/dev/null
"$DUDU_ADB" -s "$DUDU_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
DUDU_SAFETY="$($DUDU_ADB -s "$DUDU_SERIAL" shell am instrument -w \
    com.pbuchman.duduhome.test/com.pbuchman.duduhome.SafetyChecks)"
echo "$DUDU_SAFETY"
if [[ "$DUDU_SAFETY" != *"PASS:"* || "$DUDU_SAFETY" == *"FAIL:"* ]]; then
    echo "Safety checks failed." >&2
    exit 1
fi
"$DUDU_ADB" -s "$DUDU_SERIAL" shell am force-stop com.pbuchman.duduhome
"$DUDU_ADB" -s "$DUDU_SERIAL" shell am start -n com.pbuchman.duduhome/.ui.MainActivity >/dev/null
"$DUDU_ADB" -s "$DUDU_SERIAL" shell uiautomator dump /sdcard/dudu-menu.xml >/dev/null
DUDU_MENU="$($DUDU_ADB -s "$DUDU_SERIAL" shell cat /sdcard/dudu-menu.xml)"
if [[ "$DUDU_MENU" != *"open_gate_button"* ]]; then
    echo "Menu was not visible after restarting the process." >&2
    exit 1
fi
"$DUDU_ADB" -s "$DUDU_SERIAL" shell pm clear com.pbuchman.duduhome >/dev/null
"$DUDU_ADB" -s "$DUDU_SERIAL" logcat -c
"$DUDU_ADB" -s "$DUDU_SERIAL" shell am force-stop com.pbuchman.duduhome
"$DUDU_ADB" -s "$DUDU_SERIAL" shell am start \
    -n com.pbuchman.duduhome/.ui.MainActivity

sleep 2
"$DUDU_ADB" -s "$DUDU_SERIAL" shell uiautomator dump \
    /sdcard/dudu-gate-first-run.xml >/dev/null
DUDU_UI="$($DUDU_ADB -s "$DUDU_SERIAL" shell cat /sdcard/dudu-gate-first-run.xml)"
DUDU_LOGS="$($DUDU_ADB -s "$DUDU_SERIAL" logcat -d -s DuduGate:I '*:S')"

if [[ "$DUDU_UI" != *"com.pbuchman.duduhome:id/gate_number_input"* ]]; then
    echo "Brak formularza numeru po wyczyszczeniu danych aplikacji." >&2
    exit 1
fi
if [[ "$DUDU_LOGS" == *"Attempt "* ]]; then
    echo "Aplikacja rozpoczęła próbę bez zapisanego numeru." >&2
    exit 1
fi

echo
echo "Aplikacja jest otwarta na $DUDU_SERIAL w orientacji poziomej."
echo "Formularz pierwszego uruchomienia jest widoczny i dial nie został rozpoczęty."
