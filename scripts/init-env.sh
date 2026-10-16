#!/usr/bin/env bash
# Creates configuration only. Never replaces existing deployment secrets.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
command -v openssl >/dev/null || { echo 'Install openssl first.' >&2; exit 1; }
if [[ -e .env ]]; then
  echo '.env already exists; preserved. Edit it manually if needed.'
  exit 0
fi
umask 077
database_password=$(openssl rand -hex 32)
root_password=$(openssl rand -hex 32)
redis_password=$(openssl rand -hex 32)
# noclobber also protects against concurrent initializers.
set -o noclobber
{
  printf 'DATABASE_PASSWORD=%s\n' "$database_password"
  printf 'MYSQL_ROOT_PASSWORD=%s\n' "$root_password"
  printf 'REDIS_PASSWORD=%s\n' "$redis_password"
  printf '%s\n' 'WEB_PORT=8088' 'SECURE_COOKIES=false' 'STORAGE_BACKEND=local' 'REGISTRATION_OPEN=false'
} > .env
echo 'Created .env with independent random passwords. Keep this file private and backed up.'
