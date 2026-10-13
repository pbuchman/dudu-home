#!/usr/bin/env python3
"""Build a local OSM index. Computer only: pip install osmium. Never commit input/output maps."""
import argparse
import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import sqlite3
import tempfile

SETTLEMENTS = {'city', 'town', 'village', 'hamlet', 'isolated_dwelling'}
ROADS = {'motorway','trunk','primary','secondary','tertiary','unclassified','residential',
         'living_street','service','motorway_link','trunk_link','primary_link','secondary_link','tertiary_link','track'}
SCHEMA = '''
CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE roads(id INTEGER PRIMARY KEY,name TEXT NOT NULL,ref TEXT NOT NULL,city TEXT NOT NULL,a REAL,b REAL,c REAL,d REAL);
CREATE TABLE road_cells(x INTEGER,y INTEGER,id INTEGER);
CREATE TABLE places(name TEXT,lat REAL,lon REAL,x INTEGER,y INTEGER,kind TEXT NOT NULL,provenance TEXT NOT NULL);
CREATE TABLE addresses(city TEXT,lat REAL,lon REAL,x INTEGER,y INTEGER);
CREATE TABLE areas(id INTEGER PRIMARY KEY,name TEXT,rings TEXT,kind TEXT NOT NULL,provenance TEXT NOT NULL,admin_level TEXT NOT NULL,feature_id TEXT NOT NULL);
CREATE TABLE area_cells(x INTEGER,y INTEGER,id INTEGER);
'''
INDEXES = '''
CREATE INDEX road_grid ON road_cells(x,y,id);
CREATE INDEX place_grid ON places(x,y);
CREATE INDEX address_grid ON addresses(x,y);
CREATE INDEX area_grid ON area_cells(x,y,id);
'''

