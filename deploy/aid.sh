#!/bin/bash
# ============================================================================
# AID 统一部署管理脚本（菜单式，Docker 与手动部署通用）
#
# 用法：
#   sudo bash aid.sh              # 交互菜单（首次部署自动下载最新版发布包）
#   sudo bash aid.sh <子命令>     # 直通执行：install/auto/install-docker/install-manual/update/rollback/
#                                 # restart/stop/status/logs/config/backup/setup-updater
#
# 设计：
#   - 全部数据统一放在 DATA_ROOT（默认 /data/aid）：程序、上传文件、日志、
#     中间件数据、备份、发布包缓存
#   - 首次部署自动从模板创建正式配置；后续配置真源 = 用户维护的正式配置文件：
#       Docker 部署 → deploy/docker/.env（模板 .env.example）
#       手动部署   → DATA_ROOT/aid-deploy.conf（模板 deploy/aid-deploy.conf.example）
#     密码/密钥留空自动生成强随机值写回；改配置 = 编辑文件 + 菜单「重启服务」
#   - 自动识别部署方式（docker / manual），每个环节自动判断当前状态
# ============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DATA_ROOT="${AID_DATA_ROOT:-/data/aid}"
# 数据根目录必须是绝对路径：conf/systemd/compose 挂载全部依赖它可寻址
case "${DATA_ROOT}" in
  /*) ;;
  *) echo "[失败] AID_DATA_ROOT 必须是绝对路径（当前: ${DATA_ROOT}）" >&2; exit 1 ;;
esac
CONF="${DATA_ROOT}/aid-deploy.conf"
COMPOSE_DIR="${SCRIPT_DIR}/docker"
HEALTH_WAIT_SECONDS=300

# ----------------------------------------------------------------------------
# 基础工具
# ----------------------------------------------------------------------------
C_GREEN='\033[32m'; C_YELLOW='\033[33m'; C_RED='\033[31m'; C_CYAN='\033[36m'; C_BLUE='\033[34m'; C_BOLD='\033[1m'; C_RESET='\033[0m'
log()  { echo -e "[$(date '+%H:%M:%S')] $1"; }
ok()   { echo -e "[$(date '+%H:%M:%S')] ${C_GREEN}[OK]${C_RESET} $1"; }
warn() { echo -e "[$(date '+%H:%M:%S')] ${C_YELLOW}[提示]${C_RESET} $1"; }
err()  { echo -e "[$(date '+%H:%M:%S')] ${C_RED}[失败]${C_RESET} $1" >&2; }
die()  { err "$1"; exit 1; }

MANIFEST_PRIMARY_URL="${AID_MANIFEST_URL:-https://gitee.com/gzxx-2025/aid-server/raw/master/release/latest.json}"
MANIFEST_FALLBACK_URL="https://raw.githubusercontent.com/gzxx-2025/aid-server/master/release/latest.json"
TRUSTED_MANIFEST_PUBLIC_KEY="9Ez/VMofgjCU0CNmE6Jq8LKLNyfDQqbbvNTTGV5BYrk="
INSTALLER_ROOT="${DATA_ROOT}/installer"
MANAGED_SCRIPT="${INSTALLER_ROOT}/deploy/aid.sh"
DOWNLOAD_TIMEOUT_SECONDS="${AID_DOWNLOAD_TIMEOUT_SECONDS:-1800}"

risk() {
  echo -e "[$(date '+%H:%M:%S')] ${C_RED}${C_BOLD}[风险提醒]${C_RESET} $1" >&2
}

section() {
  echo ""
  echo -e "${C_CYAN}${C_BOLD}==================== $1 ====================${C_RESET}"
}

require_root() { [[ "$(id -u)" -eq 0 ]] || die "请使用 root 执行（sudo bash aid.sh）"; }

# 配置读写：key=value 存于 ${CONF}
conf_get() { # conf_get <key> <默认值>
  local value=""
  [[ -f "${CONF}" ]] && value="$(grep -E "^${1}=" "${CONF}" 2>/dev/null | head -n 1 | cut -d= -f2-)"
  echo "${value:-${2:-}}"
}
conf_set() { # conf_set <key> <value>
  mkdir -p "${DATA_ROOT}"
  touch "${CONF}"; chmod 600 "${CONF}"
  if grep -qE "^${1}=" "${CONF}" 2>/dev/null; then
    sed -i "s|^${1}=.*|${1}=${2}|" "${CONF}"
  else
    echo "${1}=${2}" >> "${CONF}"
  fi
}
ask() { # ask <提示> <默认值>
  local answer
  read -r -p "$1 [$2]: " answer </dev/tty
  echo "${answer:-$2}"
}
ask_secret() { # ask_secret <提示>
  local answer
  read -r -s -p "$1: " answer </dev/tty
  echo "" >/dev/tty
  echo "${answer}"
}
gen_secret() { tr -dc 'A-Za-z0-9' </dev/urandom | head -c 48 || true; }

# Docker 部署配置真源：deploy/docker/.env（首次由模板自动创建，之后由用户维护）
ENV_FILE="${COMPOSE_DIR}/.env"
env_get() { # env_get <key> <默认值>
  local value=""
  [[ -f "${ENV_FILE}" ]] && value="$(grep -E "^${1}=" "${ENV_FILE}" 2>/dev/null | head -n 1 | cut -d= -f2-)"
  echo "${value:-${2:-}}"
}
env_set() { # env_set <key> <value>（仅用于自动生成缺失密钥，其余内容不动）
  if grep -qE "^${1}=" "${ENV_FILE}" 2>/dev/null; then
    sed -i "s|^${1}=.*|${1}=${2}|" "${ENV_FILE}"
  else
    echo "${1}=${2}" >> "${ENV_FILE}"
  fi
}

# 按部署方式读配置：docker 读 .env（用户维护的唯一真源），manual 读 aid-deploy.conf
setting_get() { # setting_get <key> <默认值>
  if [[ "$(detect_mode)" == "docker" ]]; then
    env_get "$1" "${2:-}"
  else
    conf_get "$1" "${2:-}"
  fi
}

# 凭证字符校验：拒绝会破坏 .env / systemd unit 解析的字符（空格、#、引号、$、反斜杠）
validate_secret() { # validate_secret <名称> <值>
  case "$2" in
    *' '*|*'#'*|*'"'*|*"'"*|*'$'*|*'\'*)
      die "$1 不能包含空格、#、引号、\$ 或反斜杠（建议留空使用自动生成的强随机值）" ;;
  esac
}

# ----------------------------------------------------------------------------
# 硬件配置校验：按部署内容动态计算最低/推荐配置，低于最低配置拒绝安装。
# 依据（各组件常驻内存占用估算，含 JVM 堆外与系统开销）见 deploy/README.md「配置要求」。
# ----------------------------------------------------------------------------
check_hardware() { # check_hardware <docker|manual> <mq:yes|no>
  local mode="$1" withMq="$2"
  local cpuCores memTotalMb diskFreeGb diskProbe
  cpuCores="$(nproc 2>/dev/null || echo 1)"
  memTotalMb="$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)"
  # 数据目录首次部署时尚不存在，向上找最近存在的父目录测其所在分区的剩余空间
  diskProbe="${DATA_ROOT}"
  while [[ ! -d "${diskProbe}" && "${diskProbe}" != "/" ]]; do diskProbe="$(dirname "${diskProbe}")"; done
  diskFreeGb="$(df -Pk "${diskProbe}" 2>/dev/null | awk 'NR==2 {printf "%d", $4/1024/1024}')"
  [[ -n "${diskFreeGb}" ]] || diskFreeGb=0

  # 最低/推荐配置（MB）：按部署内容累加
  local minCpu minMem recCpu recMem minDisk
  if [[ "${mode}" == "docker" ]]; then
    # Docker 全栈：MySQL(~1.5G) + Redis(0.5G) + 后端JVM(~2.5G) + Node SSR(~0.4G) + Nginx + 系统(~1G)
    minCpu=2; minMem=$((4 * 1024 - 512)); recCpu=4; recMem=$((8 * 1024 - 512)); minDisk=40
  else
    # 手动部署：中间件可能在本机也可能在别机，按"后端+SSR在本机"计算下限
    minCpu=2; minMem=$((4 * 1024 - 512)); recCpu=4; recMem=$((8 * 1024 - 512)); minDisk=40
  fi
  if [[ "${withMq}" == "yes" ]]; then
    # RocketMQ NameServer(0.3G) + Broker(1G堆 + 堆外/页缓存 ~1G)
    minMem=$((minMem + 2 * 1024)); recMem=$((recMem + 4 * 1024)); recCpu=$((recCpu + 2))
  fi

  echo ""
  log "${C_CYAN}==> 硬件配置校验${C_RESET}"
  echo "  本机: ${cpuCores} 核 / $((memTotalMb / 1024))G 内存 / 数据盘剩余 ${diskFreeGb}G"
  echo "  最低: $((minCpu)) 核 / $(( (minMem + 512) / 1024 ))G 内存 / ${minDisk}G 磁盘"
  echo "  推荐: $((recCpu)) 核 / $(( (recMem + 512) / 1024 ))G 内存 / 100G+ 磁盘"

  local blocked=0
  [[ "${cpuCores}" -lt "${minCpu}" ]] && err "CPU 核数低于最低要求（${cpuCores} < ${minCpu}）" && blocked=1
  [[ "${memTotalMb}" -lt "${minMem}" ]] && err "内存低于最低要求（$((memTotalMb / 1024))G < $(( (minMem + 512) / 1024 ))G）" && blocked=1
  [[ "${diskFreeGb}" -lt "${minDisk}" ]] && err "数据盘剩余空间低于最低要求（${diskFreeGb}G < ${minDisk}G）" && blocked=1
  if [[ "${blocked}" -eq 1 ]]; then
    die "硬件不满足最低配置，安装已中止（媒体生成类业务低配运行极易 OOM/写满磁盘，请升级服务器配置）"
  fi
  if [[ "${cpuCores}" -lt "${recCpu}" || "${memTotalMb}" -lt "${recMem}" ]]; then
    warn "达到最低配置但低于推荐配置：可以运行，高并发生成任务时可能吃紧"
    local go
    go="$(ask '是否继续安装？(yes/no)' 'yes')"
    [[ "${go}" == "yes" ]] || die "已取消安装"
  else
    ok "硬件满足推荐配置"
  fi
}

# 部署方式检测：conf 优先，其次按运行痕迹探测
detect_mode() {
  local mode
  mode="$(conf_get DEPLOY_MODE '')"
  if [[ -n "${mode}" ]]; then echo "${mode}"; return; fi
  if command -v docker >/dev/null 2>&1 && docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q '^aid-server$'; then
    echo "docker"; return
  fi
  if systemctl list-unit-files 2>/dev/null | grep -q '^aid\.service'; then
    echo "manual"; return
  fi
  echo "none"
}

# 当前部署版本优先读取升级器同步维护的 build-info.json，旧环境回退到配置记录。
current_version() {
  local buildInfo="${DATA_ROOT}/app/build-info.json" version=""
  if [[ -f "${buildInfo}" ]]; then
    version="$(grep -E '^[[:space:]]*"version"[[:space:]]*:' "${buildInfo}" 2>/dev/null | head -n 1 \
      | sed -E 's/^[^:]+:[[:space:]]*"([^"]+)".*/\1/')"
  fi
  echo "${version:-$(conf_get CURRENT_VERSION '未知')}"
}

