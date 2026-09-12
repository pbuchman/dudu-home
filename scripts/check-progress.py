#!/usr/bin/env python3
"""Build pure checks and compare observations against the approved pre-UI baseline."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
out = root / 'build' / 'progress-checks'
out.mkdir(parents=True, exist_ok=True)
java = root / 'app/src/main/java/com/pbuchman/duduhome'
sources = [java / p for p in ('automation/HomeEvent.java', 'automation/DetectionProgress.java',
    'automation/ProgressModel.java', 'location/HomeDetector.java', 'location/MotionDetector.java')]
for name in ('HomeDetector', 'MotionDetector'):
    path = f'app/src/main/java/com/pbuchman/duduhome/location/{name}.java'
    source = subprocess.check_output(['git', 'show', f'911d18b:{path}'], cwd=root).decode()
    generated = out / f'Baseline{name}.java'
    generated.write_text(source.replace(name, f'Baseline{name}'))
    sources.append(generated)
sources += [root / 'scripts/ProgressChecks.java', root / 'scripts/DetectorEquivalence.java']
subprocess.run(['javac', '-d', str(out), *map(str, sources)], check=True)
subprocess.run(['java', '-cp', str(out), 'ProgressChecks'], check=True)
subprocess.run(['java', '-cp', str(out), 'DetectorEquivalence', *sys.argv[1:]], check=True)
