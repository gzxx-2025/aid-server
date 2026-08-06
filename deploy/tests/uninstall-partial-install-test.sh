#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_exists() { [[ -e "$1" || -L "$1" ]] || fail "expected path to remain: $1"; }
assert_missing() { [[ ! -e "$1" && ! -L "$1" ]] || fail "expected path to be removed: $1"; }

# 模拟“安装器和配置已落盘，但服务尚未启动”的失败中断状态。
export AID_SH_LIBRARY_MODE=1
export AID_UNINSTALL_TEST_MODE=1
export AID_UNINSTALL_TEST_ROOT="${TMP_ROOT}"
export AID_DATA_ROOT="${TMP_ROOT}/custom/aid"
export AID_SYSTEMD_UNIT_DIR="${TMP_ROOT}/systemd"
export AID_LOCAL_BIN_DIR="${TMP_ROOT}/local-bin"
export AID_UPDATER_CONFIG_DIR="${TMP_ROOT}/etc/aid-updater"
export AID_UPDATER_DATA_DIR="${TMP_ROOT}/var/lib/aid-updater"
export AID_JAVA_PROFILE_FILE="${TMP_ROOT}/profile/aid-java.sh"
export AID_NGINX_SITE_DIRS="${TMP_ROOT}/nginx/conf.d"
export AID_BOOTSTRAP_PATHS="${TMP_ROOT}/bootstrap/aid-install.sh"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

require_root() { :; }
risk() { :; }
warn() { :; }
log() { :; }
ok() { :; }
err() { :; }
section() { :; }
ask() { echo DELETE-AID; }
ask_yes_no() { echo y; }
detect_mode() { echo none; }
select_existing_nginx_runtime() { return 1; }
remove_aid_manual_accounts() { :; }
aid_manual_accounts_present() { return 1; }

prepare_partial_install() {
  rm -rf -- "${DATA_ROOT}" "${AID_SYSTEMD_UNIT_DIR}" "${UPDATER_CONFIG_DIR}" \
    "${UPDATER_DATA_DIR}" "${TMP_ROOT}/nginx" "${TMP_ROOT}/profile"
  mkdir -p "${DATA_ROOT}/config" "${DATA_ROOT}/installer/deploy" "${DATA_ROOT}/build-cache" \
    "${AID_SYSTEMD_UNIT_DIR}" "${UPDATER_CONFIG_DIR}" "${UPDATER_DATA_DIR}/work" "${TMP_ROOT}/nginx/conf.d"
  cat > "${AID_ROOT_MARKER}" <<EOF
AID_MANAGED_ROOT=1
AID_DATA_ROOT=${DATA_ROOT}
AID_MANAGER_SCRIPT=${MANAGED_SCRIPT}
EOF
  # 发布包刚解出的安装器是失败前最常见的可验证归属痕迹。
  printf '#!/usr/bin/env bash\n# AID 统一部署管理脚本\n' > "${DATA_ROOT}/installer/deploy/aid.sh"
  chmod 700 "${DATA_ROOT}/installer/deploy/aid.sh"
  cat > "${UPDATER_CONFIG_DIR}/.aid-managed" <<EOF
AID_MANAGED_UPDATER=1
AID_DATA_ROOT=${DATA_ROOT}
AID_MANAGER_SCRIPT=${MANAGED_SCRIPT}
EOF
  cp "${UPDATER_CONFIG_DIR}/.aid-managed" "${UPDATER_DATA_DIR}/.aid-managed"
  printf '# AID_MANAGED_NGINX=1\n# AID_DATA_ROOT=%s\n' "${DATA_ROOT}" > "${TMP_ROOT}/nginx/conf.d/aid.conf"
}

prepare_partial_install
# 未产生运行态、Docker 不可用也不应阻止清理纯手动半成品。
do_uninstall --purge
assert_missing "${DATA_ROOT}"
assert_missing "${UPDATER_CONFIG_DIR}"
assert_missing "${UPDATER_DATA_DIR}"
assert_missing "${TMP_ROOT}/nginx/conf.d/aid.conf"

# 根目录缺少 AID 受管证据时，purge 必须在任何删除前停止。
FOREIGN_ROOT="${TMP_ROOT}/foreign/aid"
mkdir -p "${FOREIGN_ROOT}/config"
printf 'not aid\n' > "${FOREIGN_ROOT}/config/value"
if ( DATA_ROOT="${FOREIGN_ROOT}"; CONFIG_ROOT="${FOREIGN_ROOT}/config"; AID_ROOT_MARKER="${CONFIG_ROOT}/.aid-managed"; validate_aid_purge_root ); then
  fail 'foreign DATA_ROOT without AID ownership evidence passed purge validation'
fi
assert_exists "${FOREIGN_ROOT}/config/value"

# 默认目录不存在且没有任何当前根归属资源时，keep/purge 都必须在删除前失败。
NO_EVIDENCE_ROOT="${TMP_ROOT}/missing/aid"
NO_DELETE_SENTINEL="${TMP_ROOT}/no-delete-sentinel"
printf 'keep\n' > "${NO_DELETE_SENTINEL}"
if ( 
  DATA_ROOT="${NO_EVIDENCE_ROOT}"
  CONFIG_ROOT="${NO_EVIDENCE_ROOT}/config"
  AID_ROOT_MARKER="${CONFIG_ROOT}/.aid-managed"
  DEPLOYMENT_DESCRIPTOR="${CONFIG_ROOT}/deployment.json"
  STATE_FILE="${CONFIG_ROOT}/install-state.conf"
  CONF="${NO_EVIDENCE_ROOT}/aid-deploy.conf"
  ENV_FILE="${NO_EVIDENCE_ROOT}/docker.env"
  MANAGED_SCRIPT="${NO_EVIDENCE_ROOT}/installer/deploy/aid.sh"
  do_uninstall --keep
); then
  fail 'uninstall without any current-root evidence reported success'
fi
assert_exists "${NO_DELETE_SENTINEL}"

# 受管升级器目录删除失败必须立即非零退出，不能因 marker 删除失败而误判为 foreign。
prepare_partial_install
if (
  rm() {
    local arg
    for arg in "$@"; do
      [[ "${arg}" == "${UPDATER_CONFIG_DIR}" ]] && return 1
    done
    command rm "$@"
  }
  remove_aid_updater_runtime purge
); then
  fail 'updater config deletion failure was silently ignored'
fi
assert_exists "${UPDATER_CONFIG_DIR}/.aid-managed"

echo 'partial-install uninstall tests passed'
