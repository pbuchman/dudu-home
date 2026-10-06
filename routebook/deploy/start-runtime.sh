#!/usr/bin/env bash
# H3 only. Does not change Cloudflare, DNS or Tailscale.
set -euo pipefail
if [[ "${1:-}" != --h3-authorized ]]; then
  echo 'Requires coordinator H3 follow-up; invoke with --h3-authorized after acceptance.' >&2; exit 1
fi
if [[ "$(uname -s)" != Linux ]]; then echo 'Linux home-dev only' >&2; exit 1; fi
base="$(cd "$(dirname "$0")" && pwd)"
python3 "$base/build-images.py"
"$base/compose.sh" up -d --wait db
"$base/compose.sh" run --rm --no-deps backend node backend/dist/main.js --bootstrap
"$base/compose.sh" up -d backend web
for attempt in {1..20}; do
  if curl --silent --fail --output /dev/null http://127.0.0.1:8792/health; then
    echo 'Private runtime healthy; publication still requires owner Cloudflare/Serve steps.'; exit 0
  fi
  sleep 1
done
echo 'Private health failed' >&2; exit 1
