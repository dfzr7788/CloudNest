# Deployment and upgrades

[简体中文](../deployment.md) · [English home](../../README.en.md)

These instructions install the pinned 1.0.0 release. The release workflow verifies
the Linux Docker deployment and an isolated backup restore. Validate networking,
TLS and remote object storage in your own deployment.

## Prerequisites and startup

Use Docker Engine with Compose 2.24.4 or later (the configuration uses `!override`).
Install Git, OpenSSL and Python 3. Start with at least 2 GiB RAM and enough disk
for the database, files, upload staging, builds and backups. Operation on a 1 GB
VPS has not yet passed the real-server acceptance gate.

```bash
git clone --branch v1.0.0 https://github.com/duyan9030-dotcom/CloudNest.git
cd CloudNest
git switch -c main
bash scripts/init-env.sh
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
docker compose ps
bash scripts/create-admin.sh owner
```

`init-env.sh` generates independent random secrets and preserves an existing `.env`.
Do not publish that file. The generated configuration exposes only the web port;
MySQL, Redis and the backend remain internal. The base Compose file listens on
loopback; the generated public overlay exposes port 8088.

Create the administrator interactively, verify `problems: 0` in its maintenance
result, and sign in at `http://YOUR_SERVER_IP:8088`. Open TCP 8088 in the cloud
firewall, preserving SSH access. No default login password exists.

For Linux amd64, `python3 scripts/use-prebuilt.py` downloads verified images for
the checked-out revision. After a successful download, start with
`docker compose up -d --no-build --wait --wait-timeout 180`.
Wait for that revision's CI release assets or build from source if unavailable.

## HTTPS and access

Put the web service behind an HTTPS reverse proxy for public use. Enable
`SECURE_COOKIES=true` only when the browser accesses the application over HTTPS.
Keep it false for plain HTTP tests. Do not expose MySQL, Redis, S3 credentials
or the backend directly to the Internet. Registration is closed by default;
administrators create invitations from account/security administration.

## Backup, restore and rollback

`mysql_data` contains database state; `app_data` contains local files, upload
staging and signing keys. `.env` contains deployment settings. Remote S3 objects
need their own consistent backup; a database-only backup is insufficient.

```bash
python3 scripts/backup.py create
python3 scripts/backup.py list
python3 scripts/backup.py verify --snapshot /ABSOLUTE/PATH/TO/SNAPSHOT
```

The default backup base is `/var/lib/cloudnest-updater`. Record the exact revision,
image checksums and snapshot location. For split A/B storage, follow the existing
[dual-node recovery procedure](../deployment-dual-node.md); do not treat a local
snapshot as proof of remote-object restoration.

Restore into a separate directory, Compose project, volumes, ports and isolated
remote bucket. Use `python3 scripts/backup.py --help` to inspect the restoration
options, and verify file bytes, versions, shares and signed downloads there.
Never rehearse restoration over the working deployment.

Only after validating the backup and rollback baseline:

```bash
git fetch origin tag v1.0.0
git merge --ff-only v1.0.0
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
```

Flyway changes are forward migrations. Rollback requires a consistent database,
objects, keys and application revision, not only an old application image.
Do not run `docker compose down -v`: it removes the data volumes.

## Background workers

`WORKER_ENABLED=true` is the default. `false` disables scheduled maintenance,
storage jobs, AI task polling and cleanup in this process. It also prevents
startup recovery from resetting interrupted uploads, downloads, storage jobs,
AI/knowledge tasks or billing records, and from releasing their reservations.
Maintenance mode also suppresses this global recovery. Default single-process
workers retain the existing interruption recovery behavior.

Flyway, node registration/local health initialization and explicit HTTP or
maintenance operations still run. Request-triggered ZIP and whole-node migration
workers are not disabled. This is not a read-only server mode.
Disabling workers in the only process leaves queued jobs unprocessed.
The switch prepares a logical role boundary; running multiple application
instances is still unsupported pending shared-state and coordination tests.
