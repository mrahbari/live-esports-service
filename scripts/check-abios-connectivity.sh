#!/usr/bin/env bash
#
# If WSL says: /usr/bin/env: 'bash\r' or $'\r': command not found, run from repo root:
#   python3 scripts/fix_sh_line_endings.py
#
# Sanity-check outbound HTTPS toward Abios (curl only). Does not run the JVM.
# Loads ABIOS_API_KEY from environment or repo root ".env".
#
# Probes:
#   A) application.yml style: api.abiosgaming.com + Bearer
#   B) Atlas docs style: atlas.abiosgaming.com + Abios-Secret
#
# Usage (from repo root, use YOUR path not a placeholder):
#   bash ./scripts/check-abios-connectivity.sh
# Env:
#   ABIOS_PROBE_MODE=all|case|docs    (default: all)
#
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

abios_trim_quotes() {
  local val=$1
  val="${val#"${val%%[![:space:]]*}"}"
  val="${val%"${val##*[![:space:]]}"}"
  local n=${#val}
  if [[ "${n}" -ge 2 && "${val:0:1}" == "'" && "${val: -1}" == "'" ]]; then
    val="${val:1:$((n - 2))}"
  fi
  n=${#val}
  if [[ "${n}" -ge 2 && "${val:0:1}" == '"' && "${val: -1}" == '"' ]]; then
    val="${val:1:$((n - 2))}"
  fi
  printf '%s' "${val}"
}

abios_try_load_dotenv() {
  local envf="${ROOT_DIR}/.env"
  [[ -f "${envf}" ]] || return 1
  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line//$'\r'/}"
    [[ "${line}" =~ ^[[:space:]]*# ]] && continue
    [[ "${line}" =~ ^[[:space:]]*$ ]] && continue
    if [[ "${line}" =~ ^ABIOS_API_KEY[[:space:]]*=[[:space:]]*(.*)$ ]]; then
      ABIOS_API_KEY="$(abios_trim_quotes "${BASH_REMATCH[1]}")"
      export ABIOS_API_KEY
      return 0
    fi
  done < "${envf}"
  return 1
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

require_curl() {
  command -v curl >/dev/null 2>&1 || die "curl is required"
}

dns_check() {
  local host=$1
  printf '  DNS %-29s ' "${host}:"
  if command -v getent >/dev/null 2>&1; then
    if getent hosts "${host}" >/dev/null 2>&1; then
      echo "OK"
      return 0
    fi
    echo "FAIL (getent hosts)"
    return 1
  fi
  if command -v dig >/dev/null 2>&1; then
    if dig +short "${host}" 2>/dev/null | grep -q .; then
      echo "OK"
      return 0
    fi
    echo "FAIL (dig)"
    return 1
  fi
  echo "(skip: install getent or dig)"
  return 0
}

probe_get() {
  local name=$1 url=$2 curl_ec
  shift 2
  local code tempfile
  tempfile="$(mktemp)"
  code=$(curl -sS -o "${tempfile}" -w "%{http_code}" \
    --connect-timeout 8 --max-time 25 "$@" "${url}") && curl_ec=0 || curl_ec=$?
  if [[ "${curl_ec}" -ne 0 ]]; then
    rm -f "${tempfile}"
    echo "  ${name}: CURL_FAILED exit=${curl_ec} (TCP/TLS/DNS/firewall)"
    return 2
  fi
  printf '  %s: HTTP %s\n' "${name}" "${code}"
  head -c 280 "${tempfile}" 2>/dev/null | tr '\n' ' ' | sed 's/[[:space:]]\+/ /g'
  echo
  rm -f "${tempfile}"
  if [[ "${code}" =~ ^2 ]]; then
    echo "    -> reachable with this host + auth style"
    return 0
  fi
  if [[ "${code}" == "401" || "${code}" == "403" ]]; then
    echo "    -> TCP OK; auth/forbidden or key mismatch for this probe"
    return 1
  fi
  if [[ "${code}" == "404" ]]; then
    echo "    -> TCP OK; path/query may differ from your Atlas plan"
    return 1
  fi
  echo "    -> HTTP not 2xx; check Atlas docs / case onboarding"
  return 1
}

PROBE_MODE="${ABIOS_PROBE_MODE:-all}"
if [[ "${PROBE_MODE}" != "all" && "${PROBE_MODE}" != "case" && "${PROBE_MODE}" != "docs" ]]; then
  die "ABIOS_PROBE_MODE must be all, case, or docs (got: ${PROBE_MODE})"
fi

if [[ -z "${ABIOS_API_KEY:-}" ]]; then
  abios_try_load_dotenv || true
fi

require_curl

echo "==> Repo: ${ROOT_DIR}"
if [[ -z "${ABIOS_API_KEY:-}" ]]; then
  die "No ABIOS_API_KEY. Set env or add ABIOS_API_KEY=... to .env (never commit)."
fi
echo "==> ABIOS_API_KEY loaded (chars: ${#ABIOS_API_KEY}) - value not echoed"

echo "==> DNS"
dns_check api.abiosgaming.com || true
dns_check atlas.abiosgaming.com || true

echo "==> HTTPS probes (${PROBE_MODE})"

url_case="${url_case:-https://api.abiosgaming.com/v1/series?state=live}"
url_docs="${url_docs:-https://atlas.abiosgaming.com/v3/series}"

success=0
if [[ "${PROBE_MODE}" == "all" || "${PROBE_MODE}" == "case" ]]; then
  echo "-- Case-study yaml style: Bearer + api host"
  if probe_get case-study-api "${url_case}" -H "Authorization: Bearer ${ABIOS_API_KEY}" -H "Accept: application/json"; then
    success=1
  fi
fi

if [[ "${PROBE_MODE}" == "all" || "${PROBE_MODE}" == "docs" ]]; then
  echo "-- Atlas public docs style: Abios-Secret + atlas host"
  if probe_get atlas-docs-secret "${url_docs}" -H "Abios-Secret: ${ABIOS_API_KEY}" -H "Accept: application/json"; then
    success=1
  fi
fi

echo "==> Summary"
if [[ "${success}" -eq 1 ]]; then
  echo "PASS: At least one probe returned HTTP 2xx."
  echo "     Point Java application.yml/atlas.yml to whichever probe matched."
else
  echo "GAP: No 2xx. If BOTH probes CURL_FAILED, fix outbound network/Docker DNS."
  echo "     If TCP works (401/404), adjust base-url, path, and header vs case pack."
  exit 2
fi
