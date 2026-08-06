#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export AID_SH_LIBRARY_MODE=1
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

mkdir -p "${TMP_ROOT}/fixture/bin"
cat > "${TMP_ROOT}/fixture/bin/node" <<'EOF'
#!/usr/bin/env bash
echo v22.22.0
EOF
chmod +x "${TMP_ROOT}/fixture/bin/node"
tar -cJf "${TMP_ROOT}/fixture.tar.xz" -C "${TMP_ROOT}/fixture" .

uname() {
  [[ "${1:-}" == "-m" ]] && { echo x86_64; return 0; }
  command uname "$@"
}
detect_glibc_version() { echo 2.17; }
require_download_tools() { :; }
ensure_host_command() { :; }
resolve_dependency_region() { RESOLVED_DEPENDENCY_REGION=cn; }
rank_download_urls() { shift; printf '%s\n' "$@"; }
sha256_file() {
  case "$(basename "$1")" in
    node-v22.22.0-linux-x64-glibc-217.tar.xz)
      echo db4a1d582e6fffcf7fb348149ca4ac8fa685699c5bc46cd7e22bbf9a7e673454 ;;
    *) echo invalid ;;
  esac
}
try_download() {
  CAPTURED_NODE_URL="$1"
  cp "${TMP_ROOT}/fixture.tar.xz" "$2"
}

DATA_ROOT="${TMP_ROOT}/data"
AID_DEPENDENCY_INSTALL_MODE=auto
RESOLVED_DEPENDENCY_REGION=""
prepare_exact_node >/dev/null

[[ "${CAPTURED_NODE_URL}" == "https://gitee.com/gzxx-2025/aid-server/releases/download/v1.0.0-beta.2/node-v22.22.0-linux-x64-glibc-217.tar.xz" ]] \
  || { echo "旧 glibc 未优先选择国内兼容包: ${CAPTURED_NODE_URL}" >&2; exit 1; }
[[ "$("${NODE_HOME}/bin/node" -v)" == "v22.22.0" ]] \
  || { echo '兼容版 Node.js 未正确就位' >&2; exit 1; }

cat > "${TMP_ROOT}/broken-node" <<'EOF'
#!/usr/bin/env bash
echo "node: version GLIBC_2.28 not found" >&2
exit 1
EOF
chmod +x "${TMP_ROOT}/broken-node"
if node_runtime_matches "${TMP_ROOT}/broken-node"; then
  echo '无法运行的 Node.js 不应通过版本校验' >&2
  exit 1
fi
[[ "${NODE_RUNTIME_ERROR}" == *'GLIBC_2.28 not found'* ]] \
  || { echo "Node.js 运行错误未被保留: ${NODE_RUNTIME_ERROR}" >&2; exit 1; }

echo 'node runtime compatibility tests passed'
