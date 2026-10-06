#!/usr/bin/env python3
"""Capture the actual app on an explicitly selected emulator; only synthetic fixture data."""
import argparse
import hashlib
import re
import json
import os
from pathlib import Path
import subprocess
import time
ROOT=Path(__file__).resolve().parents[1]
PACKAGE='com.pbuchman.duduhome'
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('serial');a=p.parse_args()
    sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))
    adb=[str(sdk/'platform-tools/adb'),'-s',a.serial]
    def run(*args):return subprocess.check_output([*adb,*args],stderr=subprocess.PIPE)
    if not a.serial.startswith('emulator-') or run('shell','getprop','ro.hardware').strip() not in (b'ranchu',b'goldfish'):raise RuntimeError('Emulator only')
    run('shell','wm','size','1200x2000');run('shell','wm','density','240')
    run('shell','settings','put','system','accelerometer_rotation','0');run('shell','settings','put','system','user_rotation','1')
    run('shell','settings','put','system','font_scale','1.0')
    for setting in ('window_animation_scale','transition_animation_scale','animator_duration_scale'):
        run('shell','settings','put','global',setting,'0')
    run('install','-r',str(ROOT/'app/build/outputs/apk/debug/app-debug.apk'))
    run('install','-r',str(ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
    run('shell','pm','clear',PACKAGE)
    run('shell','cmd','locale','set-app-locales',PACKAGE,'--user','0','--locales','pl-PL')
    if b'pl-PL' not in run('shell','cmd','locale','get-app-locales',PACKAGE,'--user','0'):raise RuntimeError('Locale not applied')
    run('shell','appops','set',PACKAGE,'SYSTEM_ALERT_WINDOW','allow')
    if int(run('shell','getprop','ro.build.version.sdk'))>=33:run('shell','pm','grant',PACKAGE,'android.permission.POST_NOTIFICATIONS')
    output=run('shell','am','instrument','-w','-e','gallery','true',PACKAGE+'.test/'+PACKAGE+'.SafetyChecks')
    if b'PASS:' not in output or b'FAIL:' in output:raise RuntimeError(output.decode())
    target=ROOT/'build/ui-checks/gallery';target.mkdir(parents=True,exist_ok=True)
    remote='/sdcard/Android/data/'+PACKAGE+'/files/'
    names=json.loads(run('shell','cat',remote+'gallery-files.json'))
    for name in names:
        if Path(name).name!=name or not name.endswith('.png'):raise RuntimeError('Invalid capture name')
        run('pull',remote+name,str(target/name))
    manifest={'application_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).decode().strip(),
              'working_tree_changes':bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT)),
              'apk_sha256':hashlib.sha256((ROOT/'app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest(),
              'version':re.search(r"versionName '([^']+)'",(ROOT/'app/build.gradle').read_text())[1],'dimensions':[2000,1200],'density':240,'locale':'pl-PL','font_scale':1.0,
              'synthetic':True,'files':names}
    (target/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(f'PASS: {len(names)} real emulator captures in build/ui-checks/gallery; review pixels before publication')
if __name__=='__main__':main()
