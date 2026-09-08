#!/usr/bin/env python3
"""Private atomic import; optional backed-up APK update. Never launches or triggers actions."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import tarfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'pl.piotrbuchman.dudugate'


def private_path(value):
    path = Path(value).resolve()
    if path.is_relative_to(ROOT) or any((parent/'.git').exists() for parent in (path, *path.parents)):
        raise ValueError('Private paths must be outside repositories')
    return path


def validate(data, robot, enabled):
    if not isinstance(data, dict): raise ValueError('Configuration must be an object')
    if data.get('schema_version') not in (1, 2): raise ValueError('Unsupported schema')
    phone = data.get('gate_number')
    if phone is not None and (not isinstance(phone, str) or not re.fullmatch(r'\+?[0-9]{3,15}', phone)): raise ValueError('Invalid phone format')
    points = data.get('points')
    if points is not None:
        if not isinstance(points, dict): raise ValueError('Invalid geometry')
        for role in ('parking', 'gate', 'approach', 'junction'):
            if not isinstance(points.get(role), dict): raise ValueError('Invalid geometry')
            for name, limit in [('lat', 85), ('lon', 180)]:
                value = points.get(role, {}).get(name)
                if type(value) not in (int, float) or not math.isfinite(value) or abs(value) > limit:
                    raise ValueError('Invalid geometry')
        origin = points['parking']
        for role in ('gate', 'approach', 'junction'):
            dx = (points[role]['lon'] - origin['lon']) * 111320 * math.cos(math.radians(origin['lat']))
            dy = (points[role]['lat'] - origin['lat']) * 111320
            if not 15 <= math.hypot(dx, dy) <= 5000: raise ValueError('Invalid point distances')
    if enabled and points is None: raise ValueError('Automation requires locations')
    if robot is not None:
        if not isinstance(robot, dict) or not isinstance(robot.get('auth'), dict): raise ValueError('Invalid credentials package')
        if robot.get('schema_version') != 1 or robot.get('routine_name') != 'Full Cleaning': raise ValueError('Invalid routine package')
        bases = {f'https://api-{r}.roborock.com' for r in ('eu', 'us', 'cn', 'ru')}
        if robot.get('api_base_url') not in bases | {base+'/' for base in bases}:
            raise ValueError('Unapproved endpoint')
        if type(robot.get('routine_id')) is not int or robot['routine_id'] <= 0: raise ValueError('Invalid routine identifier')
        for key in ('u', 's', 'h'):
            value = robot['auth'].get(key)
            if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_+=/.:-]{1,512}', value): raise ValueError('Invalid credentials')
        robot = {k: robot[k] for k in ('schema_version', 'api_base_url', 'routine_id', 'routine_name', 'auth')}
        robot['auth'] = {key: robot['auth'][key] for key in ('u', 's', 'h')}
    result = dict(schema_version=2, gate_number=phone,
                  gate_number_verified_on_current_device=bool(data.get('gate_number_verified_on_current_device')),
                  points=points, automation_enabled=enabled, roborock=robot)
    if len(json.dumps(result).encode()) > 16384: raise ValueError('Configuration too large')
    return result


def main():
    os.umask(0o077)
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('serial'); p.add_argument('config', type=private_path)
    p.add_argument('--roborock', type=private_path)
    p.add_argument('--enable-automation', action='store_true'); p.add_argument('--dry-run', action='store_true')
    p.add_argument('--apk', type=Path); p.add_argument('--backup-dir', type=private_path)
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
    p.add_argument('--adb', default=str(sdk/'platform-tools/adb'))
    p.add_argument('--apksigner', default=str(sdk/'build-tools/35.0.0/apksigner'))
    p.add_argument('--aapt', default=str(sdk/'build-tools/35.0.0/aapt'))
    a = p.parse_args()
    for source in (a.config, a.roborock):
        if source and source.stat().st_mode & 0o077: raise ValueError('Private input must have owner-only permissions (chmod 600)')
    data = json.loads(a.config.read_text())
    robot = json.loads(a.roborock.read_text()) if a.roborock else data.get('roborock')
    payload = validate(data, robot, a.enable_automation)
    if a.dry_run:
        print('Syntax valid; device, phone, signature and authorization NOT verified.'); return
    adb = [a.adb, '-s', a.serial]
    def run(args, **kwargs): return subprocess.check_output(adb + args, stderr=subprocess.PIPE, **kwargs)
    def shell(command, **kwargs): return run(['shell', command], **kwargs)
    installed = run(['shell', 'dumpsys', 'package', PACKAGE]).decode()
    version = re.search(r'versionCode=(\d+)', installed)
    if not version: raise ValueError('Existing application not found; refusing blind installation')
    if payload['gate_number'] is not None:
        prefs = ET.fromstring(run(['exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/gate_settings.xml']))
        current = prefs.find("string[@name='gate_number']")
        if current is None or current.text != payload['gate_number']:
            raise ValueError('Phone differs from existing radio settings; verify before proceeding')
        payload['gate_number_verified_on_current_device'] = True
    if a.apk:
        if not a.backup_dir: raise ValueError('--backup-dir required for APK update')
        a.backup_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        if a.backup_dir.stat().st_mode & 0o077: raise ValueError('Backup directory must be owner-only (chmod 700)')
        metadata = subprocess.check_output([a.aapt, 'dump', 'badging', str(a.apk)], stderr=subprocess.PIPE).decode()
        target = re.search(r"package: name='([^']+)' versionCode='(\d+)'", metadata)
        if not target or target.group(1) != PACKAGE or int(target.group(2)) < 3:
            raise ValueError('APK is not the expected Full Cleaning application')
        backup = Path(tempfile.mkdtemp(prefix='before-update-', dir=a.backup_dir))
        remote = run(['shell', 'pm', 'path', PACKAGE]).decode().strip().removeprefix('package:')
        if not remote.startswith('/') or '\n' in remote: raise ValueError('Unsupported installed APK layout')
        run(['pull', remote, str(backup/'installed.apk')])
        def cert(apk):
            output = subprocess.check_output([a.apksigner, 'verify', '--print-certs', str(apk)], stderr=subprocess.PIPE).decode()
            return re.findall(r'Signer #\d+ certificate SHA-256 digest: (\w+)', output)
        old, new = cert(backup/'installed.apk'), cert(a.apk)
        if not old or old != new: raise ValueError('Signature mismatch; backup preserved, no update')
        # Preconditions: driver parked, no active call/action. Stop writes before snapshot.
        run(['shell', 'am', 'force-stop', PACKAGE])
        (backup/'app-data.tar').write_bytes(run(['exec-out', 'run-as', PACKAGE, 'tar', '-cf', '-', '.']))
        # exec-out may hide a failed remote command's exit status. Validate the archive itself.
        with tarfile.open(backup/'app-data.tar') as archive:
            names = [member.name for member in archive.getmembers()]
            if not names or (payload['gate_number'] is not None and not any(
                    name.removeprefix('./') == 'shared_prefs/gate_settings.xml' for name in names)):
                raise ValueError('Incomplete application backup; no update')
        manifest = {x.name: hashlib.sha256(x.read_bytes()).hexdigest() for x in backup.iterdir() if x.is_file()}
        (backup/'sha256.json').write_text(json.dumps(manifest, indent=2))
        for name, digest in manifest.items():
            if hashlib.sha256((backup/name).read_bytes()).hexdigest() != digest: raise ValueError('Backup checksum mismatch')
        run(['shell', 'am', 'force-stop', PACKAGE])
        shell(f"run-as {PACKAGE} sh -c 'umask 077; mkdir -p no_backup; touch no_backup/maintenance'")
        disabled = dict(payload, automation_enabled=False, roborock=None)
        shell(f"run-as {PACKAGE} sh -c 'cat > no_backup/home-config.json'", input=json.dumps(disabled).encode())
        run(['install', '-r', str(a.apk)])
        version = re.search(r'versionCode=(\d+)', run(['shell', 'dumpsys', 'package', PACKAGE]).decode())
    if int(version.group(1)) < 3: raise ValueError('Install Full Cleaning build before importing')
    run(['shell', 'am', 'force-stop', PACKAGE])
    command = f"run-as {PACKAGE} sh -c 'umask 077; mkdir -p no_backup; touch no_backup/maintenance; cat > no_backup/pending-config.json.tmp && mv no_backup/pending-config.json.tmp no_backup/pending-config.json'"
    encoded = json.dumps(payload).encode(); shell(command, input=encoded)
    copied = run(['exec-out', 'run-as', PACKAGE, 'cat', 'no_backup/pending-config.json'])
    if hashlib.sha256(copied).digest() != hashlib.sha256(encoded).digest(): raise ValueError('Import checksum mismatch; automation suspended')
    if a.enable_automation:
        api = int(run(['shell', 'getprop', 'ro.build.version.sdk']).strip())
        permissions = ['ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION']
        if api >= 29: permissions += ['ACCESS_BACKGROUND_LOCATION']
        if api >= 33: permissions += ['POST_NOTIFICATIONS']
        for perm in permissions: run(['shell', 'pm', 'grant', PACKAGE, 'android.permission.'+perm])
        run(['shell', 'appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow'])
    print('Private configuration staged and checksum verified. No launch, call or cleaning.')
    print('Open Dudu Home while parked to consume import; verify settings and permissions.')


if __name__ == '__main__':
    try: main()
    except (ValueError, TypeError, OSError, subprocess.SubprocessError, ET.ParseError, tarfile.TarError) as error:
        print('FAILED: ' + (str(error) if isinstance(error, ValueError) and not isinstance(error, json.JSONDecodeError)
                           else type(error).__name__), file=sys.stderr)
        raise SystemExit(1)
