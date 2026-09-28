#!/usr/bin/env python3
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import radio_update as radio

class PreflightTests(unittest.TestCase):
    def test_idle_requires_all_evidence(self):
        ui='<hierarchy><node resource-id="com.pbuchman.duduhome:id/menu_content"/></hierarchy>'
        self.assertTrue(radio.idle_evidence('Actual mode: MODE_NORMAL','mResumedActivity: example/.Main',ui,'action_busy=false'))
        for audio,activities,xml,service in [
            ('','',ui,''),('Actual mode: MODE_IN_CALL','',ui,''),
            ('Actual mode: MODE_NORMAL','mResumedActivity: com.syu.bt/.Main',ui,''),
            ('Actual mode: MODE_NORMAL','',ui,'action_busy=true'),
            ('Actual mode: MODE_NORMAL','','invalid',''),
            ('Actual mode: MODE_NORMAL','','<hierarchy><node resource-id="com.pbuchman.duduhome:id/status_title"/></hierarchy>','')]:
            self.assertFalse(radio.idle_evidence(audio,activities,xml,service))
    def test_emulator_refused(self):
        with patch.object(radio,'run',side_effect=[b'ranchu',b'Emulator']):
            with self.assertRaises(radio.Blocked):radio.physical(['adb'])
    def test_unreachable_stops_without_actions(self):
        with patch.object(radio,'run',side_effect=[b'',b'List of devices attached\nemulator-5554\tdevice\n']):
            with self.assertRaises(radio.Blocked):radio.discover(['adb'],seconds=0)
    def test_bad_map_does_not_install(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'map.sqlite';path.write_bytes(b'bad')
            path.with_suffix('.manifest.json').write_text(json.dumps({'schema':1,'file':path.name,'bytes':3,'sha256':'bad'}))
            with self.assertRaises(radio.Blocked):radio.map_manifest(path)
    def test_no_rename_when_upload_corrupted(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'map.sqlite';path.write_bytes(b'test')
            with patch.object(radio,'run',side_effect=[b'',b'bad no_backup/places.sqlite.new']) as run:
                with self.assertRaises(radio.Blocked):radio.install_map(['adb'],path,{'sha256':radio.sha(path)})
                self.assertEqual(run.call_count,2)
if __name__=='__main__':unittest.main()
