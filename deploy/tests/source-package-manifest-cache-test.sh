#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

RESOLVED_VERSION=1.0.0-test.1
RESOLVED_MANIFEST_PATH="${TMP_ROOT}/latest.json"
AID_SOURCE_BUILD_MODE=host
TRACE_FILE="${TMP_ROOT}/builds.log"
export TRACE_FILE

TEST_BUILDER="${TMP_ROOT}/builder.sh"
cat > "${TEST_BUILDER}" <<'BUILDER'
#!/usr/bin/env sh
set -eu
output=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --output) output="$2"; shift 2 ;;
    *) shift ;;
  esac
done
printf 'build\n' >> "${TRACE_FILE}"
printf 'rebuilt package\n' > "${output}"
BUILDER

bootstrap_source_builder() { SOURCE_BUILDER_PATH="${TEST_BUILDER}"; }
prepare_source_build_images() { :; }
dependency_region_setting() { echo cn; }
package_is_source_build() { return 0; }
source_package_cache_matches_current_contract() { return 0; }
validate_release_package() { return 0; }
stat() {
  if [[ "$1" == "-c" && "$2" == "%u:%a" ]]; then
    echo '0:600'
  else
    command stat "$@"
  fi
}
ok() { :; }
warn() { :; }
risk() { :; }
section() { :; }
log() { :; }

package="${DATA_ROOT}/packages/aid-v${RESOLVED_VERSION}.tar.gz"
checksum="${package}.sha256"
fingerprint="${package}.manifest.sha256"

seed_cache() {
  local manifestFingerprint
  mkdir -p "$(dirname "${package}")"
  printf 'cached package\n' > "${package}"
  printf '%s  %s\n' "$(sha256_file "${package}")" "$(basename "${package}")" > "${checksum}"
  manifestFingerprint="$(sha256_file "${RESOLVED_MANIFEST_PATH}")"
  printf '%s\n' "${manifestFingerprint}" > "${fingerprint}"
  : > "${TRACE_FILE}"
}

printf '{"signed":"first"}\n' > "${RESOLVED_MANIFEST_PATH}"
seed_cache
ensure_source_package
[[ ! -s "${TRACE_FILE}" ]] \
  || { echo 'FAIL: matching signed manifest did not reuse source package cache' >&2; exit 1; }

rm -f "${fingerprint}"
: > "${TRACE_FILE}"
ensure_source_package
[[ "$(wc -l < "${TRACE_FILE}")" -eq 1 && -f "${fingerprint}" ]] \
  || { echo 'FAIL: legacy cache without manifest fingerprint was not rebuilt' >&2; exit 1; }

seed_cache
printf '{"signed":"replacement"}\n' > "${RESOLVED_MANIFEST_PATH}"
ensure_source_package
[[ "$(wc -l < "${TRACE_FILE}")" -eq 1 ]] \
  || { echo 'FAIL: same-version replacement manifest did not invalidate source cache' >&2; exit 1; }
[[ "$(awk 'NR == 1 {print $1}' "${fingerprint}")" == "$(sha256_file "${RESOLVED_MANIFEST_PATH}")" ]] \
  || { echo 'FAIL: rebuilt cache did not record current manifest fingerprint' >&2; exit 1; }

# 显式本地发布包是离线入口，不依赖远程清单，也不创建远程清单指纹。
localPackage="${TMP_ROOT}/aid-v1.2.3.tar.gz"
printf 'local package\n' > "${localPackage}"
unset RESOLVED_MANIFEST_PATH
AID_TRUSTED_SOURCE_PACKAGE=1 prepare_install_package "${localPackage}"
[[ "${RESOLVED_PACKAGE_PATH}" == "${localPackage}" ]] \
  || { echo 'FAIL: local package path was changed by manifest cache policy' >&2; exit 1; }

echo 'source package manifest cache tests passed'
