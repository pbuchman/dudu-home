#!/usr/bin/env python3
"""Explicit, bounded cleanup of one owner-selected legacy update directory. No recursion."""
import argparse
import json
from pathlib import Path
import re
import sys
from radio_update import Blocked,MAP_ARTIFACTS,managed_path,regular_file,sha,map_manifest

BACKUP_FILES={'installed.apk','app-data.tar','sha256.json'}
RECEIPT_FIELDS={'status','apk_sha256','map_sha256','map_schema','configuration_preserved','monitor_registered','road_test','ui_smoke'}

def cleanup(directory):
    root=managed_path(directory)
    if not root.is_dir() or root==root.parent:raise Blocked('Explicit managed directory required')
    if any((parent/'.git').exists() for parent in (root,*root.parents)):raise Blocked('Cleanup directory must be outside repositories')
    files=set();directories=[]
    # Inspect every recognized candidate before deleting anything. Never recurse into it.
    for item in root.iterdir():
        if item.name in MAP_ARTIFACTS:
            regular_file(item)
            if item.name in ('places.sqlite-wal','places.sqlite-journal','places.sqlite-shm') and item.stat().st_size:
                raise Blocked('Canonical map has an active SQLite sidecar; cleanup refused')
            files.add(item)
        elif re.fullmatch(r'before-update-[A-Za-z0-9_-]+',item.name):
            if item.is_symlink() or not item.is_dir():raise Blocked('Unsafe historical backup type')
            members={entry.name:entry for entry in item.iterdir()}
            if set(members) not in (BACKUP_FILES,BACKUP_FILES|{'installation-result.json'}):raise Blocked('Unrecognized historical backup contents')
            for entry in members.values():regular_file(entry)
            try:manifest=json.loads(members['sha256.json'].read_text())
            except (ValueError,OSError) as error:raise Blocked('Invalid historical backup manifest') from error
            if not isinstance(manifest,dict) or set(manifest)!={'installed.apk','app-data.tar'}:raise Blocked('Unrecognized historical backup manifest')
            for name,digest in manifest.items():
                if not isinstance(digest,str) or not re.fullmatch('[0-9a-f]{64}',digest) or sha(members[name])!=digest:
                    raise Blocked('Historical backup checksum mismatch')
            if 'installation-result.json' in members:
                try:receipt=json.loads(members['installation-result.json'].read_text())
                except ValueError as error:raise Blocked('Invalid historical update receipt') from error
                if (not isinstance(receipt,dict) or set(receipt)!=RECEIPT_FIELDS or receipt.get('status')!='installed'
                        or not all(isinstance(receipt.get(k),str) and re.fullmatch('[0-9a-f]{64}',receipt[k]) for k in ('apk_sha256','map_sha256'))):
                    raise Blocked('Unrecognized historical update receipt')
            files.update(members.values());directories.append(item)
    # Old builder manifests bind dated/custom versions to real validated database bytes.
    # A .sqlite extension alone never establishes ownership.
    for sidecar in root.glob('*.manifest.json'):
        if sidecar.name in MAP_ARTIFACTS:continue
        regular_file(sidecar)
        try:manifest=json.loads(sidecar.read_text())
        except ValueError:continue
        if not isinstance(manifest,dict) or set(manifest)!={'schema','file','sha256','bytes','metadata','counts'}:continue
        name=manifest.get('file')
        if not isinstance(name,str) or Path(name).name!=name or '/' in name or '\\' in name or not name.endswith('.sqlite'):
            raise Blocked('Historical map manifest path refused')
        path=root/name
        if sidecar!=path.with_suffix('.manifest.json'):raise Blocked('Historical map manifest basename mismatch')
        validated=map_manifest(path,allow_legacy=True)
        if validated!=manifest:raise Blocked('Historical map manifest does not match database')
        if name=='places.sqlite':continue
        audit=path.with_suffix('.heading-audit.jsonl')
        expected=manifest['metadata'].get('short_heading_audit_sha256')
        if expected:
            regular_file(audit)
            if sha(audit)!=expected:raise Blocked('Historical map audit checksum mismatch')
            files.add(audit)
        elif audit.exists() or audit.is_symlink():raise Blocked('Unbound historical map audit refused')
        files.update((path,sidecar))
    for path in sorted(files):path.unlink()
    for path in directories:path.rmdir()
    return len(files),len(directories)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('directory',type=Path);args=p.parse_args()
    files,directories=cleanup(args.directory)
    print(f'PASS: removed {files} recognized files and {directories} historical backup directories; unrelated files retained.')
if __name__=='__main__':
    try:main()
    except (Blocked,OSError,ValueError,TypeError) as error:
        print('BLOCKED: '+(str(error) if isinstance(error,Blocked) else type(error).__name__),file=sys.stderr);sys.exit(1)
