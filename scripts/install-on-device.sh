#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DUDU_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
DUDU_ADB="$DUDU_SDK_ROOT/platform-tools/adb"
DUDU_SERIAL="${1:-}"

if [[ $# -ne 1 || -z "$DUDU_SERIAL" ]]; then
    echo "Podaj dokładnie jedno urządzenie: scripts/install-on-device.sh DEVICE_SERIAL" >&2
    exit 1
fi

if [[ ! -x "$DUDU_ADB" ]]; then
    echo "Nie znaleziono adb w: $DUDU_ADB" >&2
    exit 1
fi

DUDU_TARGET=(-s "$DUDU_SERIAL")
# Include packages with retained data. A failed/empty/unrecognized response is NOT absence.
if ! DUDU_PACKAGES="$("$DUDU_ADB" "${DUDU_TARGET[@]}" shell pm list packages -u 2>&1)"; then
    echo "Nie udało się sprawdzić aplikacji przez ADB. Instalacja przerwana." >&2
    exit 1
fi
DUDU_PACKAGES="${DUDU_PACKAGES//$'\r'/}"
if [[ -z "$DUDU_PACKAGES" ]]; then
    echo "ADB zwróciło pustą listę aplikacji. Instalacja przerwana." >&2
    exit 1
fi
DUDU_INSTALLED=false
while IFS= read -r DUDU_PACKAGE; do
    if [[ ! "$DUDU_PACKAGE" =~ ^package:[a-zA-Z0-9_.]+$ ]]; then
        echo "Nie można jednoznacznie odczytać listy aplikacji. Instalacja przerwana." >&2
        exit 1
    fi
    if [[ "$DUDU_PACKAGE" == package:com.pbuchman.duduhome ]]; then
        DUDU_INSTALLED=true
    fi
done <<< "$DUDU_PACKAGES"
if [[ "$DUDU_INSTALLED" == true ]]; then
    echo "Aktualizacja wymaga kopii i prywatnej konfiguracji: użyj scripts/configure-device.py z --apk i --backup-dir." >&2
    exit 1
fi

cd "$PROJECT_DIR"
./gradlew assembleDebug lintDebug

# No -r: even if the package appeared after the check, never silently replace it.
"$DUDU_ADB" "${DUDU_TARGET[@]}" install \
    app/build/outputs/apk/debug/app-debug.apk
echo "Dudu Home zainstalowane. Nie uruchomiono aplikacji ani połączenia."
echo "Otwórz aplikację na postoju: zobaczysz formularz bez automatycznej akcji."
echo "Logi: $DUDU_ADB ${DUDU_TARGET[*]} logcat -s DuduGate"
