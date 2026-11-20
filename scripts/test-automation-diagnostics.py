#!/usr/bin/env python3
"""Synthetic private collection tests: no adb/device/network operations."""
import importlib.util
import os
from pathlib import Path
import stat
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('diagnostic_tool', Path(__file__).with_name('automation-diagnostics.py'))
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


WINDOW_START = '2026-11-18T14:00:00+01:00'
WINDOW_END = '2026-11-18T14:02:00+01:00'
WINDOW_WALL = (tool.datetime.fromisoformat(WINDOW_START) - tool.EPOCH) // tool.timedelta(milliseconds=1)


def row(second, sequence, body, process=1):
    return f'{WINDOW_WALL + 1000 * second} {100_000 + 1000 * second} PROCESS={process} SCHEMA=2 SEQ={sequence} {body}\n'


def gps(second, sequence, stage, elapsed, details='', *, startup=7, observation=0, process=1):
    return row(second, sequence, f'GPS_STARTUP STARTUP={startup} OBSERVATION={observation} '
               f'STAGE={stage} ELAPSED_MS={elapsed}{details}', process=process)


def report_decisions(report):
    return report.split('Motion, gate and media decisions in the window:\n', 1)[1].split('Window timeline', 1)[0]


class Checks(unittest.TestCase):
    def test_category_only_input(self):
        self.assertEqual(tool.journal(b'42 17 PROCESS=1 ID=2 GATE_ERROR_PHONE_BUSY\n'),
                         '42 17 PROCESS=1 ID=2 GATE_ERROR_PHONE_BUSY\n')
        for data in (b'private lower case payload', b'42 17 SECRET/URL\n', b'x' * (tool.LIMIT + 1)):
            with self.assertRaises(ValueError):
                tool.journal(data)
        self.assertEqual(tool.journal(b'42 17 EVENT DEPARTURE_STARTED\n43 18 VENDOR_CYCLE=8\n'),
                         '42 17 EVENT DEPARTURE_STARTED\n43 18 VENDOR_CYCLE=8\n')
        for data in (b'42 17 EVENT SECRET_PAYLOAD\n', b'42 17 GPS_SUMMARY TOKEN=ABC\n',
                     b'42 17 PROCESS=1 SCHEMA=3 SEQ=1 MONITOR_CREATE\n',
                     b'42 17 PROCESS=1 SCHEMA=2 SEQ=1 MOTION_FIX YANOSIK=20\n',
                     b'42 17 PROCESS=1 SCHEMA=2 SEQ=1 MEDIA_TARGET_SNAPSHOT S_SCOPE=A S_SCOPE=B\n'):
            with self.assertRaises(ValueError):
                tool.journal(data)

    def test_dump_filters_unrelated_identifiers_and_raw_system_data(self):
        raw = (b'  departure_reason=ACCURACY candidate_elapsed_ms=0\n'
               b'  gps_accuracy=LE_25M gps_age=LE_3S gps_speed=MOVING\n'
               b'  motion_state=WAITING motion_reason=GAP motion_baseline=REQUIRED motion_elapsed_ms=0\n'
               b'routebook=COLLECTION_READY upload=ENABLED PENDING=5 REJECTED_INVALID=0 REJECTED_CONFLICT=0 gps_http=200 gps_wait_ms=0 gps_accepted=8 gps_duplicate=2 gps_terminal=0\n'
               b'routebook_filter=DISCARDED routebook_reason=TOO_SOON routebook_callbacks=10 routebook_committed=5 routebook_discarded=5 routebook_dropped=0\n'
               b'routebook_power=READY power_upload=ENABLED POWER_PENDING=0 POWER_REJECTED=0 power_http=200 power_wait_ms=0 power_accepted=3 power_duplicate=0 power_terminal=0\n'
               b'queue_home_waiting=true device_id=PRIVATE_IDENTIFIER\n'
               b'routebook=LEGACY device_id=PRIVATE_IDENTIFIER\nActivityRecord raw system payload\n')
        text = tool.service_dump(raw)
        self.assertIn('departure_reason=ACCURACY', text)
        self.assertIn('LE_25M', text)
        self.assertIn('motion_baseline=REQUIRED', text)
        self.assertIn('gps_duplicate=2', text)
        self.assertIn('routebook_reason=TOO_SOON', text)
        self.assertIn('power_accepted=3', text)
        self.assertNotIn('PRIVATE_IDENTIFIER', text)
        self.assertNotIn('ActivityRecord', text)

    def test_collection_is_private_and_read_only(self):
        calls = []
        def fake_adb(serial, *args):
            calls.append(args)
            if args == ('get-state',): return 0, b'device\n'
            # Real adb exec-out reported a missing cat input as status 0 and stdout.
            if args[0] == 'exec-out':
                return 0, b'cat: secret location or missing file\n'
            if args[:4] == ('shell', '-T', 'run-as', tool.PACKAGE):
                if args[4] == 'cat' and args[-1].endswith('/diagnostics.txt'):
                    return 0, (b'42 17 PROCESS=1 ID=2 DEPARTURE_CANDIDATE CYCLE=3\n'
                               b'43 18 PROCESS=1 ID=2 GATE_ERROR_PHONE_BUSY\n')
                return 1, b''
            return 0, b'departure_reason=CONSUMED candidate_elapsed_ms=3000\n'
        previous = tool.adb
        tool.adb = fake_adb
        try:
            with tempfile.TemporaryDirectory() as temporary:
                private = Path(temporary)
                private.chmod(0o700)
                destination = tool.collect('SYNTHETIC', private / 'capture')
                self.assertEqual(stat.S_IMODE(destination.stat().st_mode), 0o700)
                for file in destination.iterdir():
                    self.assertEqual(stat.S_IMODE(file.stat().st_mode), 0o600)
                report = (destination / 'summary.txt').read_text()
                self.assertIn('GATE_ERROR_PHONE_BUSY', report)
                self.assertIn('PROCESS=1 ID=2', report)
                self.assertIn('ELAPSED=17', report)
                with self.assertRaises(ValueError): tool.private_destination(destination)
                private.chmod(0o755)
                with self.assertRaises(ValueError): tool.private_destination(private / 'unsafe')
            self.assertEqual(len(calls), 1 + 2 * len(tool.JOURNAL_NAMES))
            self.assertTrue(all(c[0] in ('get-state', 'shell') for c in calls))
            self.assertEqual(calls[-1][:4], ('shell','dumpsys','activity','service'))
        finally:
            tool.adb = previous

    def test_existing_unreadable_or_corrupt_segment_is_not_ignored(self):
        for return_code, data, failure in (
            (1, b'', 'APP_JOURNAL_READ_FAILED'),
            (0, b'not journal data', 'JOURNAL_CATEGORY_FORMAT_REJECTED'),
        ):
            def fake_adb(serial, *args):
                if args == ('get-state',):
                    return 0, b'device\n'
                if args[4] == 'cat':
                    return return_code, data
                return 0, b''

            previous = tool.adb
            tool.adb = fake_adb
            try:
                with tempfile.TemporaryDirectory() as temporary:
                    destination = Path(temporary) / 'capture'
                    with self.assertRaisesRegex(ValueError, failure):
                        tool.collect('SYNTHETIC', destination)
                    self.assertFalse(destination.exists())
            finally:
                tool.adb = previous

    def test_collection_keeps_journal_when_monitor_dump_times_out(self):
        def fake_adb(serial, *args):
            if args == ('get-state',):
                return 0, b'device\n'
            if args[1] == 'dumpsys':
                raise tool.subprocess.TimeoutExpired('synthetic dumpsys', 15)
            if args[4] == 'cat' and args[-1] == 'no_backup/diagnostics.txt':
                return 0, gps(15, 1, 'NO_CALLBACK', 15_000).encode()
            return 1, b''

        previous = tool.adb
        tool.adb = fake_adb
        try:
            with tempfile.TemporaryDirectory() as temporary:
                captured = tool.collect('SYNTHETIC', Path(temporary) / 'capture', WINDOW_START, WINDOW_END)
                summary = (captured / 'summary.txt').read_text()
                self.assertIn('MONITOR_DUMP_UNAVAILABLE', summary)
                self.assertIn('Watchdog observed no GPS callback', summary)
        finally:
            tool.adb = previous

    def test_window_includes_old_startup_and_target_state_without_attempt(self):
        def row(second, sequence, category):
            start = tool.datetime.fromisoformat('2026-11-18T14:00:00+01:00')
            wall = (start - tool.EPOCH) // tool.timedelta(milliseconds=1) + 1000 * second
            return f'{wall} {1000 * second} PROCESS=1 SCHEMA=2 SEQ={sequence} {category}\n'

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            startup = row(0, 1, 'MONITOR_CREATE STARTUP=7')
            snapshot = row(120, 3, 'MEDIA_TARGET_SNAPSHOT STAGE=FIRST_MOVING STARTUP=7 CYCLE=-1 REASON=NONE '
                           'Y_ACCESS=GRANTED Y_LISTENER=BOUND Y_FGS=ABSENT S_ACCESS=GRANTED S_SESSIONS=ZERO '
                           'S_SCOPE=NONE S_PLAYBACK=NONE')
            fixes = row(121, 4, 'MOTION_FIX STARTUP=7 CYCLE=-1 ACCURACY=LE_15M AGE=LE_3S SPEED=MOVING '
                        'GAP=LE_3S YANOSIK=1 SPOTIFY=1')
            state = row(122, 5, 'MOTION_STATE STARTUP=7 CYCLE=-1 GENERATION=0 STATE=WAITING '
                        'REASON=STATIONARY_BASELINE SOURCE=STARTUP BASELINE=REQUIRED BLOCKER=NONE ELAPSED_MS=0')
            foreground = row(123, 6, 'MEDIA_RECENT_FOREGROUND STAGE=FIRST_MOVING STARTUP=7 CYCLE=-1 '
                             'STATE=GRANTED ACTION=OTHER AGE=LE_1S REASON=NONE')
            (root / 'diagnostics.previous.txt').write_text(snapshot + startup)
            (root / 'diagnostics.txt').write_text(
                fixes + state + foreground + snapshot +
                ''.join(row(3600 + index, 7 + index, 'GPS_SUMMARY FIXES=30 POOR=0') for index in range(90))
            )
            report = tool.summarize(root, '2026-11-18T13:00:00Z', '2026-11-18T15:00:00+01:00')
            self.assertIn('Observed records: 5; correlated attempts: 0', report)
            self.assertIn('YANOSIK=0 SPOTIFY=0', report)
            self.assertIn('Unseen sequence values between observed rows in the window: 1', report)
            self.assertIn('Identical schema-2 rows skipped across the capture: 1', report)
            self.assertIn('2026-11-18T14:00:00.000+01:00 ELAPSED=0 PROCESS=1', report)
            self.assertLess(report.index('MONITOR_CREATE'), report.index('MEDIA_TARGET_SNAPSHOT'))
            self.assertIn('MOTION_STATE', report)
            self.assertIn('MEDIA_RECENT_FOREGROUND', report)
            self.assertNotIn('GPS_SUMMARY', report)

    def test_window_validation_and_cleaning_feedback_blocker(self):
        for start, end in (
            ('2026-11-18T14:00:00', None),
            ('2026-11-18T14:00:00+01:00', '2026-11-18T13:00:00Z'),
        ):
            with self.assertRaises(ValueError):
                tool.window_bounds(start, end)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'diagnostics.txt').write_text(
                '42 17 PROCESS=1 SCHEMA=2 SEQ=1 ROUTEBOOK_UPLOAD_ACK STATE=ACCEPTED COUNT=3\n'
                '43 18 PROCESS=1 SCHEMA=2 SEQ=2 QUEUE_STATE ID=7 ACTION=SPOTIFY STATE=WAITING '
                'REASON=PRIORITY BLOCKER_ID=2 BLOCKER_ACTION=CLEANING BLOCKS=320\n'
                '44 19 PROCESS=1 SCHEMA=2 SEQ=3 ROUTEBOOK_UPLOAD_RETRY STATE=HTTP_429 WAIT_MS=1000\n'
            )
            report = tool.summarize(root)
            self.assertIn('ROUTEBOOK_UPLOAD_ACK STATE=ACCEPTED COUNT=3', report)
            self.assertIn('ROUTEBOOK_UPLOAD_RETRY STATE=HTTP_429 WAIT_MS=1000', report)
            self.assertIn('SPOTIFY: CLEANING_FEEDBACK; blocker=CLEANING', report)
            self.assertIn('SPOTIFY: PRIORITY; blocker=CLEANING', report)

    def test_startup_witness_callback_rejections_and_independent_archive_commit(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            started = gps(0, 1, 'STARTED', 0, ' REASON=SERVICE_START')
            witness = gps(15, 5, 'NO_CALLBACK', 15_000, ' CYCLE=0')
            (directory / 'diagnostics.previous.txt.7').write_text(started +
                gps(0, 2, 'SUBSCRIBED', 0, ' STATE=ACTIVE') +
                gps(0, 3, 'CYCLE_READ', 0, ' STATE=BASELINE_ZERO CYCLE=0') + witness)
            (directory / 'diagnostics.txt').write_text(witness +
                gps(15, 6, 'RESUBSCRIBE', 15_000, ' CYCLE=0') +
                gps(16, 7, 'FIRST_CALLBACK', 16_000, ' STATE=RECEIVED CYCLE=0') +
                gps(16, 8, 'MOTION_FIX_REJECTED', 16_010, ' REASON=ACCURACY_TOO_LOW CYCLE=0') +
                gps(17, 9, 'MOTION_FIX_REJECTED', 17_010, ' REASON=STALE_FIX CYCLE=0') +
                gps(18, 10, 'FIRST_USABLE_MOTION_FIX', 18_010, ' STATE=CANDIDATE CYCLE=0') +
                gps(28, 11, 'MOTION_CONFIRMED', 28_010, ' CYCLE=0') +
                gps(29, 12, 'STARTED', 0, ' REASON=VERIFIED_WAKE CYCLE=1', observation=1) +
                gps(30, 13, 'FIRST_ARCHIVE_COMMIT', 30_000, ' CYCLE=0'))
            report = tool.summarize(directory, WINDOW_START, WINDOW_END)
            startup, wake = report.split('PROCESS=1 STARTUP=7 OBSERVATION=1', 1)
            self.assertIn('MONITOR_DUMP_UNAVAILABLE', startup)
            self.assertIn('Unseen sequence values between observed rows in the window: 1', startup)
            self.assertIn('Identical schema-2 rows skipped across the capture: 1', startup)
            self.assertIn('(+15000 ms) Watchdog observed no GPS callback through this check', startup)
            self.assertIn('(+16000 ms) First GPS callback', startup)
            self.assertIn('REASON=ACCURACY_TOO_LOW', startup)
            self.assertIn('REASON=STALE_FIX', startup)
            self.assertIn('First usable motion fix', startup)
            self.assertIn('Motion confirmed', startup)
            self.assertIn('First Routebook SQLite commit [STAGE=FIRST_ARCHIVE_COMMIT CYCLE=0]', startup)
            self.assertNotIn('First Routebook SQLite commit', wake)

    def test_trimmed_and_legacy_windows_preserve_unknowns(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'diagnostics.previous.txt').write_text(
                gps(-20, 1, 'STARTED', 0, ' REASON=SERVICE_START') + gps(-5, 2, 'NO_CALLBACK', 15_000))
            (directory / 'diagnostics.txt').write_text(
                gps(3, 3, 'MOTION_FIX_REJECTED', 23_000, ' REASON=EMPTY_CALLBACK') +
                gps(5, 4, 'SUBSCRIBE_DENIED', 0, startup=8, process=2) +
                gps(120, 5, 'FIRST_USABLE_MOTION_FIX', 140_000, ' STATE=CANDIDATE') +
                f'{WINDOW_WALL} 100000 GPS_SUMMARY FIXES=3 POOR=0\n')
            (directory / 'monitor.txt').write_text('monitor_running=false registered=false\n')
            report = tool.summarize(directory, WINDOW_START, WINDOW_END)
            self.assertIn('monitor_running=false', report)
            self.assertIn('GPS callback rejected by motion checks [STAGE=MOTION_FIX_REJECTED REASON=EMPTY_CALLBACK]', report)
            self.assertIn('No retained watchdog record establishes a callback-free period', report)
            self.assertIn('PROCESS=2 STARTUP=8 OBSERVATION=0', report)
            self.assertIn('GPS subscription denied', report)
            self.assertNotIn('Watchdog observed no GPS callback', report)
            self.assertNotIn('First usable motion fix', report)
            self.assertIn('PROCESS=LEGACY GPS_SUMMARY FIXES=3 POOR=0', report)

            (directory / 'diagnostics.txt').write_text(f'{WINDOW_WALL} 100000 EVENT DEPARTURE_STARTED\n')
            old = tool.summarize(directory, WINDOW_START, WINDOW_END)
            self.assertIn('Startup callback timing is unknown', old)
            self.assertIn('PROCESS=LEGACY EVENT DEPARTURE_STARTED', old)
            self.assertNotIn('Watchdog observed no GPS callback', old)

    def test_window_reports_post_motion_blockers_and_matches_only_explicit_attempt_context(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'diagnostics.txt').write_text(
                row(-2, 1, 'MEDIA_ENQUEUED STARTUP=7 OBSERVATION=0 CYCLE=3 STAGE=MOTION_CONFIRMED ACTION=SPOTIFY ID=9') +
                row(-1, 2, 'QUEUE_STATE ACTION=SPOTIFY STATE=WAITING REASON=PRIORITY BLOCKS=96 ID=9') +
                row(0, 3, 'MEDIA_ENQUEUE_SUPPRESSED STARTUP=7 OBSERVATION=0 CYCLE=3 STAGE=MOTION_CONFIRMED REASON=GATE_STALE') +
                row(1, 4, 'MOTION_STATE STARTUP=7 OBSERVATION=0 CYCLE=3 STATE=CANDIDATE REASON=ACCURACY_OVERLAP BLOCKER=NONE') +
                row(2, 5, 'QUEUE_STATE ACTION=SPOTIFY STATE=EXPIRED REASON=EXPIRED ID=9') +
                row(2, 6, 'ACTION_SPOTIFY_SKIPPED_EXPIRED ID=9') +
                row(3, 1, 'QUEUE_STATE ACTION=SPOTIFY STATE=EXPIRED REASON=EXPIRED ID=9', process=2) +
                row(4, 7, 'DEPARTURE_CONTINUATION_REJECTED STARTUP=7 OBSERVATION=1 CYCLE=4 REASON=CYCLE_CHANGED ID=10') +
                row(5, 8, 'MEDIA_GATE_RESULT STARTUP=7 OBSERVATION=1 CYCLE=4 REASON=AREA_CHANGED ID=10') +
                row(6, 9, 'MEDIA_ENQUEUE_SUPPRESSED STARTUP=8 OBSERVATION=0 CYCLE=-1 STAGE=MOTION_CONFIRMED REASON=SESSION_UNAVAILABLE') +
                row(7, 10, 'QUEUE_STATE ACTION=SPOTIFY STATE=EXPIRED REASON=EXPIRED STARTUP=9 OBSERVATION=0 CYCLE=5 ID=9'))
            decisions = report_decisions(tool.summarize(directory, WINDOW_START, WINDOW_END))
            self.assertIn('Media enqueue blocked after confirmed motion (GATE_STALE) [PROCESS=1 STARTUP=7 OBSERVATION=0 CYCLE=3]', decisions)
            self.assertIn('Media enqueue blocked after confirmed motion (SESSION_UNAVAILABLE)', decisions)
            self.assertIn('Motion detector CANDIDATE (ACCURACY_OVERLAP, blocker=NONE)', decisions)
            self.assertIn('SPOTIFY queue expired (EXPIRED); earlier gate-precondition wait observed', decisions)
            self.assertIn('(wait recorded before window) [PROCESS=1 STARTUP=7 OBSERVATION=0 CYCLE=3 ID=9 (enqueue recorded before window)]', decisions)
            self.assertIn('SPOTIFY queue EXPIRED (EXPIRED) [PROCESS=2 ID=9]', decisions)
            self.assertIn('SPOTIFY queue EXPIRED (EXPIRED) [PROCESS=1 STARTUP=9 OBSERVATION=0 CYCLE=5 ID=9]', decisions)
            self.assertIn('ACTION_SPOTIFY_SKIPPED_EXPIRED', decisions)
            self.assertIn('DEPARTURE_CONTINUATION_REJECTED (CYCLE_CHANGED)', decisions)
            self.assertIn('MEDIA_GATE_RESULT (AREA_CHANGED)', decisions)

    def test_two_spotify_launch_requests_keep_distinct_context_without_implying_playback(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            launch = row(95, 5, 'SPOTIFY_LAUNCH_REQUESTED ID=12')
            (directory / 'diagnostics.previous.txt').write_text(launch)
            (directory / 'diagnostics.txt').write_text(
                row(1, 1, 'QUEUE_STATE ACTION=SPOTIFY STATE=WAITING REASON=PRIORITY ID=11') +
                row(1, 2, 'MEDIA_ENQUEUED STARTUP=7 OBSERVATION=0 CYCLE=3 STAGE=MOTION_CONFIRMED ACTION=SPOTIFY ID=11') +
                row(5, 3, 'SPOTIFY_LAUNCH_REQUESTED ID=11') +
                row(90, 4, 'MEDIA_ENQUEUED STARTUP=7 OBSERVATION=1 CYCLE=4 STAGE=DEPARTURE_CONTINUATION ACTION=SPOTIFY ID=12') +
                launch + row(95, 6, 'SPOTIFY_PLAYING ID=12') +
                row(120, 7, 'SPOTIFY_LAUNCH_REQUESTED ID=13'))
            decisions = report_decisions(tool.summarize(directory, WINDOW_START, WINDOW_END))
            self.assertIn('SPOTIFY queue WAITING (PRIORITY) [PROCESS=1 STARTUP=7 OBSERVATION=0 CYCLE=3 ID=11]', decisions)
            self.assertIn('SPOTIFY_LAUNCH_REQUESTED [PROCESS=1 STARTUP=7 OBSERVATION=0 CYCLE=3 ID=11]', decisions)
            self.assertIn('SPOTIFY_LAUNCH_REQUESTED [PROCESS=1 STARTUP=7 OBSERVATION=1 CYCLE=4 ID=12]', decisions)
            self.assertIn('SPOTIFY_PLAYING [PROCESS=1 STARTUP=7 OBSERVATION=1 CYCLE=4 ID=12]', decisions)
            self.assertIn('Spotify app launch requests recorded for 2 distinct attempts', decisions)
            self.assertNotIn('ID=13', decisions)

    def test_startup_and_enqueue_validate_bounds_shape_and_closed_stage_details(self):
        maximum_cycle = gps(0, 1, 'STARTED', 0, f' REASON=VERIFIED_WAKE CYCLE={999_999_999}', observation=1)
        unknown_cycle = gps(0, 2, 'CYCLE_READ', 10, ' STATE=UNAVAILABLE CYCLE=-1')
        self.assertEqual(tool.journal((maximum_cycle + unknown_cycle).encode()), maximum_cycle + unknown_cycle)
        valid = gps(1, 2, 'NO_CALLBACK', 8000, ' CYCLE=2')
        enqueue = row(1, 3, 'MEDIA_ENQUEUED STARTUP=7 OBSERVATION=0 CYCLE=-1 STAGE=MOTION_CONFIRMED ACTION=SPOTIFY ID=9')
        standalone_enqueue = enqueue.replace('STARTUP=7 OBSERVATION=0', 'STARTUP=-1 OBSERVATION=-1')
        self.assertEqual(tool.journal((valid + enqueue + standalone_enqueue).encode()), valid + enqueue + standalone_enqueue)
        for malformed in (
            valid.replace(' STARTUP=7', ''), valid.replace('SEQ=2', 'SEQ=0'), valid.replace('SCHEMA=2', 'SCHEMA=1'),
            valid.replace('OBSERVATION=0', 'OBSERVATION=-1'), valid.replace('OBSERVATION=0', 'OBSERVATION=01'),
            valid.replace('ELAPSED_MS=8000', 'ELAPSED_MS=-1'), valid.replace('ELAPSED_MS=8000', 'ELAPSED_MS=1000000000000000000'),
            valid.replace('CYCLE=2', f'CYCLE={1_000_000_000}'), valid.replace('CYCLE=2', 'CYCLE=-1'),
            valid.replace('STAGE=NO_CALLBACK', 'STAGE=PRIVATE_WORD'), valid.rstrip('\n'),
            valid.rstrip('\n') + ' COUNT=0\n', valid.rstrip('\n') + ' OBSERVATION=0\n',
            valid.rstrip('\n') + ' TOKEN=PRIVATE_WORD\n',
            gps(1, 3, 'MOTION_FIX_REJECTED', 1, ' REASON=ACCURACY_OVERLAP'),
            gps(1, 3, 'FIRST_CALLBACK', 1, ' STATE=PRIVATE_WORD'),
            gps(1, 3, 'CYCLE_READ', 1, ' STATE=UNAVAILABLE'),
            gps(1, 3, 'STARTED', 1, ' REASON=SERVICE_START'),
            enqueue.replace('ACTION=SPOTIFY', 'ACTION=CLEANING'), enqueue.replace('ID=9', 'ID=0'),
        ):
            with self.subTest(malformed=malformed), self.assertRaises(ValueError):
                tool.journal(malformed.encode())

    def test_git_destination_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            directory.chmod(0o700)
            (directory / '.git').write_text('synthetic marker')
            with self.assertRaises(ValueError): tool.private_destination(directory / 'capture')


if __name__ == '__main__': unittest.main()
