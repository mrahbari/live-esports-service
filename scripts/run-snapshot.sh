#!/usr/bin/env bash
# Runs only Scenario A (V1 - Snapshot Track).
# Disables Scenario B (V2 Polling) and silences its logs.
# Usage: [SPRING_PROFILES_ACTIVE=atlas] ./scripts/run-snapshot.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

export APP_POLLING_ENABLED=false
export LOG_V2_LEVEL=OFF
export SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-atlas}

echo "Starting V1 Snapshot Track only..."
echo "- Polling: DISABLED"
echo "- V2 Logs: SILENCED"
echo "- Profile: ${SPRING_PROFILES_ACTIVE}"

./scripts/run.sh
