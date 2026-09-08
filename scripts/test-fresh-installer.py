#!/usr/bin/env python3
"""Run the complete fresh-install script with offline executable fixtures."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class FreshInstallerTests(unittest.TestCase):
    def execute(self, case, arguments=('synthetic-device',)):
        with tempfile.TemporaryDirectory(prefix='dudu-fresh-install-test-') as directory:
            root = Path(directory)
            (root/'scripts').mkdir()
            (root/'sdk/platform-tools').mkdir(parents=True)
            shutil.copy2(ROOT/'scripts/install-on-device.sh', root/'scripts/install-on-device.sh')
            fixture = ROOT/'scripts/test-fixtures/fresh-install/tool.py'
            for path in (root/'gradlew', root/'sdk/platform-tools/adb'):
                shutil.copy2(fixture, path); path.chmod(0o700)
            env = dict(os.environ, ANDROID_SDK_ROOT=str(root/'sdk'), DUDU_TEST_CASE=case,
                       DUDU_TEST_TRACE=str(root/'trace.jsonl'))
            result = subprocess.run(['bash', str(root/'scripts/install-on-device.sh'), *arguments],
                                    env=env, capture_output=True, text=True, timeout=10)
            trace = root/'trace.jsonl'
            calls = [json.loads(line) for line in trace.read_text().splitlines()] if trace.exists() else []
            return result, calls

    def test_check_failures_never_build_or_install(self):
        for case in ('check_error', 'check_error_with_output', 'empty', 'malformed', 'warning', 'installed'):
            with self.subTest(case=case):
                result, calls = self.execute(case)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(calls, [['adb', ['-s', 'synthetic-device', 'shell', 'pm', 'list', 'packages', '-u']]])

    def test_explicit_single_target_required(self):
        for arguments in ((), ('',), ('one', 'two')):
            result, calls = self.execute('absent', arguments)
            self.assertNotEqual(result.returncode, 0); self.assertEqual(calls, [])

    def test_confirmed_absence_installs_once_without_replace_or_launch(self):
        result, calls = self.execute('absent')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls[1], ['gradlew', ['assembleDebug', 'lintDebug']])
        self.assertEqual(calls[2], ['adb', ['-s', 'synthetic-device', 'install', 'app/build/outputs/apk/debug/app-debug.apk']])
        self.assertEqual(len(calls), 3)

    def test_build_failure_prevents_install(self):
        result, calls = self.execute('build_error')
        self.assertNotEqual(result.returncode, 0); self.assertEqual(len(calls), 2)

    def test_install_failure_and_race_never_retry(self):
        for case in ('install_error', 'race'):
            result, calls = self.execute(case)
            self.assertNotEqual(result.returncode, 0); self.assertEqual(len(calls), 3)
            self.assertNotIn('-r', calls[-1][1])
            self.assertNotIn('Dudu Home zainstalowane.', result.stdout)


if __name__ == '__main__': unittest.main()
