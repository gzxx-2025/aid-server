#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

RESOLVED_MANIFEST_PATH="${TMP_ROOT}/latest.json"
RESOLVED_CHANNEL=beta
REMOTE_VERSION=1.2.3-beta.1
REMOTE_URL=https://example.test/aid-updater.tar.gz
REMOTE_SHA=""
REMOTE_ARCHIVE=""
DOWNLOAD_TRACE="${TMP_ROOT}/downloads.log"
printf '{}\n' > "${RESOLVED_MANIFEST_PATH}"

json_updater_version() { echo "${REMOTE_VERSION}"; }
json_updater_package_string() {
  case "$4" in
    url) echo "${REMOTE_URL}" ;;
    mirror) echo '' ;;
    sha256) echo "${REMOTE_SHA}" ;;
  esac
}
manifest_payload_contains_updater() { return 0; }
resolve_official_release() { :; }
require_download_tools() { :; }
try_download() {
  printf 'download\n' >> "${DOWNLOAD_TRACE}"
  cp "${REMOTE_ARCHIVE}" "$2"
}
ok() { :; }
warn() { :; }

make_updater_archive() { # make_updater_archive <版本> <内容标识>
  local version="$1" marker="$2" fixture archive
  fixture="${TMP_ROOT}/fixture-${marker}"
  archive="${TMP_ROOT}/updater-${marker}.tar.gz"
  mkdir -p "${fixture}"
  cat > "${fixture}/aid-updater" <<EOF
#!/usr/bin/env bash
# ${marker}
[[ "\${1:-}" == "-version" ]] && { echo '${version}'; exit 0; }
exit 1
EOF
  chmod 0755 "${fixture}/aid-updater"
  tar -czf "${archive}" -C "${fixture}" aid-updater
  REMOTE_ARCHIVE="${archive}"
  REMOTE_SHA="$(sha256_file "${archive}")"
}

install_current_updater() { # install_current_updater <版本> <内容标识>
  local version="$1" marker="$2"
  mkdir -p "${DATA_ROOT}/app/updater"
  cat > "${DATA_ROOT}/app/updater/aid-updater" <<EOF
#!/usr/bin/env bash
# ${marker}
[[ "\${1:-}" == "-version" ]] && { echo '${version}'; exit 0; }
exit 1
EOF
  chmod 0755 "${DATA_ROOT}/app/updater/aid-updater"
}

reset_download_trace() { : > "${DOWNLOAD_TRACE}"; }
assert_download_count() {
  local expected="$1" actual
  actual="$(wc -l < "${DOWNLOAD_TRACE}")"
  [[ "${actual}" -eq "${expected}" ]] \
    || { echo "FAIL: expected ${expected} downloads, got ${actual}" >&2; exit 1; }
}

# 当前版本与清单版本、制品 SHA 全部一致时才允许复用。
make_updater_archive "${REMOTE_VERSION}" first
install_current_updater "${REMOTE_VERSION}" first
state_set OFFICIAL_UPDATER_VERSION "${REMOTE_VERSION}"
state_set OFFICIAL_UPDATER_PACKAGE_SHA256 "${REMOTE_SHA}"
state_set OFFICIAL_UPDATER_BINARY_SHA256 "$(sha256_file "${DATA_ROOT}/app/updater/aid-updater")"
reset_download_trace
ensure_official_updater_binary
assert_download_count 0

# 同版本强制重发导致 SHA 变化时必须刷新二进制及状态。
make_updater_archive "${REMOTE_VERSION}" replacement
reset_download_trace
ensure_official_updater_binary
assert_download_count 1
[[ "$(state_get OFFICIAL_UPDATER_VERSION '')" == "${REMOTE_VERSION}" \
    && "$(state_get OFFICIAL_UPDATER_PACKAGE_SHA256 '')" == "${REMOTE_SHA}" \
    && "$(state_get OFFICIAL_UPDATER_BINARY_SHA256 '')" == "$(sha256_file "${DATA_ROOT}/app/updater/aid-updater")" ]] \
  || { echo 'FAIL: replacement updater state was not persisted' >&2; exit 1; }
grep -Fq '# replacement' "${DATA_ROOT}/app/updater/aid-updater" \
  || { echo 'FAIL: same-version replacement binary was not installed' >&2; exit 1; }

# 旧安装只有版本、没有官方 SHA 记录时自动安全刷新一次。
sed -i '/^OFFICIAL_UPDATER_PACKAGE_SHA256=/d' "${STATE_FILE}"
install_current_updater "${REMOTE_VERSION}" legacy
reset_download_trace
ensure_official_updater_binary
assert_download_count 1
[[ "$(state_get OFFICIAL_UPDATER_PACKAGE_SHA256 '')" == "${REMOTE_SHA}" ]] \
  || { echo 'FAIL: legacy updater did not acquire package SHA state' >&2; exit 1; }

# 二进制被同版本伪装文件替换后，即使版本与包 SHA 状态未变也必须恢复官方制品。
install_current_updater "${REMOTE_VERSION}" tampered
reset_download_trace
ensure_official_updater_binary
assert_download_count 1
grep -Fq '# replacement' "${DATA_ROOT}/app/updater/aid-updater" \
  || { echo 'FAIL: tampered updater binary was not restored' >&2; exit 1; }

# 二进制未变但受信摘要记录不匹配时同样重新验证并刷新，不能只信当前文件。
state_set OFFICIAL_UPDATER_BINARY_SHA256 cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
reset_download_trace
ensure_official_updater_binary
assert_download_count 1
[[ "$(state_get OFFICIAL_UPDATER_BINARY_SHA256 '')" == "$(sha256_file "${DATA_ROOT}/app/updater/aid-updater")" ]] \
  || { echo 'FAIL: mismatched binary SHA state was not repaired' >&2; exit 1; }

# 本地版本高于远端时禁止降级，也不得篡改已有官方制品状态。
install_current_updater 2.0.0 newer
state_set OFFICIAL_UPDATER_VERSION 1.9.0
state_set OFFICIAL_UPDATER_PACKAGE_SHA256 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
state_set OFFICIAL_UPDATER_BINARY_SHA256 dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
reset_download_trace
ensure_official_updater_binary
assert_download_count 0
[[ "$(state_get OFFICIAL_UPDATER_VERSION '')" == '1.9.0' \
    && "$(state_get OFFICIAL_UPDATER_PACKAGE_SHA256 '')" == 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
    && "$(state_get OFFICIAL_UPDATER_BINARY_SHA256 '')" == 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd' ]] \
  || { echo 'FAIL: no-downgrade path modified updater state' >&2; exit 1; }

# 下载或安装失败前不得提前宣告新的官方版本与 SHA 已安装。
install_current_updater 1.0.0 old
state_set OFFICIAL_UPDATER_VERSION 1.0.0
state_set OFFICIAL_UPDATER_PACKAGE_SHA256 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
state_set OFFICIAL_UPDATER_BINARY_SHA256 eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
reset_download_trace
if (install() { return 1; }; ensure_official_updater_binary >/dev/null 2>&1); then
  echo 'FAIL: updater installation failure was reported as success' >&2
  exit 1
fi
[[ "$(state_get OFFICIAL_UPDATER_VERSION '')" == '1.0.0' \
    && "$(state_get OFFICIAL_UPDATER_PACKAGE_SHA256 '')" == 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
    && "$(state_get OFFICIAL_UPDATER_BINARY_SHA256 '')" == 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee' ]] \
  || { echo 'FAIL: failed installation updated official updater state' >&2; exit 1; }

echo 'updater package state tests passed'
