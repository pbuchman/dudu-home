#!/usr/bin/env python3
"""Only invented artifacts in disposable directories are deleted by these tests."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from radio_update import sha,Blocked

def load(name,file):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(file));module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
cleaner=load('cleaner','cleanup-update-artifacts.py');fixtures=load('fixtures','test-radio-update.py')

def backup(root):
    folder=root/'before-update-synthetic';folder.mkdir()
    for name in ('installed.apk','app-data.tar'):(folder/name).write_bytes(b'synthetic')
    (folder/'sha256.json').write_text(json.dumps({name:sha(folder/name) for name in ('installed.apk','app-data.tar')}))
    return folder

class CleanupTests(unittest.TestCase):
    def test_exact_backup_and_proven_old_map_removed_unrelated_retained(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);old=root/'dated-map.sqlite';manifest=fixtures.map_fixture(old)
            old.with_suffix('.manifest.json').write_text(json.dumps(manifest))
            canonical=root/'places.sqlite';fixtures.map_fixture(canonical)
            backup(root);(root/'arbitrary.apk').write_bytes(b'keep');(root/'unrelated.sqlite').write_bytes(b'keep')
            files,dirs=cleaner.cleanup(root)
            self.assertEqual((files,dirs),(5,1));self.assertEqual(set(p.name for p in root.iterdir()),{'places.sqlite','arbitrary.apk','unrelated.sqlite'})
    def test_nonempty_canonical_sidecar_blocks_all_deletion(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);folder=backup(root);journal=root/'places.sqlite-journal';journal.write_bytes(b'pending')
            with self.assertRaises(Blocked):cleaner.cleanup(root)
            self.assertTrue((folder/'installed.apk').exists());self.assertEqual(journal.read_bytes(),b'pending')
    def test_recognized_success_receipt_may_describe_new_apk(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);folder=backup(root)
            receipt=dict(status='installed',apk_sha256='a'*64,map_sha256='b'*64,map_schema=3,
                         configuration_preserved=True,monitor_registered=True,road_test='not performed',ui_smoke='passed')
            (folder/'installation-result.json').write_text(json.dumps(receipt))
            self.assertEqual(cleaner.cleanup(root),(4,1))
    def test_unknown_backup_contents_reject_whole_cleanup_before_any_deletion(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);folder=backup(root);(folder/'unrelated.txt').touch();sidecar=root/'places.manifest.json';sidecar.touch()
            with self.assertRaises(Blocked):cleaner.cleanup(root)
            self.assertTrue(sidecar.exists());self.assertTrue((folder/'installed.apk').exists())
    def test_symlinks_hash_mismatch_and_escaping_manifest_refused(self):
        for fault in ('symlink','hash','escape'):
            with self.subTest(fault=fault),tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary);folder=backup(root)
                if fault=='symlink':
                    victim=root/'unrelated';victim.write_bytes(b'keep');(folder/'installed.apk').unlink();(folder/'installed.apk').symlink_to(victim)
                elif fault=='hash':(folder/'installed.apk').write_bytes(b'changed')
                else:
                    old=root/'old.sqlite';manifest=fixtures.map_fixture(old);manifest['file']='../old.sqlite';old.with_suffix('.manifest.json').write_text(json.dumps(manifest))
                with self.assertRaises(Blocked):cleaner.cleanup(root)
                self.assertTrue((folder/'app-data.tar').exists())
    def test_bound_heading_audit_required_before_deleting_old_map(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);old=root/'dated.sqlite';fixtures.map_fixture(old,3)
            import sqlite3
            with sqlite3.connect(old) as db:db.execute("INSERT INTO metadata VALUES('short_heading_audit_sha256',?)",('a'*64,))
            from radio_update import map_manifest
            old.with_suffix('.manifest.json').write_text(json.dumps(map_manifest(old)))
            with self.assertRaises(OSError):cleaner.cleanup(root)
            self.assertTrue(old.exists())
if __name__=='__main__':unittest.main()
