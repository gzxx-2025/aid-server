#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

stateFile="${TMP_ROOT}/schema-state"
callLog="${TMP_ROOT}/calls.log"
orderFile="${TMP_ROOT}/startup-order"
printf 'empty\n' > "${stateFile}"

env_get() {
  case "$1" in
    MYSQL_ROOT_PASSWORD) echo root-config ;;
    DB_PASSWORD) echo db-config ;;
    DB_NAME) echo aid ;;
    DB_USERNAME) echo aid ;;
    *) echo "${2:-}" ;;
  esac
}

docker_profile_enabled() { [[ "$1" == 'mysql' ]]; }

trace_startup() { printf '%s\n' "$1" >> "${orderFile}"; }
ensure_docker_image() { trace_startup image; }
docker_container_env_value() { :; }
compose_cmd() { trace_startup compose; }
wait_docker_managed_mysql_bootstrap_complete() { trace_startup bootstrap; }
wait_docker_container_healthy() { trace_startup health; }
reconcile_docker_managed_mysql_credentials() { trace_startup reconcile; }

# 首次空目录必须等待 MySQL 官方 entrypoint 完成初始化后再同步账号；
# 已存在数据目录则先迁移旧凭证，保证重试可恢复。
ensure_mysql_ready docker
[[ "$(tr '\n' ' ' < "${orderFile}")" == 'image compose bootstrap health reconcile ' ]] \
  || { echo 'FAIL: fresh MySQL credential reconciliation raced with entrypoint initialization' >&2; exit 1; }
mkdir -p "${AID_DATA_ROOT}/mysql-data/mysql"
: > "${orderFile}"
ensure_mysql_ready docker
[[ "$(tr '\n' ' ' < "${orderFile}")" == 'image compose reconcile health ' ]] \
  || { echo 'FAIL: existing MySQL did not reconcile old credentials before health validation' >&2; exit 1; }

docker_managed_mysql_business_exec() {
  local query="$*" state
  state="$(cat "${stateFile}")"
  printf 'business|%s\n' "${query}" >> "${callLog}"
  if [[ "${query}" == *"table_name IN ('aid_config','sys_user')"* ]]; then
    case "${state}" in empty) echo 0 ;; initialized) echo 2 ;; partial) echo 1 ;; esac
  else
    case "${state}" in empty) echo 0 ;; initialized) echo 2 ;; partial) echo 1 ;; esac
  fi
}

docker_managed_mysql_root_exec() {
  printf 'root|%s\n' "$*" >> "${callLog}"
  cat >/dev/null
  printf 'initialized\n' > "${stateFile}"
}

docker_container_diagnostics() { printf 'diagnostics|%s\n' "$*" >> "${callLog}"; }

# 空库必须仅补导入一次基线，随后用业务账号校验核心表。
wait_docker_database_schema_ready >/dev/null
[[ "$(cat "${stateFile}")" == 'initialized' ]] \
  || { echo 'FAIL: empty managed MySQL was not initialized' >&2; exit 1; }
[[ "$(grep -c '^root|' "${callLog}")" == '1' ]] \
  || { echo 'FAIL: empty managed MySQL imported the baseline more than once' >&2; exit 1; }
grep -Fq -- '--default-character-set=utf8mb4 aid' "${callLog}" \
  || { echo 'FAIL: baseline import did not use the configured database' >&2; exit 1; }
grep -q '^business|' "${callLog}" \
  || { echo 'FAIL: schema verification did not use the configured business account' >&2; exit 1; }

# 有部分表时绝不能反复等待或再次覆盖导入；必须快速、明确地失败。
printf 'partial\n' > "${stateFile}"
: > "${callLog}"
if wait_docker_database_schema_ready >/dev/null 2>&1; then
  echo 'FAIL: partially initialized managed MySQL was accepted' >&2
  exit 1
fi
if grep -q '^root|' "${callLog}"; then
  echo 'FAIL: partially initialized managed MySQL was overwritten by a repeated baseline import' >&2
  exit 1
fi

echo 'Docker first-install MySQL schema tests passed'
