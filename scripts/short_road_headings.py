"""Computer-only schema-3 heading policy. Source identities never become road-name rules."""
from collections import Counter
import hashlib
import json
import math
import sqlite3

POLICY_ID = 'same-osm-way-degree2-v1'
POLICY = dict(id=POLICY_ID, short_max_m=5, extend_each_end_m=10,
              max_edges_each_end=8, minimum_chain_m=10, minimum_chord_m=10,
              max_directed_bend_deg=15,
              projection='equirectangular-111195-fragment-midlatitude',
              incidence='eligible-highway-source-edge-endpoints-saturated-at-3',
              stops='way-end-or-degree-not-2',
              reject='missing-or-zero-geometry-repeated-node-curve-edge-limit')
POLICY_JSON = json.dumps(POLICY, sort_keys=True, separators=(',', ':'))
POLICY_SHA256 = hashlib.sha256(POLICY_JSON.encode()).hexdigest()

def generation(source_sha256):
    return hashlib.sha256(('3\n'+source_sha256+'\n'+POLICY_SHA256+'\n').encode()).hexdigest()

def metadata(source_sha256):
    return {'short_heading_policy': POLICY_ID, 'short_heading_policy_sha256': POLICY_SHA256,
            'short_heading_source_sha256': source_sha256, 'map_generation': generation(source_sha256)}

class Incidence:
    """Exact degree==2 proof; values >=3 saturate. RAM and SQLite page cache are bounded."""
    BATCH = 100000
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.executescript('PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; '
                             'PRAGMA temp_store=FILE; PRAGMA cache_size=-32768; '
                             'CREATE TABLE incidence(node_id INTEGER PRIMARY KEY,degree INTEGER NOT NULL);')
        self.pending = Counter()
        self.endpoints = 0
    def add_way(self, refs):
        previous = None
        for ref in refs:
            if previous is not None:
                self.pending[previous] += 1
                if len(self.pending) >= self.BATCH: self.flush()
                self.pending[ref] += 1
                self.endpoints += 2
                if len(self.pending) >= self.BATCH: self.flush()
            previous = ref
    def flush(self):
        self.db.executemany('INSERT INTO incidence VALUES(?,?) ON CONFLICT(node_id) '
                            'DO UPDATE SET degree=min(3,degree+excluded.degree)',
                            ((node, min(3, degree)) for node, degree in self.pending.items()))
        self.pending.clear()
        self.db.commit()
    def degrees(self, refs):
        result = {}
        refs = list(set(refs))
        for offset in range(0, len(refs), 900):
            batch = refs[offset:offset+900]
            result.update(self.db.execute('SELECT node_id,degree FROM incidence WHERE node_id IN ('+
                                          ','.join('?' for _ in batch)+')', batch))
        return result
    def close(self): self.db.close()

def orientation(refs, points, ordinal, degrees):
    """Contiguous original-way support only. Points are (lon,lat); no coordinate joins."""
    a, b = points[ordinal:ordinal+2]
    scale = 111195*math.cos(math.radians((a[1]+b[1])/2))
    length = math.hypot((b[0]-a[0])*scale, (b[1]-a[1])*111195)
    if not 0 < length <= 5: return None
    if not all(math.isfinite(v) for point in points for v in point): return None
    projected = [((p[0]-a[0])*scale, (p[1]-a[1])*111195) for p in points]
    ax, ay = projected[ordinal]; bx, by = projected[ordinal+1]
    length = math.hypot(bx-ax, by-ay)
    if len(set(refs)) != len(refs): return None
    heading = math.degrees(math.atan2(bx-ax, by-ay)) % 360
    used = {ordinal}; proofs = []; ends = []; total = length; support_range = [ordinal, ordinal]
    for step, node_index in ((-1, ordinal), (1, ordinal+1)):
        point = projected[node_index]; remaining = 10.; stop = 'WAY_END'; traversed = []
        outward = (heading + (180 if step == -1 else 0)) % 360
        for count in range(8):
            edge = node_index-1 if step == -1 else node_index
            if edge < 0 or edge >= len(points)-1: break
            degree = degrees.get(refs[node_index])
            if degree != 2:
                stop = 'DEGREE_NOT_2'; break
            if edge in used: return None
            far = projected[node_index+step]
            dx, dy = far[0]-point[0], far[1]-point[1]
            edge_length = math.hypot(dx, dy)
            if edge_length <= 0: return None
            direction = math.degrees(math.atan2(dx, dy)) % 360
            if abs((direction-outward+180) % 360-180) > 15: return None
            take = min(remaining, edge_length)
            point = (point[0]+dx*take/edge_length, point[1]+dy*take/edge_length)
            total += take; remaining -= take; used.add(edge)
            proofs.append((refs[node_index], degree)); traversed.append(edge)
            support_range = [min(support_range[0], edge), max(support_range[1], edge)]
            if remaining <= 1e-9:
                stop = 'EXTENT_COMPLETE'; break
            node_index += step
        else:
            # Exhaustion is rejected even if the next node might be a dead end.
            return None
        ends.append(point)
        proofs.append(('stop', stop, traversed))
    chord = math.hypot(ends[1][0]-ends[0][0], ends[1][1]-ends[0][1])
    if total < 10 or chord < 10: return None
    return dict(heading=math.degrees(math.atan2(ends[1][0]-ends[0][0], ends[1][1]-ends[0][1])) % 360,
                support_length=total, chord_length=chord, ordinal=ordinal,
                support_range=support_range, degree_proof=proofs)
