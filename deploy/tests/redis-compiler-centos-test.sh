#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# 下载的 RPM 公钥必须先验证固定摘要；坏节点不能污染系统 key 文件。
keySource="${TMP_ROOT}/expected-key"
printf 'verified-centos-key\n' > "${keySource}"
keySha256="$(sha256_file "${keySource}")"
keyTarget="${TMP_ROOT}/rpm-gpg/RPM-GPG-KEY-test"
keyCache="${TMP_ROOT}/cache/RPM-GPG-KEY-test"
KEY_DOWNLOADS=0
RPM_IMPORTS=0
require_download_tools() { :; }
try_download() {
  KEY_DOWNLOADS=$((KEY_DOWNLOADS + 1))
  mkdir -p "$(dirname "$2")"
  case "$1" in
    *bad*) printf 'invalid-key\n' > "$2" ;;
    *good*) cp "${keySource}" "$2" ;;
    *) return 1 ;;
  esac
}
rpm() {
  [[ "${1:-}" == "--import" ]] || return 1
  [[ -s "${2:-}" ]] || return 1
  RPM_IMPORTS=$((RPM_IMPORTS + 1))
}
ensure_verified_rpm_key "测试RPM公钥" "0000000000000000000000000000000000000000" \
  "${keySha256}" "${keyTarget}" "${keyCache}" https://mirror.invalid/bad https://mirror.invalid/good >/dev/null
[[ "${KEY_DOWNLOADS}" == "2" ]] || fail "invalid RPM key mirror was not skipped"
[[ "$(sha256_file "${keyTarget}")" == "${keySha256}" ]] || fail "verified RPM key was not installed"
[[ "${RPM_IMPORTS}" == "1" ]] || fail "verified RPM key was not imported"
ensure_verified_rpm_key "测试RPM公钥" "0000000000000000000000000000000000000000" \
  "${keySha256}" "${keyTarget}" "${keyCache}" https://mirror.invalid/good >/dev/null
[[ "${KEY_DOWNLOADS}" == "2" ]] || fail "verified system RPM key was downloaded again"
[[ "${RPM_IMPORTS}" == "2" ]] || fail "cached RPM key import must remain idempotent"

# 模拟仓库写入目录，验证 CentOS 7 x86_64/aarch64 和 CentOS 8 Vault 均保持 GPG 强校验。
export AID_RPM_GPG_DIR="${TMP_ROOT}/system-keys"
export AID_YUM_REPO_DIR="${TMP_ROOT}/yum.repos.d"
MOCK_ARCH=x86_64
YUM_ARGS=""
DNF_ARGS=""
ensure_verified_rpm_key() {
  local target="$4"
  mkdir -p "$(dirname "${target}")"
  printf 'verified-key\n' > "${target}"
}
uname() { printf '%s\n' "${MOCK_ARCH}"; }
yum() { YUM_ARGS="$*"; }
dnf() { DNF_ARGS="$*"; }

install_centos7_redis_compiler >/dev/null
centos7Repo="${AID_YUM_REPO_DIR}/aid-centos7-redis-build.repo"
[[ -f "${centos7Repo}" ]] || fail "CentOS 7 isolated repository was not created"
[[ "$(grep -c '^gpgcheck=1$' "${centos7Repo}")" == "4" ]] || fail "CentOS 7 repositories must all enable GPG checks"
! grep -Eq '^gpgcheck=0$|--nogpgcheck' "${centos7Repo}" || fail "CentOS 7 repository disabled package signature checks"
grep -Fq 'mirrors.aliyun.com/centos/7.9.2009/os/$basearch/' "${centos7Repo}" \
  || fail "CentOS 7 x86_64 Base repository is missing"
[[ "${YUM_ARGS}" == *'--disablerepo=*'* && "${YUM_ARGS}" == *'devtoolset-7-gcc devtoolset-7-gcc-c++'* ]] \
  || fail "CentOS 7 compiler install did not isolate AID repositories"

MOCK_ARCH=aarch64
YUM_ARGS=""
install_centos7_redis_compiler >/dev/null
grep -Fq 'mirrors.aliyun.com/centos-altarch/7/sclo/$basearch/rh/' "${centos7Repo}" \
  || fail "CentOS 7 aarch64 SCL repository is missing"

DNF_ARGS=""
install_centos8_redis_compiler >/dev/null
centos8Repo="${AID_YUM_REPO_DIR}/aid-centos8-redis-build.repo"
[[ "$(grep -c '^gpgcheck=1$' "${centos8Repo}")" == "2" ]] || fail "CentOS 8 Vault repositories must enable GPG checks"
grep -Fq 'centos-vault/8.5.2111/AppStream/$basearch/os/' "${centos8Repo}" \
  || fail "CentOS 8 AppStream Vault fallback is missing"
