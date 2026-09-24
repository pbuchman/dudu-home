#!/usr/bin/env python3
"""Synthetic fixtures only. Never load an owner's destinations into tests."""
import json
from copy import deepcopy
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from navigation_config import read_navigation, validate_navigation

class NavigationTests(unittest.TestCase):
    def setUp(self):
        self.value = {'schema_version': 1, 'slots': [
            {'slot': 1, 'destination': {'label': 'Fixture A', 'icon': 'home', 'latitude': 0, 'longitude': 0}},
            {'slot': 3, 'destination': {'label': 'Fixture C', 'icon': 'pin', 'latitude': 0.001, 'longitude': -0.001}}]}
    def test_slots_and_empty(self):
        value = validate_navigation(self.value)
        self.assertEqual([x['slot'] for x in value['slots']], [1, 2, 3])
        self.assertIsNone(value['slots'][1]['destination'])
        self.assertEqual(validate_navigation({'schema_version':1, 'slots':[]})['slots'],
                         [{'slot':x,'destination':None} for x in (1,2,3)])
    def test_rejections(self):
        for key, value in [('label',''),('label','bad\nline'),('label','A'*65),('icon','unknown'),
                           ('latitude','0'),('latitude',True),('latitude',91),('longitude',-181),
                           ('longitude',float('nan')),('address',None)]:
            bad=deepcopy(self.value);bad['slots'][0]['destination'][key]=value
            with self.assertRaises(ValueError): validate_navigation(bad)
        for bad in ({}, [], {'schema_version':True,'slots':[]}, {'schema_version':1,'slots':[{'slot':1}]},
                    {'schema_version':1,'slots':[{'slot':1,'destination':None}]*2}):
            with self.assertRaises(ValueError): validate_navigation(bad)
    def test_parser_bounds_duplicates_and_trailing(self):
        with TemporaryDirectory() as directory:
            file=Path(directory)/'fixture.json'
            for raw in (b'{"schema_version":1,"schema_version":1,"slots":[]}', b'{}{}', b'\xff', b' '*16385,
                        b'{"schema_version":1,"slots":[{"slot":1,"destination":{"latitude":NaN}}]}'):
                file.write_bytes(raw)
                with self.assertRaises(ValueError): read_navigation(file)
            file.write_text(json.dumps(self.value))
            self.assertEqual(len(read_navigation(file)['slots']),3)

if __name__=='__main__': unittest.main()
