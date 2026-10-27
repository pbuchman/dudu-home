#!/usr/bin/env python3
"""Synthetic ADB/filesystem update checks; no device or private input is used."""
import json
import copy
import importlib.util
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
        db.execute("INSERT INTO places VALUES('Synthetic Village',0,0,0,0"+(" ,'village','osm_place_node')" if schema>=2 else ')'))
        if schema==3:
            source_hash='1'*64
            db.executemany('INSERT INTO metadata VALUES(?,?)',dict(source_sha256=source_hash,**index.heading_metadata(source_hash)).items())
        db.execute('INSERT INTO road_cells VALUES(0,0,1)')
    return radio.map_manifest(path,allow_legacy=True)

class Device:
    def __init__(self,root):
        self.root=root;self.transfers=[];self.fail=False
        private=root/'no_backup';private.mkdir()
        for name in ('maintenance','install-verification'):(private/name).touch()
    def run(self,adb,args,**kwargs):
        if args[0]!='shell':raise AssertionError(args)
        if args[1:]==['ps','-A','-o','PID,NAME']:return b'PID NAME\n1 init\n'
        command=shlex.split(args[1]) if len(args)==2 else args[1:]
        if command[:2]!=['run-as',radio.PACKAGE]:raise AssertionError(args)
        command=command[2:]
        if 'cat > no_backup/places.sqlite' in ' '.join(command):
            self.transfers.append('places.sqlite')
            data=kwargs['stdin'].read()
            if self.fail:
                (self.root/'no_backup/places.sqlite').write_bytes(data[:10])
                raise radio.Blocked('Synthetic interruption')
            kwargs={'input':data}
        else:kwargs={}
        result=subprocess.run(command,cwd=self.root,capture_output=True,**kwargs)
        if result.returncode:raise radio.Blocked('Synthetic remote operation rejected')
        return result.stdout

