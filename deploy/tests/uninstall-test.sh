#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

# 所有可删除路径均注入临时沙箱；本测试不能读取、删除或停止真实服务。
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
export AID_BOOTSTRAP_PATHS="${TMP_ROOT}/root/aid-install.sh:${TMP_ROOT}/root/aid.sh:${TMP_ROOT}/root/aid-link.sh"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"
ORIGINAL_REMOVE_AID_DOCKER_RUNTIME="$(declare -f remove_aid_docker_runtime)"
ORIGINAL_IS_AID_MANAGEMENT_COMMAND="$(declare -f is_aid_management_command)"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
SYMLINK_TESTS_SUPPORTED=0

make_test_symlink() { # make_test_symlink <target> <link>
  ln -s -- "$1" "$2" 2>/dev/null && [[ -L "$2" ]]
}

require_root() { :; }
detect_mode() { echo docker; }
risk() { :; }
warn() { :; }
log() { :; }
ok() { :; }
section() { :; }
ask_yes_no() { echo y; }
ASK_TRACE_FILE="${TMP_ROOT}/ask-trace.txt"
ask() {
  printf '%s\n' "${MOCK_ASK_VALUE:-DELETE-AID}" >> "${ASK_TRACE_FILE}"
  echo "${MOCK_ASK_VALUE:-DELETE-AID}"
}
# 主流程中的 Docker 预检必须可控；真正的 Docker 删除仍由下方 stub 隔离。
docker() {
  [[ "${1:-}" == "info" ]] && return 0
  return 1
}
select_existing_nginx_runtime() { return 1; }
# Docker 与系统账号属于宿主能力，分别用受控状态变量模拟；真实删除由本测试禁止。
remove_aid_docker_runtime() { :; }
aid_docker_containers_present() { return 1; }
aid_docker_images_present() { return 1; }
FAKE_AID_ACCOUNTS=0
remove_aid_manual_accounts() { FAKE_AID_ACCOUNTS=0; }
aid_manual_accounts_present() {
  [[ "${FAKE_AID_ACCOUNTS}" == "1" ]] || return 1
  err '卸载残留 AID 受管系统账号或用户组: aidmysql'
  return 0
}
TEST_MANAGED_COMMAND_PATH="${AID_LOCAL_BIN_DIR}/aid"
# Git for Windows may not expose POSIX symlink metadata in /tmp. The production
# predicate is covered by shell syntax checks; the sandbox uses a marked regular
# file to exercise removal and residual verification without touching /usr/local.
is_aid_management_command() {
  [[ "$1" == "${TEST_MANAGED_COMMAND_PATH}" && -f "$1" ]] || return 1
  grep -Fq 'AID managed test command' "$1"
}

