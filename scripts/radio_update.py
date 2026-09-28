"""Shared bounded, sanitized preflight and map validation for authorized local ADB updates."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE='com.pbuchman.duduhome'
class Blocked(RuntimeError): pass

def run(adb,args,**kwargs):
    result=subprocess.run([*adb,*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=kwargs.pop('timeout',30),**kwargs)
    if result.returncode:raise Blocked('ADB operation failed; no private output disclosed')
    return result.stdout

def physical(adb):
    hardware=run(adb,['shell','getprop','ro.hardware']).decode().strip()
    model=run(adb,['shell','getprop','ro.product.model']).decode().strip()
    if hardware in ('ranchu','goldfish') or not model:raise Blocked('Physical radio required')
    if run(adb,['shell','am','get-current-user']).strip()!=b'0':raise Blocked('Radio owner user required')
    if b'package:' not in run(adb,['shell','pm','path','com.syu.ms']):raise Blocked('SYU service not found')
    if b'versionCode=' not in run(adb,['shell','dumpsys','package',PACKAGE]):raise Blocked('Existing Dudu Home required')

def discover(executables,cached=None,seconds=90):
    deadline=time.monotonic()+seconds
    while True:
        found={}
        for executable in dict.fromkeys(executables):
            try:
                if cached:
                    try:subprocess.run([executable,'connect',cached],capture_output=True,timeout=8)
                    except subprocess.TimeoutExpired:pass
                services=run([executable],['mdns','services'],timeout=8).decode()
                for line in services.splitlines():
                    if '_adb-tls-connect._tcp' in line or '_adb._tcp' in line:
                        address=line.split()[-1]
                        if re.fullmatch(r'[a-zA-Z0-9.:%\[\]-]+:\d+',address):
                            subprocess.run([executable,'connect',address],capture_output=True,timeout=8)
                devices=run([executable],['devices'],timeout=8).decode()
                for line in devices.splitlines():
                    fields=line.split()
                    if len(fields)!=2 or fields[1]!='device' or fields[0].startswith('emulator-'):continue
                    candidate=[executable,'-s',fields[0]]
                    try:physical(candidate)
                    except Blocked:continue
                    identity=run(candidate,['shell','getprop','ro.serialno']).strip()
                    if not identity:raise Blocked('Radio identity unavailable')
                    found[identity]=candidate
            except (OSError,subprocess.TimeoutExpired,Blocked):continue
        if len(found)==1:return next(iter(found.values()))
        if len(found)>1:raise Blocked('Multiple authorized radios; automatic selection refused')
        if time.monotonic()>=deadline:raise Blocked('Radio unavailable or ADB unauthorized on the local network')
        time.sleep(min(5,max(0,deadline-time.monotonic())))

def idle_evidence(audio,activities,ui,service):
    # Vendor HFP is not Android Telecom. Require several independent observations and report limits.
    modes=re.findall(r'(?:Actual mode|mMode|mode owner.*?mode)\s*[:=]\s*(MODE_\w+|\d+)',audio,re.I)
    if not modes or any(mode not in ('MODE_NORMAL','0') for mode in modes):return False
    resumed='\n'.join(line for line in activities.splitlines() if 'mResumedActivity' in line or 'topResumedActivity' in line)
    if re.search(r'com\.syu\.bt|incall|dialer',resumed,re.I):return False
    if 'action_busy=true' in service:return False
    try:root=ET.fromstring(ui[ui.index('<hierarchy'):])
    except (ValueError,ET.ParseError):return False
    protected={'progress','call_status_content','status_title','gate_number_input','roborock_input'}
    if any(node.get('resource-id','').startswith(PACKAGE+':id/') and node.get('resource-id','').split('/')[-1] in protected for node in root.iter('node')):return False
    return True

def wait_idle(adb,seconds=300):
    deadline=time.monotonic()+seconds;stable=0
    while True:
        audio=run(adb,['shell','dumpsys','audio']).decode()
        activities=run(adb,['shell','dumpsys','activity','activities']).decode()
        service=run(adb,['shell','dumpsys','activity','service',PACKAGE+'/.location.HomeMonitorService']).decode()
        run(adb,['shell','uiautomator','dump','/data/local/tmp/dudu-update-ui.xml'])
        ui=run(adb,['shell','cat','/data/local/tmp/dudu-update-ui.xml']).decode()
        run(adb,['shell','rm','-f','/data/local/tmp/dudu-update-ui.xml'])
        stable=stable+1 if idle_evidence(audio,activities,ui,service) else 0
        if stable>=2:return
        if time.monotonic()>=deadline:raise Blocked('Call/action idle preflight inconclusive; application not stopped')
        time.sleep(2)

def sha(path):
    h=hashlib.sha256()
    with open(path,'rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()

def map_manifest(path):
    path=Path(path).resolve()
    manifest=json.loads(path.with_suffix('.manifest.json').read_text())
    if manifest.get('schema')!=1 or manifest.get('file')!=path.name or manifest.get('bytes')!=path.stat().st_size or manifest.get('sha256')!=sha(path):
        raise Blocked('Map manifest/checksum mismatch')
    spec=importlib.util.spec_from_file_location('place_builder',Path(__file__).with_name('build-place-index.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    metadata,counts=module.validate(path)
    if metadata!=manifest['metadata'] or counts!=manifest['counts']:raise Blocked('Map metadata mismatch')
    return manifest

def install_map(adb,path,manifest):
    with open(path,'rb') as source:
        run(adb,['shell',f"run-as {PACKAGE} sh -c 'umask 077; cat > no_backup/places.sqlite.new'"],stdin=source,timeout=900)
    output=run(adb,['shell','run-as',PACKAGE,'sha256sum','no_backup/places.sqlite.new']).decode().split()
    if not output or output[0]!=manifest['sha256']:raise Blocked('Staged map checksum mismatch')
    run(adb,['shell',f"run-as {PACKAGE} sh -c 'mv no_backup/places.sqlite.new no_backup/places.sqlite'"])
    run(adb,['shell',f"run-as {PACKAGE} sh -c 'cat > no_backup/places.manifest.json'"],input=json.dumps(manifest).encode())

def ui_tree(adb):
    run(adb,['shell','uiautomator','dump','/data/local/tmp/dudu-trip-ui.xml'])
    text=run(adb,['shell','cat','/data/local/tmp/dudu-trip-ui.xml']).decode()
    run(adb,['shell','rm','-f','/data/local/tmp/dudu-trip-ui.xml'])
    try:return ET.fromstring(text[text.index('<hierarchy'):])
    except (ValueError,ET.ParseError):raise Blocked('UI hierarchy unavailable')

def tap_node(adb,node):
    bounds=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.get('bounds',''))
    if bounds is None:raise Blocked('UI target bounds unavailable')
    x,y,right,bottom=map(int,bounds.groups())
    if right<=x or bottom<=y:raise Blocked('UI target not visible')
    run(adb,['shell','input','tap',str((x+right)//2),str((y+bottom)//2)])

def ui_resource(adb,name,seconds=15):
    deadline=time.monotonic()+seconds
    while True:
        for node in ui_tree(adb).iter('node'):
            if node.get('resource-id')==PACKAGE+':id/'+name:return node
        if time.monotonic()>=deadline:raise Blocked('Expected trip UI unavailable; no other action tapped')
        time.sleep(.5)

def smoke_trip(adb):
    # Preserve an existing user trip or summary. Only exercise a fresh installation's empty session.
    saved=subprocess.run([*adb,'exec-out','run-as',PACKAGE,'cat','no_backup/trip-state.json'],capture_output=True,timeout=10)
    try:before=json.loads(saved.stdout)
    except ValueError:before={'state':'OFF','meters':0}
    if before.get('state') in ('ACTIVE','PAUSED') or before.get('meters',0)>0:
        return 'skipped to preserve existing trip'
    started=False
    try:
        tap_node(adb,ui_resource(adb,'where_am_i_button'))
        primary=ui_resource(adb,'trip_primary')
        if primary.get('text')!='Rozpocznij':raise Blocked('Unexpected existing trip state')
        tap_node(adb,primary);started=True;time.sleep(2)
        if ui_resource(adb,'trip_primary').get('text')!='Pauza':raise Blocked('Trip did not start')
        city=ui_resource(adb,'trip_locality').get('text')
        run(adb,['shell','input','keyevent','KEYCODE_HOME']);time.sleep(2)
        overlay=ui_resource(adb,'trip_locality',seconds=5)
        city=overlay.get('text')
        tap_node(adb,overlay)
        if ui_resource(adb,'trip_primary').get('text')!='Pauza':raise Blocked('Overlay tap did not open trip')
        run(adb,['shell','input','keyevent','KEYCODE_HOME']);time.sleep(1)
        run(adb,['shell','cmd','statusbar','expand-notifications']);time.sleep(1)
        notification=next((n for n in ui_tree(adb).iter('node') if n.get('resource-id')=='android:id/title' and n.get('text')==city),None)
        if notification is None:raise Blocked('Trip notification not found in radio UI')
        tap_node(adb,notification)
        tap_node(adb,ui_resource(adb,'trip_primary'));time.sleep(1)
        if ui_resource(adb,'trip_primary').get('text')!='Wznów':raise Blocked('Pause not confirmed')
        tap_node(adb,ui_resource(adb,'trip_end'));started=False;time.sleep(1)
        if ui_resource(adb,'trip_primary').get('text')!='Rozpocznij':raise Blocked('End not confirmed')
        tap_node(adb,ui_resource(adb,'trip_back'))
        return 'passed start/background/overlay/notification/pause/end'
    finally:
        if started:
            run(adb,['shell','cmd','statusbar','collapse'])
            run(adb,['shell','am','start','-n',PACKAGE+'/.ui.MainActivity','-a',PACKAGE+'.OPEN_TRIP'])
            tap_node(adb,ui_resource(adb,'trip_end',seconds=30))
