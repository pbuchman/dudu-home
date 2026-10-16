#!/usr/bin/env python3
"""Read-only app-category collection and local summaries; never starts/stops radio apps."""
import argparse
from collections import Counter, defaultdict
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

PACKAGE = 'com.pbuchman.duduhome'
ROOT = Path(__file__).resolve().parents[1]
LIMIT = 1024 * 1024
LINE = re.compile(r'([0-9]+) ([0-9]+) ([A-Z0-9_ =:.-]{1,480})')
BLOCK_CAUSES = ('RESOURCE_CLEANUP', 'ACTIVE_OPERATION', 'UI_REQUEST_PENDING', 'PRESENTATION_BUSY',
                'MENU_VISIBLE', 'GATE_PRECONDITION', 'PRIORITY', 'YANOSIK_GRACE')
JOURNAL_NAMES = ('diagnostics.txt', 'diagnostics.previous.txt', *(f'diagnostics.previous.txt.{i}' for i in range(2, 8)))
NUMERIC_FIELDS = {'PROCESS', 'SCHEMA', 'SEQ', 'ID', 'STARTUP', 'GENERATION', 'CYCLE', 'ELIGIBLE',
                  'FIXES', 'POOR', 'HOME', 'FLAGS', 'BLOCKS', 'ELAPSED_MS', 'BLOCKER_ID', 'WAIT_MS', 'VENDOR_CYCLE'}
ENUM_FIELDS = {'ACCURACY', 'AGE', 'SPEED', 'STATE', 'REASON', 'ACTION', 'BLOCKER_ACTION'}
DUMP_KEYS = {'monitor_running', 'action_busy', 'startup', 'departure_cycle', 'departure_reason',
             'gps_accuracy', 'diagnostic_pending', 'trip_state', 'queue_home_waiting'}


def journal(data):
    if len(data) > LIMIT:
        raise ValueError('JOURNAL_TOO_LARGE')
    lines = data.decode('ascii', errors='strict').splitlines()
    if any(not LINE.fullmatch(line) for line in lines):
        raise ValueError('JOURNAL_CATEGORY_FORMAT_REJECTED')
    for line in lines:
        body = LINE.fullmatch(line).group(3)
        if 'SCHEMA=2' not in body:
            continue  # Older category-only journals keep their historical grammar.
        for word in body.split():
            if '=' not in word:
                continue
            key, value = word.split('=', 1)
            if key in NUMERIC_FIELDS and re.fullmatch(r'-?[0-9]{1,18}', value):
                continue
            if key in ENUM_FIELDS and re.fullmatch(r'[A-Z][A-Z0-9_]{0,63}', value):
                continue
            raise ValueError('JOURNAL_FIELD_REJECTED')
    return '\n'.join(lines) + ('\n' if lines else '')


def service_dump(data):
    if len(data) > 1024 * 1024:
        raise ValueError('SERVICE_DUMP_TOO_LARGE')
    accepted = []
    for raw in data.decode('utf-8', errors='replace').splitlines():
        line = raw.strip()
        if line.partition('=')[0] in DUMP_KEYS and re.fullmatch(r'[A-Za-z0-9_ =.-]{1,400}', line):
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


def collect(serial, output):
    status, current = adb(serial, 'get-state')
    if status or current.strip() != b'device':
        raise ValueError('AUTHORIZED_DEVICE_UNAVAILABLE')
    # Reading this app's categories only avoids firmware logcat, private configs and route DBs.
    journals = {}
    for name in JOURNAL_NAMES:
        status, data = adb(serial, 'exec-out', 'run-as', PACKAGE, 'cat', 'no_backup/' + name)
        if not status:
            journals[name] = journal(data)
    if not journals:
        raise ValueError('APP_JOURNAL_UNAVAILABLE')
    status, dump = adb(serial, 'shell', 'dumpsys', 'activity', 'service', PACKAGE + '/.location.HomeMonitorService')
    dump = service_dump(dump) if not status else ''
    destination = private_destination(output)
    for name, data in journals.items():
        write_private(destination / name, data)
    write_private(destination / 'monitor.txt', dump)
    write_private(destination / 'summary.txt', summarize(destination))
    return destination


def summarize(directory):
    directory = Path(directory)
    categories = Counter()
    attempts = defaultdict(list)
    observations = []
    for name in reversed(JOURNAL_NAMES):
        file = directory / name
        if not file.exists():
            continue
        for line in journal(file.read_bytes()).splitlines():
            wall, elapsed, body = LINE.fullmatch(line).groups()
            values = dict(re.findall(r'\b([A-Z_]+)=([A-Z0-9_.:-]+)', body))
            words = [word for word in body.split() if '=' not in word]
            category = words[0] if words else 'COUNTERS'
            categories[category] += 1
            observations.append((int(wall), int(elapsed), body))
            if 'ID' in values:
                attempts[(values.get('PROCESS', 'LEGACY'), values['ID'])].append((int(wall), int(elapsed), body))
    dump_path = directory / 'monitor.txt'
    dump = service_dump(dump_path.read_bytes()) if dump_path.exists() else ''
    result = ['Dudu Home app-category evidence. No physical gate/robot/GPS acceptance is inferred.',
              'Capture is read-only; journal rotation/queue drops/process death can leave gaps.',
              'Current monitor state:', dump.strip() or 'MONITOR_DUMP_UNAVAILABLE',
              f'Observed records: {sum(categories.values())}; correlated attempts: {len(attempts)}',
              'Recent startup/cycle/departure conditions:']
    for _, _, body in sorted(observations)[-80:]:
        if any(token in body for token in ('MONITOR_', 'VENDOR_', 'DEPARTURE_', 'GPS_')):
            result.append(body)
    result.append('Attempt timelines (process + ID):')
    for (process, attempt), entries in list(attempts.items())[-32:]:
        result.append(f'PROCESS={process} ID={attempt}')
        for _, elapsed, body in sorted(entries):
            result.append(f'  ELAPSED={elapsed} {body}')
    result.append('Queue blockers (persisted observations, not proof of physical activity):')
    blockers = Counter()
    for _, _, body in observations:
        if 'QUEUE_STATE ' in body:
            values = dict(re.findall(r'\b([A-Z_]+)=([A-Z0-9_.:-]+)', body))
            if values.get('STATE') == 'WAITING':
                mask = int(values.get('BLOCKS', '0'))
                reasons = [reason for bit, reason in enumerate(BLOCK_CAUSES) if mask & (1 << bit)]
                for reason in reasons or [values.get('REASON', 'UNKNOWN')]:
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
    args = parser.parse_args()
    try:
        if args.command == 'collect':
            collect(args.serial, args.output)
            print('COLLECTION_SAVED_PRIVATE')
        else:
            print(summarize(args.directory), end='')
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
