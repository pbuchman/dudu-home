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
            fixture.write_text('dependency ^10.4.0')
            self.assertEqual(scan('--working-tree').returncode, 0)
            for address in ('10' + '.23.45.67', '192' + '.168.45.67'):
                fixture.write_text(address)
                self.assertNotEqual(scan('--working-tree').returncode, 0)
            for value in ('FixtureHiddenValue', 'SyntheticHiddenStreet', '0.12346',
                          'SyntheticHiddenGroup', 'SyntheticHiddenFirst', 'SyntheticHiddenSecond',
                          'SyntheticSecondStreet', '0.23457',
                          ''.join('\\u%04x' % ord(c) for c in 'FixtureHiddenValue')):
                fixture.write_text(value)
                result = scan('--working-tree')
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn(value.encode(), result.stdout)
            browser = root/'browser.json'
            browser.write_text(json.dumps({'lib': ['D' + 'OM', 'D' + 'OM.Iterable'], 'module': 'react-' + 'd' + 'om/client'}))
            nav = json.loads(source.read_text()); nav['slots'].append({'label': 'D' + 'om'})
            source.write_text(json.dumps(nav))
            fixture.write_text('Public fixture')
            self.assertEqual(scan('--working-tree').returncode, 0)
            browser.write_text(json.dumps({'label': 'D' + 'om'}))
            self.assertNotEqual(scan('--working-tree').returncode, 0)
            browser.unlink()
            technical = 'import { renderToStaticMarkup } from "react-' + 'd' + 'om/server";\n'
            for suffix in ('.ts', '.tsx'):
                module = root/('browser'+suffix)
                module.write_text(technical)
                self.assertEqual(scan('--working-tree').returncode, 0)
                module.write_text(technical + 'const label = "D' + 'om";\n')
                self.assertNotEqual(scan('--working-tree').returncode, 0)
                module.write_text(technical)
            git('add', '.'); git('commit', '-qm', 'Synthetic technical imports')
            self.assertEqual(scan('--all-history').returncode, 0)
            module = root/'browser.ts'
            module.write_text(technical + 'const label = "D' + 'om";\n')
            git('add', '.'); git('commit', '-qm', 'Synthetic short label')
            module.write_text(technical)
            git('add', '.'); git('commit', '-qm', 'Remove synthetic short label')
            self.assertEqual(scan().returncode, 0)
            historical = scan('--all-history')
            self.assertNotEqual(historical.returncode, 0)
            self.assertIn(b'REJECTED browser.ts: private label', historical.stdout)
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
