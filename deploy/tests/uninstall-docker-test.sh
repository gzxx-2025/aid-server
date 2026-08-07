#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

# 受管脚本自身路径必须优先恢复自定义数据根，不依赖 AID_DATA_ROOT 或 Docker。
CUSTOM_ROOT="${TMP_ROOT}/custom/aid"
mkdir -p "${CUSTOM_ROOT}/installer/deploy"
cp "${ROOT_DIR}/deploy/aid.sh" "${CUSTOM_ROOT}/installer/deploy/aid.sh"
env -u AID_DATA_ROOT AID_SH_LIBRARY_MODE=1 bash -c '
  source "$1"
  [[ "${DATA_ROOT}" == "$2" ]] || exit 41
' _ "${CUSTOM_ROOT}/installer/deploy/aid.sh" "${CUSTOM_ROOT}" \
  || fail 'managed script path did not infer the custom DATA_ROOT'

# 管理链接和 unit 都不存在时，新版 Docker 标签仍必须只读恢复唯一自定义根。
DOCKER_ONLY_ROOT="${TMP_ROOT}/docker-only/aid"
MOCK_BIN="${TMP_ROOT}/mock-bin"
mkdir -p "${DOCKER_ONLY_ROOT}" "${MOCK_BIN}" "${TMP_ROOT}/empty-units" "${TMP_ROOT}/empty-bin"
cat > "${MOCK_BIN}/docker" <<'EOF'
#!/usr/bin/env bash
case "${1:-}:${2:-}" in
  info:*) exit 0 ;;
  ps:-a) printf 'aid-server\n' ;;
  inspect:--format)
    case "${3:-}" in
      *com.aid.managed*) printf 'true\n' ;;
      *com.aid.data_root*) printf '%s\n' "${MOCK_DOCKER_ROOT}" ;;
      *) exit 0 ;;
    esac
    ;;
  *) exit 1 ;;
esac
EOF
chmod 700 "${MOCK_BIN}/docker"
env -u AID_DATA_ROOT PATH="${MOCK_BIN}:${PATH}" MOCK_DOCKER_ROOT="${DOCKER_ONLY_ROOT}" \
  AID_LOCAL_BIN_DIR="${TMP_ROOT}/empty-bin" AID_SYSTEMD_UNIT_DIR="${TMP_ROOT}/empty-units" \
  AID_SH_LIBRARY_MODE=1 bash -c '
    source "$1"
    [[ "${DATA_ROOT}" == "$2" ]] || exit 42
  ' _ "${ROOT_DIR}/deploy/aid.sh" "${DOCKER_ONLY_ROOT}" \
  || fail 'Docker ownership labels did not infer the custom DATA_ROOT'

export AID_SH_LIBRARY_MODE=1
export AID_UNINSTALL_TEST_MODE=1
export AID_UNINSTALL_TEST_ROOT="${TMP_ROOT}"
export AID_DATA_ROOT="${TMP_ROOT}/data/aid"
export AID_SYSTEMD_UNIT_DIR="${TMP_ROOT}/systemd"
export AID_LOCAL_BIN_DIR="${TMP_ROOT}/local-bin"
export AID_UPDATER_CONFIG_DIR="${TMP_ROOT}/etc/aid-updater"
export AID_UPDATER_DATA_DIR="${TMP_ROOT}/var/lib/aid-updater"
export AID_JAVA_PROFILE_FILE="${TMP_ROOT}/profile/aid-java.sh"
export AID_NGINX_SITE_DIRS="${TMP_ROOT}/nginx"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

require_root() { :; }
warn() { :; }
log() { :; }
ok() { :; }
err() { :; }
section() { :; }
risk() { :; }
ask() { echo DELETE-AID; }
ask_yes_no() { echo y; }
detect_mode() { echo docker; }
select_existing_nginx_runtime() { return 1; }
remove_aid_system_services() { :; }
remove_aid_nginx_site() { :; }
remove_aid_command_links() { :; }
remove_aid_updater_runtime() { :; }
remove_aid_manual_accounts() { :; }
verify_aid_purge_cleanup() { return 0; }

mkdir -p "${DATA_ROOT}/config" "${DATA_ROOT}/installer/deploy/docker"
cat > "${AID_ROOT_MARKER}" <<EOF
AID_MANAGED_ROOT=1
AID_DATA_ROOT=${DATA_ROOT}
AID_MANAGER_SCRIPT=${MANAGED_SCRIPT}
EOF
printf '# AID 统一部署管理脚本\n' > "${DATA_ROOT}/installer/deploy/aid.sh"

