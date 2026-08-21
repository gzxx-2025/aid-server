#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT

# Git for Windows 默认把 ln -s 模拟为普通文件，无法验证 Linux 原子软链接语义。
touch "${TEST_ROOT}/symlink-target"
ln -s "${TEST_ROOT}/symlink-target" "${TEST_ROOT}/symlink-probe" 2>/dev/null || true
if [[ ! -L "${TEST_ROOT}/symlink-probe" ]]; then
  echo 'CJK font runtime tests skipped: native symbolic links are unavailable'
  exit 0
fi

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TEST_ROOT}/data"
source "${ROOT_DIR}/deploy/aid.sh"

MOCK_BIN="${TEST_ROOT}/bin"
FIXTURES="${TEST_ROOT}/fixtures"
mkdir -p "${MOCK_BIN}" "${FIXTURES}" "${AID_DATA_ROOT}"
SYSTEM_FONT="${FIXTURES}/system-cjk.otf"
ENGLISH_FONT="${FIXTURES}/english-only.otf"
ARCHIVE_FONT="${FIXTURES}/NotoSansSC-Regular.otf"
ARCHIVE_LICENSE="${FIXTURES}/LICENSE"
ARCHIVE="${FIXTURES}/font.zip"
printf 'system-cjk\n' > "${SYSTEM_FONT}"
printf 'english-only\n' > "${ENGLISH_FONT}"
printf 'downloaded-cjk\n' > "${ARCHIVE_FONT}"
printf 'SIL Open Font License 1.1\n' > "${ARCHIVE_LICENSE}"
printf 'fixed-font-archive\n' > "${ARCHIVE}"

cat > "${MOCK_BIN}/fc-list" <<'EOF'
#!/usr/bin/env bash
case "${FONT_TEST_SYSTEM_MODE:-none}" in
  cjk) printf 'Noto Sans CJK SC\tRegular\t%s\n' "${FONT_TEST_SYSTEM_FONT}" ;;
  english) printf 'Generic Sans\tRegular\t%s\n' "${FONT_TEST_ENGLISH_FONT}" ;;
  fail) exit 91 ;;
esac
EOF

cat > "${MOCK_BIN}/fc-query" <<'EOF'
#!/usr/bin/env bash
font="${@: -1}"
if [[ "${font}" == *english-only* ]]; then
  [[ "$1" == *lang* ]] && printf 'en\n' || printf '0020-007e\n'
  exit 0
fi
if [[ "$1" == *lang* ]]; then
  printf 'en|zh-cn\n'
else
  printf '0020-007e 3002 4e2d 5b57 5e38 5e55 6587 6b63 6d4b 8bd5 ff0c\n'
fi
EOF

cat > "${MOCK_BIN}/ffmpeg" <<'EOF'
#!/usr/bin/env bash
output="${@: -1}"
mkdir -p -- "$(dirname "${output}")"
printf 'video\n' > "${output}"
EOF

cat > "${MOCK_BIN}/ffprobe" <<'EOF'
#!/usr/bin/env bash
printf 'video\n'
EOF

