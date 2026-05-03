#!/usr/bin/env bash
# GET /series/live, /players/live, /teams/live — expect 200 and JSON with meta + items.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=smoke-common.sh
. "${SCRIPT_DIR}/smoke-common.sh"

require_curl

for path in /v1/series /v1/players /v1/teams; do
  url="${BASE_URL}${path}"
  http_get_expect "$url" 200
  body=$(json_get "$url")
  body_has_meta_fields "$body" || die "missing meta fields on $path"
  body_has_items_array "$body" || die "missing items array on $path"
  ok "$path"
done
