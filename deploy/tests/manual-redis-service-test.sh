#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

# Redis 的 systemd notify 支持取决于编译环境。源码构建统一使用 simple 模式，
# 避免 CentOS 7 在服务已启动后仍等待 READY 通知直至超时。
prepare_body="$(declare -f prepare_managed_redis)"
[[ "${prepare_body}" == *'supervised no'* ]] \
  || { echo 'FAIL: managed Redis must disable systemd supervision' >&2; exit 1; }
[[ "${prepare_body}" == *'Type=simple'* ]] \
  || { echo 'FAIL: managed Redis service must use Type=simple' >&2; exit 1; }
[[ "${prepare_body}" != *'Type=notify'* && "${prepare_body}" != *'--supervised systemd'* ]] \
  || { echo 'FAIL: managed Redis still depends on systemd notify support' >&2; exit 1; }
[[ "${prepare_body}" == *'wait_managed_redis_ready'* ]] \
  || { echo 'FAIL: managed Redis startup must wait for an authenticated PING' >&2; exit 1; }
[[ "${prepare_body}" == *'resolve_redis_cli_command'* && "${prepare_body}" != *'command -v redis-cli >/dev/null'* ]] \
  || { echo 'FAIL: managed Redis still requires redis-cli to be discoverable through the inherited PATH' >&2; exit 1; }

dependency_body="$(declare -f ensure_manual_host_dependencies)"
[[ "${dependency_body}" == *'"${redisCli}" "${redisArgs[@]}" INFO server'* ]] \
  || { echo 'FAIL: manual dependency validation does not use the resolved redis-cli path' >&2; exit 1; }

# 中断恢复必须识别旧 notify unit，并直接复用受管客户端，不能退回已失效的系统 yum 源。
export AID_SYSTEMD_UNIT_DIR="${TMP_ROOT}/systemd"
export AID_LOCAL_BIN_DIR="${TMP_ROOT}/bin"
mkdir -p "${AID_SYSTEMD_UNIT_DIR}" "$(managed_redis_home)/src"
printf '#!/usr/bin/env bash\nexit 0\n' > "$(managed_redis_home)/src/redis-server"
printf '#!/usr/bin/env bash\nexit 0\n' > "$(managed_redis_home)/src/redis-cli"
chmod +x "$(managed_redis_home)/src/redis-server" "$(managed_redis_home)/src/redis-cli"
cat > "${AID_SYSTEMD_UNIT_DIR}/${REDIS_MANAGED_SERVICE}" <<'EOF'
[Service]
Type=notify
ExecStart=/tmp/redis-server --supervised systemd
EOF
systemctl() { [[ "$1" == "is-active" ]]; }
managed_redis_service_needs_recovery \
  || { echo 'FAIL: interrupted legacy Redis service must be recovered' >&2; exit 1; }
link_managed_redis_commands
[[ -x "${AID_LOCAL_BIN_DIR}/redis-server" && -x "${AID_LOCAL_BIN_DIR}/redis-cli" ]] \
  || { echo 'FAIL: managed Redis commands must be reused from the compiled runtime' >&2; exit 1; }
[[ ":${PATH}:" == *":${AID_LOCAL_BIN_DIR}:"* ]] \
  || { echo 'FAIL: managed command directory was not added to PATH' >&2; exit 1; }
[[ "$(resolve_redis_cli_command)" == "${AID_LOCAL_BIN_DIR}/redis-cli" ]] \
  || { echo 'FAIL: managed redis-cli command was not resolved from linked PATH' >&2; exit 1; }

