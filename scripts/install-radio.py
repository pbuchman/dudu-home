#!/usr/bin/env python3
"""Authorized in-place update. No APK/data/map backup, rollback or saved receipt."""
import argparse
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from radio_update import (PACKAGE,Blocked,discover,physical,wait_idle,run,sha,map_manifest,
                          install_map,smoke_trip,validate_apk,block_update,fail_closed)
from navigation_config import read_navigation,validate_navigation

ROOT=Path(__file__).resolve().parents[1]
# Read-only in-memory comparison, not a data backup or restore source.
PREFERENCES=('gate_settings','daily_cleaning','journey_session','home_detector','departure_cycle',
             'trip_overlay_ui','progress_overlay_ui')

def preserved_preferences(adb):
    result={}
    for name in PREFERENCES:
        # shell protocol carries a real failure status; absence is represented explicitly.
        content=run(adb,['shell',f"run-as {PACKAGE} sh -c 'if test -f shared_prefs/{name}.xml; then cat shared_prefs/{name}.xml; fi'"])
        if content:
            try:ET.fromstring(content)
            except ET.ParseError as error:raise Blocked('Invalid safety/session preferences') from error
        result[name]=content
    result['trip-state.json']=run(adb,['shell',f"run-as {PACKAGE} sh -c 'if test -f no_backup/trip-state.json; then cat no_backup/trip-state.json; fi'"])
    return result

def finish_import(adb,old_config,canonical_nav,preserved,apk,manifest):
    # A blocked launch consumes only the inert import. It cannot start monitoring/actions.
    run(adb,['shell','am','start','-n',PACKAGE+'/.ui.MainActivity'])
    for _ in range(40):
        pending=run(adb,['shell',f"run-as {PACKAGE} sh -c 'if test -e no_backup/pending-config.json; then echo pending; fi'"])
        if not pending.strip():break
        time.sleep(.5)
    else:raise Blocked('Private import not consumed; update remains blocked')
    if json.loads(run(adb,['exec-out','run-as',PACKAGE,'cat','no_backup/home-config.json']))!=old_config:
        raise Blocked('Home configuration changed unexpectedly')
    actual=json.loads(run(adb,['exec-out','run-as',PACKAGE,'cat','no_backup/navigation.json']))
    if validate_navigation(actual)!=validate_navigation(canonical_nav):raise Blocked('Navigation verification failed')
    if preserved_preferences(adb)!=preserved:raise Blocked('Safety/session preferences changed during import')
    apk_path=run(adb,['shell','pm','path',PACKAGE]).decode().strip().removeprefix('package:')
    if not apk_path.startswith('/') or '\n' in apk_path:raise Blocked('Unsupported installed APK layout')
    installed_hash=run(adb,['shell','sha256sum',apk_path],timeout=180).decode().split()[0]
    if installed_hash!=sha(apk):raise Blocked('Installed APK differs from tested artifact')
    map_hash=run(adb,['shell','run-as',PACKAGE,'sha256sum','no_backup/places.sqlite'],timeout=900).decode().split()
    if not map_hash or map_hash[0]!=manifest['sha256']:raise Blocked('Map changed before verification completed')
    api=int(run(adb,['shell','getprop','ro.build.version.sdk']))
    for permission in ['ACCESS_COARSE_LOCATION','ACCESS_FINE_LOCATION']+(['ACCESS_BACKGROUND_LOCATION'] if api>=29 else [])+(['POST_NOTIFICATIONS'] if api>=33 else []):
        run(adb,['shell','pm','grant',PACKAGE,'android.permission.'+permission])
        permissions=run(adb,['shell','dumpsys','package',PACKAGE]).decode()
        if not re.search(r'android\.permission\.'+re.escape(permission)+r': granted=true',permissions):raise Blocked('Required runtime permission not effective')
    run(adb,['shell','appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow'])
    if b'allow' not in run(adb,['shell','appops','get',PACKAGE,'SYSTEM_ALERT_WINDOW']):raise Blocked('Overlay permission not effective')
    # Verify monitor prerequisites without allowing it to reserve/rearm anything yet.
    run(adb,['shell','am','force-stop',PACKAGE])
    run(adb,['shell','run-as',PACKAGE,'rm','-f','no_backup/maintenance','no_backup/install-verification'])
    run(adb,['shell','am','start','-n',PACKAGE+'/.ui.MainActivity'])
    time.sleep(2)
    service=run(adb,['shell','dumpsys','activity','service',PACKAGE+'/.location.HomeMonitorService']).decode()
    if 'monitor_running=true' not in service or 'registered=true' not in service:raise Blocked('Monitor startup not confirmed')
    smoke_trip(adb)
    return installed_hash

