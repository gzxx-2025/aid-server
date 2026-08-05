#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

TRACE_FILE="${TMP_ROOT}/trace.txt"
ENV_FILE="${TMP_ROOT}/docker.env"
mkdir -p "${DATA_ROOT}/app/updater" "${DATA_ROOT}/app/web-dist/server"
touch "${DATA_ROOT}/app/updater/aid-updater" "${DATA_ROOT}/app/web-dist/server/index.mjs"

trace() { printf '%s\n' "$*" >> "${TRACE_FILE}"; }
reset_trace() { : > "${TRACE_FILE}"; }
assert_trace() {
  local expected="$1" actual
  actual="$(cat "${TRACE_FILE}")"
  [[ "${actual}" == "${expected}" ]] || {
    echo "FAIL: unexpected startup trace" >&2
    printf 'expected:\n%s\nactual:\n%s\n' "${expected}" "${actual}" >&2
    exit 1
  }
}

section() { :; }
log() { :; }
ok() { :; }
risk() { :; }

# Docker 前置服务必须严格先于主程序，并等待内置 MQ 两个组件健康。
ensure_mysql_ready() { trace mysql; }
wait_docker_database_schema_ready() { trace schema; }
ensure_redis_ready() { trace redis; }
check_external_rocketmq_connectivity() { trace mq-connectivity; }
docker_profile_enabled() { [[ "$1" == "mq" && "${MOCK_MQ_ENABLED:-no}" == "yes" ]]; }
compose_cmd() { trace "compose:$*"; }
wait_docker_container_healthy() { trace "healthy:$1"; }

reset_trace
MOCK_MQ_ENABLED=yes
prepare_docker_runtime_dependencies
assert_trace $'mysql\nschema\nredis\nmq-connectivity\ncompose:up -d rocketmq-nameserver rocketmq-broker\nhealthy:aid-rocketmq-nameserver\nhealthy:aid-rocketmq-broker'

reset_trace
MOCK_MQ_ENABLED=no
prepare_docker_runtime_dependencies
assert_trace $'mysql\nschema\nredis\nmq-connectivity'

# Docker 应按后端 -> Web -> Nginx -> 升级器的顺序启动。
validate_https_runtime() { trace validate-https; }
wait_backend_healthy() { trace backend-http; }
stop_failed_docker_service() { trace "stop:$1"; }
docker_container_diagnostics() { trace "diagnostics:$1"; }

reset_trace
MOCK_FAIL_CONTAINER=""
wait_docker_container_healthy() {
  trace "healthy:$1"
  [[ "$1" != "${MOCK_FAIL_CONTAINER}" ]]
}
start_docker_application_stack
assert_trace $'validate-https\ncompose:up -d aid-server\nhealthy:aid-server\nbackend-http\ncompose:up -d aid-web\nhealthy:aid-web\ncompose:up -d nginx\nhealthy:aid-nginx\ncompose:up -d aid-updater\nhealthy:aid-updater'

# 后端失败时必须停止循环重启，且不能继续启动 Web/Nginx。
reset_trace
MOCK_FAIL_CONTAINER=aid-server
if start_docker_application_stack; then
  echo 'FAIL: backend health failure must abort the staged startup' >&2
  exit 1
fi
assert_trace $'validate-https\ncompose:up -d aid-server\nhealthy:aid-server\nstop:aid-server'

# 手动部署同样必须先让后端健康，再启动 Web/Nginx/升级器。
write_systemd_units() { trace units; }
write_nginx_site() { trace nginx; }
setup_updater() { trace updater; }
wait_manual_web_healthy() { trace web-http; }
manual_service_diagnostics() { trace "diagnostics:$1"; }
journalctl() { :; }
systemctl() {
  case "$*" in
    'list-unit-files') return 0 ;;
    'is-active --quiet aid-updater') return 0 ;;
    *) trace "systemctl:$*"; return 0 ;;
  esac
}

reset_trace
wait_backend_healthy() { trace backend-http; return 0; }
start_manual_application_stack
assert_trace $'units\nsystemctl:enable aid\nsystemctl:restart aid\nbackend-http\nsystemctl:enable aid-web\nsystemctl:restart aid-web\nweb-http\nnginx\nupdater'

# 手动后端失败时也必须停止服务，并禁止继续启动 Web。
reset_trace
wait_backend_healthy() { trace backend-http; return 1; }
if start_manual_application_stack; then
  echo 'FAIL: manual backend health failure must abort the staged startup' >&2
  exit 1
fi
assert_trace $'units\nsystemctl:enable aid\nsystemctl:restart aid\nbackend-http\ndiagnostics:aid\nsystemctl:stop aid'

echo 'startup preflight tests passed'