DOCKER_DAEMON=up
OWNED_REMOVED=0
UNOWNED_REMOVED=0
IMAGE_REMOVED=0
CONTAINER_MODE=owned
docker() {
  local command="${1:-}" sub="${2:-}" template="${3:-}" container="${4:-}"
  case "${command}:${sub}" in
    info:*) [[ "${DOCKER_DAEMON}" == up ]] ;;
    inspect:*)
      [[ "${container}" == aid-server || "${sub}" == aid-server ]] || return 1
      if [[ "${sub}" == --format ]]; then template="${3:-}"; container="${4:-}"; fi
      case "${template}" in
        *com.aid.managed*) [[ "${CONTAINER_MODE}" == owned ]] && printf 'true\n' || printf 'true\n' ;;
        *com.aid.data_root*) [[ "${CONTAINER_MODE}" == owned ]] && printf '%s\n' "${DATA_ROOT}" || printf '%s\n' "${TMP_ROOT}/other/aid" ;;
        *.Mounts*) printf '%s/app\n' "${DATA_ROOT}" ;;
        *.NetworkSettings*) printf 'aid-owned-network\n' ;;
        *'.Image}}|{{.Config.Image'*) printf 'sha256:owned|aid/openjdk:17\n' ;;
        *) return 0 ;;
      esac
      ;;
    rm:*)
      [[ "${2:-}" == -f ]] || return 1
      [[ "${3:-}" == aid-server ]] || return 1
      if [[ "${CONTAINER_MODE}" == owned ]]; then OWNED_REMOVED=1; else UNOWNED_REMOVED=1; fi
      ;;
    network:ls) printf 'aid-owned-network\n' ;;
    network:inspect)
      case "${4:-}" in
        *com.aid.managed*) printf 'true\n' ;;
        *com.aid.data_root*) printf '%s\n' "${DATA_ROOT}" ;;
        *) printf '%s\n' "${DATA_ROOT}/installer/deploy/docker" ;;
      esac
      ;;
    network:rm) return 0 ;;
    image:rm) IMAGE_REMOVED=1 ;;
    ps:*) return 0 ;;
    *) return 0 ;;
  esac
}

# 新标签属于当前根时才允许删除；另一个 AID 实例同名容器必须保留。
aid_docker_container_belongs_to_current_install aid-server || fail 'owned container was not recognized'
CONTAINER_MODE=unowned
if aid_docker_container_belongs_to_current_install aid-server; then fail 'unowned container was accepted'; fi
CONTAINER_MODE=owned
remove_aid_docker_runtime purge
[[ "${OWNED_REMOVED}" == 1 && "${IMAGE_REMOVED}" == 1 ]] || fail 'owned Docker resources were not removed'
CONTAINER_MODE=unowned
OWNED_REMOVED=0
remove_aid_docker_runtime purge
[[ "${OWNED_REMOVED}" == 0 && "${UNOWNED_REMOVED}" == 0 ]] || fail 'unowned same-name container was removed'

# Docker 守护进程不可用且已存在 Docker 证据时，必须在任何删除前失败。
DOCKER_DAEMON=down
mkdir -p "${DATA_ROOT}/mysql-data"
if ( do_uninstall --purge ); then fail 'daemon-down Docker uninstall must fail before deletion'; fi
[[ -d "${DATA_ROOT}" ]] || fail 'daemon-down preflight deleted DATA_ROOT'

# Compose 必须标注服务和默认网络的归属根。
grep -Fq 'com.aid.managed: "true"' "${ROOT_DIR}/deploy/docker/docker-compose.yml" || fail 'Compose AID ownership label is missing'
grep -Fq 'com.aid.data_root: "${DATA_ROOT:-/data/aid}"' "${ROOT_DIR}/deploy/docker/docker-compose.yml" || fail 'Compose data-root label is missing'
grep -Fq 'LANG: C.UTF-8' "${ROOT_DIR}/deploy/docker/docker-compose.yml" || fail 'Docker server UTF-8 LANG is missing'
grep -Fq 'LC_ALL: C.UTF-8' "${ROOT_DIR}/deploy/docker/docker-compose.yml" || fail 'Docker server UTF-8 LC_ALL is missing'

echo 'docker uninstall tests passed'