# ----------------------------------------------------------------------------
# 官方版本发现、签名核验、下载与单文件自举
# ----------------------------------------------------------------------------
RESOLVED_CHANNEL=""
REQUESTED_RELEASE_CHANNEL=""
RESOLVED_VERSION=""
RESOLVED_PACKAGE_URL=""
RESOLVED_PACKAGE_SHA256=""
RESOLVED_PACKAGE_PATH=""
RESOLVED_MANIFEST_PATH=""

sha256_file() { # sha256_file <文件>
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print tolower($1)}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print tolower($1)}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$1" | awk '{print tolower($NF)}'
  else
    return 1
  fi
}

require_download_tools() {
  command -v curl >/dev/null 2>&1 || die "未检测到 curl，无法自动下载：请先安装 curl"
  command -v tar >/dev/null 2>&1 || die "未检测到 tar，无法解压发布包"
  sha256_file "${BASH_SOURCE[0]}" >/dev/null 2>&1 || die "缺少 SHA256 校验工具（sha256sum/shasum/openssl）"
}

try_download() { # try_download <URL> <目标文件> <名称>
  local url="$1" target="$2" label="$3" part="${2}.part"
  case "${url}" in
    https://*) ;;
    *) err "拒绝非 HTTPS 下载地址: ${url}"; return 1 ;;
  esac
  rm -f "${part}"
  log "${C_BLUE}下载 ${label}${C_RESET}"
  echo "  ${url}"
  if ! curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 15 \
      --max-time "${DOWNLOAD_TIMEOUT_SECONDS}" --proto '=https' --tlsv1.2 \
      --progress-bar --output "${part}" "${url}"; then
    rm -f "${part}"
    return 1
  fi
  [[ -s "${part}" ]] || { rm -f "${part}"; return 1; }
  mv -f "${part}" "${target}"
  return 0
}

# 读取发布工具生成的格式化 JSON 直属字符串字段。顶层缩进 2 格，beta 直属字段缩进 4 格。
json_direct_string() { # json_direct_string <文件> <缩进> <字段>
  local line
  line="$(grep -m 1 "^${2}\"${3}\"[[:space:]]*:" "$1" 2>/dev/null || true)"
  printf '%s\n' "${line}" | sed -E 's/^[[:space:]]*"[^"]+"[[:space:]]*:[[:space:]]*"//; s/"[[:space:]]*,?[[:space:]]*$//; s#\\/#/#g'
}

json_signature_string() { # json_signature_string <文件> <字段>
  sed -n '/^  "signature"[[:space:]]*:/,/^  }/p' "$1" \
    | grep -m 1 "^    \"${2}\"[[:space:]]*:" \
    | sed -E 's/^[[:space:]]*"[^"]+"[[:space:]]*:[[:space:]]*"//; s/"[[:space:]]*,?[[:space:]]*$//'
}

verify_manifest_signature() { # verify_manifest_signature <清单> <版本> <URL> <SHA256>
  local manifest="$1" version="$2" packageUrl="$3" packageSha="$4"
  local payloadB64 signatureB64 algorithm tmpDir payloadFile signatureFile publicKeyFile
  algorithm="$(json_signature_string "${manifest}" algorithm)"
  payloadB64="$(json_signature_string "${manifest}" payload)"
  signatureB64="$(json_signature_string "${manifest}" value)"
  [[ "${algorithm}" == "Ed25519" && -n "${payloadB64}" && -n "${signatureB64}" ]] \
    || die "官方版本清单缺少 Ed25519 签名，已拒绝下载"

  # 老系统的 OpenSSL 可能不支持 Ed25519；支持时必须验签，不支持时仍依赖 HTTPS + 包 SHA256。
  if ! command -v base64 >/dev/null 2>&1 || ! command -v openssl >/dev/null 2>&1 \
      || ! openssl list -public-key-algorithms 2>/dev/null | grep -qi 'ED25519' \
      || ! openssl pkeyutl -help 2>&1 | grep -q -- '-rawin'; then
    warn "本机 OpenSSL 不支持 Ed25519，清单签名暂无法在 Shell 层验证；仍会强制校验 HTTPS 来源与发布包 SHA256"
    return 0
  fi

  tmpDir="$(mktemp -d)"
  payloadFile="${tmpDir}/payload.json"
  signatureFile="${tmpDir}/signature.bin"
  publicKeyFile="${tmpDir}/public-key.der"
  if ! printf '%s' "${payloadB64}" | base64 -d > "${payloadFile}" 2>/dev/null \
      || ! printf '%s' "${signatureB64}" | base64 -d > "${signatureFile}" 2>/dev/null; then
    rm -rf "${tmpDir}"
    die "官方版本清单签名编码无效"
  fi
  # Ed25519 SubjectPublicKeyInfo DER 前缀 + 32 字节原始公钥。
  printf '\060\052\060\005\006\003\053\145\160\003\041\000' > "${publicKeyFile}"
  if ! printf '%s' "${TRUSTED_MANIFEST_PUBLIC_KEY}" | base64 -d >> "${publicKeyFile}" 2>/dev/null \
      || ! openssl pkeyutl -verify -pubin -keyform DER -inkey "${publicKeyFile}" -rawin \
          -in "${payloadFile}" -sigfile "${signatureFile}" >/dev/null 2>&1; then
    rm -rf "${tmpDir}"
    die "官方版本清单签名验证失败，可能被篡改，已中止"
  fi
  # 所用的版本、URL、摘要必须都存在于已签名载荷中，防止只替换清单外层字段。
  if ! grep -Fq "\"productVersion\":\"${version}\"" "${payloadFile}" \
      || ! grep -Fq "\"packageUrl\":\"${packageUrl}\"" "${payloadFile}" \
      || ! grep -Fq "\"packageSha256\":\"${packageSha}\"" "${payloadFile}"; then
    rm -rf "${tmpDir}"
    die "版本信息与签名载荷不一致，已中止"
  fi
  rm -rf "${tmpDir}"
  ok "官方版本清单 Ed25519 签名验证通过"
}

fetch_release_manifest() {
  require_download_tools
  mkdir -p "${DATA_ROOT}/packages"
  RESOLVED_MANIFEST_PATH="${DATA_ROOT}/packages/latest.json"
  local candidate="${RESOLVED_MANIFEST_PATH}.new" url downloaded="no"
  for url in "${MANIFEST_PRIMARY_URL}" "${MANIFEST_FALLBACK_URL}"; do
    [[ -n "${url}" ]] || continue
    if try_download "${url}" "${candidate}" "官方版本清单"; then
      if [[ "$(wc -c < "${candidate}")" -le 262144 ]] \
          && grep -q '"schemaVersion"' "${candidate}" \
          && grep -q '"signature"' "${candidate}"; then
        mv -f "${candidate}" "${RESOLVED_MANIFEST_PATH}"
        downloaded="yes"
        break
      fi
      warn "下载到的版本清单格式异常，尝试备用地址"
    else
      warn "版本清单下载失败，尝试备用地址"
    fi
  done
  [[ "${downloaded}" == "yes" ]] || die "无法获取官方版本清单，请检查服务器能否访问 Gitee 或 GitHub"
}

release_fields_valid() { # release_fields_valid <版本> <URL> <SHA256>
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ \
     && "$2" == https://* \
     && "$3" =~ ^[0-9a-fA-F]{64}$ ]]
}

resolve_official_release() {
  fetch_release_manifest
  local requested="${AID_RELEASE_CHANNEL:-$(conf_get RELEASE_CHANNEL auto)}"
  local stableVersion stableUrl stableSha betaVersion betaUrl betaSha indent
  case "${requested}" in
    auto|stable|beta) ;;
    *) die "发布渠道只支持 auto、stable 或 beta（当前: ${requested}）" ;;
  esac
  REQUESTED_RELEASE_CHANNEL="${requested}"

  stableVersion="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" '  ' productVersion)"
  stableUrl="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" '  ' packageUrl)"
  stableSha="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" '  ' packageSha256)"
  betaVersion="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" '    ' productVersion)"
  betaUrl="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" '    ' packageUrl)"
  betaSha="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" '    ' packageSha256)"

  if [[ "${requested}" == "auto" ]]; then
    if release_fields_valid "${stableVersion}" "${stableUrl}" "${stableSha}"; then
      requested="stable"
    elif release_fields_valid "${betaVersion}" "${betaUrl}" "${betaSha}"; then
      requested="beta"
      warn "当前尚无可安装的正式版，已自动选择最新 Beta 测试版"
    else
      die "官方清单中没有可安装的正式版或 Beta 版"
    fi
  fi

  indent='  '
  [[ "${requested}" == "beta" ]] && indent='    '
  RESOLVED_CHANNEL="${requested}"
  RESOLVED_VERSION="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" "${indent}" productVersion)"
  RESOLVED_PACKAGE_URL="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" "${indent}" packageUrl)"
  RESOLVED_PACKAGE_SHA256="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" "${indent}" packageSha256 | tr 'A-F' 'a-f')"
  release_fields_valid "${RESOLVED_VERSION}" "${RESOLVED_PACKAGE_URL}" "${RESOLVED_PACKAGE_SHA256}" \
    || die "官方清单中的 ${RESOLVED_CHANNEL} 版本信息不完整"
  if [[ "${AID_ALLOW_CUSTOM_RELEASE_URL:-0}" != "1" ]]; then
    case "${RESOLVED_PACKAGE_URL}" in
      https://github.com/gzxx-2025/aid-server/releases/download/*|https://gitee.com/gzxx-2025/aid-server/releases/download/*) ;;
      *) die "清单返回了非官方制品地址，已拒绝: ${RESOLVED_PACKAGE_URL}" ;;
    esac
  fi
  verify_manifest_signature "${RESOLVED_MANIFEST_PATH}" "${RESOLVED_VERSION}" \
    "${RESOLVED_PACKAGE_URL}" "${RESOLVED_PACKAGE_SHA256}"
  [[ "${RESOLVED_CHANNEL}" == "beta" ]] && warn "即将使用 Beta 测试版 ${RESOLVED_VERSION}，生产环境请先做好数据备份"
}

