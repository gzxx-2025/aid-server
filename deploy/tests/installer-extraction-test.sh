#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_DATA_ROOT="${TMP_ROOT}/data"
export AID_SH_LIBRARY_MODE=1
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

STAGING_DIR="${TMP_ROOT}/staging"
mkdir -p "${STAGING_DIR}/backend" "${STAGING_DIR}/web-dist/server" \
  "${STAGING_DIR}/updater" "${STAGING_DIR}/installer/deploy/docker/nginx" \
  "${STAGING_DIR}/installer/deploy/docker/rocketmq" "${STAGING_DIR}/installer/sql"

printf 'jar' > "${STAGING_DIR}/backend/aid-admin.jar"
printf 'export default {}\n' > "${STAGING_DIR}/web-dist/server/index.mjs"
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
