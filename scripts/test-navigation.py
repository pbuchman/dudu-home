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
                           ('longitude',float('nan')),('address',None),('navigate_by','unknown'),
                           ('navigate_by',None),('navigate_by',True),('navigate_by','address')]:
            bad=deepcopy(self.value);bad['slots'][0]['destination'][key]=value
            with self.assertRaises(ValueError): validate_navigation(bad)
        for bad in ({}, [], {'schema_version':True,'slots':[]}, {'schema_version':1,'slots':[{'slot':1}]},
                    {'schema_version':1,'slots':[{'slot':1,'destination':None}]*2}):
            with self.assertRaises(ValueError): validate_navigation(bad)
    def test_address_mode_is_per_destination_and_requires_address(self):
        value=deepcopy(self.value)
        destination=value['slots'][0]['destination']
        destination.update(address='Synthetic Street 1 & 2, Fixture City', navigate_by='address')
        result=validate_navigation(value)
        self.assertEqual(result['slots'][0]['destination'],destination)
        self.assertEqual(result['slots'][2],self.value['slots'][1])
        for address in ('', ' ', None, 'bad\nline'):
            bad=deepcopy(value);bad['slots'][0]['destination']['address']=address
            with self.assertRaises(ValueError): validate_navigation(bad)
    def test_groups_and_legacy(self):
        place = self.value['slots'][0]['destination']
        group = {'slot': 2, 'label': 'Fixture group', 'icon': 'squash', 'destinations': [place, {
            **place, 'label': 'Fixture B', 'address': 'Synthetic address B', 'navigate_by': 'address'}]}
        value = {'schema_version': 2, 'slots': [group]}
        result = validate_navigation(value)
        self.assertEqual(result['slots'][1], group)
        self.assertEqual(result['slots'][0], {'slot': 1, 'destinations': []})
        self.assertEqual(validate_navigation(result), result)
        for count in (0, 1, 2, 3, 12):
            copy = deepcopy(value); copy['slots'][0]['destinations'] = [place] * count
            self.assertEqual(len(validate_navigation(copy)['slots'][1]['destinations']), count)
        for patch in ({'destinations': [place] * 13}, {'destination': place}, {'destinations': None},
                      {'destinations': [None]}, {'label': None}, {'icon': 'unknown'}, {'slot': True}):
            copy = deepcopy(value); copy['slots'][0].update(patch)
            with self.assertRaises(ValueError): validate_navigation(copy)
        for key in ('label', 'icon', 'destinations'):
            copy = deepcopy(value); del copy['slots'][0][key]
            with self.assertRaises(ValueError): validate_navigation(copy)
        empty = {'schema_version': 2, 'slots': [{'slot': 3, 'destinations': []}]}
        self.assertEqual(validate_navigation(empty)['slots'][2]['destinations'], [])
        # Bounds apply to the encoded complete document, including group labels.
        huge = {'schema_version': 2, 'slots': [{**group, 'slot': n, 'destinations': [
            {**place, 'label': 'Ż' * 64, 'address': 'Ż' * 160} for _ in range(12)]} for n in (1,2,3)]}
        with self.assertRaises(ValueError): validate_navigation(huge)

    def test_append_third_preserves_slots_order_and_address_mode(self):
        first = deepcopy(self.value['slots'][0]['destination'])
        second = {**first, 'label': 'Fixture B', 'address': 'Synthetic address B', 'navigate_by': 'address'}
        third = {**first, 'label': 'Fixture C', 'latitude': .003, 'longitude': .006}
        document = {'schema_version': 2, 'slots': [
            {'slot': 1, 'label': 'Fixture home', 'icon': 'home', 'destinations': [first]},
            {'slot': 2, 'label': 'Fixture group', 'icon': 'squash', 'destinations': [first, second]},
            {'slot': 3, 'label': 'Fixture pin', 'icon': 'pin', 'destinations': [third]}]}
        before = validate_navigation(document)
        document['slots'][1]['destinations'].append(third)
        after = validate_navigation(document)
        self.assertEqual(after['slots'][0], before['slots'][0])
        self.assertEqual(after['slots'][2], before['slots'][2])
        self.assertEqual(after['slots'][1]['destinations'], [first, second, third])

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
