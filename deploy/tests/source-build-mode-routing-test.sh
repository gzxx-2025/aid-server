#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

# Alpine/musl 控制容器不能直接执行 glibc JDK。固定归档元数据必须可独立校验，
# 同时禁止把错误文本路径中的版本号误当成成功的 java -version 输出。
fakeJdk="${TMP_ROOT}/temurin-17.0.20-x64"
mkdir -p "${fakeJdk}/bin"
cat > "${fakeJdk}/release" <<'EOF'
IMPLEMENTOR="Eclipse Adoptium"
IMPLEMENTOR_VERSION="Temurin-17.0.20+8"
JAVA_VERSION="17.0.20"
OS_ARCH="x86_64"
OS_NAME="Linux"
EOF
cat > "${fakeJdk}/bin/java" <<'EOF'
#!/usr/bin/env bash
echo "$0: cannot execute: required file not found" >&2
exit 127
EOF
chmod 755 "${fakeJdk}/bin/java"
jdk_home_metadata_matches "${fakeJdk}" x64 \
  || { echo 'FAIL: valid pinned JDK metadata was rejected' >&2; exit 1; }
if jdk_runtime_matches "${fakeJdk}"; then
  echo 'FAIL: a failed JDK execution was accepted because its path contained the version' >&2
  exit 1
fi
if jdk_home_metadata_matches "${fakeJdk}" aarch64; then
  echo 'FAIL: JDK metadata accepted the wrong CPU architecture' >&2
  exit 1
fi

# 首次确认文案必须按部署方式区分；非 Docker 不能再误报“拉取 Docker 镜像”。
RESOLVED_VERSION=1.0.0-test
RESOLVED_CHANNEL=beta
AID_ASSUME_YES=1
dockerConfirmText="$(confirm_first_install docker 2>&1)"
manualConfirmText="$(confirm_first_install manual 2>&1)"
grep -Fq 'Docker 部署会拉取所需镜像' <<< "${dockerConfirmText}" \
  || { echo 'FAIL: Docker first-install notice is inaccurate' >&2; exit 1; }
grep -Fq '非 Docker 部署会按配置检查或准备宿主机依赖' <<< "${manualConfirmText}" \
  || { echo 'FAIL: manual first-install notice is missing' >&2; exit 1; }
grep -Fq '用户端口 : 80' <<< "${manualConfirmText}" \
  || { echo 'FAIL: manual first-install ports are missing' >&2; exit 1; }
if grep -Fq '拉取 Docker 镜像' <<< "${manualConfirmText}"; then
  echo 'FAIL: manual first-install notice mentions Docker image pulling' >&2
  exit 1
fi
unset AID_ASSUME_YES

call_log="${TMP_ROOT}/calls.log"
docker() {
  printf 'docker %s\n' "$*" >> "${call_log}"
  return 0
}
ensure_docker_image() {
  printf 'image %s\n' "$*" >> "${call_log}"
}

# 手动/systemd 模式即便宿主机存在可用 Docker，也不得探测、拉取或调用 Docker。
prepare_source_build_images host
[[ ! -s "${call_log}" ]] \
  || { echo 'FAIL: host source-build mode invoked Docker image preparation' >&2; exit 1; }

# Docker 模式必须主动验证 Docker 并准备容器构建镜像，不能静默退回宿主机构建。
prepare_source_build_images docker
grep -Fqx 'docker info' "${call_log}" \
  || { echo 'FAIL: docker source-build mode did not require Docker Engine' >&2; exit 1; }
grep -Fq "image ${SOURCE_GIT_IMAGE} Git源码拉取" "${call_log}" \
  || { echo 'FAIL: docker source-build mode did not prepare isolated Git image' >&2; exit 1; }
grep -Fq "image ${SOURCE_MAVEN_IMAGE} Maven构建基础" "${call_log}" \
  || { echo 'FAIL: docker source-build mode did not prepare Maven image' >&2; exit 1; }
grep -Fq "image ${SOURCE_NODE_IMAGE} Node.js 22.22.0构建" "${call_log}" \
  || { echo 'FAIL: docker source-build mode did not prepare Node image' >&2; exit 1; }
grep -Fq "image ${SOURCE_GO_IMAGE} Go构建" "${call_log}" \
  || { echo 'FAIL: docker source-build mode did not prepare Go image' >&2; exit 1; }

if (unset AID_SOURCE_BUILD_MODE; require_source_build_mode >/dev/null 2>&1); then
  echo 'FAIL: official source-build path accepted a missing mode' >&2
  exit 1
fi

# 手动构建的 Git 即便在运行过程中消失，也必须直接失败，禁止退回 Docker Git 容器。
: > "${call_log}"
SOURCE_BUILDER_NAME='missing-builder-for-host-mode-test.sh'
command() {
  if [[ "$1" == '-v' && "${2:-}" == 'git' ]]; then
    return 1
  fi
  builtin command "$@"
}
if (bootstrap_source_builder host >/dev/null 2>&1); then
  echo 'FAIL: host source-build mode accepted a missing Git runtime' >&2
  exit 1
