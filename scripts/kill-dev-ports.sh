#!/usr/bin/env bash
# Free TCP port(s) on the host so docker compose / dev servers can bind (avoids EADDRINUSE).
# Default: 8080 (maps to app in docker-compose.yml).
# Usage: ./scripts/kill-dev-ports.sh [PORT ...]

set -euo pipefail

if [[ $# -eq 0 ]]; then
  PORTS=(8080 6379)
else
  PORTS=("$@")
fi

echo "==> Freeing port(s): ${PORTS[*]}"

for p in "${PORTS[@]}"; do
  # Special handling for Redis if it's a system service
  if [[ "$p" == "6379" ]]; then
    if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet redis-server; then
      echo "    Stopping redis-server system service..."
      systemctl stop redis-server || true
    elif command -v service >/dev/null 2>&1 && service redis-server status >/dev/null 2>&1; then
      echo "    Stopping redis-server system service..."
      service redis-server stop || true
    fi
  fi

  if command -v fuser >/dev/null 2>&1; then
    fuser -k "${p}/tcp" 2>/dev/null || true
  elif command -v lsof >/dev/null 2>&1; then
    pids="$(lsof -ti:"$p" -sTCP:LISTEN 2>/dev/null || true)"
    if [[ -n "${pids:-}" ]]; then
      # shellcheck disable=SC2086
      kill -9 $pids 2>/dev/null || true
    fi
  else
    echo "    WARN: install fuser (often in psmisc) or lsof to free port $p on this OS." >&2
  fi
done
