# Private Routebook deployment

Runtime configuration and data stay outside Git. Linux Docker hosts use deploy/compose.yaml.
Backend listens on loopback 8791 (ingest) and 8794 (private reads), nginx on 8792 (private
UI/API/SSE), PostgreSQL on 8793. Check host inventory before assigning these ports.

## Updating an existing paired installation to 1.4

For the explicitly approved 1.4.0-rc3 parking-time rollout, the owner waived a new backup.
Skip step 2's source/configuration copies and database dump for that rollout. Record only
configuration digests/permissions, existing source/image identifiers and raw-archive count
and digest evidence in a private receipt. Preserve incoming measurements; the event-table
migration is additive. Retain old application images for rollback, never restore an older
database over newly received points. All other deployment/verification steps still apply.
Power interpretation profiles remain empty until separate hardware acceptance, so existing
configuration and pairing require no changes to use historical estimates.

Use the reviewed merged release revision. Do not run pairing/provisioning again and do not
change public ingest, tailnet routes, device identity, credentials or the raw archive.

1. Record current source-manifest and backend/web image IDs. Preserve the old images under
   rollback tags before building the replacement images. Check available memory: bounded
   maximum-size analytics requires at least 1.5 GiB available for the application.
2. Create an owner-only backup directory (0700) outside the runtime/source tree. Save the
   current source and configuration (files 0600), a custom-format PostgreSQL dump and a
   private receipt. Verify dump readability with pg_restore and record checksums. Retain
   archive identity/count evidence and configuration hashes for the post-update comparison.
3. Transfer reviewed source with sync-source.py, which preserves unrelated destination
   files. Verify its source-manifest checksums on the host. Run start-runtime.sh with the
   authorized deployment flag. Bootstrap adds only metadata/place tables; existing raw
   point schema and ingest protocol remain compatible.
4. Verify private HTTPS health, existing v1 history/latest and analytics/detail/place reads,
   browser navigation and map rendering. Compare preserved archive and configuration
   evidence. Do not create synthetic points or place labels in the production database.
5. If health or archive/configuration checks fail, restore the previous source and select
   the preserved backend/web images, then recreate only those services. The additive
   metadata tables can remain: the old application ignores them. Do not drop tables or
   restore the entire database over new incoming points as an automatic rollback.

Keep the backup, source/image receipt, APK and live screenshots private. Physical Android
installation is a separate operation; the existing radio continues to use v1 ingestion.

## Initial installation

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
