"""Strict navigation-file validation. All diagnostics are fixed strings, never private values."""
import json
import math
import unicodedata

MAX_BYTES = 16384
MAX_DESTINATIONS = 12


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
    def icon(v):
        if v not in ('home', 'squash', 'pin'): raise ValueError('Invalid navigation icon')
        return v
    def destination(d):
        obj(d, {'label', 'icon', 'address', 'latitude', 'longitude', 'navigate_by'})
        d = dict(d)
        text(d.get('label'), 64)
        icon(d.get('icon'))
        if 'address' in d: text(d['address'], 160)
        navigate_by = d.get('navigate_by', 'coordinates')
        if (navigate_by not in ('coordinates', 'address')
                or (navigate_by == 'address' and 'address' not in d)):
            raise ValueError('Invalid navigation target mode')
        for key, limit in [('latitude', 90), ('longitude', 180)]:
            n = d.get(key)
            if type(n) not in (int, float) or not math.isfinite(n) or abs(n) > limit:
                raise ValueError('Invalid navigation coordinate')
        return d
    obj(value, {'schema_version', 'slots'})
    schema = value.get('schema_version')
    if type(schema) is not int or schema not in (1, 2):
        raise ValueError('Unsupported navigation schema')
    entries = value.get('slots')
    if not isinstance(entries, list) or len(entries) > 3: raise ValueError('Invalid navigation slots')
    slots = {}
    for entry in entries:
        obj(entry, {'slot', 'destination'} if schema == 1 else {'slot', 'label', 'icon', 'destinations'})
        slot = entry.get('slot')
        if type(slot) is not int or slot not in (1, 2, 3) or slot in slots:
            raise ValueError('Invalid navigation slot')
        if schema == 1:
            if 'destination' not in entry: raise ValueError('Missing destination')
            d = entry['destination']
            slots[slot] = {'slot': slot, 'destination': None if d is None else destination(d)}
        else:
            places = entry.get('destinations')
            if not isinstance(places, list) or len(places) > MAX_DESTINATIONS:
                raise ValueError('Invalid destinations')
            if 'label' in entry: text(entry['label'], 64)
            if 'icon' in entry: icon(entry['icon'])
            result = {'slot': slot, 'destinations': [destination(d) for d in places]}
            if places:
                result.update(label=text(entry.get('label'), 64), icon=icon(entry.get('icon')))
            slots[slot] = result
    result = {'schema_version': schema, 'slots': [slots.get(n, {'slot': n, **(
        {'destination': None} if schema == 1 else {'destinations': []})}) for n in (1, 2, 3)]}
    if len(json.dumps(result, ensure_ascii=False).encode('utf-8')) > MAX_BYTES:
        raise ValueError('Navigation document too large')
    return result
