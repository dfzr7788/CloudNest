# CloudNest Horizon Next

[简体中文](README.md) · English

Self-hosted, multi-user file storage with resumable uploads, previews, sharing,
versions, trash, WebDAV, multiple S3-compatible nodes and verified replicas.
The backend runs Java 21 / Spring Boot with MySQL and Redis; the web app uses Vue 3.
The default deployment runs one application process.

Current development version: **2.3.0-dev.1**. The 2.2/2.3 implementation includes folder snapshots, recoveries, integrity scans, transfer fallback, team ACLs and scoped contribution nodes. See [Horizon Next](docs/horizon-next.md) and its [API contract](docs/horizon-next-api.md). Windows mounting currently uses WebDAV; native WinFsp, offline writeback and the encrypted vault are not implemented. Real Windows mounting, Garage public direct uploads and cross-machine stability tests remain release gates.

The 2.1 Connected Workspace baseline includes:
Opt-in [bounded Agent runs and human proposals](docs/en/agent-runtime.md), resource topology, and a [Windows sync development client](clients/windows-sync/README.md) are implemented as development features, not a stable release.
Real Garage failure/recovery tests, isolated restoration and 1 GB VPS measurements
remain release gates. See the [delivery status](docs/connected-workspace.md).

## Install on Linux

Prerequisites: Git, OpenSSL, Python 3, Docker Engine and Docker Compose 2.24.4+.

```bash
git clone https://github.com/duyan9030-dotcom/CloudNest.git
cd CloudNest
bash scripts/init-env.sh
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
bash scripts/create-admin.sh owner
```

Choose a password interactively. There is no default administrator password.
Check that the maintenance result reports `problems: 0` and `action: create-admin`.
Allow inbound TCP 8088, then visit `http://YOUR_SERVER_IP:8088`.
Public registration is disabled by default; administrators can invite users.
Configure HTTPS and `SECURE_COOKIES=true` for long-term public use.
Keep `SECURE_COOKIES=false` when testing over plain HTTP.

The language selector on the login page and drive toolbar switches between
Simplified Chinese and English without resetting the page. Core file management
and the node center have translations; secondary dialogs and some server messages
are still in Chinese.

## Data and upgrades

Back up MySQL, local files, signing keys, deployment configuration and remote S3
objects as a consistent set. **`docker compose down -v` deletes business data volumes.**
Validate restoration before upgrading. After Flyway migrations, switching to an
older JAR alone does not restore the previous database state.

AI is disabled by default and uses a user-configured external API when enabled.
Saving configuration or uploading files does not automatically request a model.
Existing AI assistants do not provide an autonomous administrator Agent Runtime.

## Documentation

- [Deployment and upgrades](docs/en/deployment.md)
- [Using the drive](docs/en/usage.md)
- [Troubleshooting](docs/en/troubleshooting.md)
- [Read-only diagnostic tools](docs/en/agent-tools.md)
- [Chinese documentation](README.md#文档导航)
- [API reference](docs/api.md) / [OpenAPI](docs/openapi.json)
- [Security policy](SECURITY.md)

## Development

Java 21, Maven 3.9+, and Node.js 22.13+ or 24 are required.

```powershell
.\scripts\dev.ps1 server
.\scripts\dev.ps1 web
```

Open `http://127.0.0.1:5173`. Local development uses H2 and local storage.
Run `mvn -B -f cloudnest-server/pom.xml verify`, then in `cloudnest-web` run
`npm ci`, `npm run check:i18n`, `npm run build` and `npm run test:i18n`.

[MIT license](LICENSE).
