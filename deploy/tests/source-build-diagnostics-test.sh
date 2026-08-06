#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

build_log="${TMP_ROOT}/source-build.log"
for line in $(seq 1 200); do printf '普通日志 %s\n' "${line}"; done > "${build_log}"
printf '[ERROR] UNIQUE-SOURCE-BUILD-FAILURE\n' >> "${build_log}"
output="$(source_build_failure_diagnostics "${build_log}" 2>&1)"
[[ "${output}" == *'UNIQUE-SOURCE-BUILD-FAILURE'* && "${output}" == *"${build_log}"* ]] \
  || { echo 'FAIL: source build failure must print the useful log tail and full path' >&2; exit 1; }

builder_body="$(declare -f bootstrap_source_builder)"
[[ "${builder_body}" == *'for sourceRef in master "v${RESOLVED_VERSION}"'* ]] \
  || { echo 'FAIL: installer must prefer the latest public source builder' >&2; exit 1; }

builder_file="${ROOT_DIR}/deploy/build-release-from-source.sh"
count="$(grep -c -- '--no-transfer-progress' "${builder_file}")"
[[ "${count}" -ge 3 ]] \
  || { echo 'FAIL: every Maven build path must suppress transfer progress noise' >&2; exit 1; }

echo 'source build diagnostics tests passed'
