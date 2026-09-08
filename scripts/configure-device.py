#!/usr/bin/env python3
"""Install PRIVATE runtime configuration using stdin, never command-line secrets. Does not call."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

p = argparse.ArgumentParser()
p.add_argument('serial')
p.add_argument('config', type=Path)
p.add_argument('--enable-automation', action='store_true')
p.add_argument('--adb', default=str(Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk'))) / 'platform-tools/adb'))
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
source = a.config.resolve()
if source.is_relative_to(root): sys.exit('Private configuration must be outside the repository.')
data = json.loads(source.read_text())
if not data.get('gate_number_verified_on_current_device'):
    sys.exit('Refusing unverified historical number. Verify against the existing radio configuration first.')
if data.get('schema_version') != 1: sys.exit('Unsupported configuration schema.')
for role in ['parking', 'gate', 'approach', 'junction']:
    point = data.get('points', {}).get(role, {})
    if not all(isinstance(point.get(k), (int, float)) for k in ['lat', 'lon']): sys.exit('Incomplete geometry.')
payload = {k: data[k] for k in ['schema_version', 'gate_number', 'gate_number_verified_on_current_device', 'points']}
payload['automation_enabled'] = a.enable_automation
adb = [a.adb, '-s', a.serial]
package = 'pl.piotrbuchman.dudugate'
installed = subprocess.check_output(adb + ['shell', 'dumpsys', 'package', package], text=True)
import re
version = re.search(r'versionCode=(\d+)', installed)
if not version or int(version.group(1)) < 2: sys.exit('Install the menu/automation build first. Baseline not modified.')
# Only the narrow configuration file is replaced; existing app data and cooldown are untouched.
command = 'umask 077; mkdir -p no_backup && cat > no_backup/home-config.json.tmp && mv no_backup/home-config.json.tmp no_backup/home-config.json'
subprocess.run(adb + ['shell', 'run-as', package, 'sh', '-c', "'"+command+"'"], input=json.dumps(payload).encode(), check=True, stdout=subprocess.DEVNULL)
if a.enable_automation:
    for permission in ['ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION', 'ACCESS_BACKGROUND_LOCATION', 'POST_NOTIFICATIONS']:
        subprocess.run(adb + ['shell', 'pm', 'grant', package, 'android.permission.'+permission], check=True)
    subprocess.run(adb + ['shell', 'appops', 'set', package, 'SYSTEM_ALERT_WINDOW', 'allow'], check=True)
print('Private configuration installed. No app launch or call. Open Dudu Home once to apply number/setup cooldown.')
