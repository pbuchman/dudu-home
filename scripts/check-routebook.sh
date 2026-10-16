#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/routebook
javac -d build/routebook app/src/main/java/com/pbuchman/duduhome/routebook/{Json,Point,CollectorFilter,Protocol,DeliverySelection,PowerEvent,PowerProtocol}.java scripts/RoutebookChecks.java scripts/PowerProtocolChecks.java
java -cp build/routebook com.pbuchman.duduhome.routebook.RoutebookChecks "${1:-routebook/contract/fixtures.json}"
java -cp build/routebook com.pbuchman.duduhome.routebook.PowerProtocolChecks "${2:-routebook/contract/radio-observations-examples.json}"
