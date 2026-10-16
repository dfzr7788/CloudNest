#!/usr/bin/env bash
# Run after docker compose up -d --build. Password is never a command argument.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
if [[ $# -ne 1 ]]; then
  echo 'Usage: bash scripts/create-admin.sh USERNAME' >&2
  exit 1
fi
command -v docker >/dev/null
docker compose config --quiet
read -r -s -p 'Admin password: ' CN_OPERATOR_PASSWORD
printf '\n'
read -r -s -p 'Repeat password: ' confirmation
printf '\n'
[[ "$CN_OPERATOR_PASSWORD" == "$confirmation" && -n "$CN_OPERATOR_PASSWORD" ]] || {
  echo 'Passwords must be nonempty and match.' >&2; exit 1;
}
unset confirmation
export CN_OPERATOR_PASSWORD
# Restart only services that were running before this maintenance operation.
running=$(docker compose ps --status running --services)
restart=()
for service in server web; do
  if grep -qx "$service" <<< "$running"; then restart+=("$service"); fi
done
cleanup() {
  result=$?
  unset CN_OPERATOR_PASSWORD
  trap - EXIT
  if (( ${#restart[@]} )); then
    docker compose start "${restart[@]}" || result=1
  fi
  exit "$result"
}
trap cleanup EXIT
docker compose stop web server
docker compose run --rm --no-deps -e CN_OPERATOR_PASSWORD server \
  --server.port=0 --cloudnest.maintenance=true --cloudnest.action=create-admin \
  "--cloudnest.operator-username=$1"