cat > "${MOCK_BIN}/curl" <<'EOF'
#!/usr/bin/env bash
target=''
while (($#)); do
  if [[ "$1" == '-o' ]]; then shift; target="$1"; fi
  shift || true
done
[[ -n "${target}" ]]
cp -- "${FONT_TEST_ARCHIVE}" "${target}"
EOF

cat > "${MOCK_BIN}/unzip" <<'EOF'
#!/usr/bin/env bash
if [[ "$1" == '-Z1' ]]; then
  printf 'NotoSansSC-Regular.otf\nLICENSE\n'
  exit 0
fi
destination=''
while (($#)); do
  if [[ "$1" == '-d' ]]; then shift; destination="$1"; fi
  shift || true
done
mkdir -p -- "${destination}"
cp -- "${FONT_TEST_ARCHIVE_FONT}" "${destination}/NotoSansSC-Regular.otf"
cp -- "${FONT_TEST_ARCHIVE_LICENSE}" "${destination}/LICENSE"
EOF
chmod 700 "${MOCK_BIN}"/*

export PATH="${MOCK_BIN}:${PATH}"
export FONT_TEST_SYSTEM_FONT="${SYSTEM_FONT}"
export FONT_TEST_ENGLISH_FONT="${ENGLISH_FONT}"
export FONT_TEST_ARCHIVE="${ARCHIVE}"
export FONT_TEST_ARCHIVE_FONT="${ARCHIVE_FONT}"
export FONT_TEST_ARCHIVE_LICENSE="${ARCHIVE_LICENSE}"

generate_manager() { # generate_manager <case-root> [sha256]
  local caseRoot="$1" checksum="${2:-$(sha256_file "${ARCHIVE}")}" manager
  AID_FONT_ROOT="${caseRoot}/aid-fonts"
  AID_FONT_CURRENT="${AID_FONT_ROOT}/current"
  AID_CJK_FONT_PATH="${AID_FONT_CURRENT}/aid-cjk-font"
  AID_CJK_FONT_VERSION="noto-sans-sc-2.004"
  AID_CJK_FONT_SHA256="${checksum}"
  AID_CJK_FONT_FILE_SHA256="$(sha256_file "${ARCHIVE_FONT}")"
  FFMPEG_RUNTIME_FFMPEG="${MOCK_BIN}/ffmpeg"
  FFMPEG_RUNTIME_FFPROBE="${MOCK_BIN}/ffprobe"
  manager="${caseRoot}/font-manager.sh"
  mkdir -p "${caseRoot}"
  write_aid_cjk_font_manager "${manager}"
  printf '%s\n' "${manager}"
}

# 已有合格系统字体：只建立稳定软链接，不下载或复制字体。
caseRoot="${TEST_ROOT}/reuse"
manager="$(generate_manager "${caseRoot}")"
FONT_TEST_SYSTEM_MODE=cjk "${manager}" prepare > "${caseRoot}/result.log"
grep -Fq 'AID_FONT_RESULT=reused-system' "${caseRoot}/result.log"
[[ "$(readlink -f "${caseRoot}/aid-fonts/current/aid-cjk-font")" == "${SYSTEM_FONT}" ]]
[[ ! -d "${caseRoot}/aid-fonts/noto-sans-sc-2.004" ]]

# current 完整时必须幂等复用，即使后续系统扫描失败也不重新下载。
FONT_TEST_SYSTEM_MODE=fail "${manager}" validate > "${caseRoot}/validate.log"
grep -Fq 'AID_FONT_RESULT=reused-system' "${caseRoot}/validate.log"

# 系统字体只有英文时拒绝复用，并安装经过固定摘要验证的AID字体与许可证。
caseRoot="${TEST_ROOT}/download"
manager="$(generate_manager "${caseRoot}")"
FONT_TEST_SYSTEM_MODE=english "${manager}" prepare > "${caseRoot}/result.log"
grep -Fq 'AID_FONT_RESULT=installed-aid' "${caseRoot}/result.log"
[[ -f "${caseRoot}/aid-fonts/noto-sans-sc-2.004/NotoSansSC-Regular.otf" ]]
[[ -f "${caseRoot}/aid-fonts/noto-sans-sc-2.004/LICENSE" ]]
[[ "$(readlink -f "${caseRoot}/aid-fonts/current/aid-cjk-font")" \
    == "${caseRoot}/aid-fonts/noto-sans-sc-2.004/NotoSansSC-Regular.otf" ]]

# current 损坏时重新发现系统字体并恢复稳定入口。
rm -f -- "${caseRoot}/aid-fonts/current/aid-cjk-font"
ln -s "${caseRoot}/missing-font" "${caseRoot}/aid-fonts/current/aid-cjk-font"
FONT_TEST_SYSTEM_MODE=cjk "${manager}" prepare > "${caseRoot}/repair.log"
grep -Fq 'AID_FONT_RESULT=reused-system' "${caseRoot}/repair.log"
[[ "$(readlink -f "${caseRoot}/aid-fonts/current/aid-cjk-font")" == "${SYSTEM_FONT}" ]]

# 摘要不匹配必须拒绝安装，并且不能生成可用稳定链接。
caseRoot="${TEST_ROOT}/bad-sha"
manager="$(generate_manager "${caseRoot}" '0000000000000000000000000000000000000000000000000000000000000000')"
if FONT_TEST_SYSTEM_MODE=none "${manager}" prepare > "${caseRoot}/result.log" 2>&1; then
  echo 'FAIL: bad font SHA256 unexpectedly succeeded' >&2
  exit 1
fi
[[ ! -e "${caseRoot}/aid-fonts/current/aid-cjk-font" ]]

# 稳定入口被普通文件占用时必须保留原文件并明确拒绝覆盖。
caseRoot="${TEST_ROOT}/occupied"
manager="$(generate_manager "${caseRoot}")"
mkdir -p "${caseRoot}/aid-fonts/current"
printf 'user-owned\n' > "${caseRoot}/aid-fonts/current/aid-cjk-font"
if FONT_TEST_SYSTEM_MODE=cjk "${manager}" prepare > "${caseRoot}/result.log" 2>&1; then
  echo 'FAIL: occupied canonical path unexpectedly succeeded' >&2
  exit 1
fi
grep -Fxq 'user-owned' "${caseRoot}/aid-fonts/current/aid-cjk-font"

echo 'CJK font runtime tests passed'
