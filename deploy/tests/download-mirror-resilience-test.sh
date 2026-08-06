#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export AID_SH_LIBRARY_MODE=1
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT
DATA_ROOT="${TMP_ROOT}/data"
mkdir -p "${DATA_ROOT}"

node_a='https://dg2.bt.cn'
node_b='https://download.bt.cn'
node_c='https://download-cdn1.bt.cn'
node_d='https://ctcc1-node.bt.cn'
probe_bt_node() {
  case "$1" in
    "${node_a}") echo 'fast 1800 80' ;;
    "${node_b}") echo 'normal 2400 420' ;;
    "${node_c}") echo 'fast 2500 120' ;;
    *) return 1 ;;
  esac
}
resolve_dependency_region() { RESOLVED_DEPENDENCY_REGION=cn; }
mapfile -t ranked < <(rank_bt_mirror_nodes "${node_a}" "${node_b}" "${node_c}" "${node_d}")
[[ "${ranked[0]}" == "${node_c}" && "${ranked[1]}" == "${node_a}" \
    && "${ranked[2]}" == "${node_b}" && "${ranked[3]}" == "${node_d}" ]] \
  || { printf 'FAIL: AID镜像节点排序不符合吞吐/延迟规则: %s\n' "${ranked[*]}" >&2; exit 1; }

read -r -a bt_nodes <<< "${BT_MIRROR_NODES_CN}"
[[ "${#bt_nodes[@]}" -eq 10 ]] || { echo 'FAIL: AID HTTPS 镜像池必须包含 10 个唯一节点' >&2; exit 1; }
[[ " ${BT_MIRROR_NODES_CN} " != *' http://'* ]] || { echo 'FAIL: 镜像池不得包含 HTTP 节点' >&2; exit 1; }
if grep -F '节点测速:' "${ROOT_DIR}/deploy/aid.sh" | grep -Fvq 'AID 国内镜像测速:'; then
  echo 'FAIL: 用户可见测速日志必须使用 AID 品牌' >&2
  exit 1
fi

# 首次解析线路会打印测速诊断；bt_artifact_urls 的 stdout 仍必须只有 URL，
# 否则 mapfile 会把日志误当作下载地址。
BT_MIRRORS_RESOLVED=0
BT_MIRROR_ORDER=""
mapfile -t artifact_urls < <(bt_artifact_urls 'src/test-artifact.tar.gz')
[[ "${#artifact_urls[@]}" -gt 0 ]] || { echo 'FAIL: 首次线路选择未生成下载 URL' >&2; exit 1; }
for artifact_url in "${artifact_urls[@]}"; do
  [[ "${artifact_url}" =~ ^https://[^[:space:]]+/src/test-artifact\.tar\.gz$ ]] \
    || { printf 'FAIL: URL stdout 被诊断信息污染: %q\n' "${artifact_url}" >&2; exit 1; }
done

printf 'AID resumable download fixture\nsecond line\n' > "${TMP_ROOT}/source.bin"
expected="$(sha256sum "${TMP_ROOT}/source.bin" | awk '{print $1}')"
head -c 9 "${TMP_ROOT}/source.bin" > "${TMP_ROOT}/target.bin.part"
CURL_USED_RESUME=no
curl() {
  local output='' resume=no arg
  while (($#)); do
    arg="$1"; shift
    case "${arg}" in
      --output) output="$1"; shift ;;
      --continue-at) resume=yes; shift ;;
    esac
  done
  if [[ "${resume}" == "yes" ]]; then
    CURL_USED_RESUME=yes
    tail -c +$(( $(wc -c < "${output}") + 1 )) "${TMP_ROOT}/source.bin" >> "${output}"
  else
    cp "${TMP_ROOT}/source.bin" "${output}"
  fi
}
try_download 'https://mirror.example/source.bin' "${TMP_ROOT}/target.bin" '测试包' sha256 "${expected}" >/dev/null
[[ "${CURL_USED_RESUME}" == "yes" ]] || { echo 'FAIL: 已有 .part 文件时未启用断点续传' >&2; exit 1; }
file_digest_matches "${TMP_ROOT}/target.bin" sha256 "${expected}" \
  || { echo 'FAIL: 断点续传后的固定摘要校验失败' >&2; exit 1; }
[[ ! -e "${TMP_ROOT}/target.bin.part" ]] || { echo 'FAIL: 成功后不应保留 .part 文件' >&2; exit 1; }

if try_download 'https://mirror.example/source.bin' "${TMP_ROOT}/bad.bin" '错误摘要测试包' sha256 \
    '0000000000000000000000000000000000000000000000000000000000000000' >/dev/null 2>&1; then
  echo 'FAIL: 摘要错误的下载不应通过' >&2
  exit 1
fi
[[ ! -e "${TMP_ROOT}/bad.bin" && ! -e "${TMP_ROOT}/bad.bin.part" ]] \
  || { echo 'FAIL: 摘要错误的文件应被清理' >&2; exit 1; }

grep -Fq 'mysql-boost-${MYSQL_VERSION}.tar.gz' "${ROOT_DIR}/deploy/aid.sh" \
  || { echo 'FAIL: MySQL 未接入 AID 镜像源包兜底' >&2; exit 1; }
grep -Fq 'b8fe262c4679cb7bbc379a3f1addc723844db168628ce2acf78d33906849e491' "${ROOT_DIR}/deploy/aid.sh" \
  || { echo 'FAIL: MySQL AID镜像源包缺少固定 SHA256' >&2; exit 1; }

echo 'download mirror resilience tests passed'
