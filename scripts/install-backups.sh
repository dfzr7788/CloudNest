#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
[[ $(id -u) == 0 ]] || { echo 'Run as root.' >&2; exit 1; }
repo=$(pwd -P)
[[ "$repo" =~ ^/[a-zA-Z0-9_./-]+$ ]] || { echo 'Use a repository path without special characters.' >&2; exit 1; }
install -d -m 0700 /var/lib/cloudnest-updater/backups
install -d -m 0755 /var/lib/cloudnest-updater/state /etc/cloudnest
install -d -m 0755 /etc/systemd/system/cloudnest-updater.service.d
cat > /etc/systemd/system/cloudnest-updater.service.d/backup.conf <<'EOF'
[Service]
EnvironmentFile=-/etc/cloudnest/backup.env
EOF
if [[ ! -e /etc/cloudnest/backup.env ]]; then
  install -m 0600 /dev/null /etc/cloudnest/backup.env
fi
cat > /etc/systemd/system/cloudnest-backup.service <<EOF
[Unit]
Description=CloudNest consistent and optional encrypted offsite backup
After=docker.service network-online.target
Requires=docker.service
[Service]
Type=oneshot
UMask=0077
EnvironmentFile=-/etc/cloudnest/backup.env
ExecStart=/usr/bin/python3 $repo/scripts/backup.py create --repo $repo
TimeoutStartSec=3h
EOF
cat > /etc/systemd/system/cloudnest-backup.timer <<'EOF'
[Unit]
Description=Daily CloudNest backup at 03:00 server time
[Timer]
OnCalendar=*-*-* 03:00:00
RandomizedDelaySec=10m
Persistent=true
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now cloudnest-backup.timer
if systemctl is-active --quiet cloudnest-updater; then systemctl restart cloudnest-updater; fi
echo 'Daily backups enabled. Offsite: install restic, initialize a repository, set root-only /etc/cloudnest/backup.env.'
