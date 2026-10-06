#!/usr/bin/env python3
"""Local Docker only: real PostGIS, compiled Fastify and Nginx, disposable synthetic device."""
from pathlib import Path
import argparse, json, os, subprocess, tempfile, time, uuid
base=Path(__file__).resolve().parent; root=base.parent
for p in [root/'backend/dist/main.js',root/'ui/dist/index.html']:
    if not p.is_file(): raise SystemExit('BLOCKED: owners must build backend/UI before this check')
parser=argparse.ArgumentParser();parser.add_argument('--web-only',action='store_true');options=parser.parse_args()
prefix='routebook-h2-synthetic-'+uuid.uuid4().hex[:8]
names=[]
def run(args,**kw):
    result=subprocess.run(args,capture_output=True,text=True,**kw)
    if result.returncode:
        print(result.stderr)
        raise RuntimeError('local deployment test subprocess failed')
    return result.stdout.strip()
def docker(*args): return run(['docker',*args])
with tempfile.TemporaryDirectory(prefix=prefix) as t:
    tmp=Path(t); cfg=tmp/'config'
    run(['python3',str(base/'prepare-config.py'),'--config-dir',str(cfg),'--data-dir',str(tmp/'data')]+([] if options.web_only else ['--ingest-origin','https://routebook-ingest.example.com','--device-id','00000000-0000-4000-8000-000000000001']))
    data=json.loads((cfg/'backend.json').read_text());data['database_url']=data['database_url'].replace(':8793/',':5432/');(cfg/'backend.json').write_text(json.dumps(data))
    try:
        db=prefix+'-db';names.append(db)
        docker('run','-d','--name',db,'-e','POSTGRES_DB=routebook','-e','POSTGRES_USER=routebook','-e','POSTGRES_PASSWORD_FILE=/run/routebook/db-password',
               '-v',str(cfg)+':/run/routebook:ro','postgis/postgis:17-3.5')
        for _ in range(40):
            if subprocess.run(['docker','exec',db,'pg_isready','-h','127.0.0.1','-U','routebook','-d','routebook'],capture_output=True).returncode==0: break
            time.sleep(.5)
        else: raise RuntimeError('synthetic PostGIS unavailable')
        args=['--user',f'{os.getuid()}:{os.getgid()}','--network','container:'+db,'-e','ROUTEBOOK_CONFIG=/run/routebook/backend.json','-v',str(cfg)+':/run/routebook:ro','-v',str(root/'backend')+':/app/backend:ro','-v',str(root/'node_modules')+':/app/node_modules:ro','-w','/app']
        docker('run','--rm',*args,'node:22.22.0-bookworm-slim','node','backend/dist/main.js','--bootstrap')
        backend=prefix+'-backend';names.append(backend)
        docker('run','-d','--name',backend,*args,'node:22.22.0-bookworm-slim','node','backend/dist/main.js')
        web=prefix+'-web';names.append(web)
        docker('run','-d','--name',web,'--user',f'{os.getuid()}:{os.getgid()}','--network','container:'+db,
               '-v',str(cfg)+':/run/routebook:ro','-v',str(base/'templates/nginx.conf')+':/etc/nginx/nginx.conf:ro',
               '-v',str(root/'ui/dist')+':/usr/share/nginx/html:ro','--entrypoint','nginx','nginx:1.28-alpine','-g','daemon off;')
        time.sleep(1)
        check=docker('run','--rm','--network','container:'+db,'-v',str(cfg)+':/run/routebook:ro','-v',str(base)+':/checks:ro','python:3.12-alpine','python','/checks/test-isolation.py','--allow-loopback','--config','/run/routebook/backend.json','--public-origin','http://127.0.0.1:8791','--private-origin','http://127.0.0.1:8792')
        print(check)
        print('Local real PostGIS/Fastify/Nginx isolation passed. Disposable synthetic database only; home-dev unchanged.')
    finally:
        for name in reversed(names):
            subprocess.run(['docker','rm','-fv',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