validate_release_package() { # validate_release_package <包> <是否要求安装器 yes|no>
  local package="$1" requireInstaller="$2" listFile entry archEntry="" scriptEntry=""
  listFile="$(mktemp)"
  if ! tar -tzf "${package}" > "${listFile}" 2>/dev/null; then
    rm -f "${listFile}"
    die "发布包无法解压或已损坏: ${package}"
  fi
  if [[ "$(wc -l < "${listFile}")" -gt 100000 ]]; then
    rm -f "${listFile}"
    die "发布包文件数量异常，已拒绝解压"
  fi
  # 安装/更新包不需要符号链接、硬链接或设备文件；拒绝这些类型，避免链接逃逸目标目录。
  if tar -tvzf "${package}" 2>/dev/null \
      | awk 'substr($1,1,1) ~ /^[lhbcp]$/ { found=1 } END { exit(found ? 0 : 1) }'; then
    rm -f "${listFile}"
    die "发布包包含链接或特殊设备文件，已拒绝解压"
  fi
  while IFS= read -r entry; do
    case "${entry}" in
      /*|../*|*/../*|*/..) rm -f "${listFile}"; die "发布包包含不安全路径，已拒绝解压" ;;
    esac
  done < "${listFile}"
  grep -Eq '(^|/)backend/aid-admin\.jar$' "${listFile}" || { rm -f "${listFile}"; die "发布包缺少后端程序"; }
  grep -Eq '(^|/)web-dist/server/index\.mjs$' "${listFile}" || { rm -f "${listFile}"; die "发布包缺少 Web SSR 产物"; }
  grep -Eq '(^|/)build-info\.json$' "${listFile}" || { rm -f "${listFile}"; die "发布包缺少 build-info.json"; }
  case "$(uname -m)" in
    x86_64) archEntry='updater/aid-updater_linux_amd64' ;;
    aarch64) archEntry='updater/aid-updater_linux_arm64' ;;
  esac
  if [[ -n "${archEntry}" ]]; then
    grep -Eq "(^|/)${archEntry}$" "${listFile}" || { rm -f "${listFile}"; die "发布包缺少本机架构的升级器"; }
  fi
  if [[ "${requireInstaller}" == "yes" ]]; then
    for entry in installer/deploy/aid.sh installer/deploy/docker/.env.example \
        installer/deploy/docker/docker-compose.yml installer/sql/aid-init.sql; do
      grep -Eq "(^|/)${entry}$" "${listFile}" \
        || { rm -f "${listFile}"; die "发布包缺少一键安装组件: ${entry}"; }
    done
  fi
  scriptEntry="$(grep -E '(^|/)installer/deploy/aid\.sh$' "${listFile}" | head -n 1 || true)"
  if [[ -n "${scriptEntry}" ]]; then
    if ! tar -xOzf "${package}" "${scriptEntry}" 2>/dev/null | bash -n; then
      rm -f "${listFile}"
      die "发布包内的一键安装脚本语法无效，已拒绝执行"
    fi
  fi
  rm -f "${listFile}"
}

ensure_official_package() {
  mkdir -p "${DATA_ROOT}/packages"
  RESOLVED_PACKAGE_PATH="${DATA_ROOT}/packages/aid-v${RESOLVED_VERSION}.tar.gz"
  local actual invalid
  if [[ -f "${RESOLVED_PACKAGE_PATH}" ]]; then
    actual="$(sha256_file "${RESOLVED_PACKAGE_PATH}" || true)"
    if [[ "${actual}" == "${RESOLVED_PACKAGE_SHA256}" ]]; then
      ok "复用已校验的发布包: ${RESOLVED_PACKAGE_PATH}"
      validate_release_package "${RESOLVED_PACKAGE_PATH}" no
      return 0
    fi
    invalid="${RESOLVED_PACKAGE_PATH}.invalid-$(date +%Y%m%d%H%M%S)"
    mv "${RESOLVED_PACKAGE_PATH}" "${invalid}"
    risk "发现摘要不匹配的缓存包，已隔离为 ${invalid}"
  fi
  try_download "${RESOLVED_PACKAGE_URL}" "${RESOLVED_PACKAGE_PATH}" "AID v${RESOLVED_VERSION} 主程序包" \
    || die "主程序包下载失败；未对现有部署做任何修改，请检查 GitHub 网络连接后重试"
  actual="$(sha256_file "${RESOLVED_PACKAGE_PATH}" || true)"
  if [[ "${actual}" != "${RESOLVED_PACKAGE_SHA256}" ]]; then
    invalid="${RESOLVED_PACKAGE_PATH}.invalid-$(date +%Y%m%d%H%M%S)"
    mv "${RESOLVED_PACKAGE_PATH}" "${invalid}" 2>/dev/null || rm -f "${RESOLVED_PACKAGE_PATH}"
    die "发布包 SHA256 校验失败，已拒绝安装并隔离异常文件（期望 ${RESOLVED_PACKAGE_SHA256}，实际 ${actual:-无法计算}）"
  fi
  validate_release_package "${RESOLVED_PACKAGE_PATH}" no
  ok "发布包 SHA256 与结构校验通过"
}

deployment_runtime_ready() {
  [[ -f "${SCRIPT_DIR}/aid-deploy.conf.example" \
     && -f "${COMPOSE_DIR}/.env.example" \
     && -f "${COMPOSE_DIR}/docker-compose.yml" \
     && -f "${REPO_DIR}/sql/aid-init.sql" ]]
}

handoff_to_managed_installer() { # handoff_to_managed_installer <原始参数...>
  local managedDir
  managedDir="$(dirname "${MANAGED_SCRIPT}")"
  if [[ -f "${MANAGED_SCRIPT}" && "${SCRIPT_DIR}" != "${managedDir}" \
      && -f "${INSTALLER_ROOT}/deploy/docker/docker-compose.yml" \
      && -f "${INSTALLER_ROOT}/sql/aid-init.sql" ]]; then
    log "切换到已安装的统一管理脚本: ${MANAGED_SCRIPT}"
    exec bash "${MANAGED_SCRIPT}" "$@"
  fi
}

bootstrap_installer_if_needed() { # bootstrap_installer_if_needed <发布包> <install-docker|install-manual>
  local package="$1" action="$2" managedDir
  managedDir="$(dirname "${MANAGED_SCRIPT}")"
  if [[ "${SCRIPT_DIR}" == "${managedDir}" ]] && deployment_runtime_ready; then
    return 0
  fi
  validate_release_package "${package}" yes
  section "初始化 AID 一键安装器"
  mkdir -p "${DATA_ROOT}"
  tar -xzf "${package}" -C "${DATA_ROOT}" installer \
    || die "从发布包提取安装器失败"
  chmod 700 "${MANAGED_SCRIPT}" 2>/dev/null || true
  [[ -f "${MANAGED_SCRIPT}" ]] || die "安装器落盘失败: ${MANAGED_SCRIPT}"
  ok "完整安装器已安全落盘: ${INSTALLER_ROOT}"
  export AID_RELEASE_CHANNEL="${REQUESTED_RELEASE_CHANNEL:-${RESOLVED_CHANNEL:-auto}}"
  exec bash "${MANAGED_SCRIPT}" "${action}" "${package}"
}

refresh_managed_installer() { # refresh_managed_installer <发布包>
  local package="$1"
  if tar -tzf "${package}" 2>/dev/null | grep -qE '(^|/)installer/deploy/aid\.sh$'; then
    tar -xzf "${package}" -C "${DATA_ROOT}" installer \
      || { warn "程序已升级，但安装器文件刷新失败，请保留当前终端并人工检查"; return 1; }
    chmod 700 "${MANAGED_SCRIPT}" 2>/dev/null || true
    ok "部署管理脚本已同步到新版本"
  fi
}

install_management_command() {
  local commandPath="/usr/local/bin/aid" existingTarget=""
  [[ -f "${MANAGED_SCRIPT}" ]] || return 0
  if [[ -L "${commandPath}" ]]; then
    existingTarget="$(readlink "${commandPath}" 2>/dev/null || true)"
    [[ "${existingTarget}" == "${MANAGED_SCRIPT}" ]] && return 0
  fi
  if [[ -e "${commandPath}" || -L "${commandPath}" ]]; then
    warn "${commandPath} 已被其他程序占用，未覆盖；仍可执行 sudo bash ${MANAGED_SCRIPT}"
    return 0
  fi
  ln -s "${MANAGED_SCRIPT}" "${commandPath}" 2>/dev/null \
    && ok "已安装管理命令: sudo aid（更新可用 sudo aid update）" \
    || warn "无法创建 ${commandPath}，不影响部署与后台在线升级"
}

