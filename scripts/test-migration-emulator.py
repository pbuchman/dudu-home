#!/usr/bin/env python3
"""Destructive ONLY to synthetic emulator apps. Requires a matching signed legacy APK."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
OLD = 'pl.piotrbuchman.dudugate'
NEW = 'com.pbuchman.duduhome'


def main():
    p = argparse.ArgumentParser(); p.add_argument('serial'); p.add_argument('--legacy-apk', required=True)
    a = p.parse_args(); os.umask(0o077)
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
    adb = [str(sdk/'platform-tools/adb'), '-s', a.serial]
    def run(*args, **kw): return subprocess.check_output(adb+list(args), stderr=subprocess.PIPE, **kw)
    assert a.serial.startswith('emulator-') and run('shell', 'getprop', 'ro.hardware').strip() in (b'ranchu', b'goldfish')
    subprocess.run(adb+['uninstall', NEW], capture_output=True)
    run('install', '-r', '-d', a.legacy_apk)
    run('shell', 'pm', 'enable', OLD)
    run('shell', 'pm', 'clear', OLD)
    future = str(int(time.time() * 1000) + 60000) # synthetic cooldown, not a phone literal
    prefs = {
        'gate_settings': ('<map><string name="gate_number">000000000</string>'
                          f'<long name="configured_at" value="{future}"/>'
                          f'<long name="last_dial_started_at" value="{future}"/></map>').encode(),
        'daily_cleaning': b'<map><long name="last_attempt_day" value="99999"/></map>',
        'home_detector': b'<map><string name="revision">synthetic</string><int name="flags" value="31"/></map>'}
    run('shell', f'run-as {OLD} mkdir -p shared_prefs')
    for name, value in prefs.items():
        run('shell', f"run-as {OLD} sh -c 'cat > shared_prefs/{name}.xml'", input=value)
    with tempfile.TemporaryDirectory(prefix='dudu-migration-test-') as temporary:
        folder = Path(temporary)
        config = dict(schema_version=2, gate_number='000000000', points={
            'parking':dict(lat=1.0,lon=1.0), 'gate':dict(lat=1.001,lon=1.0),
            'approach':dict(lat=1.002,lon=1.0),'junction':dict(lat=1.003,lon=1.0)})
        robot = dict(schema_version=1, api_base_url='https://api-eu.roborock.com', routine_name='Full Cleaning',
                     routine_id=7, full_mop_routine_id=8, auth=dict(u='example-user',s='example-session',h='example-secret'))
        (folder/'config.json').write_text(json.dumps(config)); (folder/'robot.json').write_text(json.dumps(robot))
        common = [a.serial,'--config',str(folder/'config.json'),'--roborock',str(folder/'robot.json'),
                  '--apk',str(ROOT/'app/build/outputs/apk/debug/app-debug.apk')]
        def migrate(mode, backup, *flags):
            return subprocess.run(['python3',str(ROOT/'scripts/migrate-device.py'),mode]+common+
                                  ['--backup-dir',str(backup)]+list(flags),capture_output=True)
        result=migrate('stage', folder/'backups')
        assert result.returncode==0, result.stdout.decode()
        backup=next((folder/'backups').glob('migration-*'))
        assert ('package:'+OLD) in run('shell','pm','list','packages','-d').decode()
        for name,value in prefs.items():
            assert run('exec-out','run-as',NEW,'cat',f'shared_prefs/{name}.xml')==value
        assert migrate('stage',folder/'backups').returncode!=0
        # Interrupted stage: both apps are inert; resume restores/imports without launching.
        assert migrate('resume',backup).returncode==0
        assert migrate('verify',backup).returncode!=0
        run('shell','am','start','-n',NEW+'/.ui.MainActivity')
        for _ in range(40):
            files=run('shell','run-as',NEW,'ls','no_backup').decode().split()
            if 'pending-config.json' not in files: break
            time.sleep(0.25)
        assert 'maintenance' not in files and 'roborock.enc' in files
        cipher=run('exec-out','run-as',NEW,'cat','no_backup/roborock.enc')
        assert len(cipher)>28 and b'example-secret' not in cipher
        assert prefs['daily_cleaning']==run('exec-out','run-as',NEW,'cat','shared_prefs/daily_cleaning.xml')
        assert migrate('resume',backup).returncode!=0  # cannot reset a live app's state
        assert migrate('verify',backup).returncode==0
        assert migrate('finalize',backup).returncode!=0
        assert ('package:'+OLD) in run('shell','pm','list','packages','-u').decode()
        assert migrate('finalize',backup,'--confirm-functional-tested','--confirm-wake-tested').returncode==0
        assert ('package:'+OLD) not in run('shell','pm','list','packages').decode().splitlines()
    run('shell','am','force-stop',NEW)
    run('shell','pm','clear',NEW)
    print('PASS: migration state, interruption/resume, encrypted import, quota, old-package exclusion, guarded removal; emulator only')


if __name__=='__main__': main()
