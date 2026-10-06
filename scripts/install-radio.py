#!/usr/bin/env python3
"""Authorized unattended update. Existing ADB authorization and owner-only inputs are required."""
import argparse
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import time
import xml.etree.ElementTree as ET
from radio_update import PACKAGE,Blocked,discover,physical,wait_idle,run,sha,map_manifest,install_map,smoke_trip
from navigation_config import read_navigation

ROOT=Path(__file__).resolve().parents[1]
def main():
    os.umask(0o077)
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config',required=True,type=Path);p.add_argument('--roborock',required=True,type=Path)
    p.add_argument('--navigation',required=True,type=Path);p.add_argument('--map',required=True,type=Path)
    p.add_argument('--apk',required=True,type=Path);p.add_argument('--backup-dir',required=True,type=Path)
    p.add_argument('--adb',action='append',default=[]);p.add_argument('--discovery-seconds',type=int,default=90)
    a=p.parse_args()
    for path in (a.config,a.roborock,a.navigation):
        if path.stat().st_mode & 0o077:raise Blocked('Private input must be owner-only')
    config=json.loads(a.config.read_text())
    executables=a.adb or [str(Path.home()/'Library/Android/sdk/platform-tools/adb')]
    adb=discover(executables,config.get('device',{}).get('last_adb_serial'),a.discovery_seconds)
    physical(adb);print('Verified authorized physical radio on the local connection.',flush=True)
    manifest=map_manifest(a.map)
    free=run(adb,['shell','df','-k','/data']).decode().splitlines()[-1].split()
    if len(free)<4 or int(free[3])*1024 < a.map.stat().st_size*2+100*1024*1024:raise Blocked('Insufficient space for safe map replacement')
    wait_idle(adb)
    old_config=json.loads(run(adb,['exec-out','run-as',PACKAGE,'cat','no_backup/home-config.json']))
    old_enabled=old_config.get('automation_enabled',False)
    if old_config.get('points')!=config.get('points'):raise Blocked('Installed geometry differs from canonical configuration')
    # Read actual navigation before staging. Do not silently restore an older shorter list.
    installed_nav=json.loads(run(adb,['exec-out','run-as',PACKAGE,'cat','no_backup/navigation.json']))
    canonical_nav=read_navigation(a.navigation)
    from navigation_config import validate_navigation
    if validate_navigation(installed_nav)!=validate_navigation(canonical_nav):raise Blocked('Installed navigation differs from canonical source')
    preserved={}
    for name in ('gate_settings','daily_cleaning','journey_session','home_detector'):
        result=subprocess.run([*adb,'exec-out','run-as',PACKAGE,'cat',f'shared_prefs/{name}.xml'],capture_output=True)
        try:ET.fromstring(result.stdout);preserved[name]=result.stdout
        except ET.ParseError:pass
    a.backup_dir.mkdir(parents=True,exist_ok=True,mode=0o700)
    previous=set(a.backup_dir.glob('before-update-*'))
    # This marker is honored independently of the one-time import and blocks all monitor startup.
    run(adb,['shell',f"run-as {PACKAGE} sh -c 'mkdir -p no_backup; touch no_backup/install-verification'"])
    backup=None;released=False
    try:
        command=[sys.executable,str(ROOT/'scripts/configure-device.py'),adb[2],str(a.config),'--roborock',str(a.roborock),
                 '--navigation',str(a.navigation),'--apk',str(a.apk),'--backup-dir',str(a.backup_dir),'--adb',adb[0]]
        if old_enabled:command.append('--enable-automation')
        result=subprocess.run(command,capture_output=True,timeout=900)
        backups=set(a.backup_dir.glob('before-update-*'))-previous
        if len(backups)==1:backup=backups.pop()
        if result.returncode:raise Blocked('Backed-up update/import failed')
        install_map(adb,a.map,manifest)
        run(adb,['shell','am','start','-n',PACKAGE+'/.ui.MainActivity'])
        for _ in range(40):
            check=subprocess.run([*adb,'shell','run-as',PACKAGE,'test','-e','no_backup/pending-config.json'],capture_output=True)
            if check.returncode:break
            time.sleep(.5)
        else:raise Blocked('Private import not consumed')
        if json.loads(run(adb,['exec-out','run-as',PACKAGE,'cat','no_backup/home-config.json']))!=old_config:
            raise Blocked('Home configuration changed unexpectedly')
        saved_nav=json.loads(run(adb,['exec-out','run-as',PACKAGE,'cat','no_backup/navigation.json']))
        if validate_navigation(saved_nav)!=validate_navigation(canonical_nav):raise Blocked('Navigation verification failed')
        for name,content in preserved.items():
            actual=run(adb,['exec-out','run-as',PACKAGE,'cat',f'shared_prefs/{name}.xml'])
            if actual!=content:raise Blocked('Safety/session preferences changed during import')
        apk_path=run(adb,['shell','pm','path',PACKAGE]).decode().strip().removeprefix('package:')
        installed_hash=run(adb,['shell','sha256sum',apk_path]).decode().split()[0]
        if installed_hash!=sha(a.apk):raise Blocked('Installed APK differs from tested artifact')
        api=int(run(adb,['shell','getprop','ro.build.version.sdk']))
        for permission in ['ACCESS_COARSE_LOCATION','ACCESS_FINE_LOCATION']+(['ACCESS_BACKGROUND_LOCATION'] if api>=29 else [])+(['POST_NOTIFICATIONS'] if api>=33 else []):
            run(adb,['shell','pm','grant',PACKAGE,'android.permission.'+permission])
            permissions=run(adb,['shell','dumpsys','package',PACKAGE]).decode()
            if not re.search(r'android\.permission\.'+re.escape(permission)+r': granted=true',permissions):raise Blocked('Required runtime permission not effective')
        run(adb,['shell','appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow'])
        if b'allow' not in run(adb,['shell','appops','get',PACKAGE,'SYSTEM_ALERT_WINDOW']):raise Blocked('Overlay permission not effective')
        run(adb,['shell','run-as',PACKAGE,'rm','no_backup/install-verification'])
        released=True
        run(adb,['shell','am','start','-n',PACKAGE+'/.ui.MainActivity'])
        time.sleep(2)
        service=run(adb,['shell','dumpsys','activity','service',PACKAGE+'/.location.HomeMonitorService']).decode()
        if 'monitor_running=true' not in service or 'registered=true' not in service:raise Blocked('Monitor startup not confirmed')
        receipt={'status':'installed','apk_sha256':installed_hash,'map_sha256':manifest['sha256'],
                 'configuration_preserved':True,'monitor_registered':True,'road_test':'not performed','ui_smoke':smoke_trip(adb)}
        (backup/'installation-result.json').write_text(json.dumps(receipt,indent=2)+'\n')
        print('PASS: exact APK installed, map verified, full configuration preserved, monitor registered. Driving/wake not tested.')
    except BaseException:
        # After monitoring resumes, restoring old state could rearm an action: keep current state.
        if released:raise Blocked('Post-start verification failed; current safety state preserved, inspect private backup')
        if backup and (backup/'installed.apk').is_file() and (backup/'app-data.tar').is_file():
            run(adb,['shell','am','force-stop',PACKAGE])
            run(adb,['install','-r','-d',str(backup/'installed.apk')],timeout=180)
            with tarfile.open(backup/'app-data.tar') as archive:
                for member in archive:
                    if member.name.startswith('/') or '..' in Path(member.name).parts:raise Blocked('Unsafe recovery archive')
            with (backup/'app-data.tar').open('rb') as stream:
                run(adb,['shell',f"run-as {PACKAGE} tar -xf -"],stdin=stream,timeout=900)
            run(adb,['shell','run-as',PACKAGE,'rm','-f','no_backup/install-verification','no_backup/pending-config.json','no_backup/maintenance'])
            run(adb,['shell','am','start','-n',PACKAGE+'/.ui.MainActivity'])
            time.sleep(2)
            restored=run(adb,['shell','dumpsys','activity','services',PACKAGE]).decode()
            if 'HomeMonitorService' not in restored:raise Blocked('Rollback monitor restart unconfirmed')
            old_path=run(adb,['shell','pm','path',PACKAGE]).decode().strip().removeprefix('package:')
            if run(adb,['shell','sha256sum',old_path]).decode().split()[0]!=sha(backup/'installed.apk'):raise Blocked('Rollback APK verification failed')
            raise Blocked('Update failed; previous APK restored and checked, private backup retained')
        run(adb,['shell','run-as',PACKAGE,'rm','-f','no_backup/install-verification'])
        run(adb,['shell','am','start','-n',PACKAGE+'/.ui.MainActivity'])
        raise
if __name__=='__main__':
    try:main()
    except (Blocked,ValueError,OSError,subprocess.SubprocessError) as error:
        print('BLOCKED: '+(str(error) if isinstance(error,Blocked) else type(error).__name__),file=sys.stderr);sys.exit(1)
