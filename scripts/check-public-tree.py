#!/usr/bin/env python3
"""Check staged files and optionally all Git history. Does not print matched secrets."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

p = argparse.ArgumentParser()
p.add_argument('--all-history', action='store_true')
p.add_argument('--working-tree', action='store_true')
p.add_argument('--private-config', type=Path)
args = p.parse_args()
root = Path(__file__).resolve().parents[1]
def git(*a):
    return subprocess.check_output(['git', *a], cwd=root)

private = []
if args.private_config:
    config = json.loads(args.private_config.read_text())
    private += [str(config.get('gate_number', ''))]
    private += config.get('private_terms', [])
    for point in config.get('points', {}).values():
        private += [str(value) for value in point.values() if isinstance(value, (float, int))]
    private = [v.encode().lower() for v in private if len(v) >= 5]

errors = set()
def inspect(name, data):
    path = Path(name)
    if (path.suffix.lower() in {'.apk', '.aab', '.jks', '.keystore', '.jsonl', '.gpx', '.kml', '.log', '.pdf'}
            or name.startswith(('private/', 'calibration/', 'output/')) or path.name in {'config.json', '.env', 'local.properties'}):
        errors.add((name, 'private/generated file type'))
    for value in private:
        if value in data.lower():
            errors.add((name, 'private value'))
    if path.suffix.lower() in {'.png', '.jar'}:
        return
    if re.search(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{20,}', data):
        errors.add((name, 'credential pattern'))
    if re.search(rb'(?<![\w.])[+]?[1-9][0-9]{8,14}(?![\w.])', data):
        errors.add((name, 'phone-like literal'))
    if re.search(rb'(?:https?://)?(?:10\.|192\.168\.)\d{1,3}\.\d{1,3}', data):
        errors.add((name, 'private device address'))

entries = git('ls-files', '-s', '-z').split(b'\0')
count = 0
seen = set()
for entry in entries:
    if not entry:
        continue
    meta, name = entry.split(b'\t', 1)
    blob = meta.split()[1].decode()
    name = name.decode()
    inspect(name, git('cat-file', 'blob', blob))
    seen.add((name, blob))
    count += 1
if not count:
    sys.exit('No staged/tracked files to inspect.')
if args.working_tree:
    for raw in git('ls-files', '--cached', '--others', '--exclude-standard', '-z').split(b'\0'):
        if raw:
            name = raw.decode()
            path = root / name
            if path.is_file(): inspect(name, path.read_bytes())
if args.all_history:
    for rev in git('rev-list', '--all').decode().splitlines():
        for entry in git('ls-tree', '-r', '-z', rev).split(b'\0'):
            if not entry:
                continue
            meta, name = entry.split(b'\t', 1)
            blob = meta.split()[2].decode()
            name = name.decode()
            if (name, blob) not in seen:
                inspect(name, git('cat-file', 'blob', blob))
                seen.add((name, blob))
if errors:
    for name, reason in sorted(errors):
        print(f'REJECTED {name}: {reason}')
    sys.exit(1)
print(f'Privacy checks passed: {count} index files, {len(seen)} distinct file versions.')
print('Pattern checks complement, not replace, manual review of text, images and metadata.')
