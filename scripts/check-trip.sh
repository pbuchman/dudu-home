#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/trip
javac -d build/trip app/src/main/java/com/pbuchman/duduhome/trip/TripFix.java app/src/main/java/com/pbuchman/duduhome/trip/TripDistance.java scripts/TripChecks.java
java -cp build/trip com.pbuchman.duduhome.trip.TripChecks
python3 scripts/test-place-index.py
