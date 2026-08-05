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
TRACE_FILE="${TMP_ROOT}/trace.txt"
trace() { printf '%s\n' "$*" >> "${TRACE_FILE}"; }
log() { :; }
ok() { :; }
err() { :; }

# 内置 Redis 必须先创建容器并等待健康，而不是只检查镜像。
cat > "${ENV_FILE}" <<'EOF'
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_USERNAME=
REDIS_PASSWORD=
REDIS_DATABASE=0
COMPOSE_PROFILES=mysql,redis
EOF
docker_profile_enabled() { [[ "$1" == "redis" ]]; }
compose_cmd() { trace "compose:$*"; }
wait_docker_container_healthy() { trace "healthy:$1"; }
: > "${TRACE_FILE}"
ensure_redis_ready
[[ "$(cat "${TRACE_FILE}")" == $'compose:up -d redis\nhealthy:aid-redis' ]] \
  || { echo 'FAIL: internal Redis must start and pass health check' >&2; exit 1; }

# 外部 Redis 必须从容器网络校验认证与 Redis 6+ 版本，密码不得进入命令参数。
cat > "${ENV_FILE}" <<'EOF'
REDIS_HOST=redis.internal
REDIS_PORT=6380
REDIS_USERNAME=aid
REDIS_PASSWORD=secret-do-not-log
REDIS_DATABASE=2
COMPOSE_PROFILES=mysql
EOF
docker_profile_enabled() { return 1; }
ensure_docker_image() { trace "image:$1"; }
MOCK_REDIS_VERSION=7.2.15
docker() {
  trace "docker:$*"
  printf 'redis_version:%s\r\n' "${MOCK_REDIS_VERSION}"
}
: > "${TRACE_FILE}"
ensure_redis_ready
grep -Fq 'image:redis:7-alpine' "${TRACE_FILE}"
grep -Fq -- '--network bridge' "${TRACE_FILE}"
grep -Fq -- '-h redis.internal -p 6380 -n 2 --user aid INFO server' "${TRACE_FILE}"
if grep -Fq 'secret-do-not-log' "${TRACE_FILE}"; then
  echo 'FAIL: Redis password must not appear in Docker command arguments' >&2
  exit 1
fi

MOCK_REDIS_VERSION=5.0.14
if ensure_redis_ready >/dev/null 2>&1; then
  echo 'FAIL: external Redis below version 6 must be rejected' >&2
  exit 1
fi

echo 'redis preflight tests passed'
