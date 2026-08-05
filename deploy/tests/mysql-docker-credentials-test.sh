#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

stateFile="${TMP_ROOT}/root-password"
callLog="${TMP_ROOT}/calls.log"
printf 'root-old\n' > "${stateFile}"

env_get() {
  case "$1" in
    MYSQL_ROOT_PASSWORD) echo root-new ;;
    DB_PASSWORD) echo db-new ;;
    DB_NAME) echo aid ;;
    DB_USERNAME) echo aid ;;
    *) echo "${2:-}" ;;
  esac
}

docker() {
  printf '%s|%s\n' "${MYSQL_PWD-}" "$*" >> "${callLog}"
  case "$1" in
    inspect) printf 'running\n'; return 0 ;;
    exec)
      if [[ "$*" == *"-uroot"* ]]; then
        if [[ "$*" == *"CREATE DATABASE"* ]]; then
          [[ "${MYSQL_PWD-}" == "$(cat "${stateFile}")" ]] || return 1
          printf 'root-new\n' > "${stateFile}"
          return 0
        fi
        [[ "${MYSQL_PWD-}" == "$(cat "${stateFile}")" ]]
        return $?
      fi
      [[ "${MYSQL_PWD-}" == "db-new" && "$*" == *"--database=aid"* \
        && "$*" == *"--user=aid"* ]]
      return $?
      ;;
    *) return 1 ;;
  esac
}

reconcile_docker_managed_mysql_credentials root-old >/dev/null
[[ "$(cat "${stateFile}")" == 'root-new' ]] \
  || { echo 'FAIL: Docker root password did not follow the current config' >&2; exit 1; }
grep -Fq "ALTER USER 'aid'@'%' IDENTIFIED BY 'db-new'" "${callLog}" \
  || { echo 'FAIL: Docker business password did not follow the current config' >&2; exit 1; }
grep -Fq 'root-old|exec -i -e MYSQL_PWD aid-mysql mysql' "${callLog}" \
  || { echo 'FAIL: previous container root credential was not used for safe migration' >&2; exit 1; }
grep -Fq 'root-new|exec -i -e MYSQL_PWD aid-mysql mysql' "${callLog}" \
  || { echo 'FAIL: current Docker root credential was not verified after migration' >&2; exit 1; }
grep -Fq 'db-new|exec -i -e MYSQL_PWD aid-mysql mysql' "${callLog}" \
  || { echo 'FAIL: current Docker business credential was not verified after migration' >&2; exit 1; }

echo 'Docker MySQL credential reconciliation tests passed'
