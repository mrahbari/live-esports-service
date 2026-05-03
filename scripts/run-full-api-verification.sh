#!/usr/bin/env bash
# Full manual verification against a *running* app: all smoke checks + data-source hint.
#
# Prerequisite: service up, e.g.  bash ./scripts/run.sh  (mock default)
#                or            bash ./scripts/run-atlas.sh  (Atlas — same env as SPRING_PROFILES_ACTIVE=atlas + run.sh)
#
# Usage:
#   bash ./scripts/run-full-api-verification.sh
#
# Environment (optional):
#   BASE_URL=http://127.0.0.1:8080
#   SMOKE_SKIP_RATE_LIMIT=1          — skip chatty 429 burst (faster)
#   STRICT_CLASSPATH_MOCK=1          — fail unless response matches bundled mock fixture
#                                    (use when you expect default mock; not for Atlas)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=smoke-common.sh
. "${SCRIPT_DIR}/smoke-common.sh"

require_curl

echo "==> Full API verification (smoke + mock fingerprint hint)"
echo "==> Target: ${BASE_URL}"
"${SCRIPT_DIR}/run-smoke-tests.sh"

url="${BASE_URL}/series/live"
body=$(json_get "$url")

if echo "$body" | grep -q 'ser-1001'; then
  echo "OK:     Payload matches bundled classpath mock fingerprint (seriesId ser-1001)."
  echo "        → Default mock mode is likely active (see README «Mock vs real data»)."
else
  echo "NOTE:  No ser-1001 in /series/live — not the bundled classpath mock fixture."
  echo "        → Likely atlas profile, or ABIOS_MOCK_FILE / custom data — OK if intended."
  if [[ "${STRICT_CLASSPATH_MOCK:-}" == "1" ]]; then
    echo "SMOKE FAIL: STRICT_CLASSPATH_MOCK=1 but series response is not classpath mock." >&2
    exit 1
  fi
fi

echo "==> Full API verification finished."
