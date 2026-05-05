#!/usr/bin/env bash
# Runs only Scenario B (V2 - Polling Track).
# Disables Scenario A (V1 Snapshot background refresh) and silences its logs.
# Usage: [SPRING_PROFILES_ACTIVE=atlas] ./scripts/run-polling.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

export APP_LIVE_BACKGROUND_REFRESH_ENABLED=false
export LOG_V1_LEVEL=OFF
export SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-atlas}

echo "Starting V2 Polling Track only..."
echo "- V1 Background Refresh: DISABLED"
echo "- V1 Logs: SILENCED"
echo "- Profile: ${SPRING_PROFILES_ACTIVE}"

./scripts/run.sh