class UpdateTests(unittest.TestCase):
    def test_map_is_self_contained_and_legacy_explicit_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'places.sqlite';expected=map_fixture(path,3)
            self.assertEqual(radio.map_manifest(path),expected)
            self.assertEqual(list(Path(tmp).iterdir()),[path])
            with sqlite3.connect(path) as db:db.execute("UPDATE metadata SET value='1' WHERE key='schema'")
            with self.assertRaises(radio.Blocked):radio.map_manifest(path)
            self.assertEqual(radio.map_manifest(path,allow_legacy=True)['schema'],1)
    def test_bad_missing_and_symlink_local_map_never_touch_device(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'places.sqlite'
            for content in (None,b'corrupt'):
                if content:path.write_bytes(content)
                with patch.object(radio,'run') as run:
                    with self.assertRaises((radio.Blocked,OSError)):radio.install_map(['adb'],path,{})
                    run.assert_not_called()
            target=Path(tmp)/'other';target.write_bytes(b'safe');path.unlink();path.symlink_to(target)
            with self.assertRaises(radio.Blocked):radio.map_manifest(path)
            self.assertEqual(target.read_bytes(),b'safe')
    def test_active_local_sqlite_sidecars_are_rejected_before_device_access(self):
        for suffix in ('-wal','-journal','-shm'):
            with self.subTest(suffix=suffix),tempfile.TemporaryDirectory() as tmp:
                path=Path(tmp)/'places.sqlite';expected=map_fixture(path)
                sidecar=path.with_name(path.name+suffix);sidecar.write_bytes(b'pending bytes')
                with patch.object(radio,'run') as run:
                    with self.assertRaises(radio.Blocked):radio.install_map(['adb'],path,expected)
                    run.assert_not_called()
    def test_local_sqlite_sidecar_symlinks_are_refused_before_open(self):
        for suffix in ('-wal','-journal','-shm'):
            with self.subTest(suffix=suffix),tempfile.TemporaryDirectory() as tmp:
                path=Path(tmp)/'places.sqlite';expected=map_fixture(path)
                victim=Path(tmp)/'unrelated';victim.write_bytes(b'keep')
                path.with_name(path.name+suffix).symlink_to(victim)
                with patch.object(radio,'run') as run:
                    with self.assertRaises(radio.Blocked):radio.install_map(['adb'],path,expected)
                    run.assert_not_called()
                self.assertEqual(victim.read_bytes(),b'keep')
    def test_actual_integrity_and_policy_checked_before_upload(self):
        for sql in ('DELETE FROM road_cells',"UPDATE metadata SET value='wrong' WHERE key='short_heading_policy'"):
            with self.subTest(sql=sql),tempfile.TemporaryDirectory() as tmp:
                path=Path(tmp)/'places.sqlite';expected=map_fixture(path,3)
                with sqlite3.connect(path) as db:db.execute(sql)
                with patch.object(radio,'run') as run:
                    with self.assertRaises(radio.Blocked):radio.install_map(['adb'],path,expected)
                    run.assert_not_called()
    def test_same_hash_zero_transfer_changed_missing_corrupt_fail_before_write(self):
        for state in ('same','changed','missing','corrupt'):
            with self.subTest(state=state),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);host=root/'host';host.mkdir();device=root/'device';device.mkdir()
                path=host/'places.sqlite';expected=map_fixture(path);fake=Device(device)
                remote=device/'no_backup/places.sqlite'
                if state!='missing':remote.write_bytes(path.read_bytes() if state=='same' else b'corrupt')
                old_mtime=remote.stat().st_mtime_ns if remote.exists() else None
                for name in radio.MAP_ARTIFACTS:
                    content=b'' if name in radio.SQLITE_SIDECARS else b'old'
                    (host/name).write_bytes(content);(device/'no_backup'/name).write_bytes(content)
                (host/'unrelated.sqlite').write_bytes(b'keep');(device/'no_backup/unrelated.sqlite').write_bytes(b'keep')
                before=remote.read_bytes() if remote.exists() else None
                with patch.object(radio,'run',side_effect=fake.run):
                    if state=='same':self.assertFalse(radio.install_map(['adb'],path,expected))
                    else:
                        with self.assertRaisesRegex(radio.Blocked,'enforced quiescence gate'):radio.install_map(['adb'],path,expected)
                self.assertEqual(fake.transfers,[])
                self.assertEqual(remote.read_bytes() if remote.exists() else None,before)
                if state=='same':
                    self.assertEqual(remote.stat().st_mtime_ns,old_mtime)
                    self.assertEqual(set(x.name for x in host.iterdir()),{'places.sqlite','unrelated.sqlite'})
                    self.assertEqual(set(x.name for x in (device/'no_backup').iterdir()),{'places.sqlite','unrelated.sqlite','maintenance','install-verification'})
                else:
                    self.assertTrue(all((device/'no_backup'/name).exists() for name in radio.MAP_ARTIFACTS))
    def test_nonempty_remote_sidecars_preserved_before_cleanup(self):
        for name in radio.SQLITE_SIDECARS:
            with self.subTest(name=name),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);host=root/'host';host.mkdir();device=root/'device';device.mkdir()
                path=host/'places.sqlite';manifest=map_fixture(path);fake=Device(device)
                remote=device/'no_backup/places.sqlite';remote.write_bytes(path.read_bytes())
                sidecar=device/'no_backup'/name;sidecar.write_bytes(b'pending SQLite data')
                artifact=host/'places.sqlite.previous';artifact.write_bytes(b'keep')
                with patch.object(radio,'run',side_effect=fake.run),self.assertRaises(radio.Blocked):
                    radio.install_map(['adb'],path,manifest)
                self.assertEqual(sidecar.read_bytes(),b'pending SQLite data')
                self.assertEqual(remote.read_bytes(),path.read_bytes());self.assertEqual(artifact.read_bytes(),b'keep')
                self.assertEqual(fake.transfers,[])
    def test_sidecar_appearing_after_preflight_blocks_cleanup(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);host=root/'host';host.mkdir();device=root/'device';device.mkdir()
            path=host/'places.sqlite';manifest=map_fixture(path);fake=Device(device)
            remote=device/'no_backup/places.sqlite';remote.write_bytes(path.read_bytes())
            sidecar=device/'no_backup/places.sqlite-wal';observations=0;calls=[]
            def run(adb,args,**kwargs):
                nonlocal observations
                calls.append(args)
                if args[1:]==['ps','-A','-o','PID,NAME']:
                    observations+=1
                    if observations==2:sidecar.write_bytes(b'new pending data')
                return fake.run(adb,args,**kwargs)
            with patch.object(radio,'run',side_effect=run),self.assertRaises(radio.Blocked):
                radio.install_map(['adb'],path,manifest)
            self.assertEqual(sidecar.read_bytes(),b'new pending data')
            self.assertFalse(any('rm' in call for call in calls))
            self.assertEqual(remote.read_bytes(),path.read_bytes())
    def test_remote_ambiguous_sidecar_types_are_preserved(self):
        for name in radio.SQLITE_SIDECARS:
            for kind in ('symlink','directory'):
                with self.subTest(name=name,kind=kind),tempfile.TemporaryDirectory() as tmp:
                    root=Path(tmp);device=root/'device';device.mkdir();fake=Device(device)
                    sidecar=device/'no_backup'/name;unrelated=root/'unrelated';unrelated.write_bytes(b'keep')
                    if kind=='symlink':sidecar.symlink_to(unrelated)
                    else:sidecar.mkdir()
                    with patch.object(radio,'run',side_effect=fake.run),self.assertRaises(radio.Blocked):
                        radio.remote_sqlite_sidecar_guard(['adb'])
                    self.assertTrue(sidecar.is_symlink() if kind=='symlink' else sidecar.is_dir())
                    self.assertEqual(unrelated.read_bytes(),b'keep')
    def test_nonempty_host_sidecar_appearing_before_cleanup_is_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'places.sqlite';map_fixture(path)
            artifact=path.parent/'places.sqlite.previous';artifact.write_bytes(b'keep')
            sidecar=path.parent/'places.sqlite-journal';sidecar.write_bytes(b'pending local data')
            with self.assertRaises(radio.Blocked):radio.cleanup_host_map(path)
            self.assertEqual(sidecar.read_bytes(),b'pending local data');self.assertEqual(artifact.read_bytes(),b'keep')
    def test_missing_map_retry_retains_block_without_transfer_or_launch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);host=root/'host';host.mkdir();device=root/'device';device.mkdir()
            path=host/'places.sqlite';expected=map_fixture(path);fake=Device(device)
            with patch.object(radio,'run',side_effect=fake.run):
                for _ in range(2):
                    with self.assertRaises(radio.Blocked):radio.install_map(['adb'],path,expected)
                self.assertTrue((device/'no_backup/install-verification').exists())
                self.assertFalse((device/'no_backup/places.sqlite').exists())
            self.assertEqual(fake.transfers,[])
    def test_remote_and_cleanup_symlinks_are_refused_without_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);host=root/'host';host.mkdir();device=root/'device';device.mkdir()
            path=host/'places.sqlite';expected=map_fixture(path);fake=Device(device)
            victim=root/'unrelated';victim.write_bytes(b'keep')
            (device/'no_backup/places.sqlite').symlink_to(victim)
            with patch.object(radio,'run',side_effect=fake.run):
                with self.assertRaises(radio.Blocked):radio.install_map(['adb'],path,expected)
            self.assertEqual(fake.transfers,[]);self.assertEqual(victim.read_bytes(),b'keep')
            (host/radio.MAP_ARTIFACTS[0]).symlink_to(victim)
            with self.assertRaises(radio.Blocked):radio.cleanup_host_map(path)
            self.assertEqual(victim.read_bytes(),b'keep')
    def test_compatibility_and_signing_fail_before_device_mutation(self):
        for schema,code in ((3,28),(2,22),(1,31),(4,31)):
            with self.assertRaises(radio.Blocked):radio.validate_map_apk(schema,code)
        for schema,code in ((2,23),(2,31),(3,29),(3,31)):radio.validate_map_apk(schema,code)
        with tempfile.TemporaryDirectory() as tmp:
            apk=Path(tmp)/'app.apk';apk.write_bytes(b'apk')
            metadata=f"package: name='{radio.PACKAGE}' versionCode='31'".encode()
            for signing in (b'',b'certificate invalid'):
                with patch.object(radio.subprocess,'check_output',side_effect=[metadata,signing]),patch.object(radio,'run') as run:
                    with self.assertRaises(radio.Blocked):radio.validate_apk(apk,'aapt','apksigner')
                    run.assert_not_called()
            with patch.object(radio.subprocess,'check_output',side_effect=[metadata,('Signer #1 certificate SHA-256 digest: '+'a'*64).encode()]):
                self.assertEqual(radio.validate_apk(apk,'aapt','apksigner',30,2,3),31)
    def test_failure_after_release_does_not_interrupt_active_call_or_launch(self):
        with patch.object(radio,'run') as run:radio.fail_closed(['adb'])
        self.assertEqual(run.call_count,1)
        self.assertNotIn('force-stop',str(run.call_args));self.assertNotIn('am start',str(run.call_args))
        self.assertIn('install-verification',str(run.call_args))
    def test_idle_resume_keeps_call_and_action_guards(self):
        ui='<hierarchy><node resource-id="'+radio.PACKAGE+':id/automation_status" text="Import blocked"/></hierarchy>'
        self.assertTrue(radio.idle_evidence('Actual mode: MODE_NORMAL','mResumedActivity: example/.Main',ui,'action_busy=false'))
        self.assertTrue(radio.idle_evidence('Actual mode: MODE_NORMAL','mResumedActivity: example/.Main',ui,'',False))
        self.assertFalse(radio.idle_evidence('Actual mode: MODE_NORMAL','mResumedActivity: example/.Main',ui,''))
        for audio,activities,xml,service in [
            ('','',ui,''),('Actual mode: MODE_IN_CALL','',ui,''),
            ('Actual mode: MODE_NORMAL','mResumedActivity: com.syu.bt/.Main',ui,''),
            ('Actual mode: MODE_NORMAL','',ui,'action_busy=true'),
            ('Actual mode: MODE_NORMAL','','invalid','action_busy=false'),
            ('Actual mode: MODE_NORMAL','','<hierarchy><node resource-id="'+radio.PACKAGE+':id/status_title"/></hierarchy>','')]:
            self.assertFalse(radio.idle_evidence(audio,activities,xml,service))
    def test_emulator_and_unreachable_refused(self):
        with patch.object(radio,'run',side_effect=[b'ranchu',b'Emulator']):
            with self.assertRaises(radio.Blocked):radio.physical(['adb'])
        with patch.object(radio,'run',side_effect=[b'',b'List of devices attached\nemulator-5554\tdevice\n']):
            with self.assertRaises(radio.Blocked):radio.discover(['adb'],seconds=0)
    def test_session_smoke_never_changes_controls(self):
        for state in ('Start','Pauza'):
            def node(adb,name,seconds=15):return {'resource-id':radio.PACKAGE+':id/'+name,'text':{'trip_primary':state,'trip_reset':'Reset','trip_time':'100:01:01'}.get(name,'')}
            with patch.object(radio,'ui_resource',side_effect=node),patch.object(radio,'ui_tree',return_value=radio.ET.fromstring('<hierarchy/>')),patch.object(radio,'tap_node') as tap,patch.object(radio,'run'):
                self.assertIn('text redacted and unverified',radio.smoke_trip(['adb']))
                self.assertEqual([call.args[1]['resource-id'].split('/')[-1] for call in tap.call_args_list],['where_am_i_button'])

