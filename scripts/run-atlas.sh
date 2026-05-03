#!/usr/bin/env bash
# Same as run.sh, but Spring profile "atlas" → application-atlas.yml (mock off, real Atlas HTTP if ABIOS_API_KEY is set).
# Equivalent: SPRING_PROFILES_ACTIVE=atlas ./scripts/run.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

if docker compose version >/dev/null 2>&1; then
  COMPOSE_CMD=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_CMD=(docker-compose)
else
  echo "Docker Compose is not available. Install docker compose v2 or docker-compose." >&2
  exit 1
fi

"${COMPOSE_CMD[@]}" down --remove-orphans >/dev/null 2>&1 || true

# shellcheck disable=SC2086
"${ROOT_DIR}/scripts/kill-dev-ports.sh" ${KILL_DEV_PORTS:-}

SPRING_PROFILES_ACTIVE=atlas "${COMPOSE_CMD[@]}" up --build --force-recreate
