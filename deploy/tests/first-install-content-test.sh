#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_DATA_ROOT="${TMP_ROOT}/data"
export AID_SH_LIBRARY_MODE=1
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

assert_no_unmanaged_entry() {
  local mode="$1" actual
  actual="$(first_install_unmanaged_entry "${mode}" || true)"
  [[ -z "${actual}" ]] || {
    echo "FAIL: ${mode} 模式误判 AID 自有目录: ${actual}" >&2
    exit 1
  }
}

assert_unmanaged_entry() {
  local mode="$1" expected="$2" actual
  actual="$(first_install_unmanaged_entry "${mode}" || true)"
  [[ "${actual}" == "${expected}" ]] || {
    echo "FAIL: ${mode} 模式未识别未知内容，实际: ${actual:-<空>}" >&2
    exit 1
  }
}

assert_unmanaged_manual_runtime_entry() {
  local actual
  actual="$(first_install_unmanaged_entry docker || true)"
  case "${actual}" in
    "${AID_DATA_ROOT}/runtime"|"${AID_DATA_ROOT}/mysql-data-manual"|\
    "${AID_DATA_ROOT}/mysql-files"|"${AID_DATA_ROOT}/redis-data-manual"|"${AID_DATA_ROOT}/run") ;;
    *)
      echo "FAIL: docker 模式未识别手动部署目录，实际: ${actual:-<空>}" >&2
      exit 1
      ;;
  esac
}

# Docker 在确认部署前会完成配置、源码构建、镜像准备与安装器提取。
mkdir -p "${AID_DATA_ROOT}"/{packages,installer,config,source-build,build-cache,logs}
touch "${AID_DATA_ROOT}/aid-deploy.conf"
assert_no_unmanaged_entry docker

# 手动部署还会提前准备受管 MySQL/Redis 的运行目录。
mkdir -p "${AID_DATA_ROOT}"/{runtime,mysql-data-manual,mysql-files,redis-data-manual,run}
assert_no_unmanaged_entry manual

# Docker 不应把仅属于手动部署的运行目录静默放行。find 的目录返回顺序不固定，
# 因此接受任意一个被正确识别出的手动部署目录，避免依赖具体文件系统顺序。
assert_unmanaged_manual_runtime_entry

rm -rf "${AID_DATA_ROOT}/runtime" "${AID_DATA_ROOT}/mysql-data-manual" \
  "${AID_DATA_ROOT}/mysql-files" "${AID_DATA_ROOT}/redis-data-manual" "${AID_DATA_ROOT}/run"
mkdir -p "${AID_DATA_ROOT}/other-business"
assert_unmanaged_entry docker "${AID_DATA_ROOT}/other-business"

rm -rf "${AID_DATA_ROOT}/other-business" "${AID_DATA_ROOT}/config"
if ln -s "${TMP_ROOT}" "${AID_DATA_ROOT}/config" 2>/dev/null; then
  assert_unmanaged_entry docker "${AID_DATA_ROOT}/config"
else
  echo 'SKIP: filesystem does not provide POSIX symbolic links; first-install symlink check skipped'
fi

echo 'first install content tests passed'
