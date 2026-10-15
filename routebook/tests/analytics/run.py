#!/usr/bin/env python3
"""Disposable local analytics acceptance. Requires built source, Docker and nginx.

Browser plugin not available in the execution session; the optional browser check
uses the repository's existing Playwright dependency, without installing anything.
"""
import argparse
import os
from pathlib import Path
import subprocess
import time
import uuid

HERE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source', type=Path, default=HERE.parent.parent)
parser.add_argument('--ui-source', type=Path)
parser.add_argument('--browser', action='store_true')
parser.add_argument('--evidence', type=Path)
parser.add_argument('--db-port', type=int, default=55442)
args = parser.parse_args()
source = args.source.resolve()
ui_source = (args.ui_source or source).resolve()
if not (source / 'backend/dist/analytics.js').is_file():
    raise SystemExit('BLOCKED: run npm run build:backend in the source Routebook first')
if args.browser and not (ui_source / 'ui/dist/index.html').is_file():
    raise SystemExit('BLOCKED: run npm run build:ui in the UI source Routebook first')
name = 'routebook-analytics-synthetic-' + uuid.uuid4().hex[:10]
environment = dict(os.environ,
    ROUTEBOOK_ACCEPTANCE_SOURCE=str(source),
    ROUTEBOOK_ACCEPTANCE_UI_SOURCE=str(ui_source),
    ROUTEBOOK_ANALYTICS_TEST_DB=f'postgres://postgres@127.0.0.1:{args.db_port}/routebook_synthetic_analytics',
    ROUTEBOOK_ANALYTICS_BROWSER='1' if args.browser else '0')
if args.evidence:
    evidence = args.evidence.resolve()
    if evidence.is_relative_to(HERE.parents[2]):
        raise SystemExit('Evidence must remain outside the repository')
    evidence.mkdir(parents=True, exist_ok=True)
    environment['ROUTEBOOK_ANALYTICS_EVIDENCE'] = str(evidence)
started = False
try:
    subprocess.run(['docker', 'run', '--rm', '-d', '--name', name,
        '-p', f'127.0.0.1:{args.db_port}:5432',
        '-e', 'POSTGRES_HOST_AUTH_METHOD=trust',
        '-e', 'POSTGRES_DB=routebook_synthetic_analytics',
        'postgis/postgis:17-3.5'], check=True, stdout=subprocess.DEVNULL)
    started = True
    for _ in range(80):
        result = subprocess.run(['docker', 'exec', name, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'routebook_synthetic_analytics'], capture_output=True)
        if result.returncode == 0:
            break
        time.sleep(.25)
    else:
        raise SystemExit('BLOCKED: disposable PostGIS did not become ready')
    result = subprocess.run(['node', '--test', str(HERE / 'acceptance.test.mjs')], env=environment)
    raise SystemExit(result.returncode)
finally:
    if started:
        subprocess.run(['docker', 'rm', '-fv', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
