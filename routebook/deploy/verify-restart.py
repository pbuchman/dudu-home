#!/usr/bin/env python3
"""Home-dev web-only acceptance: restart only Routebook, compare cluster/config, no inserts."""
import hashlib,json,os,subprocess,time,urllib.request
from pathlib import Path
base=Path(__file__).resolve().parent;cfg=Path.home()/'.config/routebook'
assert json.loads((cfg/'backend.json').read_text())['devices']==[], 'web-only restart acceptance requires no device bindings'
def config_hashes(): return {p.name:hashlib.sha256(p.read_bytes()).digest() for p in cfg.iterdir() if p.is_file()}
def snapshot():
    sql="SELECT json_build_object('devices',(SELECT count(*) FROM routebook_devices),'points',(SELECT count(*) FROM routebook_points),'cluster_id',system_identifier::text,'fsync',current_setting('fsync'),'full_page_writes',current_setting('full_page_writes'),'synchronous_commit',current_setting('synchronous_commit')) FROM pg_control_system();"
    return json.loads(subprocess.check_output(['docker','exec','routebook-db-1','psql','-U','routebook','-d','routebook','-Atc',sql],text=True))
before=snapshot();hashes=config_hashes()
subprocess.run([str(base/'compose.sh'),'restart','db','backend','web'],check=True,stdout=subprocess.DEVNULL)
for _ in range(40):
    try:
        with urllib.request.urlopen('http://127.0.0.1:8792/health',timeout=2) as response:
            if json.loads(response.read())=={'status':'ok'}:break
    except Exception: pass
    time.sleep(.5)
else: raise SystemExit('Routebook health unavailable after restart')
after=snapshot();assert before==after;assert config_hashes()==hashes
assert all(after[key]=='on' for key in ['fsync','full_page_writes','synchronous_commit'])
rows=[]
for name in ['routebook-db-1','routebook-backend-1','routebook-web-1']:
    selected=json.loads(subprocess.check_output(['docker','inspect','--format','{{json .State}}',name],text=True))
    assert selected['Running']
    image=subprocess.check_output(['docker','inspect','--format','{{.Image}}',name],text=True).strip()
    policy=subprocess.check_output(['docker','inspect','--format','{{.HostConfig.RestartPolicy.Name}}',name],text=True).strip()
    rows.append({'container':name,'image_id':image,'running':True,'restart_policy':policy})
print(json.dumps({'restart_passed':True,'database_unchanged':True,'config_unchanged':True,'before':before,'after':after,'containers':rows}))
