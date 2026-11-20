#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/diagnostic-checks
javac -d build/diagnostic-checks app/src/main/java/com/pbuchman/duduhome/diagnostics/RollingJournal.java \
  app/src/main/java/com/pbuchman/duduhome/diagnostics/CategoryFormat.java \
  app/src/main/java/com/pbuchman/duduhome/diagnostics/BlockCoalescer.java \
  app/src/main/java/com/pbuchman/duduhome/diagnostics/QueueBlockers.java \
  app/src/main/java/com/pbuchman/duduhome/diagnostics/PresentationCoalescer.java \
  app/src/main/java/com/pbuchman/duduhome/automation/DetectionProgress.java \
  app/src/main/java/com/pbuchman/duduhome/location/MotionDetector.java \
  app/src/main/java/com/pbuchman/duduhome/location/GpsStartupTrace.java \
  scripts/DiagnosticChecks.java scripts/GpsStartupChecks.java
java -cp build/diagnostic-checks com.pbuchman.duduhome.diagnostics.DiagnosticChecks
java -cp build/diagnostic-checks com.pbuchman.duduhome.location.GpsStartupChecks
python3 scripts/test-diagnostics.py
python3 scripts/test-automation-diagnostics.py
