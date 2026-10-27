#!/usr/bin/env python3
"""Synthetic format/atomic validation tests; optional real PBF parsing with installed pyosmium."""
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('index',Path(__file__).with_name('build-place-index.py'))
index=importlib.util.module_from_spec(spec);spec.loader.exec_module(index)
class MapTests(unittest.TestCase):
    def test_cell_boundary(self):
        self.assertEqual(set(index.cells([(0.009,0.009),(0.011,0.011)])),{(0,0),(0,1),(1,0),(1,1)})
    def test_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'test.sqlite'
            with sqlite3.connect(path) as db:
                db.executescript(index.SCHEMA+index.INDEXES)
                db.executemany('INSERT INTO metadata VALUES(?,?)',[('schema','2'),('license','ODbL-1.0')])
            with self.assertRaises(ValueError):index.validate(path)
            with sqlite3.connect(path) as db:
                db.execute("INSERT INTO roads VALUES(1,'Example','','',0,0,0,0.001)")
                db.execute("INSERT INTO places VALUES('Example',0,0,0,0,'village','osm_place_node')")
                db.execute('INSERT INTO road_cells VALUES(0,0,1)')
            self.assertEqual(index.validate(path)[1]['roads'],1)
            with sqlite3.connect(path) as db:db.execute("UPDATE metadata SET value='99' WHERE key='schema'")
            with self.assertRaises(ValueError):index.validate(path)
    def test_boundary_selection(self):
        self.assertEqual(index.area_kind({'name':'Test Locality','boundary':'administrative','admin_level':'8'}),'administrative_locality')
        for level in ('7','9','10','11'):
            self.assertIsNone(index.area_kind({'name':'Test Division','boundary':'administrative','admin_level':level,'place':'village'}))
        self.assertEqual(index.area_kind({'name':'Test Hamlet','place':'hamlet'}),'hamlet')
    @unittest.skipUnless(importlib.util.find_spec('osmium'),'pyosmium computer builder dependency not installed')
    def test_missing_source_preserves_existing_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            target=Path(tmp)/'places.sqlite';target.write_bytes(b'previous')
            with self.assertRaises(OSError):index.build(Path(tmp)/'missing.osm',target,'Synthetic','2026-01-01')
            self.assertEqual(target.read_bytes(),b'previous')
    @unittest.skipUnless(importlib.util.find_spec('osmium'),'pyosmium computer builder dependency not installed')
    def test_failed_build_removes_incomplete_output_and_scratch(self):
        with tempfile.TemporaryDirectory() as tmp:
            source=Path(tmp)/'source.osm';target=Path(tmp)/'places.sqlite'
            source.write_text('<osm version="0.6"></osm>')
            with patch.object(index,'validate',side_effect=ValueError('synthetic validation failure')):
                with self.assertRaises(ValueError):index.build(source,target,'Synthetic','2026-01-01')
            self.assertEqual(set(p.name for p in Path(tmp).iterdir()),{'source.osm'})
    @unittest.skipUnless(importlib.util.find_spec('osmium'),'pyosmium computer builder dependency not installed')
    def test_osm_parser(self):
        with tempfile.TemporaryDirectory() as tmp:
            source=Path(tmp)/'synthetic.osm';target=Path(tmp)/'places.sqlite'
            source.write_text('''<osm version="0.6"><node id="1" lat="0" lon="0"><tag k="place" v="village"/><tag k="name" v="Test Settlement"/></node><node id="2" lat="0" lon="0.001"><tag k="addr:city" v="Test Settlement"/></node><way id="3"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/><tag k="name" v="Test Road"/></way></osm>''')
            index.build(source,target,'Synthetic','2026-01-01')
            self.assertEqual(index.validate(target)[1]['roads'],1)
            index.build(source,target,'Synthetic','2026-01-01')
            self.assertEqual(index.validate(target)[1]['roads'],1)
            self.assertEqual(set(p.name for p in Path(tmp).iterdir()),{'synthetic.osm','places.sqlite'})
    @unittest.skipUnless(importlib.util.find_spec('osmium'),'pyosmium computer builder dependency not installed')
    def test_osm_boundary_holes_and_disjoint_parts(self):
        with tempfile.TemporaryDirectory() as tmp:
            source=Path(tmp)/'synthetic.osm';target=Path(tmp)/'places.sqlite'
            # Invented geography near the equator. One level-8 relation with two outer rings and a hole.
            rings=[[(0,0),(.02,0),(.02,.02),(0,.02)],[(.005,.005),(.015,.005),(.015,.015),(.005,.015)],[(.03,0),(.04,0),(.04,.01),(.03,.01)]]
            nodes=[];ways=[];node_id=1
            for way_id,ring in enumerate(rings,100):
                ids=[]
                for lon,lat in ring:
                    ids.append(node_id);nodes.append(f'<node id="{node_id}" lon="{lon}" lat="{lat}"/>');node_id+=1
                ways.append(f'<way id="{way_id}">'+''.join(f'<nd ref="{ref}"/>' for ref in [*ids,ids[0]])+'</way>')
            nodes.append('<node id="50" lon="0.001" lat="0.001"><tag k="place" v="village"/><tag k="name" v="Synthetic Village"/></node>')
            nodes.append('<node id="51" lon="0.002" lat="0.001"><tag k="place" v="hamlet"/><tag k="name" v="Synthetic Hamlet"/></node>')
            nodes.append('<node id="52" lon="0.002" lat="0.002"><tag k="place" v="isolated_dwelling"/><tag k="name" v="Synthetic Dwelling"/></node>')
            ways.append('<way id="110"><nd ref="1"/><nd ref="2"/><tag k="highway" v="trunk"/></way>')
            relations=[]
            for relation_id,level in enumerate(('8','7','9','10','11'),200):
                members='<member type="way" ref="100" role="outer"/>'
                if level=='8':members+='<member type="way" ref="101" role="inner"/><member type="way" ref="102" role="outer"/>'
                relation_type='boundary' if level=='8' else 'multipolygon'
                relations.append(f'<relation id="{relation_id}">{members}<tag k="type" v="{relation_type}"/><tag k="boundary" v="administrative"/><tag k="admin_level" v="{level}"/><tag k="name" v="Synthetic Locality {level}"/></relation>')
            source.write_text('<osm version="0.6">'+''.join(nodes+ways+relations)+'</osm>')
            index.build(source,target,'Synthetic','2026-01-01')
            with sqlite3.connect(target) as db:
                self.assertEqual(dict(db.execute('SELECT key,value FROM metadata'))['schema'],'3')
                self.assertEqual(set(db.execute('SELECT kind,provenance FROM places')),{('village','osm_place_node'),('hamlet','osm_place_node'),('isolated_dwelling','osm_place_node')})
                areas=list(db.execute('SELECT name,rings,kind,provenance,admin_level,feature_id FROM areas'))
                self.assertEqual(len(areas),2)
                self.assertEqual({(a[0],*a[2:]) for a in areas},{('Synthetic Locality 8','administrative_locality','osm_admin_boundary','8','relation:200')})
                self.assertEqual(sorted(len(json.loads(a[1])) for a in areas),[1,2])
            self.assertEqual(index.validate(target)[0]['schema'],'3')
if __name__=='__main__':unittest.main()
