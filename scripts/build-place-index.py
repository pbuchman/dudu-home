#!/usr/bin/env python3
"""Build a local OSM index. Computer only: pip install osmium. Never commit input/output maps."""
import argparse
import datetime
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import tempfile
import re
from short_road_headings import Incidence, orientation, metadata as heading_metadata

SETTLEMENTS = {'city', 'town', 'village', 'hamlet', 'isolated_dwelling'}
ROADS = {'motorway','trunk','primary','secondary','tertiary','unclassified','residential',
         'living_street','service','motorway_link','trunk_link','primary_link','secondary_link','tertiary_link','track'}
SCHEMA = '''
CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE roads(id INTEGER PRIMARY KEY,name TEXT NOT NULL,ref TEXT NOT NULL,city TEXT NOT NULL,a REAL,b REAL,c REAL,d REAL);
CREATE TABLE road_short_headings(road_id INTEGER PRIMARY KEY,heading REAL NOT NULL,support_length REAL NOT NULL);
CREATE TABLE road_heading_sources(road_id INTEGER PRIMARY KEY,proof TEXT NOT NULL);
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
    with sqlite3.connect(Path(path).resolve().as_uri()+'?mode=ro',uri=True) as db:
        if db.execute('PRAGMA journal_mode').fetchone()[0]=='wal':raise ValueError('Map must be a standalone SQLite file, without WAL')
        if db.execute('PRAGMA quick_check').fetchone()[0]!='ok': raise ValueError('Invalid map database')
        metadata=dict(db.execute('SELECT key,value FROM metadata'))
        if metadata.get('schema') not in ('1','2','3') or metadata.get('license')!='ODbL-1.0': raise ValueError('Unsupported map metadata')
        if int(metadata['schema'])>=2:
            for table,required in [('places',{'kind','provenance'}),('areas',{'kind','provenance','admin_level','feature_id'})]:
                columns={row[1] for row in db.execute(f'PRAGMA table_info({table})')}
                if not required.issubset(columns): raise ValueError('Incomplete schema 2 provenance')
        if metadata['schema']=='3':
            source_hash=metadata.get('source_sha256','')
            if not re.fullmatch('[0-9a-f]{64}',source_hash) or any(metadata.get(k)!=v for k,v in heading_metadata(source_hash).items()):
                raise ValueError('Invalid schema 3 heading policy/source binding')
            columns={row[1] for row in db.execute('PRAGMA table_info(road_short_headings)')}
            if columns!={'road_id','heading','support_length'}: raise ValueError('Incomplete schema 3 heading table')
            # Older schema-3 packs remain readable; new builds embed their source proof.
            if db.execute('SELECT h.road_id FROM road_short_headings h LEFT JOIN roads r ON r.id=h.road_id '
                          'WHERE r.id IS NULL OR typeof(h.heading) NOT IN (\'real\',\'integer\') '
                          'OR h.heading<0 OR h.heading>=360 OR typeof(h.support_length) NOT IN (\'real\',\'integer\') '
                          'OR h.support_length<10 OR h.support_length>25.000001 LIMIT 1').fetchone():
                raise ValueError('Invalid short heading geometry/support')
            for a,b,c,d in db.execute('SELECT r.a,r.b,r.c,r.d FROM roads r JOIN road_short_headings h ON h.road_id=r.id'):
                if not all(isinstance(v,(int,float)) and math.isfinite(v) for v in (a,b,c,d)):
                    raise ValueError('Invalid supported road coordinates')
                length=math.hypot((d-b)*111195*math.cos(math.radians((a+c)/2)),(c-a)*111195)
                if not 0<length<=5.000001: raise ValueError('Heading attached to non-short fragment')
        if metadata.get('short_heading_proof_storage')=='road_heading_sources-v1':
            if db.execute('SELECT road_id FROM road_short_headings EXCEPT SELECT road_id FROM road_heading_sources LIMIT 1').fetchone():
                raise ValueError('Missing embedded heading proof')
            for road_id,proof in db.execute('SELECT road_id,proof FROM road_heading_sources'):
                try:record=json.loads(proof)
                except (TypeError,ValueError) as error:raise ValueError('Invalid embedded heading proof') from error
                if not isinstance(record,dict) or not isinstance(record.get('way_id'),int) or not record.get('node_ids') or not record.get('degree_proof'):
                    raise ValueError('Incomplete embedded heading proof')
        counts={table:db.execute(f'SELECT count(*) FROM {table}').fetchone()[0] for table in ('roads','places','addresses','areas')}
        if metadata['schema']=='3': counts['road_short_headings']=db.execute('SELECT count(*) FROM road_short_headings').fetchone()[0]
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
    from radio_update import regular_file,cleanup_host_map,managed_path
    source=regular_file(source);target=managed_path(target)
    if target.name!='places.sqlite':raise ValueError('Output must be the managed places.sqlite file')
    if target==source:raise ValueError('Source and output must differ')
    with osmium.io.Reader(str(source)) as reader:
        source_timestamp=reader.header().get('osmosis_replication_timestamp')
    if source_timestamp:data_date=source_timestamp[:10]
    source_stat=source.stat();source_hash=digest(source)
    target.parent.mkdir(parents=True,exist_ok=True)
    if target.exists():cleanup_host_map(target)
    target.unlink(missing_ok=True)
    # Build directly into the sole designated map. Failed builds remove this incomplete output.
    temporary=target
    temporary.touch(mode=0o600,exist_ok=False)
    db=None
    try:
        db=sqlite3.connect(temporary)
        db.executescript('PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA temp_store=FILE; PRAGMA cache_size=-32768;'+SCHEMA)
        factory=osmium.geom.GeoJSONFactory()
    except BaseException:
        if db is not None:db.close()
        target.unlink(missing_ok=True);raise
    incidence=None
    class DegreeHandler(osmium.SimpleHandler):
        def way(self,way):
            if way.tags.get('highway') in ROADS: incidence.add_way(n.ref for n in way.nodes)
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
                refs=[n.ref for n in way.nodes]
                short_ordinals={i for i,(a,b) in enumerate(zip(points,points[1:]))
                                if 0<math.hypot((b[0]-a[0])*111195*math.cos(math.radians((a[1]+b[1])/2)),(b[1]-a[1])*111195)<=5}
                degrees=incidence.degrees(refs) if short_ordinals else {}
                for ordinal,(a,b) in enumerate(zip(points,points[1:])):
                    if a==b:continue
                    self.road+=1
                    db.execute('INSERT INTO roads VALUES(?,?,?,?,?,?,?,?)',(self.road,way.tags.get('name',''),way.tags.get('ref',''),way.tags.get('addr:city') or way.tags.get('addr:place',''),a[1],a[0],b[1],b[0]))
                    db.executemany('INSERT INTO road_cells VALUES(?,?,?)',((x,y,self.road) for x,y in cells((a,b))))
                    supported=orientation(refs,points,ordinal,degrees) if ordinal in short_ordinals else None
                    if supported:
                        db.execute('INSERT INTO road_short_headings VALUES(?,?,?)',(self.road,supported['heading'],supported['support_length']))
                        proof=dict(way_id=way.id,node_ids=refs[supported['support_range'][0]:supported['support_range'][1]+2],**supported)
                        db.execute('INSERT INTO road_heading_sources VALUES(?,?)',(self.road,json.dumps(proof,separators=(',',':'))))
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
        print('Counting eligible-road source-node incidence (schema 3, disk-backed, 32 MiB SQLite cache)...',flush=True)
        # Disk-backed node locations keep the national extract out of radio memory and bound builder RAM.
        with tempfile.TemporaryDirectory(prefix='dudu-osm-nodes-',dir=target.parent) as scratch:
            incidence=Incidence(str(Path(scratch)/'incidence.sqlite'))
            DegreeHandler().apply_file(str(source));incidence.flush()
            print(json.dumps(dict(phase='incidence_ready',endpoint_observations=incidence.endpoints,
                                  scratch_bytes=(Path(scratch)/'incidence.sqlite').stat().st_size)),flush=True)
            print('Indexing OSM roads, supported headings, addresses, settlements and locality boundaries...',flush=True)
            Handler().apply_file(str(source),locations=True,idx=f'sparse_file_array,{scratch}/nodes')
            incidence.close();incidence=None
        db.commit();print('Building spatial indexes...',flush=True);db.executescript(INDEXES)
        current_stat=source.stat()
        if (source_stat.st_ino,source_stat.st_size,source_stat.st_mtime_ns)!=(current_stat.st_ino,current_stat.st_size,current_stat.st_mtime_ns):
            raise ValueError('Source changed during build')
        meta={'schema':'3','region':region,'data_date':data_date,'built_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'source':'https://download.geofabrik.de/europe/poland.html','source_sha256':source_hash,'source_timestamp':source_timestamp or data_date,
              'license':'ODbL-1.0','attribution':'© OpenStreetMap contributors','attribution_url':'https://www.openstreetmap.org/copyright'}
        meta.update(heading_metadata(source_hash));meta['short_heading_proof_storage']='road_heading_sources-v1'
        db.executemany('INSERT INTO metadata VALUES(?,?)',meta.items());db.commit();db.execute('ANALYZE');db.close()
        metadata,counts=validate(temporary)
        # Metadata and source proof live in SQLite; no manifest/audit/second map is emitted.
        print(json.dumps({'status':'ready','bytes':target.stat().st_size,'counts':counts}),flush=True)
    except BaseException:
        if incidence is not None:incidence.close()
        db.close();target.unlink(missing_ok=True);raise

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('source',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--region',default='Poland');p.add_argument('--data-date',required=True)
    a=p.parse_args();datetime.date.fromisoformat(a.data_date)
    build(a.source,a.output,a.region,a.data_date)
if __name__=='__main__':main()
