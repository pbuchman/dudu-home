#!/usr/bin/env python3
"""Invented source geometry only: independent topology/parser and schema-3 contract checks."""
import importlib.util
import json
import math
from pathlib import Path
import sqlite3
import tempfile
import unittest
import short_road_headings as h

spec=importlib.util.spec_from_file_location('index',Path(__file__).with_name('build-place-index.py'))
index=importlib.util.module_from_spec(spec);spec.loader.exec_module(index)

def points(metres): return [(x/111195,y/111195) for x,y in metres]
def supported(metres,ordinal=0,refs=None,degrees=None):
    refs=refs or list(range(1,len(metres)+1))
    degrees=degrees or dict(zip(refs,[1]+[2]*(len(refs)-2)+[1]))
    return h.orientation(refs,points(metres),ordinal,degrees)

class HeadingGeometry(unittest.TestCase):
    def test_straight_and_reverse(self):
        forward=supported([(0,0),(0,4),(0,24)])
        backward=supported([(0,24),(0,4),(0,0)],1)
        self.assertAlmostEqual(forward['heading'],0)
        self.assertAlmostEqual(backward['heading'],180)
        self.assertAlmostEqual(forward['support_length'],14)
        self.assertAlmostEqual(forward['chord_length'],14)
    def test_branch_stops_without_crossing(self):
        self.assertIsNone(supported([(0,0),(0,4),(0,24)],degrees={1:1,2:3,3:1}))
        result=supported([(0,-24),(0,-4),(0,0),(0,24)],1,degrees={1:1,2:2,3:4,4:1})
        self.assertIsNotNone(result)
        self.assertEqual(result['support_range'],[0,1])
        self.assertNotIn((3,4),result['degree_proof'])
    def test_curves_turns_zero_cycles_stub(self):
        for geometry in ([(0,0),(0,4),(20,4)],[(0,0),(0,4),(0,-20)],
                         [(0,0),(0,4),(0,4),(0,24)],[(0,0),(0,4)],
                         [(0,0),(0,0),(0,24)]):
            with self.subTest(geometry=geometry): self.assertIsNone(supported(geometry))
        self.assertIsNone(supported([(0,0),(0,4),(0,24),(0,0)],refs=[1,2,3,1]))
    def test_limits_and_bend(self):
        self.assertIsNone(supported([(0,n) for n in range(0,13)])) # eight edges insufficient
        accepted=supported([(0,0),(0,4),(2,24)])
        self.assertIsNotNone(accepted)
        self.assertIsNone(supported([(0,0),(0,4),(6,24)]))
        both=supported([(0,-24),(0,-4),(0,0),(0,24)],1)
        self.assertAlmostEqual(both['support_length'],24)
    def test_disk_incidence_duplicate_and_coincident_ids(self):
        with tempfile.TemporaryDirectory() as tmp:
            counter=h.Incidence(str(Path(tmp)/'incidence.sqlite'))
            counter.BATCH=3
            counter.add_way([1,2,3]);counter.add_way([4,5])
            counter.flush();self.assertEqual(counter.degrees([1,2,3,4,5]),{1:1,2:2,3:1,4:1,5:1})
            counter.add_way([1,2,3]);counter.flush()
            self.assertEqual(counter.degrees([2])[2],3)
            self.assertIsNone(supported([(0,0),(0,4),(0,24)],degrees=counter.degrees([1,2,3])))
            counter.close()

