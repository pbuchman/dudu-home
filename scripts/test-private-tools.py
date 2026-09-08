#!/usr/bin/env python3
"""Dependency-free synthetic validation tests: no ADB or cloud calls."""
from copy import deepcopy
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from types import SimpleNamespace
import unittest


def load(name, filename):
    spec = spec_from_file_location(name, Path(__file__).with_name(filename))
    module = module_from_spec(spec); spec.loader.exec_module(module); return module


installer = load('installer', 'configure-device.py')
bootstrap = load('bootstrap', 'bootstrap-roborock.py')


class PrivateToolsTests(unittest.TestCase):
    def setUp(self):
        self.config = dict(schema_version=2, gate_number='000000000', points={
            'parking': dict(lat=0, lon=0), 'gate': dict(lat=0.001, lon=0),
            'approach': dict(lat=0.002, lon=0), 'junction': dict(lat=0.003, lon=0)})
        self.robot = dict(schema_version=1, api_base_url='https://api-eu.roborock.com',
                          routine_id=7, routine_name='Full Cleaning',
                          auth=dict(u='example-user', s='example-session', h='example-secret'))

    def test_full_package(self):
        value = installer.validate(self.config, self.robot, True)
        self.assertEqual(value['schema_version'], 2)
        self.assertTrue(value['automation_enabled'])
        self.assertFalse(value['gate_number_verified_on_current_device'])
        self.assertEqual(set(value['roborock']['auth']), {'u', 's', 'h'})

    def test_optional_fields(self):
        self.assertIsNone(installer.validate(dict(schema_version=2), None, False)['points'])
        with self.assertRaises(ValueError): installer.validate(dict(schema_version=2), None, True)

    def test_reject_invalid_data(self):
        for bad in (None, [], {}, dict(schema_version=2, gate_number=123), dict(schema_version=2, points=[])):
            with self.assertRaises(ValueError): installer.validate(bad, None, False)
        for value in (float('nan'), float('inf'), 90, '0', None):
            bad = deepcopy(self.config); bad['points']['parking']['lat'] = value
            with self.assertRaises(ValueError): installer.validate(bad, None, True)

    def test_routine_restrictions(self):
        for key, value in [('api_base_url', 'http://api-eu.roborock.com'), ('api_base_url', 'https://example.org'),
                           ('api_base_url', 'https://api-eu.roborock.com//'), ('routine_id', 0),
                           ('routine_id', 7.5), ('routine_id', '7'), ('routine_name', 'Other')]:
            bad = deepcopy(self.robot); bad[key] = value
            with self.assertRaises(ValueError): installer.validate(self.config, bad, True)
        bad = deepcopy(self.robot); bad['auth']['h'] = 'header\ninjection'
        with self.assertRaises(ValueError): installer.validate(self.config, bad, True)

    def test_outside_repo(self):
        with self.assertRaises(ValueError): installer.private_path(Path(__file__).parent/'configuration.json')

    def test_manual_mop(self):
        both = dict(self.robot, full_mop_routine_id=8)
        self.assertEqual(installer.validate(self.config, both, True)['roborock']['full_mop_routine_id'], 8)
        for value in (None, True, 0, -1, 7, 8.5, '8'):
            with self.assertRaises(ValueError):
                installer.validate(self.config, dict(self.robot, full_mop_routine_id=value), True)

    def test_minimal_bundle(self):
        r = SimpleNamespace(u='example-user', s='example-session', h='example-secret', k='unneeded-key',
                            r=SimpleNamespace(a='https://api-eu.roborock.com', m='unneeded-mqtt'))
        bundle = bootstrap.minimal_bundle(SimpleNamespace(rriot=r, token='unneeded-token'), SimpleNamespace(id=7, name='Full Cleaning'))
        self.assertEqual(bundle, self.robot)
        both = bootstrap.minimal_bundle(SimpleNamespace(rriot=r), SimpleNamespace(id=7, name='Full Cleaning'),
                                        SimpleNamespace(id=8, name='Full Mop'))
        self.assertEqual(both['full_mop_routine_id'], 8)
        with self.assertRaises(ValueError):
            bootstrap.minimal_bundle(SimpleNamespace(rriot=r), SimpleNamespace(id=7, name='Full Cleaning'),
                                     SimpleNamespace(id=8, name='Other'))


if __name__ == '__main__': unittest.main()
