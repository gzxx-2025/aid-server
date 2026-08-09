#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/custom/aid"
export AID_UPDATER_CONFIG_DIR="${TMP_ROOT}/etc/aid-updater"
export AID_UPDATER_DATA_DIR="${TMP_ROOT}/var/aid-updater"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

mkdir -p "${CONFIG_ROOT}"
CONF="${CONFIG_ROOT}/manual-runtime.conf"
ENV_FILE="${CONFIG_ROOT}/docker-runtime.env"
cat > "${CONF}" <<EOF
# AID 手动部署配置（唯一配置真源）
DATA_ROOT=${DATA_ROOT}
BACKEND_PORT=8080
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=aid
DB_USERNAME=aid
DB_PASSWORD=manualsecret
EOF
cat > "${ENV_FILE}" <<EOF
# AID Docker 部署配置（唯一配置真源）
DATA_ROOT=${DATA_ROOT}
BACKEND_PORT=8080
MYSQL_ROOT_PASSWORD=dockersecret
DB_HOST=mysql
DB_PORT=3306
DB_NAME=aid
DB_USERNAME=aid
DB_PASSWORD=dockerbusiness
COMPOSE_PROFILES=mysql,redis
EOF

write_updater_config manual
grep -Fq '"serviceManager": "systemd"' "${UPDATER_CONFIG_FILE}" \
  || { echo 'FAIL: manual updater manager was not written' >&2; exit 1; }
grep -Fq "\"configPath\": \"${CONF}\"" "${UPDATER_CONFIG_FILE}" \
  || { echo 'FAIL: custom manual config path was not retained' >&2; exit 1; }
grep -Fq '"mode": "systemd"' "${DEPLOYMENT_DESCRIPTOR}" \
  || { echo 'FAIL: manual deployment descriptor was not written' >&2; exit 1; }

# 模拟手动部署失败后切换 Docker；升级器配置和 descriptor 必须一起收敛，
# 不得继续读取上一种部署方式的路径或服务管理器。
write_updater_config docker
grep -Fq '"serviceManager": "docker"' "${UPDATER_CONFIG_FILE}" \
  || { echo 'FAIL: Docker updater manager was not written after mode switch' >&2; exit 1; }
grep -Fq "\"configPath\": \"${ENV_FILE}\"" "${UPDATER_CONFIG_FILE}" \
  || { echo 'FAIL: custom Docker config path was not retained' >&2; exit 1; }
grep -Fq '"mode": "docker"' "${DEPLOYMENT_DESCRIPTOR}" \
  || { echo 'FAIL: Docker deployment descriptor was not written after mode switch' >&2; exit 1; }

# 再模拟 Docker 失败后切回手动部署，验证转换可逆且不残留 Docker 语义。
write_updater_config manual
grep -Fq '"serviceManager": "systemd"' "${UPDATER_CONFIG_FILE}" \
  || { echo 'FAIL: updater manager did not return to systemd' >&2; exit 1; }
grep -Fq "\"configPath\": \"${CONF}\"" "${UPDATER_CONFIG_FILE}" \
  || { echo 'FAIL: updater retained stale Docker config path' >&2; exit 1; }

TRACE_FILE="${TMP_ROOT}/runtime-transition.trace"
: > "${TRACE_FILE}"
aid_systemd_unit_belongs_to_current_install() { return 0; }
systemctl() { printf 'systemctl:%s\n' "$*" >> "${TRACE_FILE}"; }
stop_conflicting_updater_runtime docker
grep -Fxq 'systemctl:disable --now aid-updater' "${TRACE_FILE}" \
  || { echo 'FAIL: Docker transition did not stop the old systemd updater' >&2; exit 1; }

: > "${TRACE_FILE}"
aid_systemd_unit_belongs_to_current_install() { return 1; }
if stop_conflicting_updater_runtime docker >/dev/null 2>&1; then
  echo 'FAIL: unknown active systemd updater must block Docker transition' >&2
  exit 1
fi
if grep -Fq 'disable --now' "${TRACE_FILE}"; then
  echo 'FAIL: unknown systemd updater must not be modified' >&2
  exit 1
fi

: > "${TRACE_FILE}"
aid_docker_daemon_available() { return 0; }
aid_docker_container_belongs_to_current_install() { [[ "$1" == 'aid-updater' ]]; }
docker() {
  printf 'docker:%s\n' "$*" >> "${TRACE_FILE}"
  [[ "$*" == *"--format {{.State.Running}}"* ]] && printf 'true\n'
  return 0
}
stop_conflicting_updater_runtime manual
grep -Fxq 'docker:rm -f aid-updater' "${TRACE_FILE}" \
  || { echo 'FAIL: manual transition did not remove the old Docker updater' >&2; exit 1; }

: > "${TRACE_FILE}"
aid_docker_container_belongs_to_current_install() { return 1; }
if stop_conflicting_updater_runtime manual >/dev/null 2>&1; then
  echo 'FAIL: unknown active Docker updater must block manual transition' >&2
  exit 1
fi
if grep -Fq 'docker:rm -f aid-updater' "${TRACE_FILE}"; then
  echo 'FAIL: unknown Docker updater must not be modified' >&2
  exit 1
fi

echo 'updater mode switch tests passed'
