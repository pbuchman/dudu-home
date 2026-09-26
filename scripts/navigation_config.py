"""Strict navigation-file validation. All diagnostics are fixed strings, never private values."""
import json
import math
import unicodedata

MAX_BYTES = 16384


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result: raise ValueError('Duplicate navigation field')
        result[key] = value
    return result


def read_navigation(path):
    with path.open('rb') as stream: data = stream.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES: raise ValueError('Navigation document too large')
    try:
        value = json.loads(data.decode('utf-8'), object_pairs_hook=_pairs,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Invalid navigation number')))
    except (ValueError, UnicodeError, RecursionError):
        raise ValueError('Invalid navigation document') from None
    return validate_navigation(value)


def validate_navigation(value):
    def obj(v, allowed):
        if not isinstance(v, dict) or set(v) - allowed: raise ValueError('Invalid navigation fields')
    def text(v, limit):
        if (not isinstance(v, str) or not v or len(v) > limit or v != v.strip()
                or any(unicodedata.category(c) in ('Cc', 'Cf') for c in v)):
            raise ValueError('Invalid navigation text')
        return v
    obj(value, {'schema_version', 'slots'})
    if type(value.get('schema_version')) is not int or value['schema_version'] != 1:
        raise ValueError('Unsupported navigation schema')
    entries = value.get('slots')
    if not isinstance(entries, list) or len(entries) > 3: raise ValueError('Invalid navigation slots')
    slots = {}
    for entry in entries:
        obj(entry, {'slot', 'destination'})
        slot = entry.get('slot')
        if type(slot) is not int or slot not in (1, 2, 3) or slot in slots or 'destination' not in entry:
            raise ValueError('Invalid navigation slot')
        d = entry['destination']
        if d is not None:
            obj(d, {'label', 'icon', 'address', 'latitude', 'longitude', 'navigate_by'})
            d = dict(d)
            text(d.get('label'), 64)
            if d.get('icon') not in ('home', 'squash', 'pin'): raise ValueError('Invalid navigation icon')
            if 'address' in d: text(d['address'], 160)
            navigate_by = d.get('navigate_by', 'coordinates')
            if (navigate_by not in ('coordinates', 'address')
                    or (navigate_by == 'address' and 'address' not in d)):
                raise ValueError('Invalid navigation target mode')
            for key, limit in [('latitude', 90), ('longitude', 180)]:
                n = d.get(key)
                if type(n) not in (int, float) or not math.isfinite(n) or abs(n) > limit:
                    raise ValueError('Invalid navigation coordinate')
        slots[slot] = d
    result = {'schema_version': 1, 'slots': [{'slot': n, 'destination': slots.get(n)} for n in (1, 2, 3)]}
    if len(json.dumps(result, ensure_ascii=False).encode('utf-8')) > MAX_BYTES:
        raise ValueError('Navigation document too large')
    return result
