#!/usr/bin/env python3
"""Exercise the real update/import CLI on an emulator, using synthetic credentials only.

Requires the instrumentation APK. Never contacts Roborock or operates a physical radio.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.pbuchman.duduhome'


def main():
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('serial'); a = p.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
    adb = [str(sdk/'platform-tools/adb'), '-s', a.serial]
    def run(*args): return subprocess.check_output(adb + list(args), stderr=subprocess.PIPE)
    if not a.serial.startswith('emulator-') or run('shell', 'getprop', 'ro.hardware').strip() not in (b'ranchu', b'goldfish'):
        raise RuntimeError('Emulator only')
    result = run('shell', 'am', 'instrument', '-w', PACKAGE+'.test/'+PACKAGE+'.SafetyChecks')
    if b'PASS:' not in result or b'FAIL:' in result: raise RuntimeError('Synthetic prerequisites failed')
    before = run('exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/daily_cleaning.xml')
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix='dudu-home-install-check-') as temporary:
        private = Path(temporary)
        robot = dict(schema_version=1, api_base_url='https://api-eu.roborock.com', routine_id=7,
                     routine_name='Full Cleaning', full_mop_routine_id=8,
                     auth=dict(u='example-user', s='example-session', h='example-secret'))
        config = dict(schema_version=2, gate_number='0000', points=None, roborock=robot)
        source = private/'input.json'; source.write_text(json.dumps(config))
        command = [sys.executable, str(ROOT/'scripts/configure-device.py'), a.serial, str(source),
                   '--apk', str(ROOT/'app/build/outputs/apk/debug/app-debug.apk'), '--backup-dir', str(private/'backups')]
        rejected = subprocess.run(command, capture_output=True)
        if rejected.returncode == 0 or b'Phone differs' not in rejected.stderr:
            raise RuntimeError('Mismatched phone was not rejected')
        config['gate_number'] = '000000000'; source.write_text(json.dumps(config))
        subprocess.run(command, check=True, capture_output=True)
        if not run('exec-out', 'run-as', PACKAGE, 'cat', 'no_backup/pending-config.json'):
            raise RuntimeError('No staged configuration')
        run('shell', 'am', 'start', '-n', PACKAGE+'/.ui.MainActivity')
        for _ in range(40):
            # shell protocol propagates exit status; exec-out may return zero for a failed cat.
            staged = subprocess.run(adb+['shell', 'run-as', PACKAGE, 'test', '-e', 'no_backup/pending-config.json'], capture_output=True)
            if staged.returncode != 0: break
            time.sleep(0.5)
        else: raise RuntimeError('Import was not consumed')
        cipher = run('exec-out', 'run-as', PACKAGE, 'cat', 'no_backup/roborock.enc')
        if len(cipher) < 28 or b'example-secret' in cipher: raise RuntimeError('Encrypted credentials not installed')
        geometry = json.loads(run('exec-out', 'run-as', PACKAGE, 'cat', 'no_backup/home-config.json'))
        if geometry.get('automation_enabled') or 'roborock' in geometry or 'gate_number' in geometry:
            raise RuntimeError('Private sections were not separated')
        if before != run('exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/daily_cleaning.xml'):
            raise RuntimeError('Update reset daily quota')
        run('shell', 'am', 'force-stop', PACKAGE)
        run('shell', 'am', 'start', '-n', PACKAGE+'/.ui.MainActivity')
        if before != run('exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/daily_cleaning.xml'):
            raise RuntimeError('Restart reset daily quota')
    # Clear only synthetic robot credentials, preserving a safe menu for visual inspection.
    run('shell', 'run-as', PACKAGE, 'rm', 'no_backup/roborock.enc')
    print('PASS: mismatched phone refused; signature-checked backup/update, encrypted one-time import, quota across restart; no calls or cleaning')


if __name__ == '__main__': main()