version_compare() { # version_compare <左> <右>：输出 1/0/-1
  local left="$1" right="$2" leftCore rightCore leftPre="" rightPre=""
  local i max leftPart rightPart LC_ALL=C
  local -a leftCoreParts rightCoreParts leftPreParts rightPreParts
  [[ "${left}" == "${right}" ]] && { echo 0; return; }

  leftCore="${left%%-*}"; rightCore="${right%%-*}"
  [[ "${left}" == *-* ]] && leftPre="${left#*-}"
  [[ "${right}" == *-* ]] && rightPre="${right#*-}"
  IFS='.' read -r -a leftCoreParts <<< "${leftCore}"
  IFS='.' read -r -a rightCoreParts <<< "${rightCore}"
  for i in 0 1 2; do
    leftPart="${leftCoreParts[$i]:-0}"; rightPart="${rightCoreParts[$i]:-0}"
    if (( 10#${leftPart} > 10#${rightPart} )); then echo 1; return; fi
    if (( 10#${leftPart} < 10#${rightPart} )); then echo -1; return; fi
  done

  # SemVer：相同核心版本下，正式版高于任何预发布版本。
  [[ -z "${leftPre}" && -n "${rightPre}" ]] && { echo 1; return; }
  [[ -n "${leftPre}" && -z "${rightPre}" ]] && { echo -1; return; }
  [[ -z "${leftPre}" ]] && { echo 0; return; }

  IFS='.' read -r -a leftPreParts <<< "${leftPre}"
  IFS='.' read -r -a rightPreParts <<< "${rightPre}"
  max=${#leftPreParts[@]}; (( ${#rightPreParts[@]} > max )) && max=${#rightPreParts[@]}
  for ((i=0; i<max; i++)); do
    # 前面标识符都相同时，较短的预发布字段优先级更低（alpha < alpha.1）。
    (( i >= ${#leftPreParts[@]} )) && { echo -1; return; }
    (( i >= ${#rightPreParts[@]} )) && { echo 1; return; }
    leftPart="${leftPreParts[$i]}"; rightPart="${rightPreParts[$i]}"
    [[ "${leftPart}" == "${rightPart}" ]] && continue
    if [[ "${leftPart}" =~ ^[0-9]+$ && "${rightPart}" =~ ^[0-9]+$ ]]; then
      if (( 10#${leftPart} > 10#${rightPart} )); then echo 1; else echo -1; fi
    elif [[ "${leftPart}" =~ ^[0-9]+$ ]]; then
      echo -1
    elif [[ "${rightPart}" =~ ^[0-9]+$ ]]; then
      echo 1
    elif [[ "${leftPart}" > "${rightPart}" ]]; then
      echo 1
    else
      echo -1
    fi
    return
  done
  echo 0
}

prepare_install_package() { # prepare_install_package [本地包]
  local supplied="${1:-}"
  if [[ -n "${supplied}" ]]; then
    [[ -f "${supplied}" ]] || die "发布包不存在: ${supplied}"
    RESOLVED_PACKAGE_PATH="$(cd "$(dirname "${supplied}")" && pwd)/$(basename "${supplied}")"
    RESOLVED_VERSION="$(version_from_package "${RESOLVED_PACKAGE_PATH}")"
    REQUESTED_RELEASE_CHANNEL="${AID_RELEASE_CHANNEL:-$(conf_get RELEASE_CHANNEL auto)}"
    case "${REQUESTED_RELEASE_CHANNEL}" in
      auto|stable|beta) ;;
      *) die "发布渠道只支持 auto、stable 或 beta（当前: ${REQUESTED_RELEASE_CHANNEL}）" ;;
    esac
    if [[ "${RESOLVED_VERSION}" == *-* ]]; then RESOLVED_CHANNEL="beta"; else RESOLVED_CHANNEL="stable"; fi
    risk "你选择了本地发布包，脚本只能校验包结构，无法确认它是否来自官方 Release"
    validate_release_package "${RESOLVED_PACKAGE_PATH}" no
    return 0
  fi
  resolve_official_release
  ensure_official_package
}

confirm_first_install() { # confirm_first_install <docker|manual>
  local mode="$1" answer defaultAnswer="yes"
  section "首次部署确认"
  echo -e "  部署方式 : ${C_GREEN}${mode}${C_RESET}"
  echo -e "  目标版本 : ${C_GREEN}${RESOLVED_VERSION}${C_RESET} (${RESOLVED_CHANNEL})"
  echo "  数据目录 : ${DATA_ROOT}"
  if [[ "${mode}" == "docker" ]]; then
    echo "  用户端口 : $(env_get HTTP_PORT 80)"
    echo "  管理端口 : $(env_get ADMIN_PORT 8090)"
    echo "  后端端口 : $(env_get BACKEND_PORT 8080)"
  fi
  warn "安装会拉取 Docker 镜像、创建服务并占用以上端口；不会自动开放防火墙或修改域名解析"
  risk "后台初始账号为 admin / admin123，首次登录后必须立即修改密码"
  warn "官方媒体资产包体积较大且存储目标因人而异，本步骤不会静默下载或写入 OSS/COS"
  if [[ -d "${DATA_ROOT}" ]] && find "${DATA_ROOT}" -mindepth 1 -maxdepth 1 \
      ! -name packages ! -name installer ! -name aid-deploy.conf -print -quit 2>/dev/null | grep -q .; then
    risk "${DATA_ROOT} 已存在非安装缓存内容；继续前请确认这里不是其他业务的数据目录"
    defaultAnswer="no"
  fi
  if [[ "${AID_ASSUME_YES:-0}" == "1" ]]; then
    risk "AID_ASSUME_YES=1：已跳过人工确认，请确保这是你明确授权的全新服务器"
    return 0
  fi
  answer="$(ask '确认开始部署？(yes/no)' "${defaultAnswer}")"
  [[ "${answer}" == "yes" ]] || die "已取消部署，未启动任何 AID 服务"
}

# ----------------------------------------------------------------------------
# 配置收集（首次部署 / 修改配置共用；默认值 = 已保存值 > 出厂默认）
# ----------------------------------------------------------------------------
# ----------------------------------------------------------------------------
# 手动部署配置校验：aid-deploy.conf 首次由模板自动创建（唯一配置真源），
# 仅提示输入必要的外部数据库密码；TOKEN_SECRET 留空时自动生成强随机值写回
# ----------------------------------------------------------------------------
ensure_conf_file() {
  if [[ ! -f "${CONF}" ]]; then
    [[ -f "${SCRIPT_DIR}/aid-deploy.conf.example" ]] || die "缺少手动部署配置模板"
    mkdir -p "${DATA_ROOT}"
    cp "${SCRIPT_DIR}/aid-deploy.conf.example" "${CONF}"
    chmod 600 "${CONF}"
    ok "已自动生成手动部署配置: ${CONF}"
    warn "手动部署使用本机或外部 MySQL/Redis/Node/Nginx，默认连接参数不一定适合你的环境"
  fi
  chmod 600 "${CONF}" 2>/dev/null || true
  # 必填校验：数据库密码必须由用户提供（无合理默认值）
  local dbPwd
  dbPwd="$(conf_get DB_PASSWORD '')"
  if [[ -z "${dbPwd}" ]]; then
    risk "手动部署必须连接现有数据库，脚本不会猜测或生成数据库密码"
    dbPwd="$(ask_secret '请输入数据库密码（输入内容不会显示）')"
    [[ -n "${dbPwd}" ]] || die "数据库密码不能为空；如需调整主机/端口，请编辑 ${CONF} 后重试"
    validate_secret 'DB_PASSWORD' "${dbPwd}"
    conf_set DB_PASSWORD "${dbPwd}"
  fi
  validate_secret 'DB_PASSWORD' "${dbPwd}"
  local redisPwd
  redisPwd="$(conf_get REDIS_PASSWORD '')"
  [[ -n "${redisPwd}" ]] && validate_secret 'REDIS_PASSWORD' "${redisPwd}"
  # JWT 密钥留空自动生成写回
  if [[ -z "$(conf_get TOKEN_SECRET '')" ]]; then
    conf_set TOKEN_SECRET "$(gen_secret)"
    ok "TOKEN_SECRET 留空，已自动生成强随机值写入配置"
  fi
  return 0
}

# ----------------------------------------------------------------------------
# Docker 模式配置校验：.env 首次由 .env.example 自动创建（唯一配置真源），
# 后续不覆盖用户配置；仅在关键密钥留空时自动生成强随机值写回
# ----------------------------------------------------------------------------
ensure_env_file() {
  if [[ ! -f "${ENV_FILE}" ]]; then
    [[ -f "${COMPOSE_DIR}/.env.example" ]] || die "缺少 Docker 配置模板"
    cp "${COMPOSE_DIR}/.env.example" "${ENV_FILE}"
    chmod 600 "${ENV_FILE}"
    ok "已自动生成 Docker 配置: ${ENV_FILE}"
    warn "首次安装采用安全默认方案：内置 MySQL + Redis、暂不启用 RocketMQ、密码与 JWT 密钥自动生成"
  fi
  # 关键密钥留空自动生成（字母数字强随机，写回 .env 持久化）
  local key
  for key in MYSQL_ROOT_PASSWORD DB_PASSWORD TOKEN_SECRET; do
    if [[ -z "$(env_get "${key}" '')" ]]; then
      env_set "${key}" "$(gen_secret)"
      ok "${key} 留空，已自动生成强随机值写入 .env"
    fi
  done
  # 校验密码字符不破坏 .env/JSON 解析
  validate_secret 'MYSQL_ROOT_PASSWORD' "$(env_get MYSQL_ROOT_PASSWORD '')"
  validate_secret 'DB_PASSWORD' "$(env_get DB_PASSWORD '')"
  local redisPwd
  redisPwd="$(env_get REDIS_PASSWORD '')"
  [[ -n "${redisPwd}" ]] && validate_secret 'REDIS_PASSWORD' "${redisPwd}"
  # 提醒 DATA_ROOT 与脚本运行目录保持一致
  local envDataRoot
  envDataRoot="$(env_get DATA_ROOT /data/aid)"
  if [[ "${envDataRoot}" != "${DATA_ROOT}" ]]; then
    warn ".env 的 DATA_ROOT(${envDataRoot}) 与脚本数据目录(${DATA_ROOT}) 不一致，以 .env 为准需同步设置 AID_DATA_ROOT 环境变量"
  fi
  return 0
}

compose_cmd() { (cd "${COMPOSE_DIR}" && docker compose "$@"); }

# ----------------------------------------------------------------------------
# 产物摆位（两种模式共用）：发布包 -> DATA_ROOT/app/
# ----------------------------------------------------------------------------
place_artifacts() { # place_artifacts <包路径>
  local package="$1" tmpDir pkgRoot jar
  tmpDir="$(mktemp -d)"
  # RETURN trap 触发后自解除：bash 的 trap 是全局的，不解除会在后续每次函数返回时重复执行
  # shellcheck disable=SC2064
  trap "rm -rf '${tmpDir}'; trap - RETURN" RETURN
  tar -xzf "${package}" -C "${tmpDir}" || die "发布包解压失败: ${package}"
  pkgRoot="${tmpDir}"
  if [[ ! -d "${pkgRoot}/backend" ]]; then
    local sub
    sub="$(find "${tmpDir}" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
    [[ -n "${sub}" && -d "${sub}/backend" ]] && pkgRoot="${sub}"
  fi
  jar="$(find "${pkgRoot}/backend" -maxdepth 1 -name '*.jar' 2>/dev/null | head -n 1 || true)"
  [[ -n "${jar}" ]] || die "包内缺少 backend/*.jar，不是合法的 AID 发布包"

  mkdir -p "${DATA_ROOT}/app" "${DATA_ROOT}/uploadPath" "${DATA_ROOT}/logs" "${DATA_ROOT}/backups" "${DATA_ROOT}/packages"
  install -m 0644 "${jar}" "${DATA_ROOT}/app/aid-admin.jar"
  ok "服务端产物已就位: ${DATA_ROOT}/app/aid-admin.jar"
  if [[ -f "${pkgRoot}/build-info.json" ]]; then
    install -m 0644 "${pkgRoot}/build-info.json" "${DATA_ROOT}/app/build-info.json"
  fi
  local dist
  for dist in admin-dist web-dist; do
    if [[ -d "${pkgRoot}/${dist}" ]]; then
      rm -rf "${DATA_ROOT}/app/${dist}"
      cp -r "${pkgRoot}/${dist}" "${DATA_ROOT}/app/${dist}"
      ok "${dist} 已就位"
    else
      mkdir -p "${DATA_ROOT}/app/${dist}"
      warn "包内不含 ${dist}（对应端将不可用）"
    fi
  done
  # 升级器二进制：按本机架构从包内 updater/ 选取（在线升级能力的执行代理）
  place_updater_binary "${pkgRoot}"
  # 增量 SQL 暂存（升级场景由 do_update 决定如何执行）
  rm -rf "${DATA_ROOT}/packages/pending-sql"
  if [[ -d "${pkgRoot}/sql" ]]; then
    cp -r "${pkgRoot}/sql" "${DATA_ROOT}/packages/pending-sql"
  fi
}

# 从发布包安装升级器二进制到 DATA_ROOT/app/updater/aid-updater（包内无 updater/ 时静默跳过）
place_updater_binary() { # place_updater_binary <包根目录>
  local pkgRoot="$1" archSuffix binSource
  case "$(uname -m)" in
    x86_64)  archSuffix="linux_amd64" ;;
    aarch64) archSuffix="linux_arm64" ;;
    *) warn "未知架构 $(uname -m)，跳过升级器二进制安装"; return 0 ;;
  esac
  binSource="${pkgRoot}/updater/aid-updater_${archSuffix}"
  if [[ ! -f "${binSource}" ]]; then
    return 0
  fi
  mkdir -p "${DATA_ROOT}/app/updater"
  install -m 0755 "${binSource}" "${DATA_ROOT}/app/updater/aid-updater"
  ok "升级器二进制已就位: ${DATA_ROOT}/app/updater/aid-updater"
}

# ----------------------------------------------------------------------------
# 升级器（aid-updater）安装：两种部署方式自动完成，页面即可一键升级
#   docker 模式 → compose 内 aid-updater 容器运行（写配置即可，容器随编排拉起）
#   manual 模式 → systemd 服务运行
# ----------------------------------------------------------------------------
UPDATER_CONFIG_DIR="/etc/aid-updater"
UPDATER_CONFIG_FILE="${UPDATER_CONFIG_DIR}/config.json"
UPDATER_DATA_DIR="/var/lib/aid-updater"

write_updater_config() { # write_updater_config <docker|manual>
  local mode="$1" serviceManager backendService restartServices healthUrl execContainer dbHost dbPort dbUser dbPwd dbName
  mkdir -p "${UPDATER_CONFIG_DIR}" "${UPDATER_DATA_DIR}/inbox" "${UPDATER_DATA_DIR}/work" "${UPDATER_DATA_DIR}/backups"
  if [[ "${mode}" == "docker" ]]; then
    serviceManager="docker"; backendService="aid-server"
    restartServices='["aid-web", "aid-nginx"]'
    # 升级器容器与后端同网络，直连服务名探活，不受宿主机端口映射影响
    healthUrl="http://aid-server:8080"
    # SQL/备份经 docker exec 在数据库容器内执行，宿主机与升级器容器都无需 MySQL 客户端
    execContainer="aid-mysql"
    dbHost="127.0.0.1"; dbPort="3306"
    dbUser="root"; dbPwd="$(env_get MYSQL_ROOT_PASSWORD '')"
    dbName="$(env_get DB_NAME aid)"
  else
    serviceManager="systemd"; backendService="aid"
    restartServices='["aid-web"]'
    healthUrl="http://127.0.0.1:$(conf_get BACKEND_PORT 8080)"
    execContainer=""
    dbHost="$(conf_get DB_HOST 127.0.0.1)"; dbPort="$(conf_get DB_PORT 3306)"
    dbUser="$(conf_get DB_USERNAME root)"; dbPwd="$(conf_get DB_PASSWORD '')"
    dbName="$(conf_get DB_NAME aid)"
  fi
  # 密码等值经 python/awk 不可靠，凭证字符已由 validate_secret 约束（无引号反斜杠），可安全嵌入 JSON
  cat > "${UPDATER_CONFIG_FILE}" <<EOF
{
  "healthFile": "${UPDATER_DATA_DIR}/health.json",
  "taskFile": "${UPDATER_DATA_DIR}/inbox/task.json",
  "workDir": "${UPDATER_DATA_DIR}/work",
  "backupDir": "${UPDATER_DATA_DIR}/backups",
  "pollIntervalSeconds": 3,
  "heartbeatIntervalSeconds": 5,
  "downloadTimeoutSeconds": 600,
  "keepBackups": 3,
  "install": {
    "backendJar": "${DATA_ROOT}/app/aid-admin.jar",
    "adminDist": "${DATA_ROOT}/app/admin-dist",
    "webDist": "${DATA_ROOT}/app/web-dist",
    "serviceManager": "${serviceManager}",
    "backendService": "${backendService}",
    "restartServices": ${restartServices},
    "healthCheckUrl": "${healthUrl}",
    "healthCheckTimeoutSeconds": 180
  },
  "database": {
    "enabled": true,
    "host": "${dbHost}",
    "port": ${dbPort},
    "name": "${dbName}",
    "user": "${dbUser}",
    "password": "${dbPwd}",
    "execContainer": "${execContainer}"
  }
}
EOF
  chmod 600 "${UPDATER_CONFIG_FILE}"
}

# 安装/修复升级器（幂等；两种部署方式通用）
setup_updater() { # setup_updater <docker|manual>
  local mode="$1"
  if [[ ! -f "${DATA_ROOT}/app/updater/aid-updater" ]]; then
    warn "发布包未携带升级器二进制，跳过升级器安装（页面一键升级不可用，可手动升级）"
    return 0
  fi
  write_updater_config "${mode}"
  if [[ "${mode}" == "docker" ]]; then
    # 容器模式：配置就位后（重新）拉起升级器容器即可
    compose_cmd up -d aid-updater >/dev/null 2>&1 || true
    compose_cmd restart aid-updater >/dev/null 2>&1 || true
  else
    install -m 0755 "${DATA_ROOT}/app/updater/aid-updater" /usr/local/bin/aid-updater
    cat > /etc/systemd/system/aid-updater.service <<'EOF'
[Unit]
Description=AID Updater - AID platform upgrade agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/aid-updater -config /etc/aid-updater/config.json
# 自升级完成后进程主动退出，由 systemd 拉起新版本
Restart=always
RestartSec=3
# 需要 root：停/起后端服务、替换数据目录下产物
User=root
NoNewPrivileges=false

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
    systemctl enable aid-updater >/dev/null 2>&1 || true
    systemctl restart aid-updater
  fi
  ok "升级器已安装并启动（后台「项目升级配置」页可看到运行状态）"
}

# 从包文件名提取版本号（aid-v1.2.0.tar.gz -> 1.2.0），不匹配命名规范时返回"未知"
version_from_package() {
  local name; name="$(basename "$1")"
  if [[ "${name}" =~ ^aid-v(.+)\.tar\.gz$ ]]; then
    echo "${BASH_REMATCH[1]}"
  else
    echo "未知"
  fi
}

# 确保 MySQL 就绪（docker 模式：容器不在运行则拉起并等待健康，最长 90s）
ensure_mysql_ready() {
  local mode; mode="$(detect_mode)"
  if [[ "${mode}" != "docker" ]]; then return 0; fi
  compose_cmd up -d mysql >/dev/null 2>&1 || true
  local deadline=$(( $(date +%s) + 90 ))
  until [[ "$(docker inspect -f '{{.State.Health.Status}}' aid-mysql 2>/dev/null)" == "healthy" ]]; do
    [[ $(date +%s) -ge ${deadline} ]] && { err "MySQL 容器未就绪"; return 1; }
    sleep 3
  done
  return 0
}

# 数据库全量备份到指定文件（两种部署模式通用）
# docker 模式经容器内客户端执行，宿主机无需安装 mysql；密码经 MYSQL_PWD 传递，
# 不拼进命令行（避免特殊字符断参与 ps 泄露）
backup_database() { # backup_database <输出文件.sql.gz>
  local outFile="$1" mode
  mode="$(detect_mode)"
  if ! command -v gzip >/dev/null 2>&1; then err "缺少 gzip"; return 1; fi
  if [[ "${mode}" == "docker" ]]; then
    docker exec -e MYSQL_PWD="$(env_get MYSQL_ROOT_PASSWORD '')" aid-mysql \
      mysqldump -uroot --single-transaction --routines --triggers "$(env_get DB_NAME aid)" \
      | gzip > "${outFile}" || return 1
  else
    command -v mysqldump >/dev/null 2>&1 || { err "缺少 mysqldump（数据库备份需要）"; return 1; }
    MYSQL_PWD="$(conf_get DB_PASSWORD)" mysqldump --host "$(conf_get DB_HOST 127.0.0.1)" --port "$(conf_get DB_PORT 3306)" \
      --user "$(conf_get DB_USERNAME root)" --single-transaction --routines --triggers "$(conf_get DB_NAME aid)" \
      | gzip > "${outFile}" || return 1
  fi
  [[ -s "${outFile}" ]] || { err "数据库备份文件为空"; return 1; }
  return 0
}

# 数据库全量恢复（覆盖导入，调用方必须先确认）
restore_database() { # restore_database <备份文件.sql.gz>
  local dumpFile="$1" mode
  mode="$(detect_mode)"
  if [[ "${mode}" == "docker" ]]; then
    gunzip < "${dumpFile}" | docker exec -i -e MYSQL_PWD="$(env_get MYSQL_ROOT_PASSWORD '')" aid-mysql \
      mysql -uroot --default-character-set=utf8mb4 "$(env_get DB_NAME aid)" || return 1
  else
    gunzip < "${dumpFile}" | MYSQL_PWD="$(conf_get DB_PASSWORD)" mysql --host "$(conf_get DB_HOST 127.0.0.1)" --port "$(conf_get DB_PORT 3306)" \
      --user "$(conf_get DB_USERNAME root)" --default-character-set=utf8mb4 "$(conf_get DB_NAME aid)" || return 1
  fi
  return 0
}

# 执行单个 SQL 文件（两种部署模式通用；docker 模式经容器内客户端执行）
run_sql_file() { # run_sql_file <sql文件>
  local sqlFile="$1" mode
  mode="$(detect_mode)"
  if [[ "${mode}" == "docker" ]]; then
    docker exec -i -e MYSQL_PWD="$(env_get MYSQL_ROOT_PASSWORD '')" aid-mysql \
      mysql -uroot --default-character-set=utf8mb4 "$(env_get DB_NAME aid)" < "${sqlFile}" || return 1
  else
    MYSQL_PWD="$(conf_get DB_PASSWORD)" mysql --host "$(conf_get DB_HOST 127.0.0.1)" --port "$(conf_get DB_PORT 3306)" \
      --user "$(conf_get DB_USERNAME root)" --default-character-set=utf8mb4 "$(conf_get DB_NAME aid)" < "${sqlFile}" || return 1
  fi
  return 0
}

# ----------------------------------------------------------------------------
# 健康等待
# ----------------------------------------------------------------------------
wait_backend_healthy() {
  local port deadline
  port="$(setting_get BACKEND_PORT 8080)"
  log "等待后端就绪（最长 ${HEALTH_WAIT_SECONDS}s，首次启动含数据库初始化）..."
  deadline=$(( $(date +%s) + HEALTH_WAIT_SECONDS ))
  until curl -sf -o /dev/null "http://127.0.0.1:${port}" 2>/dev/null; do
    if [[ $(date +%s) -ge ${deadline} ]]; then
      echo ""
      err "后端在 ${HEALTH_WAIT_SECONDS}s 内未就绪，诊断信息："
      if [[ "$(detect_mode)" == "docker" ]]; then
        compose_cmd ps || true
        echo "排查: docker logs --tail 100 aid-server / docker logs aid-mysql"
      else
        systemctl --no-pager --lines 0 status aid || true
        echo "排查: journalctl -u aid --no-pager -n 100"
      fi
      return 1
    fi
    sleep 5
  done
  ok "后端已就绪"
}

print_access_info() {
  local mode configFile
  mode="$(detect_mode)"
  if [[ "${mode}" == "docker" ]]; then configFile="${ENV_FILE}"; else configFile="${CONF}"; fi
  echo ""
  echo -e "${C_GREEN}=================== 操作完成 ===================${C_RESET}"
  echo "访问地址:"
  echo "  管理端: http://服务器IP:$(setting_get ADMIN_PORT 8090)/   （默认账号 admin / admin123，登录后立即改密）"
  echo "  用户端: http://服务器IP:$(setting_get HTTP_PORT 80)/"
  echo "数据目录: ${DATA_ROOT}（程序/上传/日志/数据/备份全部在此）"
  echo "配置文件: ${configFile}（菜单「修改配置」可调整）"
  [[ -f "${MANAGED_SCRIPT}" ]] && echo "管理命令: sudo aid 或 sudo bash ${MANAGED_SCRIPT}"
}

# 首次部署前的已有部署检查：重复执行等于"用新包重装程序层"（数据不受影响），
# 跨部署方式混装会端口冲突，给出明确提示并要求确认
confirm_reinstall() { # confirm_reinstall <目标模式 docker|manual>
  local targetMode="$1" existingMode
  existingMode="$(detect_mode)"
  [[ "${existingMode}" == "none" ]] && return 0
  echo ""
  if [[ "${existingMode}" == "${targetMode}" ]]; then
    warn "检测到本机已完成过部署（方式: ${existingMode}）"
    warn "继续执行将用新发布包覆盖程序产物并重启服务；数据库数据与上传文件不受影响，已保存的配置作为默认值沿用"
  else
    err "检测到本机已有【${existingMode}】方式的部署，与本次选择的【${targetMode}】不同！"
    warn "两种部署方式并存会产生端口冲突（80/8080/3306），请先停掉原部署（菜单 6）再切换方式"
  fi
  local goOn
  goOn="$(ask '确认继续？(yes/no)' 'no')"
  [[ "${goOn}" == "yes" ]] || { log "已取消"; return 1; }
  return 0
}

# ----------------------------------------------------------------------------
# 首次部署：Docker
# ----------------------------------------------------------------------------
do_install_docker() {
  require_root
  local existingMode package targetVersion targetChannel
  existingMode="$(detect_mode)"
  confirm_reinstall docker || return 0
  if ! command -v docker >/dev/null 2>&1; then
    risk "未检测到 Docker Engine；AID 不会自动安装 Docker，也不会修改服务器软件源"
    die "请管理员安装 Docker Engine 24+ 与 Compose v2 插件，确认 docker version 正常后重新运行"
  fi
  docker info >/dev/null 2>&1 \
    || die "Docker 已安装但守护进程未运行，请先启动 Docker 服务后重试"
  docker compose version >/dev/null 2>&1 \
    || die "未检测到 docker compose v2 插件，请安装 Compose 插件后重试（脚本不会自动安装）"
  if [[ "$(uname -m)" == "aarch64" ]]; then
    die "Docker 一键部署固定使用 MySQL 5.7，官方镜像不支持 ARM64；请改用 x86_64 服务器"
  fi
  prepare_install_package "${1:-}"
  package="${RESOLVED_PACKAGE_PATH}"
  bootstrap_installer_if_needed "${package}" install-docker

  # Docker 首次部署自动从模板生成配置；后续仍以 .env 为唯一配置真源。
  ensure_env_file
  [[ "${existingMode}" == "none" ]] && confirm_first_install docker
  conf_set DEPLOY_MODE "docker"
  conf_set DATA_ROOT "${DATA_ROOT}"

  # 硬件校验基线按 .env 实际配置评估：profiles 含 mq 才计入内置 MQ 内存
  local mqPlan="no"
  [[ ",$(env_get COMPOSE_PROFILES redis)," == *",mq,"* ]] && mqPlan="yes"
  check_hardware docker "${mqPlan}"

  place_artifacts "${package}"
  # 升级器配置先于容器编排就位，aid-updater 容器首次拉起即可正常运行
  if [[ -f "${DATA_ROOT}/app/updater/aid-updater" ]]; then
    write_updater_config docker
  fi
  # RocketMQ 数据目录属主（镜像内 rocketmq 用户 uid=3000）；profiles 含 mq 即启用了内置 MQ
  if [[ ",$(env_get COMPOSE_PROFILES redis)," == *",mq,"* ]]; then
    mkdir -p "${DATA_ROOT}/rocketmq/broker-data" "${DATA_ROOT}/rocketmq/broker-logs" "${DATA_ROOT}/rocketmq/namesrv-logs"
    chown -R 3000:3000 "${DATA_ROOT}/rocketmq" 2>/dev/null || true
  fi

  log "启动容器编排..."
  compose_cmd up -d || die "容器编排启动失败"
  wait_backend_healthy || die "部署未完成，按上方提示排查后可重新执行本菜单项（数据库初始化失败需先清空再重装: cd ${COMPOSE_DIR} && docker compose down && rm -rf ${DATA_ROOT}/mysql-data）"
  targetVersion="${RESOLVED_VERSION:-$(version_from_package "${package}")}"
  targetChannel="${REQUESTED_RELEASE_CHANNEL:-auto}"
  conf_set CURRENT_VERSION "${targetVersion}"
  conf_set RELEASE_CHANNEL "${targetChannel}"
  install_management_command
  print_access_info
  if [[ -f "${DATA_ROOT}/app/updater/aid-updater" ]]; then
    echo "在线升级器已随部署自动运行（后台「项目升级配置」页可查看状态并一键升级）"
  fi
  echo "配置调整: 编辑 ${ENV_FILE} 后执行菜单「重启服务」生效"
  echo "后续: 登录改密 → 配置AI厂商密钥 → 配置OSS"
}

# ----------------------------------------------------------------------------
# 首次部署：手动（systemd）
# ----------------------------------------------------------------------------
write_systemd_units() {
  local javaBin nodeBin
  javaBin="$(command -v java)"
  nodeBin="$(command -v node)"
  cat > /etc/systemd/system/aid.service <<EOF
[Unit]
Description=AID Server
After=network-online.target

[Service]
Type=simple
WorkingDirectory=${DATA_ROOT}/app
Environment=DB_HOST=$(conf_get DB_HOST 127.0.0.1)
Environment=DB_PORT=$(conf_get DB_PORT 3306)
Environment=DB_NAME=$(conf_get DB_NAME aid)
Environment=DB_USERNAME=$(conf_get DB_USERNAME root)
Environment=DB_PASSWORD=$(conf_get DB_PASSWORD '')
Environment=REDIS_HOST=$(conf_get REDIS_HOST 127.0.0.1)
Environment=REDIS_PORT=$(conf_get REDIS_PORT 6379)
Environment=REDIS_PASSWORD=$(conf_get REDIS_PASSWORD '')
Environment=TOKEN_SECRET=$(conf_get TOKEN_SECRET '')
Environment=AID_PROFILE=${DATA_ROOT}/uploadPath
Environment=LOG_PATH=${DATA_ROOT}/logs
Environment=ROCKETMQ_ENABLED=$(conf_get ROCKETMQ_ENABLED false)
Environment=ROCKETMQ_NAMESERVER=$(conf_get ROCKETMQ_NAMESERVER 127.0.0.1:9876)
Environment=SERVER_PORT=$(conf_get BACKEND_PORT 8080)
ExecStart=${javaBin} $(conf_get JAVA_OPTS '-Xms1g -Xmx2g') -jar ${DATA_ROOT}/app/aid-admin.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
  chmod 600 /etc/systemd/system/aid.service

  cat > /etc/systemd/system/aid-web.service <<EOF
[Unit]
Description=AID Web (Nuxt SSR)
After=network-online.target aid.service

[Service]
Type=simple
WorkingDirectory=${DATA_ROOT}/app/web-dist
Environment=NITRO_PORT=3000
Environment=NITRO_HOST=127.0.0.1
Environment=NUXT_PROXY_TARGET=http://127.0.0.1:$(conf_get BACKEND_PORT 8080)
ExecStart=${nodeBin} server/index.mjs
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
}

write_nginx_site() {
  local httpPort adminPort backendPort content
  httpPort="$(conf_get HTTP_PORT 80)"
  adminPort="$(conf_get ADMIN_PORT 8090)"
  backendPort="$(conf_get BACKEND_PORT 8080)"
  content="# AID 站点：${httpPort}=C端用户端，${adminPort}=后台管理端（根路径托管）
# 仅在请求确实携带 Upgrade 头时才发送 Connection: upgrade，普通请求保持 keep-alive
map \$http_upgrade \$connection_upgrade {
    default upgrade;
    ''      '';
}

server {
    listen ${httpPort};
    server_name _;
    client_max_body_size 1024m;
    location = /healthz { access_log off; return 200 \"ok\"; }
    location /aid/ {
        proxy_pass http://127.0.0.1:${backendPort}/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_read_timeout 300s;
        proxy_buffering off;
    }
    location /profile/ {
        proxy_pass http://127.0.0.1:${backendPort}/profile/;
    }
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$connection_upgrade;
    }
}

server {
    listen ${adminPort};
    server_name _;
    client_max_body_size 1024m;
    root ${DATA_ROOT}/app/admin-dist;
    index index.html;
    location / {
        try_files \$uri \$uri/ /index.html;
    }
    location /prod-api/ {
        proxy_pass http://127.0.0.1:${backendPort}/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_read_timeout 300s;
        proxy_buffering off;
    }
    location /profile/ {
        proxy_pass http://127.0.0.1:${backendPort}/profile/;
    }
}"
  if command -v nginx >/dev/null 2>&1 && [[ -d /etc/nginx/conf.d ]]; then
    [[ -f /etc/nginx/conf.d/aid.conf ]] && cp /etc/nginx/conf.d/aid.conf "/etc/nginx/conf.d/aid.conf.bak.$(date +%s)"
    echo "${content}" > /etc/nginx/conf.d/aid.conf
    nginx -t >/dev/null 2>&1 && systemctl reload nginx && ok "Nginx 站点已生效" \
      || warn "nginx 配置校验失败，请人工检查 /etc/nginx/conf.d/aid.conf"
  else
    echo "${content}" > "${DATA_ROOT}/aid-nginx.conf"
    warn "未检测到 nginx，站点配置已生成到 ${DATA_ROOT}/aid-nginx.conf 供手工放置"
  fi
}

do_install_manual() {
  require_root
  local existingMode package targetVersion targetChannel
  existingMode="$(detect_mode)"
  confirm_reinstall manual || return 0
  command -v systemctl >/dev/null 2>&1 || die "未检测到 systemd"
  command -v java >/dev/null 2>&1 || die "未检测到 java，请安装 JDK 17+"
  local javaMajor nodeMajor
  javaMajor="$(java -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/')"
  [[ "${javaMajor}" -ge 17 ]] || die "JDK 版本过低（当前 ${javaMajor}，需要 17+）"
  command -v node >/dev/null 2>&1 || die "未检测到 node，请安装 Node.js 18+"
  nodeMajor="$(node -v | sed -E 's/v([0-9]+).*/\1/')"
  [[ "${nodeMajor}" -ge 18 ]] || die "Node.js 版本过低（当前 ${nodeMajor}，需要 18+）"
  command -v mysql >/dev/null 2>&1 || die "未检测到 mysql 客户端（数据库初始化需要）"
  prepare_install_package "${1:-}"
  package="${RESOLVED_PACKAGE_PATH}"
  bootstrap_installer_if_needed "${package}" install-manual

  # 手动部署自动生成配置骨架，但外部数据库密码仍必须由用户明确提供。
  ensure_conf_file
  [[ "${existingMode}" == "none" ]] && confirm_first_install manual
  conf_set DEPLOY_MODE "manual"
  conf_set DATA_ROOT "${DATA_ROOT}"

  # 硬件校验基线按配置实际评估：启用 MQ（手动部署常与业务同机）按含 MQ 内存计
  local mqPlan="no"
  [[ "$(conf_get ROCKETMQ_ENABLED false)" == "true" ]] && mqPlan="yes"
  check_hardware manual "${mqPlan}"

  # 数据库连通性与初始化
  local dbHost dbPort dbUser dbPwd dbName tableCount
  dbHost="$(conf_get DB_HOST)"; dbPort="$(conf_get DB_PORT)"; dbUser="$(conf_get DB_USERNAME)"
  dbPwd="$(conf_get DB_PASSWORD)"; dbName="$(conf_get DB_NAME)"
  MYSQL_PWD="${dbPwd}" mysql --host "${dbHost}" --port "${dbPort}" --user "${dbUser}" -e "SELECT 1" >/dev/null 2>&1 \
    || die "数据库连接失败，请检查配置（菜单「修改配置」可修改后重试）"
  tableCount="$(MYSQL_PWD="${dbPwd}" mysql --host "${dbHost}" --port "${dbPort}" --user "${dbUser}" \
    -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${dbName}'" 2>/dev/null || echo 0)"
  if [[ "${tableCount}" -gt 0 ]]; then
    ok "数据库 ${dbName} 已有 ${tableCount} 张表，跳过初始化"
  else
    local initSql="${REPO_DIR}/sql/aid-init.sql"
    [[ -f "${initSql}" ]] || die "未找到初始化脚本 ${initSql}"
    log "创建数据库并导入基线（约 1 分钟）..."
    MYSQL_PWD="${dbPwd}" mysql --host "${dbHost}" --port "${dbPort}" --user "${dbUser}" \
      -e "CREATE DATABASE IF NOT EXISTS \`${dbName}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
    MYSQL_PWD="${dbPwd}" mysql --host "${dbHost}" --port "${dbPort}" --user "${dbUser}" \
      --default-character-set=utf8mb4 "${dbName}" < "${initSql}" || die "基线导入失败"
    ok "数据库初始化完成"
  fi

  place_artifacts "${package}"
  write_systemd_units
  systemctl enable aid >/dev/null 2>&1; systemctl restart aid
  if [[ -f "${DATA_ROOT}/app/web-dist/server/index.mjs" ]]; then
    systemctl enable aid-web >/dev/null 2>&1; systemctl restart aid-web
  else
    warn "web-dist 无 SSR 产物，aid-web 服务暂不启动"
  fi
  write_nginx_site
  # 在线升级器随部署自动安装（systemd 服务），页面即可一键升级
  setup_updater manual
  wait_backend_healthy || die "部署未完成，按上方提示排查后重试"
  targetVersion="${RESOLVED_VERSION:-$(version_from_package "${package}")}"
  targetChannel="${REQUESTED_RELEASE_CHANNEL:-auto}"
  conf_set CURRENT_VERSION "${targetVersion}"
  conf_set RELEASE_CHANNEL "${targetChannel}"
  install_management_command
  print_access_info
}

# ----------------------------------------------------------------------------
# 更新到指定版本（两种模式通用）
# ----------------------------------------------------------------------------
do_update() {
  require_root
  local mode package supplied current target comparison go backupDir dist old f targetChannel
  mode="$(detect_mode)"
  [[ "${mode}" != "none" ]] || die "尚未部署，请先执行首次部署"
  supplied="${1:-}"
  current="$(current_version)"

  if [[ -n "${supplied}" ]]; then
    prepare_install_package "${supplied}"
    package="${RESOLVED_PACKAGE_PATH}"
    target="${RESOLVED_VERSION}"
  else
    resolve_official_release
    target="${RESOLVED_VERSION}"
    if [[ "${current}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
      comparison="$(version_compare "${target}" "${current}")"
      if [[ "${comparison}" == "0" ]]; then
        ok "当前已是 ${RESOLVED_CHANNEL} 渠道最新版: ${current}"
        return 0
      fi
      if [[ "${comparison}" == "-1" ]]; then
        risk "远端版本 ${target} 低于当前版本 ${current}，自动更新绝不会执行降级"
        warn "如确需回退，请使用菜单「回滚到升级前备份」，不要用旧发布包覆盖"
        return 0
      fi
    else
      warn "无法可靠识别当前版本（${current}），将按升级流程继续，但会先完整备份"
    fi
  fi

  section "版本升级确认"
  echo -e "  当前版本 : ${C_YELLOW}${current}${C_RESET}"
  echo -e "  目标版本 : ${C_GREEN}${target}${C_RESET} (${RESOLVED_CHANNEL:-本地包})"
  risk "升级会短暂停止服务，并可能执行包内增量 SQL；请勿在生成任务运行期间操作"
  warn "脚本将在替换任何程序或执行 SQL 前备份三端产物与数据库；备份失败会立即中止"
  warn "后台「项目升级配置」仍是首选入口，具备签名校验、SQL 历史记录和失败自动回滚"
  if [[ "${AID_ASSUME_YES:-0}" == "1" ]]; then
    risk "AID_ASSUME_YES=1：已跳过升级人工确认"
    go="yes"
  else
    go="$(ask "确认从 ${current} 更新到 ${target}？(yes/no)" 'no')"
  fi
  [[ "${go}" == "yes" ]] || { log "已取消"; return; }

  if [[ -z "${supplied}" ]]; then
    ensure_official_package
    package="${RESOLVED_PACKAGE_PATH}"
  fi

  # docker 模式先确保数据库容器可用（备份与增量 SQL 都依赖它）
  ensure_mysql_ready || die "数据库未就绪，升级已中止（未做任何变更）"

  # 升级前自动完整备份（产物 + 数据库 + 版本标记），供菜单「回滚」还原
  backupDir="${DATA_ROOT}/backups/upgrade-$(date +%Y%m%d%H%M%S)-v${current}"
  mkdir -p "${backupDir}"
  [[ -f "${DATA_ROOT}/app/aid-admin.jar" ]] && cp "${DATA_ROOT}/app/aid-admin.jar" "${backupDir}/"
  [[ -f "${DATA_ROOT}/app/build-info.json" ]] && cp "${DATA_ROOT}/app/build-info.json" "${backupDir}/"
  for dist in admin-dist web-dist; do
    [[ -d "${DATA_ROOT}/app/${dist}" ]] && cp -r "${DATA_ROOT}/app/${dist}" "${backupDir}/"
  done
  log "备份数据库（升级前快照）..."
  backup_database "${backupDir}/db.sql.gz" || die "数据库备份失败，升级已中止（未做任何变更）"
  echo "${current}" > "${backupDir}/version.txt"
  ok "升级前完整备份已生成: ${backupDir}"
  # 升级备份保留最近 3 份，从旧到新清理
  ls -1d "${DATA_ROOT}/backups"/upgrade-* 2>/dev/null | sort | head -n -3 | while read -r old; do
    rm -rf "${old}" && log "已清理过期升级备份: ${old}"
  done

  place_artifacts "${package}"

  # 执行包内增量 SQL（脚本通道直接顺序执行；脚本均要求幂等，重复执行无副作用；
  # docker 模式经容器内客户端执行，手动模式需要宿主机 mysql 客户端）
  if [[ -d "${DATA_ROOT}/packages/pending-sql" ]] && ls "${DATA_ROOT}/packages/pending-sql"/*.sql >/dev/null 2>&1; then
    if [[ "${mode}" == "docker" ]] || command -v mysql >/dev/null 2>&1; then
      log "执行包内增量 SQL..."
      for f in "${DATA_ROOT}/packages/pending-sql"/*.sql; do
        log "  执行 $(basename "${f}")"
        run_sql_file "${f}" || die "SQL 执行失败: $(basename "${f}")（产物已更新，请处理 SQL 后重启）"
      done
      ok "增量 SQL 执行完成"
    else
      warn "无 mysql 客户端，包内增量 SQL 未执行，请人工处理: ${DATA_ROOT}/packages/pending-sql"
    fi
  fi

  do_restart
  wait_backend_healthy || die "新版本未就绪，可执行菜单「回滚到升级前备份」还原: ${backupDir}"
  # 包内携带新版升级器二进制时重启升级器使其生效（未安装过则跳过）
  if [[ -f "${DATA_ROOT}/app/updater/aid-updater" && -f "${UPDATER_CONFIG_FILE}" ]]; then
    if [[ "${mode}" == "docker" ]]; then
      compose_cmd restart aid-updater >/dev/null 2>&1 || true
    else
      install -m 0755 "${DATA_ROOT}/app/updater/aid-updater" /usr/local/bin/aid-updater 2>/dev/null || true
      systemctl restart aid-updater 2>/dev/null || true
    fi
  fi
  target="$(current_version)"
  [[ "${target}" == "未知" ]] && target="${RESOLVED_VERSION:-$(version_from_package "${package}")}"
  targetChannel="${REQUESTED_RELEASE_CHANNEL:-$(conf_get RELEASE_CHANNEL auto)}"
  conf_set CURRENT_VERSION "${target}"
  conf_set RELEASE_CHANNEL "${targetChannel}"
  refresh_managed_installer "${package}" || true
  install_management_command
  ok "已更新到 ${target}"
  print_access_info
}

# ----------------------------------------------------------------------------
# 回滚：从最近的升级前备份中选择还原（最多展示 3 份）
# ----------------------------------------------------------------------------
do_rollback() {
  require_root
  local mode; mode="$(detect_mode)"
  [[ "${mode}" != "none" ]] || die "尚未部署"

  # 收集最近 3 份升级备份（目录名含时间戳，字典序即时间序，取最新 3 份倒序展示）
  local backups=()
  while IFS= read -r line; do backups+=("${line}"); done < <(ls -1d "${DATA_ROOT}/backups"/upgrade-* 2>/dev/null | sort -r | head -n 3)
  [[ ${#backups[@]} -gt 0 ]] || die "没有可用的升级前备份（只有通过菜单「更新」升级过才会生成）"

  echo ""
  echo "可回滚的升级前备份（最近 ${#backups[@]} 份）："
  local i dir ver stamp
  for i in "${!backups[@]}"; do
    dir="${backups[$i]}"
    ver="$(cat "${dir}/version.txt" 2>/dev/null || echo 未知)"
    stamp="$(basename "${dir}" | sed -E 's/^upgrade-([0-9]{8})([0-9]{6}).*/\1 \2/' | sed -E 's/([0-9]{4})([0-9]{2})([0-9]{2}) ([0-9]{2})([0-9]{2})([0-9]{2})/\1-\2-\3 \4:\5:\6/')"
    echo "  $((i+1))) 版本 v${ver}  备份于 ${stamp}  $(du -sh "${dir}" 2>/dev/null | cut -f1)"
  done
  local choice
  choice="$(ask '选择要回滚到的备份（0=取消）' '0')"
  [[ "${choice}" =~ ^[0-9]+$ ]] || die "无效选择"
  [[ "${choice}" -ge 1 && "${choice}" -le ${#backups[@]} ]] || { log "已取消"; return; }
  local target="${backups[$((choice-1))]}"
  local targetVer; targetVer="$(cat "${target}/version.txt" 2>/dev/null || echo 未知)"

  echo ""
  warn "即将回滚：当前 v$(current_version) → v${targetVer}（备份 $(basename "${target}")）"
  warn "程序产物将被还原；数据库默认【不】还原（避免丢失升级后产生的业务数据）"
  local go
  go="$(ask '确认回滚程序产物？(yes/no)' 'no')"
  [[ "${go}" == "yes" ]] || { log "已取消"; return; }

  # 回滚前对当前状态再做一份保护备份（防止误回滚无法恢复）
  local safeguard="${DATA_ROOT}/backups/pre-rollback-$(date +%Y%m%d%H%M%S)-v$(current_version)"
  local dist old
  mkdir -p "${safeguard}"
  [[ -f "${DATA_ROOT}/app/aid-admin.jar" ]] && cp "${DATA_ROOT}/app/aid-admin.jar" "${safeguard}/"
  [[ -f "${DATA_ROOT}/app/build-info.json" ]] && cp "${DATA_ROOT}/app/build-info.json" "${safeguard}/"
  for dist in admin-dist web-dist; do
    [[ -d "${DATA_ROOT}/app/${dist}" ]] && cp -r "${DATA_ROOT}/app/${dist}" "${safeguard}/"
  done
  echo "$(current_version)" > "${safeguard}/version.txt"
  ok "当前状态保护备份: ${safeguard}"
  # 保护备份同样保留最近 3 份
  ls -1d "${DATA_ROOT}/backups"/pre-rollback-* 2>/dev/null | sort | head -n -3 | while read -r old; do
    rm -rf "${old}" && log "已清理过期保护备份: ${old}"
  done

  # 还原产物
  do_stop || true
  [[ -f "${target}/aid-admin.jar" ]] && install -m 0644 "${target}/aid-admin.jar" "${DATA_ROOT}/app/aid-admin.jar"
  if [[ -f "${target}/build-info.json" ]]; then
    install -m 0644 "${target}/build-info.json" "${DATA_ROOT}/app/build-info.json"
  else
    rm -f "${DATA_ROOT}/app/build-info.json"
  fi
  for dist in admin-dist web-dist; do
    if [[ -d "${target}/${dist}" ]]; then
      rm -rf "${DATA_ROOT}/app/${dist}"
      cp -r "${target}/${dist}" "${DATA_ROOT}/app/${dist}"
    fi
  done
  ok "程序产物已还原到 v${targetVer}"

  # 可选：还原数据库（高危，显式确认）
  if [[ -f "${target}/db.sql.gz" ]]; then
    local restoreDb
    restoreDb="$(ask '是否同时还原数据库？会丢失升级后产生的全部数据！(yes/no)' 'no')"
    if [[ "${restoreDb}" == "yes" ]]; then
      log "还原数据库（升级前快照）..."
      # docker 模式数据库容器需在运行态且健康后才能导入
      ensure_mysql_ready || die "数据库容器未就绪，还原已中止"
      restore_database "${target}/db.sql.gz" || die "数据库还原失败，请人工处理: ${target}/db.sql.gz"
      ok "数据库已还原"
    else
      log "数据库保持现状（新版本增量为幂等加法结构，老版本代码可正常运行）"
    fi
  fi

  do_restart
  wait_backend_healthy || die "回滚后服务未就绪，请查看日志排查；保护备份: ${safeguard}"
  conf_set CURRENT_VERSION "${targetVer}"
  ok "已回滚到 v${targetVer}"
  print_access_info
}

# ----------------------------------------------------------------------------
# 日常操作
# ----------------------------------------------------------------------------
do_restart() {
  require_root
  local mode; mode="$(detect_mode)"
  case "${mode}" in
    docker)
      # .env 由用户维护，up -d 会应用其中的变更（环境/端口/内存等按需重建容器）
      log "重启容器编排（.env 配置变更同时生效）..."
      compose_cmd up -d
      compose_cmd restart aid-server aid-web nginx 2>/dev/null || true
      ;;
    manual)
      write_systemd_units
      systemctl restart aid
      systemctl restart aid-web 2>/dev/null || true
      systemctl reload nginx 2>/dev/null || true
      ;;
    *) die "尚未部署" ;;
  esac
  ok "重启完成"
}

do_stop() {
  require_root
  local mode; mode="$(detect_mode)"
  case "${mode}" in
    docker) compose_cmd stop ;;
    manual) systemctl stop aid aid-web 2>/dev/null || systemctl stop aid ;;
    *) die "尚未部署" ;;
  esac
  ok "已停止"
}

do_status() {
  local mode; mode="$(detect_mode)"
  echo ""
  echo "部署方式: ${mode}    版本: $(current_version)    数据目录: ${DATA_ROOT}"
  case "${mode}" in
    docker) compose_cmd ps ;;
    manual)
      systemctl --no-pager --lines 0 status aid 2>/dev/null | head -n 5 || true
      systemctl --no-pager --lines 0 status aid-web 2>/dev/null | head -n 5 || true
      ;;
    *) warn "尚未部署" ;;
  esac
  echo ""
  df -h "${DATA_ROOT}" 2>/dev/null | tail -n 1 | awk '{print "磁盘: 已用 "$3" / 共 "$2"（"$5"）"}' || true
}

do_logs() {
  local mode choice; mode="$(detect_mode)"
  [[ "${mode}" != "none" ]] || die "尚未部署"
  echo ""
  echo "查看日志（Ctrl+C 退出跟踪）："
  echo "  1) 后端实时日志"
  echo "  2) 后端错误日志文件"
  echo "  3) 用户端（SSR）日志"
  echo "  4) MySQL 日志"
  echo "  5) 升级器日志"
  choice="$(ask '选择' '1')"
  case "${choice}" in
    1)
      if [[ "${mode}" == "docker" ]]; then docker logs -f --tail 200 aid-server
      else journalctl -u aid -f -n 200; fi ;;
    2) tail -n 200 -f "${DATA_ROOT}/logs/sys-error.log" 2>/dev/null || warn "错误日志文件不存在: ${DATA_ROOT}/logs/sys-error.log" ;;
    3)
      if [[ "${mode}" == "docker" ]]; then docker logs -f --tail 200 aid-web
      else journalctl -u aid-web -f -n 200; fi ;;
    4)
      if [[ "${mode}" == "docker" ]]; then docker logs -f --tail 100 aid-mysql
      else warn "手动部署的 MySQL 日志位置取决于你的安装方式"; fi ;;
    5)
      # 先判断服务是否安装，避免 Ctrl+C 退出日志跟踪时误报"未安装"
      if systemctl list-unit-files 2>/dev/null | grep -q '^aid-updater\.service'; then journalctl -u aid-updater -f -n 100
      else warn "升级器未安装（安装: sudo bash ${SCRIPT_DIR}/install-updater.sh）"; fi ;;
    *) warn "无效选择" ;;
  esac
}

