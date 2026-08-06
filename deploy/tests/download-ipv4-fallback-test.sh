#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

printf 'IPv4 fallback fixture\n' > "${TMP_ROOT}/source.bin"
expected="$(sha256sum "${TMP_ROOT}/source.bin" | awk '{print $1}')"

CURL_CALL_SEQUENCE=""
curl() {
  local output='' ipv4=no arg
  while (($#)); do
    arg="$1"; shift
    case "${arg}" in
      --output) output="$1"; shift ;;
      --ipv4) ipv4=yes ;;
    esac
  done
  CURL_CALL_SEQUENCE+="${ipv4} "
  [[ "${ipv4}" == "yes" ]] || return 7
  cp "${TMP_ROOT}/source.bin" "${output}"
}

try_download 'https://repo.example/test.bin' "${TMP_ROOT}/curl-success.bin" 'curl IPv4 兜底测试' \
  sha256 "${expected}" >/dev/null
[[ "${CURL_CALL_SEQUENCE}" == 'no yes ' ]] \
  || { echo "FAIL: curl must retry with IPv4 after the dual-stack request fails: ${CURL_CALL_SEQUENCE}" >&2; exit 1; }
file_digest_matches "${TMP_ROOT}/curl-success.bin" sha256 "${expected}" \
  || { echo 'FAIL: curl IPv4 retry output must pass the fixed digest check' >&2; exit 1; }

CURL_FAILURE_CALLS=0
curl() {
  CURL_FAILURE_CALLS=$((CURL_FAILURE_CALLS + 1))
  return 7
}
if try_download 'https://repo.example/fail.bin' "${TMP_ROOT}/curl-fail.bin" 'curl 双失败测试' >/dev/null 2>&1; then
  echo 'FAIL: curl download must fail when dual-stack and IPv4 attempts both fail' >&2
  exit 1
fi
[[ "${CURL_FAILURE_CALLS}" == '2' ]] \
  || { echo "FAIL: curl double failure must include one IPv4 retry: ${CURL_FAILURE_CALLS}" >&2; exit 1; }
[[ ! -e "${TMP_ROOT}/curl-fail.bin" ]] \
  || { echo 'FAIL: failed curl download must not publish a target file' >&2; exit 1; }

# Force the common downloader onto wget while retaining the shell's normal command lookup for all other tools.
command() {
  if [[ "${1:-}" == '-v' && "${2:-}" == 'curl' ]]; then
    return 1
  fi
  builtin command "$@"
}
WGET_CALL_SEQUENCE=""
wget() {
  local output='' ipv4=no arg
  if [[ "${1:-}" == '--help' ]]; then
    printf '%s\n' '--https-only' '--secure-protocol'
    return 0
  fi
  while (($#)); do
    arg="$1"; shift
    case "${arg}" in
      --output-document=*) output="${arg#*=}" ;;
      --inet4-only) ipv4=yes ;;
    esac
  done
  WGET_CALL_SEQUENCE+="${ipv4} "
  [[ "${ipv4}" == 'yes' ]] || return 7
  cp "${TMP_ROOT}/source.bin" "${output}"
}

try_download 'https://repo.example/wget.bin' "${TMP_ROOT}/wget-success.bin" 'wget IPv4 兜底测试' \
  sha256 "${expected}" >/dev/null
[[ "${WGET_CALL_SEQUENCE}" == 'no yes ' ]] \
  || { echo "FAIL: wget must retry with IPv4 after the dual-stack request fails: ${WGET_CALL_SEQUENCE}" >&2; exit 1; }
file_digest_matches "${TMP_ROOT}/wget-success.bin" sha256 "${expected}" \
  || { echo 'FAIL: wget IPv4 retry output must pass the fixed digest check' >&2; exit 1; }

if try_download 'http://repo.example/insecure.bin' "${TMP_ROOT}/insecure.bin" 'HTTP 拒绝测试' >/dev/null 2>&1; then
  echo 'FAIL: IPv4 fallback must not permit an insecure HTTP URL' >&2
  exit 1
fi
[[ ! -e "${TMP_ROOT}/insecure.bin" ]] \
  || { echo 'FAIL: rejected HTTP download must not create a target file' >&2; exit 1; }

echo 'download IPv4 fallback tests passed'