def probe_fixture():
    node=dict(package=radio.PACKAGE,viewId=radio.PACKAGE+':id/where_am_i_button',
              visible=True,enabled=True,clickable=True,bounds=[0,0,100,80],childCount=0,children=[],
              actions=[{'id':16,'name':'CLICK'}],text={'redacted':True,'value':'[REDACTED]'})
    return dict(schema=1,status=dict(connected=True,service=radio.PROBE_SERVICE,elapsedMs=1020),
                windows=[{'root':node}],activeRoot=copy.deepcopy(node),nodesWritten=2,nodeLimit=2500,depthLimit=48)

class ProbeTests(unittest.TestCase):
    def test_complete_fresh_redacted_probe_preserves_only_structural_evidence(self):
        tree=radio.probe_tree(json.dumps(probe_fixture()),1000,1050)
        node=next(tree.iter('node'))
        self.assertEqual(node.get('resource-id'),radio.PACKAGE+':id/where_am_i_button')
        self.assertEqual(node.get('text'),'');self.assertEqual(node.get('text-redacted'),'true')
        with patch.object(radio,'run') as run:radio.tap_node(['adb'],node)
        run.assert_called_once_with(['adb'],['shell','input','tap','50','40'])
    def test_stale_partial_disconnected_or_truncated_probe_is_rejected(self):
        changes=[lambda d:d['status'].update(elapsedMs=1000),lambda d:d['status'].update(elapsedMs=2000),
                 lambda d:d['status'].update(connected=False),lambda d:d.update(nodesWritten=1),
                 lambda d:d.update(windows=[]),lambda d:d['windows'][0].update(root=None),
                 lambda d:d['activeRoot'].update(truncated=True),
                 lambda d:d['windows'][0]['root'].update(childCount=1),
                 lambda d:d['windows'][0]['root'].update(children=[None],childCount=1),
                 lambda d:d['windows'][0]['root'].update(bounds=[0,0,True,1])]
        for change in changes:
            document=probe_fixture();change(document)
            with self.subTest(document=document),self.assertRaises(radio.Blocked):radio.probe_tree(json.dumps(document),1000,1050)
        for payload in (b'{',b'[]',b'null'):
            with self.subTest(payload=payload),self.assertRaises(radio.Blocked):radio.probe_tree(payload,1000,1050)
    def test_ordered_broadcast_uses_existing_service_without_ui_automation(self):
        calls=[]
        def run(adb,args,**kwargs):
            calls.append(args)
            if args[-1]=='/proc/uptime':return b'1.00 0.00' if len(calls)==1 else b'1.05 0.00'
            if args[1:3]==['am','broadcast']:return b'Broadcast completed: result=0, data="PROBE_WRITTEN"\n'
            if args[-1]==radio.PROBE_FILE:return json.dumps(probe_fixture()).encode()
            raise AssertionError(args)
        with patch.object(radio,'run',side_effect=run),patch.object(radio.time,'sleep'):radio.ui_tree(['adb'])
        text=repr(calls)
        for forbidden in ('uiautomator','instrument','settings','force-stop','input','rm'):
            self.assertNotIn(forbidden,text)
        self.assertIn('ProbeReceiver',text)
    def test_unavailable_or_stale_probe_never_taps_stops_or_launches(self):
        for reply in (b'Broadcast completed: result=2, data="SERVICE_NOT_CONNECTED"',
                      b'Broadcast completed: result=0, data="PROBE_WRITTEN"'):
            calls=[];document=probe_fixture();document['status']['elapsedMs']=900
            def run(adb,args,**kwargs):
                calls.append(args)
                if args[-1]=='/proc/uptime':return b'1.00 0.00'
                if args[1:3]==['am','broadcast']:return reply
                if args[-1]==radio.PROBE_FILE:return json.dumps(document).encode()
                raise AssertionError(args)
            with patch.object(radio,'run',side_effect=run),patch.object(radio.time,'sleep'):
                with self.assertRaises(radio.Blocked):radio.smoke_trip(['adb'])
            self.assertFalse(any('input' in x or 'force-stop' in x or 'start' in x for x in calls))
    def test_process_state_is_explicit_and_includes_secondary_processes(self):
        for output,expected in ((b'PID NAME\n1 init\n',False),
                                (f'PID NAME\n9 {radio.PACKAGE}:listener\n'.encode(),True)):
            with patch.object(radio,'run',return_value=output):self.assertEqual(radio.process_running(['adb']),expected)
        for output in (b'',b'not a listing',b'PID NAME\nunknown\n'):
            with patch.object(radio,'run',return_value=output),self.assertRaises(radio.Blocked):radio.process_running(['adb'])
    def test_restarted_process_blocks_even_equal_map_before_cleanup(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);host=root/'host';host.mkdir();device=root/'device';device.mkdir()
            path=host/'places.sqlite';manifest=map_fixture(path);fake=Device(device)
            (device/'no_backup/places.sqlite').write_bytes(path.read_bytes())
            calls=[]
            def run(adb,args,**kwargs):
                calls.append(args)
                if args[1:]==['ps','-A','-o','PID,NAME']:return f'PID NAME\n42 {radio.PACKAGE}\n'.encode()
                return fake.run(adb,args,**kwargs)
            with patch.object(radio,'run',side_effect=run),self.assertRaises(radio.Blocked):radio.install_map(['adb'],path,manifest)
            self.assertEqual(fake.transfers,[])
            self.assertFalse(any('rm' in x or 'force-stop' in x or 'input' in x for x in calls))

