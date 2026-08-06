#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

# CentOS 7 的 Bash 4.2 在 set -u 下展开空数组会直接退出。模拟系统没有任何
# 可用 Nginx，函数必须正常返回“未找到”，不能出现 candidates[@] 未绑定错误。
nginx_binary_version() { return 0; }
status=0
output="$( ( PATH="${TMP_ROOT}/empty-path" select_existing_nginx_runtime ) 2>&1)" || status=$?
[[ "${status}" == "1" ]] \
  || { echo "FAIL: empty Nginx detection returned ${status}: ${output}" >&2; exit 1; }
[[ "${output}" != *'unbound variable'* && "${output}" != *'未绑定变量'* ]] \
  || { echo "FAIL: empty Nginx candidates triggered nounset: ${output}" >&2; exit 1; }
if declare -f select_existing_nginx_runtime | grep -Fq 'candidates[@]'; then
  echo 'FAIL: Nginx runtime detection still expands an optional array' >&2
  exit 1
fi

echo 'Nginx runtime detection tests passed'
