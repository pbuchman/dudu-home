#!/usr/bin/env python3
"""Reproducible, emulator-only real TLS -> Fastify -> PostGIS -> UI acceptance."""
import argparse, hashlib, json, os, pathlib, re, secrets, subprocess, tempfile, time, uuid
ROOT=pathlib.Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--android',type=pathlib.Path,required=True);p.add_argument('--serial',default='emulator-5554');args=p.parse_args();args.android=args.android.resolve()
adb=pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb';evidence=ROOT/'tests/integration/evidence';evidence.mkdir(parents=True,exist_ok=True)
def command(argv,**kw):
    return subprocess.run(list(map(str,argv)),check=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,**kw).stdout
if not args.serial.startswith('emulator-'):raise SystemExit('Emulator serial required')
if command([adb,'-s',args.serial,'shell','getprop','ro.kernel.qemu']).strip()!=b'1':raise SystemExit('Physical device refused')
apk=args.android/'app/build/outputs/apk/debug/app-debug.apk';before=hashlib.sha256(apk.read_bytes()).hexdigest()
build=command([args.android/'gradlew','assembleDebugAndroidTest','-ProutebookRunner=com.pbuchman.duduhome.routebook.RoutebookHttpsInstrumentation'],cwd=args.android,env=dict(os.environ,ANDROID_HOME=str(pathlib.Path.home()/'Library/Android/sdk')))
(evidence/'android-instrumentation-build.txt').write_bytes(build)
run_id=str(uuid.uuid4());component='com.pbuchman.duduhome.test/com.pbuchman.duduhome.routebook.RoutebookHttpsInstrumentation'
command([adb,'-s',args.serial,'install','-r',apk]);command([adb,'-s',args.serial,'install','-r',args.android/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'])
def phase(name):
    output=command([adb,'-s',args.serial,'shell','am','instrument','-w','-e','run_id',run_id,'-e','phase',name,component]).decode()
    (evidence/f'android-{name}.txt').write_text(output)
    if 'PASS: HTTPS '+name not in output:raise RuntimeError(output)
    return output
seed=phase('seed');device=re.search(r'device_id=([0-9a-f-]{36})',seed).group(1)
with tempfile.TemporaryDirectory(prefix='routebook-integration-') as temp:
    d=pathlib.Path(temp);os.chmod(d,0o700);token=secrets.token_urlsafe(32)
    def private(name,value):
        f=d/name;f.write_text(json.dumps(value));os.chmod(f,0o600)
    private('harness.json',dict(device_id=device,token=token,database_url='postgresql://postgres@127.0.0.1:55439/routebook_synthetic'))
    private('fault.json',{'mode':'normal'})
    (d/'extensions').write_text('subjectAltName=DNS:localhost\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n')
    command(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',d/'ca.key','-out',d/'ca.pem','-days','1','-subj','/CN=Routebook synthetic test CA'])
    command(['openssl','req','-newkey','rsa:2048','-nodes','-keyout',d/'server.key','-out',d/'server.csr','-subj','/CN=localhost'])
    command(['openssl','x509','-req','-in',d/'server.csr','-CA',d/'ca.pem','-CAkey',d/'ca.key','-CAcreateserial','-out',d/'server.pem','-days','1','-extfile',d/'extensions'])
    for f in d.iterdir():os.chmod(f,0o600)
    for filename,data in [('routebook-https-config.json',json.dumps({'token':token}).encode()),('routebook-https-ca.pem',(d/'ca.pem').read_bytes())]:
        command([adb,'-s',args.serial,'shell',f'run-as com.pbuchman.duduhome sh -c "umask 077; cat > no_backup/{filename}"'],input=data)
    command([adb,'-s',args.serial,'reverse','tcp:18443','tcp:18443'])
    env=dict(os.environ,ROUTEBOOK_INTEGRATION_PRIVATE=temp)
    with (evidence/'harness.txt').open('w') as log:
        server=subprocess.Popen(['node','tests/integration/harness.mjs'],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
        try:
            deadline=time.monotonic()+15
            while not (d/'ready').exists():
                if server.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Harness failed; inspect evidence/harness.txt')
                time.sleep(.1)
            phase('tls');private('fault.json',{'mode':'lost'});phase('lost')
            command([adb,'-s',args.serial,'shell','am','force-stop','com.pbuchman.duduhome'])
            phase('resume');phase('partial');phase('auth');private('fault.json',{'mode':'redirect'});phase('redirect')
            private('fault.json',{'mode':'invalid'});phase('invalid');private('fault.json',{'mode':'normal'});phase('recover')
            verification=subprocess.run(['node','tests/integration/verify.mjs'],cwd=ROOT,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
            output=verification.stdout.decode();(evidence/'verify.txt').write_text(output);print(output);verification.check_returncode()
        finally:
            server.terminate()
            try:server.wait(timeout=15)
            except subprocess.TimeoutExpired:server.kill();server.wait()
            if (d/'ledger.json').exists():(evidence/'ledger.json').write_bytes((d/'ledger.json').read_bytes())
            command([adb,'-s',args.serial,'reverse','--remove','tcp:18443'])
            command([adb,'-s',args.serial,'shell','run-as','com.pbuchman.duduhome','rm','-f','no_backup/routebook-https-config.json','no_backup/routebook-https-ca.pem'])
after=hashlib.sha256(apk.read_bytes()).hexdigest();assert before==after
(evidence/'apk-sha256.txt').write_text(after+'\n');print('PASS unchanged application APK '+after)
