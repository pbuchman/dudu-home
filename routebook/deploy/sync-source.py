#!/usr/bin/env python3
"""Ship only reviewed runtime/build sources to home-dev, preserving other destination files."""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess, tempfile
parser=argparse.ArgumentParser()
parser.add_argument("--host",required=True)
args=parser.parse_args()
if not args.host or args.host.startswith("-") or not all(c.isalnum() or c in "._-@" for c in args.host): parser.error("invalid SSH host alias")
root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='routebook-source-') as tmp:
    target=Path(tmp)
    for name in ['package.json','package-lock.json','tsconfig.json','README.md']:
        shutil.copy2(root/name,target/name)
    for area in ['backend','ui']:
        (target/area).mkdir()
        for name in ['package.json','tsconfig.json','index.html','vite.config.ts']:
            path=root/area/name
            if path.is_file(): shutil.copy2(path,target/area/name)
        for name in ['src','sql']:
            path=root/area/name
            if path.is_dir(): shutil.copytree(path,target/area/name)
    (target/'deploy').mkdir()
    for path in (root/'deploy').iterdir():
        if path.is_file() and (path.suffix in ['.py','.sh','.mjs','.yaml'] or path.name=='Dockerfile'):
            shutil.copy2(path,target/'deploy'/path.name)
    shutil.copytree(root/'deploy/templates',target/'deploy/templates')
    (target/'docs').mkdir()
    shutil.copy2(root/'docs/HOME_DEV.md',target/'docs/HOME_DEV.md')
    manifest={str(path.relative_to(target)):hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(target.rglob('*')) if path.is_file()}
    (target/'deploy/source-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    subprocess.run(['ssh',args.host,'mkdir -p ~/personal/routebook'],check=True)
    subprocess.run(['rsync','-a','--checksum',str(target)+'/', args.host+':personal/routebook/'],check=True)
    print(f'Sent {len(manifest)} reviewed files and checksum manifest; no delete, secrets, APK or private GPS.')
