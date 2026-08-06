#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_exists() { [[ -e "$1" || -L "$1" ]] || fail "expected path to remain: $1"; }
assert_missing() { [[ ! -e "$1" && ! -L "$1" ]] || fail "expected path to be removed: $1"; }

# 所有系统路径重定向至沙箱，禁止测试接触真实 systemd、Nginx、升级器或账号。
export AID_SH_LIBRARY_MODE=1
export AID_UNINSTALL_TEST_MODE=1
export AID_UNINSTALL_TEST_ROOT="${TMP_ROOT}"
export AID_DATA_ROOT="${TMP_ROOT}/data/aid"
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
detect_mode() { echo manual; }
select_existing_nginx_runtime() { return 1; }

FAKE_AIDMYSQL_HOME=""
FAKE_AIDREDIS_HOME=""
FAKE_AIDMYSQL_REMOVED=0
FAKE_AIDREDIS_REMOVED=0
FAKE_AIDMYSQL_GROUP=0
FAKE_AIDREDIS_GROUP=0
USERDEL_FAIL=0
getent() {
  case "${1:-}:${2:-}" in
    passwd:aidmysql) [[ -n "${FAKE_AIDMYSQL_HOME}" ]] && printf 'aidmysql:x:991:991::%s:/sbin/nologin\n' "${FAKE_AIDMYSQL_HOME}" ;;
    passwd:aidredis) [[ -n "${FAKE_AIDREDIS_HOME}" ]] && printf 'aidredis:x:992:992::%s:/usr/sbin/nologin\n' "${FAKE_AIDREDIS_HOME}" ;;
    group:aidmysql) [[ "${FAKE_AIDMYSQL_GROUP}" == 1 ]] && printf 'aidmysql:x:991:\n' ;;
    group:aidredis) [[ "${FAKE_AIDREDIS_GROUP}" == 1 ]] && printf 'aidredis:x:992:\n' ;;
    *) return 1 ;;
  esac
}
id() {
  case "${1:-}" in
    aidmysql) [[ -n "${FAKE_AIDMYSQL_HOME}" ]] ;;
    aidredis) [[ -n "${FAKE_AIDREDIS_HOME}" ]] ;;
    *) return 1 ;;
  esac
}
userdel() {
  [[ "${USERDEL_FAIL}" == 0 ]] || return 1
  case "${1:-}" in
    aidmysql) FAKE_AIDMYSQL_HOME=""; FAKE_AIDMYSQL_REMOVED=1 ;;
    aidredis) FAKE_AIDREDIS_HOME=""; FAKE_AIDREDIS_REMOVED=1 ;;
    *) return 1 ;;
  esac
}
groupdel() {
  case "${1:-}" in
    aidmysql) FAKE_AIDMYSQL_GROUP=0 ;;
    aidredis) FAKE_AIDREDIS_GROUP=0 ;;
    *) return 1 ;;
  esac
}

write_marker() { # write_marker <path> <kind>
  local path="$1" kind="$2"
  mkdir -p "$(dirname "${path}")"
  case "${kind}" in
    root)
      cat > "${path}" <<EOF
AID_MANAGED_ROOT=1
AID_DATA_ROOT=${DATA_ROOT}
AID_MANAGER_SCRIPT=${MANAGED_SCRIPT}
EOF
      ;;
    updater)
      cat > "${path}" <<EOF
AID_MANAGED_UPDATER=1
AID_DATA_ROOT=${DATA_ROOT}
AID_MANAGER_SCRIPT=${MANAGED_SCRIPT}
EOF
      ;;
  esac
}