# 上一次安装留下的受管悬空链接必须可幂等修复；非 AID 链接则不得覆盖。
rm -f "${AID_LOCAL_BIN_DIR}/redis-cli"
mkdir -p "${AID_LOCAL_BIN_DIR}"
mkdir -p "${DATA_ROOT}/runtime/redis-old/src"
printf '#!/usr/bin/env bash\nexit 0\n' > "${DATA_ROOT}/runtime/redis-old/src/redis-cli"
chmod +x "${DATA_ROOT}/runtime/redis-old/src/redis-cli"
ln -s "${DATA_ROOT}/runtime/redis-old/src/redis-cli" "${AID_LOCAL_BIN_DIR}/redis-cli"
if [[ -L "${AID_LOCAL_BIN_DIR}/redis-cli" ]]; then
  link_managed_redis_commands
  [[ "$(readlink -f "${AID_LOCAL_BIN_DIR}/redis-cli")" == "$(managed_redis_home)/src/redis-cli" ]] \
    || { echo 'FAIL: stale managed redis-cli symlink was not repaired' >&2; exit 1; }
else
  rm -f "${AID_LOCAL_BIN_DIR}/redis-cli"
fi

foreignDir="${TMP_ROOT}/foreign"
mkdir -p "${foreignDir}"
printf '#!/usr/bin/env bash\nexit 0\n' > "${foreignDir}/redis-cli"
chmod +x "${foreignDir}/redis-cli"
rm -f "${AID_LOCAL_BIN_DIR}/redis-cli"
mkdir -p "${AID_LOCAL_BIN_DIR}"
ln -s "${foreignDir}/redis-cli" "${AID_LOCAL_BIN_DIR}/redis-cli"
if [[ -L "${AID_LOCAL_BIN_DIR}/redis-cli" ]]; then
  PATH="/usr/bin:/bin" link_managed_redis_commands
  [[ "$(readlink -f "${AID_LOCAL_BIN_DIR}/redis-cli")" == "${foreignDir}/redis-cli" ]] \
    || { echo 'FAIL: foreign redis-cli symlink was overwritten' >&2; exit 1; }
else
  rm -f "${AID_LOCAL_BIN_DIR}/redis-cli"
fi
[[ "$(PATH="/usr/bin:/bin" resolve_redis_cli_command)" == "$(managed_redis_home)/src/redis-cli" ]] \
  || { echo 'FAIL: managed redis-cli source was not used when a foreign link occupied the command path' >&2; exit 1; }

# sudo secure_path 常见值不含 /usr/local/bin；受管命令必须加入当前进程 PATH，
# 即使入口目录原先不可见，也能解析受管 redis-cli 的绝对路径。
(
  AID_LOCAL_BIN_DIR="${TMP_ROOT}/secure-bin"
  export AID_LOCAL_BIN_DIR
  PATH='/sbin:/bin:/usr/sbin:/usr/bin'
  export PATH
  link_managed_redis_commands
  [[ ":${PATH}:" == *":${AID_LOCAL_BIN_DIR}:"* ]]
  resolved="$(resolve_redis_cli_command)"
  [[ -n "${resolved}" && -x "${resolved}" ]]
) || { echo 'FAIL: sudo secure_path could not resolve managed redis-cli' >&2; exit 1; }

# PATH 未设置也不能在 set -u 下报未绑定变量；函数应补齐安全默认 PATH。
(
  unset PATH
  link_managed_redis_commands
  [[ -n "${PATH}" && ":${PATH}:" == *":${AID_LOCAL_BIN_DIR}:"* ]]
) || { echo 'FAIL: unset PATH was not repaired safely' >&2; exit 1; }

# 健康等待必须能在服务活跃且随后 PING 成功时结束。
PING_ATTEMPTS=0
managed_redis_ping() {
  PING_ATTEMPTS=$((PING_ATTEMPTS + 1))
  [[ "${PING_ATTEMPTS}" -ge 2 ]]
}
systemctl() { [[ "$1" == "is-active" ]]; }
sleep() { :; }
date() { command date "$@"; }
ok() { :; }
manual_service_diagnostics() { echo 'FAIL: diagnostics should not run for a healthy Redis' >&2; return 1; }
wait_managed_redis_ready 6379 '' ''

echo 'manual Redis service tests passed'