spec=importlib.util.spec_from_file_location('installer',Path(__file__).with_name('install-radio.py'))
installer=importlib.util.module_from_spec(spec);spec.loader.exec_module(installer)

class FlowTests(unittest.TestCase):
    def test_map_preflight_blocks_mismatch_before_any_radio_mutation(self):
        from contextlib import ExitStack
        for state in ('same','changed','missing','corrupt','nonempty_wal','nonempty_journal','nonempty_shm'):
            with self.subTest(state=state),tempfile.TemporaryDirectory() as tmp,ExitStack() as stack:
                root=Path(tmp);config=root/'config.json';robot=root/'robot.json';nav=root/'nav.json';map_path=root/'places.sqlite';apk=root/'app.apk'
                geometry={'schema_version':2,'automation_enabled':False}
                config.write_text(json.dumps({'schema_version':2}))
                robot.write_text(json.dumps(dict(schema_version=1,api_base_url='https://api-eu.roborock.com',routine_id=7,routine_name='Full Cleaning',auth=dict(u='synthetic',s='synthetic',h='synthetic'))))
                nav.write_text(json.dumps({'schema_version':2,'slots':[]}));map_path.write_bytes(b'fixture');apk.write_bytes(b'fixture')
                for path in (config,robot,nav):path.chmod(0o600)
                calls=[]
                def run(adb,args,**kwargs):
                    calls.append(args);text=' '.join(args)
                    if 'dumpsys package' in text:return b'versionCode=31'
                    if 'df -k' in text:return b'data 1000000 0 1000000'
                    if 'sha256sum no_backup/places.sqlite' in text:
                        if state=='missing':return b''
                        digest={'changed':'b','corrupt':'c'}.get(state,'a')*64
                        return (digest+' file\n7\n').encode()
                    if 'home-config.json' in text:return json.dumps(geometry).encode()
                    if 'navigation.json' in text:return nav.read_bytes()
                    return b''
                stack.enter_context(patch('sys.argv',['install-radio.py','--config',str(config),'--roborock',str(robot),'--navigation',str(nav),'--map',str(map_path),'--apk',str(apk)]))
                stack.enter_context(patch.object(installer,'run',side_effect=run))
                stack.enter_context(patch.object(installer,'validate_apk',return_value=31))
                stack.enter_context(patch.object(installer,'map_manifest',return_value={'schema':3,'sha256':'a'*64}))
                stack.enter_context(patch.object(installer,'discover',return_value=['adb','-s','synthetic']))
                stack.enter_context(patch.object(installer,'physical'))
                stack.enter_context(patch.object(radio,'remote_map_guard'))
                stack.enter_context(patch.object(radio,'remote_sqlite_sidecar_guard',side_effect=radio.Blocked('Nonempty remote SQLite sidecar') if state.startswith('nonempty_') else None))
                idle=stack.enter_context(patch.object(installer,'wait_idle'))
                block=stack.enter_context(patch.object(installer,'block_update'))
                failed=stack.enter_context(patch.object(installer,'fail_closed'))
                preserved=stack.enter_context(patch.object(installer,'preserved_preferences',return_value={}))
                configure=stack.enter_context(patch.object(installer.subprocess,'run',return_value=type('Result',(),{'returncode':0})()))
                upload=stack.enter_context(patch.object(installer,'install_map',return_value=False))
                finish=stack.enter_context(patch.object(installer,'finish_import'))
                if state=='same':
                    installer.main()
                    self.assertEqual(idle.call_count,2)
                    block.assert_called_once();configure.assert_called_once();upload.assert_called_once();finish.assert_called_once()
                    self.assertTrue(any('force-stop' in call for call in calls))
                else:
                    with self.assertRaises(radio.Blocked):installer.main()
                    for boundary in (idle,block,preserved,configure,upload,finish):boundary.assert_not_called()
                    self.assertFalse(any('force-stop' in call or 'input' in call or 'start' in call for call in calls))
                failed.assert_not_called()

    def test_core_verification_precedes_release_and_passive_smoke(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk=Path(tmp)/'app.apk';apk.write_bytes(b'synthetic APK')
            config={'schema_version':2,'automation_enabled':False}
            nav={'schema_version':2,'slots':[]};calls=[]
            def run(adb,args,**kwargs):
                calls.append(args)
                text=' '.join(args)
                if 'home-config.json' in text:return json.dumps(config).encode()
                if 'navigation.json' in text:return json.dumps(nav).encode()
                if 'pending-config.json' in text:return b''
                if args[1:3]==['pm','path']:return b'package:/data/app/base.apk'
                if 'sha256sum' in args:return ((radio.sha(apk) if args[-1].endswith('.apk') else 'a'*64)+' file').encode()
                if 'ro.build.version.sdk' in text:return b'28'
                if 'dumpsys package' in text:return b'android.permission.ACCESS_COARSE_LOCATION: granted=true\nandroid.permission.ACCESS_FINE_LOCATION: granted=true'
                if 'appops get' in text:return b'allow'
                if 'dumpsys activity service' in text:return b'monitor_running=true registered=true'
                return b''
            def smoke(adb):
                self.assertTrue(any('no_backup/install-verification' in call and 'rm' in call for call in calls))
            with patch.object(installer,'run',side_effect=run),patch.object(installer,'preserved_preferences',return_value={'safe':b'same'}),patch.object(installer,'smoke_trip',side_effect=smoke),patch.object(installer.time,'sleep'):
                installer.finish_import(['adb'],config,nav,{'safe':b'same'},apk,{'sha256':'a'*64})
            release=next(i for i,call in enumerate(calls) if 'no_backup/install-verification' in call and 'rm' in call)
            hashes=[i for i,call in enumerate(calls) if 'sha256sum' in call]
            self.assertTrue(hashes and all(i<release for i in hashes))
    def test_outer_busy_and_partial_failure_then_retry(self):
        from contextlib import ExitStack
        for fault in ('busy_after_block','partial_map','after_release',None):
            with self.subTest(fault=fault),tempfile.TemporaryDirectory() as tmp,ExitStack() as stack:
                root=Path(tmp);config=root/'config.json';robot=root/'robot.json';nav=root/'nav.json';map_path=root/'places.sqlite';apk=root/'app.apk'
                geometry={'schema_version':2,'automation_enabled':False}
                config.write_text(json.dumps({'schema_version':2}))
                robot.write_text(json.dumps(dict(schema_version=1,api_base_url='https://api-eu.roborock.com',routine_id=7,routine_name='Full Cleaning',auth=dict(u='synthetic',s='synthetic',h='synthetic'))))
                nav.write_text(json.dumps({'schema_version':2,'slots':[]}));map_path.write_bytes(b'fixture');apk.write_bytes(b'fixture')
                for path in (config,robot,nav):path.chmod(0o600)
                calls=[]
                def run(adb,args,**kwargs):
                    calls.append(args);text=' '.join(args)
                    if 'dumpsys package' in text:return b'versionCode=31'
                    if 'df -k' in text:return b'data 1000000 0 1000000'
                    if 'sha256sum no_backup/places.sqlite' in text:return (('a'*64)+' file\n7\n').encode()
                    if 'home-config.json' in text:return json.dumps(geometry).encode()
                    if 'navigation.json' in text:return nav.read_bytes()
                    return b''
                stack.enter_context(patch('sys.argv',['install-radio.py','--config',str(config),'--roborock',str(robot),'--navigation',str(nav),'--map',str(map_path),'--apk',str(apk)]))
                stack.enter_context(patch.object(installer,'run',side_effect=run))
                stack.enter_context(patch.object(installer,'validate_apk',return_value=31))
                stack.enter_context(patch.object(installer,'map_manifest',return_value={'schema':3,'sha256':'a'*64}))
                stack.enter_context(patch.object(installer,'discover',return_value=['adb','-s','synthetic']))
                stack.enter_context(patch.object(installer,'physical'))
                stack.enter_context(patch.object(radio,'remote_map_guard'))
                stack.enter_context(patch.object(radio,'remote_sqlite_sidecar_guard'))
                stack.enter_context(patch.object(installer,'wait_idle',side_effect=[None,radio.Blocked('busy')] if fault=='busy_after_block' else [None,None]))
                block=stack.enter_context(patch.object(installer,'block_update'))
                failed=stack.enter_context(patch.object(installer,'fail_closed'))
                stack.enter_context(patch.object(installer,'preserved_preferences',return_value={'safe':b'same'}))
                stack.enter_context(patch.object(installer.subprocess,'run',return_value=type('Result',(),{'returncode':0})()))
                upload=stack.enter_context(patch.object(installer,'install_map',side_effect=radio.Blocked('partial') if fault=='partial_map' else None,return_value=True))
                finish=stack.enter_context(patch.object(installer,'finish_import',side_effect=radio.Blocked('post-release') if fault=='after_release' else None))
                if fault:
                    with self.assertRaises(radio.Blocked):installer.main()
                    failed.assert_called_once()
                else:installer.main();failed.assert_not_called()
                block.assert_called_once()
                if fault=='busy_after_block':
                    upload.assert_not_called();finish.assert_not_called()
                    self.assertFalse(any('force-stop' in call for call in calls))
                if fault=='partial_map':finish.assert_not_called()

if __name__=='__main__':unittest.main()
