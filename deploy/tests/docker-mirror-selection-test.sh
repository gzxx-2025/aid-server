#!/usr/bin/env bash
set -Eeuo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "${TEST_DIR}/.." && pwd)"

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT=/tmp/aid-mirror-selection-test
# shellcheck source=../aid.sh
source "${DEPLOY_DIR}/aid.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# 模拟 Registry 根接口：fast 最快、slow 较慢、dead 不可达。
curl() {
  local url="${*: -1}"
  case "${url}" in
    https://fast.example/v2/) printf '401 0.040' ;;
    https://slow.example/v2/) printf '200 0.320' ;;
    *) return 28 ;;
  esac
}

AID_DOCKER_MIRRORS='HTTPS://slow.example/,fast.example,slow.example,dead.example'
DOCKER_MIRRORS_RESOLVED=0
DOCKER_MIRROR_ORDER=''
resolve_docker_mirror_order
[[ "${DOCKER_MIRROR_ORDER}" == 'fast.example slow.example dead.example' ]] \
  || fail "测速、去重或末位回退顺序错误: ${DOCKER_MIRROR_ORDER}"

[[ "$(dockerhub_mirror_image fast.example mysql:5.7)" == 'fast.example/library/mysql:5.7' ]] \
  || fail '官方 library 镜像映射错误'
[[ "$(dockerhub_mirror_image fast.example apache/rocketmq:5.3.1)" == 'fast.example/apache/rocketmq:5.3.1' ]] \
  || fail '组织镜像映射错误'
[[ -z "$(normalize_docker_mirror 'https://bad.example/path?token=secret' 2>/dev/null || true)" ]] \
  || fail '带查询参数的镜像地址未被拒绝'

# Registry 测速只是初排；当前镜像的 manifest 可用者应优先，真实 pull 失败后
# 仍必须继续尝试 manifest 预检失败但可能支持普通 pull 的候选。
PULL_ATTEMPTS=''
probe_docker_image_manifest() {
  [[ "$1" == 'fast.example/library/mysql:5.7' ]]
}
pull_docker_image() {
  PULL_ATTEMPTS+="$1 "
  [[ "$1" == 'slow.example/library/mysql:5.7' ]]
}
local_image_matches_digest() { return 0; }
docker() {
  [[ "$1" == tag ]]
}

DOCKER_MIRROR_ORDER='slow.example fast.example dead.example'
DOCKER_MIRRORS_RESOLVED=1
try_docker_mirrors 'mysql:5.7' 'MySQL5.7' 'sha256:test' \
  || fail '实际拉取未回退到可用镜像'
[[ "${PULL_ATTEMPTS}" == 'fast.example/library/mysql:5.7 slow.example/library/mysql:5.7 ' ]] \
  || fail "manifest 预检与实际拉取回退顺序错误: ${PULL_ATTEMPTS}"

echo 'PASS: Docker 多镜像测速与实际拉取回退'
