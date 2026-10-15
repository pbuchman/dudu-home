#!/usr/bin/env python3
import json
import importlib.util
import hashlib
from pathlib import Path
import shlex
import sqlite3
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import radio_update as radio
spec=importlib.util.spec_from_file_location('index',Path(__file__).with_name('build-place-index.py'))
index=importlib.util.module_from_spec(spec);spec.loader.exec_module(index)

def map_fixture(path,schema=2):
    sql=index.SCHEMA
    if schema==1:
        sql=sql.replace(',kind TEXT NOT NULL,provenance TEXT NOT NULL','')
        sql=sql.replace(',admin_level TEXT NOT NULL,feature_id TEXT NOT NULL','')
    with sqlite3.connect(path) as db:
        db.executescript(sql+index.INDEXES)
        db.executemany('INSERT INTO metadata VALUES(?,?)',[('schema',str(schema)),('license','ODbL-1.0')])
        db.execute("INSERT INTO roads VALUES(1,'Synthetic Road','','',0,0,0,0.001)")
        db.execute("INSERT INTO places VALUES('Synthetic Village',0,0,0,0"+(" ,'village','osm_place_node')" if schema==2 else ')'))
        db.execute('INSERT INTO road_cells VALUES(0,0,1)')
    metadata,counts=index.validate(path)
    manifest={'schema':schema,'file':path.name,'bytes':path.stat().st_size,'sha256':radio.sha(path),'metadata':metadata,'counts':counts}
    path.with_suffix('.manifest.json').write_text(json.dumps(manifest))
    return manifest

class PreflightTests(unittest.TestCase):
    def test_session_smoke_is_passive_for_active_and_paused(self):
        for state in ('Start','Pauza'):
            with self.subTest(state=state):
                def node(adb,name,seconds=15):
                    return {'resource-id':radio.PACKAGE+':id/'+name,'text':{'trip_primary':state,'trip_reset':'Reset','trip_time':'100:01:01'}.get(name,'')}
                with patch.object(radio,'ui_resource',side_effect=node),patch.object(radio,'ui_tree',return_value=radio.ET.fromstring('<hierarchy><node scrollable="true" bounds="[0,0][1000,600]"/></hierarchy>')),patch.object(radio,'tap_node') as tap,patch.object(radio,'run') as run:
                    result=radio.smoke_trip(['adb'])
                    self.assertIn('preserved',result)
                    self.assertEqual([call.args[1]['resource-id'].split('/')[-1] for call in tap.call_args_list],['where_am_i_button'])
                    self.assertEqual([call.args[1] for call in run.call_args_list],[['shell','input','swipe','500','450','500','150','300'],['shell','input','keyevent','KEYCODE_BACK']])
    def test_session_smoke_rejects_old_controls_without_modifying_session(self):
        with patch.object(radio,'ui_resource',return_value={'text':'Wznów'}),patch.object(radio,'ui_tree',return_value=radio.ET.fromstring('<hierarchy/>')),patch.object(radio,'tap_node') as tap:
            with self.assertRaises(radio.Blocked):radio.smoke_trip(['adb'])
            self.assertEqual(tap.call_count,1)
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
            path=Path(tmp)/'map.sqlite';manifest=map_fixture(path)
            with patch.object(radio,'run',side_effect=[b'',b'bad no_backup/places.sqlite.new',b'']) as run:
                with self.assertRaises(radio.Blocked):radio.install_map(['adb'],path,manifest)
                self.assertEqual(run.call_count,3)
                self.assertFalse(any('mv ' in str(call) for call in run.call_args_list))
    def test_legacy_explicit_recovery_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'map.sqlite';manifest=map_fixture(path,1)
            with self.assertRaises(radio.Blocked):radio.map_manifest(path)
            self.assertEqual(radio.map_manifest(path,allow_legacy=True),manifest)
    def test_schema2_integrity_checked_before_upload(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'map.sqlite';manifest=map_fixture(path)
            self.assertEqual(radio.map_manifest(path),manifest)
            with sqlite3.connect(path) as db:db.execute('DELETE FROM road_cells')
            manifest['sha256']=radio.sha(path)
            path.with_suffix('.manifest.json').write_text(json.dumps(manifest))
            with patch.object(radio,'run') as run:
                with self.assertRaises(ValueError):radio.install_map(['adb'],path,manifest)
                run.assert_not_called()
    def test_atomic_replacement_and_manifest_failure(self):
        for failure in (None,'manifest_upload','manifest_hash','rename'):
            with self.subTest(failure=failure),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);path=root/'map.sqlite';manifest=map_fixture(path)
                private=root/'no_backup';private.mkdir()
                (private/'places.sqlite').write_bytes(b'old map')
                (private/'places.manifest.json').write_bytes(b'old manifest')
                def fake_run(adb,args,**kwargs):
                    if len(args)==2:
                        shell=shlex.split(args[1]);command=shell[-1]
                        if 'cat >' in command:
                            name=command.split('cat >',1)[1].strip()
                            if failure=='manifest_upload' and name.endswith('.json.new'):raise radio.Blocked('Synthetic upload failure')
                            data=kwargs.get('input')
                            if data is None:data=kwargs['stdin'].read()
                            (root/name).write_bytes(data);return b''
                        if failure=='rename':command=command.replace('mv no_backup/places.sqlite.new no_backup/places.sqlite','false')
                        result=subprocess.run(['sh','-c',command],cwd=root,capture_output=True)
                        if result.returncode:raise radio.Blocked('Synthetic rename failure')
                        return result.stdout
                    if 'sha256sum' in args:
                        name=args[-1];checksum=radio.sha(root/name)
                        if failure=='manifest_hash' and name.endswith('.json.new'):checksum='bad'
                        return (checksum+' '+name).encode()
                    if 'rm' in args:
                        for name in args[args.index('-f')+1:]:(root/name).unlink(missing_ok=True)
                        return b''
                    raise AssertionError('Unexpected fake ADB operation')
                with patch.object(radio,'run',side_effect=fake_run):
                    if failure:
                        with self.assertRaises(radio.Blocked):radio.install_map(['adb'],path,manifest)
                        self.assertEqual((private/'places.sqlite').read_bytes(),b'old map')
                        self.assertEqual((private/'places.manifest.json').read_bytes(),b'old manifest')
                    else:
                        radio.install_map(['adb'],path,manifest)
                        self.assertEqual(radio.sha(private/'places.sqlite'),manifest['sha256'])
                        self.assertEqual(json.loads((private/'places.manifest.json').read_text()),manifest)
if __name__=='__main__':unittest.main()
