#!/usr/bin/env bash
# Prove per-IP Bucket4j returns 429 + Retry-After after exhausting burst.
# Uses X-Forwarded-For with a TEST-NET IP so your own IP bucket stays clean.
#
# Default limits in application.yml: burst 120 / minute → SMOKE_RATE_BURST defaults to 135.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=smoke-common.sh
. "${SCRIPT_DIR}/smoke-common.sh"

require_curl

: "${SMOKE_FAKE_CLIENT_IP:=198.51.100.77}"
: "${SMOKE_RATE_BURST:=135}"
url="${BASE_URL}/v1/series"
hdrf="${TMPDIR:-/tmp}/abios-smoke-hdr.$$"
rm -f "$hdrf"
trap 'rm -f "$hdrf"' EXIT

first=$(curl -sS -o /dev/null -w "%{http_code}" -H "X-Forwarded-For: ${SMOKE_FAKE_CLIENT_IP}" "$url")
[[ "$first" == "200" ]] || die "expected first request 200 got $first"

got429=0
for ((i = 2; i <= SMOKE_RATE_BURST; i++)); do
  code=$(curl -sS -o /dev/null -w "%{http_code}" -D "$hdrf" -H "X-Forwarded-For: ${SMOKE_FAKE_CLIENT_IP}" "$url")
  if [[ "$code" == "429" ]]; then
    got429=1
    ra=$(grep -i '^Retry-After:' "$hdrf" | head -1 | cut -d' ' -f2- | tr -d '\r' | sed 's/^[[:space:]]*//')
    [[ -n "${ra:-}" ]] || die "429 without Retry-After header"
    break
  fi
done

[[ "$got429" -eq 1 ]] ||
  die "never received 429 after ${SMOKE_RATE_BURST} requests (raise SMOKE_RATE_BURST or set APP_LIVE_CLIENT_BURST_CAPACITY lower in compose)"

ok "per-IP rate limit returns 429 + Retry-After"
