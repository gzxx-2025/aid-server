#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/managed/aid"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

mkdir -p "${CONFIG_ROOT}"
cat > "${ENV_FILE}" <<EOF
# AID Docker 部署配置（唯一配置真源）
DATA_ROOT=${TMP_ROOT}/wrong/aid
HTTP_PORT=80
ADMIN_PORT=8089
HTTPS_PORT=443
MYSQL_ROOT_PASSWORD=rootsecret12
DB_HOST=mysql
DB_PORT=3306
DB_NAME=aid
DB_USERNAME=aid
DB_PASSWORD=dbsecret1234
MYSQL_PORT=3306
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_USERNAME=
REDIS_PASSWORD=
REDIS_DATABASE=0
TOKEN_SECRET=tokensecret1234
BACKEND_PORT=8080
COMPOSE_PROFILES=mysql,redis
ROCKETMQ_ENABLED=false
ROCKETMQ_NAMESERVER=rocketmq-nameserver:9876
EOF

merge_runtime_configuration() { :; }
if ( ensure_env_file ) >/dev/null 2>&1; then
  echo 'FAIL: Docker DATA_ROOT mismatch must be rejected' >&2
  exit 1
fi

sed -i "s|^DATA_ROOT=.*|DATA_ROOT=${AID_DATA_ROOT}|" "${ENV_FILE}"
ensure_env_file >/dev/null
grep -Fq "\"dataRoot\": \"${AID_DATA_ROOT}\"" "${DEPLOYMENT_DESCRIPTOR}" \
  || { echo 'FAIL: custom DATA_ROOT descriptor was not persisted' >&2; exit 1; }

echo 'custom data root tests passed'
