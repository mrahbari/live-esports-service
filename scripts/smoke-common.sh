#!/usr/bin/env bash
# Shared helpers for smoke scripts. Source from same directory:
#   SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)" && . "${SCRIPT_DIR}/smoke-common.sh"

: "${BASE_URL:=http://127.0.0.1:8080}"

die() {
  echo "SMOKE FAIL: $*" >&2
  exit 1
}

ok() {
  echo "SMOKE OK:   $*"
}

require_curl() {
  command -v curl >/dev/null 2>&1 || die "curl is required"
}

http_get_expect() {
  local url=$1
  local want=$2
  local code
  code=$(curl -sS -o /dev/null -w "%{http_code}" "$url") || die "curl failed: $url"
  [[ "$code" == "$want" ]] || die "HTTP $code expected $want for $url"
}

json_get() {
  local url=$1
  curl -sS -f "$url" || die "GET failed or non-2xx: $url"
}

body_has_meta_fields() {
  local json=$1
  if command -v jq >/dev/null 2>&1; then
    echo "$json" | jq -e '.meta | has("fetchedAt") and has("stale") and has("degraded") and has("count")' >/dev/null 2>&1
  else
    echo "$json" | grep -q '"meta"' && echo "$json" | grep -q '"fetchedAt"' && echo "$json" | grep -q '"stale"' &&
      echo "$json" | grep -q '"degraded"' && echo "$json" | grep -q '"count"'
  fi
}

body_has_items_array() {
  local json=$1
  if command -v jq >/dev/null 2>&1; then
    echo "$json" | jq -e '.items | type == "array"' >/dev/null 2>&1
  else
    echo "$json" | grep -q '"items"'
  fi
}
