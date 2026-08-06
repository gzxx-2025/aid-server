#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

cacheDir="${TMP_ROOT}/cache"
archive="${cacheDir}/mysql-5.7.44.tar.gz"
keyFile="${cacheDir}/RPM-GPG-KEY-mysql-2022"
signatureFile="${archive}.asc"
mkdir -p "${cacheDir}"
printf 'archive\n' > "${archive}"
printf 'stale-key\n' > "${keyFile}"
printf 'stale-signature\n' > "${signatureFile}"

KEY_DOWNLOADS=0
SIGNATURE_DOWNLOADS=0
ensure_host_command() { :; }
try_download() {
  case "$1" in
    *RPM-GPG-KEY-mysql-2022)
      printf 'official-key\n' > "$2"
      KEY_DOWNLOADS=$((KEY_DOWNLOADS + 1)) ;;
    *archives/gpg/*)
      printf 'official-signature\n' > "$2"
      SIGNATURE_DOWNLOADS=$((SIGNATURE_DOWNLOADS + 1)) ;;
    *) return 1 ;;
  esac
}
gpg() {
  case "$*" in
    *--fingerprint*)
      if grep -Fq 'official-key' "${keyFile}"; then
        printf 'fpr:::::::::859BE8D7C586F538430B19C2467B942D3A79BD29:\n'
      else
        printf 'fpr:::::::::0000000000000000000000000000000000000000:\n'
      fi ;;
    *--import*) return 0 ;;
    *--verify*) grep -Fq 'official-signature' "${signatureFile}" ;;
    *) return 1 ;;
  esac
}

verify_mysql_archive_signature "${archive}" "$(basename "${archive}")" "${cacheDir}" >/dev/null
[[ "${KEY_DOWNLOADS}" == "1" ]] \
  || { echo 'FAIL: invalid cached MySQL key must be downloaded exactly once' >&2; exit 1; }
[[ "${SIGNATURE_DOWNLOADS}" == "1" ]] \
  || { echo 'FAIL: invalid cached MySQL signature must be downloaded exactly once' >&2; exit 1; }

KEY_DOWNLOADS=0
SIGNATURE_DOWNLOADS=0
verify_mysql_archive_signature "${archive}" "$(basename "${archive}")" "${cacheDir}" >/dev/null
[[ "${KEY_DOWNLOADS}" == "0" ]] \
  || { echo 'FAIL: verified MySQL key cache must be reused without downloading' >&2; exit 1; }
[[ "${SIGNATURE_DOWNLOADS}" == "0" ]] \
  || { echo 'FAIL: verified MySQL signature cache must be reused without downloading' >&2; exit 1; }

echo 'MySQL signature cache recovery and reuse tests passed'
