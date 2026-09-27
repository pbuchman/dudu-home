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
p.add_argument('--private-roborock', type=Path)
p.add_argument('--private-navigation', type=Path)
args = p.parse_args()
root = Path(__file__).resolve().parents[1]
def git(*a):
    return subprocess.check_output(['git', *a], cwd=root)

private = []
if args.private_config:
    config = json.loads(args.private_config.read_text())
    private += [str(config.get('gate_number', ''))]
    private += config.get('private_terms', [])
    for point in (config.get('points') or {}).values():
        private += [str(value) for value in point.values() if isinstance(value, (float, int))]
    private = [v.encode().lower() for v in private if len(v) >= 5]

# Navigation values never live in public fixtures. Match exact labels, address words,
# Unicode escapes, and rounded coordinate forms without printing the matched data.
short_private = []
shared_vocabulary = []
if args.private_navigation:
    navigation = json.loads(args.private_navigation.read_text())
    places = []
    for entry in navigation.get('slots', []):
        if not isinstance(entry, dict): continue
        places.append(entry)  # Group metadata is private too.
        places.append(entry.get('destination'))
        places.extend(entry.get('destinations', []))
    for place in places:
        if not isinstance(place, dict): continue
        for field in ('label', 'address'):
            value = place.get(field, '')
            terms = [value] + re.findall(r"[^\W\d_]{5,}", value, re.UNICODE)
            for term in terms:
                if not term: continue
                # A generic label can equal a required schema icon token. Report every
                # occurrence for review instead of ignoring that label or rejecting code.
                if term == value and term.lower() in {"home", "squash", "pin"}:
                    shared_vocabulary.append(term.encode().lower())
                    continue
                # The complete label stays forbidden; generic supported icon names are not private address words.
                if term != value and term.lower() in {"home", "squash", "pin"}: continue
                if len(term) < 4: short_private.append(term.encode().lower())
                else:
                    private += [term.encode().lower(), json.dumps(term, ensure_ascii=True)[1:-1].encode().lower(),
                                term.encode('utf-16le').lower()]
        for field in ('latitude', 'longitude'):
            value = place.get(field)
            if isinstance(value, (int, float)):
                private += [str(value).encode()] + [f'{value:.{digits}f}'.encode() for digits in range(3, 8)]

errors = set()
reviews = set()
if args.private_roborock:
    robot = json.loads(args.private_roborock.read_text())
    private += [str(v).encode().lower() for v in robot.get('auth', {}).values() if len(str(v)) >= 4]
    if robot.get('routine_id'): private.append(str(robot['routine_id']).encode())
    if robot.get('full_mop_routine_id'): private.append(str(robot['full_mop_routine_id']).encode())
def decoded_unicode(data):
    text = data.decode('utf-8', errors='ignore')
    return re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m[1], 16)), text).encode('utf-8', errors='surrogatepass')

def inspect(name, data, current_style=False):
    path = Path(name)
    if (path.suffix.lower() in {'.apk', '.aab', '.jks', '.keystore', '.jsonl', '.gpx', '.kml', '.log', '.pdf', '.enc'}
            or name.startswith(('private/', 'calibration/', 'output/')) or path.name in {'config.json', 'navigation.json', 'navigation.proposed.json', 'verified-places.json', '.env', 'local.properties'}):
        errors.add((name, 'private/generated file type'))
    searchable = data.lower() + b"\0" + decoded_unicode(data).lower()
    for value in shared_vocabulary:
        if re.search(rb'(?<![a-zA-Z0-9_])' + re.escape(value) + rb'(?![a-zA-Z0-9_])', searchable):
            reviews.add((name, 'generic label overlaps schema vocabulary; manual review required'))
    for value in private:
        if value in searchable:
            errors.add((name, 'private value'))
    # Short byte sequences occur randomly inside compressed pixel/JAR data. Review pixels
    # separately; inspect PNG text metadata rather than treating DEFLATE bytes as words.
    short_data = data
    if path.suffix.lower() == ".jar": short_data = b""
    if path.suffix.lower() == ".png":
        import struct, zlib
        short_data = b""
        cursor = 8
        while cursor + 12 <= len(data):
            length = struct.unpack(">I", data[cursor:cursor+4])[0]
            kind = data[cursor+4:cursor+8]; chunk = data[cursor+8:cursor+8+length]
            if kind in (b"tEXt", b"iTXt", b"zTXt"):
                short_data += chunk
                if kind == b"zTXt": short_data += zlib.decompress(chunk.split(b"\0", 1)[1][1:])
                elif kind == b"iTXt":
                    rest = chunk.split(b"\0", 1)[1]
                    if rest[0] == 1: short_data += zlib.decompress(rest[2:].split(b"\0", 2)[2])
            cursor += length + 12
    short_data += b"\0" + decoded_unicode(short_data)
    for value in short_private:
        if re.search(rb'(?<![a-zA-Z0-9_])' + re.escape(value) + rb'(?![a-zA-Z0-9_])', short_data.lower()):
            if path.suffix.lower() == '.md':
                reviews.add((name, 'short label can be ordinary prose; manual review required'))
            else: errors.add((name, 'private label'))
    if path.suffix.lower() in {'.png', '.jar'}:
        return
    # Historical typography is not a privacy leak and must not force a history rewrite.
    if current_style and b'\xe2\x80\x94' in data:
        errors.add((name, 'em dash'))
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
    inspect(name, git('cat-file', 'blob', blob), current_style=True)
    seen.add((name, blob))
    count += 1
if not count:
    sys.exit('No staged/tracked files to inspect.')
if args.working_tree:
    for raw in git('ls-files', '--cached', '--others', '--exclude-standard', '-z').split(b'\0'):
        if raw:
            name = raw.decode()
            path = root / name
            if path.is_file(): inspect(name, path.read_bytes(), current_style=True)
if args.all_history:
    for rev in git('rev-list', '--all').decode().splitlines():
        inspect('commit-message', git('show', '-s', '--format=%B', rev))
        for entry in git('ls-tree', '-r', '-z', rev).split(b'\0'):
            if not entry:
                continue
            meta, name = entry.split(b'\t', 1)
            blob = meta.split()[2].decode()
            name = name.decode()
            if (name, blob) not in seen:
                inspect(name, git('cat-file', 'blob', blob))
                seen.add((name, blob))
for name, reason in sorted(reviews):
    print(f'REVIEW {name}: {reason}')
if errors:
    for name, reason in sorted(errors):
        print(f'REJECTED {name}: {reason}')
    sys.exit(1)
print(f'Privacy checks passed: {count} index files, {len(seen)} distinct file versions.')
print('Pattern checks complement, not replace, manual review of text, images and metadata.')