def digest(path):
    h = hashlib.sha256()
    with open(path,'rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''): h.update(block)
    return h.hexdigest()

def cells(points):
    xs=[math.floor(p[0]*100) for p in points]; ys=[math.floor(p[1]*100) for p in points]
    for x in range(min(xs),max(xs)+1):
        for y in range(min(ys),max(ys)+1): yield x,y

def validate(path):
    with sqlite3.connect(f'file:{Path(path).resolve()}?mode=ro',uri=True) as db:
        if db.execute('PRAGMA quick_check').fetchone()[0]!='ok': raise ValueError('Invalid map database')
        metadata=dict(db.execute('SELECT key,value FROM metadata'))
        if metadata.get('schema') not in ('1','2') or metadata.get('license')!='ODbL-1.0': raise ValueError('Unsupported map metadata')
        if metadata['schema']=='2':
            for table,required in [('places',{'kind','provenance'}),('areas',{'kind','provenance','admin_level','feature_id'})]:
                columns={row[1] for row in db.execute(f'PRAGMA table_info({table})')}
                if not required.issubset(columns): raise ValueError('Incomplete schema 2 provenance')
        counts={table:db.execute(f'SELECT count(*) FROM {table}').fetchone()[0] for table in ('roads','places','addresses','areas')}
        if counts['roads']==0 or counts['places']==0: raise ValueError('Incomplete map index')
        for table,grid in [('roads','road_cells'),('areas','area_cells')]:
            # One table scan each, including legacy packs whose grids have no id-leading index.
            if db.execute(f'SELECT id FROM {table} EXCEPT SELECT id FROM {grid} LIMIT 1').fetchone():
                raise ValueError('Missing spatial index')
        return metadata,counts

def area_kind(tags):
    """Polish level 8 represents a locality; levels 7/9/10/11 are not primary names.

    An administrative relation often has no place tag. Preserve its origin separately
    from settlement geometry so a nested hamlet cannot replace the enclosing locality.
    """
    if not tags.get('name'): return None
    if tags.get('boundary')=='administrative':
        return 'administrative_locality' if tags.get('admin_level')=='8' else None
    return tags.get('place') if tags.get('place') in SETTLEMENTS else None

def build(source,target,region,data_date):
    import osmium
    source=Path(source).resolve();target=Path(target).resolve()
    with osmium.io.Reader(str(source)) as reader:
        source_timestamp=reader.header().get('osmosis_replication_timestamp')
    if source_timestamp: data_date=source_timestamp[:10]
    if target.exists(): raise ValueError('Output exists; build a new version before installing')
    target.parent.mkdir(parents=True,exist_ok=True)
    temporary=target.with_name(target.name+'.building')
    if temporary.exists(): raise ValueError('Incomplete build exists; inspect/remove it before retry')
    db=sqlite3.connect(temporary)
    db.executescript('PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA temp_store=FILE;'+SCHEMA)
    factory=osmium.geom.GeoJSONFactory()
    class Handler(osmium.SimpleHandler):
        def __init__(self):
            super().__init__();self.road=0;self.area_id=0;self.seen=0
        def checkpoint(self):
            self.seen+=1
            if self.seen%100000==0: db.commit()
        def address(self,tags,lon,lat):
            city=tags.get('addr:city') or tags.get('addr:place')
            if city: db.execute('INSERT INTO addresses VALUES(?,?,?,?,?)',(city,lat,lon,math.floor(lon*100),math.floor(lat*100)))
        def node(self,node):
            if not node.location.valid(): return
            lon,lat=node.location.lon,node.location.lat
            if node.tags.get('place') in SETTLEMENTS and node.tags.get('name'):
                db.execute('INSERT INTO places VALUES(?,?,?,?,?,?,?)',(node.tags['name'],lat,lon,math.floor(lon*100),math.floor(lat*100),node.tags['place'],'osm_place_node'))
            self.address(node.tags,lon,lat);self.checkpoint()
        def way(self,way):
            if way.tags.get('highway') not in ROADS and not (way.tags.get('addr:city') or way.tags.get('addr:place')): return
            if any(not n.location.valid() for n in way.nodes): return
            points=[(n.lon,n.lat) for n in way.nodes]
            if not points:return
            if way.tags.get('highway') in ROADS:
                for a,b in zip(points,points[1:]):
                    if a==b:continue
                    self.road+=1
                    db.execute('INSERT INTO roads VALUES(?,?,?,?,?,?,?,?)',(self.road,way.tags.get('name',''),way.tags.get('ref',''),way.tags.get('addr:city') or way.tags.get('addr:place',''),a[1],a[0],b[1],b[0]))
                    db.executemany('INSERT INTO road_cells VALUES(?,?,?)',((x,y,self.road) for x,y in cells((a,b))))
            self.address(way.tags,sum(p[0] for p in points)/len(points),sum(p[1] for p in points)/len(points));self.checkpoint()
        def area(self,area):
            kind=area_kind(area.tags)
            if not kind:return
            try: geometry=json.loads(factory.create_multipolygon(area))
            except (RuntimeError,ValueError):return
            for polygon in geometry['coordinates']:
                self.area_id+=1
                provenance='osm_admin_boundary' if kind=='administrative_locality' else 'osm_place_area'
                feature_id=('way:' if area.from_way() else 'relation:')+str(area.orig_id())
                db.execute('INSERT INTO areas VALUES(?,?,?,?,?,?,?)',(self.area_id,area.tags['name'],json.dumps(polygon,separators=(',',':')),kind,provenance,area.tags.get('admin_level',''),feature_id))
                db.executemany('INSERT INTO area_cells VALUES(?,?,?)',((x,y,self.area_id) for x,y in cells(polygon[0])))
    try:
        print('Indexing OSM roads, addresses, settlements and locality boundaries (schema 2)...',flush=True)
        # Disk-backed node locations keep the national extract out of radio memory and bound builder RAM.
        with tempfile.TemporaryDirectory(prefix='dudu-osm-nodes-',dir=target.parent) as scratch:
            Handler().apply_file(str(source),locations=True,idx=f'sparse_file_array,{scratch}/nodes')
        db.commit();print('Building spatial indexes...',flush=True);db.executescript(INDEXES)
        meta={'schema':'2','region':region,'data_date':data_date,'built_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'source':'https://download.geofabrik.de/europe/poland.html','source_sha256':digest(source),'source_timestamp':source_timestamp or data_date,
              'license':'ODbL-1.0','attribution':'© OpenStreetMap contributors','attribution_url':'https://www.openstreetmap.org/copyright'}
        db.executemany('INSERT INTO metadata VALUES(?,?)',meta.items());db.commit();db.execute('ANALYZE');db.close()
        metadata,counts=validate(temporary)
        os.replace(temporary,target)
        manifest={'schema':2,'file':target.name,'sha256':digest(target),'bytes':target.stat().st_size,'metadata':metadata,'counts':counts}
        target.with_suffix('.manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
        print(json.dumps({'status':'ready','bytes':manifest['bytes'],'counts':counts}),flush=True)
    except BaseException:
        db.close();raise

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('source',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--region',default='Poland');p.add_argument('--data-date',required=True)
    a=p.parse_args();datetime.date.fromisoformat(a.data_date)
    build(a.source,a.output,a.region,a.data_date)
if __name__=='__main__':main()
