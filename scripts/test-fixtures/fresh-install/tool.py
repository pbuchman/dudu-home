#!/usr/bin/env python3
"""Offline executable fixture for the installer regression tests, never real ADB/Gradle."""
import json
import os
from pathlib import Path
import sys

kind = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['DUDU_TEST_TRACE'], 'a') as trace:
    trace.write(json.dumps([kind, args]) + '\n')
case = os.environ['DUDU_TEST_CASE']
if kind == 'gradlew':
    raise SystemExit(1 if case == 'build_error' else 0)
if args[:2] != ['-s', 'synthetic-device']: raise SystemExit(91)
if args[2:] == ['shell', 'pm', 'list', 'packages', '-u']:
    if case == 'check_error': raise SystemExit(1)
    if case == 'check_error_with_output':
        print('package:android'); raise SystemExit(1)
    if case == 'empty': raise SystemExit(0)
    if case == 'malformed': print('Error: package manager unavailable'); raise SystemExit(0)
    print('package:android\r')
    print('package:com.pbuchman.duduhome.test')
    if case == 'installed': print('package:com.pbuchman.duduhome')
    if case == 'warning': print('untrusted diagnostic', file=sys.stderr)
    raise SystemExit(0)
if args[2:3] == ['install']:
    if '-r' in args: raise SystemExit(92)
    raise SystemExit(1 if case in ('install_error', 'race') else 0)
raise SystemExit(93)
