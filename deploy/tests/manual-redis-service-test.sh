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