prepare_sandbox() {
  rm -rf -- "${DATA_ROOT}" "${AID_SYSTEMD_UNIT_DIR}" "${AID_LOCAL_BIN_DIR}" \
    "${UPDATER_CONFIG_DIR}" "${UPDATER_DATA_DIR}" "${TMP_ROOT}/profile" \
    "${TMP_ROOT}/nginx" "${TMP_ROOT}/root"
  mkdir -p "${DATA_ROOT}/config" "${DATA_ROOT}/installer/deploy" "${DATA_ROOT}/app/updater" "${DATA_ROOT}/runtime/bin" \
    "${AID_SYSTEMD_UNIT_DIR}" "${AID_LOCAL_BIN_DIR}" "${UPDATER_CONFIG_DIR}" \
    "${UPDATER_DATA_DIR}" "${TMP_ROOT}/profile" "${TMP_ROOT}/nginx/conf.d" "${TMP_ROOT}/root"
  printf '#!/usr/bin/env bash\n# AID 统一部署管理脚本\n' > "${DATA_ROOT}/installer/deploy/aid.sh"
  chmod 700 "${DATA_ROOT}/installer/deploy/aid.sh"
  cat > "${AID_ROOT_MARKER}" <<EOF
AID_MANAGED_ROOT=1
AID_DATA_ROOT=${DATA_ROOT}
AID_MANAGER_SCRIPT=${MANAGED_SCRIPT}
EOF
  printf 'updater-binary\n' > "${DATA_ROOT}/app/updater/aid-updater"
  touch "${DATA_ROOT}/runtime/bin/redis-server"
  cp "${DATA_ROOT}/app/updater/aid-updater" "${AID_LOCAL_BIN_DIR}/aid-updater"
  printf 'AID managed test command\n' > "${AID_LOCAL_BIN_DIR}/aid"
  for unit in aid.service aid-web.service aid-updater.service aid-mysql.service aid-redis.service aid-nginx.service; do
    printf '# AID_MANAGED_UNIT=1\n# AID_DATA_ROOT=%s\n' "${DATA_ROOT}" > "${AID_SYSTEMD_UNIT_DIR}/${unit}"
  done
  printf '# AID_MANAGED_JAVA_PROFILE=1\n# AID_DATA_ROOT=%s\n' "${DATA_ROOT}" > "${JAVA_PROFILE_FILE}"
  printf 'AID_MANAGED_UPDATER=1\nAID_DATA_ROOT=%s\nAID_MANAGER_SCRIPT=%s\n' "${DATA_ROOT}" "${MANAGED_SCRIPT}" \
    > "${UPDATER_CONFIG_DIR}/.aid-managed"
  cp "${UPDATER_CONFIG_DIR}/.aid-managed" "${UPDATER_DATA_DIR}/.aid-managed"
  touch "${UPDATER_CONFIG_DIR}/config.json" "${UPDATER_DATA_DIR}/health.json"
  printf '# AID_MANAGED_NGINX=1\n# AID_DATA_ROOT=%s\n' "${DATA_ROOT}" > "${TMP_ROOT}/nginx/conf.d/aid.conf"
  touch "${TMP_ROOT}/nginx/conf.d/aid.conf.bak.1"
  printf '# AID 统一部署管理脚本\n' > "${TMP_ROOT}/root/aid-install.sh"
  printf '# unrelated local script\n' > "${TMP_ROOT}/root/aid.sh"
  if [[ "${SYMLINK_TESTS_SUPPORTED}" == "1" ]]; then
    make_test_symlink "${TMP_ROOT}/root/aid-install.sh" "${TMP_ROOT}/root/aid-link.sh" \
      || fail 'filesystem stopped supporting symbolic links during test'
    make_test_symlink "${TMP_ROOT}/nginx/conf.d/aid.conf" "${TMP_ROOT}/nginx/conf.d/aid.conf.bak.link" \
      || fail 'filesystem stopped supporting symbolic links during test'
  fi
  FAKE_AID_ACCOUNTS=1
  unset MOCK_ASK_VALUE AID_UNINSTALL_SAFE_REEXEC
  : > "${ASK_TRACE_FILE}"
}

assert_exists() { [[ -e "$1" || -L "$1" ]] || fail "expected path to remain: $1"; }
assert_missing() { [[ ! -e "$1" && ! -L "$1" ]] || fail "expected path to be removed: $1"; }
assert_source_pattern() {
  local pattern="$1" description="$2"
  grep -Eq -- "${pattern}" "${ROOT_DIR}/deploy/aid.sh" || fail "missing route: ${description}"
}

# 菜单 13 必须直接走彻底卸载入口；uninstall-all 必须是独立的 purge 别名路由。
assert_source_pattern '^[[:space:]]*13\)[[:space:]]*do_uninstall_all[[:space:]]*\|\|[[:space:]]*true[[:space:]]*;;' 'menu 13 -> do_uninstall_all'
assert_source_pattern '^[[:space:]]*uninstall-all\)[[:space:]]*do_uninstall_all;[[:space:]]*exit[[:space:]]*\$\?[[:space:]]*;;' 'uninstall-all -> do_uninstall_all'
assert_source_pattern 'env AID_DATA_ROOT="\$3" AID_SH_LIBRARY_MODE=0 AID_UNINSTALL_SAFE_REEXEC=1 bash' 'safe reexec preserves DATA_ROOT'
assert_source_pattern 'BACKUP_SHA256=' 'Nginx backup integrity state'
if grep -Fq 'AID_UNINSTALL_SAFE_CONFIRMED' "${ROOT_DIR}/deploy/aid.sh"; then
  fail 'purge confirmation must not be bypassed through an environment flag'
fi

# CentOS 7 Bash 4.2 在 set -u 下会把声明后仍为空的数组视作未绑定。卸载路径
# 必须使用 ${array[@]+...} 或独立计数器，不能直接展开/取空数组长度。
if grep -Eq '\$\{#(AID_DATA_ROOT_CANDIDATES|AID_OWNED_DOCKER_IMAGE_IDS|AID_OWNED_SYSTEMD_SERVICES|AID_OWNED_MANUAL_ACCOUNTS|AID_OWNED_MANUAL_GROUPS)\[@\]\}' \
    "${ROOT_DIR}/deploy/aid.sh"; then
  fail 'uninstall ownership arrays contain a Bash 4.2-incompatible empty length expansion'
