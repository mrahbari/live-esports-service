#!/usr/bin/env bash
# Docker Compose stack. Mock by default (`docker-compose.yml` uses SPRING_PROFILES_ACTIVE=${...:-default}).
# Atlas (mock off): ./scripts/run-atlas.sh  or  SPRING_PROFILES_ACTIVE=atlas bash ./scripts/run.sh  (needs ABIOS_API_KEY).
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

# Free listeners on published host ports. Defaults to 8080 and 6379; override example: KILL_DEV_PORTS="8080 8443".
# shellcheck disable=SC2086
"${ROOT_DIR}/scripts/kill-dev-ports.sh" ${KILL_DEV_PORTS:-}

"${COMPOSE_CMD[@]}" up --build --force-recreate
