#!/usr/bin/env python3
"""Privacy-scanner regressions with invented tokens and ocean coordinates only."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCANNER = Path(__file__).with_name('check-public-tree.py')


class PrivacyChecks(unittest.TestCase):
    def test_private_values_in_tests_history_and_escaped_text(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base/'public'; root.mkdir(); (root/'scripts').mkdir()
            shutil.copyfile(SCANNER, root/'scripts/check-public-tree.py')
            def git(*args):
                return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.PIPE)
            git('init', '-q'); git('config', 'user.email', 'fixture@example.invalid'); git('config', 'user.name', 'Fixture')
            source = base/'private-input.json'
            source.write_text(json.dumps({'slots': [{'destination': {'label': 'FixtureHiddenValue',
                'address': 'SyntheticHiddenStreet', 'latitude': 0.1234567, 'longitude': 0.7654321}}]}))
            group = {'label': 'SyntheticHiddenGroup', 'destinations': [
                {'label': 'SyntheticHiddenFirst'}, {'label': 'SyntheticHiddenSecond',
                 'address': 'SyntheticSecondStreet', 'latitude': 0.2345678}]}
            config = json.loads(source.read_text()); config['slots'].append(group)
            source.write_text(json.dumps(config))
            fixture = root/'test-fixture.txt'
            fixture.write_text('Public fixture')
            git('add', '.'); git('commit', '-qm', 'Synthetic baseline')
            def scan(*args):
                return subprocess.run(['python3', str(root/'scripts/check-public-tree.py'),
                    '--private-navigation', str(source), *args], capture_output=True)
            self.assertEqual(scan('--all-history').returncode, 0)
            for value in ('FixtureHiddenValue', 'SyntheticHiddenStreet', '0.12346',
                          'SyntheticHiddenGroup', 'SyntheticHiddenFirst', 'SyntheticHiddenSecond',
                          'SyntheticSecondStreet', '0.23457',
                          ''.join('\\u%04x' % ord(c) for c in 'FixtureHiddenValue')):
                fixture.write_text(value)
                result = scan('--working-tree')
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn(value.encode(), result.stdout)
            fixture.write_text('Public fixture')
            for suffix in ('.sqlite', '.pbf', '.building'):
                artifact = root/('synthetic-map'+suffix)
                artifact.write_text('Generated map fixture')
                self.assertNotEqual(scan('--working-tree').returncode, 0)
                artifact.unlink()
            fixture.write_text('FixtureHiddenValue')
            git('add', '.'); git('commit', '-qm', 'Synthetic leak')
            fixture.write_text('Public fixture'); git('add', '.'); git('commit', '-qm', 'Remove synthetic leak')
            self.assertEqual(scan().returncode, 0)
            self.assertNotEqual(scan('--all-history').returncode, 0)


if __name__ == '__main__': unittest.main()