@unittest.skipUnless(importlib.util.find_spec('osmium'),'pyosmium computer builder dependency not installed')
class SourceParser(unittest.TestCase):
    def fixture(self,root):
        import osmium
        # Each way has a separate invented origin. Deliberately coincident node coordinates
        # use different IDs in the disconnected pair. Names never determine topology.
        geometries=[([(0,0),(0,4),(0,24)],[1,2,3],101),
                    ([(100,0),(100,4)],[11,12],102),
                    ([(100,4),(100,24)],[13,14],103),
                    ([(200,0),(200,4),(200,24)],[21,22,23],104),
                    ([(200,4),(220,4)],[22,24],105),
                    ([(300,0),(300,4),(320,4)],[31,32,33],106),
                    ([(400,0),(400,4),(400,24)],[41,42,43],107),
                    ([(400,0),(400,4),(400,24)],[41,42,43],108),
                    ([(500,0),(500,4),(500,24),(500,0)],[51,52,53,51],109),
                    ([(600,0),(600,4),(600,4),(600,24)],[61,62,63,64],110),
                    ([(700,0),(700,4)],[71,72],111),
                    ([(700,4),(700,24)],[72,73],112),
                    ([(800,24),(800,4),(800,0)],[81,82,83],113)]
        nodes={};ways=[]
        for geometry,refs,way in geometries:
            for ref,(lon,lat) in zip(refs,points(geometry)):nodes[ref]=(lon,lat)
            ways.append('<way id="'+str(way)+'">'+''.join('<nd ref="'+str(ref)+'"/>' for ref in refs)+
                        '<tag k="highway" v="service"/><tag k="name" v="Synthetic Lane"/></way>')
        xml='<osm version="0.6">'+''.join(f'<node id="{ref}" lon="{lon}" lat="{lat}"/>' for ref,(lon,lat) in sorted(nodes.items()))
        xml+='<node id="99" lon="0.02" lat="0.02"><tag k="place" v="village"/><tag k="name" v="Synthetic Settlement"/></node>'+''.join(ways)+'</osm>'
        source=root/'fixture.osm';source.write_text(xml)
        pbf=root/'fixture.osm.pbf'
        with osmium.SimpleWriter(str(pbf)) as writer:
            class Copy(osmium.SimpleHandler):
                def node(self,n):writer.add_node(n)
                def way(self,w):writer.add_way(w)
            Copy().apply_file(str(source))
        return pbf
    def test_original_pbf_both_passes_and_private_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);source=self.fixture(root);target=root/'map.sqlite'
            index.build(source,target,'Synthetic','2026-01-01')
            manifest=json.loads(target.with_suffix('.manifest.json').read_text())
            audit=[json.loads(line) for line in target.with_suffix('.heading-audit.jsonl').read_text().splitlines()]
            self.assertEqual(manifest['schema'],3)
            self.assertEqual(audit[0]['source_sha256'],index.digest(source))
            accepted={row['way_id'] for row in audit[1:]}
            self.assertEqual(accepted,{101,113})
            self.assertEqual(manifest['counts']['road_short_headings'],2)
            for row in audit[1:]:
                self.assertEqual(row['support_range'],[0,1])
                self.assertTrue(all(proof[1]==2 for proof in row['degree_proof'] if proof[0]!='stop'))
                self.assertGreaterEqual(row['chord_length'],10)
            with sqlite3.connect(target) as db:
                self.assertEqual(dict(db.execute('SELECT key,value FROM metadata'))['short_heading_policy'],h.POLICY_ID)
                self.assertEqual(len(list(db.execute('SELECT * FROM roads'))),22)
            self.assertEqual(index.validate(target)[1],manifest['counts'])
            # Legacy schema 2 remains supported without the side table or heading metadata.
            with sqlite3.connect(target) as db:
                db.execute('DROP TABLE road_short_headings')
                db.execute("UPDATE metadata SET value='2' WHERE key='schema'")
                db.execute("DELETE FROM metadata WHERE key LIKE 'short_heading%' OR key='map_generation'")
            self.assertEqual(index.validate(target)[0]['schema'],'2')
    def test_invalid_policy_and_heading_values(self):
        for mutation in ("UPDATE metadata SET value='wrong' WHERE key='short_heading_policy'",
                         "UPDATE metadata SET value='2' WHERE key='short_heading_source_sha256'",
                         'UPDATE road_short_headings SET support_length=26',
                         'UPDATE road_short_headings SET heading=360',
                         'UPDATE road_short_headings SET road_id=999999 WHERE road_id=(SELECT min(road_id) FROM road_short_headings)'):
            with self.subTest(mutation=mutation),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);target=root/'map.sqlite';index.build(self.fixture(root),target,'Synthetic','2026-01-01')
                with sqlite3.connect(target) as db:db.execute(mutation)
                with self.assertRaises(ValueError):index.validate(target)

if __name__=='__main__':unittest.main()
