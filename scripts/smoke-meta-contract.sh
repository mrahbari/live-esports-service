#!/usr/bin/env bash
# Stronger meta contract check on one response (counts non-negative sanity via jq).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=smoke-common.sh
. "${SCRIPT_DIR}/smoke-common.sh"

require_curl

url="${BASE_URL}/v1/series"
body=$(json_get "$url")

if command -v jq >/dev/null 2>&1; then
  count=$(echo "$body" | jq -r '.meta.count')
  [[ "$count" =~ ^[0-9]+$ ]] || die "meta.count not a non-negative integer"
  stale=$(echo "$body" | jq -r '.meta.stale')
  degraded=$(echo "$body" | jq -r '.meta.degraded')
  [[ "$stale" == "true" || "$stale" == "false" ]] || die "meta.stale must be boolean"
  [[ "$degraded" == "true" || "$degraded" == "false" ]] || die "meta.degraded must be boolean"
else
  body_has_meta_fields "$body" || die "meta contract"
fi

if command -v jq >/dev/null 2>&1; then
  len=$(echo "$body" | jq '.items | length')
  ok "meta contract on /v1/series (items.length=${len})"
else
  ok "meta contract on /v1/series"
fi
