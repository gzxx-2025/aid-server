#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

assert_preexisting_snapshot() { # assert_preexisting_snapshot <file|symlink>
  local kind="$1" root entry
  root="${TMP_ROOT}/snapshot-${kind}"
  entry="${root}/foreign-${kind}"
  mkdir -p "${root}"
  if [[ "${kind}" == "file" ]]; then
    printf 'foreign\n' > "${entry}"
  else
    ln -s "${TMP_ROOT}" "${entry}" 2>/dev/null \
      || { echo 'SKIP: filesystem does not provide POSIX symbolic links; startup snapshot symlink check skipped'; return 0; }
  fi
  AID_DATA_ROOT="${root}" AID_SH_LIBRARY_MODE=1 bash -c '
    set -euo pipefail
    source "$1"
    mkdir -p "${AID_DATA_ROOT}/config"
    cat > "${AID_DATA_ROOT}/config/.aid-managed" <<EOF
AID_MANAGED_ROOT=1
AID_DATA_ROOT=${AID_DATA_ROOT}
AID_MANAGER_SCRIPT=${AID_DATA_ROOT}/installer/deploy/aid.sh
EOF
    actual="$(first_install_unmanaged_entry docker || true)"
    [[ "${actual}" == "$2" ]] || {
      echo "FAIL: startup snapshot lost preexisting entry, actual: ${actual:-<empty>}" >&2
      exit 1
    }
  ' bash "${ROOT_DIR}/deploy/aid.sh" "${entry}"
}

assert_preexisting_snapshot file
assert_preexisting_snapshot symlink

if AID_DATA_ROOT="${TMP_ROOT}/lexical/../aid" AID_SH_LIBRARY_MODE=1 bash -c 'source "$1"' bash "${ROOT_DIR}/deploy/aid.sh" >/dev/null 2>&1; then
  echo 'FAIL: DATA_ROOT containing .. must be rejected' >&2
  exit 1
fi

symlinkRoot="${TMP_ROOT}/symlink-root"
if ln -s "${TMP_ROOT}/snapshot-file" "${symlinkRoot}" 2>/dev/null && [[ -L "${symlinkRoot}" ]]; then
  if AID_DATA_ROOT="${symlinkRoot}/new/aid" AID_SH_LIBRARY_MODE=1 bash -c 'source "$1"' bash "${ROOT_DIR}/deploy/aid.sh" >/dev/null 2>&1; then
    echo 'FAIL: DATA_ROOT below a symbolic-link parent must be rejected' >&2
    exit 1
  fi
else
  echo 'SKIP: filesystem does not provide POSIX symbolic links; DATA_ROOT symlink check skipped'
fi

export AID_DATA_ROOT="${TMP_ROOT}/data"
# 先于 source 制造未知目录，验证启动快照不会被本轮后来写入的 AID 标记洗白。
mkdir -p "${AID_DATA_ROOT}/runtime"
export AID_SH_LIBRARY_MODE=1
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

is_safe_aid_data_root_candidate /data/aid \
  || { echo 'FAIL: standard /data/aid path was rejected' >&2; exit 1; }
is_safe_aid_data_root_candidate /mnt/aid \
  || { echo 'FAIL: standard /mnt/aid path was rejected' >&2; exit 1; }

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

# 即使本轮随后写入了合法标记，启动前既有的未知 runtime 仍必须显式提醒。
mkdir -p "${AID_DATA_ROOT}/config"
cat > "${AID_DATA_ROOT}/config/.aid-managed" <<EOF
AID_MANAGED_ROOT=1
AID_DATA_ROOT=${AID_DATA_ROOT}
AID_MANAGER_SCRIPT=${AID_DATA_ROOT}/installer/deploy/aid.sh
EOF
assert_unmanaged_entry docker "${AID_DATA_ROOT}/runtime"
assert_unmanaged_entry manual "${AID_DATA_ROOT}/runtime"

# 从这里开始模拟脚本在真正空目录上的全新运行。
rm -rf "${AID_DATA_ROOT}"
AID_DATA_ROOT_OWNED_ON_ENTRY=0
AID_DATA_ROOT_UNMANAGED_ON_ENTRY=""

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