def main():
    os.umask(0o077)
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config',required=True,type=Path);p.add_argument('--roborock',required=True,type=Path)
    p.add_argument('--navigation',required=True,type=Path);p.add_argument('--map',required=True,type=Path)
    p.add_argument('--apk',required=True,type=Path)
    p.add_argument('--adb',action='append',default=[]);p.add_argument('--discovery-seconds',type=int,default=90)
    sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))
    p.add_argument('--aapt',default=str(sdk/'build-tools/35.0.0/aapt'))
    p.add_argument('--apksigner',default=str(sdk/'build-tools/35.0.0/apksigner'))
    a=p.parse_args()
    spec=importlib.util.spec_from_file_location('configure_device',ROOT/'scripts/configure-device.py')
    configure=importlib.util.module_from_spec(spec);spec.loader.exec_module(configure)
    for path in (a.config,a.roborock,a.navigation):
        configure.private_path(path)
        if path.stat().st_mode & 0o077:raise Blocked('Private input must be owner-only')
    config=json.loads(a.config.read_text());robot=json.loads(a.roborock.read_text());canonical_nav=read_navigation(a.navigation)
    # Validate all local inputs, including the real database and signature, before radio mutation.
    configure.validate(config,robot,False,canonical_nav)
    manifest=map_manifest(a.map)
    if a.map.name!='places.sqlite':raise Blocked('Managed map output must be named places.sqlite')
    validate_apk(a.apk,a.aapt,a.apksigner,navigation_schema=canonical_nav['schema_version'],map_schema=manifest['schema'])
    executables=a.adb or [str(sdk/'platform-tools/adb')]
    adb=discover(executables,config.get('device',{}).get('last_adb_serial'),a.discovery_seconds)
    physical(adb);print('Verified authorized physical radio on the local connection.',flush=True)
    version=re.search(r'versionCode=(\d+)',run(adb,['shell','dumpsys','package',PACKAGE]).decode())
    if not version:raise Blocked('Installed version unavailable')
    validate_apk(a.apk,a.aapt,a.apksigner,int(version.group(1)),canonical_nav['schema_version'],manifest['schema'])
    free=run(adb,['shell','df','-k','/data']).decode().splitlines()[-1].split()
    # One map only; no reserve for a staged or rollback copy.
    from radio_update import remote_map_guard,remote_sqlite_sidecar_guard
    remote_map_guard(adb)
    remote_sqlite_sidecar_guard(adb)
    remote=run(adb,['shell',f"run-as {PACKAGE} sh -c 'if test -f no_backup/places.sqlite; then sha256sum no_backup/places.sqlite; wc -c < no_backup/places.sqlite; fi'"],timeout=900).decode().split()
    same=bool(remote) and remote[0]==manifest['sha256']
    if not same:
        raise Blocked('Map replacement requires an enforced quiescence gate; radio left unchanged')
    old_bytes=int(remote[-1]) if remote and remote[-1].isdigit() else 0
    needed=(0 if same else max(0,a.map.stat().st_size-old_bytes))+100*1024*1024
    if len(free)<4 or int(free[3])*1024 < needed:raise Blocked('Insufficient space for update')
    old_config=json.loads(run(adb,['exec-out','run-as',PACKAGE,'cat','no_backup/home-config.json']))
    old_enabled=old_config.get('automation_enabled',False)
    if old_config.get('points')!=config.get('points'):raise Blocked('Installed geometry differs from canonical configuration')
    installed_nav=json.loads(run(adb,['exec-out','run-as',PACKAGE,'cat','no_backup/navigation.json']))
    if validate_navigation(installed_nav)!=validate_navigation(canonical_nav):raise Blocked('Installed navigation differs from canonical source')
    wait_idle(adb)
    block_update(adb)
    try:
        # The block prevents new admission; existing work must still finish before stop.
        wait_idle(adb)
        run(adb,['shell','am','force-stop',PACKAGE])
        preserved=preserved_preferences(adb)
        command=[sys.executable,str(ROOT/'scripts/configure-device.py'),adb[2],str(a.config),'--roborock',str(a.roborock),
                 '--navigation',str(a.navigation),'--apk',str(a.apk),'--adb',adb[0],'--aapt',a.aapt,'--apksigner',a.apksigner]
        if old_enabled:command.append('--enable-automation')
        result=subprocess.run(command,capture_output=True,timeout=900)
        if result.returncode:raise Blocked('In-place APK update/import failed; update remains blocked')
        changed=install_map(adb,a.map,manifest)
        finish_import(adb,old_config,canonical_nav,preserved,a.apk,manifest)
        print('PASS: exact APK installed; map '+('replaced and verified' if changed else 'unchanged, transfer skipped')+
              '; configuration and safety/session state preserved. Driving/wake not tested.')
    except BaseException:
        fail_closed(adb)
        raise
if __name__=='__main__':
    try:main()
    except (Blocked,ValueError,OSError,subprocess.SubprocessError) as error:
        print('BLOCKED: '+(str(error) if isinstance(error,Blocked) else type(error).__name__),file=sys.stderr);sys.exit(1)
