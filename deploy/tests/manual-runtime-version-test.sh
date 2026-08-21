#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data/aid"
export AID_JAVA_PROFILE_FILE="${TMP_ROOT}/profile.d/aid-java.sh"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"

[[ "${NGINX_VERSION}" == "1.30.4" ]] || { echo 'FAIL: manual Nginx version drifted' >&2; exit 1; }
[[ "${MYSQL_VERSION}" == "5.7.44" ]] || { echo 'FAIL: manual MySQL version drifted' >&2; exit 1; }
[[ "${REDIS_VERSION}" == "8.0.5" ]] || { echo 'FAIL: manual Redis version drifted' >&2; exit 1; }
[[ "${MANUAL_JDK_VERSION}" == "17.0.8" ]] || { echo 'FAIL: manual JDK version drifted' >&2; exit 1; }
[[ "${JDK_VERSION}" == "17.0.20" && "${JAVA_RUNTIME_IMAGE}" == "aid/openjdk:17.0.20-ffmpeg7.0.2-font2.004" ]] \
  || { echo 'FAIL: Docker JDK baseline must remain unchanged' >&2; exit 1; }
[[ "${FFMPEG_RUNTIME_VERSION}" == "7.0.2" && "${FFMPEG_MIN_VERSION}" == "5.1" ]] \
  || { echo 'FAIL: FFmpeg fixed runtime baseline drifted' >&2; exit 1; }
configure_ffmpeg_runtime_paths
[[ "${FFMPEG_RUNTIME_FFMPEG}" == '/opt/aid-ffmpeg/current/ffmpeg' \
   && "${FFMPEG_RUNTIME_FFPROBE}" == '/opt/aid-ffmpeg/current/ffprobe' ]] \
  || { echo 'FAIL: FFmpeg managed paths must be identical across deployment modes' >&2; exit 1; }
[[ "${AID_CJK_FONT_VERSION}" == 'noto-sans-sc-2.004' \
   && "${AID_CJK_FONT_PATH}" == '/opt/aid-fonts/current/aid-cjk-font' \
   && "${AID_CJK_FONT_SHA256}" == '4d107c09ada479d3e48b6e78c83835773cbd9214bf6e12cdb7b60f8e068292ec' \
   && "${AID_CJK_FONT_FILE_SHA256}" == 'faa6c9df652116dde789d351359f3d7e5d2285a2b2a1f04a2d7244df706d5ea9' ]] \
  || { echo 'FAIL: managed CJK font baseline drifted' >&2; exit 1; }
[[ "$(ffmpeg_runtime_checksum amd64)" == 'abda8d77ce8309141f83ab8edf0596834087c52467f6badf376a6a2a4c87cf67' \
   && "$(ffmpeg_runtime_checksum arm64)" == 'f4149bb2b0784e30e99bdda85471c9b5930d3402014e934a5098b41d0f7201b1' ]] \
  || { echo 'FAIL: FFmpeg architecture checksum drifted' >&2; exit 1; }
grep -Fq 'image: aid/openjdk:17.0.20-ffmpeg7.0.2-font2.004' "${ROOT_DIR}/deploy/docker/docker-compose.yml" \
  || { echo 'FAIL: Compose FFmpeg image tag drifted' >&2; exit 1; }
grep -Fq 'AID_FFMPEG_PATH: /opt/aid-ffmpeg/current/ffmpeg' "${ROOT_DIR}/deploy/docker/docker-compose.yml" \
  || { echo 'FAIL: Compose FFmpeg path drifted' >&2; exit 1; }
grep -Fq 'AID_FFPROBE_PATH: /opt/aid-ffmpeg/current/ffprobe' "${ROOT_DIR}/deploy/docker/docker-compose.yml" \
  || { echo 'FAIL: Compose FFprobe path drifted' >&2; exit 1; }
grep -Fq "'ffmpegPath', '/opt/aid-ffmpeg/current/ffmpeg'" "${ROOT_DIR}/sql/aid-init.sql" \
  || { echo 'FAIL: aid-init FFmpeg default path drifted' >&2; exit 1; }
grep -Fq "'ffprobePath', '/opt/aid-ffmpeg/current/ffprobe'" "${ROOT_DIR}/sql/aid-init.sql" \
  || { echo 'FAIL: aid-init FFprobe default path drifted' >&2; exit 1; }
grep -Fq "'ffmpegPath', '/opt/aid-ffmpeg/current/ffmpeg'" "${ROOT_DIR}/sql/v1.0.0-beta.6.sql" \
  || { echo 'FAIL: beta.6 FFmpeg default path drifted' >&2; exit 1; }
