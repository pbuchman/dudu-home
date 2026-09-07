#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DUDU_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
DUDU_ADB="$DUDU_SDK_ROOT/platform-tools/adb"
DUDU_SERIAL="${1:-}"

if [[ ! -x "$DUDU_ADB" ]]; then
    echo "Nie znaleziono adb w: $DUDU_ADB" >&2
    exit 1
fi

cd "$PROJECT_DIR"
./gradlew assembleDebug lintDebug

DUDU_TARGET=()
if [[ -n "$DUDU_SERIAL" ]]; then
    DUDU_TARGET=(-s "$DUDU_SERIAL")
fi

"$DUDU_ADB" "${DUDU_TARGET[@]}" install -r \
    app/build/outputs/apk/debug/app-debug.apk
echo "Dudu Home zainstalowane. Nie uruchomiono aplikacji ani połączenia."
echo "Otwórz aplikację świadomie: przy zapisanym numerze rozpocznie próbę połączenia."
echo "Logi: $DUDU_ADB ${DUDU_TARGET[*]} logcat -s DuduGate"
