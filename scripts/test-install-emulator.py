#!/usr/bin/env python3
"""Exercise the real update/import CLI on an emulator, using synthetic credentials only.

Requires the instrumentation APK. Never contacts Roborock or operates a physical radio.
"""
import argparse
from contextlib import contextmanager
import importlib.util
import json
import os
import runpy
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.pbuchman.duduhome'


@contextmanager
def prerequisite_display(run):
    """Keep installer prerequisites independent of the preceding layout profile."""
    fixed = run('shell', 'wm', 'fixed-to-user-rotation').decode().strip()
    if fixed not in ('default', 'disabled', 'enabled', 'enabled_if_no_auto_rotation'):
        raise RuntimeError('Unrecognized emulator rotation policy')
    rotation = run('shell', 'wm', 'user-rotation').decode().split()
    if not rotation or rotation[0] not in ('free', 'lock'):
        raise RuntimeError('Unrecognized emulator user rotation')
    size = re.search(r'Override size: (\d+x\d+)', run('shell', 'wm', 'size').decode())
    density = re.search(r'Override density: (\d+)', run('shell', 'wm', 'density').decode())
    saved = {key: run('shell', 'settings', 'get', 'system', key).decode().strip()
             for key in ('font_scale', 'accelerometer_rotation', 'user_rotation')}
    try:
        run('shell', 'wm', 'fixed-to-user-rotation', 'enabled')
        run('shell', 'wm', 'size', '1200x2000')
        run('shell', 'wm', 'density', '240')
        run('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
        run('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
        run('shell', 'settings', 'put', 'system', 'user_rotation', '1')
        run('shell', 'wm', 'user-rotation', 'lock', '1')
        spec = importlib.util.spec_from_file_location('capture_window_layout', ROOT/'scripts/capture-window-layout.py')
        layouts = importlib.util.module_from_spec(spec); spec.loader.exec_module(layouts)
        layouts.wait_display(run, 2000, 1200, 240, 1.0)
        yield
    finally:
        run('shell', 'wm', 'size', size.group(1) if size else 'reset')
        run('shell', 'wm', 'density', density.group(1) if density else 'reset')
        for key, value in saved.items():
            if value == 'null': run('shell', 'settings', 'delete', 'system', key)
            else: run('shell', 'settings', 'put', 'system', key, value)
        run('shell', 'wm', 'user-rotation', *rotation)
        run('shell', 'wm', 'fixed-to-user-rotation', fixed)


def main():
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('serial')
    p.add_argument('--configure-fixture', nargs=argparse.REMAINDER, help=argparse.SUPPRESS)
    a = p.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
    adb = [str(sdk/'platform-tools/adb'), '-s', a.serial]
    def run(*args): return subprocess.check_output(adb + list(args), stderr=subprocess.PIPE)
    if not a.serial.startswith('emulator-') or run('shell', 'getprop', 'ro.hardware').strip() not in (b'ranchu', b'goldfish'):
        raise RuntimeError('Emulator only')
    if a.configure_fixture is not None:
        # Only this synthetic emulator test replaces the radio-specific idle source.
        # All installer validation/import/signature code still executes unchanged.
        import radio_update
        def emulator_idle(candidate, seconds=300):
            if candidate != adb or run('shell', 'getprop', 'ro.hardware').strip() not in (b'ranchu', b'goldfish'):
                raise RuntimeError('Synthetic idle fixture requires the explicitly verified emulator')
            if b'package:' in run('shell', 'pm', 'list', 'packages', 'com.syu.ms'):
                raise RuntimeError('Synthetic idle fixture refuses a vendor call service')
        radio_update.wait_idle = emulator_idle
        check_output = subprocess.check_output
        def observe_install(command, *values, **options):
            try:
                return check_output(command, *values, **options)
            except subprocess.CalledProcessError as error:
                # The production CLI deliberately sanitizes ADB stderr. Record only the
                # precise synthetic signer-failure category, never the raw device output.
                if (list(command)[:len(adb)+2] == [*adb, 'install', '-r']
                        and b'INSTALL_FAILED_UPDATE_INCOMPATIBLE' in (error.output or b'') + (error.stderr or b'')):
                    print('SYNTHETIC_INSTALL_SIGNATURE_MISMATCH', file=sys.stderr)
                raise
        subprocess.check_output = observe_install
        sys.argv = [str(ROOT/'scripts/configure-device.py'), *a.configure_fixture]
        runpy.run_path(sys.argv[0], run_name='__main__')
        return
    with prerequisite_display(run):
        check_install(a, sdk, adb, run)


def check_install(a, sdk, adb, run):
    if int(run('shell','getprop','ro.build.version.sdk')) >= 33:
        run('shell','pm','grant',PACKAGE,'android.permission.POST_NOTIFICATIONS')
    result = run('shell', 'am', 'instrument', '-w', PACKAGE+'.test/'+PACKAGE+'.SafetyChecks')
    evidence = ROOT/'build/install-emulator'; evidence.mkdir(parents=True, exist_ok=True)
    (evidence/'prerequisites.txt').write_bytes(result)
    if b'PASS:' not in result or b'FAIL:' in result:
        raise RuntimeError('Synthetic prerequisites failed: '+result.decode(errors='replace'))
    before = run('exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/daily_cleaning.xml')
    # Seed a consumed gate opportunity for this emulator boot; imports and restarts must retain it.
    run('shell', 'am', 'force-stop', PACKAGE)
    boot = int(run('shell', 'settings', 'get', 'global', 'boot_count').strip())
    departure = (f'<map><int name="boot" value="{boot}"/><long name="counter" value="0"/>'
                 '<long name="generation" value="123"/><string name="baseline">PARKING</string>'
                 '<boolean name="consumed" value="true"/><boolean name="away" value="false"/></map>').encode()
    subprocess.run(adb+['shell', f"run-as {PACKAGE} sh -c 'umask 077; cat > shared_prefs/departure_cycle.xml'"],
                   input=departure, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    departure_before = run('exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/departure_cycle.xml')
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix='dudu-home-install-check-') as temporary:
        private = Path(temporary)
        robot = dict(schema_version=1, api_base_url='https://api-eu.roborock.com', routine_id=7,
                     routine_name='Full Cleaning', full_mop_routine_id=8,
                     auth=dict(u='example-user', s='example-session', h='example-secret'))
        config = dict(schema_version=2, gate_number='0000', points=None, roborock=robot)
        source = private/'input.json'; source.write_text(json.dumps(config))
        navigation = dict(schema_version=2, slots=[dict(slot=2, label='Fixture group', icon='squash', destinations=[
            dict(label='Fixture A', icon='pin', latitude=0, longitude=0),
            dict(label='Fixture B', icon='pin', address='Synthetic address', navigate_by='address', latitude=0.001, longitude=0.002)])])
        nav_source = private/'navigation.json'; nav_source.write_text(json.dumps(navigation))
        command = [sys.executable, str(Path(__file__).resolve()), a.serial, '--configure-fixture', a.serial, str(source),
                   '--navigation', str(nav_source), '--apk', str(ROOT/'app/build/outputs/apk/debug/app-debug.apk')]
        rejected = subprocess.run(command, capture_output=True)
        if rejected.returncode == 0 or b'Phone differs' not in rejected.stderr:
            raise RuntimeError('Mismatched phone was not rejected')
        config['gate_number'] = '000000000'; source.write_text(json.dumps(config))
        # Malformed local APK must fail before creating staging or maintenance.
        bad_apk=private/'bad.apk';bad_apk.write_bytes(b'not an APK')
        invalid=list(command);invalid[-1]=str(bad_apk)
        if subprocess.run(invalid,capture_output=True).returncode==0:raise RuntimeError('Bad APK admitted')
        # Simulate resuming an outer update after partial map transfer. Configuration import
        # must preserve the outer block even after it has consumed pending-config.json.
        run('shell','run-as',PACKAGE,'touch','no_backup/install-verification')
        trip_state=json.dumps(dict(state='PAUSED',generation=432,meters=1234.5,time_ms=987000,partial_time=False)).encode()
        subprocess.run(adb+['shell',f"run-as {PACKAGE} sh -c 'cat > no_backup/trip-state.json'"],input=trip_state,check=True,capture_output=True)
        state_paths=['shared_prefs/'+name+'.xml' for name in ('gate_settings','daily_cleaning','journey_session','departure_cycle')]+['no_backup/trip-state.json']
        state_before={name:run('exec-out','run-as',PACKAGE,'cat',name) for name in state_paths}
        # Generate a disposable test signer. Package Manager must reject this otherwise
        # valid replacement, retaining installed data and the outer maintenance block.
        keytool=str(Path(os.environ.get('JAVA_HOME','/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home'))/'bin/keytool')
        if not Path(keytool).is_file():keytool='keytool'
        store=private/'synthetic-signing.p12'
        subprocess.run([keytool,'-genkeypair','-keystore',str(store),'-storepass','synthetic-test-only',
                        '-keypass','synthetic-test-only','-alias','synthetic','-dname','CN=Synthetic',
                        '-keyalg','RSA','-validity','1'],check=True,capture_output=True)
        wrong=private/'wrong-signed.apk'
        subprocess.run([str(sdk/'build-tools/35.0.0/apksigner'),'sign','--ks',str(store),
                        '--ks-pass','pass:synthetic-test-only','--out',str(wrong),command[-1]],check=True,capture_output=True)
        rejected_command=list(command);rejected_command[-1]=str(wrong)
        signer_rejection=subprocess.run(rejected_command,capture_output=True)
        (evidence/'signer-rejection.txt').write_bytes(signer_rejection.stderr)
        if signer_rejection.returncode==0 or b'SYNTHETIC_INSTALL_SIGNATURE_MISMATCH' not in signer_rejection.stderr:
            raise RuntimeError('Different signer was not rejected specifically by Package Manager signature validation')
        for name,content in state_before.items():
            if run('exec-out','run-as',PACKAGE,'cat',name)!=content:raise RuntimeError('Signer rejection changed existing state')
        if b'install-verification' not in run('shell','run-as',PACKAGE,'ls','no_backup'):raise RuntimeError('Signer rejection released block')
        subprocess.run(command, check=True, capture_output=True)
        if any(private.glob('before-update-*')) or (private/'backups').exists():raise RuntimeError('Unexpected backup artifacts')
        if not run('exec-out', 'run-as', PACKAGE, 'cat', 'no_backup/pending-config.json'):
            raise RuntimeError('No staged configuration')
        run('shell', 'am', 'start', '-n', PACKAGE+'/.ui.MainActivity')
        for _ in range(40):
            # shell protocol propagates exit status; exec-out may return zero for a failed cat.
            staged = subprocess.run(adb+['shell', 'run-as', PACKAGE, 'test', '-e', 'no_backup/pending-config.json'], capture_output=True)
            if staged.returncode != 0: break
            time.sleep(0.5)
        else: raise RuntimeError('Import was not consumed')
        if b'install-verification' not in run('shell','run-as',PACKAGE,'ls','no_backup'):raise RuntimeError('Import released outer block')
        for name,content in state_before.items():
            if run('exec-out','run-as',PACKAGE,'cat',name)!=content:raise RuntimeError('Update changed safety/session state')
        # A second explicit import works even when the first has already been consumed.
        subprocess.run(command,check=True,capture_output=True)
        run('shell','am','start','-n',PACKAGE+'/.ui.MainActivity')
        for _ in range(40):
            staged=subprocess.run(adb+['shell','run-as',PACKAGE,'test','-e','no_backup/pending-config.json'],capture_output=True)
            if staged.returncode:break
            time.sleep(.5)
        else:raise RuntimeError('Explicit import retry was not consumed')
        for name,content in state_before.items():
            if run('exec-out','run-as',PACKAGE,'cat',name)!=content:raise RuntimeError('Retry changed safety/session state')
        run('shell','run-as',PACKAGE,'rm','no_backup/install-verification')
        cipher = run('exec-out', 'run-as', PACKAGE, 'cat', 'no_backup/roborock.enc')
        if len(cipher) < 28 or b'example-secret' in cipher: raise RuntimeError('Encrypted credentials not installed')
        geometry = json.loads(run('exec-out', 'run-as', PACKAGE, 'cat', 'no_backup/home-config.json'))
        if geometry.get('automation_enabled') or any(key in geometry for key in ('roborock', 'gate_number', 'navigation')):
            raise RuntimeError('Private sections were not separated')
        saved_navigation = json.loads(run('exec-out', 'run-as', PACKAGE, 'cat', 'no_backup/navigation.json'))
        if (saved_navigation['slots'][1]['destinations'][0]['label'] != 'Fixture A'
                or saved_navigation['slots'][1]['destinations'][1]['navigate_by'] != 'address'
                or saved_navigation['slots'][2]['destinations'] != []):
            raise RuntimeError('Navigation import or empty slots failed')
        if before != run('exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/daily_cleaning.xml'):
            raise RuntimeError('Update reset daily quota')
        if departure_before != run('exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/departure_cycle.xml'):
            raise RuntimeError('Update reset consumed gate opportunity')
        run('shell', 'am', 'force-stop', PACKAGE)
        run('shell', 'am', 'start', '-n', PACKAGE+'/.ui.MainActivity')
        if before != run('exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/daily_cleaning.xml'):
            raise RuntimeError('Restart reset daily quota')
        if departure_before != run('exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/departure_cycle.xml'):
            raise RuntimeError('Restart reset consumed gate opportunity')
    # Clear only synthetic robot credentials, preserving a safe menu for visual inspection.
    run('shell', 'run-as', PACKAGE, 'rm', 'no_backup/roborock.enc')
    print('PASS: mismatched phone refused; locally verified in-place update without backups, retry under persistent block, preserved cooldown/journey/trip state, encrypted one-time import, private navigation, quota and gate opportunity across restart; no calls or cleaning')


if __name__ == '__main__': main()
