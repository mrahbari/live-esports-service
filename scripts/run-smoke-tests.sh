#!/usr/bin/env bash
# Run all HTTP smoke checks against a running app (mock or real).
# Prereq: service listening (e.g. ./scripts/run.sh). Optional: export BASE_URL=http://host:port
#
# Steps:
#   1) smoke-live-endpoints.sh
#   2) smoke-actuator.sh
#   3) smoke-meta-contract.sh
#   4) smoke-rate-limit.sh (set SMOKE_SKIP_RATE_LIMIT=1 to skip — test is chatty)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=smoke-common.sh
. "${SCRIPT_DIR}/smoke-common.sh"

require_curl

echo "==> Smoke base URL: ${BASE_URL}"
echo "==> Live endpoints + JSON shape"
"${SCRIPT_DIR}/smoke-live-endpoints.sh"
echo "==> Actuator probes"
"${SCRIPT_DIR}/smoke-actuator.sh"
echo "==> Meta contract"
"${SCRIPT_DIR}/smoke-meta-contract.sh"

if [[ "${SMOKE_SKIP_RATE_LIMIT:-}" == "1" ]]; then
  echo "==> Skipping rate-limit burst (SMOKE_SKIP_RATE_LIMIT=1)"
else
  echo "==> Per-IP rate limit (many requests; isolated X-Forwarded-For IP)"
  "${SCRIPT_DIR}/smoke-rate-limit.sh"
fi

echo "==> All smoke tests passed."