[[ "${DNF_ARGS}" == *'--disablerepo=*'* && "${DNF_ARGS}" == *'gcc gcc-c++'* ]] \
  || fail "CentOS 8 Vault compiler install did not isolate AID repositories"

DNF_ARGS=""
install_centos8_redis_compiler stream >/dev/null
grep -Fq 'vault.centos.org/centos/8-stream/BaseOS/$basearch/os/' "${centos8Repo}" \
  || fail "CentOS Stream 8 official Vault fallback is missing"
! grep -Fq 'centos-vault/8.5.2111' "${centos8Repo}" \
  || fail "CentOS Stream 8 fallback mixed CentOS Linux 8.5 packages"

# CC/CXX 可以是 PATH 中的命令名；解析后必须导出绝对可执行路径并通过 GCC 7+ 门禁。
mockBin="${TMP_ROOT}/compiler-bin"
mkdir -p "${mockBin}"
cat > "${mockBin}/gcc" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *-dumpfullversion*) echo 8.5.0 ;;
  *--version*) echo 'gcc (mock) 8.5.0' ;;
esac
EOF
cp "${mockBin}/gcc" "${mockBin}/g++"
chmod +x "${mockBin}/gcc" "${mockBin}/g++"
(
  PATH="${mockBin}:${PATH}"
  CC=gcc CXX=g++
  export PATH CC CXX
  redis_compiler_pair_is_supported "${CC}" "${CXX}" >/dev/null
  [[ "${CC}" == "${mockBin}/gcc" && "${CXX}" == "${mockBin}/g++" ]]
) || fail "PATH compiler names were not resolved and exported"

# 发行版路由：CentOS 7 使用 SCL，CentOS Linux 8 仓库失败后使用 Vault；
# Stream 9 使用自身 dnf，绝不能回退到 CentOS Linux 8 Vault。
osRelease="${TMP_ROOT}/os-release"
export AID_OS_RELEASE_FILE="${osRelease}"
COMPILER_READY=0
ROUTE=""
select_redis_build_compiler() { [[ "${COMPILER_READY}" == "1" ]]; }
install_centos7_redis_compiler() { ROUTE=centos7; COMPILER_READY=1; }
install_centos8_redis_compiler() { ROUTE="centos8-${1:-linux}"; COMPILER_READY=1; }
install_rpm_redis_compiler() { return 1; }

cat > "${osRelease}" <<'EOF'
ID=centos
VERSION_ID=7
NAME="CentOS Linux"
EOF
ensure_redis_build_compiler auto >/dev/null
[[ "${ROUTE}" == "centos7" ]] || fail "CentOS 7 did not select the SCL compiler route"

COMPILER_READY=0
ROUTE=""
cat > "${osRelease}" <<'EOF'
ID=centos
VERSION_ID=8
NAME="CentOS Linux"
EOF
ensure_redis_build_compiler auto >/dev/null
[[ "${ROUTE}" == "centos8-linux" ]] || fail "EOL CentOS Linux 8 did not use the 8.5 Vault fallback"

COMPILER_READY=0
ROUTE=""
cat > "${osRelease}" <<'EOF'
ID=centos
VERSION_ID=8
NAME="CentOS Stream"
EOF
ensure_redis_build_compiler auto >/dev/null
[[ "${ROUTE}" == "centos8-stream" ]] || fail "EOL CentOS Stream 8 did not use its official Vault fallback"

COMPILER_READY=0
ROUTE=""
install_rpm_redis_compiler() { ROUTE=stream-native; COMPILER_READY=1; }
cat > "${osRelease}" <<'EOF'
ID=centos
VERSION_ID=9
NAME="CentOS Stream"
EOF
ensure_redis_build_compiler auto >/dev/null
[[ "${ROUTE}" == "stream-native" ]] || fail "CentOS Stream did not keep its native package manager route"

COMPILER_READY=0
cat > "${osRelease}" <<'EOF'
ID=centos
VERSION_ID=6
NAME="CentOS"
EOF
if (ensure_redis_build_compiler auto >/dev/null 2>&1); then
  fail "unsupported CentOS 6 must not continue with an incompatible compiler"
fi

# 回归保护：不再下载/安装会因 RPM 数据库缺少旧公钥而误报的 SCL 引导 RPM。
compilerBody="$(declare -f install_centos7_redis_compiler)"
[[ "${compilerBody}" != *'centos-release-scl-rh'* && "${compilerBody}" != *'rpm -K'* ]] \
  || fail "legacy SCL bootstrap RPM signature path is still present"

echo 'CentOS Redis compiler compatibility tests passed'
