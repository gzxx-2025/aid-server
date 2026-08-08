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

# Docker 在确认部署前会完成配置、源码构建、镜像准备与安装器提取。
mkdir -p "${AID_DATA_ROOT}"/{packages,installer,config,source-build,build-cache,logs}
touch "${AID_DATA_ROOT}/aid-deploy.conf"
assert_no_unmanaged_entry docker

# 没有 AID 所有权证据时，单独存在的 runtime 仍必须按未知内容处理。
mkdir -p "${AID_DATA_ROOT}/runtime"
assert_unmanaged_entry docker "${AID_DATA_ROOT}/runtime"
assert_unmanaged_entry manual "${AID_DATA_ROOT}/runtime"
rm -rf "${AID_DATA_ROOT}/runtime"

# 配置阶段写入强所有权证据后，Docker/手动部署都必须允许 AID 的标准目录。
# 这保证源码构建、失败重试或部署方式切换留下的受管内容不会被误报。
mkdir -p "${AID_DATA_ROOT}/config"
cat > "${AID_DATA_ROOT}/config/.aid-managed" <<EOF
AID_MANAGED_ROOT=1
AID_DATA_ROOT=${AID_DATA_ROOT}
AID_MANAGER_SCRIPT=${AID_DATA_ROOT}/installer/deploy/aid.sh
EOF
mkdir -p "${AID_DATA_ROOT}"/{app,backups,runtime,run,mysql-data,redis-data,rocketmq,uploadPath,uploadPath-private,mysql-data-manual,mysql-files,redis-data-manual,.installer-extract.retry}
touch "${AID_DATA_ROOT}/aid-nginx.conf"
assert_no_unmanaged_entry docker
assert_no_unmanaged_entry manual

mkdir -p "${AID_DATA_ROOT}/other-business"
assert_unmanaged_entry docker "${AID_DATA_ROOT}/other-business"

rm -rf "${AID_DATA_ROOT}/other-business" "${AID_DATA_ROOT}/config"
if ln -s "${TMP_ROOT}" "${AID_DATA_ROOT}/config" 2>/dev/null; then
  assert_unmanaged_entry docker "${AID_DATA_ROOT}/config"
else
  echo 'SKIP: filesystem does not provide POSIX symbolic links; first-install symlink check skipped'
fi

echo 'first install content tests passed'
