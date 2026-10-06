#!/usr/bin/env python3
"""Read-only isolation probes. No coordinates or response bodies printed; no valid point writes."""
import argparse, json, ssl, urllib.request, urllib.error
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('--public-origin',required=True)
p.add_argument('--private-origin',required=True)
p.add_argument('--config',type=Path,required=True)
p.add_argument('--allow-loopback',action='store_true')
a=p.parse_args()
from urllib.parse import urlsplit
for origin in [a.public_origin,a.private_origin]:
    u=urlsplit(origin)
    if u.path not in ('','/') or u.query or u.fragment or u.username: p.error('origin only')
    if u.scheme!='https' and not(a.allow_loopback and u.scheme=='http' and u.hostname=='127.0.0.1'): p.error('HTTPS required')
config=json.loads(a.config.read_text())
binding=config['devices'][0] if config['devices'] else None
device=binding['device_id'] if binding else None; token=binding['token'] if binding else None
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None
opener=urllib.request.build_opener(NoRedirect(),urllib.request.HTTPSHandler(context=ssl.create_default_context()))
def check(origin,path,status,method='GET',auth=None,data=None,verify=None):
    headers={'Content-Type':'application/json','User-Agent':'Routebook-Isolation-Check/1.0'}
    if auth: headers['Authorization']='Bearer '+auth
    request=urllib.request.Request(origin.rstrip('/')+path,data=data,headers=headers,method=method)
    try:
        with opener.open(request,timeout=10) as r:
            actual=r.status
            body=r.read(4*1024*1024) if verify else b''
    except urllib.error.HTTPError as e: actual=e.code; body=b''; e.close()
    if actual!=status: raise SystemExit(f'FAIL {method} {path}: expected {status}, got {actual}')
    if verify and not verify(body): raise SystemExit(f'FAIL response contract {path}')
    print(f'PASS {method} {path}: {status}')
query='?device_id='+device if device else ''
paths=['/','/index.html','/routebook-config.json','/health','/v1/latest'+query,
       '/v1/day'+query+('&date=2026-10-05' if device else ''),
       '/v1/range'+query+('&from=2026-10-05&to=2026-10-05' if device else ''),
       '/v1/live'+query,'/v1/ingest','/unknown']
for path in paths:
    check(a.public_origin,path,404)
    if token: check(a.public_origin,path,404,auth=token)
check(a.public_origin,'/v1/ingest',401,'POST',data=b'{}')
check(a.public_origin,'/v1/ingest',401,'POST',auth='wrong',data=b'{}')
if token: check(a.public_origin,'/v1/ingest',422,'POST',auth=token,data=b'{}')
for method in ['HEAD','OPTIONS','PUT','DELETE']:
    check(a.public_origin,'/v1/ingest',404,method,auth=token)
check(a.private_origin,'/health',200,verify=lambda b:json.loads(b)=={'status':'ok'})
check(a.private_origin,'/',200,verify=lambda b:b'<html' in b.lower())
check(a.private_origin,'/routebook-config.json',200,verify=lambda b:json.loads(b).get('deviceId')==device and 'token' not in json.loads(b))
check(a.private_origin,'/v1/ingest',404,'POST',auth=token,data=b'{}')
for path in paths[4:7] if device else []:
    check(a.private_origin,path,200,verify=lambda b:json.loads(b).get('device_id')==device)
if not device:
    print('Web-only isolation passed; no invented device read/SSE requests. Radio binding is pending.')
    raise SystemExit(0)
request=urllib.request.Request(a.private_origin.rstrip('/')+'/v1/live?device_id='+device,headers={'User-Agent':'Routebook-Isolation-Check/1.0'})
with opener.open(request,timeout=10) as stream:
    if stream.status!=200 or 'text/event-stream' not in stream.headers.get('Content-Type',''): raise SystemExit('FAIL SSE status/content type')
    event=None; snapshot=None
    for _ in range(10):
        line=stream.readline(1024*1024).decode().strip()
        if line.startswith('event: '): event=line[7:]
        if line.startswith('data: '): snapshot=json.loads(line[6:]); break
    if event!='snapshot' or not snapshot or snapshot.get('device_id')!=device: raise SystemExit('FAIL SSE snapshot')
print('PASS private SSE snapshot')
print('Isolation probes passed; no GPS writes performed.')