grep -Fq "'ffprobePath', '/opt/aid-ffmpeg/current/ffprobe'" "${ROOT_DIR}/sql/v1.0.0-beta.6.sql" \
  || { echo 'FAIL: beta.6 FFprobe default path drifted' >&2; exit 1; }
grep -Fq "'ffmpegFontFile', '/opt/aid-fonts/current/aid-cjk-font'" "${ROOT_DIR}/sql/aid-init.sql" \
  || { echo 'FAIL: aid-init CJK font default path drifted' >&2; exit 1; }
grep -Fq "'ffmpegFontFile', '/opt/aid-fonts/current/aid-cjk-font'" "${ROOT_DIR}/sql/v1.0.0-beta.6.sql" \
  || { echo 'FAIL: beta.6 CJK font default path drifted' >&2; exit 1; }

mkdir -p "${TMP_ROOT}/compiler"
cat > "${TMP_ROOT}/compiler/gcc" <<'EOF'
#!/usr/bin/env bash
case "$*" in *-dumpversion*) echo '7.5.0' ;; *) echo 'gcc test 7.5.0' ;; esac
EOF
cat > "${TMP_ROOT}/compiler/g++" <<'EOF'
#!/usr/bin/env bash
echo 'g++ test 7.5.0'
EOF
cat > "${TMP_ROOT}/compiler/java" <<'EOF'
#!/usr/bin/env bash
echo 'openjdk version "11.0.0"' >&2
EOF
chmod +x "${TMP_ROOT}/compiler/gcc" "${TMP_ROOT}/compiler/g++" "${TMP_ROOT}/compiler/java"
unset JAVA_HOME
export PATH="${TMP_ROOT}/compiler:/usr/bin:/bin"
export CC="${TMP_ROOT}/compiler/gcc" CXX="${TMP_ROOT}/compiler/g++"
select_redis_build_compiler >/dev/null \
  || { echo 'FAIL: Redis GCC/G++ 7+ toolchain was not accepted' >&2; exit 1; }
[[ "${CC}" == "${TMP_ROOT}/compiler/gcc" && "${CXX}" == "${TMP_ROOT}/compiler/g++" ]] \
  || { echo 'FAIL: Redis compiler environment was not activated' >&2; exit 1; }

mkdir -p "${TMP_ROOT}/fixture/jdk/bin"
cat > "${TMP_ROOT}/fixture/jdk/bin/java" <<'EOF'
#!/usr/bin/env bash
echo 'java version "17.0.8" 2023-07-18 LTS' >&2
EOF
cat > "${TMP_ROOT}/fixture/jdk/bin/javac" <<'EOF'
#!/usr/bin/env bash
echo 'javac 17.0.8'
EOF
chmod +x "${TMP_ROOT}/fixture/jdk/bin/java" "${TMP_ROOT}/fixture/jdk/bin/javac"
tar -czf "${TMP_ROOT}/jdk-17.0.8.tar.gz" -C "${TMP_ROOT}/fixture" jdk

DOWNLOAD_COUNT=0
require_download_tools() { :; }
bt_artifact_urls() { printf '%s\n' 'https://download.bt.cn/src/jdk/x64/jdk-17.0.8.tar.gz'; }
sha256_file() { printf '%s\n' '74b528a33bb2dfa02b4d74a0d66c9aff52e4f52924ce23a62d7f9eb1a6744657'; }
try_download() {
  cp "${TMP_ROOT}/jdk-17.0.8.tar.gz" "$2"
  DOWNLOAD_COUNT=$((DOWNLOAD_COUNT + 1))
}

prepare_manual_jdk >/dev/null
[[ -x "${JDK_HOME}/bin/java" ]] || { echo 'FAIL: managed JDK was not installed' >&2; exit 1; }
[[ "${JAVA_HOME}" == "${JDK_HOME}" ]] || { echo 'FAIL: JAVA_HOME was not refreshed in installer process' >&2; exit 1; }
[[ "${PATH}" == "${JDK_HOME}/bin:"* ]] || { echo 'FAIL: managed JDK was not prepended to PATH' >&2; exit 1; }
grep -Fq "export JAVA_HOME=\"${JDK_HOME}\"" "${JAVA_PROFILE_FILE}" \
  || { echo 'FAIL: persistent JAVA_HOME was not written' >&2; exit 1; }
grep -Fq 'export PATH="${JAVA_HOME}/bin:${PATH}"' "${JAVA_PROFILE_FILE}" \
  || { echo 'FAIL: persistent PATH was not written safely' >&2; exit 1; }

prepare_manual_jdk >/dev/null
[[ "${DOWNLOAD_COUNT}" == "1" ]] || { echo 'FAIL: matching JDK must skip repeated download' >&2; exit 1; }

echo 'manual runtime version tests passed'
