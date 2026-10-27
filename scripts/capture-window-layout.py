#!/usr/bin/env python3
"""Render synthetic app windows on an explicitly chosen emulator; never accepts a radio."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import struct
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.pbuchman.duduhome'
PROFILES = {
    'radio': (2000, 1200, 240, 1.0),
    'radio-large-font': (2000, 1200, 240, 1.6),
    'landscape': (1280, 720, 240, 1.0),
    'compact': (800, 480, 160, 1.0),
    'compact-large-font': (800, 480, 160, 1.6),
}


def png_size(payload):
    if len(payload) < 24 or payload[:8] != b'\x89PNG\r\n\x1a\n':
        raise RuntimeError('Display capture did not return a PNG')
    return struct.unpack('>II', payload[16:24])


def wait_display(run, width, height, density, font):
    """Wait for observed compositor pixels, not merely accepted wm/settings writes."""
    deadline = time.monotonic() + 15
    stable = 0
    observed = None
    while time.monotonic() < deadline:
        size = png_size(run('exec-out', 'screencap', '-p'))
        densities = re.findall(r'(?:Physical|Override) density: (\d+)', run('shell', 'wm', 'density').decode())
        actual_density = int(densities[-1]) if densities else None
        actual_font = float(run('shell', 'settings', 'get', 'system', 'font_scale'))
        observed = dict(size=list(size), density=actual_density, font_scale=actual_font)
        matches = size == (width, height) and actual_density == density and abs(actual_font - font) < 0.001
        stable = stable + 1 if matches else 0
        if stable >= 2:
            return observed
        time.sleep(0.2)
    raise RuntimeError(f'Display did not settle at {(width, height, density, font)}: {observed}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('serial')
    parser.add_argument('--profile', choices=PROFILES, action='append')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/window-layouts')
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
    adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]

    def run(*command):
        return subprocess.check_output([*adb, *command], stderr=subprocess.PIPE)

    if not args.serial.startswith('emulator-') or run('shell', 'getprop', 'ro.hardware').strip() not in (b'ranchu', b'goldfish'):
        raise RuntimeError('Emulator only: refusing physical-device access')
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    run('install', '-r', str(apk))
    run('install', '-r', str(ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
    for setting in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
        run('shell', 'settings', 'put', 'global', setting, '0')
    fixed_rotation = run('shell', 'wm', 'fixed-to-user-rotation').decode().strip()
    if fixed_rotation not in ('default', 'disabled', 'enabled', 'enabled_if_no_auto_rotation'):
        raise RuntimeError(f'Unrecognized fixed-to-user-rotation policy: {fixed_rotation}')
    try:
        # A phone launcher can request portrait after the tested Activity finishes. Keep
        # all synthetic windows at the selected landscape rotation, including overlays.
        run('shell', 'wm', 'fixed-to-user-rotation', 'enabled')
        for name in args.profile or PROFILES:
            width, height, density, font = PROFILES[name]
            run('shell', 'am', 'force-stop', PACKAGE)
            run('shell', 'wm', 'size', f'{height}x{width}')
            run('shell', 'wm', 'density', str(density))
            run('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
            run('shell', 'settings', 'put', 'system', 'user_rotation', '1')
            run('shell', 'wm', 'user-rotation', 'lock', '1')
            run('shell', 'settings', 'put', 'system', 'font_scale', str(font))
            run('shell', 'pm', 'clear', PACKAGE)
            run('shell', 'cmd', 'locale', 'set-app-locales', PACKAGE, '--user', '0', '--locales', 'pl-PL')
            run('shell', 'appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow')
            # No GPS permission is granted. There is no private configuration or routine bundle.
            if int(run('shell', 'getprop', 'ro.build.version.sdk')) >= 33:
                run('shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
            display_preflight = wait_display(run, width, height, density, font)
            result = run('shell', 'am', 'instrument', '-w', '-e', 'windowLayouts', 'true',
                         PACKAGE + '.test/' + PACKAGE + '.SafetyChecks').decode()
            target = args.output / name
            target.mkdir(parents=True, exist_ok=True)
            (target / 'instrumentation.txt').write_text(result)
            if 'PASS:' not in result or 'FAIL:' in result:
                raise RuntimeError(f'{name}: {result}')
            remote = '/sdcard/Android/data/' + PACKAGE + '/files/'
            manifest = json.loads(run('shell', 'cat', remote + 'window-layout-files.json'))
            if manifest['density'] != density or abs(manifest['font_scale'] - font) >= 0.001:
                raise RuntimeError(f'{name}: app density/font scale did not match the requested profile')
            for filename in manifest['files']:
                if Path(filename).name != filename or not filename.endswith('.png'):
                    raise RuntimeError('Invalid screenshot filename')
                run('pull', remote + filename, str(target / filename))
                actual = png_size((target / filename).read_bytes())
                if actual != (width, height):
                    raise RuntimeError(f'{name}: expected {(width, height)} pixels, rendered {actual}')
            manifest.update(profile=name, expected_size=[width, height], expected_font_scale=font,
                            display_preflight=display_preflight,
                            apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                            boundary='Synthetic platform rendering only; physical radio acceptance pending')
            (target / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
            print(f'PASS: {name}: {len(manifest["files"])} captures in {target}')
    finally:
        run('shell', 'wm', 'fixed-to-user-rotation', fixed_rotation)


if __name__ == '__main__':
    main()
