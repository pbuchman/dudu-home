#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/detector
javac -d build/detector app/src/main/java/pl/piotrbuchman/dudugate/HomeEvent.java \
  app/src/main/java/pl/piotrbuchman/dudugate/HomeDetector.java scripts/DetectorChecks.java scripts/DetectorReplay.java
java -cp build/detector pl.piotrbuchman.dudugate.DetectorChecks
