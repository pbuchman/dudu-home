"""Shared bounded, sanitized preflight and map validation for authorized local ADB updates."""
import hashlib
import importlib.util
import json
from decimal import Decimal, InvalidOperation
from pathlib import Path
import re
import subprocess
import sqlite3
import stat
import time
import xml.etree.ElementTree as ET

PACKAGE='com.pbuchman.duduhome'
PROBE_PACKAGE='com.pbuchman.wisprsteering'
PROBE_SERVICE=PROBE_PACKAGE+'/'+PROBE_PACKAGE+'.SteeringAccessibilityService'
PROBE_FILE='files/diagnostics/window-tree.json'
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

def idle_evidence(audio,activities,ui,service,process_running=True):
    # Vendor HFP is not Android Telecom. Require several independent observations and report limits.
    modes=re.findall(r'(?:Actual mode|mMode|mode owner.*?mode)\s*[:=]\s*(MODE_\w+|\d+)',audio,re.I)
    if not modes or any(mode not in ('MODE_NORMAL','0') for mode in modes):return False
    resumed='\n'.join(line for line in activities.splitlines() if 'mResumedActivity' in line or 'topResumedActivity' in line)
    if re.search(r'com\.syu\.bt|incall|dialer',resumed,re.I):return False
    busy=re.findall(r'^action_busy=(true|false)$',service,re.M)
    if busy and busy!=['false']:return False
    if process_running and busy!=['false']:return False
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
        ui=ET.tostring(ui_tree(adb),encoding='unicode')
        running=process_running(adb)
        stable=stable+1 if idle_evidence(audio,activities,ui,service,running) else 0
        if stable>=2:return
        if time.monotonic()>=deadline:raise Blocked('Call/action idle preflight inconclusive; application not stopped')
        time.sleep(2)

