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

if "$DUDU_ADB" "${DUDU_TARGET[@]}" shell pm path pl.piotrbuchman.dudugate 2>/dev/null | grep -q '^package:'; then
    echo "Aktualizacja wymaga kopii i prywatnej konfiguracji: użyj scripts/configure-device.py z --apk i --backup-dir." >&2
    exit 1
fi

"$DUDU_ADB" "${DUDU_TARGET[@]}" install -r \
    app/build/outputs/apk/debug/app-debug.apk
echo "Dudu Home zainstalowane. Nie uruchomiono aplikacji ani połączenia."
echo "Otwórz aplikację na postoju: zobaczysz formularz bez automatycznej akcji."
echo "Logi: $DUDU_ADB ${DUDU_TARGET[*]} logcat -s DuduGate"