prepare_current_manual_install() {
  rm -rf -- "${DATA_ROOT}" "${AID_SYSTEMD_UNIT_DIR}" "${AID_LOCAL_BIN_DIR}" \
    "${UPDATER_CONFIG_DIR}" "${UPDATER_DATA_DIR}" "${TMP_ROOT}/profile" "${TMP_ROOT}/nginx"
  mkdir -p "${DATA_ROOT}/config/nginx" "${DATA_ROOT}/app" "${AID_SYSTEMD_UNIT_DIR}" \
    "${AID_LOCAL_BIN_DIR}" "${UPDATER_CONFIG_DIR}" "${UPDATER_DATA_DIR}" \
    "${TMP_ROOT}/profile" "${TMP_ROOT}/nginx/conf.d"
  write_marker "${AID_ROOT_MARKER}" root
  printf 'jar\n' > "${DATA_ROOT}/app/aid-admin.jar"
  for unit in aid.service aid-web.service aid-mysql.service aid-redis.service aid-nginx.service; do
    printf '# AID_MANAGED_UNIT=1\n# AID_DATA_ROOT=%s\n' "${DATA_ROOT}" > "${AID_SYSTEMD_UNIT_DIR}/${unit}"
  done
  printf '# AID_MANAGED_JAVA_PROFILE=1\n# AID_DATA_ROOT=%s\n' "${DATA_ROOT}" > "${JAVA_PROFILE_FILE}"
  write_marker "${UPDATER_CONFIG_DIR}/.aid-managed" updater
  write_marker "${UPDATER_DATA_DIR}/.aid-managed" updater
  touch "${UPDATER_CONFIG_DIR}/config.json" "${UPDATER_DATA_DIR}/health.json"
  printf '# 原有 Nginx 站点\nserver { listen 80; }\n' > "${TMP_ROOT}/nginx/conf.d/aid.conf.aid-before-install.1"
  printf '# AID_MANAGED_NGINX=1\n# AID_DATA_ROOT=%s\nserver { listen 18080; }\n' "${DATA_ROOT}" \
    > "${TMP_ROOT}/nginx/conf.d/aid.conf"
  cat > "${AID_NGINX_STATE_FILE}" <<EOF
AID_MANAGED_NGINX_STATE=1
AID_DATA_ROOT=${DATA_ROOT}
SITE_FILE=${TMP_ROOT}/nginx/conf.d/aid.conf
BACKUP_FILE=${TMP_ROOT}/nginx/conf.d/aid.conf.aid-before-install.1
BACKUP_SHA256=$(sha256_file "${TMP_ROOT}/nginx/conf.d/aid.conf.aid-before-install.1")
EOF
  FAKE_AIDMYSQL_HOME="${DATA_ROOT}/mysql-data-manual"
  FAKE_AIDREDIS_HOME="${DATA_ROOT}/redis-data-manual"
  FAKE_AIDMYSQL_REMOVED=0
  FAKE_AIDREDIS_REMOVED=0
  FAKE_AIDMYSQL_GROUP=1
  FAKE_AIDREDIS_GROUP=1
  USERDEL_FAIL=0
}

# 新 marker 与精确根路径是当前 unit 的充分证据；同名外部 unit 必须保留。
prepare_current_manual_install
printf '[Service]\nExecStart=/usr/bin/other\n' > "${AID_SYSTEMD_UNIT_DIR}/aid-updater.service"
if aid_systemd_unit_belongs_to_current_install "${AID_SYSTEMD_UNIT_DIR}/aid-updater.service"; then
  fail 'unowned same-name updater unit was accepted'
fi

# 旧版 updater unit 没有 marker 时，只有其配置确实指回当前安装器和 jar 才可回收。
cat > "${UPDATER_CONFIG_FILE}" <<EOF
{"managerScript": "${MANAGED_SCRIPT}", "backendJar": "${DATA_ROOT}/app/aid-admin.jar"}
EOF
cat > "${AID_SYSTEMD_UNIT_DIR}/aid-updater.service" <<EOF
[Service]
ExecStart=/usr/local/bin/aid-updater -config ${UPDATER_CONFIG_FILE}
EOF
aid_systemd_unit_belongs_to_current_install "${AID_SYSTEMD_UNIT_DIR}/aid-updater.service" \
  || fail 'legacy updater unit with owned configuration was not recognized'

# stop/disable 失败且服务仍 active 时，必须在删除 unit 前停止卸载。
prepare_current_manual_install
systemctl() {
  case "${1:-}" in
    disable) return 1 ;;
    is-active) return 0 ;;
    *) return 0 ;;
  esac
}
if ( AID_UNINSTALL_TEST_SYSTEMD=1; remove_aid_system_services ); then
  fail 'active managed systemd service did not block unit deletion'