do_config() {
  require_root
  local mode configFile
  mode="$(detect_mode)"
  [[ "${mode}" != "none" ]] || die "尚未部署，请先完成首次部署"
  # 两种部署方式统一：配置文件由用户维护，本菜单只做编辑入口与生效引导
  if [[ "${mode}" == "docker" ]]; then
    configFile="${ENV_FILE}"
  else
    configFile="${CONF}"
  fi
  echo ""
  log "当前部署（${mode}）的全部配置集中在: ${configFile}"
  echo "  1) 编辑该文件（每项均有注释说明）"
  echo "  2) 保存后执行本菜单「重启服务」生效"
  echo "  3) 改动了数据库凭证时，执行菜单「安装/修复在线升级器」同步升级器配置"
  local editNow
  editNow="$(ask "现在用 vi 打开编辑？(yes/no)" 'no')"
  if [[ "${editNow}" == "yes" ]]; then
    "${EDITOR:-vi}" "${configFile}" </dev/tty >/dev/tty || true
    local apply
    apply="$(ask '立即重启使配置生效？(yes/no)' 'yes')"
    if [[ "${apply}" == "yes" ]]; then
      # 凭证可能变更，先同步升级器配置再重启
      if [[ -f "${UPDATER_CONFIG_FILE}" && -f "${DATA_ROOT}/app/updater/aid-updater" ]]; then
        write_updater_config "${mode}"
      fi
      do_restart
    fi
  fi
}

