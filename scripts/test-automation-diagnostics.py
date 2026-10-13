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


class Checks(unittest.TestCase):
    def test_category_only_input(self):
        self.assertEqual(tool.journal(b'42 17 PROCESS=1 ID=2 GATE_ERROR_PHONE_BUSY\n'),
                         '42 17 PROCESS=1 ID=2 GATE_ERROR_PHONE_BUSY\n')
        for data in (b'private lower case payload', b'42 17 SECRET/URL\n', b'x' * (tool.LIMIT + 1)):
            with self.assertRaises(ValueError):
                tool.journal(data)

    def test_dump_filters_unrelated_identifiers_and_raw_system_data(self):
        raw = b'  departure_reason=ACCURACY candidate_elapsed_ms=0\n  gps_accuracy=LE_25M gps_age=LE_3S gps_speed=MOVING\nroutebook=X device_id=PRIVATE_IDENTIFIER\nActivityRecord raw system payload\n'
        text = tool.service_dump(raw)
        self.assertIn('departure_reason=ACCURACY', text)
        self.assertIn('LE_25M', text)
        self.assertNotIn('PRIVATE_IDENTIFIER', text)
        self.assertNotIn('ActivityRecord', text)

    def test_collection_is_private_and_read_only(self):
        calls = []
        def fake_adb(serial, *args):
            calls.append(args)
            if args == ('get-state',): return 0, b'device\n'
            if args[0] == 'exec-out':
                return (0, b'42 17 PROCESS=1 ID=2 DEPARTURE_CANDIDATE CYCLE=3\n43 18 PROCESS=1 ID=2 GATE_ERROR_PHONE_BUSY\n') if args[-1].endswith('/diagnostics.txt') else (1, b'')
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
            self.assertEqual(len(calls), 4)
            self.assertTrue(all(c[0] in ('get-state', 'exec-out', 'shell') for c in calls))
            self.assertEqual(calls[-1][:4], ('shell','dumpsys','activity','service'))
        finally:
            tool.adb = previous

    def test_git_destination_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            directory.chmod(0o700)
            (directory / '.git').write_text('synthetic marker')
            with self.assertRaises(ValueError): tool.private_destination(directory / 'capture')


if __name__ == '__main__': unittest.main()