fi
assert_exists "${AID_SYSTEMD_UNIT_DIR}/aid.service"
unset -f systemctl
# 旧版 data 目录没有 marker 时，删除配置前必须先记住它的归属。
prepare_current_manual_install
cat > "${UPDATER_CONFIG_FILE}" <<EOF
{"managerScript": "${MANAGED_SCRIPT}", "backendJar": "${DATA_ROOT}/app/aid-admin.jar"}
EOF
rm -f -- "${UPDATER_CONFIG_DIR}/.aid-managed" "${UPDATER_DATA_DIR}/.aid-managed"
remove_aid_updater_runtime purge
assert_missing "${UPDATER_CONFIG_DIR}"
assert_missing "${UPDATER_DATA_DIR}"

# 即使受管站点文件被异常删除，只要备份状态仍完整，也必须恢复安装前配置。
prepare_current_manual_install
rm -f -- "${TMP_ROOT}/nginx/conf.d/aid.conf"
remove_aid_nginx_site
grep -Fq '# 原有 Nginx 站点' "${TMP_ROOT}/nginx/conf.d/aid.conf" \
  || fail 'missing managed Nginx site did not restore the recorded original site'
assert_missing "${TMP_ROOT}/nginx/conf.d/aid.conf.aid-before-install.1"
assert_missing "${AID_NGINX_STATE_FILE}"

# 备份 SHA256 不匹配时不得恢复，且不得删除未知备份。
prepare_current_manual_install
printf 'tampered\n' >> "${TMP_ROOT}/nginx/conf.d/aid.conf.aid-before-install.1"
if aid_nginx_backup_for_site "${TMP_ROOT}/nginx/conf.d/aid.conf" >/dev/null; then
  fail 'tampered Nginx backup passed SHA256 verification'
fi
remove_aid_nginx_site
assert_exists "${TMP_ROOT}/nginx/conf.d/aid.conf.aid-before-install.1"

# --keep 只撤销运行入口。原 Nginx 站点应被恢复，数据、升级器工作数据、账号必须保留。
prepare_current_manual_install
do_uninstall --keep
assert_exists "${DATA_ROOT}"
assert_missing "${AID_SYSTEMD_UNIT_DIR}/aid.service"
assert_missing "${JAVA_PROFILE_FILE}"
assert_missing "${UPDATER_CONFIG_DIR}"
assert_exists "${UPDATER_DATA_DIR}"
grep -Fq '# 原有 Nginx 站点' "${TMP_ROOT}/nginx/conf.d/aid.conf" || fail 'previous Nginx site was not restored'
assert_missing "${TMP_ROOT}/nginx/conf.d/aid.conf.aid-before-install.1"
assert_missing "${AID_NGINX_STATE_FILE}"
[[ "${FAKE_AIDMYSQL_REMOVED}" == 0 && "${FAKE_AIDREDIS_REMOVED}" == 0 ]] \
  || fail 'keep removed managed manual accounts'

# --purge 删除当前受管的所有手动安装痕迹和账号。
prepare_current_manual_install
do_uninstall --purge
assert_missing "${DATA_ROOT}"
assert_missing "${UPDATER_CONFIG_DIR}"
assert_missing "${UPDATER_DATA_DIR}"
assert_missing "${AID_SYSTEMD_UNIT_DIR}/aid.service"
assert_missing "${JAVA_PROFILE_FILE}"
[[ "${FAKE_AIDMYSQL_REMOVED}" == 1 && "${FAKE_AIDREDIS_REMOVED}" == 1 ]] \
  || fail 'purge did not remove current managed manual accounts'
[[ "${FAKE_AIDMYSQL_GROUP}" == 0 && "${FAKE_AIDREDIS_GROUP}" == 0 ]] \
  || fail 'purge did not remove current managed manual groups'

# 账号删除失败后必须由最终残留核验命中，不能因 home 判断变化而放过同名 group。
prepare_current_manual_install
USERDEL_FAIL=1
remove_aid_manual_accounts
aid_manual_accounts_present || fail 'failed managed account deletion was not reported as residual'

# 同名但 home 不属于当前 DATA_ROOT 的账号不满足归属条件，必须保留。
FAKE_AIDMYSQL_HOME="${TMP_ROOT}/other/mysql"
remove_aid_manual_accounts
[[ -n "${FAKE_AIDMYSQL_HOME}" ]] || fail 'unowned same-name manual account was removed'

echo 'manual uninstall tests passed'
