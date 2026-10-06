#!/usr/bin/env python3
import importlib.util, json, os, stat, subprocess, tempfile, unittest
from pathlib import Path
base=Path(__file__).resolve().parent
class Templates(unittest.TestCase):
    def test_private_provisioning_and_preservation(self):
        with tempfile.TemporaryDirectory() as t:
            cfg=Path(t)/'config'; data=Path(t)/'data'
            cmd=['python3',str(base/'prepare-config.py'),'--ingest-origin','https://routebook-ingest.example.com','--device-id','00000000-0000-4000-8000-000000000001','--config-dir',str(cfg),'--data-dir',str(data)]
            result=subprocess.run(cmd,capture_output=True,check=True)
            b=json.loads((cfg/'backend.json').read_text()); android=json.loads((cfg/'android-provisioning.json').read_text())
            token=b['devices'][0]['token']
            self.assertNotIn(token,result.stdout.decode())
            self.assertEqual(android['token'],token)
            self.assertEqual(android['endpoint'],'https://routebook-ingest.example.com/v1/ingest')
            self.assertEqual(set(android),{'version','device_id','endpoint','token','enabled','sample_ms','max_age_ms','gap_ms','accuracy_m'})
            self.assertEqual(b['public'],{'host':'127.0.0.1','port':8791})
            self.assertEqual(b['private'],{'host':'127.0.0.1','port':8794})
            self.assertEqual(json.loads((cfg/'routebook-config.json').read_text())['mapTilerKey'],'')
            for f in cfg.iterdir(): self.assertEqual(stat.S_IMODE(f.stat().st_mode),0o600)
            self.assertEqual(stat.S_IMODE(cfg.stat().st_mode),0o700)
            before={f.name:f.read_bytes() for f in cfg.iterdir()}
            self.assertEqual(subprocess.run(cmd,capture_output=True).returncode,0)
            self.assertEqual(before,{f.name:f.read_bytes() for f in cfg.iterdir()})
            env={**os.environ,'ROUTEBOOK_CONFIG_DIR':str(cfg),'ROUTEBOOK_DATA_DIR':str(data),'ROUTEBOOK_UID':str(os.getuid()),'ROUTEBOOK_GID':str(os.getgid())}
            render=subprocess.run(['docker','compose','-f',str(base/'compose.yaml'),'config','--format','json'],env=env,capture_output=True,check=True)
            compose=json.loads(render.stdout)
            self.assertEqual(compose['services']['db']['ports'][0]['host_ip'],'127.0.0.1')
            self.assertEqual(compose['services']['backend']['network_mode'],'host')
            self.assertNotIn(token,render.stdout.decode())
    def test_web_first_then_real_binding(self):
        with tempfile.TemporaryDirectory() as t:
            cfg=Path(t)/'config'; data=Path(t)/'data'
            cmd=['python3',str(base/'prepare-config.py'),'--ingest-origin','https://routebook-ingest.example.com','--config-dir',str(cfg),'--data-dir',str(data)]
            subprocess.run(cmd,capture_output=True,check=True)
            backend=json.loads((cfg/'backend.json').read_text())
            ui=json.loads((cfg/'routebook-config.json').read_text())
            self.assertEqual(backend['devices'],[])
            self.assertNotIn('deviceId',ui)
            self.assertFalse((cfg/'android-provisioning.json').exists())
            before={f.name:f.read_bytes() for f in cfg.iterdir()}
            subprocess.run(cmd,capture_output=True,check=True)
            self.assertEqual(before,{f.name:f.read_bytes() for f in cfg.iterdir()})
            # Preserve operator changes and durable data while adding only the binding.
            backend['rate_limit_per_minute']=60; backend['derivation']={'stop_seconds':240}
            (cfg/'backend.json').write_text(json.dumps(backend))
            ui['mapTilerKey']='synthetic-browser-map-key'; ui['mapTilerStyle']='outdoor-v2'
            (cfg/'routebook-config.json').write_text(json.dumps(ui))
            (data/'sentinel').write_bytes(b'synthetic-existing-data')
            password=(cfg/'db-password').read_bytes()
            binding=cmd+['--device-id','00000000-0000-4000-8000-000000000001']
            result=subprocess.run(binding,capture_output=True,check=True)
            after=json.loads((cfg/'backend.json').read_text()); android=json.loads((cfg/'android-provisioning.json').read_text())
            self.assertEqual((cfg/'db-password').read_bytes(),password)
            self.assertEqual({k:v for k,v in after.items() if k!='devices'},{k:v for k,v in backend.items() if k!='devices'})
            self.assertEqual(json.loads((cfg/'routebook-config.json').read_text()),{**ui,'deviceId':android['device_id']})
            self.assertEqual(android['token'],after['devices'][0]['token'])
            self.assertNotIn(android['token'],result.stdout.decode())
            self.assertEqual((data/'sentinel').read_bytes(),b'synthetic-existing-data')
            before={f.name:f.read_bytes() for f in cfg.iterdir()}
            subprocess.run(binding,capture_output=True,check=True)
            subprocess.run(cmd,capture_output=True,check=True)
            self.assertEqual(before,{f.name:f.read_bytes() for f in cfg.iterdir()})
            wrong=cmd+['--device-id','00000000-0000-4000-8000-000000000099']
            self.assertNotEqual(subprocess.run(wrong,capture_output=True).returncode,0)
            self.assertEqual(before,{f.name:f.read_bytes() for f in cfg.iterdir()})
            for f in cfg.iterdir(): self.assertEqual(stat.S_IMODE(f.stat().st_mode),0o600)
    def test_credential_mismatch_preserved(self):
        with tempfile.TemporaryDirectory() as t:
            cfg=Path(t)/'config'; data=Path(t)/'data'
            cmd=['python3',str(base/'prepare-config.py'),'--ingest-origin','https://routebook-ingest.example.com','--config-dir',str(cfg),'--data-dir',str(data),'--device-id','00000000-0000-4000-8000-000000000001']
            subprocess.run(cmd,capture_output=True,check=True)
            android=json.loads((cfg/'android-provisioning.json').read_text());android['token']='synthetic_mismatched_token_abcdefghijklmnopqrstuvwxyz'
            (cfg/'android-provisioning.json').write_text(json.dumps(android))
            before={f.name:f.read_bytes() for f in cfg.iterdir()}
            self.assertNotEqual(subprocess.run(cmd,capture_output=True).returncode,0)
            self.assertEqual(before,{f.name:f.read_bytes() for f in cfg.iterdir()})
    def test_h3_gate(self):
        self.assertNotEqual(subprocess.run(['bash',str(base/'start-runtime.sh')],capture_output=True).returncode,0)
    def test_nginx_private_listener_and_sse(self):
        nginx=(base/'templates/nginx.conf').read_text()
        self.assertIn('listen 127.0.0.1:8792;',nginx)
        self.assertIn('proxy_buffering off;',nginx)
        self.assertIn('location = /v1/ingest { return 404; }',nginx)
        self.assertIn('access_log off;',nginx)
        self.assertNotIn('8791',nginx)
if __name__=='__main__': unittest.main(verbosity=2)
