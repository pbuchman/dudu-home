#!/usr/bin/env python3
"""Prepare web first; explicitly bind a real radio later, preserving existing credentials."""
from pathlib import Path
from urllib.parse import urlsplit
import argparse, fcntl, json, os, secrets, stat, tempfile, uuid


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--device-id')
    parser.add_argument('--config-dir',type=Path,default=Path.home()/'.config/routebook')
    parser.add_argument('--data-dir',type=Path,default=Path.home()/'.local/share/routebook')
    parser.add_argument('--ingest-origin')
    args=parser.parse_args()
    if args.device_id and str(uuid.UUID(args.device_id))!=args.device_id:
        parser.error('canonical device UUID required')
    if args.device_id and not args.ingest_origin:
        parser.error('--ingest-origin is required when binding a radio')
    if args.ingest_origin:
        origin=urlsplit(args.ingest_origin)
        if (origin.scheme!='https' or not origin.hostname or origin.username or origin.password
                or origin.port not in (None,443) or origin.path or origin.query or origin.fragment):
            parser.error('ingest origin must be HTTPS with no credentials, path, query or fragment')
    root=Path(__file__).resolve().parents[1]
    for directory in [args.config_dir,args.data_dir]:
        directory=directory.expanduser().resolve()
        if directory==root or root in directory.parents or any((parent/'.git').exists() for parent in [directory,*directory.parents]):
            parser.error('runtime must be outside repositories')
        directory.mkdir(parents=True,mode=0o700,exist_ok=True)
        if stat.S_IMODE(directory.stat().st_mode)!=0o700 or directory.stat().st_uid!=os.getuid():
            parser.error('runtime directory requires owner and 0700')
    cfg=args.config_dir.expanduser().resolve()

    def read(name):
        path=cfg/name
        if not path.exists() and not path.is_symlink(): return None
        metadata=path.lstat()
        if not stat.S_ISREG(metadata.st_mode) or stat.S_IMODE(metadata.st_mode)!=0o600 or metadata.st_uid!=os.getuid():
            raise ValueError('private config must be regular, owned and 0600')
        return path.read_text()

    def write(name,value):
        # Readers see either the previous complete JSON or the new complete JSON.
        fd,tmp=tempfile.mkstemp(prefix='.provision-',dir=cfg)
        try:
            with os.fdopen(fd,'w') as stream:
                stream.write(value); stream.flush(); os.fsync(stream.fileno())
            os.replace(tmp,cfg/name)
            directory_fd=os.open(cfg,os.O_RDONLY)
            try: os.fsync(directory_fd)
            finally: os.close(directory_fd)
        finally:
            if os.path.exists(tmp): os.unlink(tmp)

    # Serialize local provisioning only; no pairing API or background process.
    lock_fd=os.open(cfg/'.provision.lock',os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    with os.fdopen(lock_fd,'r+') as lock:
        read('.provision.lock')
        fcntl.flock(lock,fcntl.LOCK_EX)
        password_raw=read('db-password')
        backend_raw=read('backend.json')
        ui_raw=read('routebook-config.json')
        android_raw=read('android-provisioning.json')
        if backend_raw and not password_raw: raise ValueError('missing existing database credential')
        password=password_raw.strip() if password_raw else secrets.token_urlsafe(36)
        if not password: raise ValueError('empty database credential')
        backend=json.loads(backend_raw) if backend_raw else {
            'database_url':f'postgresql://routebook:{password}@127.0.0.1:8793/routebook',
            'public':{'host':'127.0.0.1','port':8791},'private':{'host':'127.0.0.1','port':8794},
            'devices':[], 'derivation':{},'rate_limit_per_minute':120}
        ui=json.loads(ui_raw) if ui_raw else {'mode':'api','mapTilerKey':'','mapTilerStyle':'streets-v4'}
        if not isinstance(backend,dict) or not isinstance(backend.get('devices'),list) or not isinstance(ui,dict):
            raise ValueError('invalid existing config')
        android=json.loads(android_raw) if android_raw else None
        new_android=None
        if args.device_id:
            bindings=backend['devices']
            match=[device for device in bindings if device.get('device_id')==args.device_id]
            if len(match)>1 or (bindings and not match):
                raise ValueError('different existing device preserved; review replacement separately')
            if android and (android.get('device_id')!=args.device_id or android.get('endpoint')!=args.ingest_origin+'/v1/ingest'):
                raise ValueError('different existing Android provisioning preserved')
            token=match[0]['token'] if match else android['token'] if android else secrets.token_urlsafe(48)
            if android and android.get('token')!=token:
                raise ValueError('existing token mismatch; no credentials changed')
            if not match: bindings.append({'device_id':args.device_id,'token':token})
            ui['deviceId']=args.device_id
            if not android:
                new_android={'version':1,'device_id':args.device_id,'endpoint':args.ingest_origin+'/v1/ingest',
                             'token':token,'enabled':True,'sample_ms':5000,'max_age_ms':5000,'gap_ms':30000,'accuracy_m':50}
        elif android and not backend['devices']:
            raise ValueError('incomplete binding; rerun with the same explicit device UUID')
        # All validation finishes before writes. Keep an Android token first so an interrupted
        # binding can resume with the same explicit UUID and token; backend bootstrap is separate.
        if not password_raw: write('db-password',password+'\n')
        if new_android: write('android-provisioning.json',json.dumps(new_android)+'\n')
        if not backend_raw or json.loads(backend_raw)!=backend: write('backend.json',json.dumps(backend)+'\n')
        if not ui_raw or json.loads(ui_raw)!=ui: write('routebook-config.json',json.dumps(ui)+'\n')
    print('Private Routebook configuration prepared; '+('device binding ready.' if args.device_id else 'web ready without a radio binding.')+' No services started or device import performed.')


if __name__=='__main__':
    try: main()
    except (ValueError, OSError, KeyError, TypeError):
        raise SystemExit('Provisioning stopped; inspect private configuration, ownership and device identity. Secrets were not printed.')