# 安装/修复升级器（老环境补装、配置损坏修复、部署方式变更后重写配置）
do_setup_updater() {
  require_root
  local mode; mode="$(detect_mode)"
  [[ "${mode}" != "none" ]] || die "尚未部署，首次部署会自动安装升级器"
  if [[ ! -f "${DATA_ROOT}/app/updater/aid-updater" ]]; then
    die "缺少升级器二进制 ${DATA_ROOT}/app/updater/aid-updater（当前部署的发布包版本过旧，请先菜单「更新」到新版本）"
  fi
  setup_updater "${mode}"
  log "后台「项目升级配置 → 重新检测」即可看到升级器运行状态"
}

do_backup() {
  require_root
  local mode stamp target
  mode="$(detect_mode)"; [[ "${mode}" != "none" ]] || die "尚未部署"
  # docker 模式确保数据库容器就绪（可能刚执行过「停止服务」）
  ensure_mysql_ready || die "数据库未就绪，备份已中止"
  stamp="$(date '+%Y%m%d-%H%M%S')"
  target="${DATA_ROOT}/backups/${stamp}"
  mkdir -p "${target}"
  log "备份数据库..."
  backup_database "${target}/db.sql.gz" || die "数据库备份失败"
  log "备份上传文件..."
  tar -czf "${target}/uploadPath.tar.gz" -C "${DATA_ROOT}" uploadPath 2>/dev/null || true
  cp "${CONF}" "${target}/aid-deploy.conf.bak" 2>/dev/null || true
  chmod 600 "${target}/aid-deploy.conf.bak" 2>/dev/null || true
  # docker 模式的配置真源是 .env，一并备份（含凭证，权限收紧）
  if [[ "${mode}" == "docker" && -f "${ENV_FILE}" ]]; then
    cp "${ENV_FILE}" "${target}/env.bak" 2>/dev/null || true
    chmod 600 "${target}/env.bak" 2>/dev/null || true
  fi
  ok "备份完成: ${target}"
  # 清理 7 天前的手动备份
  find "${DATA_ROOT}/backups" -mindepth 1 -maxdepth 1 -type d -name '20*' -mtime +7 -exec rm -rf {} \; 2>/dev/null || true
}

