#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

# 本用例聚焦主应用与中间件的同版本恢复分支，升级器安装序列由 updater-recovery-test 覆盖。
updater_runtime_ready() { return 0; }

TRACE_FILE="${TMP_ROOT}/trace.txt"
trace() { printf '%s\n' "$*" >> "${TRACE_FILE}"; }
reset_trace() { : > "${TRACE_FILE}"; }
assert_trace() {
  local expected="$1" actual
  actual="$(cat "${TRACE_FILE}")"
  [[ "${actual}" == "${expected}" ]] || {
    printf 'FAIL: expected trace:\n%s\nactual:\n%s\n' "${expected}" "${actual}" >&2
    exit 1
  }
}

mkdir -p "${DATA_ROOT}/app/admin-dist" "${DATA_ROOT}/app/web-dist"
printf 'jar\n' > "${DATA_ROOT}/app/aid-admin.jar"
touch "${DATA_ROOT}/app/admin-dist/index.html" "${DATA_ROOT}/app/web-dist/index.html" \
  "${DATA_ROOT}/app/web-dist/200.html"

docker_profile_enabled() { [[ ",${MOCK_PROFILES:-}," == *",$1,"* ]]; }
docker() {
  local format container
  [[ "$1" == "inspect" ]] || return 1
  format="$3"; container="${@: -1}"
  case "${format}" in
    *State.Status*)
      if [[ "${container}" == "aid-server" && "${MOCK_SERVER_DOWN:-0}" == "1" \
          || "${container}" == "aid-redis" && "${MOCK_REDIS_DOWN:-0}" == "1" ]]; then
        echo exited
      else
        echo running
      fi ;;
    *State.Health*) echo healthy ;;
  esac
}

MOCK_PROFILES=""
MOCK_SERVER_DOWN=0
deployment_application_ready docker

MOCK_SERVER_DOWN=1
if deployment_application_ready docker; then
  echo 'FAIL: stopped backend must make deployment unhealthy' >&2
  exit 1
fi

# 同版本判断必须覆盖内置数据服务；Redis 未启动时不能误报全栈健康。
MOCK_SERVER_DOWN=0
MOCK_PROFILES="redis"
MOCK_REDIS_DOWN=1
if deployment_application_ready docker; then
  echo 'FAIL: stopped internal Redis must make deployment unhealthy' >&2
  exit 1
fi
MOCK_REDIS_DOWN=0
deployment_application_ready docker

systemctl() {
  case "$*" in
    'is-active --quiet aid') return 0 ;;
    'is-active --quiet aid-nginx.service') return 0 ;;
    'list-unit-files') return 0 ;;
    'is-active --quiet aid-updater') return 0 ;;
    *) return 1 ;;
  esac
}
select_existing_nginx_runtime() {
  NGINX_BIN=/bin/true
  NGINX_SERVICE=aid-nginx.service
  NGINX_SITE_DIR="${CONFIG_ROOT}/nginx/conf.d"
}
deployment_application_ready manual

rm -f "${DATA_ROOT}/app/aid-admin.jar"
if deployment_artifacts_ready; then
  echo 'FAIL: missing backend artifact must trigger same-version redeployment' >&2
  exit 1
fi

printf 'jar\n' > "${DATA_ROOT}/app/aid-admin.jar"
deployment_artifacts_ready

# install/update 命中同一版本但服务不健康时，必须进入 do_restart 的完整前置
# 检查与分阶段启动，不能打印“最新版”后直接退出。
require_root() { :; }
detect_mode() { echo docker; }
ensure_env_file() { :; }
dependency_install_mode() { echo manual; }
require_docker_runtime() { :; }
current_version() { echo 1.0.0-beta.2; }
resolve_official_release() {
  RESOLVED_VERSION=1.0.0-beta.2
  RESOLVED_CHANNEL=beta
  REQUESTED_RELEASE_CHANNEL=beta
}
ensure_official_updater_binary() { trace updater-binary-first; }
setup_updater() { trace "updater-start-first:$1"; }
version_compare() { echo 0; }
deployment_application_ready() { return 1; }
deployment_artifacts_ready() { return 0; }
do_restart() { trace restart-with-preflight; }
state_set() { trace "state:$1=$2"; }
state_get() { echo auto; }
install_management_command() { trace management-command; }
print_access_info() { trace access-info; }
ok() { :; }
warn() { :; }

reset_trace
do_update
assert_trace $'updater-binary-first\nupdater-start-first:docker\nmanagement-command\nrestart-with-preflight\nstate:DEPLOY_MODE=docker\nstate:DATA_ROOT='"${DATA_ROOT}"$'\nstate:CURRENT_VERSION=1.0.0-beta.2\nstate:RELEASE_CHANNEL=beta\nmanagement-command\naccess-info'

echo 'same-version recovery tests passed'
