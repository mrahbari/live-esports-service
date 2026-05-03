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

echo "==> Cleaning target directory..."
MAX_RETRIES=5
RETRY_COUNT=0
CLEAN_SUCCESS=false

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
  # 1. Try host-level rm -rf (fastest)
  if rm -rf "${ROOT_DIR}/target" 2>/dev/null; then
    CLEAN_SUCCESS=true
    break
  fi
  
  # 2. Try Docker-based rm -rf (handles files owned by root)
  if docker run --rm -v "${ROOT_DIR}:/workspace" -w /workspace busybox rm -rf target 2>/dev/null; then
    CLEAN_SUCCESS=true
    break
  fi

  RETRY_COUNT=$((RETRY_COUNT + 1))
  echo "    Cleaning failed (locked?). Retrying in 1s... ($RETRY_COUNT/$MAX_RETRIES)"
  sleep 1
done

if [ "$CLEAN_SUCCESS" = true ]; then
  echo "    Successfully cleaned target directory."
else
  echo "    ERROR: Failed to clean target directory after $MAX_RETRIES attempts." >&2
  echo "    Please ensure no IDEs or other processes are locking the 'target' folder." >&2
  exit 1
fi
