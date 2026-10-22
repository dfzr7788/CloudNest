# CloudNest

English · [简体中文](README.md)

CloudNest is a self-hosted, multi-user file drive with personal file management, team workspaces, multiple storage nodes and data protection. Optional AI features use your own API provider.

Current version: `2.3.0-dev.1` (development preview). The backend supports one application instance. Windows sync, WebDAV drive mapping and public direct uploads have the limitations described in the [feature guide (Chinese)](docs/features.md#功能边界).

## Features

| Area | Capabilities |
| --- | --- |
| Files and transfers | Folders, favorites, tags, full-text search, cross-folder selection, resumable uploads, SHA-256 verification and ZIP download tasks |
| Reading and sharing | Image, PDF, Markdown and Office reading previews, version history, password-protected shares, collections and upload-only links |
| Teams | Invitations, member roles, directory ACLs, allocated quotas, team recycle bin and audit records |
| Data protection | Directory snapshots, comparisons, copy-based restoration, scheduled policies and integrity checks |
| Storage | Local and S3-compatible storage, capacity budgets, placement policies, migrations, verified replicas and team-contributed nodes |
| Accounts | Session management, TOTP, email recovery and revocable WebDAV application passwords |
| Optional services | OnlyOffice, Windows sync prototype, AI reading and knowledge search, read-only diagnostics, bounded Agent runs and human approvals |

AI is disabled by default. Uploading files or saving settings does not call a model. Review external data transfer and costs before confirming requests. Replicas and snapshots do not replace independent backups.

## Install on Linux

Requirements: Git, OpenSSL, Python 3, Docker Engine and Docker Compose 2.24.4+.

```bash
git clone https://github.com/duyan9030-dotcom/CloudNest.git
cd CloudNest
bash scripts/init-env.sh
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
bash scripts/create-admin.sh owner
```

Choose the administrator password interactively; there is no default password. Check for `problems: 0` and `action: create-admin`. Allow inbound TCP 8088 and visit `http://YOUR_SERVER_IP:8088`. Public registration is disabled by default; administrators can invite users.

The initialization script generates random secrets and preserves existing `.env` files. Use HTTPS and `SECURE_COOKIES=true` for public deployment; use `false` for plain HTTP. See the [deployment guide](docs/en/deployment.md).

## Data and upgrades

Back up MySQL, local and remote objects, signing keys, deployment configuration and the running version as a consistent set. **`docker compose down -v` deletes business data volumes.** Verify restoration before upgrading. Source deployments can then run:

```bash
git pull --ff-only
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
```

After database migrations, changing the image alone cannot restore the previous database. Prebuilt images and web updates are covered in the [system update guide (Chinese)](docs/system-updates.md).

## Development

The frontend uses Vue 3 and TypeScript; the backend uses Java 21 and Spring Boot. Docker uses MySQL and Redis; local development supports H2 and local storage.

Install Java 21, Maven 3.9+ and Node.js 22.13+ or 24. Run these in separate PowerShell windows:

```powershell
.\scripts\dev.ps1 server
.\scripts\dev.ps1 web
```

Open `http://127.0.0.1:5173`. See the [development guide (Chinese)](docs/development.md) for configuration and checks.

## Documentation

- [Feature guide (Chinese)](docs/features.md) and [using the drive](docs/en/usage.md)
- [Deployment](docs/en/deployment.md) and [troubleshooting](docs/en/troubleshooting.md)
- [Read-only diagnostics](docs/en/agent-tools.md) and [Agent runs and approvals](docs/en/agent-runtime.md)
- [Windows sync client](clients/windows-sync/README.md) and [storage node client](clients/storage-node/README.md)
- [API reference](docs/api.md), [workspace API](docs/workspace-api.md) and [OpenAPI](docs/openapi.json)
- [Complete documentation index (Chinese)](README.md#文档导航)
- [Contributing](CONTRIBUTING.md) and [security policy](SECURITY.md)

[MIT license](LICENSE).