fi
AID_DATA_ROOT_CANDIDATES=(); AID_DATA_ROOT_CANDIDATE_COUNT=0
AID_OWNED_DOCKER_IMAGE_IDS=(); AID_OWNED_DOCKER_IMAGE_COUNT=0
AID_OWNED_SYSTEMD_SERVICES=(); AID_OWNED_SYSTEMD_SERVICE_COUNT=0
AID_OWNED_MANUAL_ACCOUNTS=(); AID_OWNED_MANUAL_ACCOUNT_COUNT=0
AID_OWNED_MANUAL_GROUPS=(); AID_OWNED_MANUAL_GROUP_COUNT=0
aid_docker_images_present && fail 'empty Docker ownership array was reported as residual'
aid_manual_accounts_present && fail 'empty manual ownership arrays were reported as residual'
remove_aid_system_services
savedNginxDirs="${AID_NGINX_SITE_DIRS}"; AID_NGINX_SITE_DIRS=""; remove_aid_nginx_site; AID_NGINX_SITE_DIRS="${savedNginxDirs}"
savedBootstrapPaths="${AID_BOOTSTRAP_PATHS}"; AID_BOOTSTRAP_PATHS=""; remove_aid_bootstrap_scripts; AID_BOOTSTRAP_PATHS="${savedBootstrapPaths}"

# 悬空软链接仍必须能识别为 AID 管理命令；指向其他位置的同名链接不会匹配。
prepare_sandbox
rm -f -- "${AID_LOCAL_BIN_DIR}/aid"
ln -s "${MANAGED_SCRIPT}" "${AID_LOCAL_BIN_DIR}/aid"
rm -f -- "${MANAGED_SCRIPT}"
if [[ -L "${AID_LOCAL_BIN_DIR}/aid" ]]; then
  eval "${ORIGINAL_IS_AID_MANAGEMENT_COMMAND}"
  is_aid_management_command "${AID_LOCAL_BIN_DIR}/aid" || fail 'dangling managed aid symlink was not recognized'
  remove_aid_command_links
  assert_missing "${AID_LOCAL_BIN_DIR}/aid"
else
  # Git for Windows may not expose dangling POSIX symlinks in /tmp; production
  # logic is still guarded by this exact resolver route.
  assert_source_pattern '^resolve_aid_symlink_target\(' 'dangling symlink resolver'
fi
# 恢复 Windows 兼容的普通文件模拟，供后续沙箱断言使用。
is_aid_management_command() {
  [[ "$1" == "${TEST_MANAGED_COMMAND_PATH}" && -f "$1" ]] || return 1
  grep -Fq 'AID managed test command' "$1"
}

# purge 只接受足够深的真实目录，必须拒绝高风险根目录和软链接。
if ( DATA_ROOT=/data; validate_aid_purge_root ) >/dev/null 2>&1; then
  fail 'high-risk /data root must never pass purge validation'
fi
mkdir -p "${TMP_ROOT}/data/real-aid"
if make_test_symlink "${TMP_ROOT}/data/real-aid" "${TMP_ROOT}/data/link-aid"; then
  SYMLINK_TESTS_SUPPORTED=1
  if ( DATA_ROOT="${TMP_ROOT}/data/link-aid"; validate_aid_purge_root ) >/dev/null 2>&1; then
    fail 'symbolic-link DATA_ROOT must never pass purge validation'
  fi
else
  printf 'SKIP: filesystem does not provide POSIX symbolic links; symbolic-link safety checks skipped\n' >&2
fi

# 临时安全脚本的 REPO_DIR 可能解析为 /；根目录绝不能把引导脚本误判为源码。
prepare_sandbox
originalRepoDir="${REPO_DIR}"
REPO_DIR="/"
remove_aid_bootstrap_scripts
REPO_DIR="${originalRepoDir}"
assert_missing "${TMP_ROOT}/root/aid-install.sh"

# 真实项目源码根（.git 或 pom.xml）下的引导脚本仍必须保留。
prepare_sandbox
touch "${TMP_ROOT}/root/pom.xml"
REPO_DIR="${TMP_ROOT}/root"
remove_aid_bootstrap_scripts
REPO_DIR="${originalRepoDir}"
assert_exists "${TMP_ROOT}/root/aid-install.sh"

# keep：保留 DATA_ROOT、升级器数据和受管账号；只撤销运行入口。
prepare_sandbox
do_uninstall --keep
assert_exists "${DATA_ROOT}"
assert_exists "${UPDATER_DATA_DIR}"
assert_exists "${TMP_ROOT}/root/aid-install.sh"
[[ "${FAKE_AID_ACCOUNTS}" == "1" ]] || fail 'keep must preserve AID-managed accounts'
assert_missing "${AID_SYSTEMD_UNIT_DIR}/aid.service"
assert_missing "${AID_LOCAL_BIN_DIR}/aid"
assert_missing "${UPDATER_CONFIG_DIR}"

