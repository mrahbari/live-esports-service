#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Step 1: Bring down this project's compose stack so Docker releases its port allocations.
# This avoids "port is already allocated" errors when a previous run left containers up.
if docker compose version >/dev/null 2>&1; then
  COMPOSE_CMD=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_CMD=(docker-compose)
fi

if [[ -n "${COMPOSE_CMD[*]:-}" ]]; then
  echo "==> Tearing down compose stack..."
  (cd "${ROOT_DIR}" && "${COMPOSE_CMD[@]}" down --remove-orphans 2>/dev/null) || true
fi

# Step 2: Kill any remaining processes (OS-level and Docker) on the required ports.
"${ROOT_DIR}/scripts/kill-dev-ports.sh"

docker run --rm \
  -v "${ROOT_DIR}:/workspace" \
  -w /workspace \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -q clean
