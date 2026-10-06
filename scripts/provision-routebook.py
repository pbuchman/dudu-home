#!/usr/bin/env python3
"""Prepare owner-only config outside Git or stage it through stdin. Never starts the monitor/actions."""
import argparse
import getpass
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
from urllib.parse import urlsplit


def validate(data):
    fields = {'version', 'device_id', 'endpoint', 'token', 'enabled', 'sample_ms', 'max_age_ms', 'gap_ms', 'accuracy_m'}
    if set(data) != fields or data['version'] != 1 or type(data['enabled']) is not bool:
        raise ValueError('invalid schema')
    if str(uuid.UUID(data['device_id'])) != data['device_id']:
        raise ValueError('invalid device identity')
    endpoint = urlsplit(data['endpoint'])
    if endpoint.scheme != 'https' or not endpoint.hostname or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment or endpoint.path != '/v1/ingest' or endpoint.port not in (None, 443):
        raise ValueError('invalid HTTPS endpoint')
    if not re.fullmatch(r'[A-Za-z0-9._~+/-]{32,510}={0,2}', data['token']):
        raise ValueError('invalid token')
    for key in ('sample_ms', 'max_age_ms', 'gap_ms'):
        if type(data[key]) is not int:
            raise ValueError('invalid thresholds')
    if not 1000 <= data['sample_ms'] <= data['gap_ms'] <= 30000 or not 1 <= data['max_age_ms'] <= 5000 or type(data['accuracy_m']) not in (float, int) or not 0 < data['accuracy_m'] <= 50:
        raise ValueError('invalid thresholds')


def outside_git(path):
    if any((parent / '.git').exists() for parent in (path, *path.parents)):
        raise ValueError('configuration must be outside Git')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    prepare = commands.add_parser('prepare')
    prepare.add_argument('--output', type=Path, required=True)
    prepare.add_argument('--device-id', required=True)
    prepare.add_argument('--endpoint', required=True)
    prepare.add_argument('--token-stdin', action='store_true')
    stage = commands.add_parser('stage')
    stage.add_argument('--file', type=Path, required=True)
    stage.add_argument('--serial', required=True)
    stage.add_argument('--adb', default='adb')
    args = parser.parse_args()
    os.umask(0o077)
    if args.command == 'prepare':
        path = args.output.expanduser().resolve()
        outside_git(path)
        token = sys.stdin.readline().rstrip('\r\n') if args.token_stdin else getpass.getpass('Dedicated Routebook token: ')
        data = dict(version=1, device_id=args.device_id, endpoint=args.endpoint, token=token, enabled=True,
                    sample_ms=5000, max_age_ms=5000, gap_ms=30000, accuracy_m=50)
        validate(data)
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if path.parent.stat().st_mode & 0o077:
            raise ValueError('parent directory must be owner-only')
        with path.open('x', encoding='utf-8') as out:
            json.dump(data, out, separators=(',', ':'))
            out.flush()
            os.fsync(out.fileno())
        print('Private provisioning file prepared; token not displayed.')
    else:
        path = args.file.expanduser().resolve()
        outside_git(path)
        if path.stat().st_mode & 0o077 or path.parent.stat().st_mode & 0o077:
            raise ValueError('file/directory must be owner-only')
        raw = path.read_bytes()
        if len(raw) > 16384:
            raise ValueError('file too large')
        def unique(pairs):
            value = {}
            for key, item in pairs:
                if key in value:
                    raise ValueError('duplicate key')
                value[key] = item
            return value
        validate(json.loads(raw, object_pairs_hook=unique))
        if not re.fullmatch(r'[A-Za-z0-9_.:-]+', args.serial):
            raise ValueError('invalid serial')
        command = ['run-as', 'com.pbuchman.duduhome', 'sh', '-c',
                   "'umask 077; mkdir -p no_backup && cat > no_backup/routebook-import.json.tmp && chmod 600 no_backup/routebook-import.json.tmp && mv no_backup/routebook-import.json.tmp no_backup/routebook-import.json'"]
        completed = subprocess.run([args.adb, '-s', args.serial, 'shell', *command], input=raw, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if completed.returncode:
            raise ValueError('private staging failed')
        print('Separate Routebook file staged. It will be imported on the next safe monitor start; no Activity or action requested.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError):
        sys.exit('Routebook provisioning failed; check private file, permissions, identity and selected device.')