# ----------------------------------------------------------------------------
# 菜单
# ----------------------------------------------------------------------------
show_menu() {
  local mode; mode="$(detect_mode)"
  echo ""
  echo -e "${C_CYAN}==================== AID 部署管理 ====================${C_RESET}"
  echo -e " 部署方式: ${C_GREEN}${mode}${C_RESET}    当前版本: ${C_GREEN}$(current_version)${C_RESET}    渠道: ${C_GREEN}$(conf_get RELEASE_CHANNEL auto)${C_RESET}"
  echo -e " 数据目录: ${DATA_ROOT}"
  echo "------------------------------------------------------"
  echo "  1) 一键首次部署（Docker，自动下载，推荐）"
  echo "  2) 首次部署（手动 systemd，自动下载）"
  echo "  3) 自动检查并升级到当前渠道最新版（升级前完整备份）"
  echo "  4) 回滚到升级前备份（最近 3 份可选）"
  echo "  5) 重启服务（配置变更后生效）"
  echo "  6) 停止服务"
  echo "  7) 查看状态"
  echo "  8) 查看日志"
  echo "  9) 修改配置（编辑配置文件后一键生效）"
  echo " 10) 立即备份（数据库+上传文件）"
  echo " 11) 安装/修复在线升级器"
  echo "  0) 退出"
  echo "------------------------------------------------------"
}

