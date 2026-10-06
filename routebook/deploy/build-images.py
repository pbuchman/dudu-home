#!/usr/bin/env python3
"""Copy only application sources into a scratch context; never send checkout/private files."""
from pathlib import Path
import shutil, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
if not (root/'package-lock.json').is_file():
    raise SystemExit('BLOCKED: integrator must supply reviewed root package-lock.json first')
with tempfile.TemporaryDirectory(prefix='routebook-build-') as tmp:
    target=Path(tmp)
    for name in ['package.json','package-lock.json','tsconfig.json']:
        shutil.copy2(root/name,target/name)
    for area in ['backend','ui']:
        (target/area).mkdir()
        for name in ['package.json','tsconfig.json','index.html','vite.config.ts']:
            p=root/area/name
            if p.is_file(): shutil.copy2(p,target/area/name)
        for name in ['src','sql']:
            p=root/area/name
            if p.is_dir(): shutil.copytree(p,target/area/name)
    (target/'deploy/templates').mkdir(parents=True)
    shutil.copy2(root/'deploy/Dockerfile',target/'deploy/Dockerfile')
    shutil.copy2(root/'deploy/templates/nginx.conf',target/'deploy/templates/nginx.conf')
    for stage in ['backend','web']:
        subprocess.run(['docker','build','--target',stage,'-t',f'routebook-{stage}:local','-f',str(target/'deploy/Dockerfile'),str(target)],check=True)
