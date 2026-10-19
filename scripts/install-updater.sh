#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
[[ $(id -u) == 0 ]] || { echo 'Run as root.' >&2; exit 1; }
command -v python3 >/dev/null
command -v docker >/dev/null
command -v systemctl >/dev/null
[[ -f .env ]] || { echo 'Initialize .env first.' >&2; exit 1; }
[[ ! -e /var/lib/cloudnest-updater/state/active.json ]] || { echo 'An update is active; wait for it to finish before reinstalling.' >&2; exit 1; }
repo=$(pwd -P)
# Keep systemd ExecStart arguments and environment unambiguous.
[[ "$repo" =~ ^/[a-zA-Z0-9_./-]+$ ]] || { echo 'Use a repository path without spaces or special characters.' >&2; exit 1; }
[[ $(git remote get-url origin) == 'https://github.com/duyan9030-dotcom/CloudNest.git' ]] || {
  echo 'Updater requires the official HTTPS origin.' >&2; exit 1;
}
install -d -m 0755 /opt/cloudnest-updater /var/lib/cloudnest-updater
install -d -m 0700 /var/lib/cloudnest-updater/backups
install -d -m 0755 /var/lib/cloudnest-updater/state
install -d -m 0700 -o 10001 -g 10001 /var/lib/cloudnest-updater/requests
install -m 0755 scripts/updater.py /opt/cloudnest-updater/updater.py
install -m 0644 scripts/split_backup.py /opt/cloudnest-updater/split_backup.py
# Preserve previously selected TLS/other overlays. Compose reads this value from .env.
python3 - <<'PY'
from pathlib import Path
p = Path('.env')
lines = p.read_text().splitlines()
current = next((s.split('=', 1)[1].strip().strip('"\'') for s in lines if s.startswith('COMPOSE_FILE=')), 'docker-compose.yml')
if 'deploy/compose.updater.yml' not in current.split(':'):
    current += ':deploy/compose.updater.yml'
lines = [s for s in lines if not s.startswith('COMPOSE_FILE=')]
p.write_text('\n'.join(lines) + '\nCOMPOSE_FILE=' + current + '\n')
p.chmod(0o600)
PY
docker compose config --quiet
cat > /etc/systemd/system/cloudnest-updater.service <<EOF
[Unit]
Description=CloudNest administrator update worker
After=docker.service network-online.target
Wants=network-online.target
Requires=docker.service

[Service]
Type=simple
UMask=0077
EnvironmentFile=-/etc/cloudnest/backup.env
ExecStart=/usr/bin/python3 /opt/cloudnest-updater/updater.py --repo $repo
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now cloudnest-updater
systemctl restart cloudnest-updater
if grep -q 'deploy/compose.images.yml' .env; then
  docker compose up -d --no-build --wait --wait-timeout 180 server web
else
  docker compose up -d --build --wait --wait-timeout 180 server web
fi
git rev-parse HEAD > /var/lib/cloudnest-updater/state/deployed
echo 'Updater installed. Sign in as administrator and open System updates.'