# purge：uninstall-all 是 --purge 别名，必须删除所有受管残留，非 AID 同名脚本不得删除。
prepare_sandbox
do_uninstall_all
assert_missing "${DATA_ROOT}"
assert_missing "${AID_SYSTEMD_UNIT_DIR}/aid.service"
assert_missing "${AID_LOCAL_BIN_DIR}/aid"
assert_missing "${AID_LOCAL_BIN_DIR}/aid-updater"
assert_missing "${UPDATER_CONFIG_DIR}"
assert_missing "${UPDATER_DATA_DIR}"
assert_missing "${TMP_ROOT}/nginx/conf.d/aid.conf"
if [[ "${SYMLINK_TESTS_SUPPORTED}" == "1" ]]; then
  assert_exists "${TMP_ROOT}/nginx/conf.d/aid.conf.bak.link"
fi
assert_missing "${TMP_ROOT}/root/aid-install.sh"
assert_exists "${TMP_ROOT}/root/aid.sh"
if [[ "${SYMLINK_TESTS_SUPPORTED}" == "1" ]]; then
  [[ -L "${TMP_ROOT}/root/aid-link.sh" ]] || fail 'marked bootstrap symlink must not be removed'
fi
[[ "${FAKE_AID_ACCOUNTS}" == "0" ]] || fail 'purge must remove AID-managed accounts'

# AID_ASSUME_YES 不能绕过 DELETE-AID；确认文字不匹配时不应开始清理。
prepare_sandbox
export AID_ASSUME_YES=1
MOCK_ASK_VALUE='not-delete-aid'
do_uninstall --purge
unset AID_ASSUME_YES
assert_exists "${DATA_ROOT}"
[[ "$(wc -l < "${ASK_TRACE_FILE}")" -eq 1 ]] || fail 'AID_ASSUME_YES must not bypass DELETE-AID prompt'

# 安全 reexec 只防止再次换出，仍必须在安全副本中要求唯一一次 DELETE-AID。
prepare_sandbox
export AID_UNINSTALL_SAFE_REEXEC=1
do_uninstall --purge
unset AID_UNINSTALL_SAFE_REEXEC
[[ "$(wc -l < "${ASK_TRACE_FILE}")" -eq 1 ]] || fail 'safe reexec must require exactly one DELETE-AID prompt'

# 残留核验失败必须返回非零，且不能将失败误报为成功。
prepare_sandbox
remove_aid_bootstrap_scripts() { :; }
if ( do_uninstall --purge ); then
  fail 'purge with a deliberately retained AID bootstrap must fail verification'
fi
assert_exists "${TMP_ROOT}/root/aid-install.sh"

# Nginx AID 配置或脚本创建的备份未删除时，purge 必须失败而不是误报成功。
prepare_sandbox
remove_aid_nginx_site() { :; }
if ( do_uninstall --purge ); then
  fail 'purge with a retained AID Nginx configuration must fail verification'
fi
assert_exists "${TMP_ROOT}/nginx/conf.d/aid.conf"

# 网络扫描只依据 Compose working_dir；即便容器已不存在也会删除 AID 网络，
# 删除失败则残留检测必须命中。整个 Docker 命令在这里均为函数模拟。
prepare_sandbox
eval "${ORIGINAL_REMOVE_AID_DOCKER_RUNTIME}"
FAKE_AID_NETWORK_PRESENT=1
FAKE_AID_NETWORK_DELETE_FAIL=0
docker() {
  case "$1:${2:-}" in
    inspect:*) return 1 ;;
    network:ls)
      [[ "${FAKE_AID_NETWORK_PRESENT}" == "1" ]] && printf 'aid-test-network\n'
      return 0
      ;;
    network:inspect)
      case "${4:-}" in
        *com.aid.managed*) printf 'true\n' ;;
        *com.aid.data_root*) printf '%s\n' "${DATA_ROOT}" ;;
        *com.docker.compose.project.working_dir*) printf '%s\n' "${DATA_ROOT}/installer/deploy/docker" ;;
        *) printf '%s\n' "${DATA_ROOT}/installer/deploy/docker" ;;
      esac
      return 0
      ;;
    network:rm)
      [[ "${FAKE_AID_NETWORK_DELETE_FAIL}" == "1" ]] && return 1
      FAKE_AID_NETWORK_PRESENT=0
      return 0
      ;;
    image:ls) return 0 ;;
    *) return 0 ;;
  esac
}
remove_aid_docker_runtime purge
[[ "${FAKE_AID_NETWORK_PRESENT}" == "0" ]] || fail 'orphaned AID Compose network was not removed'
aid_docker_networks_present && fail 'removed AID Compose network was still reported as residual'
FAKE_AID_NETWORK_PRESENT=1
FAKE_AID_NETWORK_DELETE_FAIL=1
remove_aid_docker_runtime purge
aid_docker_networks_present || fail 'failed AID Compose network deletion was not reported as residual'

echo 'uninstall tests passed'
