#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

ENV_FILE="${TMP_ROOT}/docker.env"
COMPOSE_DIR="${TMP_ROOT}/compose"
MOCK_BIN="${TMP_ROOT}/bin"
export MOCK_COMPOSE_PROFILE_FILE="${TMP_ROOT}/profile.txt"
mkdir -p "${COMPOSE_DIR}" "${MOCK_BIN}"
printf 'services: {}\n' > "${COMPOSE_DIR}/docker-compose.yml"

cat > "${MOCK_BIN}/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "${COMPOSE_PROFILES-<unset>}" > "${MOCK_COMPOSE_PROFILE_FILE}"
EOF
chmod +x "${MOCK_BIN}/docker"
PATH="${MOCK_BIN}:${PATH}"

assert_compose_profiles() {
  local expected="$1" actual
  COMPOSE_PROFILES=stale compose_cmd config
  actual="$(cat "${MOCK_COMPOSE_PROFILE_FILE}")"
  [[ "${actual}" == "${expected}" ]] || {
    echo "FAIL: expected COMPOSE_PROFILES='${expected}', actual='${actual}'" >&2
    exit 1
  }
}

cat > "${ENV_FILE}" <<'EOF'
DB_HOST=mysql
COMPOSE_PROFILES=mysql,redis
EOF
assert_compose_profiles 'mysql,redis'

# 兼容旧配置：内置 MySQL 地址存在时自动补齐 mysql Profile。
cat > "${ENV_FILE}" <<'EOF'
DB_HOST=mysql
COMPOSE_PROFILES=redis
EOF
assert_compose_profiles 'redis,mysql'

# 外部依赖且 Profile 为空时必须显式清空，不能继承调用者环境。
cat > "${ENV_FILE}" <<'EOF'
DB_HOST=db.internal
COMPOSE_PROFILES=
EOF
assert_compose_profiles ''

echo 'compose profile tests passed'