fi
unset -f command
[[ ! -s "${call_log}" ]] \
  || { echo 'FAIL: host source-build mode used Docker when Git was unavailable' >&2; exit 1; }
SOURCE_BUILDER_NAME='build-release-from-source.sh'

old_builder="${TMP_ROOT}/old-build-release-from-source.sh"
printf '%s\n' '#!/bin/sh' 'exit 0' > "${old_builder}"
if source_builder_supports_explicit_mode "${old_builder}"; then
  echo 'FAIL: legacy source builder without explicit mode support was accepted' >&2
  exit 1
fi
source_builder_supports_explicit_mode "${ROOT_DIR}/deploy/build-release-from-source.sh" \
  || { echo 'FAIL: current source builder was not recognized as explicit-mode capable' >&2; exit 1; }

mkdir -p "${INSTALLER_ROOT}/deploy"
printf '%s\n' '#!/bin/sh' 'exit 0' > "${INSTALLER_ROOT}/deploy/${SOURCE_BUILDER_NAME}"
bootstrap_source_builder host
cmp -s "${ROOT_DIR}/deploy/build-release-from-source.sh" "${INSTALLER_ROOT}/deploy/${SOURCE_BUILDER_NAME}" \
  || { echo 'FAIL: local explicit-mode builder was not synchronized to managed installer' >&2; exit 1; }

assert_before() {
  local body="$1" first="$2" second="$3" firstOffset secondOffset
  firstOffset="${body%%"${first}"*}"
  secondOffset="${body%%"${second}"*}"
  [[ "${firstOffset}" != "${body}" && "${secondOffset}" != "${body}" ]] \
    || { echo "FAIL: missing route marker ${first} or ${second}" >&2; exit 1; }
  (( ${#firstOffset} < ${#secondOffset} )) \
    || { echo "FAIL: ${first} must occur before ${second}" >&2; exit 1; }
}

assert_before "$(declare -f do_install_docker)" 'set_source_build_mode docker' 'prepare_install_package'
assert_before "$(declare -f do_install_manual)" 'set_source_build_mode manual' 'prepare_install_package'
assert_before "$(declare -f do_update)" 'set_source_build_mode "${mode}"' 'prepare_install_package'
assert_before "$(declare -f ensure_source_package)" 'require_source_build_mode' 'sourceBuildMode="${AID_SOURCE_BUILD_MODE}"'
assert_before "$(declare -f ensure_source_package)" 'bootstrap_source_builder "${sourceBuildMode}"' 'prepare_source_build_images "${sourceBuildMode}"'
assert_before "$(declare -f do_setup_updater)" 'set_source_build_mode "${mode}"' 'bootstrap_source_builder "${AID_SOURCE_BUILD_MODE}"'

builder_file="${ROOT_DIR}/deploy/build-release-from-source.sh"
prepareJdkLine="$(grep -n '^prepare_exact_jdk$' "${builder_file}" | cut -d: -f1)"
detectArchLine="$(grep -n '^detect_current_updater_arch$' "${builder_file}" | cut -d: -f1)"
[[ "${prepareJdkLine}" =~ ^[0-9]+$ && "${detectArchLine}" =~ ^[0-9]+$ \
    && "${prepareJdkLine}" -lt "${detectArchLine}" ]] \
  || { echo 'FAIL: pinned JDK must be prepared before Docker/host source-build routing' >&2; exit 1; }
grep -Fqx '# AID_SOURCE_BUILD_MODE_CAPABILITY=explicit-v1' "${builder_file}" \
  || { echo 'FAIL: source builder lacks the explicit-mode capability marker' >&2; exit 1; }
grep -Fq 'SOURCE_BUILD_MODE="${AID_SOURCE_BUILD_MODE:-auto}"' "${builder_file}" \
  || { echo 'FAIL: source builder does not accept AID_SOURCE_BUILD_MODE' >&2; exit 1; }
grep -Fq 'build_with_host' "${builder_file}" \
  || { echo 'FAIL: source builder no longer has the host build route' >&2; exit 1; }
grep -Fq 'build_with_docker' "${builder_file}" \
  || { echo 'FAIL: source builder no longer has the Docker build route' >&2; exit 1; }
[[ "$(grep -Fc 'if [ "$USE_DOCKER" = yes ]; then' "${builder_file}")" -ge 3 ]] \
  || { echo 'FAIL: source builder does not route Git operations by explicit build mode' >&2; exit 1; }
[[ "$(grep -Fc '非 Docker 构建拒绝使用 Docker 回退' "${builder_file}")" -ge 2 ]] \
  || { echo 'FAIL: source builder did not guard all Git Docker fallbacks' >&2; exit 1; }
grep -Fq 'ensure_docker_image "$GIT_IMAGE"' "${builder_file}" \
  || { echo 'FAIL: docker source builder does not unconditionally prepare its Git image' >&2; exit 1; }

echo 'source build mode routing tests passed'
