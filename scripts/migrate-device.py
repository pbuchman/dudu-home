#!/usr/bin/env python3
"""Explicit old-ID migration. No app launch, call, robot action or automatic removal."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import xml.etree.ElementTree as ET
from importlib.util import spec_from_file_location, module_from_spec

ROOT = Path(__file__).resolve().parents[1]
OLD = 'pl.piotrbuchman.dudugate'
NEW = 'com.pbuchman.duduhome'
PREFS = ('gate_settings', 'daily_cleaning', 'home_detector')
spec = spec_from_file_location('configuration', ROOT/'scripts/configure-device.py')
configuration = module_from_spec(spec)
spec.loader.exec_module(configuration)


def extract_state(archive_path, expected_phone):
    """Read only allowlisted XML members, never extract a tar into a directory."""
    result = {}
    with tarfile.open(archive_path) as archive:
        for name in PREFS:
            matches = [m for m in archive.getmembers()
                       if m.name.removeprefix('./') == f'shared_prefs/{name}.xml']
            if not matches:
                if name == 'gate_settings': raise ValueError('Missing gate state')
                continue
            if len(matches) != 1 or not matches[0].isfile() or matches[0].size > 65536:
                raise ValueError('Invalid state archive')
            value = archive.extractfile(matches[0]).read()
            tree = ET.fromstring(value)
            if tree.tag != 'map': raise ValueError('Invalid preferences')
            if name == 'gate_settings':
                phone = tree.find("string[@name='gate_number']")
                if phone is None or phone.text != expected_phone:
                    raise ValueError('Phone differs from installed application')
            result[name] = value
    return result


def main():
    os.umask(0o077)
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode', choices=('stage', 'resume', 'verify', 'finalize'))
    p.add_argument('serial')
    p.add_argument('--config', type=configuration.private_path, required=True)
    p.add_argument('--roborock', type=configuration.private_path, required=True)
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--backup-dir', type=configuration.private_path, required=True,
                   help='Parent directory for stage; exact migration directory otherwise')
    p.add_argument('--confirm-functional-tested', action='store_true')
    p.add_argument('--confirm-wake-tested', action='store_true')
    a = p.parse_args()
    for f in (a.config, a.roborock):
        if f.stat().st_mode & 0o077: raise ValueError('Private input permissions must be 600')
    data = json.loads(a.config.read_text())
    configuration.validate(data, json.loads(a.roborock.read_text()), True)
    if not data.get('gate_number'): raise ValueError('Verified private phone required')
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
    adb = [str(sdk/'platform-tools/adb'), '-s', a.serial]
    def run(*args, **kw):
        return subprocess.check_output(adb+list(args), stderr=subprocess.PIPE, timeout=90, **kw)
    def shell(command, **kw): return run('shell', command, **kw)
    def cert(path):
        r = subprocess.check_output([str(sdk/'build-tools/35.0.0/apksigner'), 'verify',
                                     '--print-certs', str(path)], stderr=subprocess.PIPE)
        return re.findall(rb'certificate SHA-256 digest: (\w+)', r)
    def installed(pkg):
        r = run('shell', 'pm', 'list', 'packages', '-u').decode().splitlines()
        if not r or any(not line.startswith('package:') for line in r):
            raise ValueError('Unrecognized package inventory')
        return 'package:'+pkg in r
    def checked_apk(pkg, path):
        remote = run('shell', 'pm', 'path', pkg).decode().strip()
        if not remote.startswith('package:/') or '\n' in remote: raise ValueError('Invalid APK path')
        run('pull', remote.removeprefix('package:'), str(path))
        if not cert(path) or cert(path) != cert(a.apk): raise ValueError('Signature mismatch')
    metadata = subprocess.check_output([str(sdk/'build-tools/35.0.0/aapt'), 'dump', 'badging', str(a.apk)], stderr=subprocess.PIPE).decode()
    target = re.search(r"package: name='"+re.escape(NEW)+r"' versionCode='(\d+)'", metadata)
    if not target or int(target.group(1)) < 5:
        raise ValueError('Expected new-ID APK version 5 or later')
    if not installed(OLD): raise ValueError('Legacy application absent; migration refused')
    if a.mode == 'stage':
        if installed(NEW): raise ValueError('New application exists; use verified resume directory')
        a.backup_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        if a.backup_dir.stat().st_mode & 0o077: raise ValueError('Backup directory must be private')
        backup = Path(tempfile.mkdtemp(prefix='migration-', dir=a.backup_dir))
        checked_apk(OLD, backup/'installed.apk')
        # Never do this during a call/action. The operator must establish a safe parked session.
        run('shell', 'am', 'force-stop', OLD)
        (backup/'app-data.tar').write_bytes(run('exec-out', 'run-as', OLD, 'tar', '-cf', '-', '.'))
        extract_state(backup/'app-data.tar', data['gate_number'])
        manifest = {f.name: hashlib.sha256(f.read_bytes()).hexdigest()
                    for f in (backup/'installed.apk', backup/'app-data.tar')}
        (backup/'sha256.json').write_text(json.dumps(manifest))
        for name, digest in manifest.items():
            if hashlib.sha256((backup/name).read_bytes()).hexdigest() != digest:
                raise ValueError('Backup checksum mismatch')
    else:
        backup = a.backup_dir
        if backup.stat().st_mode & 0o077: raise ValueError('Backup directory must be private')
        manifest = json.loads((backup/'sha256.json').read_text())
        if set(manifest) != {'installed.apk', 'app-data.tar'}: raise ValueError('Invalid backup manifest')
        for name, digest in manifest.items():
            if hashlib.sha256((backup/name).read_bytes()).hexdigest() != digest:
                raise ValueError('Backup checksum mismatch')
        if cert(backup/'installed.apk') != cert(a.apk): raise ValueError('Backup certificate mismatch')
    state = extract_state(backup/'app-data.tar', data['gate_number'])
    if a.mode in ('stage', 'resume'):
        # Disable the old package as well as maintenance: vendor wake cannot revive it.
        run('shell', 'am', 'force-stop', OLD)
        shell(f"run-as {OLD} sh -c 'umask 077; mkdir -p no_backup; touch no_backup/maintenance'")
        run('shell', 'pm', 'disable-user', '--user', '0', OLD)
        if not installed(NEW):
            run('install', str(a.apk))  # no replacement; an unexpected competing install fails
        else:
            checked_apk(NEW, backup/'resume-target.apk')
            # Resume only interrupted staging, not a completed target (would roll back quota).
            files = run('shell', 'run-as', NEW, 'ls', 'no_backup').decode().split()
            if 'home-config.json' in files and 'maintenance' not in files:
                raise ValueError('Target already initialized; use verify, not resume')
        run('shell', 'am', 'force-stop', NEW)
        shell(f"run-as {NEW} sh -c 'umask 077; mkdir -p no_backup shared_prefs; touch no_backup/maintenance'")
        for name, value in state.items():
            shell(f"run-as {NEW} sh -c 'cat > shared_prefs/{name}.xml'", input=value)
        result = subprocess.run(['python3', str(ROOT/'scripts/configure-device.py'), a.serial,
                                 str(a.config), '--roborock', str(a.roborock), '--enable-automation'],
                                capture_output=True, timeout=90)
        if result.returncode: raise ValueError('Configuration staging failed; both packages stay suspended')
        print('Migration staged. Open the NEW app while parked; no action was launched.')
        print('Private backup:', backup)
        return
    checked_apk(NEW, backup/'verified-target.apk')
    if hashlib.sha256((backup/'verified-target.apk').read_bytes()).digest() != hashlib.sha256(a.apk.read_bytes()).digest():
        raise ValueError('Installed target differs from expected APK')
    files = run('shell', 'run-as', NEW, 'ls', 'no_backup').decode().split()
    if any(f in files for f in ('maintenance', 'pending-config.json')) or not {'home-config.json', 'roborock.enc'} <= set(files):
        raise ValueError('New application import incomplete')
    prefs = ET.fromstring(run('exec-out', 'run-as', NEW, 'cat', 'shared_prefs/gate_settings.xml'))
    if prefs.find("string[@name='gate_number']").text != data['gate_number']: raise ValueError('New phone mismatch')
    for name in ('daily_cleaning', 'home_detector'):
        if name not in state: continue
        actual = ET.fromstring(run('exec-out', 'run-as', NEW, 'cat', f'shared_prefs/{name}.xml'))
        previous = ET.fromstring(state[name])
        if name == 'daily_cleaning':
            before = previous.find("long[@name='last_attempt_day']")
            after = actual.find("long[@name='last_attempt_day']")
            if before is not None and (after is None or int(after.get('value')) < int(before.get('value'))):
                raise ValueError('Daily quota regressed')
        # Detector flags can legitimately advance after driving; preservation is tested at import.
    disabled = run('shell', 'pm', 'list', 'packages', '-d').decode().splitlines()
    if 'package:'+OLD not in disabled: raise ValueError('Legacy application is not disabled')
    if a.mode == 'finalize':
        if not (a.confirm_functional_tested and a.confirm_wake_tested):
            raise ValueError('Functional and vendor wake verification required before removal')
        if run('uninstall', OLD).decode().strip() != 'Success': raise ValueError('Legacy removal failed')
        print('Legacy package removed; APK/data archive retained. Old Keystore key is not recoverable.')
    else:
        print('Import and exclusion verified. Hardware action/wake tests remain operator responsibilities.')


if __name__ == '__main__':
    try: main()
    except Exception as error:
        # Never print subprocess arguments, raw XML, credentials or vendor output.
        print('Migration failed safely:', str(error) if type(error) is ValueError else type(error).__name__)
        raise SystemExit(1)
