#!/usr/bin/env bash
set -euo pipefail
export ROUTEBOOK_CONFIG_DIR="${ROUTEBOOK_CONFIG_DIR:-$HOME/.config/routebook}"
export ROUTEBOOK_DATA_DIR="${ROUTEBOOK_DATA_DIR:-$HOME/.local/share/routebook}"
export ROUTEBOOK_UID="$(id -u)" ROUTEBOOK_GID="$(id -g)"
exec docker compose -f "$(dirname "$0")/compose.yaml" "$@"
