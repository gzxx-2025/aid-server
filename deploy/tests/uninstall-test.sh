#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data/aid"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

# 彻底清理只允许足够深的真实绝对目录，拒绝 /data 等高风险根目录。
mkdir -p "${DATA_ROOT}"
touch "${DATA_ROOT}/sentinel"
remove_aid_manual_accounts() { :; }
purge_aid_data
[[ ! -e "${DATA_ROOT}" ]] || { echo 'FAIL: validated AID data root was not purged' >&2; exit 1; }
if ( DATA_ROOT=/data; validate_aid_purge_root ) >/dev/null 2>&1; then
  echo 'FAIL: high-risk /data root must never pass purge validation' >&2
  exit 1
fi

TRACE_FILE="${TMP_ROOT}/trace.txt"
trace() { printf '%s\n' "$*" >> "${TRACE_FILE}"; }
reset_trace() { : > "${TRACE_FILE}"; }
assert_trace() {
  local expected="$1" actual
  actual="$(cat "${TRACE_FILE}")"
  [[ "${actual}" == "${expected}" ]] || {
    printf 'FAIL: expected uninstall trace:\n%s\nactual:\n%s\n' "${expected}" "${actual}" >&2
    exit 1
  }
}

mkdir -p "${DATA_ROOT}"
require_root() { :; }
detect_mode() { echo "${MOCK_MODE}"; }
risk() { :; }
warn() { :; }
log() { :; }
ok() { :; }
section() { :; }
ask_yes_no() { echo y; }
ask() { echo DELETE-AID; }
remove_aid_docker_runtime() { trace "docker:$1"; }
remove_aid_system_services() { trace systemd; }
remove_aid_nginx_site() { trace nginx; }
remove_aid_command_links() { trace commands; }
remove_aid_updater_runtime() { trace "updater:$1"; }
purge_aid_data() { trace purge-data; }

# Docker/手动模式走同一卸载收口；keep 不得误删 DATA_ROOT。
MOCK_MODE=docker
reset_trace
do_uninstall keep
assert_trace $'docker:keep\nsystemd\nnginx\ncommands\nupdater:keep'

MOCK_MODE=manual
reset_trace
do_uninstall purge
assert_trace $'docker:purge\nsystemd\nnginx\ncommands\nupdater:purge\npurge-data'

echo 'uninstall tests passed'
