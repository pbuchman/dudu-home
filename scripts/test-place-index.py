#!/usr/bin/env python3
"""Synthetic format/atomic validation tests; optional real PBF parsing with installed pyosmium."""
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
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
                db.executemany('INSERT INTO metadata VALUES(?,?)',[('schema','1'),('license','ODbL-1.0')])
            with self.assertRaises(ValueError):index.validate(path)
            with sqlite3.connect(path) as db:
                db.execute("INSERT INTO roads VALUES(1,'Example','','',0,0,0,0.001)")
                db.execute("INSERT INTO places VALUES('Example',0,0,0,0)")
                db.execute('INSERT INTO road_cells VALUES(0,0,1)')
            self.assertEqual(index.validate(path)[1]['roads'],1)
            with sqlite3.connect(path) as db:db.execute("UPDATE metadata SET value='99' WHERE key='schema'")
            with self.assertRaises(ValueError):index.validate(path)
    @unittest.skipUnless(importlib.util.find_spec('osmium'),'pyosmium computer builder dependency not installed')
    def test_osm_parser(self):
        with tempfile.TemporaryDirectory() as tmp:
            source=Path(tmp)/'synthetic.osm';target=Path(tmp)/'map.sqlite'
            source.write_text('''<osm version="0.6"><node id="1" lat="0" lon="0"><tag k="place" v="village"/><tag k="name" v="Test Settlement"/></node><node id="2" lat="0" lon="0.001"><tag k="addr:city" v="Test Settlement"/></node><way id="3"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/><tag k="name" v="Test Road"/></way></osm>''')
            index.build(source,target,'Synthetic','2026-01-01')
            manifest=json.loads(target.with_suffix('.manifest.json').read_text())
            self.assertEqual(manifest['sha256'],index.digest(target))
            self.assertEqual(manifest['counts']['roads'],1)
            with self.assertRaises(ValueError):index.build(source,target,'Synthetic','2026-01-01')
if __name__=='__main__':unittest.main()
