#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/trip
javac -d build/trip app/src/main/java/com/pbuchman/duduhome/routebook/Json.java app/src/main/java/com/pbuchman/duduhome/trip/{TripFix,TripDistance,TripTime,RadioTimeGate,RadioProfile}.java scripts/TripChecks.java scripts/RadioPowerChecks.java
java -cp build/trip com.pbuchman.duduhome.trip.TripChecks
java -cp build/trip com.pbuchman.duduhome.trip.RadioPowerChecks
python3 scripts/test-place-index.py
