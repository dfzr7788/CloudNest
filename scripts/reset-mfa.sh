#!/usr/bin/env bash
# Host-only recovery, requiring access to the server and the account's current password.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
[[ $# == 1 && "$1" =~ ^[a-zA-Z0-9_]{3,40}$ ]] || { echo 'Usage: bash scripts/reset-mfa.sh USERNAME' >&2; exit 1; }
read -r -s -p 'Current account password: ' CN_OPERATOR_PASSWORD
printf '\n'
export CN_OPERATOR_PASSWORD
running=$(docker compose ps --status running --services)
restart=()
for service in server web; do if grep -qx "$service" <<< "$running"; then restart+=("$service"); fi; done
cleanup() {
  result=$?
  unset CN_OPERATOR_PASSWORD
  trap - EXIT
  if (( ${#restart[@]} )); then docker compose start "${restart[@]}" || result=1; fi
  exit "$result"
}
trap cleanup EXIT
docker compose stop web server
docker compose run --rm --no-deps -e CN_OPERATOR_PASSWORD server \
  --server.port=0 --cloudnest.maintenance=true --cloudnest.action=reset-mfa \
  "--cloudnest.operator-username=$1"
