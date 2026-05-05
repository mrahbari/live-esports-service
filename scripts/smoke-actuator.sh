#!/usr/bin/env bash
# Actuator health group endpoints (when exposed).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=smoke-common.sh
. "${SCRIPT_DIR}/smoke-common.sh"

require_curl

for path in /actuator/health /actuator/health/liveness /actuator/health/readiness; do
  url="${BASE_URL}${path}"
  http_get_expect "$url" 200
  body=$(json_get "$url")
  if command -v jq >/dev/null 2>&1; then
    echo "$body" | jq -e '.status' >/dev/null 2>&1 || die "no .status on $path"
  else
    echo "$body" | grep -q '"status"' || die "no status field on $path"
  fi
  ok "$path"
done
