#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/detector
javac -d build/detector app/src/main/java/com/pbuchman/duduhome/automation/HomeEvent.java \
  app/src/main/java/com/pbuchman/duduhome/automation/DetectionProgress.java \
  app/src/main/java/com/pbuchman/duduhome/location/HomeDetector.java scripts/DetectorChecks.java scripts/DetectorReplay.java
java -cp build/detector com.pbuchman.duduhome.location.DetectorChecks
javac -d build/detector app/src/main/java/com/pbuchman/duduhome/location/MotionDetector.java \
  app/src/main/java/com/pbuchman/duduhome/automation/DetectionProgress.java \
  app/src/main/java/com/pbuchman/duduhome/automation/MotionHook.java scripts/MotionChecks.java
java -cp build/detector com.pbuchman.duduhome.location.MotionChecks
javac -cp build/detector -d build/detector app/src/main/java/com/pbuchman/duduhome/automation/AutomationCoordinator.java scripts/CoordinatorChecks.java
java -cp build/detector com.pbuchman.duduhome.automation.CoordinatorChecks
