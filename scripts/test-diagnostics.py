#!/usr/bin/env python3
"""Synthetic offline collector reconstruction; no ADB, real coordinates or external command."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
spec=importlib.util.spec_from_file_location('diagnostics',Path(__file__).with_name('automation-diagnostics.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class DiagnosticsChecks(unittest.TestCase):
    def test_all_segments_reconstruct_process_and_block_causes(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for index,(action,reason,blocker) in enumerate((('CLEANING','ACTIVE_OPERATION','GATE'),
                    ('YANOSIK','RESOURCE_CLEANUP','GATE'),('SPOTIFY','MENU_VISIBLE','NONE'),
                    ('SPOTIFY','YANOSIK_GRACE','YANOSIK'),('SPOTIFY','PRIORITY','CLEANING'),
                    ('SPOTIFY','GATE_PRECONDITION','GATE'),('GATE','NONE','NONE'),('CLEANING','FAILURE','NONE'))):
                row=f'{100+index} {10+index} PROCESS={1+index%2} SCHEMA=2 SEQ={index+1} QUEUE_STATE ID=1 ACTION={action} STATE=WAITING REASON={reason} BLOCKER_ID=2 BLOCKER_ACTION={blocker}\n'
                (root/module.JOURNAL_NAMES[index]).write_text(row)
            report=module.summarize(root)
            self.assertIn('Observed records: 8; correlated attempts: 2',report)
            for reason in ('ACTIVE_OPERATION','RESOURCE_CLEANUP','MENU_VISIBLE','YANOSIK_GRACE','PRIORITY','GATE_PRECONDITION','FAILURE'):
                self.assertIn(reason,report)
            self.assertIn('PROCESS=1 ID=1',report);self.assertIn('PROCESS=2 ID=1',report)
    def test_simultaneous_masks_remain_visible(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'diagnostics.txt').write_text(
                '100 1 PROCESS=1 SCHEMA=2 SEQ=1 QUEUE_STATE ID=1 ACTION=SPOTIFY STATE=WAITING REASON=MENU_VISIBLE BLOCKS=144 BLOCKER_ID=0 BLOCKER_ACTION=NONE\n'
                '101 2 PROCESS=1 SCHEMA=2 SEQ=2 QUEUE_STATE ID=2 ACTION=CLEANING STATE=WAITING REASON=RESOURCE_CLEANUP BLOCKS=65 BLOCKER_ID=3 BLOCKER_ACTION=GATE\n')
            report=module.summarize(root)
            for reason in ('MENU_VISIBLE','YANOSIK_GRACE','RESOURCE_CLEANUP','PRIORITY'):
                self.assertIn(': '+reason+';',report)
    def test_bounds_and_legacy_format(self):
        self.assertEqual(module.journal(b'42 17 PROCESS=1 ID=2 ACTION_REQUESTED_GATE\n'),'42 17 PROCESS=1 ID=2 ACTION_REQUESTED_GATE\n')
        for invalid in (b'secret payload',b'1 2 EVENT TOKEN=lowercase\n',b'1 2 PROCESS=1 SCHEMA=2 SEQ=1 EVENT TOKEN=ABC\n',b'x'*(module.LIMIT+1)):
            with self.assertRaises(ValueError):module.journal(invalid)

if __name__=='__main__':unittest.main()