def sha(path):
    h=hashlib.sha256()
    with open(path,'rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()

def managed_path(path):
    path=Path(path).absolute()
    system_aliases={Path('/tmp'):Path('/private/tmp'),Path('/var'):Path('/private/var'),Path('/etc'):Path('/private/etc')}
    if any(part.is_symlink() and system_aliases.get(part)!=part.resolve() for part in (path,*path.parents)):
        raise Blocked('Symlink input or managed directory refused')
    return path.resolve()

def regular_file(path):
    path=managed_path(path)
    if not stat.S_ISREG(path.stat().st_mode):raise Blocked('Regular input file required')
    return path

def map_manifest(path,allow_legacy=False):
    """Compute a transient descriptor from the database; no companion file is required."""
    path=regular_file(path)
    for suffix in ('-wal','-journal','-shm'):
        sidecar=path.with_name(path.name+suffix)
        if sidecar.is_symlink() or (sidecar.exists() and (not sidecar.is_file() or sidecar.stat().st_size)):
            raise Blocked('Local map has an active SQLite sidecar; standalone database required')
    spec=importlib.util.spec_from_file_location('place_builder',Path(__file__).with_name('build-place-index.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    try:metadata,counts=module.validate(path)
    except (ValueError,KeyError,sqlite3.Error) as error:raise Blocked('Invalid local map database') from error
    schema=int(metadata['schema'])
    if schema not in ((1,2,3) if allow_legacy else (2,3)):raise Blocked('Unsupported map schema for update')
    return dict(schema=schema,file=path.name,bytes=path.stat().st_size,sha256=sha(path),metadata=metadata,counts=counts)

def validate_apk(path,aapt,apksigner,installed_version=0,navigation_schema=1,map_schema=None):
    path=regular_file(path)
    try:
        metadata=subprocess.check_output([str(aapt),'dump','badging',str(path)],stderr=subprocess.PIPE).decode()
        signed=subprocess.check_output([str(apksigner),'verify','--print-certs',str(path)],stderr=subprocess.PIPE).decode()
    except (OSError,subprocess.SubprocessError,UnicodeError) as error:raise Blocked('Local APK verification failed') from error
    target=re.search(r"package: name='([^']+)' versionCode='(\d+)'",metadata)
    if not target or target.group(1)!=PACKAGE or int(target.group(2))<3:raise Blocked('Unexpected APK package/version')
    if not re.findall(r'Signer #\d+ certificate SHA-256 digest: [0-9a-fA-F]{64}',signed):raise Blocked('APK signing certificate unavailable')
    code=int(target.group(2))
    if code<int(installed_version):raise Blocked('APK downgrade refused')
    if navigation_schema==2 and code<17:raise Blocked('APK does not support navigation schema 2')
    validate_map_apk(map_schema,code)
    return code

def validate_map_apk(schema,code):
    if schema is not None and (schema not in (2,3) or code<(29 if schema==3 else 23)):
        raise Blocked('Map schema incompatible with target APK')

# Only these historical artifacts in the selected map directory belong to this tool.
# Dated/custom basenames and arbitrary APK/tar/SQLite files are deliberately not inferred.
SQLITE_SIDECARS=('places.sqlite-journal','places.sqlite-wal','places.sqlite-shm')
MAP_ARTIFACTS=(*SQLITE_SIDECARS,'places.sqlite.new','places.sqlite.previous','places.sqlite.bak','places.sqlite.backup',
               'places.manifest.json','places.manifest.json.new','places.manifest.json.previous',
               'places.heading-audit.jsonl','places.heading-audit.jsonl.building','places.sqlite.building')

def cleanup_host_map(path):
    path=regular_file(path)
    if path.name!='places.sqlite':raise Blocked('Managed map output must be named places.sqlite')
    for name in MAP_ARTIFACTS:
        candidate=path.parent/name
        if candidate.is_symlink() or (candidate.exists() and not candidate.is_file()):
            raise Blocked('Unexpected managed map artifact type')
        if name in SQLITE_SIDECARS and candidate.exists() and candidate.stat().st_size:
            raise Blocked('Local map has a nonempty SQLite sidecar; cleanup refused')
    for name in MAP_ARTIFACTS:(path.parent/name).unlink(missing_ok=True)

def remote_map_guard(adb):
    # Fail before redirecting through a symlink, including a symlinked parent directory.
    names=('no_backup','no_backup/places.sqlite',*(f'no_backup/{name}' for name in MAP_ARTIFACTS))
    checks='; '.join('test ! -L '+name+' || exit 1' for name in names)
    for name in names[1:]:checks+='; if test -e '+name+' && test ! -f '+name+'; then exit 1; fi'

    run(adb,['shell',f"run-as {PACKAGE} sh -c '{checks}'"])

def remote_sqlite_sidecar_guard(adb):
    # Matching main-file bytes do not prove that a journal/WAL has no pending data.
    checks='test ! -L no_backup || exit 1'
    for name in SQLITE_SIDECARS:
        path='no_backup/'+name
        checks+=f'; test ! -L {path} || exit 1; if test -e {path}; then test -f {path} && test ! -s {path} || exit 1; fi'
    try:run(adb,['shell',f"run-as {PACKAGE} sh -c '{checks}'"])
    except Blocked as error:raise Blocked('Remote SQLite sidecar is nonempty or unavailable; cleanup refused') from error

def install_map(adb,path,manifest):
    # The persistent admission block is not a SQLite lock. Listener/task restarts can
    # recreate a controller; observed absence alone cannot exclude a transfer-time race.
    validated=map_manifest(path)
    if validated!=manifest:raise Blocked('Local map changed before installation')
    if Path(path).name!='places.sqlite':raise Blocked('Managed map output must be named places.sqlite')
    remote_map_guard(adb)
    remote_sqlite_sidecar_guard(adb)
    run(adb,['shell','run-as',PACKAGE,'test','-e','no_backup/install-verification'])
    require_quiescent(adb)
    # The existing map must already match; changed/missing/corrupt map repair stays blocked.
    output=run(adb,['shell',f"run-as {PACKAGE} sh -c 'if test -f no_backup/places.sqlite; then sha256sum no_backup/places.sqlite; fi'"],timeout=900).decode().split()
    changed=not output or output[0]!=manifest['sha256']
    if changed:
        raise Blocked('Map replacement requires an enforced quiescence gate; existing map left untouched and update blocked')
    require_quiescent(adb)
    remote_sqlite_sidecar_guard(adb)
    run(adb,['shell','run-as',PACKAGE,'rm','-f',*(f'no_backup/{name}' for name in MAP_ARTIFACTS)])
    require_quiescent(adb)
    cleanup_host_map(path)
    return changed

def block_update(adb,verification=True):
    # The caller has validated all inputs and observed idle before creating either marker.
    markers='no_backup/maintenance'+(' no_backup/install-verification' if verification else '')
    guards='; '.join('test ! -L '+name for name in markers.split())
    run(adb,['shell',f"run-as {PACKAGE} sh -c 'set -e; test ! -L no_backup; {guards}; umask 077; mkdir -p no_backup; touch {markers}; sync'"])

def fail_closed(adb):
    # Retain the block without interrupting a call that may have started after release.
    # In the partial-upload path the caller already stopped the process before writing.
    block_update(adb)

def process_running(adb):
    """Require a successful, structurally valid process listing, never absent logs."""
    lines=run(adb,['shell','ps','-A','-o','PID,NAME']).decode().splitlines()
    if not lines or lines[0].split()!=['PID','NAME']:raise Blocked('Process state unavailable')
    running=False
    for line in lines[1:]:
        fields=line.split()
        if len(fields)!=2 or not fields[0].isdigit():raise Blocked('Process state unavailable')
        if fields[1]==PACKAGE or fields[1].startswith(PACKAGE+':'):running=True
    return running

def require_quiescent(adb):
    if process_running(adb):raise Blocked('Application restarted; map left blocked without stopping later work')

def device_elapsed(adb):
    try:
        value=Decimal(run(adb,['shell','cat','/proc/uptime']).decode().split()[0])
        if not value.is_finite() or value<0:raise ValueError()
        return int(value*1000)
    except (IndexError,ValueError,InvalidOperation,UnicodeError) as error:
        raise Blocked('Probe freshness clock unavailable') from error

def probe_tree(payload,earliest,latest):
    """Validate actual redacted ProbeStore schema, including silent truncation cases."""
    try:
        document=json.loads(payload)
        status=document['status'];elapsed=status['elapsedMs']
        if (type(document['schema']) is not int or document['schema']!=1
                or status['connected'] is not True or status['service']!=PROBE_SERVICE
                or type(elapsed) is not int or not earliest+10<=elapsed<=latest+10
                or not 0<=latest-earliest<=30000):raise ValueError()
        if document['nodeLimit']!=2500 or document['depthLimit']!=48:raise ValueError()
        hierarchy=ET.Element('hierarchy',{'probe':'redacted-accessibility'});count=0
        def append(source,parent,depth=0):
            nonlocal count
            if not isinstance(source,dict) or source.get('truncated') or depth>48:raise ValueError()
            count+=1
            if count>2500:raise ValueError()
            children=source['children'];bounds=source['bounds'];pkg=source['package'];view_id=source['viewId']
            if (not isinstance(children,list) or type(source['childCount']) is not int or source['childCount']!=len(children)
                    or not isinstance(bounds,list) or len(bounds)!=4 or any(type(n) is not int for n in bounds)
                    or not isinstance(pkg,str) or (view_id is not None and not isinstance(view_id,str))):raise ValueError()
            for flag in ('visible','enabled','clickable'):
                if type(source[flag]) is not bool:raise ValueError()
            text=source['text']
            if not isinstance(text,dict) or type(text['redacted']) is not bool or not isinstance(text['value'],str):raise ValueError()
            actions=source['actions']
            if not isinstance(actions,list) or any(not isinstance(x,dict) or not isinstance(x.get('name'),str) for x in actions):raise ValueError()
            node=ET.SubElement(parent,'node',{'resource-id':view_id or '', 'package':pkg,
                'text':'' if text['redacted'] else text['value'], 'text-redacted':str(text['redacted']).lower(),
                'visible':str(source['visible']).lower(),'enabled':str(source['enabled']).lower(),
                'clickable':str(source['clickable']).lower(),
                'bounds':f'[{bounds[0]},{bounds[1]}][{bounds[2]},{bounds[3]}]',
                'scrollable':str(any(x['name']=='SCROLL_FORWARD' for x in actions)).lower()})
            for child in children:append(child,node,depth+1)
        windows=document['windows']
        if not isinstance(windows,list) or not windows:raise ValueError()
        for window in windows:append(window['root'],hierarchy)
        # The duplicate active root is part of nodesWritten; validate it as well.
        if document['activeRoot'] is not None:append(document['activeRoot'],ET.Element('active'))
        if type(document['nodesWritten']) is not int or document['nodesWritten']!=count:raise ValueError()
        return hierarchy
    except (ValueError,KeyError,TypeError,RecursionError,UnicodeError) as error:
        raise Blocked('Fresh complete accessibility probe unavailable; no UI action taken') from error

def ui_tree(adb):
    # Ordered DUMP uses the connected service; it never starts UIAutomation or changes
    # enabled services. ProbeStore can retain an old AtomicFile after a failed capture.
    earliest=device_elapsed(adb)
    time.sleep(.02)  # /proc/uptime has 10 ms precision: capture beyond its old tick.
    reply=run(adb,['shell','am','broadcast','--user','0','-a',PROBE_PACKAGE+'.DUMP',
                   '-n',PROBE_PACKAGE+'/.ProbeReceiver']).decode()
    if not re.search(r'Broadcast completed: result=0, data="PROBE_WRITTEN"',reply):
        raise Blocked('Connected accessibility probe unavailable; no UI action taken')
    payload=run(adb,['exec-out','run-as',PROBE_PACKAGE,'cat',PROBE_FILE])
    return probe_tree(payload,earliest,device_elapsed(adb))

def tap_node(adb,node):
    bounds=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',node.get('bounds',''))
    if (node.get('package')!=PACKAGE or node.get('visible')!='true'
            or node.get('enabled')!='true' or node.get('clickable')!='true'):
        raise Blocked('UI target is not visibly actionable')
    if bounds is None:raise Blocked('UI target bounds unavailable')
    x,y,right,bottom=map(int,bounds.groups())
    if right<=x or bottom<=y:raise Blocked('UI target not visible')
    run(adb,['shell','input','tap',str((x+right)//2),str((y+bottom)//2)])

def ui_resource(adb,name,seconds=15):
    deadline=time.monotonic()+seconds
    while True:
        for node in ui_tree(adb).iter('node'):
            if (node.get('package')==PACKAGE and node.get('resource-id')==PACKAGE+':id/'+name
                    and node.get('visible')=='true'):return node
        if time.monotonic()>=deadline:raise Blocked('Expected trip UI unavailable; no other action tapped')
        time.sleep(.5)

def smoke_trip(adb):
    # Installation only inspects the passive screen. Never Start, Pause or Reset user data.
    # GPS may keep increasing an already-active session while this check is running.
    tap_node(adb,ui_resource(adb,'where_am_i_button'))
    # The passive screen scrolls on short landscape displays; reveal its lower controls.
    tree=ui_tree(adb)
    scroll=next((n for n in tree.iter('node') if n.get('package')==PACKAGE
                 and n.get('visible')=='true' and n.get('scrollable')=='true'),None)
    if scroll is not None:
        bounds=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',scroll.get('bounds',''))
        if bounds is None:raise Blocked('Trip scroll bounds unavailable; no session action tapped')
        left,top,right,bottom=map(int,bounds.groups());x=(left+right)//2
        run(adb,['shell','input','swipe',str(x),str(top+(bottom-top)*3//4),str(x),str(top+(bottom-top)//4),'300'])
    for name in ('trip_primary','trip_reset','trip_time'):ui_resource(adb,name)
    # ProbeStore redacts these strings. IDs prove structure only; keep the caller's
    # independent pre-release trip-state comparison rather than claiming text evidence.
    run(adb,['shell','input','keyevent','KEYCODE_BACK'])
    return 'passed passive session screen/control structure; text redacted and unverified; no session control tapped'
