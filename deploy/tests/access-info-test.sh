#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
export AID_PUBLIC_IP=8.8.4.4
export AID_PRIVATE_IP=10.20.30.40
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

valid_ipv4 8.8.4.4
valid_ipv4 10.20.30.40
private_ipv4 10.20.30.40
if valid_ipv4 999.20.30.40 || private_ipv4 8.8.4.4 || public_ipv4 127.0.0.1; then
  echo 'FAIL: IPv4 validation accepted an invalid classification' >&2
  exit 1
fi
[[ "$(detect_public_ipv4)" == "8.8.4.4" ]]
[[ "$(detect_private_ipv4)" == "10.20.30.40" ]]

ENV_FILE="${TMP_ROOT}/docker.env"
CONF="${TMP_ROOT}/aid-deploy.conf"
touch "${ENV_FILE}" "${CONF}"
detect_mode() { echo "${MOCK_MODE}"; }
setting_get() {
  case "$1" in
    HTTP_PORT) echo 80 ;;
    ADMIN_PORT) echo 8090 ;;
    *) echo "${2:-}" ;;
  esac
}
read_admin_entry_settings() {
  ADMIN_ENTRY_ENABLED_VALUE=true
  ADMIN_ENTRY_CODE_VALUE=4Azs8kbhPL5e
}
docker_profile_enabled() {
  [[ "$1" == "mysql" && "${MOCK_MODE}" == "docker" ]]
}
env_get() {
  case "$1" in
    COMPOSE_PROFILES) echo mysql,redis ;;
    MYSQL_PORT|DB_PORT) echo 3306 ;;
    DB_HOST) echo mysql ;;
    DB_NAME) echo aid ;;
    DB_USERNAME) echo aid ;;
    *) echo "${2:-}" ;;
  esac
}
conf_get() {
  case "$1" in
    HTTPS_ENABLED) echo false ;;
    DB_HOST) echo 127.0.0.1 ;;
    DB_PORT) echo 3306 ;;
    DB_NAME) echo aid ;;
    DB_USERNAME) echo aid ;;
    *) echo "${2:-}" ;;
  esac
}

MOCK_MODE=docker
dockerOutput="$(print_access_info)"
grep -Fq '用户端外网访问入口: http://8.8.4.4:80/' <<< "${dockerOutput}"
grep -Fq '用户端内网访问入口: http://10.20.30.40:80/' <<< "${dockerOutput}"
grep -Fq '管理端外网访问入口: http://8.8.4.4:8090/4Azs8kbhPL5e' <<< "${dockerOutput}"
grep -Fq '管理端内网访问入口: http://10.20.30.40:8090/4Azs8kbhPL5e' <<< "${dockerOutput}"
grep -Fq 'Navicat 连接内置 MySQL（推荐 SSH 隧道，不开放公网 3306）' <<< "${dockerOutput}"
grep -Fq 'COMPOSE_PROFILES=mysql,redis,https' <<< "${dockerOutput}"
if grep -Fq 'http://服务器IP' <<< "${dockerOutput}" || grep -Fq 'http://localhost:8090' <<< "${dockerOutput}"; then
  echo 'FAIL: legacy placeholder/local access links are still printed' >&2
  exit 1
fi

MOCK_MODE=manual
manualOutput="$(print_access_info)"
grep -Fq '用户端外网访问入口: http://8.8.4.4:80/' <<< "${manualOutput}"
grep -Fq 'Navicat 连接本机 MySQL（推荐 SSH 隧道，不开放公网 3306）' <<< "${manualOutput}"
grep -Fq 'HTTPS_ENABLED=true' <<< "${manualOutput}"

echo 'access info tests passed'
