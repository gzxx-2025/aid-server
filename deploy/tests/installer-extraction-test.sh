#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_DATA_ROOT="${TMP_ROOT}/data"
export AID_SH_LIBRARY_MODE=1
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"
# 单元测试不写宿主机 /usr/local/bin，只验证安装器落盘与交接逻辑。
install_management_command() { :; }

STAGING_DIR="${TMP_ROOT}/staging"
mkdir -p "${STAGING_DIR}/backend" "${STAGING_DIR}/web-dist" \
  "${STAGING_DIR}/updater" "${STAGING_DIR}/installer/deploy/docker/nginx" \
  "${STAGING_DIR}/installer/deploy/docker/rocketmq" "${STAGING_DIR}/installer/sql"

printf 'jar' > "${STAGING_DIR}/backend/aid-admin.jar"
printf '<!doctype html>\n' > "${STAGING_DIR}/web-dist/index.html"
printf '<!doctype html>\n' > "${STAGING_DIR}/web-dist/200.html"
printf '{"builtBy":"remote-source-build"}\n' > "${STAGING_DIR}/build-info.json"
printf 'updater' > "${STAGING_DIR}/updater/aid-updater_linux_amd64"
printf 'updater' > "${STAGING_DIR}/updater/aid-updater_linux_arm64"
cat > "${STAGING_DIR}/installer/deploy/aid.sh" <<'EOF'
#!/usr/bin/env bash
if [[ -n "${AID_TEST_ACTION_FILE:-}" ]]; then
  printf '%s\n' "${1:-}" > "${AID_TEST_ACTION_FILE}"
fi
EOF
printf 'DB_HOST=127.0.0.1\n' > "${STAGING_DIR}/installer/deploy/aid-deploy.conf.example"
printf 'COMPOSE_PROFILES=redis\n' > "${STAGING_DIR}/installer/deploy/docker/.env.example"
printf 'services: {}\n' > "${STAGING_DIR}/installer/deploy/docker/docker-compose.yml"
printf 'server {}\n' > "${STAGING_DIR}/installer/deploy/docker/nginx/aid-https.conf.template"
printf 'location / { try_files $uri $uri/ /200.html; }\n' > "${STAGING_DIR}/installer/deploy/docker/nginx/web-static.conf"
printf '#!/usr/bin/env bash\n' > "${STAGING_DIR}/installer/deploy/docker/rocketmq/broker-entrypoint.sh"
printf 'SELECT 1;\n' > "${STAGING_DIR}/installer/sql/aid-init.sql"
chmod +x "${STAGING_DIR}/installer/deploy/aid.sh"

DOT_PACKAGE="${TMP_ROOT}/dot-prefix.tar.gz"
PLAIN_PACKAGE="${TMP_ROOT}/plain-prefix.tar.gz"
WRAPPED_PACKAGE="${TMP_ROOT}/wrapped-prefix.tar.gz"
(cd "${STAGING_DIR}" && tar -czf "${DOT_PACKAGE}" ./*)
(cd "${STAGING_DIR}" && tar -czf "${PLAIN_PACKAGE}" *)
mkdir -p "${TMP_ROOT}/wrapped/aid-v-test"
cp -a "${STAGING_DIR}/." "${TMP_ROOT}/wrapped/aid-v-test/"
(cd "${TMP_ROOT}/wrapped" && tar -czf "${WRAPPED_PACKAGE}" aid-v-test)

assert_installer_ready() {
  [[ -f "${INSTALLER_ROOT}/deploy/aid.sh" ]]
  [[ -f "${INSTALLER_ROOT}/deploy/aid-deploy.conf.example" ]]
  [[ -f "${INSTALLER_ROOT}/deploy/docker/docker-compose.yml" ]]
  [[ -f "${INSTALLER_ROOT}/deploy/docker/nginx/web-static.conf" ]]
  [[ -f "${INSTALLER_ROOT}/sql/aid-init.sql" ]]
  [[ "$(cat "${INSTALLER_ROOT}/deploy/docker/.env")" == 'preserve-me' ]]
}

test_direct_extraction() {
  local package="$1"
  rm -rf "${INSTALLER_ROOT}"
  mkdir -p "${INSTALLER_ROOT}/deploy/docker"
  printf 'preserve-me\n' > "${INSTALLER_ROOT}/deploy/docker/.env"
  validate_release_package "${package}" yes
  extract_installer_from_package "${package}"
  assert_installer_ready
}

# 兼容旧源码包的 ./installer、新包的 installer，以及带发布包根目录的格式。
test_direct_extraction "${DOT_PACKAGE}"
test_direct_extraction "${PLAIN_PACKAGE}"
test_direct_extraction "${WRAPPED_PACKAGE}"

# 远程最新引导模式必须保留当前脚本，不能被缓存发布包中的旧 aid.sh 覆盖。
REMOTE_SCRIPT_DIR="${TMP_ROOT}/remote-bootstrap"
REMOTE_ACTION_FILE="${TMP_ROOT}/remote-bootstrap-action.txt"
mkdir -p "${REMOTE_SCRIPT_DIR}"
cat > "${REMOTE_SCRIPT_DIR}/aid.sh" <<'EOF'
#!/usr/bin/env bash
printf 'remote-latest:%s\n' "${1:-}" > "${AID_TEST_ACTION_FILE}"
EOF
chmod +x "${REMOTE_SCRIPT_DIR}/aid.sh"
# 已部署环境使用远程最新脚本时，即使业务版本相同，也必须持久化更新受管
# aid.sh；否则本次能执行 default，下一次 sudo aid default 仍会落回旧命令集。
(
  export AID_REMOTE_BOOTSTRAP=1
  SCRIPT_DIR="${REMOTE_SCRIPT_DIR}"
  handoff_to_managed_installer default
)
grep -Fq 'remote-latest' "${MANAGED_SCRIPT}" \
  || { echo 'remote bootstrap must persist the latest manager for future sudo aid commands' >&2; exit 1; }

(
  export AID_REMOTE_BOOTSTRAP=1 AID_TEST_ACTION_FILE="${REMOTE_ACTION_FILE}"
  SCRIPT_DIR="${REMOTE_SCRIPT_DIR}"
  bootstrap_installer_if_needed "${DOT_PACKAGE}" install-docker
)
[[ "$(cat "${REMOTE_ACTION_FILE}")" == 'remote-latest:install-docker' ]] \
  || { echo 'remote bootstrap script must replace and execute instead of the cached manager' >&2; exit 1; }
grep -Fq 'remote-latest' "${MANAGED_SCRIPT}" \
  || { echo 'managed installer must retain the remote bootstrap script' >&2; exit 1; }

test_bootstrap_action() {
  local package="$1" action="$2" actionFile
  actionFile="${TMP_ROOT}/${action}.txt"
  rm -rf "${INSTALLER_ROOT}"
  (
    export AID_TEST_ACTION_FILE="${actionFile}"
    bootstrap_installer_if_needed "${package}" "${action}"
  )
  [[ "$(cat "${actionFile}")" == "${action}" ]]
}

# Docker 与手动首次部署必须经过同一套兼容提取逻辑并正确交接动作。
test_bootstrap_action "${DOT_PACKAGE}" install-docker
test_bootstrap_action "${DOT_PACKAGE}" install-manual

# 后续升级刷新同样兼容旧包路径，并保留用户运行配置 .env。
mkdir -p "${INSTALLER_ROOT}/deploy/docker"
printf 'preserve-me\n' > "${INSTALLER_ROOT}/deploy/docker/.env"
refresh_managed_installer "${DOT_PACKAGE}"
assert_installer_ready

echo 'installer extraction tests passed'
