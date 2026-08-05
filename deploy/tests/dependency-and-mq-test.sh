#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export AID_SH_LIBRARY_MODE=1
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT
ENV_FILE="${TMP_ROOT}/docker.env"
CONF="${TMP_ROOT}/manual.conf"

write_docker_config() {
  cat > "${ENV_FILE}" <<EOF
COMPOSE_PROFILES=$1
ROCKETMQ_ENABLED=$2
ROCKETMQ_NAMESERVER=$3
EOF
}

write_docker_config 'mysql,redis' false 'rocketmq-nameserver:9876'
validate_rocketmq_mode docker

write_docker_config 'mysql,redis,mq' true 'rocketmq-nameserver:9876'
validate_rocketmq_mode docker

write_docker_config 'mysql,redis' true 'mq-a.internal:9876;mq-b.internal:9876'
validate_rocketmq_mode docker

write_docker_config 'mysql,redis,mq' false 'rocketmq-nameserver:9876'
if (validate_rocketmq_mode docker >/dev/null 2>&1); then
  echo 'mq profile without ROCKETMQ_ENABLED=true must fail' >&2
  exit 1
fi

write_docker_config 'mysql,redis,mq' true 'mq.external:9876'
if (validate_rocketmq_mode docker >/dev/null 2>&1); then
  echo 'internal mq profile with external nameserver must fail' >&2
  exit 1
fi

cat > "${CONF}" <<'EOF'
ROCKETMQ_ENABLED=true
ROCKETMQ_NAMESERVER=mq-a.internal:9876;mq-b.internal:9876
EOF
validate_rocketmq_mode manual

mkdir -p "${TMP_ROOT}/bin"
cat > "${TMP_ROOT}/bin/docker" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  '--version') echo "Docker version ${MOCK_DOCKER_VERSION}, build test" ;;
  'compose version --short') echo "${MOCK_COMPOSE_VERSION}" ;;
  'info') exit 0 ;;
  *) exit 1 ;;
esac
EOF
cat > "${TMP_ROOT}/bin/git" <<'EOF'
#!/usr/bin/env bash
echo "git version ${MOCK_GIT_VERSION}"
EOF
cat > "${TMP_ROOT}/bin/nginx" <<'EOF'
#!/usr/bin/env bash
echo "nginx version: nginx/${MOCK_NGINX_VERSION}" >&2
EOF
chmod +x "${TMP_ROOT}/bin/docker" "${TMP_ROOT}/bin/git" "${TMP_ROOT}/bin/nginx"
PATH="${TMP_ROOT}/bin:${PATH}"

export MOCK_GIT_VERSION=2.47.2 MOCK_NGINX_VERSION=1.26.3
ensure_git_runtime manual >/dev/null
ensure_nginx_runtime manual >/dev/null

export MOCK_GIT_VERSION=1.7.12
if (ensure_git_runtime manual >/dev/null 2>&1); then
  echo 'Git below minimum version must fail in manual dependency mode' >&2
  exit 1
fi

export MOCK_GIT_VERSION=2.47.2 MOCK_NGINX_VERSION=1.17.10
if (ensure_nginx_runtime manual >/dev/null 2>&1); then
  echo 'Nginx below minimum version must fail in manual dependency mode' >&2
  exit 1
fi

export MOCK_DOCKER_VERSION=24.0.9 MOCK_COMPOSE_VERSION=2.20.3
docker_runtime_version_matches

export MOCK_DOCKER_VERSION=23.0.6 MOCK_COMPOSE_VERSION=2.20.3
if docker_runtime_version_matches; then
  echo 'Docker Engine below 24 must fail version gate' >&2
  exit 1
fi

export MOCK_DOCKER_VERSION=26.1.4 MOCK_COMPOSE_VERSION=2.19.9
if docker_runtime_version_matches; then
  echo 'Docker Compose below 2.20 must fail version gate' >&2
  exit 1
fi

echo 'dependency and RocketMQ tests passed'