main() {
  # 用户以后仍执行最初下载的单文件时，自动切换到发布包持久化安装的最新版管理脚本。
  handoff_to_managed_installer "$@"
  case "${1:-}" in
    install|auto)
      if [[ "$(detect_mode)" == "none" ]]; then
        do_install_docker "${2:-}"
      else
        do_update "${2:-}"
      fi
      exit $?
      ;;
    install-docker) do_install_docker "${2:-}"; exit $? ;;
    install-manual) do_install_manual "${2:-}"; exit $? ;;
    update)         do_update "${2:-}"; exit $? ;;
    rollback)       do_rollback; exit $? ;;
    restart)        do_restart; exit $? ;;
    stop)           do_stop; exit $? ;;
    status)         do_status; exit $? ;;
    logs)           do_logs; exit $? ;;
    config)         do_config; exit $? ;;
    backup)         do_backup; exit $? ;;
    setup-updater)  do_setup_updater; exit $? ;;
    '') ;;
    *) die "未知子命令: $1（可用: install/auto/install-docker/install-manual/update/rollback/restart/stop/status/logs/config/backup/setup-updater）" ;;
  esac

  # 交互菜单模式：Ctrl+C 只中断当前操作（如日志跟踪）回到菜单，不退出脚本
  trap ':' INT
  while :; do
    show_menu
    local choice
    read -r -p "请选择: " choice </dev/tty
    case "${choice}" in
      1) do_install_docker || true ;;
      2) do_install_manual || true ;;
      3) do_update || true ;;
      4) do_rollback || true ;;
      5) do_restart || true ;;
      6) do_stop || true ;;
      7) do_status || true ;;
      8) do_logs || true ;;
      9) do_config || true ;;
      10) do_backup || true ;;
      11) do_setup_updater || true ;;
      0) exit 0 ;;
      *) warn "无效选择" ;;
    esac
  done
}

if [[ "${AID_SH_LIBRARY_MODE:-0}" != "1" ]]; then
  main "$@"
fi
