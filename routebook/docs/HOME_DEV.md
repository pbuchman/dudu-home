# Private Routebook deployment

Runtime configuration and data stay outside Git. Linux Docker hosts use deploy/compose.yaml.
Backend listens on loopback 8791 (ingest) and 8794 (private reads), nginx on 8792 (private
UI/API/SSE), PostgreSQL on 8793. Check host inventory before assigning these ports.

1. From routebook/, run `npm ci`, `npm run build`, `npm test` and deployment template tests.
2. Transfer reviewed source with `python3 deploy/sync-source.py --host YOUR_SSH_ALIAS`.
   Existing destination files are preserved; no runtime configuration or private evidence is sent.
3. On the host, run `python3 deploy/prepare-config.py` for an unpaired web-only instance.
   It preserves existing credentials and configuration; it does not start services.
4. Run `./deploy/start-runtime.sh --h3-authorized` only as an authorized deployment operation.
5. Expose nginx using a tailnet-only HTTPS origin. Keep UI, history, health and SSE private.
6. The existing owner of Cloudflare tunnel/DNS may add a dedicated public HTTPS ingest origin
   targeting loopback 8791. Preserve every unrelated route and deny public read access.
   `assert-cloudflare-plan.mjs PLAN.json POLICY.json` checks the dedicated delta against the
   existing owner's Terraform resource layout. Private POLICY.json contains host, healthHost
   and matrixHost. The example.com hosts in tests/templates are invented, never deployment defaults.
7. After installation, read the real radio installation UUID and run prepare-config.py with
   `--device-id UUID --ingest-origin https://YOUR_INGEST_HOST`. Import its private provisioning
   file using the Android helper. Preserve the existing database, token and device identity.
8. Verify authenticated real ingest, commit ACK, private history and live updates; never insert
   fictitious devices into the production archive for a smoke test.

Backend JSON, database password, Android token and browser map configuration are owner-only
files (0600, directory 0700). Never put an ingest token in UI configuration. Restrict the
MapTiler browser key to the actual UI origin and preserve provider attribution.

Existing deployment evidence stays private. Publishing this source does not deploy it,
change a tunnel, update credentials or certify an installed radio. Database restart tests
use only an explicitly dedicated synthetic database/container. No destructive migration or
automatic rollback system is included.
