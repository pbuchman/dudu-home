#!/usr/bin/env python3
"""Read-only app-category collection and local summaries; never starts/stops radio apps."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
from typing import NamedTuple
from zoneinfo import ZoneInfo

PACKAGE = 'com.pbuchman.duduhome'
ROOT = Path(__file__).resolve().parents[1]
LIMIT = 1024 * 1024
LINE = re.compile(r'([0-9]{1,18}) ([0-9]{1,18}) ([A-Z0-9_ =-]{1,480})')
NUMERIC = re.compile(r'-?[0-9]{1,18}')
ENUM = re.compile(r'[A-Z][A-Z0-9_]{0,63}')
CATEGORY = re.compile(r'[A-Z][A-Z0-9_]{1,239}')
EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
WARSAW = ZoneInfo('Europe/Warsaw')
BLOCK_CAUSES = ('RESOURCE_CLEANUP', 'ACTIVE_OPERATION', 'UI_REQUEST_PENDING', 'PRESENTATION_BUSY',
                'MENU_VISIBLE', 'GATE_PRECONDITION', 'PRIORITY', 'YANOSIK_GRACE', 'CLEANING_FEEDBACK')
JOURNAL_NAMES = ('diagnostics.txt', 'diagnostics.previous.txt', *(f'diagnostics.previous.txt.{i}' for i in range(2, 8)))
NUMERIC_FIELDS = {'PROCESS', 'SCHEMA', 'SEQ', 'ID', 'STARTUP', 'OBSERVATION', 'GENERATION', 'CYCLE', 'ELIGIBLE',
                  'FIXES', 'POOR', 'HOME', 'FLAGS', 'BLOCKS', 'ELAPSED_MS', 'BLOCKER_ID', 'WAIT_MS', 'VENDOR_CYCLE', 'COUNT'}
BOOL_FIELDS = {'YANOSIK', 'SPOTIFY'}
ENUM_FIELDS = {'ACCURACY', 'AGE', 'SPEED', 'STATE', 'REASON', 'ACTION', 'BLOCKER_ACTION',
               'GAP', 'SOURCE', 'BLOCKER', 'BASELINE', 'STAGE', 'Y_ACCESS', 'Y_LISTENER', 'Y_FGS',
               'S_ACCESS', 'S_SESSIONS', 'S_SCOPE', 'S_PLAYBACK'}
LEGACY_EVENTS = {'DEPARTURE_STARTED', 'RETURN_APPROACH', 'OUTBOUND_CHECKPOINT', 'JOURNEY_EXIT'}
UNSIGNED = re.compile(r'0|[1-9][0-9]{0,17}')
STARTUP_DETAILS = {
    'STARTED': ('REASON', {'SERVICE_START', 'VERIFIED_WAKE'}),
    'CYCLE_READ': ('STATE', {'BASELINE_ZERO', 'BASELINE_COUNTER', 'REARMED', 'UNCHANGED',
                             'UNAVAILABLE', 'INVALID_BOOT', 'STATE_WRITE_FAILED'}),
    'SUBSCRIBED': ('STATE', {'ACTIVE', 'ALREADY_ACTIVE'}),
    'SUBSCRIBE_DENIED': None,
    'RESUBSCRIBE': None,
    'NO_CALLBACK': None,
    'FIRST_CALLBACK': ('STATE', {'RECEIVED', 'EMPTY'}),
    'MOTION_FIX_REJECTED': ('REASON', {'EMPTY_CALLBACK', 'BAD_TIME', 'STALE_FIX', 'MOCK_FIX',
                                      'SPEED_MISSING', 'SPEED_INVALID', 'ACCURACY_MISSING',
                                      'ACCURACY_INVALID', 'ACCURACY_TOO_LOW', 'POSITION_INVALID', 'NONMONOTONIC'}),
    'FIRST_USABLE_MOTION_FIX': ('STATE', {'WAITING', 'BASELINE_WAIT', 'BASELINE_READY',
                                         'STOPPED', 'CANDIDATE', 'CONFIRMED'}),
    'FIRST_ARCHIVE_COMMIT': None,
    'MOTION_CONFIRMED': None,
}
MEDIA_SOURCES = {'MOTION_CONFIRMED', 'DEPARTURE_CONTINUATION'}
MEDIA_TARGETS = {'YANOSIK', 'SPOTIFY'}
CORRELATION_FIELDS = ('PROCESS', 'STARTUP', 'OBSERVATION', 'CYCLE', 'ID')
DUMP_KEYS = {'monitor_running', 'action_busy', 'startup', 'departure_cycle', 'departure_reason',
             'gps_accuracy', 'diagnostic_pending', 'trip_state', 'queue_home_waiting', 'motion_state',
             'routebook', 'routebook_filter', 'routebook_power'}
DUMP_NUMERIC = {'startup', 'departure_cycle', 'candidate_elapsed_ms', 'diagnostic_pending', 'dropped',
                'map_schema', 'queue_generation', 'motion_elapsed_ms', 'motion_cycle', 'motion_generation',
                'PENDING', 'REJECTED_INVALID', 'REJECTED_CONFLICT', 'gps_http', 'gps_wait_ms',
                'gps_accepted', 'gps_duplicate', 'gps_terminal', 'POWER_PENDING', 'POWER_REJECTED',
                'power_http', 'power_wait_ms', 'power_accepted', 'power_duplicate', 'power_terminal',
                'routebook_callbacks', 'routebook_committed', 'routebook_discarded', 'routebook_dropped'}
DUMP_BOOL = {'monitor_running', 'registered', 'action_busy', 'cycle_ready', 'consumed', 'blocked', 'loaded',
             'gps_fresh', 'storage_ok', 'queue_home_waiting', 'gate_media_allowed', 'external_launch_allowed'}
DUMP_ENUM = {'baseline', 'departure_reason', 'gps_accuracy', 'gps_age', 'gps_speed', 'trip_state',
             'locality_quality', 'street_quality', 'display_street_quality', 'locality_source', 'street_source',
             'display_street_source', 'motion_state', 'motion_reason', 'motion_blocker', 'motion_source',
             'motion_baseline', 'motion_gap', 'routebook', 'upload', 'routebook_power', 'power_upload',
             'routebook_filter', 'routebook_reason'}


class Record(NamedTuple):
    wall: int
    elapsed: int
    body: str
    category: str
    fields: dict


def known_counter(value):
    return bool(UNSIGNED.fullmatch(value)) and int(value) <= 999_999_999


def valid_startup(fields):
    required = {'SCHEMA', 'PROCESS', 'SEQ', 'STARTUP', 'OBSERVATION', 'STAGE', 'ELAPSED_MS'}
    if not required <= fields.keys() or fields['STAGE'] not in STARTUP_DETAILS:
        return False
    stage = fields['STAGE']
    detail = STARTUP_DETAILS[stage]
    if detail:
        key, allowed = detail
        if fields.get(key) not in allowed:
            return False
        required.add(key)
    if not required <= fields.keys() <= required | {'CYCLE'}:
        return False
    cycle = fields.get('CYCLE')
    if cycle is not None and not (known_counter(cycle) or (cycle == '-1' and stage == 'CYCLE_READ')):
        return False
    if stage == 'CYCLE_READ' and cycle is None:
        return False
    if stage == 'STARTED':
        service_start = fields['REASON'] == 'SERVICE_START'
        if (fields['ELAPSED_MS'] != '0' or service_start != (fields['OBSERVATION'] == '0')
                or (not service_start and cycle is None)):
            return False
    return fields['SCHEMA'] == '2' and fields['SEQ'] != '0' and all(
        UNSIGNED.fullmatch(fields[key]) for key in ('PROCESS', 'SEQ', 'STARTUP', 'OBSERVATION', 'ELAPSED_MS'))


def valid_media_enqueue(fields):
    required = {'SCHEMA', 'PROCESS', 'SEQ', 'STARTUP', 'OBSERVATION', 'CYCLE', 'STAGE', 'ACTION', 'ID'}
    return (fields.keys() == required and fields['SCHEMA'] == '2'
            and fields['STAGE'] in MEDIA_SOURCES and fields['ACTION'] in MEDIA_TARGETS
            and (fields['CYCLE'] == '-1' or known_counter(fields['CYCLE']))
            and all(fields[key] == '-1' or UNSIGNED.fullmatch(fields[key]) for key in ('STARTUP', 'OBSERVATION'))
            and fields['SEQ'] != '0' and fields['ID'] != '0' and all(
                UNSIGNED.fullmatch(fields[key]) for key in ('PROCESS', 'SEQ', 'ID')))


def records(data):
    if len(data) > LIMIT:
        raise ValueError('JOURNAL_TOO_LARGE')
    for raw in data.decode('ascii').splitlines(keepends=True):
        line = raw.removesuffix('\n').removesuffix('\r')
        match = LINE.fullmatch(line)
        if match is None:
            raise ValueError('JOURNAL_CATEGORY_FORMAT_REJECTED')
        wall, elapsed, body = match.groups()
        labels = []
        fields = {}
        for word in body.split(' '):
            key, separator, value = word.partition('=')
            if not separator and CATEGORY.fullmatch(key):
                labels.append(key)
                continue
            if key not in fields and (
                (key in NUMERIC_FIELDS and NUMERIC.fullmatch(value))
                or (key in ENUM_FIELDS and ENUM.fullmatch(value))
                or (key in BOOL_FIELDS and value in ('0', '1'))
            ):
                fields[key] = value
                continue
            raise ValueError('JOURNAL_FIELD_REJECTED')
        if fields.get('SCHEMA', '1') not in ('1', '2'):
            raise ValueError('JOURNAL_SCHEMA_UNSUPPORTED')
        if not labels and 'VENDOR_CYCLE' in fields:
            category = 'VENDOR_CYCLE'
        elif len(labels) == 1:
            category = labels[0]
        elif len(labels) == 2 and fields.get('SCHEMA', '1') == '1' and labels[0] == 'EVENT' and labels[1] in LEGACY_EVENTS:
            category = 'EVENT'
        else:
            raise ValueError('JOURNAL_CATEGORY_FORMAT_REJECTED')
        if 'OBSERVATION' in fields and fields['OBSERVATION'] != '-1' and not UNSIGNED.fullmatch(fields['OBSERVATION']):
            raise ValueError('JOURNAL_FIELD_REJECTED')
        if category == 'GPS_STARTUP' and (not raw.endswith('\n') or not valid_startup(fields)):
            raise ValueError('JOURNAL_STARTUP_FORMAT_REJECTED')
        if category == 'MEDIA_ENQUEUED' and (not raw.endswith('\n') or not valid_media_enqueue(fields)):
            raise ValueError('JOURNAL_MEDIA_ENQUEUE_FORMAT_REJECTED')
        yield Record(int(wall), int(elapsed), body, category, fields)


def journal(data):
    return ''.join(f'{record.wall} {record.elapsed} {record.body}\n' for record in records(data))


def dump_field(word):
    key, _, value = word.partition('=')
    return ((key in DUMP_NUMERIC and NUMERIC.fullmatch(value))
            or (key in DUMP_BOOL and value in ('true', 'false'))
            or (key in DUMP_ENUM and ENUM.fullmatch(value))
            or (key == 'map' and value in ('missing', 'ready', 'invalid')))


def service_dump(data):
    if len(data) > LIMIT:
        raise ValueError('SERVICE_DUMP_TOO_LARGE')
    accepted = []
    for raw in data.decode('utf-8', errors='replace').splitlines():
        line = raw.strip()
        if len(line) <= 400 and line.partition('=')[0] in DUMP_KEYS and all(dump_field(word) for word in line.split(' ')):
            accepted.append(line)
    return '\n'.join(accepted) + ('\n' if accepted else '')


def private_destination(path):
    path = Path(path).expanduser()
    if path.exists() or path.is_symlink():
        raise ValueError('OUTPUT_ALREADY_EXISTS')
    parent = path.parent.resolve(strict=True)
    if not parent.is_dir() or parent.stat().st_uid != os.getuid() or stat.S_IMODE(parent.stat().st_mode) & 0o077:
        raise ValueError('OUTPUT_PARENT_MUST_BE_OWNER_PRIVATE')
    resolved = parent / path.name
    if ROOT == parent or ROOT in parent.parents or any((p / '.git').exists() for p in (parent, *parent.parents)):
        raise ValueError('OUTPUT_MUST_BE_OUTSIDE_GIT')
    resolved.mkdir(mode=0o700)
    return resolved


def write_private(path, text):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as output:
        output.write(text)
        output.flush()
        os.fsync(output.fileno())


def adb(serial, *args):
    result = subprocess.run(['adb', '-s', serial, *args], capture_output=True, timeout=15, check=False)
    return result.returncode, result.stdout


def collect(serial, output, window_start=None, window_end=None):
    window_bounds(window_start, window_end)
    status, current = adb(serial, 'get-state')
    if status or current.strip() != b'device':
        raise ValueError('AUTHORIZED_DEVICE_UNAVAILABLE')
    # Reading this app's categories only avoids firmware logcat, private configs and route DBs.
    journals = {}
    for name in JOURNAL_NAMES:
        path = 'no_backup/' + name
        # shell -T uses the remote exit status. exec-out can report cat's error as success and stdout.
        status, data = adb(serial, 'shell', '-T', 'run-as', PACKAGE, 'cat', path)
        if status == 0:
            journals[name] = journal(data)
        else:
            exists, _ = adb(serial, 'shell', '-T', 'run-as', PACKAGE, 'test', '-f', path)
            if status != 1 or exists != 1:
                raise ValueError('APP_JOURNAL_READ_FAILED')
    if not journals:
        raise ValueError('APP_JOURNAL_UNAVAILABLE')
    try:
        status, dump = adb(serial, 'shell', 'dumpsys', 'activity', 'service', PACKAGE + '/.location.HomeMonitorService')
        dump = service_dump(dump) if not status and len(dump) <= LIMIT else ''
    except subprocess.TimeoutExpired:
        dump = ''
    destination = private_destination(output)
    for name, data in journals.items():
        write_private(destination / name, data)
    write_private(destination / 'monitor.txt', dump)
    write_private(destination / 'summary.txt', summarize(destination, window_start, window_end))
    return destination


def window_bounds(start, end):
    def parse(value):
        if value is None:
            return None
        try:
            if value.endswith('Z'):
                value = value[:-1] + '+00:00'
            timestamp = datetime.fromisoformat(value)
            if timestamp.utcoffset() is not None:
                return (timestamp.astimezone(timezone.utc) - EPOCH) // timedelta(microseconds=1)
        except (ValueError, OverflowError):
            pass
        raise ValueError('WINDOW_REQUIRES_ISO_TIME_WITH_OFFSET')

    lower, upper = parse(start), parse(end)
    if lower is not None and upper is not None and lower >= upper:
        raise ValueError('WINDOW_BEGIN_MUST_PRECEDE_END')
    return lower, upper


def local_time(wall):
    try:
        return (EPOCH + timedelta(milliseconds=wall)).astimezone(WARSAW).isoformat(timespec='milliseconds')
    except OverflowError:
        return f'WALL_MS={wall}'


def boundary_time(microseconds):
    if microseconds is None:
        return 'unbounded'
    try:
        return (EPOCH + timedelta(microseconds=microseconds)).astimezone(WARSAW).isoformat()
    except OverflowError:
        return (EPOCH + timedelta(microseconds=microseconds)).isoformat()


def evidence_line(record):
    legacy = 'PROCESS=LEGACY ' if 'PROCESS' not in record.fields else ''
    return f'{local_time(record.wall)} ELAPSED={record.elapsed} {legacy}{record.body}'


def read_limited(path):
    with path.open('rb') as source:
        return source.read(LIMIT + 1)


STARTUP_LABELS = {
    'STARTED': 'Monitor observation started',
    'CYCLE_READ': 'Cycle read',
    'SUBSCRIBED': 'GPS subscription',
    'SUBSCRIBE_DENIED': 'GPS subscription denied',
    'RESUBSCRIBE': 'GPS resubscription attempted',
    'NO_CALLBACK': 'Watchdog observed no GPS callback through this check',
    'FIRST_CALLBACK': 'First GPS callback',
    'MOTION_FIX_REJECTED': 'GPS callback rejected by motion checks',
    'FIRST_USABLE_MOTION_FIX': 'First usable motion fix',
    'FIRST_ARCHIVE_COMMIT': 'First Routebook SQLite commit',
    'MOTION_CONFIRMED': 'Motion confirmed',
}


def field_text(fields, keys):
    return ' '.join(f'{key}={fields[key]}' for key in keys if key in fields)


def startup_report(observations):
    result = ['GPS startup evidence in the window:']
    traces = defaultdict(list)
    for record in observations:
        if record.category == 'GPS_STARTUP':
            values = record.fields
            traces[(values['PROCESS'], values['STARTUP'], values['OBSERVATION'])].append(record)
    if not traces:
        result.append('  Startup callback timing is unknown. No startup trace rows are retained in this window.')
    for (process, startup, observation), entries in traces.items():
        result.append(f'  PROCESS={process} STARTUP={startup} OBSERVATION={observation}')
        if not any(record.fields['STAGE'] == 'NO_CALLBACK' for record in entries):
            result.append('    No retained watchdog record establishes a callback-free period in this window.')
        for record in sorted(entries, key=lambda row: int(row.fields['ELAPSED_MS'])):
            values = record.fields
            label = STARTUP_LABELS[values['STAGE']]
            details = field_text(values, ('STAGE', 'CYCLE', 'STATE', 'REASON'))
            result.append(f'    {local_time(record.wall)} (+{values["ELAPSED_MS"]} ms) {label} [{details}]')
    return result


def attempt_key(record):
    values = record.fields
    return (values['PROCESS'], values['ID']) if 'PROCESS' in values and 'ID' in values else None


def observation_order(record):
    return record.elapsed, int(record.fields.get('SEQ', '0'))


def compatible_context(record, context):
    return all(record.fields[key] == context.fields[key] for key in ('STARTUP', 'OBSERVATION', 'CYCLE')
               if key in record.fields and key in context.fields)


def media_action(record):
    if record.category in ('QUEUE_STATE', 'MEDIA_ENQUEUED'):
        return record.fields.get('ACTION')
    for action in ('YANOSIK', 'SPOTIFY'):
        if record.category.startswith((action + '_', 'ACTION_' + action + '_')):
            return action
    return None


def queue_causes(values):
    mask = int(values.get('BLOCKS', '0'))
    if 0 < mask < 1 << len(BLOCK_CAUSES):
        return [reason for bit, reason in enumerate(BLOCK_CAUSES) if mask & (1 << bit)]
    return [values.get('REASON', 'UNKNOWN')]


def media_label(record, earlier_gate_wait):
    values = record.fields
    if record.category == 'MEDIA_ENQUEUE_SUPPRESSED':
        source = values.get('STAGE')
        reason = values.get('REASON', 'UNKNOWN')
        if source == 'MOTION_CONFIRMED':
            return f'Media enqueue blocked after confirmed motion ({reason})'
        if source == 'DEPARTURE_CONTINUATION':
            return f'Media enqueue blocked after departure continuation ({reason})'
    if record.category == 'MEDIA_ENQUEUED':
        return f'{values["ACTION"]} queued ({values["STAGE"]})'
    if record.category == 'MOTION_STATE':
        state, reason, blocker = (values.get(key, 'UNKNOWN') for key in ('STATE', 'REASON', 'BLOCKER'))
        if state in ('CONFIRMED', 'REJECTED') or reason == 'ACCURACY_OVERLAP' or blocker not in ('NONE', 'UNKNOWN'):
            return f'Motion detector {state} ({reason}, blocker={blocker})'
    if record.category == 'QUEUE_STATE' and values.get('ACTION') in MEDIA_TARGETS:
        action = values['ACTION']
        state = values.get('STATE', 'UNKNOWN')
        causes = ', '.join(queue_causes(values))
        if state == 'EXPIRED' and earlier_gate_wait:
            return f'{action} queue expired ({causes}); earlier gate-precondition wait observed at {local_time(earlier_gate_wait.wall)}'
        return f'{action} queue {state} ({causes})'
    if record.category.startswith(('ACTION_YANOSIK_SKIPPED_', 'ACTION_SPOTIFY_SKIPPED_',
                                   'ACTION_YANOSIK_CANCELLED_', 'ACTION_SPOTIFY_CANCELLED_',
                                   'YANOSIK_', 'SPOTIFY_')):
        return record.category
    if record.category in ('MEDIA_GATE_RESULT', 'DEPARTURE_CONTINUATION_REJECTED'):
        return f'{record.category} ({values.get("REASON", "UNKNOWN")})'
    if record.category.startswith('MONITOR_'):
        return record.category
    return None


def media_report(observations, retained, lower, upper):
    result = ['Motion, gate and media decisions in the window:']
    enqueues = defaultdict(list)
    gate_waits = defaultdict(list)
    for record in retained:
        key = attempt_key(record)
        if key is None or (upper is not None and record.wall * 1000 >= upper):
            continue
        if record.category == 'MEDIA_ENQUEUED':
            enqueues[key].append(record)
        values = record.fields
        if (record.category == 'QUEUE_STATE' and values.get('STATE') == 'WAITING'
                and 'GATE_PRECONDITION' in queue_causes(values)):
            gate_waits[key].append(record)

    launches = set()
    for record in observations:
        values = record.fields
        key = attempt_key(record)
        earlier = None
        matching = enqueues.get(key, ())
        enqueues_compatible = (len(matching) == 1 and compatible_context(record, matching[0])
                               and media_action(record) == matching[0].fields['ACTION'])
        enqueue = matching[0] if enqueues_compatible else None
        if record.category == 'QUEUE_STATE' and values.get('STATE') == 'EXPIRED' and (not matching or enqueue):
            earlier = max((wait for wait in gate_waits.get(key, ())
                           if wait.fields.get('ACTION') == values.get('ACTION')
                           and compatible_context(record, wait) and (enqueue is None or compatible_context(enqueue, wait))
                           and observation_order(wait) < observation_order(record)),
                          key=observation_order, default=None)
        if record.category == 'SPOTIFY_LAUNCH_REQUESTED' and key is not None:
            launches.add(key)
        label = media_label(record, earlier)
        if label is None:
            continue
        context = field_text(enqueue.fields if enqueue else values, CORRELATION_FIELDS)
        if enqueue and lower is not None and enqueue.wall * 1000 < lower:
            context += ' (enqueue recorded before window)'
        if earlier and lower is not None and earlier.wall * 1000 < lower:
            label += ' (wait recorded before window)'
        result.append(f'  {local_time(record.wall)} {label}' + (f' [{context}]' if context else ''))

    if len(result) == 1:
        result.append('  No retained decision or action markers in this window. Activity is unknown.')
    if launches:
        attempt = 'attempt' if len(launches) == 1 else 'attempts'
        result.append(f'  Spotify app launch requests recorded for {len(launches)} distinct {attempt}. '
                      'These markers establish app requests. Visible launches and audible playback require separate evidence.')
    return result


def summarize(directory, window_start=None, window_end=None):
    directory = Path(directory)
    lower, upper = window_bounds(window_start, window_end)
    limited_window = lower is not None or upper is not None
    retained = []
    seen = set()
    duplicate_rows = 0
    for name in reversed(JOURNAL_NAMES):
        file = directory / name
        if not file.exists():
            continue
        for record in records(read_limited(file)):
            if record.fields.get('SCHEMA') == '2' and 'SEQ' in record.fields and 'PROCESS' in record.fields:
                identity = (record.wall, record.elapsed, record.body)
                if identity in seen:
                    duplicate_rows += 1
                    continue
                seen.add(identity)
            retained.append(record)
    # Stable for equal wall timestamps. Retained order follows the oldest-to-newest segments.
    retained.sort(key=lambda record: record.wall)
    observations = [record for record in retained
                    if (lower is None or record.wall * 1000 >= lower)
                    and (upper is None or record.wall * 1000 < upper)]
    categories = Counter()
    attempts = defaultdict(list)
    sequence = defaultdict(set)
    for record in observations:
        values = record.fields
        categories[record.category] += 1
        if 'ID' in values:
            attempts[(values.get('PROCESS', 'LEGACY'), values['ID'])].append(record)
        if values.get('SCHEMA') == '2' and 'SEQ' in values and 'PROCESS' in values:
            sequence[values['PROCESS']].add(int(values['SEQ']))
    dump_path = directory / 'monitor.txt'
    dump = service_dump(read_limited(dump_path)) if dump_path.exists() else ''
    result = ['Dudu Home app-category evidence. No physical gate/robot/GPS acceptance is inferred.',
              'Capture is read-only; journal rotation/queue drops/process death can leave gaps.',
              'Monitor state at capture time:', dump.strip() or 'MONITOR_DUMP_UNAVAILABLE']
    if limited_window:
        result.append(f'Requested window: {boundary_time(lower)} (inclusive) to {boundary_time(upper)} (exclusive)')
    if retained:
        result.append(f'Retained wall-clock range: {local_time(retained[0].wall)} to {local_time(retained[-1].wall)}')
    else:
        result.append('Retained wall-clock range: NO_JOURNAL_ROWS')
    result.append(f'Observed records: {len(observations)}; correlated attempts: {len(attempts)}')
    if duplicate_rows:
        result.append(f'Identical schema-2 rows skipped across the capture: {duplicate_rows}')
    if limited_window:
        missing = sum(max(items) - min(items) + 1 - len(items) for items in sequence.values()) if sequence else 'UNAVAILABLE'
        result.append(f'Unseen sequence values between observed rows in the window: {missing}')
        result.append('Media requests recorded in the window: '
                      f'YANOSIK={categories["ACTION_REQUESTED_YANOSIK"]} SPOTIFY={categories["ACTION_REQUESTED_SPOTIFY"]}')
        result.extend(startup_report(observations))
        result.extend(media_report(observations, retained, lower, upper))
        result.append('Window timeline (Europe/Warsaw):')
        result.extend(evidence_line(record) for record in observations)
    else:
        result.append('Recent startup, motion and media observations (Europe/Warsaw):')
        context = [record for record in observations if record.category.startswith((
            'BOOT_', 'WAKE_', 'MONITOR_', 'VENDOR_', 'DEPARTURE_', 'GPS_', 'MOTION_', 'MEDIA_',
            'YANOSIK_', 'SPOTIFY_', 'PROGRESS_RESET_', 'ROUTEBOOK_'))]
        result.extend(evidence_line(record) for record in context[-80:])
        result.append('Attempt timelines (process + ID):')
        for (process, attempt), entries in list(attempts.items())[-32:]:
            result.append(f'PROCESS={process} ID={attempt}')
            result.extend('  ' + evidence_line(record) for record in entries)
    result.append('Queue blockers (persisted observations, not proof of physical activity):')
    blockers = Counter()
    for record in observations:
        values = record.fields
        if record.category == 'QUEUE_STATE' and values.get('STATE') == 'WAITING':
            for reason in queue_causes(values):
                blockers[(values.get('ACTION', 'UNKNOWN'), reason,
                          values.get('BLOCKER_ACTION', 'NONE'))] += 1
    result.extend(f'{action}: {reason}; blocker={blocker}; observations={count}'
                  for (action, reason, blocker), count in sorted(blockers.items()))
    result.append('Category counts:')
    result.extend(f'{category}={count}' for category, count in sorted(categories.items()))
    return '\n'.join(result) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    capture = sub.add_parser('collect')
    capture.add_argument('--serial', required=True)
    capture.add_argument('--output', required=True, type=Path)
    report = sub.add_parser('summarize')
    report.add_argument('directory', type=Path)
    for command in (capture, report):
        command.add_argument('--from', dest='window_start', help='inclusive ISO 8601 date and time with UTC offset')
        command.add_argument('--to', dest='window_end', help='exclusive ISO 8601 date and time with UTC offset')
    args = parser.parse_args()
    try:
        if args.command == 'collect':
            collect(args.serial, args.output, args.window_start, args.window_end)
            print('COLLECTION_SAVED_PRIVATE')
        else:
            print(summarize(args.directory, args.window_start, args.window_end), end='')
    except (ValueError, OSError, UnicodeError, subprocess.SubprocessError) as error:
        # Never echo command stderr, serials, raw dump text or arbitrary path/exception payloads.
        category = str(error) if isinstance(error, ValueError) else 'DIAGNOSTIC_TOOL_FAILED'
        if not re.fullmatch(r'[A-Z_]+', category):
            category = 'DIAGNOSTIC_TOOL_FAILED'
        print(category, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
