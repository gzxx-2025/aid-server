#!/bin/bash
# ============================================================================
# AID 统一部署管理脚本（菜单式，Docker 与手动部署通用）
#
# 用法：
#   sudo bash aid.sh              # 交互菜单（首次部署按版本标签拉取三端源码并构建）
#   sudo bash aid.sh <子命令>     # 直通执行：install/auto/install-docker/install-manual/update/rollback/progress/
#                                 # restart/stop/status/default/mysql/logs/config/backup/setup-updater/uninstall/uninstall-all
#
# 设计：
#   - 全部数据统一放在 DATA_ROOT（默认 /data/aid）：程序、上传文件、日志、
#     中间件数据、备份、源码构建缓存
#   - 首次部署自动从模板创建正式配置；后续配置真源 = 用户维护的正式配置文件：
#       Docker 部署 → DATA_ROOT/config/docker.env（模板 deploy/docker/.env.example）
#       手动部署   → DATA_ROOT/aid-deploy.conf（模板 deploy/aid-deploy.conf.example）
#     密码/密钥留空自动生成强随机值写回；改配置 = 编辑文件 + 菜单「重启服务」
#   - 自动识别部署方式（docker / manual），每个环节自动判断当前状态
# ============================================================================

set -uo pipefail

SCRIPT_PATH="$(readlink -f -- "${BASH_SOURCE[0]}" 2>/dev/null || printf '%s' "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(cd "$(dirname "${SCRIPT_PATH}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
# AID_DATA_ROOT 是唯一允许显式覆盖数据根目录的环境变量。未提供时，优先从
# 已受管安装痕迹恢复真实目录，避免自定义目录的 ``sudo aid uninstall`` 错删默认目录。
AID_DATA_ROOT_EXPLICIT=0
if [[ -n "${AID_DATA_ROOT+x}" && -n "${AID_DATA_ROOT:-}" ]]; then
  AID_DATA_ROOT_EXPLICIT=1
fi
DATA_ROOT="${AID_DATA_ROOT:-/data/aid}"
# These paths are overridable only for controlled tests and non-standard system
# layouts. Production defaults remain the normal Linux locations.
AID_SYSTEMD_UNIT_DIR="${AID_SYSTEMD_UNIT_DIR:-/etc/systemd/system}"
AID_LOCAL_BIN_DIR="${AID_LOCAL_BIN_DIR:-/usr/local/bin}"
AID_BOOTSTRAP_PATHS="${AID_BOOTSTRAP_PATHS:-/root/aid-install.sh:/root/aid.sh}"
AID_BOOTSTRAP_MARKER="AID 统一部署管理脚本"
AID_MANAGEMENT_COMMAND_WAS_MANAGED=0
AID_UPDATER_COMMAND_WAS_MANAGED=0
AID_OWNED_DOCKER_IMAGE_IDS=()
AID_OWNED_DOCKER_IMAGE_COUNT=0
AID_OWNED_SYSTEMD_SERVICES=()
AID_OWNED_SYSTEMD_SERVICE_COUNT=0
AID_OWNED_MANUAL_ACCOUNTS=()
AID_OWNED_MANUAL_ACCOUNT_COUNT=0
AID_OWNED_MANUAL_GROUPS=()
AID_OWNED_MANUAL_GROUP_COUNT=0

is_safe_aid_data_root_candidate() { # is_safe_aid_data_root_candidate <absolute path>
  local candidate="$1" resolved="" probe parent component
  local -a candidateComponents=()
  [[ "${candidate}" == /* && "${candidate}" != "/" ]] || return 1
  [[ "${candidate}" != *//* ]] || return 1
  IFS='/' read -r -a candidateComponents <<< "${candidate#/}"
  for component in "${candidateComponents[@]+"${candidateComponents[@]}"}"; do
    [[ "${component}" != "." && "${component}" != ".." ]] || return 1
  done
  case "${candidate}" in
    /bin|/boot|/data|/dev|/etc|/home|/opt|/root|/run|/srv|/tmp|/usr|/var) return 1 ;;
  esac
  [[ "${candidate#/}" == */* ]] || return 1
  # 目标尚不存在时 readlink -f 无法证明父链安全，因此逐级 Lstat 语义检查；
  # `/tmp/link/new/aid` 即使 new/aid 尚未创建，也会在 link 层被拒绝。
  probe="${candidate}"
  while :; do
    [[ ! -L "${probe}" ]] || return 1
    parent="${probe%/*}"
    [[ -n "${parent}" ]] || parent="/"
    [[ "${parent}" != "${probe}" ]] || break
    probe="${parent}"
  done
  if [[ -e "${candidate}" ]]; then
    resolved="$(readlink -f -- "${candidate}" 2>/dev/null || true)"
    [[ -n "${resolved}" && "${resolved}" == "${candidate}" ]] || return 1
  fi
  return 0
}

aid_script_data_root_candidate() { # aid_script_data_root_candidate <script path>
  local path="$1" root=""
  [[ "${path}" == */installer/deploy/aid.sh ]] || return 1
  root="${path%/installer/deploy/aid.sh}"
  is_safe_aid_data_root_candidate "${root}" || return 1
  printf '%s\n' "${root}"
}

AID_DATA_ROOT_CANDIDATES=()
AID_DATA_ROOT_CANDIDATE_COUNT=0
add_aid_data_root_candidate() { # add_aid_data_root_candidate <candidate>
  local candidate="$1" existing
  is_safe_aid_data_root_candidate "${candidate}" || return 0
  for existing in "${AID_DATA_ROOT_CANDIDATES[@]+"${AID_DATA_ROOT_CANDIDATES[@]}"}"; do
    [[ "${existing}" == "${candidate}" ]] && return 0
  done
  AID_DATA_ROOT_CANDIDATES+=("${candidate}")
  AID_DATA_ROOT_CANDIDATE_COUNT=$((AID_DATA_ROOT_CANDIDATE_COUNT + 1))
}

aid_root_candidate_has_strong_evidence() { # aid_root_candidate_has_strong_evidence <candidate>
  local candidate="$1" marker state descriptor manualConfig dockerConfig managedScript
  marker="${candidate}/config/.aid-managed"
  state="${candidate}/config/install-state.conf"
  descriptor="${candidate}/config/deployment.json"
  manualConfig="${candidate}/aid-deploy.conf"
  dockerConfig="${candidate}/config/docker.env"
  managedScript="${candidate}/installer/deploy/aid.sh"
  [[ -f "${marker}" && ! -L "${marker}" ]] \
    && grep -Fxq 'AID_MANAGED_ROOT=1' "${marker}" 2>/dev/null \
    && grep -Fxq "AID_DATA_ROOT=${candidate}" "${marker}" 2>/dev/null \
    && grep -Fxq "AID_MANAGER_SCRIPT=${managedScript}" "${marker}" 2>/dev/null && return 0
  [[ -f "${state}" && ! -L "${state}" ]] \
    && grep -Fxq "DATA_ROOT=${candidate}" "${state}" 2>/dev/null \
    && grep -Eq '^DEPLOY_MODE=(docker|manual)$' "${state}" 2>/dev/null && return 0
  [[ -f "${descriptor}" && ! -L "${descriptor}" ]] \
    && grep -Fq "\"dataRoot\": \"${candidate}\"" "${descriptor}" 2>/dev/null \
    && grep -Eq '"mode"[[:space:]]*:[[:space:]]*"(docker|manual|systemd)"' "${descriptor}" 2>/dev/null && return 0
  [[ -f "${manualConfig}" && ! -L "${manualConfig}" ]] \
    && grep -Fq '# AID 手动部署配置（唯一配置真源' "${manualConfig}" 2>/dev/null \
    && grep -Fxq "DATA_ROOT=${candidate}" "${manualConfig}" 2>/dev/null && return 0
  [[ -f "${dockerConfig}" && ! -L "${dockerConfig}" ]] \
    && grep -Fq '# AID Docker 部署配置（唯一配置真源）' "${dockerConfig}" 2>/dev/null \
    && grep -Fxq "DATA_ROOT=${candidate}" "${dockerConfig}" 2>/dev/null && return 0
  [[ -f "${managedScript}" && ! -L "${managedScript}" ]] \
    && grep -Fq -- "${AID_BOOTSTRAP_MARKER}" "${managedScript}" 2>/dev/null
}

aid_legacy_docker_mount_root_candidate() { # aid_legacy_docker_mount_root_candidate <bind source>
  local source="$1" suffix root
  for suffix in /app /config /installer /mysql-data /redis-data /rocketmq /uploads /logs; do
    [[ "${source}" == *"${suffix}" ]] || continue
    root="${source%${suffix}}"
    is_safe_aid_data_root_candidate "${root}" || continue
    aid_root_candidate_has_strong_evidence "${root}" || continue
    printf '%s\n' "${root}"
    return 0
  done
  return 1
}

infer_aid_data_root_from_docker() {
  local container marker root mount candidate
  command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 || return 0
  while IFS= read -r container; do
    [[ -n "${container}" ]] || continue
    marker="$(docker inspect --format '{{index .Config.Labels "com.aid.managed"}}' "${container}" 2>/dev/null || true)"
    root="$(docker inspect --format '{{index .Config.Labels "com.aid.data_root"}}' "${container}" 2>/dev/null || true)"
    if [[ "${marker}" == "true" && -n "${root}" ]]; then
      add_aid_data_root_candidate "${root}"
      continue
    fi
    case "${container}" in
      aid-nginx-https|aid-nginx|aid-web|aid-server|aid-updater|aid-rocketmq-broker|aid-rocketmq-nameserver|aid-redis|aid-mysql) ;;
      *) continue ;;
    esac
    while IFS= read -r mount; do
      candidate="$(aid_legacy_docker_mount_root_candidate "${mount}" 2>/dev/null || true)"
      [[ -z "${candidate}" ]] || add_aid_data_root_candidate "${candidate}"
    done < <(docker inspect --format '{{range .Mounts}}{{if eq .Type "bind"}}{{.Source}}{{"\n"}}{{end}}{{end}}' "${container}" 2>/dev/null || true)
  done < <(docker ps -a --format '{{.Names}}' 2>/dev/null || true)
}

infer_aid_data_root() {
  local candidate="" linkTarget="" unitPath="" markerRoot="" workingDir="" legacyRoot="" existing
  AID_DATA_ROOT_CANDIDATES=()
  AID_DATA_ROOT_CANDIDATE_COUNT=0

  candidate="$(aid_script_data_root_candidate "${SCRIPT_PATH}" 2>/dev/null || true)"
  [[ -z "${candidate}" ]] || add_aid_data_root_candidate "${candidate}"

  # 受管管理命令只能是指向安装器目录的软链接；不会把普通 /usr/local/bin/aid 当成 AID。
  if [[ -L "${AID_LOCAL_BIN_DIR}/aid" ]]; then
    linkTarget="$(readlink -f -- "${AID_LOCAL_BIN_DIR}/aid" 2>/dev/null || true)"
    candidate="$(aid_script_data_root_candidate "${linkTarget}" 2>/dev/null || true)"
    [[ -z "${candidate}" ]] || add_aid_data_root_candidate "${candidate}"
  fi

  # 新 unit 有明确 marker；旧 unit 仅接受 AID 后端工作目录及其产物的精确组合。
  for unitPath in "${AID_SYSTEMD_UNIT_DIR}/aid.service" "${AID_SYSTEMD_UNIT_DIR}/aid-web.service" \
      "${AID_SYSTEMD_UNIT_DIR}/aid-updater.service" "${AID_SYSTEMD_UNIT_DIR}/aid-mysql.service" \
      "${AID_SYSTEMD_UNIT_DIR}/aid-redis.service" "${AID_SYSTEMD_UNIT_DIR}/aid-nginx.service"; do
    [[ -f "${unitPath}" && ! -L "${unitPath}" ]] || continue
    markerRoot="$(sed -nE 's|^# AID_DATA_ROOT=(/[^[:space:]]+)$|\1|p' "${unitPath}" | head -n 1)"
    if grep -Fxq '# AID_MANAGED_UNIT=1' "${unitPath}" 2>/dev/null && [[ -n "${markerRoot}" ]]; then
      add_aid_data_root_candidate "${markerRoot}"
      continue
    fi
    workingDir="$(sed -nE 's#^WorkingDirectory=(/.*)/app$#\1#p' "${unitPath}" | head -n 1)"
    [[ -n "${workingDir}" ]] || continue
    legacyRoot="${workingDir}"
    if grep -Fq "WorkingDirectory=${legacyRoot}/app" "${unitPath}" 2>/dev/null \
        && { grep -Fq "${legacyRoot}/app/aid-admin.jar" "${unitPath}" 2>/dev/null \
          || grep -Fq "EnvironmentFile=${legacyRoot}/aid-deploy.conf" "${unitPath}" 2>/dev/null; }; then
      add_aid_data_root_candidate "${legacyRoot}"
    fi
  done

  # 没有脚本、管理链接或 unit 候选时，才只读检查 Docker。新版使用归属标签；
  # 无标签旧容器必须是固定名称，并由 bind mount 反推到带强 AID 证据的根目录。
  [[ "${AID_DATA_ROOT_CANDIDATE_COUNT}" -gt 0 ]] || infer_aid_data_root_from_docker

  if [[ "${AID_DATA_ROOT_CANDIDATE_COUNT}" -eq 1 ]]; then
    printf '%s\n' "${AID_DATA_ROOT_CANDIDATES[0]}"
    return 0
  fi
  if [[ "${AID_DATA_ROOT_CANDIDATE_COUNT}" -gt 1 ]]; then
    printf '[失败] 检测到多个 AID 数据目录，拒绝选择：\n' >&2
    for existing in "${AID_DATA_ROOT_CANDIDATES[@]+"${AID_DATA_ROOT_CANDIDATES[@]}"}"; do printf '  - %s\n' "${existing}" >&2; done
    printf '请显式指定唯一目录，例如：sudo AID_DATA_ROOT=/实际目录 aid uninstall --purge\n' >&2
    return 2
  fi
  return 1
}

if [[ "${AID_DATA_ROOT_EXPLICIT}" == "0" ]]; then
  if inferredDataRoot="$(infer_aid_data_root)"; then
    DATA_ROOT="${inferredDataRoot}"
  else
    inferStatus=$?
    [[ "${inferStatus}" -eq 2 ]] && exit 1
  fi
fi
# 去掉非根路径末尾的分隔符后再做完整安全校验。数据根本身及任一既有父级
# 不能通过软链接跳转，也不能是文件系统根或过浅的系统目录。
while [[ "${DATA_ROOT}" != "/" && "${DATA_ROOT}" == */ ]]; do DATA_ROOT="${DATA_ROOT%/}"; done
if ! is_safe_aid_data_root_candidate "${DATA_ROOT}"; then
  echo "[失败] AID_DATA_ROOT 不是安全的独立绝对目录（禁止软链接、.. 和系统根）: ${DATA_ROOT}" >&2
  exit 1
fi
# 数据根目录必须是绝对路径：conf/systemd/compose 挂载全部依赖它可寻址
case "${DATA_ROOT}" in
  /*) ;;
  *) echo "[失败] AID_DATA_ROOT 必须是绝对路径（当前: ${DATA_ROOT}）" >&2; exit 1 ;;
esac
COMPOSE_DIR="${SCRIPT_DIR}/docker"
CONFIG_ROOT="${DATA_ROOT}/config"
AID_ROOT_MARKER="${CONFIG_ROOT}/.aid-managed"
AID_NGINX_SITE_DIRS="${AID_NGINX_SITE_DIRS:-${CONFIG_ROOT}/nginx/conf.d:/etc/nginx/conf.d:/www/server/panel/vhost/nginx}"
AID_NGINX_STATE_FILE="${CONFIG_ROOT}/nginx/managed-site.state"
DEPLOYMENT_DESCRIPTOR="${CONFIG_ROOT}/deployment.json"
STATE_FILE="${CONFIG_ROOT}/install-state.conf"
DEFAULT_MANUAL_CONFIG="${DATA_ROOT}/aid-deploy.conf"
DEFAULT_DOCKER_CONFIG="${CONFIG_ROOT}/docker.env"
descriptorMode=""
descriptorPath=""
descriptorNormalizedPath=""
configRootNormalized="$(readlink -m -- "${CONFIG_ROOT}" 2>/dev/null || true)"
if [[ -f "${DEPLOYMENT_DESCRIPTOR}" ]]; then
  descriptorMode="$(grep -E '"mode"[[:space:]]*:' "${DEPLOYMENT_DESCRIPTOR}" 2>/dev/null | head -n 1 | sed -E 's/.*:[[:space:]]*"([^"]+)".*/\1/')"
  descriptorPath="$(grep -E '"configPath"[[:space:]]*:' "${DEPLOYMENT_DESCRIPTOR}" 2>/dev/null | head -n 1 | sed -E 's/.*:[[:space:]]*"([^"]+)".*/\1/')"
fi
CONF="${DEFAULT_MANUAL_CONFIG}"
ENV_FILE="${DEFAULT_DOCKER_CONFIG}"
if [[ ( "${descriptorMode}" == "manual" || "${descriptorMode}" == "systemd" ) && "${descriptorPath}" == /* ]]; then CONF="${descriptorPath}"; fi
# 旧版在远程引导脚本位于 /tmp 时，可能将 Docker 配置误写到临时 deploy/docker/.env。
# 这里只记录 descriptor 指向，绝不直接将未受控路径作为当前配置真源；ensure_env_file
# 会在核对 root 所有权、普通文件、AID 配置头和 DATA_ROOT 后执行一次性迁移。
LEGACY_DOCKER_CONFIG_PATH=""
if [[ "${descriptorMode}" == "docker" && "${descriptorPath}" == /* ]]; then
  descriptorNormalizedPath="$(readlink -m -- "${descriptorPath}" 2>/dev/null || true)"
  if [[ -n "${configRootNormalized}" \
      && "${descriptorNormalizedPath}" == "${descriptorPath}" \
      && "${descriptorNormalizedPath}" == "${configRootNormalized}/"* ]]; then
    # 后台允许在受控配置目录中选择自定义 .env/.conf，继续保留该指向。
    ENV_FILE="${descriptorPath}"
  elif [[ "${descriptorPath}" != "${DEFAULT_DOCKER_CONFIG}" ]]; then
    LEGACY_DOCKER_CONFIG_PATH="${descriptorPath}"
  fi
fi

# 必须在本轮安装创建目录、补齐配置或写入受管标记之前保留数据根的原始状态。
# 否则一个原本属于其他业务的 runtime/config 等目录，会被本轮刚写入的
# deployment.json/.aid-managed 反向“证明”为 AID 目录，首次确认便失去保护作用。
AID_DATA_ROOT_OWNED_ON_ENTRY=0
AID_DATA_ROOT_UNMANAGED_ON_ENTRY=""
if aid_root_candidate_has_strong_evidence "${DATA_ROOT}"; then
  AID_DATA_ROOT_OWNED_ON_ENTRY=1
elif [[ -d "${DATA_ROOT}" && ! -L "${DATA_ROOT}" ]]; then
  while IFS= read -r -d '' preexistingEntry; do
    AID_DATA_ROOT_UNMANAGED_ON_ENTRY="${preexistingEntry}"
    break
  done < <(find "${DATA_ROOT}" -mindepth 1 -maxdepth 1 -print0 2>/dev/null)
fi
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
# 官网一行命令每次都会下载 master 上的最新引导脚本。已有部署时继续使用这份
# 新脚本的控制逻辑，但把 Compose/SQL 等运行资源指向已安装目录，避免被旧 aid.sh 接管。
if [[ "${AID_REMOTE_BOOTSTRAP:-0}" == "1" \
    && -f "${INSTALLER_ROOT}/deploy/docker/docker-compose.yml" \
    && -f "${INSTALLER_ROOT}/sql/aid-init.sql" ]]; then
  REPO_DIR="${INSTALLER_ROOT}"
  COMPOSE_DIR="${INSTALLER_ROOT}/deploy/docker"
fi
DOWNLOAD_TIMEOUT_SECONDS="${AID_DOWNLOAD_TIMEOUT_SECONDS:-0}"
DOWNLOAD_MIN_SPEED_BYTES="${AID_DOWNLOAD_MIN_SPEED_BYTES:-1024}"
DOWNLOAD_LOW_SPEED_SECONDS="${AID_DOWNLOAD_LOW_SPEED_SECONDS:-30}"
SOURCE_BUILDER_NAME="build-release-from-source.sh"
SOURCE_GIT_IMAGE="${AID_GIT_IMAGE:-alpine/git:2.47.2}"
SOURCE_MAVEN_IMAGE="${AID_MAVEN_IMAGE:-maven:3.9.9-eclipse-temurin-17}"
SOURCE_NODE_IMAGE="${AID_NODE_IMAGE:-node:22.22.0-bookworm-slim}"
SOURCE_GO_IMAGE="${AID_GO_IMAGE:-golang:1.22.12-bookworm}"
# Docker Hub 国内代理采用可配置候选列表。默认值均为公开 Registry 代理；云厂商
# 专属加速地址可通过正式配置 DOCKER_MIRRORS 或 AID_DOCKER_MIRRORS 覆盖。
DEFAULT_DOCKER_MIRRORS="docker.m.daocloud.io,dockerproxy.net"
DOCKER_MIRROR_ORDER=""
DOCKER_MIRRORS_RESOLVED=0
IMAGE_PULL_TIMEOUT_SECONDS="${AID_IMAGE_PULL_TIMEOUT_SECONDS:-900}"
IMAGE_MANIFEST_PROBE_TIMEOUT_SECONDS="${AID_IMAGE_MANIFEST_PROBE_TIMEOUT_SECONDS:-45}"
DOCKER_MIN_VERSION="24.0.0"
DOCKER_COMPOSE_MIN_VERSION="2.20.0"
GIT_MIN_VERSION="1.8.3"
NGINX_VERSION="1.30.4"
NGINX_MIN_VERSION="1.30.4"
NGINX_HOME=""
NGINX_BIN=""
NGINX_SERVICE=""
NGINX_SITE_DIR=""
NGINX_MANAGED_SERVICE="aid-nginx.service"
JDK_VERSION="17.0.20"
JDK_BUILD="8"
JDK_HOME=""
MANUAL_JDK_VERSION="17.0.8"
JAVA_PROFILE_FILE="${AID_JAVA_PROFILE_FILE:-/etc/profile.d/aid-java.sh}"
NODE_VERSION="22.22.0"
NODE_HOME=""
NODE_RUNTIME_ERROR=""
MAVEN_VERSION="3.9.9"
MAVEN_HOME=""
GO_VERSION="1.22.12"
GO_HOME=""
FFMPEG_RUNTIME_VERSION="7.0.2"
FFMPEG_MIN_VERSION="5.1"
FFMPEG_RUNTIME_MIRROR_TAG="v1.0.0-beta.6"
FFMPEG_REQUIRED_ENCODERS="libx264 libx265 aac"
FFMPEG_REQUIRED_FILTERS="tpad apad scale pad fps setpts concat overlay drawtext amix alimiter aresample atrim asetpts aformat"
FFMPEG_RUNTIME_ROOT="/opt/aid-ffmpeg"
FFMPEG_RUNTIME_ARCH=""
FFMPEG_RUNTIME_HOME=""
FFMPEG_RUNTIME_FFMPEG=""
FFMPEG_RUNTIME_FFPROBE=""
AID_FONT_ROOT="/opt/aid-fonts"
AID_FONT_CURRENT="/opt/aid-fonts/current"
AID_CJK_FONT_PATH="/opt/aid-fonts/current/aid-cjk-font"
AID_CJK_FONT_VERSION="noto-sans-sc-2.004"
AID_CJK_FONT_ARCHIVE="18_NotoSansSC.zip"
AID_CJK_FONT_FILE="NotoSansSC-Regular.otf"
AID_CJK_FONT_SHA256="4d107c09ada479d3e48b6e78c83835773cbd9214bf6e12cdb7b60f8e068292ec"
AID_CJK_FONT_FILE_SHA256="faa6c9df652116dde789d351359f3d7e5d2285a2b2a1f04a2d7244df706d5ea9"
MYSQL_VERSION="5.7.44"
MYSQL_HOME=""
MYSQL_MANAGED_SERVICE="aid-mysql.service"
REDIS_VERSION="8.0.5"
REDIS_HOME=""
REDIS_MANAGED_SERVICE="aid-redis.service"
# AID 国内依赖镜像候选池。安装器会统一测速、排序并在失败时切换节点，
# 任何下载内容仍必须通过 AID 固定的官方摘要/签名校验后才可安装。
BT_MIRROR_NODES_CN="https://dg2.bt.cn https://download-cdn1.bt.cn https://download.bt.cn https://ctcc1-node.bt.cn https://cmcc1-node.bt.cn https://ctcc2-node.bt.cn https://hk1-node.bt.cn https://na1-node.bt.cn https://jp1-node.bt.cn https://cf1-node.aapanel.com"
BT_MIRROR_NODES_GLOBAL="https://cf1-node.aapanel.com https://jp1-node.bt.cn https://na1-node.bt.cn https://download.bt.cn https://dg2.bt.cn https://download-cdn1.bt.cn https://ctcc1-node.bt.cn https://ctcc2-node.bt.cn https://hk1-node.bt.cn https://cmcc1-node.bt.cn"
BT_MIRROR_ORDER=""
BT_MIRRORS_RESOLVED=0
JAVA_RUNTIME_IMAGE="aid/openjdk:17.0.20-ffmpeg${FFMPEG_RUNTIME_VERSION}-font2.004"
DEFAULT_ADMIN_ENTRY_CODE=""
OS_PACKAGE_INDEX_READY=0

risk() {
  echo -e "[$(date '+%H:%M:%S')] ${C_RED}${C_BOLD}[风险提醒]${C_RESET} $1" >&2
}

section() {
  echo ""
  echo -e "${C_CYAN}${C_BOLD}==================== $1 ====================${C_RESET}"
}

require_root() { [[ "$(id -u)" -eq 0 ]] || die "请使用 root 执行（已部署环境使用 sudo aid <子命令>）"; }

# 配置读写：key=value 存于 ${CONF}
conf_get() { # conf_get <key> <默认值>
  local value=""
  if [[ -f "${CONF}" ]] && grep -qE "^${1}=" "${CONF}" 2>/dev/null; then
    value="$(grep -E "^${1}=" "${CONF}" 2>/dev/null | head -n 1 | cut -d= -f2-)"
    echo "${value}"
  else
    echo "${2:-}"
  fi
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

# 安装状态与运行配置严格分离。该文件只保存部署方式、当前版本与发布渠道，
# 不参与应用启动，也不保存数据库密码等业务配置。
state_get() { # state_get <key> <default>
  local value=""
  [[ -f "${STATE_FILE}" ]] && value="$(grep -E "^${1}=" "${STATE_FILE}" 2>/dev/null | head -n 1 | cut -d= -f2-)"
  # 兼容旧部署：迁移完成前允许从原 aid-deploy.conf 读取历史状态字段。
  [[ -z "${value}" ]] && value="$(conf_get "$1" '')"
  echo "${value:-${2:-}}"
}

state_set() { # state_set <key> <value>
  mkdir -p "${CONFIG_ROOT}"
  touch "${STATE_FILE}"; chmod 600 "${STATE_FILE}"
  if grep -qE "^${1}=" "${STATE_FILE}" 2>/dev/null; then
    sed -i "s|^${1}=.*|${1}=${2}|" "${STATE_FILE}"
  else
    echo "${1}=${2}" >> "${STATE_FILE}"
  fi
}
ask() { # ask <提示> <默认值>
  local answer
  read -r -p "$1 [$2]: " answer </dev/tty
  echo "${answer:-$2}"
}
ask_yes_no() { # ask_yes_no <提示> <默认值:y|n>
  local prompt="$1" defaultAnswer="$2" answer
  [[ "${defaultAnswer}" == "y" || "${defaultAnswer}" == "n" ]] \
    || die "y/n默认值只支持y或n"
  while :; do
    read -r -p "${prompt} (y/n) [${defaultAnswer}]: " answer </dev/tty
    answer="${answer:-${defaultAnswer}}"
    case "${answer,,}" in
      y) echo "y"; return 0 ;;
      n) echo "n"; return 0 ;;
      *) echo "请输入 y 或 n" >/dev/tty ;;
    esac
  done
}
ask_secret() { # ask_secret <提示>
  local answer
  read -r -s -p "$1: " answer </dev/tty
  echo "" >/dev/tty
  echo "${answer}"
}
gen_secret() { tr -dc 'A-Za-z0-9' </dev/urandom | head -c 48 || true; }
# MySQL 密码需要兼顾终端手工录入体验，自动生成固定 12 位字母数字；JWT 等
# 其他密钥仍使用上面的 48 位随机值，不随数据库密码规则缩短。
gen_database_secret() { gen_secret | head -c 12; }

# Docker 部署配置真源默认固定在 DATA_ROOT/config/docker.env；
# 后台自定义路径也必须位于 DATA_ROOT/config 受控目录中。
env_get() { # env_get <key> <默认值>
  local value=""
  if [[ -f "${ENV_FILE}" ]] && grep -qE "^${1}=" "${ENV_FILE}" 2>/dev/null; then
    value="$(grep -E "^${1}=" "${ENV_FILE}" 2>/dev/null | head -n 1 | cut -d= -f2-)"
    echo "${value}"
  else
    echo "${2:-}"
  fi
}
env_set() { # env_set <key> <value>（仅用于自动生成缺失密钥，其余内容不动）
  if grep -qE "^${1}=" "${ENV_FILE}" 2>/dev/null; then
    sed -i "s|^${1}=.*|${1}=${2}|" "${ENV_FILE}"
  else
    echo "${1}=${2}" >> "${ENV_FILE}"
  fi
}

write_aid_root_marker() {
  local tmp
  mkdir -p "${CONFIG_ROOT}"
  tmp="$(mktemp "${CONFIG_ROOT}/.aid-managed.XXXXXX")" || die "无法写入 AID 受管标记"
  cat > "${tmp}" <<EOF
AID_MANAGED_ROOT=1
AID_DATA_ROOT=${DATA_ROOT}
AID_MANAGER_SCRIPT=${MANAGED_SCRIPT}
EOF
  chmod 600 "${tmp}"
  mv -f -- "${tmp}" "${AID_ROOT_MARKER}"
}

write_deployment_descriptor() { # write_deployment_descriptor <docker|manual> <配置绝对路径>
  local mode="$1" configPath="$2" tmp
  [[ "${configPath}" == /* ]] || die "部署配置路径必须是绝对路径"
  mkdir -p "${CONFIG_ROOT}"
  tmp="$(mktemp "${CONFIG_ROOT}/.deployment.XXXXXX")"
  cat > "${tmp}" <<EOF
{
  "mode": "${mode}",
  "dataRoot": "${DATA_ROOT}",
  "configPath": "${configPath}"
}
EOF
  chmod 600 "${tmp}"
  mv -f "${tmp}" "${DEPLOYMENT_DESCRIPTOR}"
  write_aid_root_marker
}

docker_config_has_managed_identity() { # docker_config_has_managed_identity <path>
  local path="$1" resolved owner links
  [[ -f "${path}" && ! -L "${path}" ]] || return 1
  resolved="$(readlink -f -- "${path}" 2>/dev/null || true)"
  [[ -n "${resolved}" && "${resolved}" == "${path}" ]] || return 1
  owner="$(stat -c '%u' -- "${path}" 2>/dev/null || true)"
  links="$(stat -c '%h' -- "${path}" 2>/dev/null || true)"
  [[ "${owner}" == "0" && "${links}" == "1" ]] || return 1
  grep -Fq '# AID Docker 部署配置（唯一配置真源）' "${path}" 2>/dev/null \
    && grep -Fxq "DATA_ROOT=${DATA_ROOT}" "${path}" 2>/dev/null
}

legacy_docker_descriptor_matches() { # legacy_docker_descriptor_matches <legacy path>
  local legacy="$1" resolved owner links
  [[ -f "${DEPLOYMENT_DESCRIPTOR}" && ! -L "${DEPLOYMENT_DESCRIPTOR}" ]] || return 1
  resolved="$(readlink -f -- "${DEPLOYMENT_DESCRIPTOR}" 2>/dev/null || true)"
  [[ -n "${resolved}" && "${resolved}" == "${DEPLOYMENT_DESCRIPTOR}" ]] || return 1
  owner="$(stat -c '%u' -- "${DEPLOYMENT_DESCRIPTOR}" 2>/dev/null || true)"
  links="$(stat -c '%h' -- "${DEPLOYMENT_DESCRIPTOR}" 2>/dev/null || true)"
  [[ "${owner}" == "0" && "${links}" == "1" ]] || return 1
  grep -Fq '"mode": "docker"' "${DEPLOYMENT_DESCRIPTOR}" 2>/dev/null \
    && grep -Fq "\"dataRoot\": \"${DATA_ROOT}\"" "${DEPLOYMENT_DESCRIPTOR}" 2>/dev/null \
    && grep -Fq "\"configPath\": \"${legacy}\"" "${DEPLOYMENT_DESCRIPTOR}" 2>/dev/null
}

legacy_docker_parent_is_secure() { # legacy_docker_parent_is_secure <legacy path>
  local parent resolved owner mode
  parent="$(dirname -- "$1")"
  [[ -d "${parent}" && ! -L "${parent}" ]] || return 1
  resolved="$(readlink -f -- "${parent}" 2>/dev/null || true)"
  [[ -n "${resolved}" && "${resolved}" == "${parent}" ]] || return 1
  owner="$(stat -c '%u' -- "${parent}" 2>/dev/null || true)"
  mode="$(stat -c '%a' -- "${parent}" 2>/dev/null || true)"
  [[ "${owner}" == "0" && "${mode}" =~ ^[0-7]{3,4}$ ]] || return 1
  (( (8#${mode} & 8#022) == 0 ))
}

archive_migrated_docker_config() { # archive_migrated_docker_config <legacy path>
  local legacy="$1" backup stamp suffix=0
  stamp="$(date '+%Y%m%d-%H%M%S')"
  backup="${legacy}.migrated.${stamp}.bak"
  while [[ -e "${backup}" || -L "${backup}" ]]; do
    suffix=$((suffix + 1)); backup="${legacy}.migrated.${stamp}.${suffix}.bak"
  done
  if mv -- "${legacy}" "${backup}"; then
    chmod 600 "${backup}" 2>/dev/null || true
    chown root:root "${backup}" 2>/dev/null || true
    echo "  旧配置备份: ${backup}"
  else
    warn "Docker 配置已迁移，但旧文件无法改名，请人工移除: ${legacy}"
  fi
}

migrate_legacy_docker_config() {
  local legacy="${LEGACY_DOCKER_CONFIG_PATH:-}" resolved staged
  [[ -n "${legacy}" && "${legacy}" != "${ENV_FILE}" ]] || return 0

  # 仅兼容两类历史路径：受管安装器的旧 .env，以及旧版远程引导器
  # 留在 /tmp/.../docker/.env 的临时文件。其他 CONFIG_ROOT 外路径一律拒绝。
  case "${legacy}" in
    "${INSTALLER_ROOT}/deploy/docker/.env"|/tmp/docker/.env|/tmp/*/docker/.env) ;;
    *) die "旧 Docker 配置不在可迁移的历史路径: ${legacy}" ;;
  esac
  resolved="$(readlink -f -- "${legacy}" 2>/dev/null || true)"
  [[ -n "${resolved}" && "${resolved}" == "${legacy}" ]] \
    || die "旧 Docker 配置路径包含链接或不安全跳转: ${legacy}"
  legacy_docker_parent_is_secure "${legacy}" \
    || die "旧 Docker 配置父目录必须由 root 所有、不是软链接且不允许组或其他用户写入"
  legacy_docker_descriptor_matches "${legacy}" \
    || die "旧 Docker 配置缺少可信部署描述证据: ${legacy}"
  docker_config_has_managed_identity "${legacy}" \
    || die "旧 Docker 配置必须是 root 所有的独立普通文件，且 AID 标识与 DATA_ROOT 必须匹配"

  # 受控真源与旧文件同时存在时，只有内容逐字节一致才能收敛指向。
  # 不一致往往意味着两套数据库凭证，必须阻断并交由管理员选择。
  if [[ -e "${ENV_FILE}" || -L "${ENV_FILE}" ]]; then
    docker_config_has_managed_identity "${ENV_FILE}" \
      || die "Docker 受控配置身份校验失败: ${ENV_FILE}"
    cmp -s -- "${legacy}" "${ENV_FILE}" \
      || die "Docker 配置内容冲突，禁止自动选择: ${legacy} <> ${ENV_FILE}"
    write_deployment_descriptor docker "${ENV_FILE}"
    archive_migrated_docker_config "${legacy}"
    LEGACY_DOCKER_CONFIG_PATH=""
    ok "Docker descriptor 已收敛到受控配置: ${ENV_FILE}"
    return 0
  fi

  mkdir -p "${CONFIG_ROOT}"
  [[ ! -L "${CONFIG_ROOT}" ]] || die "Docker 配置目录不能是软链接: ${CONFIG_ROOT}"
  staged="$(mktemp "${CONFIG_ROOT}/.docker.env.migrate.XXXXXX")" \
    || die "无法创建 Docker 配置迁移临时文件"
  if ! install -m 0600 "${legacy}" "${staged}" || ! cmp -s -- "${legacy}" "${staged}"; then
    rm -f -- "${staged}"
    die "Docker 配置迁移复制或校验失败，旧文件未修改"
  fi
  mv -f -- "${staged}" "${ENV_FILE}" \
    || { rm -f -- "${staged}"; die "Docker 配置迁移落盘失败，旧文件未修改"; }
  chown root:root "${ENV_FILE}" 2>/dev/null || true
  chmod 600 "${ENV_FILE}"
  docker_config_has_managed_identity "${ENV_FILE}" \
    || die "Docker 受控配置迁移后身份复核失败: ${ENV_FILE}"
  write_deployment_descriptor docker "${ENV_FILE}"

  # 目标和 descriptor 都已落盘后再把旧文件原子改名为 root-only 备份。
  archive_migrated_docker_config "${legacy}"
  ok "Docker 配置已安全迁移至: ${ENV_FILE}"
  LEGACY_DOCKER_CONFIG_PATH=""
}

config_sha256() { sha256_file "$1" 2>/dev/null || true; }

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

validate_port() { # validate_port <名称> <值>
  [[ "$2" =~ ^[0-9]+$ ]] && (( 10#$2 >= 1 && 10#$2 <= 65535 )) \
    || die "$1 必须是 1-65535 的端口"
}

docker_profile_enabled() { # docker_profile_enabled <profile>
  local profiles
  # 旧版 Docker 配置中 MySQL 固定内置，COMPOSE_PROFILES 可能只有 redis。
  # DB_HOST=mysql 已能无歧义表示内置数据库，运行时兼容启用 mysql Profile，
  # 无需改写用户原有的 COMPOSE_PROFILES 值。
  if [[ "$1" == "mysql" && "$(env_get DB_HOST mysql)" == "mysql" ]]; then
    return 0
  fi
  profiles="$(env_get COMPOSE_PROFILES mysql,redis | tr -d '[:space:]')"
  [[ ",${profiles}," == *",$1,"* ]]
}

validate_compose_profiles() {
  local profiles item
  local -a items=()
  profiles="$(env_get COMPOSE_PROFILES mysql,redis)"
  IFS=',' read -ra items <<< "${profiles}"
  for item in "${items[@]}"; do
    item="$(echo "${item}" | xargs)"
    [[ -z "${item}" || "${item}" == "mysql" || "${item}" == "redis" || "${item}" == "mq" || "${item}" == "https" ]] \
      || die "COMPOSE_PROFILES 仅支持 mysql、redis、mq、https"
  done
}

validate_https_file() { # validate_https_file <配置名> <路径>
  local key="$1" path="$2" allowedRoot resolved
  [[ "${path}" == /* ]] || die "${key} 必须是绝对路径"
  [[ -f "${path}" && -r "${path}" ]] || die "${key} 文件不存在或不可读: ${path}"
  [[ ! -L "${path}" ]] || die "${key} 禁止使用软链接，请复制证书到 ${DATA_ROOT}/config/ssl"
  allowedRoot="$(readlink -f "${DATA_ROOT}/config/ssl" 2>/dev/null || true)"
  resolved="$(readlink -f "${path}" 2>/dev/null || true)"
  [[ -n "${allowedRoot}" && "${resolved}" == "${allowedRoot}/"* ]] \
    || die "${key} 只能放在 ${DATA_ROOT}/config/ssl"
}

validate_docker_extended_config() {
  local dbHost dbPort redisHost redisPort redisUser redisPwd mqAccessKey mqSecretKey domain adminDomain certPath keyPath
  dependency_install_mode docker >/dev/null
  validate_compose_profiles
  dbHost="$(env_get DB_HOST mysql)"; dbPort="$(env_get DB_PORT 3306)"
  validate_port DB_PORT "${dbPort}"
  [[ "$(env_get DB_NAME '')" =~ ^[A-Za-z0-9_]+$ ]] || die "DB_NAME 仅允许字母、数字和下划线"
  [[ "$(env_get DB_USERNAME '')" =~ ^[A-Za-z0-9_.-]+$ ]] || die "DB_USERNAME 格式错误"
  if docker_profile_enabled mysql; then
    [[ "${dbHost}" == "mysql" && "${dbPort}" == "3306" ]] \
      || die "启用内置 MySQL 时 DB_HOST/DB_PORT 必须为 mysql/3306"
    [[ -n "$(env_get MYSQL_ROOT_PASSWORD '')" ]] || die "内置 MySQL 必须配置 MYSQL_ROOT_PASSWORD"
  else
    [[ -n "${dbHost}" && "${dbHost}" != "mysql" ]] || die "外部 MySQL 必须配置可访问的 DB_HOST"
    case "${dbHost}" in localhost|127.0.0.1|::1) die "Docker 外部 MySQL 不能填写本容器回环地址，请使用内网 IP、DNS 或 host.docker.internal" ;; esac
    [[ -n "$(env_get DB_PASSWORD '')" ]] || die "外部 MySQL 必须填写真实 DB_PASSWORD"
  fi
  redisHost="$(env_get REDIS_HOST redis)"; redisPort="$(env_get REDIS_PORT 6379)"
  redisUser="$(env_get REDIS_USERNAME '')"; redisPwd="$(env_get REDIS_PASSWORD '')"
  validate_port REDIS_PORT "${redisPort}"
  if docker_profile_enabled redis; then
    [[ "${redisHost}" == "redis" && "${redisPort}" == "6379" ]] \
      || die "启用内置 Redis 时 REDIS_HOST/REDIS_PORT 必须为 redis/6379"
  else
    [[ -n "${redisHost}" && "${redisHost}" != "redis" ]] \
      || die "外部 Redis 必须配置可访问的 REDIS_HOST"
    case "${redisHost}" in
      localhost|127.0.0.1|::1) die "Docker 外部 Redis 不能填写本容器回环地址，请使用内网 IP、DNS 或 host.docker.internal" ;;
    esac
  fi
  [[ -n "${redisUser}" ]] && validate_secret 'REDIS_USERNAME' "${redisUser}"
  [[ -n "${redisPwd}" ]] && validate_secret 'REDIS_PASSWORD' "${redisPwd}"
  if docker_profile_enabled redis && [[ -n "${redisUser}" && "${redisUser}" != "default" ]]; then
    die "内置 Redis 只支持空用户名或 default；自定义 ACL 用户请使用外部 Redis"
  fi
  mqAccessKey="$(env_get ROCKETMQ_ACCESS_KEY '')"; mqSecretKey="$(env_get ROCKETMQ_SECRET_KEY '')"
  [[ -n "${mqAccessKey}" ]] && validate_secret 'ROCKETMQ_ACCESS_KEY' "${mqAccessKey}"
  [[ -n "${mqSecretKey}" ]] && validate_secret 'ROCKETMQ_SECRET_KEY' "${mqSecretKey}"
  [[ -z "${mqAccessKey}" && -z "${mqSecretKey}" || -n "${mqAccessKey}" && -n "${mqSecretKey}" ]] \
    || die "RocketMQ ACL 的 AccessKey 与 SecretKey 必须同时填写或同时留空"
  if [[ -n "${mqAccessKey}" ]]; then
    [[ "${mqAccessKey}" =~ ^[A-Za-z0-9]+$ && "${mqSecretKey}" =~ ^[A-Za-z0-9]+$ ]] \
      || die "RocketMQ ACL 凭证仅允许字母和数字"
  fi
  case "$(env_get ROCKETMQ_ENABLED false)" in true|false) ;; *) die "ROCKETMQ_ENABLED 只支持 true 或 false" ;; esac
  case "$(env_get ROCKETMQ_FLUSH_DISK_TYPE ASYNC_FLUSH)" in
    ASYNC_FLUSH|SYNC_FLUSH) ;;
    *) die "ROCKETMQ_FLUSH_DISK_TYPE 只支持 ASYNC_FLUSH 或 SYNC_FLUSH" ;;
  esac
  if [[ "$(env_get ROCKETMQ_ENABLED false)" == "true" ]]; then
    [[ -n "$(env_get ROCKETMQ_NAMESERVER '')" ]] || die "启用 RocketMQ 时必须配置 ROCKETMQ_NAMESERVER"
  fi
  validate_rocketmq_mode docker
  [[ "$(env_get REDIS_DATABASE 0)" =~ ^[0-9]+$ ]] || die "REDIS_DATABASE 必须是非负整数"
  if docker_profile_enabled https; then
    validate_port HTTPS_PORT "$(env_get HTTPS_PORT 443)"
    domain="$(env_get HTTPS_PUBLIC_DOMAIN '')"; adminDomain="$(env_get HTTPS_ADMIN_DOMAIN '')"
    [[ "${domain}" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || die "HTTPS_PUBLIC_DOMAIN 格式错误"
    [[ "${adminDomain}" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || die "HTTPS_ADMIN_DOMAIN 格式错误"
    [[ "${domain}" != "${adminDomain}" ]] || die "用户端与管理端 HTTPS 域名不能相同"
    [[ "$(env_get HTTPS_PORT 443)" != "$(env_get HTTP_PORT 80)" \
       && "$(env_get HTTPS_PORT 443)" != "$(env_get ADMIN_PORT 8089)" ]] \
      || die "HTTPS_PORT 不能与 HTTP_PORT 或 ADMIN_PORT 重复"
    mkdir -p "${DATA_ROOT}/config/ssl"; chmod 700 "${DATA_ROOT}/config/ssl"
    certPath="$(env_get HTTPS_CERT_PATH "${DATA_ROOT}/config/ssl/fullchain.pem")"
    keyPath="$(env_get HTTPS_KEY_PATH "${DATA_ROOT}/config/ssl/privkey.pem")"
    validate_https_file HTTPS_CERT_PATH "${certPath}"
    validate_https_file HTTPS_KEY_PATH "${keyPath}"
  fi
}

# ----------------------------------------------------------------------------
# 硬件配置检查：按本机部署内容动态计算最低/推荐配置。
# 低于标准时显示当前配置和风险，由管理员使用 y/n 决定是否继续，不强制中止脚本。
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
    # Docker 全栈：MySQL(~1.5G) + Redis(0.5G) + 后端JVM(~2.5G) + 静态 Web Nginx + 网关 Nginx + 系统(~1G)
    minCpu=2; minMem=$((4 * 1024 - 512)); recCpu=4; recMem=$((8 * 1024 - 512)); minDisk=40
  else
    # 手动部署：中间件可能在本机也可能在别机，按“后端+静态 Web/Nginx 在本机”计算下限
    minCpu=2; minMem=$((4 * 1024 - 512)); recCpu=4; recMem=$((8 * 1024 - 512)); minDisk=40
  fi
  if [[ "${withMq}" == "yes" ]]; then
    # 本机启用 RocketMQ：最低 4核4G，推荐 6核12G；外部 MQ 不应加算本机资源。
    minCpu=4; minMem=$((4 * 1024 - 512)); recCpu=6; recMem=$((12 * 1024 - 512))
  fi

  echo ""
  log "${C_CYAN}==> 硬件配置校验${C_RESET}"
  echo "  本机: ${cpuCores} 核 / $((memTotalMb / 1024))G 内存 / 数据盘剩余 ${diskFreeGb}G"
  echo "  最低: $((minCpu)) 核 / $(( (minMem + 512) / 1024 ))G 内存 / ${minDisk}G 磁盘"
  echo "  推荐: $((recCpu)) 核 / $(( (recMem + 512) / 1024 ))G 内存 / 100G+ 磁盘"

  local belowMinimum=0 needsConfirmation=0 go
  [[ "${cpuCores}" -lt "${minCpu}" ]] && warn "当前 CPU 低于最低配置（${cpuCores} 核 < ${minCpu} 核）" && belowMinimum=1
  [[ "${memTotalMb}" -lt "${minMem}" ]] && warn "当前内存低于最低配置（$((memTotalMb / 1024))G < $(( (minMem + 512) / 1024 ))G）" && belowMinimum=1
  [[ "${diskFreeGb}" -lt "${minDisk}" ]] && warn "当前数据盘剩余空间低于最低配置（${diskFreeGb}G < ${minDisk}G）" && belowMinimum=1
  if [[ "${belowMinimum}" -eq 1 ]]; then
    risk "当前本机配置小于最低运行配置，继续安装可能出现 OOM、进程被杀或磁盘写满"
    needsConfirmation=1
  elif [[ "${cpuCores}" -lt "${recCpu}" || "${memTotalMb}" -lt "${recMem}" ]]; then
    warn "当前本机达到最低配置，但低于推荐配置；高并发生成任务时可能吃紧"
    needsConfirmation=1
  fi
  if [[ "${needsConfirmation}" -eq 1 ]]; then
    if [[ "${AID_ASSUME_YES:-0}" == "1" ]]; then
      warn "AID_ASSUME_YES=1：已按 y 继续安装"
    else
      go="$(ask_yes_no '是否继续安装？' 'y')"
      [[ "${go}" == "y" ]] || die "已取消安装"
    fi
  else
    ok "硬件满足推荐配置"
  fi
}

# 部署方式检测：只认健康部署写入的状态或真实运行痕迹；配置文件本身不代表已部署。
detect_mode() {
  local mode current
  mode="$(state_get DEPLOY_MODE '')"
  [[ "${mode}" == "systemd" ]] && mode="manual"
  current="$(state_get CURRENT_VERSION '')"
  # deployment.json 只声明配置文件位置，不代表部署成功。首次配置阶段即会生成它，
  # 因此绝不能仅凭 descriptorMode 把再次执行 install 误导进升级流程。
  if [[ "${mode}" == "docker" && "${current}" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]; then
    echo "docker"; return
  fi
  if [[ "${mode}" == "manual" && "${current}" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]; then
    echo "manual"; return
  fi
  if command -v docker >/dev/null 2>&1 && docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q '^aid-server$'; then
    echo "docker"; return
  fi
  if systemctl list-unit-files 2>/dev/null | grep -q '^aid\.service'; then
    echo "manual"; return
  fi
  echo "none"
}

docker_cli_version() {
  docker --version 2>/dev/null | sed -E 's/.*version[[:space:]]+v?([0-9]+(\.[0-9]+){1,2}).*/\1/' | head -n 1
}

docker_compose_version() {
  docker compose version --short 2>/dev/null | sed -E 's/^v//' | head -n 1
}

docker_runtime_version_matches() {
  local engine compose
  command -v docker >/dev/null 2>&1 || return 1
  engine="$(docker_cli_version)"
  compose="$(docker_compose_version)"
  [[ "${engine}" =~ ^[0-9]+\.[0-9]+ ]] && version_at_least "${engine}" "${DOCKER_MIN_VERSION}" \
    && [[ "${compose}" =~ ^[0-9]+\.[0-9]+ ]] && version_at_least "${compose}" "${DOCKER_COMPOSE_MIN_VERSION}"
}

docker_repo_candidates() { # docker_repo_candidates <ubuntu|debian|centos> <相对文件>
  local distro="$1" file="$2"
  printf '%s\n' \
    "https://mirrors.tuna.tsinghua.edu.cn/docker-ce/linux/${distro}/${file}" \
    "https://mirrors.aliyun.com/docker-ce/linux/${distro}/${file}" \
    "https://download.docker.com/linux/${distro}/${file}"
}

configure_new_docker_registry_mirrors() {
  local daemonFile="/etc/docker/daemon.json" backup="" mirror json="" first=yes
  resolve_docker_mirror_order
  [[ -n "${DOCKER_MIRROR_ORDER}" ]] || return 0
  for mirror in ${DOCKER_MIRROR_ORDER}; do
    [[ "${first}" == "yes" ]] || json+=","
    json+="\"https://${mirror}\""
    first=no
  done
  mkdir -p /etc/docker
  if [[ -s "${daemonFile}" ]]; then
    # 已有 daemon.json 可能包含企业私库、存储驱动或日志策略。安装器绝不覆盖未知配置，
    # AID 自身仍会通过 DOCKER_MIRRORS 逐个前缀拉取镜像。
    warn "检测到现有 ${daemonFile}，为避免覆盖管理员配置，跳过写入全局 registry-mirrors"
    return 0
  fi
  backup="${daemonFile}.bak.$(date +%Y%m%d%H%M%S)"
  [[ ! -e "${daemonFile}" ]] || cp -a "${daemonFile}" "${backup}"
  printf '{\n  "registry-mirrors": [%s]\n}\n' "${json}" > "${daemonFile}" \
    || die "Docker 镜像加速配置写入失败"
  chmod 600 "${daemonFile}"
  if command -v dockerd >/dev/null 2>&1 \
      && ! dockerd --validate --config-file "${daemonFile}" >/dev/null 2>&1; then
    [[ ! -e "${backup}" ]] || cp -a "${backup}" "${daemonFile}"
    die "Docker daemon.json 校验失败，已恢复原配置"
  fi
  ok "Docker 全局镜像加速已按测速结果写入 ${daemonFile}"
}

install_docker_engine() {
  local osId="" distro="" codename="" arch="" selected="" url tmp fingerprint manager=""
  local -a rankedUrls=()
  [[ -r /etc/os-release ]] || die "无法识别 Linux 发行版，不能自动安装 Docker"
  # shellcheck disable=SC1091
  . /etc/os-release
  osId="${ID,,}"
  case "${osId}" in
    ubuntu|debian)
      distro="${osId}"
      install_os_packages "Docker安装基础工具" "ca-certificates curl gnupg" "ca-certificates curl gnupg2"
      mapfile -t rankedUrls < <(rank_download_urls "Docker软件源" $(docker_repo_candidates "${distro}" gpg))
      tmp="$(mktemp)"
      for url in "${rankedUrls[@]}"; do
        if try_download "${url}" "${tmp}" "Docker软件源签名"; then selected="${url%/gpg}"; break; fi
      done
      [[ -n "${selected}" && -s "${tmp}" ]] || { rm -f -- "${tmp}"; die "Docker 国内镜像与官方软件源均不可用"; }
      fingerprint="$(gpg --show-keys --with-colons "${tmp}" 2>/dev/null | awk -F: '$1=="fpr" {print toupper($10); exit}')"
      [[ "${fingerprint}" == "9DC858229FC7DD38854AE2D88D81803C0EBFCD88" ]] \
        || { rm -f -- "${tmp}"; die "Docker 软件源签名指纹不匹配，已拒绝安装"; }
      install -d -m 0755 /etc/apt/keyrings
      install -m 0644 "${tmp}" /etc/apt/keyrings/aid-docker.asc
      rm -f -- "${tmp}"
      codename="${VERSION_CODENAME:-${UBUNTU_CODENAME:-}}"
      [[ -n "${codename}" ]] || die "无法识别发行版代号，不能配置 Docker APT 软件源"
      arch="$(dpkg --print-architecture)"
      printf '%s\n' \
        'Types: deb' \
        "URIs: ${selected}" \
        "Suites: ${codename}" \
        'Components: stable' \
        "Architectures: ${arch}" \
        'Signed-By: /etc/apt/keyrings/aid-docker.asc' \
        > /etc/apt/sources.list.d/aid-docker.sources
      DEBIAN_FRONTEND=noninteractive apt-get update \
        || die "Docker 软件源索引刷新失败；未继续安装"
      DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin \
        || die "Docker Engine 自动安装失败"
      manager=apt ;;
    centos|rhel|rocky|almalinux|almalinux_*|ol|oraclelinux|opencloudos|anolis)
      distro=centos
      install_os_packages "Docker安装基础工具" "ca-certificates curl" "ca-certificates curl"
      mapfile -t rankedUrls < <(rank_download_urls "Docker软件源" $(docker_repo_candidates "${distro}" docker-ce.repo))
      tmp="$(mktemp)"
      for url in "${rankedUrls[@]}"; do
        if try_download "${url}" "${tmp}" "Docker软件源配置"; then
          grep -q '^gpgcheck=1' "${tmp}" && grep -q '^\[docker-ce-stable\]' "${tmp}" \
            && { selected="${url}"; break; }
        fi
      done
      [[ -n "${selected}" ]] || { rm -f -- "${tmp}"; die "Docker 国内镜像与官方软件源均不可用"; }
      install -m 0644 "${tmp}" /etc/yum.repos.d/aid-docker-ce.repo
      rm -f -- "${tmp}"
      if command -v dnf >/dev/null 2>&1; then manager=dnf; else manager=yum; fi
      "${manager}" install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin \
        || die "Docker Engine 自动安装失败" ;;
    *) die "当前发行版 ${osId} 暂不支持自动安装 Docker；请按 Docker 官方文档安装后重试" ;;
  esac
  configure_new_docker_registry_mirrors
  systemctl daemon-reload
  systemctl enable --now docker >/dev/null 2>&1 || die "Docker 已安装但服务启动失败"
  ok "Docker Engine 与 Compose v2 已通过 ${manager} 安装并启动"
}

require_docker_runtime() {
  local engine="" compose="" consent=""
  if docker_runtime_version_matches; then
    engine="$(docker_cli_version)"; compose="$(docker_compose_version)"
    if ! docker info >/dev/null 2>&1; then
      systemctl start docker >/dev/null 2>&1 || die "Docker 版本符合要求但守护进程无法启动"
    fi
    ok "Docker Engine ${engine} / Compose ${compose} 已存在且版本符合，跳过安装"
    return 0
  fi

  engine="$(docker_cli_version 2>/dev/null || true)"
  compose="$(docker_compose_version 2>/dev/null || true)"
  if [[ -n "${engine}" || -n "${compose}" ]]; then
    risk "Docker 版本不符合要求（Engine ${engine:-未检测到}，Compose ${compose:-未检测到}；要求 Engine ${DOCKER_MIN_VERSION}+ / Compose ${DOCKER_COMPOSE_MIN_VERSION}+）"
    warn "升级 Docker 可能重启守护进程并短暂影响本机已有容器，请先确认业务窗口"
  else
    warn "未检测到 Docker Engine；仅在管理员确认后才会配置软件源并自动安装"
  fi
  consent="${AID_AUTO_INSTALL_DOCKER:-}"
  case "${consent}" in
    yes|y|true|1) consent=y ;;
    no|n|false|0) consent=n ;;
    '') consent="$(ask_yes_no '是否自动安装/升级 Docker Engine 与 Compose v2？' 'n')" ;;
    *) die "AID_AUTO_INSTALL_DOCKER 只支持 yes 或 no" ;;
  esac
  if [[ "${consent}" != "y" ]]; then
    die "已停止；请先人工安装 Docker Engine ${DOCKER_MIN_VERSION}+ 与 Compose ${DOCKER_COMPOSE_MIN_VERSION}+ 后重试"
  fi
  install_docker_engine
  docker_runtime_version_matches || die "Docker 安装完成但版本仍不符合要求"
  docker info >/dev/null 2>&1 || die "Docker 安装完成但守护进程不可用"
}

dependency_install_mode() { # dependency_install_mode <docker|manual>
  local mode="$1" value
  if [[ "${mode}" == "docker" ]]; then
    value="$(env_get DEPENDENCY_INSTALL_MODE auto)"
  else
    value="$(conf_get DEPENDENCY_INSTALL_MODE auto)"
  fi
  case "${value}" in
    auto|manual) echo "${value}" ;;
    *) die "DEPENDENCY_INSTALL_MODE 只支持 auto 或 manual" ;;
  esac
}

dependency_region_setting() {
  if [[ -n "${AID_DEPENDENCY_REGION:-}" ]]; then
    echo "${AID_DEPENDENCY_REGION}"
  else
    case "${descriptorMode:-}" in
      docker) env_get DEPENDENCY_REGION auto ;;
      manual|systemd) conf_get DEPENDENCY_REGION auto ;;
      *)
        if [[ -f "${ENV_FILE}" ]]; then
          env_get DEPENDENCY_REGION auto
        elif [[ -f "${CONF}" ]]; then
          conf_get DEPENDENCY_REGION auto
        else
          echo auto
        fi ;;
    esac
  fi
}

download_timeout_setting() {
  local configured=""
  if [[ -n "${AID_DOWNLOAD_TIMEOUT_SECONDS+x}" ]]; then
    configured="${AID_DOWNLOAD_TIMEOUT_SECONDS}"
  else
    case "${descriptorMode:-}" in
      docker) configured="$(env_get DOWNLOAD_TIMEOUT_SECONDS 0)" ;;
      manual|systemd) configured="$(conf_get DOWNLOAD_TIMEOUT_SECONDS 0)" ;;
      *)
        if [[ -f "${ENV_FILE}" ]]; then
          configured="$(env_get DOWNLOAD_TIMEOUT_SECONDS 0)"
        elif [[ -f "${CONF}" ]]; then
          configured="$(conf_get DOWNLOAD_TIMEOUT_SECONDS 0)"
        else
          configured=0
        fi ;;
    esac
  fi
  [[ "${configured}" =~ ^(0|[1-9][0-9]*)$ ]] \
    || die "DOWNLOAD_TIMEOUT_SECONDS 必须为非负整数；0 表示不限制总下载时长"
  printf '%s\n' "${configured}"
}

docker_mirror_setting() {
  local configured=""
  if [[ -n "${AID_DOCKER_MIRRORS:-}" ]]; then
    echo "${AID_DOCKER_MIRRORS}"
  elif [[ -n "${AID_DOCKER_CN_MIRROR:-}" ]]; then
    # 兼容旧版单镜像环境变量。
    echo "${AID_DOCKER_CN_MIRROR}"
  else
    case "${descriptorMode}" in
      docker) configured="$(env_get DOCKER_MIRRORS "${DEFAULT_DOCKER_MIRRORS}")" ;;
      manual|systemd) configured="$(conf_get DOCKER_MIRRORS "${DEFAULT_DOCKER_MIRRORS}")" ;;
      *)
        if [[ -f "${ENV_FILE}" ]]; then
          configured="$(env_get DOCKER_MIRRORS "${DEFAULT_DOCKER_MIRRORS}")"
        elif [[ -f "${CONF}" ]]; then
          configured="$(conf_get DOCKER_MIRRORS "${DEFAULT_DOCKER_MIRRORS}")"
        else
          configured="${DEFAULT_DOCKER_MIRRORS}"
        fi ;;
    esac
    echo "${configured:-${DEFAULT_DOCKER_MIRRORS}}"
  fi
}

resolve_dependency_region() {
  [[ -z "${RESOLVED_DEPENDENCY_REGION:-}" ]] || return 0
  local configured country="" url
  configured="$(dependency_region_setting)"
  case "${configured}" in
    cn|global) RESOLVED_DEPENDENCY_REGION="${configured}" ;;
    auto)
      if command -v curl >/dev/null 2>&1; then
        for url in https://ipinfo.io/country https://ifconfig.co/country-iso; do
          country="$(curl --fail --silent --location --connect-timeout 3 --max-time 6 "${url}" 2>/dev/null \
            | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]' | head -c 2 || true)"
          [[ "${country}" =~ ^[A-Z]{2}$ ]] && break
          country=""
        done
      fi
      if [[ "${country}" == "CN" ]]; then
        RESOLVED_DEPENDENCY_REGION=cn
      elif [[ "${country}" =~ ^[A-Z]{2}$ ]]; then
        RESOLVED_DEPENDENCY_REGION=global
      elif command -v curl >/dev/null 2>&1 \
          && curl --fail --silent --show-error --head --connect-timeout 5 --max-time 8 https://github.com >/dev/null 2>&1; then
        RESOLVED_DEPENDENCY_REGION=global
      else
        RESOLVED_DEPENDENCY_REGION=cn
      fi ;;
    *) die "DEPENDENCY_REGION 只支持 auto、cn 或 global" ;;
  esac
  if [[ "${RESOLVED_DEPENDENCY_REGION}" == "cn" ]]; then
    warn "依赖下载已选择国内线路${country:+（出口地区 ${country}）}；国内镜像失败时自动回退官方地址"
  else
    log "依赖下载已选择国际线路${country:+（出口地区 ${country}）}；官方地址失败时自动回退国内镜像"
  fi
}

normalize_docker_mirror() { # normalize_docker_mirror <Registry前缀>
  local mirror="$1"
  mirror="${mirror,,}"
  mirror="${mirror#https://}"
  mirror="${mirror#http://}"
  mirror="${mirror%/}"
  [[ "${mirror}" =~ ^[a-z0-9.-]+(:[0-9]+)?(/[a-z0-9._/-]+)?$ ]] || return 1
  echo "${mirror}"
}

probe_docker_mirror() { # probe_docker_mirror <Registry前缀>；成功输出毫秒
  local mirror="$1" registry result code seconds
  command -v curl >/dev/null 2>&1 || return 1
  registry="${mirror%%/*}"
  result="$(curl --silent --show-error --output /dev/null \
    --connect-timeout 3 --max-time 6 --write-out '%{http_code} %{time_total}' \
    "https://${registry}/v2/" 2>/dev/null || true)"
  code="${result%% *}"
  seconds="${result#* }"
  [[ "${code}" == "200" || "${code}" == "401" ]] || return 1
  awk -v value="${seconds}" 'BEGIN { printf "%d\n", value * 1000 }'
}

# 复用成熟安装器的做法：候选源先做短连接测速，可达源按延迟排序；探测失败的
# 候选仍保留在末尾参与真实 pull，避免 Registry 根接口临时异常造成误判。
resolve_docker_mirror_order() {
  (( DOCKER_MIRRORS_RESOLVED == 0 )) || return 0
  local raw candidate mirror latency ranked="" deferred="" sorted="" seen=" "
  raw="$(docker_mirror_setting)"
  while IFS= read -r candidate; do
    candidate="${candidate//[[:space:]]/}"
    [[ -n "${candidate}" ]] || continue
    mirror="$(normalize_docker_mirror "${candidate}" 2>/dev/null || true)"
    if [[ -z "${mirror}" ]]; then
      warn "已忽略非法 Docker 镜像地址: ${candidate}"
      continue
    fi
    [[ "${seen}" != *" ${mirror} "* ]] || continue
    seen+="${mirror} "
    if latency="$(probe_docker_mirror "${mirror}" 2>/dev/null)"; then
      log "Docker镜像测速: ${mirror} ${latency}ms"
      ranked+="${latency} ${mirror}"$'\n'
    else
      warn "Docker镜像测速不可达，保留为末位重试: ${mirror}"
      deferred+="${mirror} "
    fi
  done < <(printf '%s\n' "${raw}" | tr ',' '\n')
  if [[ -n "${ranked}" ]]; then
    sorted="$(printf '%s' "${ranked}" | sort -n -k1,1 | awk '{printf "%s ", $2}')"
  fi
  DOCKER_MIRROR_ORDER="${sorted}${deferred}"
  DOCKER_MIRROR_ORDER="${DOCKER_MIRROR_ORDER% }"
  DOCKER_MIRRORS_RESOLVED=1
  if [[ -n "${DOCKER_MIRROR_ORDER}" ]]; then
    log "Docker国内镜像尝试顺序: ${DOCKER_MIRROR_ORDER// / -> }"
  else
    warn "未配置有效 Docker 国内镜像，将只尝试 Docker Hub 官方地址"
  fi
}

dockerhub_mirror_image() { # dockerhub_mirror_image <Registry前缀> <标准镜像>
  local mirror="$1" image="$2" first="${2%%/*}"
  if [[ "${image}" == */* ]]; then
    [[ "${first}" != *.* && "${first}" != *:* && "${first}" != "localhost" ]] || return 1
    echo "${mirror}/${image}"
  else
    echo "${mirror}/library/${image}"
  fi
}

# 默认 Docker Hub 镜像固定到发布时核验过的清单摘要。国内镜像按同一摘要拉取，
# 既保留网络回退能力，也避免第三方镜像站返回同名但内容不同的镜像。
docker_image_digest() { # docker_image_digest <标准镜像>
  case "$1" in
    alpine/git:2.47.2) echo 'sha256:062a01ad7a0eb17cff382bc5e26086b4d710e56dfdfdf001109a49b6d9bd378c' ;;
    maven:3.9.9-eclipse-temurin-17) echo 'sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc' ;;
    node:22.22.0-bookworm-slim) echo 'sha256:dd9d21971ec4395903fa6143c2b9267d048ae01ca6d3ea96f16cb30df6187d94' ;;
    golang:1.22.12-bookworm) echo 'sha256:3d699e4d15d0f8f13c9195c0632a16702b8cbdece2955af1c23b37ae5d55a253' ;;
    debian:bookworm-slim) echo 'sha256:7b140f374b289a7c2befc338f42ebe6441b7ea838a042bbd5acbfca6ec875818' ;;
    nginx:1.25-alpine) echo 'sha256:516475cc129da42866742567714ddc681e5eed7b9ee0b9e9c015e464b4221a00' ;;
    docker:27-cli) echo 'sha256:851f91d241214e7c6db86513b270d58776379aacc5eb9c4a87e5b47115e3065c' ;;
    mysql:5.7) echo 'sha256:4bc6bc963e6d8443453676cae56536f4b8156d78bae03c0145cbe47c2aad73bb' ;;
    redis:7-alpine) echo 'sha256:e7723ff73d963f5cc6d9c4643ea3d989527a402a319239054e9472a7fb9219a2' ;;
    apache/rocketmq:5.3.1) echo 'sha256:d2b231c1b9204129e4f4dd65ec1521c81b8d6826e5f1fe8daa521dab0db5bf16' ;;
    *) return 1 ;;
  esac
}

image_with_digest() { # image_with_digest <镜像名:标签> <摘要>
  local image="$1" digest="$2"
  echo "${image%:*}@${digest}"
}

local_image_matches_digest() { # local_image_matches_digest <镜像> <摘要>
  local image="$1" digest="$2"
  docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "${image}" 2>/dev/null \
    | grep -Fq "@${digest}"
}

pull_docker_image() {
  if command -v timeout >/dev/null 2>&1; then
    timeout "${IMAGE_PULL_TIMEOUT_SECONDS}" docker pull "$1"
  else
    docker pull "$1"
  fi
}

probe_docker_image_manifest() { # probe_docker_image_manifest <完整镜像引用>
  if command -v timeout >/dev/null 2>&1; then
    timeout "${IMAGE_MANIFEST_PROBE_TIMEOUT_SECONDS}" docker manifest inspect "$1" >/dev/null 2>&1
  else
    docker manifest inspect "$1" >/dev/null 2>&1
  fi
}

try_docker_mirrors() { # try_docker_mirrors <镜像> <用途> <官方摘要>
  local image="$1" label="$2" digest="$3" mirror mirrorRef candidateOrder
  local readyMirrors="" deferredMirrors=""
  resolve_docker_mirror_order
  # Registry 根接口的延迟只能反映入口连通性。真实镜像可能在后端对象存储或某个
  # 仓库路径上失败，因此再对当前镜像做 manifest 预检；通过者优先，失败者仍
  # 保留在末尾参与真实 pull，避免第三方代理协议差异造成误判。
  for mirror in ${DOCKER_MIRROR_ORDER}; do
    mirrorRef="$(dockerhub_mirror_image "${mirror}" "${image}" 2>/dev/null || true)"
    [[ -n "${mirrorRef}" ]] || continue
    if probe_docker_image_manifest "${mirrorRef}"; then
      log "Docker镜像清单可用: ${mirrorRef}"
      readyMirrors+="${mirror} "
    else
      warn "Docker镜像清单预检失败，保留为末位重试: ${mirrorRef}"
      deferredMirrors+="${mirror} "
    fi
  done
  candidateOrder="${readyMirrors}${deferredMirrors}"
  candidateOrder="${candidateOrder% }"
  for mirror in ${candidateOrder}; do
    mirrorRef="$(dockerhub_mirror_image "${mirror}" "${image}" 2>/dev/null || true)"
    [[ -n "${mirrorRef}" ]] || continue
    log "通过国内镜像下载${label}: ${mirrorRef}"
    if pull_docker_image "${mirrorRef}"; then
      if [[ -z "${digest}" ]] || local_image_matches_digest "${mirrorRef}" "${digest}"; then
        docker tag "${mirrorRef}" "${image}" || die "${label}镜像名称映射失败: ${image}"
        ok "${label}镜像下载成功: ${mirror}"
        return 0
      fi
      warn "${label}镜像摘要与官方发布清单不一致，已拒绝来源: ${mirror}"
      docker image rm "${mirrorRef}" >/dev/null 2>&1 || true
    else
      warn "${label}镜像下载失败，继续下一个来源: ${mirror}"
    fi
  done
  return 1
}

ensure_docker_image() { # ensure_docker_image <镜像> <用途>
  local image="$1" label="$2" installMode="${AID_DEPENDENCY_INSTALL_MODE:-auto}"
  local digest="" officialRef="" mirror mirrorImage
  digest="$(docker_image_digest "${image}" 2>/dev/null || true)"
  if docker image inspect "${image}" >/dev/null 2>&1; then
    if [[ -z "${digest}" ]] || local_image_matches_digest "${image}" "${digest}"; then
      ok "${label}镜像已存在，跳过下载: ${image}"
      return 0
    fi
    warn "${label}本地镜像摘要不符合当前发布清单，将重新拉取已固定版本: ${image}"
  fi
  resolve_dependency_region
  resolve_docker_mirror_order
  if [[ -n "${digest}" ]]; then
    officialRef="$(image_with_digest "${image}" "${digest}")"
  else
    officialRef="${image}"
    warn "${label}使用了自定义或未固定镜像，无法与官方发布摘要核对: ${image}"
  fi
  for mirror in ${DOCKER_MIRROR_ORDER}; do
    mirrorImage="$(dockerhub_mirror_image "${mirror}" "${image}" 2>/dev/null || true)"
    if [[ -n "${mirrorImage}" ]] && docker image inspect "${mirrorImage}" >/dev/null 2>&1 \
        && { [[ -z "${digest}" ]] || local_image_matches_digest "${mirrorImage}" "${digest}"; }; then
      docker tag "${mirrorImage}" "${image}" || die "${label}镜像名称映射失败: ${image}"
      ok "${label}国内镜像缓存有效，已映射为标准名称: ${image}"
      return 0
    fi
  done
  if [[ "${installMode}" == "manual" ]]; then
    die "缺少${label}镜像 ${image}；请从已配置镜像或官方地址手动拉取后重试，或把 DEPENDENCY_INSTALL_MODE 改为 auto"
  fi
  if [[ "${RESOLVED_DEPENDENCY_REGION}" == "cn" ]]; then
    if try_docker_mirrors "${image}" "${label}" "${digest}"; then return 0; fi
    warn "全部国内镜像均失败，自动回退官方地址: ${officialRef}"
  fi
  log "通过官方地址下载${label}: ${officialRef}"
  if pull_docker_image "${officialRef}"; then
    [[ "${officialRef}" == "${image}" ]] || docker tag "${officialRef}" "${image}" \
      || die "${label}镜像名称映射失败: ${image}"
    return 0
  fi
  if [[ "${RESOLVED_DEPENDENCY_REGION}" != "cn" ]]; then
    warn "官方地址下载失败，自动尝试测速后的国内镜像列表"
    if try_docker_mirrors "${image}" "${label}" "${digest}"; then return 0; fi
  fi
  die "${label}镜像下载失败；官方地址和全部国内镜像均不可用: ${image}"
}

set_source_build_mode() { # set_source_build_mode <docker|manual>
  case "$1" in
    docker) export AID_SOURCE_BUILD_MODE="docker" ;;
    manual) export AID_SOURCE_BUILD_MODE="host" ;;
    *) die "未知部署方式，无法选择源码构建模式: $1" ;;
  esac
}

require_source_build_mode() {
  case "${AID_SOURCE_BUILD_MODE:-}" in
    docker|host) return 0 ;;
    '') die "缺少 AID_SOURCE_BUILD_MODE；请通过 AID 官方 Docker 或非 Docker 部署入口执行" ;;
    *) die "AID_SOURCE_BUILD_MODE 仅支持 docker 或 host" ;;
  esac
}

prepare_source_build_images() { # prepare_source_build_images <docker|host>
  local sourceBuildMode="$1"
  case "${sourceBuildMode}" in
    host)
      # 手动/systemd 部署必须只使用宿主机构建工具；即使 Docker 已安装也绝不探测、拉取或调用 Docker。
      return 0
      ;;
    docker)
      command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 \
        || die "Docker 容器源码构建需要可用的 Docker Engine"
      ensure_docker_image "${SOURCE_GIT_IMAGE}" "Git源码拉取"
      ensure_docker_image "${SOURCE_MAVEN_IMAGE}" "Maven构建基础"
      ensure_docker_image "${SOURCE_NODE_IMAGE}" "Node.js 22.22.0构建"
      ensure_docker_image "${SOURCE_GO_IMAGE}" "Go构建"
      ;;
    *) die "未知源码构建模式: ${sourceBuildMode}" ;;
  esac
}

prepare_docker_runtime_images() {
  prepare_jdk_runtime_image
  ensure_docker_image "nginx:1.25-alpine" "Web静态站点与Nginx网关"
  ensure_docker_image "docker:27-cli" "升级器Docker客户端"
  # 内置 MySQL 使用该镜像；外部 MySQL 也需要它作为一次性5.7兼容客户端。
  ensure_docker_image "mysql:5.7" "MySQL5.7"
  docker_profile_enabled redis && ensure_docker_image "redis:7-alpine" "Redis"
  docker_profile_enabled mq && ensure_docker_image "apache/rocketmq:5.3.1" "RocketMQ"
}

install_os_packages() { # install_os_packages <用途> <apt包列表> <rpm包列表>
  local label="$1" aptPackages="$2" rpmPackages="$3" manager=""
  local -a packages=()
  if command -v apt-get >/dev/null 2>&1; then
    manager="apt-get"
    if [[ "${OS_PACKAGE_INDEX_READY}" != "1" ]]; then
      log "刷新系统软件包索引（仅本次自动安装执行一次）..."
      DEBIAN_FRONTEND=noninteractive apt-get update || die "系统软件包索引刷新失败，请切换 DEPENDENCY_INSTALL_MODE=manual 后人工处理"
      OS_PACKAGE_INDEX_READY=1
    fi
    read -ra packages <<< "${aptPackages}"
    log "自动安装缺失依赖 ${label}: ${aptPackages}"
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${packages[@]}" \
      || die "自动安装 ${label} 失败，请改为 manual 后按发行版文档安装"
  elif command -v dnf >/dev/null 2>&1; then
    manager="dnf"; read -ra packages <<< "${rpmPackages}"
    log "自动安装缺失依赖 ${label}: ${rpmPackages}"
    dnf install -y "${packages[@]}" || die "自动安装 ${label} 失败，请改为 manual 后按发行版文档安装"
  elif command -v yum >/dev/null 2>&1; then
    manager="yum"; read -ra packages <<< "${rpmPackages}"
    log "自动安装缺失依赖 ${label}: ${rpmPackages}"
    yum install -y "${packages[@]}" || die "自动安装 ${label} 失败，请改为 manual 后按发行版文档安装"
  else
    die "未识别受支持的软件包管理器，无法自动安装 ${label}；请改为 DEPENDENCY_INSTALL_MODE=manual 后人工安装"
  fi
  ok "${label} 自动安装命令执行完成（${manager}）"
}

ffmpeg_runtime_arch() {
  case "$(uname -m)" in
    x86_64|amd64) printf '%s\n' amd64 ;;
    aarch64|arm64) printf '%s\n' arm64 ;;
    *) return 1 ;;
  esac
}

ffmpeg_runtime_checksum() { # ffmpeg_runtime_checksum <amd64|arm64>
  case "$1" in
    amd64) printf '%s\n' 'abda8d77ce8309141f83ab8edf0596834087c52467f6badf376a6a2a4c87cf67' ;;
    arm64) printf '%s\n' 'f4149bb2b0784e30e99bdda85471c9b5930d3402014e934a5098b41d0f7201b1' ;;
    *) return 1 ;;
  esac
}

configure_ffmpeg_runtime_paths() {
  FFMPEG_RUNTIME_ARCH="$(ffmpeg_runtime_arch 2>/dev/null || true)"
  [[ -n "${FFMPEG_RUNTIME_ARCH}" ]] \
    || die "AID FFmpeg ${FFMPEG_RUNTIME_VERSION} 暂不支持当前架构: $(uname -m)"
  FFMPEG_RUNTIME_HOME="${FFMPEG_RUNTIME_ROOT}/ffmpeg-${FFMPEG_RUNTIME_VERSION}-${FFMPEG_RUNTIME_ARCH}"
  FFMPEG_RUNTIME_FFMPEG="${FFMPEG_RUNTIME_ROOT}/current/ffmpeg"
  FFMPEG_RUNTIME_FFPROBE="${FFMPEG_RUNTIME_ROOT}/current/ffprobe"
}

write_ffmpeg_runtime_checker() { # write_ffmpeg_runtime_checker <target>
  local target="$1"
  cat > "${target}" <<'AID_FFMPEG_CHECKER'
#!/bin/sh
set -eu

ffmpeg_path="$1"
ffprobe_path="$2"
work_root="$3"
minimum_version="$4"
required_encoders="$5"
required_filters="$6"
expected_version="${7:-}"

fail() {
  printf '[FFmpeg校验失败] %s\n' "$1" >&2
  exit 1
}

[ -x "${ffmpeg_path}" ] || fail "FFmpeg不可执行: ${ffmpeg_path}"
[ -x "${ffprobe_path}" ] || fail "FFprobe不可执行: ${ffprobe_path}"
mkdir -p "${work_root}" || fail "无法创建检测目录: ${work_root}"

version_line="$(${ffmpeg_path} -version 2>&1 | head -n 1 || true)"
current_version="$(printf '%s\n' "${version_line}" | sed -n 's/^ffmpeg version[[:space:]]\+[^0-9]*\([0-9][0-9.]*\).*/\1/p')"
version_issue=""
minimum_major="${minimum_version%%.*}"
minimum_minor="${minimum_version#*.}"
if [ -z "${current_version}" ]; then
  current_version="无法识别"
  version_issue="版本输出异常 ${version_line:-空}"
else
  current_major="${current_version%%.*}"
  current_rest="${current_version#*.}"
  current_minor="${current_rest%%.*}"
  case "${current_version}" in
    *.*) ;;
    *) version_issue="版本格式异常" ;;
  esac
  case "${current_major}:${current_minor}:${minimum_major}:${minimum_minor}" in
    *[!0-9:]*|'') version_issue="版本格式异常" ;;
  esac
  if [ -z "${version_issue}" ] && { [ "${current_major}" -lt "${minimum_major}" ] \
      || { [ "${current_major}" -eq "${minimum_major}" ] && [ "${current_minor}" -lt "${minimum_minor}" ]; }; }; then
    version_issue="版本过低"
  fi
  if [ -n "${expected_version}" ] && [ "${current_version}" != "${expected_version}" ]; then
    version_issue="固定版本不匹配，要求 ${expected_version}"
  fi
fi
ffprobe_issue=""
${ffprobe_path} -version >/dev/null 2>&1 || ffprobe_issue="FFprobe无法运行 ${ffprobe_path}"

check_dir="$(mktemp -d "${work_root%/}/.ffmpeg-check.XXXXXX")" \
  || fail "无法创建FFmpeg临时检测目录"
cleanup() { rm -rf -- "${check_dir}"; }
trap cleanup EXIT INT TERM

missing_encoders=""
if ! ${ffmpeg_path} -hide_banner -encoders >"${check_dir}/encoders.txt" 2>&1; then
  missing_encoders="无法读取"
else
  for name in ${required_encoders}; do
    if ! grep -Eq "[[:space:]]${name}([[:space:]]|$)" "${check_dir}/encoders.txt"; then
      missing_encoders="${missing_encoders}${missing_encoders:+,}${name}"
    fi
  done
fi

missing_filters=""
if ! ${ffmpeg_path} -hide_banner -filters >"${check_dir}/filters.txt" 2>&1; then
  missing_filters="无法读取"
else
  for name in ${required_filters}; do
    if ! grep -Eq "[[:space:]]${name}([[:space:]]|$)" "${check_dir}/filters.txt"; then
      missing_filters="${missing_filters}${missing_filters:+,}${name}"
    fi
  done
fi
if [ -n "${version_issue}${ffprobe_issue}${missing_encoders}${missing_filters}" ]; then
  fail "能力不完整；当前 ${current_version}；最低 ${minimum_version}；版本 ${version_issue:-符合}；FFprobe ${ffprobe_issue:-正常}；缺少编码器 ${missing_encoders:-无}；缺少滤镜 ${missing_filters:-无}"
fi

output_file="${check_dir}/aid-ffmpeg-smoke.mp4"
${ffmpeg_path} -hide_banner -loglevel error -nostdin -y \
  -f lavfi -i 'testsrc2=size=160x90:rate=25:duration=0.6' \
  -f lavfi -i 'anullsrc=sample_rate=48000:channel_layout=stereo' \
  -filter_complex '[0:v]tpad=stop_mode=clone:stop_duration=0.4,scale=160:90:force_original_aspect_ratio=decrease,pad=160:90:(ow-iw)/2:(oh-ih)/2,fps=25,setpts=PTS-STARTPTS[v];[1:a]apad=whole_dur=1,atrim=duration=1,asetpts=PTS-STARTPTS,aformat=sample_rates=48000:channel_layouts=stereo[a]' \
  -map '[v]' -map '[a]' -t 1 -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart "${output_file}" \
  || fail "最小合成失败；当前 ${current_version}；最低 ${minimum_version}"
[ -s "${output_file}" ] || fail "最小合成未生成有效MP4"
${ffprobe_path} -v error -select_streams v:0 -show_entries stream=codec_type -of csv=p=0 "${output_file}" \
  | grep -Fxq video || fail "最小合成缺少视频流"
${ffprobe_path} -v error -select_streams a:0 -show_entries stream=codec_type -of csv=p=0 "${output_file}" \
  | grep -Fxq audio || fail "最小合成缺少音频流"
printf '[FFmpeg校验通过] 当前 %s；最低 %s\n' "${current_version}" "${minimum_version}"
AID_FFMPEG_CHECKER
  chmod 700 "${target}"
}

run_ffmpeg_runtime_check() { # run_ffmpeg_runtime_check <ffmpeg> <ffprobe> <work-root> [exact-version]
  local ffmpegPath="$1" ffprobePath="$2" workRoot="$3" exactVersion="${4:-}"
  local checker="" output="" status=0
  mkdir -p "${workRoot}" || return 1
  checker="$(mktemp "${workRoot%/}/.ffmpeg-checker.XXXXXX")" || return 1
  write_ffmpeg_runtime_checker "${checker}"
  output="$("${checker}" "${ffmpegPath}" "${ffprobePath}" "${workRoot}" \
    "${FFMPEG_MIN_VERSION}" "${FFMPEG_REQUIRED_ENCODERS}" "${FFMPEG_REQUIRED_FILTERS}" \
    "${exactVersion}" 2>&1)" || status=$?
  rm -f -- "${checker}"
  if [[ "${status}" -ne 0 ]]; then
    [[ -z "${output}" ]] || printf '%s\n' "${output}" >&2
    return "${status}"
  fi
  [[ -z "${output}" ]] || printf '%s\n' "${output}"
}

ffmpeg_runtime_usable() {
  local resolvedCurrent="" versionLine="" exactVersion="" expectedArchive="" storedArchive=""
  local storedFfmpeg="" storedFfprobe="" storedChecker=""
  configure_ffmpeg_runtime_paths
  if [[ "$#" -eq 0 ]]; then
    [[ -L "${FFMPEG_RUNTIME_ROOT}/current" && ! -L "${FFMPEG_RUNTIME_HOME}" ]] || return 1
    resolvedCurrent="$(readlink -f -- "${FFMPEG_RUNTIME_ROOT}/current" 2>/dev/null || true)"
    [[ "${resolvedCurrent}" == "${FFMPEG_RUNTIME_HOME}" ]] || return 1
    [[ -x "${FFMPEG_RUNTIME_HOME}/check-runtime.sh" \
        && -f "${FFMPEG_RUNTIME_HOME}/archive.sha256" \
        && -f "${FFMPEG_RUNTIME_HOME}/runtime-integrity.sha256" ]] || return 1
    expectedArchive="$(ffmpeg_runtime_checksum "${FFMPEG_RUNTIME_ARCH}" 2>/dev/null || true)"
    storedArchive="$(head -n 1 "${FFMPEG_RUNTIME_HOME}/archive.sha256" | tr -d '[:space:]')"
    storedFfmpeg="$(sed -n 's/[[:space:]]\+ffmpeg$//p' "${FFMPEG_RUNTIME_HOME}/runtime-integrity.sha256" | head -n 1)"
    storedFfprobe="$(sed -n 's/[[:space:]]\+ffprobe$//p' "${FFMPEG_RUNTIME_HOME}/runtime-integrity.sha256" | head -n 1)"
    storedChecker="$(sed -n 's/[[:space:]]\+check-runtime\.sh$//p' "${FFMPEG_RUNTIME_HOME}/runtime-integrity.sha256" | head -n 1)"
    [[ -n "${expectedArchive}" && "${storedArchive}" == "${expectedArchive}" \
        && -n "${storedFfmpeg}" && -n "${storedFfprobe}" && -n "${storedChecker}" \
        && "$(sha256_file "${FFMPEG_RUNTIME_HOME}/ffmpeg" 2>/dev/null || true)" == "${storedFfmpeg}" \
        && "$(sha256_file "${FFMPEG_RUNTIME_HOME}/ffprobe" 2>/dev/null || true)" == "${storedFfprobe}" \
        && "$(sha256_file "${FFMPEG_RUNTIME_HOME}/check-runtime.sh" 2>/dev/null || true)" == "${storedChecker}" ]] || return 1
    versionLine="$("${FFMPEG_RUNTIME_FFMPEG}" -version 2>/dev/null | head -n 1 || true)"
    [[ "${versionLine}" == "ffmpeg version ${FFMPEG_RUNTIME_VERSION}"* ]] || return 1
    exactVersion="${FFMPEG_RUNTIME_VERSION}"
  fi
  run_ffmpeg_runtime_check "${1:-${FFMPEG_RUNTIME_FFMPEG}}" \
    "${2:-${FFMPEG_RUNTIME_FFPROBE}}" "${3:-${FFMPEG_RUNTIME_ROOT}/checks}" "${exactVersion}"
}

ffmpeg_runtime_download_urls() { # ffmpeg_runtime_download_urls <amd64|arm64>
  local arch="$1" name="" url="" seen=$'\n'
  local custom="" gitee="" tencent="" aliyun="" github=""
  local -a candidates=()
  name="ffmpeg-${FFMPEG_RUNTIME_VERSION}-${arch}-static.tar.xz"
  gitee="https://gitee.com/gzxx-2025/aid-server/releases/download/${FFMPEG_RUNTIME_MIRROR_TAG}/${name}"
  github="https://github.com/publicala/ffmpeg-static/releases/download/v${FFMPEG_RUNTIME_VERSION}/${name}"
  case "${arch}" in
    amd64)
      custom="${AID_FFMPEG_PRIMARY_URL_AMD64:-}"
      tencent="${AID_FFMPEG_TENCENT_URL_AMD64:-}"
      aliyun="${AID_FFMPEG_ALIYUN_URL_AMD64:-}" ;;
    arm64)
      custom="${AID_FFMPEG_PRIMARY_URL_ARM64:-}"
      tencent="${AID_FFMPEG_TENCENT_URL_ARM64:-}"
      aliyun="${AID_FFMPEG_ALIYUN_URL_ARM64:-}" ;;
  esac
  candidates=("${custom}" "${gitee}" "${tencent}" "${aliyun}" "${github}")
  for url in "${candidates[@]}"; do
    [[ -n "${url}" && "${seen}" != *$'\n'"${url}"$'\n'* ]] || continue
    printf '%s\n' "${url}"
    seen+="${url}"$'\n'
  done
}

install_ffmpeg_runtime_version() ( # isolated subshell guarantees temporary cleanup on every exit
  local arch="$1" checksum="$2" runtimeRoot target current workDir archive
  local staged backup linkTmp downloaded="no" url ffmpegSource="" ffprobeSource="" hadBackup="no"
  local -a urls=()
  runtimeRoot="${FFMPEG_RUNTIME_ROOT}"
  target="${runtimeRoot}/ffmpeg-${FFMPEG_RUNTIME_VERSION}-${arch}"
  current="${runtimeRoot}/current"
  [[ ! -L "${runtimeRoot}" ]] || exit 1
  mkdir -p "${runtimeRoot}" || exit 1
  [[ ! -L "${runtimeRoot}" && -d "${runtimeRoot}" ]] || exit 1
  workDir="$(mktemp -d "${runtimeRoot}/.ffmpeg-install.XXXXXX")" || exit 1
  trap 'rm -rf -- "${workDir}" "${staged:-}" "${linkTmp:-}"' EXIT INT TERM
  archive="${workDir}/ffmpeg-${FFMPEG_RUNTIME_VERSION}-${arch}-static.tar.xz"

  mapfile -t urls < <(ffmpeg_runtime_download_urls "${arch}")
  for url in "${urls[@]}"; do
    [[ -n "${url}" ]] || continue
    rm -f -- "${archive}" "${archive}.part"
    if try_download "${url}" "${archive}" "AID FFmpeg ${FFMPEG_RUNTIME_VERSION}（${arch}）" sha256 "${checksum}" \
        && [[ "$(sha256_file "${archive}" 2>/dev/null || true)" == "${checksum}" ]]; then
      downloaded="yes"
      break
    fi
    warn "FFmpeg 当前下载地址不可用或 SHA256 不匹配，未触碰现有运行时，切换备用地址"
  done
  [[ "${downloaded}" == "yes" ]] || exit 1

  mkdir -p "${workDir}/extract" || exit 1
  if tar -tJf "${archive}" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
    warn "FFmpeg 压缩包包含越界路径，已拒绝解压"
    exit 1
  fi
  tar -xJf "${archive}" -C "${workDir}/extract" || exit 1
  ffmpegSource="$(find "${workDir}/extract" -type f -name ffmpeg -perm -u+x -print -quit 2>/dev/null || true)"
  ffprobeSource="$(find "${workDir}/extract" -type f -name ffprobe -perm -u+x -print -quit 2>/dev/null || true)"
  [[ -n "${ffmpegSource}" && -n "${ffprobeSource}" ]] || exit 1

  staged="${runtimeRoot}/.ffmpeg-${FFMPEG_RUNTIME_VERSION}-${arch}.staged.$$"
  mkdir -m 755 "${staged}" || exit 1
  install -m 755 "${ffmpegSource}" "${staged}/ffmpeg" || exit 1
  install -m 755 "${ffprobeSource}" "${staged}/ffprobe" || exit 1
  write_ffmpeg_runtime_checker "${staged}/check-runtime.sh"
  run_ffmpeg_runtime_check "${staged}/ffmpeg" "${staged}/ffprobe" "${workDir}/validation" \
    "${FFMPEG_RUNTIME_VERSION}" || exit 1
  printf '%s\n' "${checksum}" > "${staged}/archive.sha256"
  chmod 644 "${staged}/archive.sha256"
  {
    printf '%s  ffmpeg\n' "$(sha256_file "${staged}/ffmpeg")"
    printf '%s  ffprobe\n' "$(sha256_file "${staged}/ffprobe")"
    printf '%s  check-runtime.sh\n' "$(sha256_file "${staged}/check-runtime.sh")"
  } > "${staged}/runtime-integrity.sha256"
  chmod 644 "${staged}/runtime-integrity.sha256"

  backup="${target}.previous.$$"
  if [[ -e "${target}" || -L "${target}" ]]; then
    [[ ! -L "${target}" && -d "${target}" ]] || exit 1
    mv -- "${target}" "${backup}" || exit 1
    hadBackup="yes"
  fi
  if ! mv -- "${staged}" "${target}"; then
    [[ ! -e "${backup}" ]] || mv -- "${backup}" "${target}" || true
    exit 1
  fi
  staged=""
  if [[ -e "${current}" && ! -L "${current}" ]]; then
    rm -rf -- "${target}"
    [[ "${hadBackup}" != "yes" ]] || mv -- "${backup}" "${target}"
    exit 1
  fi
  linkTmp="${current}.tmp.$$"
  if ! ln -s "$(basename "${target}")" "${linkTmp}" \
      || ! mv -Tf -- "${linkTmp}" "${current}"; then
    rm -f -- "${linkTmp}"
    rm -rf -- "${target}"
    [[ "${hadBackup}" != "yes" ]] || mv -- "${backup}" "${target}"
    exit 1
  fi
  linkTmp=""
  if [[ -e "${backup}" ]] && ! rm -rf -- "${backup}"; then
    warn "FFmpeg 新运行时已启用，但旧版本备份清理失败: ${backup}"
  fi
)

prepare_ffmpeg_runtime() {
  local installMode="$1" arch checksum
  configure_ffmpeg_runtime_paths
  if ffmpeg_runtime_usable; then
    ok "AID FFmpeg ${FFMPEG_RUNTIME_VERSION} 运行时已存在且能力完整，跳过下载: ${FFMPEG_RUNTIME_HOME}"
    print_ffmpeg_runtime_paths
    return 0
  fi
  warn "AID FFmpeg 运行时缺失或能力不完整，将保留旧版本并重新安装固定版本 ${FFMPEG_RUNTIME_VERSION}"
  ensure_host_command xz "XZ解压工具" "xz-utils" "xz" "${installMode}"
  ensure_host_command tar "Tar解压工具" "tar" "tar" "${installMode}"
  require_download_tools
  arch="${FFMPEG_RUNTIME_ARCH}"
  checksum="$(ffmpeg_runtime_checksum "${arch}" 2>/dev/null || true)"
  [[ -n "${checksum}" ]] || die "缺少 FFmpeg ${FFMPEG_RUNTIME_VERSION} ${arch} 固定 SHA256"
  install_ffmpeg_runtime_version "${arch}" "${checksum}" \
    || die "AID FFmpeg ${FFMPEG_RUNTIME_VERSION} 安装失败；现有运行时与 current 链接均未替换，请检查下载线路后重试"
  ffmpeg_runtime_usable \
    || die "AID FFmpeg ${FFMPEG_RUNTIME_VERSION} 安装后能力复检失败；current 链接未指向不可用运行时"
  ok "AID FFmpeg ${FFMPEG_RUNTIME_VERSION} 已通过 SHA256、能力与最小合成校验"
  print_ffmpeg_runtime_paths
}

print_ffmpeg_runtime_paths() {
  configure_ffmpeg_runtime_paths
  echo "  后台推荐 FFmpeg路径 : ${FFMPEG_RUNTIME_FFMPEG}"
  echo "  后台推荐 FFprobe路径: ${FFMPEG_RUNTIME_FFPROBE}"
}

write_aid_cjk_font_manager() { # write_aid_cjk_font_manager <target>
  local target="$1"
  cat > "${target}" <<EOF
#!/usr/bin/env bash
set -euo pipefail
FONT_ROOT='${AID_FONT_ROOT}'
FONT_CURRENT='${AID_FONT_CURRENT}'
FONT_PATH='${AID_CJK_FONT_PATH}'
FONT_VERSION='${AID_CJK_FONT_VERSION}'
FONT_ARCHIVE='${AID_CJK_FONT_ARCHIVE}'
FONT_FILE='${AID_CJK_FONT_FILE}'
FONT_SHA256='${AID_CJK_FONT_SHA256}'
FONT_FILE_SHA256='${AID_CJK_FONT_FILE_SHA256}'
DEFAULT_FFMPEG_PATH='${FFMPEG_RUNTIME_FFMPEG}'
DEFAULT_FFPROBE_PATH='${FFMPEG_RUNTIME_FFPROBE}'
EOF
  cat >> "${target}" <<'AID_CJK_FONT_MANAGER'

FFMPEG_PATH="${AID_FFMPEG_PATH:-${DEFAULT_FFMPEG_PATH}}"
FFPROBE_PATH="${AID_FFPROBE_PATH:-${DEFAULT_FFPROBE_PATH}}"
OFFICIAL_URL="https://github.com/notofonts/noto-cjk/releases/download/Sans2.004/${FONT_ARCHIVE}"
ALIYUN_URL="${AID_CJK_FONT_ALIYUN_URL:-https://mirrors.aliyun.com/github/releases/googlefonts/noto-cjk/Sans2.004/${FONT_ARCHIVE}}"
TENCENT_URL="${AID_CJK_FONT_TENCENT_URL:-}"
ACTION="${1:-prepare}"
WORK_DIR=""
STAGED=""
LINK_TMP=""
SOURCE_TMP=""

font_log() { printf '[AID字体] %s\n' "$*"; }
font_warn() { printf '[AID字体][提示] %s\n' "$*" >&2; }
font_fail() { printf '[AID字体][失败] %s\n' "$*" >&2; exit 1; }

cleanup_font_manager() {
  [ -z "${WORK_DIR}" ] || rm -rf -- "${WORK_DIR}"
  [ -z "${STAGED}" ] || rm -rf -- "${STAGED}"
  [ -z "${LINK_TMP}" ] || rm -f -- "${LINK_TMP}"
  [ -z "${SOURCE_TMP}" ] || rm -f -- "${SOURCE_TMP}"
}
trap cleanup_font_manager EXIT INT TERM

sha256_path() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print tolower($1)}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print tolower($1)}'
  else
    font_fail "缺少SHA256校验工具"
  fi
}

charset_contains_codepoint() { # charset_contains_codepoint <fontconfig charset> <hex>
  local charset="$1" wantedHex="$2" token start end wanted
  wanted=$((16#${wantedHex}))
  for token in ${charset}; do
    case "${token}" in
      *-*)
        start="${token%%-*}"; end="${token#*-}"
        [[ "${start}" =~ ^[0-9a-fA-F]+$ && "${end}" =~ ^[0-9a-fA-F]+$ ]] || continue
        if (( 16#${start} <= wanted && wanted <= 16#${end} )); then return 0; fi
        ;;
      *)
        [[ "${token}" =~ ^[0-9a-fA-F]+$ ]] || continue
        if (( 16#${token} == wanted )); then return 0; fi
        ;;
    esac
  done
  return 1
}

font_file_has_required_cjk() { # font_file_has_required_cjk <font>
  local font="$1" resolved="" languages="" charset="" codepoint
  [[ -e "${font}" && -r "${font}" ]] || return 1
  resolved="$(readlink -f -- "${font}" 2>/dev/null || true)"
  [[ -n "${resolved}" && -f "${resolved}" && -r "${resolved}" ]] || return 1
  languages="$(fc-query --format='%{lang}\n' -- "${resolved}" 2>/dev/null || true)"
  printf '%s\n' "${languages}" | grep -Eiq '(^|[|,[:space:]])zh(-[a-z]{2})?([|,[:space:]]|$)' || return 1
  charset="$(fc-query --format='%{charset}\n' -- "${resolved}" 2>/dev/null || true)"
  [[ -n "${charset}" ]] || return 1
  # “中文测试，字幕正常。”逐字校验，避免只按字体名称或lang元数据误判。
  for codepoint in 4e2d 6587 6d4b 8bd5 ff0c 5b57 5e55 6b63 5e38 3002; do
    charset_contains_codepoint "${charset}" "${codepoint}" || return 1
  done
}

escape_drawtext_font_path() {
  printf '%s' "$1" | sed "s/\\\\/\\\\\\\\/g; s/:/\\\\:/g; s/'/\\\\\\\\'/g; s/,/\\\\,/g; s/;/\\\\;/g; s/\\[/\\\\[/g; s/\\]/\\\\]/g"
}

font_drawtext_works() { # font_drawtext_works <canonical font path>
  local font="$1" escaped output
  [[ -x "${FFMPEG_PATH}" && -x "${FFPROBE_PATH}" ]] || return 1
  escaped="$(escape_drawtext_font_path "${font}")"
  output="${WORK_DIR}/aid-cjk-font-smoke.mp4"
  rm -f -- "${output}"
  "${FFMPEG_PATH}" -hide_banner -loglevel error -nostdin -y \
    -f lavfi -i 'color=c=black:s=320x180:d=0.5' \
    -vf "drawtext=fontfile='${escaped}':text='中文测试，字幕正常。':fontcolor=white:fontsize=24:x=10:y=10" \
    -an -t 0.5 -c:v libx264 -pix_fmt yuv420p "${output}" >/dev/null 2>&1 || return 1
  [[ -s "${output}" ]] || return 1
  "${FFPROBE_PATH}" -v error -select_streams v:0 -show_entries stream=codec_type \
    -of csv=p=0 "${output}" 2>/dev/null | grep -Fxq video
}

current_font_valid() {
  [[ -L "${FONT_PATH}" ]] || return 1
  font_file_has_required_cjk "${FONT_PATH}" || return 1
  font_drawtext_works "${FONT_PATH}"
}

record_font_source() { # record_font_source <system|aid>
  SOURCE_TMP="${FONT_CURRENT}/.aid-source.tmp.$$"
  printf '%s\n' "$1" > "${SOURCE_TMP}"
  chmod 644 "${SOURCE_TMP}"
  mv -Tf -- "${SOURCE_TMP}" "${FONT_CURRENT}/.aid-source"
  SOURCE_TMP=""
}

restore_previous_link() { # restore_previous_link <old target> <old source>
  local oldTarget="$1" oldSource="$2"
  rm -f -- "${FONT_PATH}"
  if [[ -n "${oldTarget}" ]]; then
    LINK_TMP="${FONT_CURRENT}/.aid-cjk-font.restore.$$"
    ln -s "${oldTarget}" "${LINK_TMP}"
    mv -Tf -- "${LINK_TMP}" "${FONT_PATH}"
    LINK_TMP=""
  fi
  if [[ -n "${oldSource}" ]]; then
    record_font_source "${oldSource}"
  else
    rm -f -- "${FONT_CURRENT}/.aid-source"
  fi
}

activate_font() { # activate_font <absolute target> <system|aid>
  local candidate="$1" source="$2" oldTarget="" oldSource=""
  [[ -d "${FONT_CURRENT}" && ! -L "${FONT_CURRENT}" ]] || return 1
  [[ ! -e "${FONT_PATH}" || -L "${FONT_PATH}" ]] || return 1
  [[ ! -e "${FONT_CURRENT}/.aid-source" || ( -f "${FONT_CURRENT}/.aid-source" && ! -L "${FONT_CURRENT}/.aid-source" ) ]] \
    || return 1
  [[ -L "${FONT_PATH}" ]] && oldTarget="$(readlink -- "${FONT_PATH}" 2>/dev/null || true)"
  [[ -f "${FONT_CURRENT}/.aid-source" ]] && IFS= read -r oldSource < "${FONT_CURRENT}/.aid-source" || true
  LINK_TMP="${FONT_CURRENT}/.aid-cjk-font.tmp.$$"
  ln -s "${candidate}" "${LINK_TMP}" || return 1
  mv -Tf -- "${LINK_TMP}" "${FONT_PATH}" || return 1
  LINK_TMP=""
  if ! current_font_valid; then
    restore_previous_link "${oldTarget}" "${oldSource}"
    return 1
  fi
  record_font_source "${source}"
}

font_rank() { # font_rank <family> <style>
  local family style rank
  family="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  style="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
  case "${family}" in
    *emoji*|*symbol*|*icon*|*awesome*|*material*) return 1 ;;
    *noto*sans*cjk*sc*) rank=10 ;;
    *source*han*sans*cn*|*source*han*sans*sc*) rank=20 ;;
    *wenquanyi*micro*hei*) rank=30 ;;
    *sans*|*hei*|*gothic*) rank=40 ;;
    *) return 1 ;;
  esac
  case "${style}" in *regular*|*normal*|*book*) ;; *) rank=$((rank + 5)) ;; esac
  printf '%s\n' "${rank}"
}

find_and_activate_system_font() {
  local family style file rank resolved candidates ranked
  candidates="${WORK_DIR}/fontconfig-candidates.tsv"
  ranked="${WORK_DIR}/ranked-fonts.tsv"
  fc-list :lang=zh -f '%{family[0]}\t%{style[0]}\t%{file}\n' > "${candidates}" 2>/dev/null || return 1
  : > "${ranked}"
  while IFS=$'\t' read -r family style file; do
    [[ -n "${file}" ]] || continue
    rank="$(font_rank "${family}" "${style}" 2>/dev/null || true)"
    [[ -n "${rank}" ]] || continue
    resolved="$(readlink -f -- "${file}" 2>/dev/null || true)"
    [[ -n "${resolved}" ]] || continue
    printf '%03d\t%s\n' "${rank}" "${resolved}" >> "${ranked}"
  done < "${candidates}"
  sort -n -k1,1 "${ranked}" | cut -f2- | awk '!seen[$0]++' > "${ranked}.unique"
  while IFS= read -r file; do
    [[ -n "${file}" ]] || continue
    font_file_has_required_cjk "${file}" || continue
    if activate_font "${file}" system; then
      printf 'AID_FONT_RESULT=reused-system\n'
      printf 'AID_FONT_SELECTED=%s\n' "${file}"
      return 0
    fi
  done < "${ranked}.unique"
  return 1
}

download_font_archive() { # download_font_archive <url> <target>
  local url="$1" target="$2"
  rm -f -- "${target}" "${target}.part"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --retry-delay 2 --connect-timeout 15 --max-time 600 \
      -o "${target}.part" "${url}" || return 1
  elif command -v wget >/dev/null 2>&1; then
    wget --tries=3 --timeout=30 -O "${target}.part" "${url}" || return 1
  else
    return 1
  fi
  [[ "$(sha256_path "${target}.part")" == "${FONT_SHA256}" ]] || return 1
  mv -f -- "${target}.part" "${target}"
}

install_aid_font() {
  local target="${FONT_ROOT}/${FONT_VERSION}" archive="${WORK_DIR}/${FONT_ARCHIVE}"
  local extract="${WORK_DIR}/extract" backup="${target}.previous.$$" downloaded=no url
  if [[ -d "${target}" && ! -L "${target}" && -f "${target}/LICENSE" \
      && -f "${target}/archive.sha256" \
      && -f "${target}/font.sha256" \
      && "$(head -n 1 "${target}/archive.sha256" 2>/dev/null | tr -d '[:space:]')" == "${FONT_SHA256}" \
      && "$(head -n 1 "${target}/font.sha256" 2>/dev/null | tr -d '[:space:]')" == "${FONT_FILE_SHA256}" \
      && "$(sha256_path "${target}/${FONT_FILE}" 2>/dev/null || true)" == "${FONT_FILE_SHA256}" ]] \
      && font_file_has_required_cjk "${target}/${FONT_FILE}"; then
    activate_font "${target}/${FONT_FILE}" aid || return 1
    printf 'AID_FONT_RESULT=installed-aid\n'
    printf 'AID_FONT_SELECTED=%s\n' "${target}/${FONT_FILE}"
    return 0
  fi

  for url in "${TENCENT_URL}" "${ALIYUN_URL}" "${OFFICIAL_URL}"; do
    [[ -n "${url}" ]] || continue
    font_log "下载固定字体 ${FONT_VERSION}: ${url}"
    if download_font_archive "${url}" "${archive}"; then
      downloaded=yes
      break
    fi
    font_warn "当前字体地址不可用或SHA256不匹配，切换下一地址"
  done
  [[ "${downloaded}" == yes ]] || return 1
  if unzip -Z1 "${archive}" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
    font_warn "字体压缩包包含越界路径，拒绝解压"
    return 1
  fi
  mkdir -p "${extract}"
  unzip -q "${archive}" "${FONT_FILE}" LICENSE -d "${extract}" || return 1
  [[ -f "${extract}/${FONT_FILE}" && -f "${extract}/LICENSE" ]] || return 1
  [[ "$(sha256_path "${extract}/${FONT_FILE}")" == "${FONT_FILE_SHA256}" ]] || return 1
  font_file_has_required_cjk "${extract}/${FONT_FILE}" || return 1

  STAGED="${FONT_ROOT}/.${FONT_VERSION}.staged.$$"
  mkdir -m 755 "${STAGED}" || return 1
  install -m 644 "${extract}/${FONT_FILE}" "${STAGED}/${FONT_FILE}" || return 1
  install -m 644 "${extract}/LICENSE" "${STAGED}/LICENSE" || return 1
  printf '%s\n' "${FONT_SHA256}" > "${STAGED}/archive.sha256"
  chmod 644 "${STAGED}/archive.sha256"
  printf '%s\n' "${FONT_FILE_SHA256}" > "${STAGED}/font.sha256"
  chmod 644 "${STAGED}/font.sha256"
  cat > "${STAGED}/NOTICE" <<EOF
Noto Sans SC ${FONT_VERSION}
Official release: ${OFFICIAL_URL}
Installed file: ${FONT_FILE}
Archive SHA256: ${FONT_SHA256}
Font SHA256: ${FONT_FILE_SHA256}
Copyright and trademark notices are embedded in the original font file.
License: SIL Open Font License 1.1; see LICENSE in this directory.
EOF
  chmod 644 "${STAGED}/NOTICE"

  if [[ -e "${target}" || -L "${target}" ]]; then
    [[ -d "${target}" && ! -L "${target}" ]] || return 1
    mv -- "${target}" "${backup}" || return 1
  fi
  if ! mv -- "${STAGED}" "${target}"; then
    [[ ! -e "${backup}" ]] || mv -- "${backup}" "${target}" || true
    return 1
  fi
  STAGED=""
  if ! activate_font "${target}/${FONT_FILE}" aid; then
    rm -rf -- "${target}"
    [[ ! -e "${backup}" ]] || mv -- "${backup}" "${target}" || true
    return 1
  fi
  [[ ! -e "${backup}" ]] || rm -rf -- "${backup}"
  printf 'AID_FONT_RESULT=installed-aid\n'
  printf 'AID_FONT_SELECTED=%s\n' "${target}/${FONT_FILE}"
}

[[ "${ACTION}" == prepare || "${ACTION}" == validate ]] \
  || font_fail "只支持prepare或validate"
command -v fc-list >/dev/null 2>&1 && command -v fc-query >/dev/null 2>&1 \
  || font_fail "缺少fontconfig基础工具fc-list/fc-query"
[[ -x "${FFMPEG_PATH}" && -x "${FFPROBE_PATH}" ]] \
  || font_fail "FFmpeg或FFprobe不可执行"
[[ ! -L "${FONT_ROOT}" ]] || font_fail "字体根目录不能是软链接: ${FONT_ROOT}"
mkdir -p "${FONT_ROOT}"
[[ -d "${FONT_ROOT}" && ! -L "${FONT_ROOT}" ]] || font_fail "字体根目录无效: ${FONT_ROOT}"
[[ ! -L "${FONT_CURRENT}" ]] || font_fail "字体current必须是目录而不是软链接: ${FONT_CURRENT}"
mkdir -p "${FONT_CURRENT}"
chmod 755 "${FONT_ROOT}" "${FONT_CURRENT}"
[[ ! -e "${FONT_PATH}" || -L "${FONT_PATH}" ]] \
  || font_fail "中文字体稳定入口已被普通文件占用，拒绝覆盖: ${FONT_PATH}"
[[ ! -e "${FONT_CURRENT}/.aid-source" \
    || ( -f "${FONT_CURRENT}/.aid-source" && ! -L "${FONT_CURRENT}/.aid-source" ) ]] \
  || font_fail "字体来源标记不是受支持的普通文件: ${FONT_CURRENT}/.aid-source"
WORK_DIR="$(mktemp -d "${FONT_ROOT}/.font-manager.XXXXXX")" \
  || font_fail "无法创建字体临时目录"

if current_font_valid; then
  sourceType="$(head -n 1 "${FONT_CURRENT}/.aid-source" 2>/dev/null || true)"
  case "${sourceType}" in
    system) printf 'AID_FONT_RESULT=reused-system\n' ;;
    *) printf 'AID_FONT_RESULT=installed-aid\n' ;;
  esac
  printf 'AID_FONT_SELECTED=%s\n' "$(readlink -f -- "${FONT_PATH}")"
  exit 0
fi
[[ "${ACTION}" == prepare ]] || font_fail "AID中文字体稳定入口无效: ${FONT_PATH}"
if find_and_activate_system_font; then
  exit 0
fi
install_aid_font || font_fail "未发现可用系统中文字体，固定字体下载、校验或安装失败"
current_font_valid || font_fail "中文字体初始化后复检失败"
AID_CJK_FONT_MANAGER
  chmod 700 "${target}"
}

prepare_aid_cjk_font() { # prepare_aid_cjk_font <auto|manual>
  local installMode="$1" manager output sourceType selected
  ensure_host_command fc-list "Fontconfig中文字体检测工具" "fontconfig" "fontconfig" "${installMode}"
  command -v fc-query >/dev/null 2>&1 \
    || die "Fontconfig安装后仍缺少fc-query，无法验证中文字符集"
  ensure_host_command unzip "ZIP解压工具" "unzip" "unzip" "${installMode}"
  require_download_tools
  mkdir -p "${DATA_ROOT}/build-cache/toolchains"
  manager="$(mktemp "${DATA_ROOT}/build-cache/toolchains/.aid-cjk-font.XXXXXX")" \
    || die "无法创建中文字体管理脚本"
  write_aid_cjk_font_manager "${manager}"
  [[ ! -L "${AID_FONT_ROOT}" ]] \
    || { rm -f -- "${manager}"; die "字体根目录不能是软链接: ${AID_FONT_ROOT}"; }
  mkdir -p "${AID_FONT_ROOT}"
  [[ -d "${AID_FONT_ROOT}" && ! -L "${AID_FONT_ROOT}" ]] \
    || { rm -f -- "${manager}"; die "字体根目录无效: ${AID_FONT_ROOT}"; }
  [[ ! -e "${AID_FONT_ROOT}/check-font.sh" \
      || ( -f "${AID_FONT_ROOT}/check-font.sh" && ! -L "${AID_FONT_ROOT}/check-font.sh" ) ]] \
    || { rm -f -- "${manager}"; die "字体校验脚本路径被非普通文件占用: ${AID_FONT_ROOT}/check-font.sh"; }
  install -m 700 "${manager}" "${AID_FONT_ROOT}/check-font.sh" \
    || { rm -f -- "${manager}"; die "中文字体校验脚本落盘失败"; }
  rm -f -- "${manager}"
  output="$(AID_FFMPEG_PATH="${FFMPEG_RUNTIME_FFMPEG}" AID_FFPROBE_PATH="${FFMPEG_RUNTIME_FFPROBE}" \
    AID_CJK_FONT_TENCENT_URL="${AID_CJK_FONT_TENCENT_URL:-}" \
    AID_CJK_FONT_ALIYUN_URL="${AID_CJK_FONT_ALIYUN_URL:-}" \
    "${AID_FONT_ROOT}/check-font.sh" prepare)" \
    || die "AID中文字体自动检测与初始化失败"
  printf '%s\n' "${output}"
  sourceType="$(head -n 1 "${AID_FONT_CURRENT}/.aid-source" 2>/dev/null || true)"
  selected="$(readlink -f -- "${AID_CJK_FONT_PATH}" 2>/dev/null || true)"
  if [[ "${sourceType}" == "system" ]]; then
    ok "中文字体：已复用系统字体（${selected}）"
  else
    ok "中文字体：已安装AID字体（${selected}）"
  fi
  echo "  后台推荐 中文字体路径: ${AID_CJK_FONT_PATH}"
}

ensure_host_command() { # ensure_host_command <命令> <用途> <apt包列表> <rpm包列表> <安装模式>
  local commandName="$1" label="$2" aptPackages="$3" rpmPackages="$4" installMode="$5"
  if command -v "${commandName}" >/dev/null 2>&1; then
    ok "${label} 已安装，跳过"
    return 0
  fi
  [[ "${installMode}" == "auto" ]] \
    || die "缺少 ${label}（命令 ${commandName}）；请人工安装后重试，或设置 DEPENDENCY_INSTALL_MODE=auto"
  install_os_packages "${label}" "${aptPackages}" "${rpmPackages}"
  command -v "${commandName}" >/dev/null 2>&1 || die "已执行安装但仍未找到 ${commandName}，请检查系统 PATH"
}

ensure_git_runtime() {
  local installMode="$1" version=""
  if command -v git >/dev/null 2>&1; then
    version="$(git --version 2>/dev/null | sed -nE 's/^git version ([0-9]+(\.[0-9]+)+).*/\1/p')"
    if [[ -n "${version}" ]] && version_at_least "${version}" "${GIT_MIN_VERSION}"; then
      ok "Git ${version} 已存在且版本符合，跳过安装"
      return 0
    fi
    warn "现有 Git ${version:-未知} 低于要求 ${GIT_MIN_VERSION}，将通过系统包管理器升级"
  fi
  [[ "${installMode}" == "auto" ]] \
    || die "缺少 Git ${GIT_MIN_VERSION}+；请人工安装后重试，或设置 DEPENDENCY_INSTALL_MODE=auto"
  install_os_packages "Git ${GIT_MIN_VERSION}+" "git" "git"
  version="$(git --version 2>/dev/null | sed -nE 's/^git version ([0-9]+(\.[0-9]+)+).*/\1/p')"
  [[ -n "${version}" ]] && version_at_least "${version}" "${GIT_MIN_VERSION}" \
    || die "系统软件源提供的 Git ${version:-未知} 仍低于 ${GIT_MIN_VERSION}，请升级发行版软件源后重试"
  ok "Git ${version} 已安装且版本符合"
}

nginx_binary_version() { # nginx_binary_version <二进制路径>
  "$1" -v 2>&1 | sed -nE 's#^nginx version: nginx/([0-9]+(\.[0-9]+)+).*#\1#p'
}

select_existing_nginx_candidate() { # select_existing_nginx_candidate <二进制路径>
  local candidate="$1" version
  [[ -n "${candidate}" && -x "${candidate}" ]] || return 1
  version="$(nginx_binary_version "${candidate}")"
  [[ -n "${version}" ]] && version_at_least "${version}" "${NGINX_MIN_VERSION}" || return 1
  NGINX_BIN="${candidate}"
  if [[ "${candidate}" == "${NGINX_HOME}/sbin/nginx" ]]; then
    NGINX_SERVICE="${NGINX_MANAGED_SERVICE}"
    NGINX_SITE_DIR="${CONFIG_ROOT}/nginx/conf.d"
  elif [[ "${candidate}" == /www/server/nginx/* ]]; then
    NGINX_SERVICE="nginx.service"
    NGINX_SITE_DIR="/www/server/panel/vhost/nginx"
  else
    NGINX_SERVICE="nginx.service"
    NGINX_SITE_DIR="/etc/nginx/conf.d"
  fi
  ok "Nginx ${version} 已存在且版本符合，跳过下载和编译: ${candidate}"
}

select_existing_nginx_runtime() {
  local systemNginx=""
  NGINX_HOME="${DATA_ROOT}/runtime/nginx-${NGINX_VERSION}"
  select_existing_nginx_candidate "${NGINX_HOME}/sbin/nginx" && return 0
  systemNginx="$(command -v nginx 2>/dev/null || true)"
  if [[ -n "${systemNginx}" ]]; then
    select_existing_nginx_candidate "${systemNginx}" && return 0
  fi
  select_existing_nginx_candidate /www/server/nginx/sbin/nginx && return 0
  return 1
}

ensure_nginx_build_dependencies() {
  local installMode="$1"
  ensure_host_command gcc "C编译器" "build-essential" "gcc" "${installMode}"
  ensure_host_command make "Make构建工具" "build-essential" "make" "${installMode}"
  if [[ ! -f /usr/include/pcre2.h && ! -f /usr/include/pcre.h ]]; then
    [[ "${installMode}" == "auto" ]] || die "编译 Nginx ${NGINX_VERSION} 需要 PCRE 开发库"
    install_os_packages "Nginx PCRE开发库" "libpcre2-dev" "pcre2-devel"
  else
    ok "Nginx PCRE 开发库已存在，跳过安装"
  fi
  if [[ ! -f /usr/include/zlib.h ]]; then
    [[ "${installMode}" == "auto" ]] || die "编译 Nginx ${NGINX_VERSION} 需要 zlib 开发库"
    install_os_packages "Nginx zlib开发库" "zlib1g-dev" "zlib-devel"
  else
    ok "Nginx zlib 开发库已存在，跳过安装"
  fi
  if [[ ! -f /usr/include/openssl/ssl.h ]]; then
    [[ "${installMode}" == "auto" ]] || die "编译 Nginx ${NGINX_VERSION} 需要 OpenSSL 开发库"
    install_os_packages "Nginx OpenSSL开发库" "libssl-dev" "openssl-devel"
  else
    ok "Nginx OpenSSL 开发库已存在，跳过安装"
  fi
}

prepare_managed_nginx() {
  local installMode="$1" name checksum cacheDir archive downloaded=no url sourceDir buildLog jobs
  local -a urls=()
  name="nginx-${NGINX_VERSION}.tar.gz"
  checksum="4261dc90e9e47c1c4041276e9aaa3d48ebe2e664f728e14fa95ae6c67d57a08b"
  cacheDir="${DATA_ROOT}/build-cache/toolchains"
  archive="${cacheDir}/${name}"
  NGINX_HOME="${DATA_ROOT}/runtime/nginx-${NGINX_VERSION}"
  NGINX_BIN="${NGINX_HOME}/sbin/nginx"
  NGINX_SERVICE="${NGINX_MANAGED_SERVICE}"
  NGINX_SITE_DIR="${CONFIG_ROOT}/nginx/conf.d"
  buildLog="${DATA_ROOT}/logs/nginx/build-${NGINX_VERSION}.log"
  require_download_tools
  mkdir -p "${cacheDir}" "${DATA_ROOT}/runtime" "$(dirname "${buildLog}")"
  if [[ -f "${archive}" ]] && ! file_digest_matches "${archive}" sha256 "${checksum}"; then
    warn "Nginx 缓存校验失败，将重新下载"
    rm -f -- "${archive}"
  fi
  if [[ ! -f "${archive}" ]]; then
    [[ "${installMode}" == "auto" ]] \
      || die "缺少 Nginx ${NGINX_VERSION}；请人工安装后重试，或设置 DEPENDENCY_INSTALL_MODE=auto"
    mapfile -t urls < <(
      [[ -z "${AID_NGINX_DOWNLOAD_URL:-}" ]] || printf '%s\n' "${AID_NGINX_DOWNLOAD_URL}"
      bt_artifact_urls "src/${name}"
      printf '%s\n' "https://nginx.org/download/${name}"
    )
    for url in "${urls[@]}"; do
      [[ -n "${url}" ]] || continue
      if try_download "${url}" "${archive}" "Nginx ${NGINX_VERSION}" sha256 "${checksum}"; then
        downloaded=yes
        break
      fi
      warn "Nginx 当前节点不可用或摘要不匹配，切换下一个 AID 镜像/官方节点"
    done
    [[ "${downloaded}" == "yes" ]] || die "Nginx ${NGINX_VERSION} 下载失败或校验不通过"
  fi
  ensure_nginx_build_dependencies "${installMode}"
  sourceDir="${cacheDir}/nginx-source-${NGINX_VERSION}.tmp.$$"
  rm -rf -- "${sourceDir}"
  mkdir -p "${sourceDir}" "${NGINX_SITE_DIR}" "${DATA_ROOT}/run/nginx" "${DATA_ROOT}/logs/nginx"
  tar -xzf "${archive}" -C "${sourceDir}" --strip-components=1 \
    || { rm -rf -- "${sourceDir}"; die "Nginx 压缩包解压失败"; }
  jobs="$(nproc 2>/dev/null || echo 2)"; [[ "${jobs}" =~ ^[0-9]+$ ]] || jobs=2
  (( jobs > 4 )) && jobs=4
  log "编译 Nginx ${NGINX_VERSION}，完整日志: ${buildLog}"
  rm -rf -- "${NGINX_HOME}"
  if ! (cd "${sourceDir}" && ./configure \
      --prefix="${NGINX_HOME}" \
      --sbin-path="${NGINX_BIN}" \
      --conf-path="${NGINX_HOME}/conf/nginx.conf" \
      --pid-path="${DATA_ROOT}/run/nginx/nginx.pid" \
      --error-log-path="${DATA_ROOT}/logs/nginx/error.log" \
      --http-log-path="${DATA_ROOT}/logs/nginx/access.log" \
      --with-http_ssl_module --with-http_v2_module --with-http_realip_module \
      --with-http_gzip_static_module --with-threads >"${buildLog}" 2>&1 \
      && make -j "${jobs}" >>"${buildLog}" 2>&1 \
      && make install >>"${buildLog}" 2>&1); then
    rm -rf -- "${sourceDir}" "${NGINX_HOME}"
    die "Nginx ${NGINX_VERSION} 源码编译失败，请查看 ${buildLog}"
  fi
  rm -rf -- "${sourceDir}"
  [[ -x "${NGINX_BIN}" ]] && [[ "$(nginx_binary_version "${NGINX_BIN}")" == "${NGINX_VERSION}" ]] \
    || die "Nginx 编译产物不完整或实际版本不正确"
  cat > "${NGINX_HOME}/conf/nginx.conf" <<EOF
worker_processes auto;
pid ${DATA_ROOT}/run/nginx/nginx.pid;
error_log ${DATA_ROOT}/logs/nginx/error.log warn;

events { worker_connections 4096; }

http {
    include ${NGINX_HOME}/conf/mime.types;
    default_type application/octet-stream;
    access_log ${DATA_ROOT}/logs/nginx/access.log;
    sendfile on;
    keepalive_timeout 65;
    include ${NGINX_SITE_DIR}/*.conf;
}
EOF
  cat > "/etc/systemd/system/${NGINX_MANAGED_SERVICE}" <<EOF
# AID_MANAGED_UNIT=1
# AID_DATA_ROOT=${DATA_ROOT}
[Unit]
Description=AID managed Nginx ${NGINX_VERSION}
After=network-online.target

[Service]
Type=simple
ExecStartPre=${NGINX_BIN} -t -c ${NGINX_HOME}/conf/nginx.conf
ExecStart=${NGINX_BIN} -c ${NGINX_HOME}/conf/nginx.conf -g 'daemon off;'
ExecReload=${NGINX_BIN} -c ${NGINX_HOME}/conf/nginx.conf -s reload
Restart=on-failure
RestartSec=5
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF
  chmod 644 "/etc/systemd/system/${NGINX_MANAGED_SERVICE}"
  systemctl daemon-reload
  command -v nginx >/dev/null 2>&1 || ln -s "${NGINX_BIN}" /usr/local/bin/nginx
  ok "Nginx ${NGINX_VERSION} 已通过固定 SHA256 校验并安装为 ${NGINX_MANAGED_SERVICE}"
}

ensure_nginx_runtime() {
  local installMode="$1" existing="" version=""
  if select_existing_nginx_runtime; then return 0; fi
  if command -v nginx >/dev/null 2>&1; then
    existing="$(command -v nginx)"; version="$(nginx_binary_version "${existing}")"
    if systemctl is-active --quiet nginx.service 2>/dev/null; then
      die "现有 Nginx ${version:-未知} 低于 ${NGINX_MIN_VERSION} 且正在运行；脚本不会覆盖现有站点，请先人工升级或停止后重试"
    fi
    warn "现有 Nginx ${version:-未知} 低于 ${NGINX_MIN_VERSION}；将安装隔离的 AID Nginx ${NGINX_VERSION}"
  fi
  [[ "${installMode}" == "auto" ]] \
    || die "缺少 Nginx ${NGINX_MIN_VERSION}+；请人工安装后重试，或设置 DEPENDENCY_INSTALL_MODE=auto"
  prepare_managed_nginx "${installMode}"
}

nginx_runtime_active() {
  if [[ "${NGINX_SERVICE}" == "${NGINX_MANAGED_SERVICE}" ]]; then
    systemctl is-active --quiet "${NGINX_SERVICE}" 2>/dev/null
    return $?
  fi
  [[ -n "${NGINX_SERVICE}" ]] && systemctl is-active --quiet "${NGINX_SERVICE}" 2>/dev/null && return 0
  [[ -n "${NGINX_BIN}" ]] && pgrep -x nginx >/dev/null 2>&1
}

start_nginx_runtime() {
  nginx_runtime_active && return 0
  [[ -n "${NGINX_SERVICE}" ]] \
    || die "Nginx 已安装但未识别到服务，请先启动 ${NGINX_BIN:-nginx}"
  systemctl enable --now "${NGINX_SERVICE}" >/dev/null 2>&1 \
    || die "Nginx 自动启动失败，请执行 systemctl status ${NGINX_SERVICE} 排查"
  ok "Nginx 已启用并启动: ${NGINX_SERVICE}"
}

reload_nginx_runtime() {
  "${NGINX_BIN}" -t >/dev/null 2>&1 || return 1
  if [[ -n "${NGINX_SERVICE}" ]] && systemctl reload "${NGINX_SERVICE}" >/dev/null 2>&1; then return 0; fi
  "${NGINX_BIN}" -s reload >/dev/null 2>&1
}

version_at_least() { # version_at_least <当前版本> <最低版本>
  local current="$1" required="$2" first
  first="$(printf '%s\n%s\n' "${required}" "${current}" | sort -V | head -n 1)"
  [[ "${first}" == "${required}" ]]
}

tcp_reachable() { # tcp_reachable <host> <port>
  if command -v timeout >/dev/null 2>&1; then
    timeout 3 bash -c 'exec 3<>/dev/tcp/"$1"/"$2"' _ "$1" "$2" >/dev/null 2>&1
  elif command -v curl >/dev/null 2>&1; then
    curl --silent --output /dev/null --connect-timeout 3 --max-time 3 "telnet://$1:$2" >/dev/null 2>&1
  else
    return 1
  fi
}

rocketmq_nameserver_entries() { # rocketmq_nameserver_entries <host:port[;host:port]>
  printf '%s\n' "$1" | tr ';,' '\n' | sed '/^[[:space:]]*$/d; s/^[[:space:]]*//; s/[[:space:]]*$//'
}

rocketmq_config_error() { # rocketmq_config_error <docker|manual> <错误说明>
  local mode="$1" message="$2" configPath
  if [[ "${mode}" == "docker" ]]; then configPath="${ENV_FILE}"; else configPath="${CONF}"; fi
  err "${message}"
  echo -e "  ${C_CYAN}请修改配置文件${C_RESET}: ${configPath}" >&2
  if [[ "${mode}" == "docker" ]]; then
    echo "  关闭 MQ   : COMPOSE_PROFILES 删除 mq，ROCKETMQ_ENABLED=false" >&2
    echo "  内置 MQ   : COMPOSE_PROFILES 加入 mq，ROCKETMQ_ENABLED=true，ROCKETMQ_NAMESERVER=rocketmq-nameserver:9876" >&2
    echo "  宿主机 MQ : COMPOSE_PROFILES 删除 mq，ROCKETMQ_ENABLED=true，ROCKETMQ_NAMESERVER=host.docker.internal:9876" >&2
    echo "  外部 MQ   : COMPOSE_PROFILES 删除 mq，ROCKETMQ_ENABLED=true，ROCKETMQ_NAMESERVER=内网IP或DNS:9876" >&2
    echo "  修改完成后重新执行当前命令；已有部署也可执行 sudo aid restart。" >&2
  else
    echo "  关闭 MQ   : ROCKETMQ_ENABLED=false" >&2
    echo "  外部 MQ   : ROCKETMQ_ENABLED=true，ROCKETMQ_NAMESERVER=内网IP或DNS:9876" >&2
    echo "  手动部署不会自动安装 RocketMQ；修改后重新执行当前命令或 sudo aid restart。" >&2
  fi
  exit 1
}

validate_rocketmq_nameserver_format() { # validate_rocketmq_nameserver_format <值> [docker|manual]
  local value="$1" mode="${2:-}" entry host port count=0
  while IFS= read -r entry; do
    if [[ ! "${entry}" =~ ^([A-Za-z0-9._-]+):([0-9]+)$ ]]; then
      [[ -z "${mode}" ]] && die "ROCKETMQ_NAMESERVER 必须使用 host:port，多个地址用分号分隔"
      rocketmq_config_error "${mode}" "ROCKETMQ_NAMESERVER 必须使用 host:port，多个地址用分号分隔"
    fi
    host="${BASH_REMATCH[1]}"; port="${BASH_REMATCH[2]}"
    if [[ -z "${host}" || ! "${port}" =~ ^[0-9]+$ || ${#port} -gt 5 ]] \
        || (( 10#${port} < 1 || 10#${port} > 65535 )); then
      [[ -z "${mode}" ]] && die "RocketMQ NameServer 的主机或端口无效"
      rocketmq_config_error "${mode}" "RocketMQ NameServer 的主机或端口无效: ${entry}"
    fi
    count=$((count + 1))
  done < <(rocketmq_nameserver_entries "${value}")
  if (( count == 0 )); then
    [[ -z "${mode}" ]] && die "启用 RocketMQ 时至少配置一个 NameServer"
    rocketmq_config_error "${mode}" "启用 RocketMQ 时至少配置一个 NameServer"
  fi
}

rocketmq_setting() { # rocketmq_setting <docker|manual> <key> <默认值>
  if [[ "$1" == "docker" ]]; then env_get "$2" "${3:-}"; else conf_get "$2" "${3:-}"; fi
}

validate_rocketmq_mode() { # validate_rocketmq_mode <docker|manual>
  local mode="$1" enabled nameserver entry host
  enabled="$(rocketmq_setting "${mode}" ROCKETMQ_ENABLED false)"
  if [[ "${mode}" == "docker" ]] && docker_profile_enabled mq && [[ "${enabled}" != "true" ]]; then
    rocketmq_config_error docker "COMPOSE_PROFILES 包含 mq，但 ROCKETMQ_ENABLED 不是 true"
  fi
  [[ "${enabled}" == "true" ]] || return 0
  nameserver="$(rocketmq_setting "${mode}" ROCKETMQ_NAMESERVER '')"
  validate_rocketmq_nameserver_format "${nameserver}" "${mode}"
  if [[ "${mode}" == "docker" ]]; then
    if docker_profile_enabled mq; then
      [[ "${nameserver}" == "rocketmq-nameserver:9876" ]] \
        || rocketmq_config_error docker "已启用内置 RocketMQ，但 NameServer 地址不是 rocketmq-nameserver:9876"
    else
      [[ "${nameserver}" != *"rocketmq-nameserver:"* ]] \
        || rocketmq_config_error docker "当前未启用内置 mq Profile，不能使用容器服务名 rocketmq-nameserver"
      while IFS= read -r entry; do
        host="${entry%:*}"
        case "${host,,}" in
          127.0.0.1|localhost)
            rocketmq_config_error docker "Docker 中的 ${entry} 指向业务容器自身；MQ 在宿主机时请改用 host.docker.internal:${entry##*:}" ;;
        esac
      done < <(rocketmq_nameserver_entries "${nameserver}")
    fi
  fi
}

docker_tcp_reachable() { # docker_tcp_reachable <host> <port>；必须从容器网络视角探测
  local host="$1" port="$2" probeName="aid-mq-probe-$$-${RANDOM}" result=1
  if command -v timeout >/dev/null 2>&1; then
    timeout 12 docker run --rm --name "${probeName}" --pull=never --network bridge \
      --add-host host.docker.internal:host-gateway "${JAVA_RUNTIME_IMAGE}" \
      bash -c 'exec 3<>/dev/tcp/"$1"/"$2"' _ "${host}" "${port}" >/dev/null 2>&1 && result=0
  else
    docker run --rm --name "${probeName}" --pull=never --network bridge \
      --add-host host.docker.internal:host-gateway "${JAVA_RUNTIME_IMAGE}" \
      bash -c 'exec 3<>/dev/tcp/"$1"/"$2"' _ "${host}" "${port}" >/dev/null 2>&1 && result=0
  fi
  docker rm -f "${probeName}" >/dev/null 2>&1 || true
  return "${result}"
}

check_external_rocketmq_connectivity() { # check_external_rocketmq_connectivity <docker|manual>
  local mode="$1" enabled nameserver entry host port reachable=0 failed="" configPath networkLabel
  enabled="$(rocketmq_setting "${mode}" ROCKETMQ_ENABLED false)"
  [[ "${enabled}" == "true" ]] || { ok "RocketMQ 未启用，跳过中间件校验"; return 0; }
  if [[ "${mode}" == "docker" ]] && docker_profile_enabled mq; then return 0; fi
  nameserver="$(rocketmq_setting "${mode}" ROCKETMQ_NAMESERVER '')"
  validate_rocketmq_nameserver_format "${nameserver}" "${mode}"
  if [[ "${mode}" == "docker" ]]; then
    configPath="${ENV_FILE}"; networkLabel="Docker容器网络"
  else
    configPath="${CONF}"; networkLabel="当前服务器"
  fi
  while IFS= read -r entry; do
    host="${entry%:*}"; port="${entry##*:}"
    if [[ "${mode}" == "docker" ]] && docker_tcp_reachable "${host}" "${port}"; then
      ok "RocketMQ NameServer 可从 AID 容器网络访问: ${entry}"
      reachable=$((reachable + 1))
    elif [[ "${mode}" != "docker" ]] && tcp_reachable "${host}" "${port}"; then
      ok "RocketMQ NameServer 可达: ${entry}"
      reachable=$((reachable + 1))
    else
      failed+="${entry} "
    fi
  done < <(rocketmq_nameserver_entries "${nameserver}")
  if (( reachable == 0 )); then
    err "RocketMQ NameServer 从${networkLabel}全部不可达: ${failed% }"
    echo "  请修改配置文件: ${configPath}" >&2
    if [[ "${mode}" == "docker" ]]; then
      echo "  MQ 在宿主机：使用 host.docker.internal:9876，并确保 NameServer 监听宿主机可访问地址。" >&2
      echo "  MQ 在其他服务器：使用容器可路由的内网 IP/DNS；Broker 的 brokerIP1 也必须能被容器访问。" >&2
      echo "  不使用 MQ：从 COMPOSE_PROFILES 删除 mq，并设置 ROCKETMQ_ENABLED=false。" >&2
    else
      echo "  请确认防火墙、NameServer 监听地址和端口；脚本不会自动安装 RocketMQ。" >&2
    fi
    exit 1
  fi
  [[ -z "${failed}" ]] || warn "部分 RocketMQ NameServer 不可达，将使用其余节点: ${failed% }"
}

install_mysql_runtime_libraries() {
  local installMode="$1"
  if command -v ldconfig >/dev/null 2>&1 \
      && ldconfig -p 2>/dev/null | grep -q 'libaio\.so\.1' \
      && ldconfig -p 2>/dev/null | grep -q 'libnuma\.so\.1'; then
    ok "MySQL 5.7 运行库已存在，跳过安装"
    return 0
  fi
  [[ "${installMode}" == "auto" ]] || die "本机 MySQL 5.7 缺少运行库，请先安装 libaio 与 libnuma"
  if command -v apt-get >/dev/null 2>&1; then
    if [[ "${OS_PACKAGE_INDEX_READY}" != "1" ]]; then
      DEBIAN_FRONTEND=noninteractive apt-get update || die "MySQL 运行库的软件包索引刷新失败"
      OS_PACKAGE_INDEX_READY=1
    fi
    if apt-cache show libaio1 >/dev/null 2>&1; then
      DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends libaio1 libnuma1 \
        || die "MySQL 5.7 运行库安装失败"
    else
      DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends libaio1t64 libnuma1 \
        || die "MySQL 5.7 运行库安装失败"
    fi
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y libaio numactl-libs || die "MySQL 5.7 运行库安装失败"
  elif command -v yum >/dev/null 2>&1; then
    yum install -y libaio numactl-libs || die "MySQL 5.7 运行库安装失败"
  else
    die "无法自动安装 MySQL 运行库（libaio、libnuma）"
  fi
}

install_mysql_compat_libraries() {
  local installMode="$1"
  [[ "${installMode}" == "auto" ]] \
    || die "MySQL 5.7 缺少 libtinfo/libncurses 兼容库，请人工安装后重试"
  if command -v apt-get >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends libtinfo5 \
      || DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends libncurses5 \
      || die "MySQL 5.7 的 libtinfo.so.5 兼容库安装失败"
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y ncurses-compat-libs || dnf install -y ncurses-libs \
      || die "MySQL 5.7 的 ncurses 兼容库安装失败"
  elif command -v yum >/dev/null 2>&1; then
    yum install -y ncurses-compat-libs || yum install -y ncurses-libs \
      || die "MySQL 5.7 的 ncurses 兼容库安装失败"
  else
    die "无法自动安装 MySQL 5.7 的 libtinfo 兼容库"
  fi
}

gpg_primary_key_fingerprint() { # gpg_primary_key_fingerprint <公钥文件>，兼容 CentOS 7 的旧版 GnuPG
  local keyFile="$1" inspectHome fingerprint=""
  inspectHome="$(mktemp -d)"; chmod 700 "${inspectHome}"
  if GNUPGHOME="${inspectHome}" gpg --batch --quiet --import "${keyFile}" >/dev/null 2>&1; then
    fingerprint="$(GNUPGHOME="${inspectHome}" gpg --batch --with-colons --fingerprint 2>/dev/null \
      | awk -F: '$1=="fpr" {print toupper($10); exit}')"
  fi
  rm -rf -- "${inspectHome}"
  printf '%s\n' "${fingerprint}"
}

mysql_gpg_key_fingerprint() { # mysql_gpg_key_fingerprint <公钥文件>
  gpg_primary_key_fingerprint "$1"
}

verify_mysql_archive_signature() { # verify_mysql_archive_signature <归档> <文件名> <缓存目录> [摘要说明]
  local archive="$1" name="$2" cacheDir="$3" digestLabel="${4:-Oracle 官方 MD5}"
  local keyFile signatureFile fingerprint gpgHome
  local expected="859BE8D7C586F538430B19C2467B942D3A79BD29"
  keyFile="${cacheDir}/RPM-GPG-KEY-mysql-2022"
  signatureFile="${archive}.asc"
  ensure_host_command gpg "GnuPG签名工具" "gnupg" "gnupg2" "${AID_DEPENDENCY_INSTALL_MODE:-auto}"
  if [[ ! -s "${keyFile}" ]]; then
    try_download "https://repo.mysql.com/RPM-GPG-KEY-mysql-2022" "${keyFile}" "MySQL官方GPG公钥" \
      || die "MySQL 官方 GPG 公钥下载失败"
  fi
  fingerprint="$(mysql_gpg_key_fingerprint "${keyFile}")"
  if [[ "${fingerprint}" != "${expected}" ]]; then
    warn "MySQL GPG 公钥缓存指纹异常，删除缓存并从 Oracle 官方地址重新获取"
    rm -f -- "${keyFile}" "${keyFile}.part"
    try_download "https://repo.mysql.com/RPM-GPG-KEY-mysql-2022" "${keyFile}" "MySQL官方GPG公钥" \
      || die "MySQL 官方 GPG 公钥重新下载失败"
    fingerprint="$(mysql_gpg_key_fingerprint "${keyFile}")"
  fi
  [[ "${fingerprint}" == "${expected}" ]] \
    || die "MySQL 官方 GPG 公钥重新下载后指纹仍不匹配"
  if [[ ! -s "${signatureFile}" ]]; then
    try_download "https://downloads.mysql.com/archives/gpg/?file=${name}&p=23" "${signatureFile}" "MySQL归档GPG签名" \
      || die "MySQL 官方归档签名下载失败"
  fi
  gpgHome="$(mktemp -d)"; chmod 700 "${gpgHome}"
  GNUPGHOME="${gpgHome}" gpg --batch --quiet --import "${keyFile}" >/dev/null 2>&1 \
    || { rm -rf -- "${gpgHome}"; die "MySQL GPG 公钥导入失败"; }
  # 临时 keyring 中只导入了上方已核对主指纹的 Oracle 公钥；签名可由其认证子密钥完成，
  # 因此以 gpg 的完整验签退出码为准，不能把 VALIDSIG 的签名子密钥指纹误当成主指纹。
  if ! GNUPGHOME="${gpgHome}" gpg --batch --verify "${signatureFile}" "${archive}" >/dev/null 2>&1; then
    warn "MySQL GPG 签名缓存校验失败，删除缓存并重新下载官方签名"
    rm -f -- "${signatureFile}" "${signatureFile}.part"
    if ! try_download "https://downloads.mysql.com/archives/gpg/?file=${name}&p=23" \
        "${signatureFile}" "MySQL归档GPG签名" \
        || ! GNUPGHOME="${gpgHome}" gpg --batch --verify "${signatureFile}" "${archive}" >/dev/null 2>&1; then
      rm -rf -- "${gpgHome}"
      die "MySQL ${MYSQL_VERSION} GPG 签名校验失败，已拒绝安装"
    fi
  fi
  rm -rf -- "${gpgHome}"
  ok "MySQL ${MYSQL_VERSION} 已通过 ${digestLabel} 与 Oracle GPG 双重校验"
}

ensure_mysql_source_build_dependencies() {
  local installMode="$1"
  ensure_host_command gcc "C编译器" "build-essential" "gcc" "${installMode}"
  ensure_host_command g++ "C++编译器" "build-essential" "gcc-c++" "${installMode}"
  ensure_host_command make "Make构建工具" "build-essential" "make" "${installMode}"
  ensure_host_command cmake "CMake构建工具" "cmake" "cmake" "${installMode}"
  ensure_host_command bison "Bison构建工具" "bison" "bison" "${installMode}"
  ensure_host_command perl "Perl运行时" "perl" "perl" "${installMode}"
  if [[ ! -f /usr/include/ncurses.h && ! -f /usr/include/ncurses/ncurses.h \
      && ! -f /usr/include/ncursesw/ncurses.h ]]; then
    [[ "${installMode}" == "auto" ]] || die "编译 MySQL 5.7 需要 ncurses 开发库"
    install_os_packages "MySQL ncurses开发库" "libncurses-dev" "ncurses-devel"
  else
    ok "MySQL ncurses 开发库已存在，跳过安装"
  fi
  if [[ ! -f /usr/include/openssl/ssl.h ]]; then
    [[ "${installMode}" == "auto" ]] || die "编译 MySQL 5.7 需要 OpenSSL 开发库"
    install_os_packages "MySQL OpenSSL开发库" "libssl-dev" "openssl-devel"
  else
    ok "MySQL OpenSSL 开发库已存在，跳过安装"
  fi
}

prepare_managed_mysql_from_bt_source() { # Oracle 二进制入口不可用时的最终兜底
  local installMode="$1" cacheDir="$2" sourceName sourceArchive checksum downloaded=no url
  local sourceRoot buildDir installDir buildLog jobs memoryKb memoryJobs freeKb
  local -a urls=()
  sourceName="mysql-boost-${MYSQL_VERSION}.tar.gz"
  sourceArchive="${cacheDir}/${sourceName}"
  checksum="b8fe262c4679cb7bbc379a3f1addc723844db168628ce2acf78d33906849e491"
  buildLog="${DATA_ROOT}/logs/mysql/source-build-${MYSQL_VERSION}.log"
  if [[ -f "${sourceArchive}" ]] && ! file_digest_matches "${sourceArchive}" sha256 "${checksum}"; then
    warn "MySQL 源码缓存 SHA256 不匹配，将重新下载"
    rm -f -- "${sourceArchive}"
  fi
  if [[ ! -f "${sourceArchive}" ]]; then
    mapfile -t urls < <(bt_artifact_urls "src/${sourceName}")
    urls+=("https://downloads.mysql.com/archives/get/p/23/file/${sourceName}")
    for url in "${urls[@]}"; do
      if try_download "${url}" "${sourceArchive}" "MySQL ${MYSQL_VERSION} 官方源包" sha256 "${checksum}"; then
        downloaded=yes
        break
      fi
      warn "MySQL 源包当前节点不可用或校验失败，切换下一个 AID 镜像/Oracle 节点"
    done
    [[ "${downloaded}" == "yes" ]] || die "MySQL ${MYSQL_VERSION} 二进制与 AID 镜像源包入口均不可用"
  fi
  verify_mysql_archive_signature "${sourceArchive}" "${sourceName}" "${cacheDir}" "Oracle 固定 SHA256"
  install_mysql_runtime_libraries "${installMode}"
  ensure_mysql_source_build_dependencies "${installMode}"
  freeKb="$(df -Pk "${cacheDir}" 2>/dev/null | awk 'NR==2 {print $4}')"
  [[ "${freeKb}" =~ ^[0-9]+$ && "${freeKb}" -ge 6291456 ]] \
    || die "MySQL 源码兜底编译至少需要 6 GiB 可用空间"
  sourceRoot="${cacheDir}/mysql-source-${MYSQL_VERSION}.tmp.$$"
  buildDir="${sourceRoot}/build"; installDir="${MYSQL_HOME}.tmp.$$"
  rm -rf -- "${sourceRoot}" "${installDir}"
  mkdir -p "${sourceRoot}" "${buildDir}" "${installDir}" "$(dirname "${buildLog}")"
  tar -xzf "${sourceArchive}" -C "${sourceRoot}" --strip-components=1 \
    || { rm -rf -- "${sourceRoot}" "${installDir}"; die "MySQL 官方源包解压失败"; }
  jobs="$(nproc 2>/dev/null || echo 1)"; [[ "${jobs}" =~ ^[0-9]+$ ]] || jobs=1
  (( jobs > 4 )) && jobs=4
  memoryKb="$(awk '/MemTotal:/ {print $2; exit}' /proc/meminfo 2>/dev/null || echo 0)"
  if [[ "${memoryKb}" =~ ^[0-9]+$ && "${memoryKb}" -gt 0 ]]; then
    memoryJobs=$((memoryKb / 1258291)); (( memoryJobs < 1 )) && memoryJobs=1
    (( jobs > memoryJobs )) && jobs="${memoryJobs}"
  fi
  warn "Oracle 二进制包入口均失败，启用已签名的 AID 镜像源包兜底；编译会临时占用 CPU，完整日志: ${buildLog}"
  if ! (cd "${buildDir}" && cmake .. \
      -DCMAKE_INSTALL_PREFIX="${installDir}" \
      -DSYSCONFDIR="${CONFIG_ROOT}" \
      -DWITH_MYISAM_STORAGE_ENGINE=1 \
      -DWITH_INNOBASE_STORAGE_ENGINE=1 \
      -DWITH_PARTITION_STORAGE_ENGINE=1 \
      -DWITH_FEDERATED_STORAGE_ENGINE=1 \
      -DEXTRA_CHARSETS=all \
      -DDEFAULT_CHARSET=utf8mb4 \
      -DDEFAULT_COLLATION=utf8mb4_general_ci \
      -DENABLED_LOCAL_INFILE=1 \
      -DWITH_BOOST="${sourceRoot}/boost" \
      -DWITH_SSL=system \
      -DWITH_UNIT_TESTS=OFF >"${buildLog}" 2>&1 \
      && make -j "${jobs}" >>"${buildLog}" 2>&1 \
      && make install >>"${buildLog}" 2>&1); then
    rm -rf -- "${sourceRoot}" "${installDir}"
    die "MySQL 5.7 源码兜底编译失败，请查看 ${buildLog}"
  fi
  [[ -x "${installDir}/bin/mysqld" && -x "${installDir}/bin/mysql" ]] \
    || { rm -rf -- "${sourceRoot}" "${installDir}"; die "MySQL 源码编译产物不完整"; }
  rm -rf -- "${MYSQL_HOME}"
  mv "${installDir}" "${MYSQL_HOME}" || die "MySQL 源码编译产物就位失败"
  rm -rf -- "${sourceRoot}"
  ok "MySQL ${MYSQL_VERSION} 已通过 AID 国内镜像下载并完成受控源码编译"
}

# 受管 MySQL 使用独立 Socket，root 操作必须显式指定 Socket，避免客户端误连
# /tmp/mysql.sock 后把“连接位置错误”误报为“密码不一致”。
managed_mysql_root_exec() { # managed_mysql_root_exec <mysql> <socket> <password> [mysql参数...]
  local mysqlBin="$1" mysqlSocket="$2" password="$3"
  shift 3
  MYSQL_PWD="${password}" "${mysqlBin}" --connect-timeout=3 --protocol=socket \
    --socket="${mysqlSocket}" -uroot "$@"
}

managed_mysql_business_exec() { # managed_mysql_business_exec <mysql> <host> <port> <database> <user> <password> [mysql参数...]
  local mysqlBin="$1" host="$2" port="$3" database="$4" user="$5" password="$6"
  shift 6
  MYSQL_PWD="${password}" "${mysqlBin}" --connect-timeout=3 --protocol=TCP \
    --host="${host}" --port="${port}" --database="${database}" --user="${user}" "$@"
}

# 正式配置文件是受管数据库的唯一凭证真源。安装与恢复都会重新应用库名、
# root 密码、业务账号、业务密码和授权，随后用两套配置分别回连验证。
reconcile_managed_mysql_credentials() { # <mysql> <socket> <host> <port> <db> <user> <dbPwd> <rootPwd>
  local mysqlBin="$1" mysqlSocket="$2" host="$3" port="$4"
  local database="$5" user="$6" dbPwd="$7" rootPwd="$8" rootAuth=""

  if managed_mysql_root_exec "${mysqlBin}" "${mysqlSocket}" "${rootPwd}" -e 'SELECT 1' >/dev/null 2>&1; then
    rootAuth="${rootPwd}"
  elif managed_mysql_root_exec "${mysqlBin}" "${mysqlSocket}" "" -e 'SELECT 1' >/dev/null 2>&1; then
    # 只兼容 initialize-insecure 创建且尚未设置密码的全新数据目录。
    rootAuth=""
  else
    die "受管 MySQL root 密码与 ${CONF} 不一致；请恢复正确的 MYSQL_ROOT_PASSWORD 后重试"
  fi

  if [[ "${user}" == "root" ]]; then
    [[ "${dbPwd}" == "${rootPwd}" ]] \
      || die "DB_USERNAME=root 时 DB_PASSWORD 必须与 MYSQL_ROOT_PASSWORD 一致"
    managed_mysql_root_exec "${mysqlBin}" "${mysqlSocket}" "${rootAuth}" -e \
      "CREATE DATABASE IF NOT EXISTS \`${database}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; ALTER USER 'root'@'localhost' IDENTIFIED BY '${rootPwd}'; FLUSH PRIVILEGES;" \
      || die "MySQL root 配置同步失败"
  else
    managed_mysql_root_exec "${mysqlBin}" "${mysqlSocket}" "${rootAuth}" -e \
      "CREATE DATABASE IF NOT EXISTS \`${database}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; CREATE USER IF NOT EXISTS '${user}'@'127.0.0.1' IDENTIFIED BY '${dbPwd}'; ALTER USER '${user}'@'127.0.0.1' IDENTIFIED BY '${dbPwd}'; GRANT ALL PRIVILEGES ON \`${database}\`.* TO '${user}'@'127.0.0.1'; ALTER USER 'root'@'localhost' IDENTIFIED BY '${rootPwd}'; FLUSH PRIVILEGES;" \
      || die "MySQL 业务账号配置同步失败"
  fi

  managed_mysql_root_exec "${mysqlBin}" "${mysqlSocket}" "${rootPwd}" -e 'SELECT 1' >/dev/null 2>&1 \
    || die "MySQL root 凭证同步校验失败"
  managed_mysql_business_exec "${mysqlBin}" "${host}" "${port}" "${database}" "${user}" "${dbPwd}" -e 'SELECT 1' >/dev/null 2>&1 \
    || die "MySQL 业务凭证同步校验失败"
  ok "MySQL 数据库、root 与业务账号已和配置文件保持一致"
}

# 兼容旧脚本在账号初始化前失败、却已向配置写入 48 位随机密码的现场。仅当
# AID 受管数据目录仍是空 root、业务账号不可用且目标库没有任何表时，才把这两项
# 尚未生效的旧随机值安全迁移成 12 位；已经投入使用的密码绝不自动改写。
normalize_pristine_managed_mysql_credentials() { # <mysql> <socket> <host> <port> <db> <user> <dbPwd> <rootPwd>
  local mysqlBin="$1" mysqlSocket="$2" host="$3" port="$4"
  local database="$5" user="$6" dbPwd="$7" rootPwd="$8" tableCount backupPath newDbPwd newRootPwd
  MANAGED_DB_PASSWORD="${dbPwd}"
  MANAGED_ROOT_PASSWORD="${rootPwd}"
  if (( ${#dbPwd} >= 10 && ${#dbPwd} <= 16 && ${#rootPwd} >= 10 && ${#rootPwd} <= 16 )); then
    return 0
  fi
  managed_mysql_root_exec "${mysqlBin}" "${mysqlSocket}" "${rootPwd}" -e 'SELECT 1' >/dev/null 2>&1 \
    && return 0
  managed_mysql_root_exec "${mysqlBin}" "${mysqlSocket}" "" -e 'SELECT 1' >/dev/null 2>&1 \
    || return 0
  managed_mysql_business_exec "${mysqlBin}" "${host}" "${port}" "${database}" "${user}" "${dbPwd}" -e 'SELECT 1' >/dev/null 2>&1 \
    && return 0
  tableCount="$(managed_mysql_root_exec "${mysqlBin}" "${mysqlSocket}" "" --batch --skip-column-names \
    -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${database}'" 2>/dev/null | tail -n 1 || true)"
  [[ "${tableCount}" == "0" ]] || return 0

  backupPath="${CONF}.bak.$(date '+%Y%m%d-%H%M%S')"
  cp -p -- "${CONF}" "${backupPath}" || die "备份旧 MySQL 配置失败"
  chmod 600 "${backupPath}" 2>/dev/null || true
  newDbPwd="$(gen_database_secret)"
  newRootPwd="$(gen_database_secret)"
  while [[ "${newRootPwd}" == "${newDbPwd}" ]]; do newRootPwd="$(gen_database_secret)"; done
  conf_set DB_PASSWORD "${newDbPwd}"
  conf_set MYSQL_ROOT_PASSWORD "${newRootPwd}"
  MANAGED_DB_PASSWORD="${newDbPwd}"
  MANAGED_ROOT_PASSWORD="${newRootPwd}"
  ok "检测到未完成初始化的旧配置，MySQL root/业务密码已迁移为12位随机值"
  echo "  原配置备份: ${backupPath}"
}

prepare_managed_mysql() {
  local installMode="$1" arch name cacheDir archive checksum actual downloaded=no url tmp reuseBinary=no
  local dbHost dbPort dbName dbUser dbPwd rootPwd mysqlConf mysqlData mysqlFiles mysqlRun mysqlLog
  local -a urls=()
  case "$(uname -m)" in
    x86_64|amd64) arch=x86_64 ;;
    *) die "手动模式自动安装 MySQL ${MYSQL_VERSION} 目前仅支持 x86_64；其他架构请使用外部 MySQL 5.7" ;;
  esac
  name="mysql-${MYSQL_VERSION}-linux-glibc2.12-${arch}.tar.gz"
  checksum="d7c8436bbf456e9a4398011a0c52bc40"
  cacheDir="${DATA_ROOT}/build-cache/toolchains"
  archive="${cacheDir}/${name}"
  MYSQL_HOME="${DATA_ROOT}/runtime/mysql-${MYSQL_VERSION}"
  mysqlData="${DATA_ROOT}/mysql-data-manual"
  mysqlFiles="${DATA_ROOT}/mysql-files"
  mysqlRun="${DATA_ROOT}/run/mysql"
  mysqlLog="${DATA_ROOT}/logs/mysql"
  mysqlConf="${CONFIG_ROOT}/mysql-5.7.cnf"
  if [[ -x "${MYSQL_HOME}/bin/mysqld" ]] \
      && "${MYSQL_HOME}/bin/mysqld" --version 2>/dev/null | grep -Fq "Ver 5.7.${MYSQL_VERSION##*.}"; then
    reuseBinary=yes
    ok "受管 MySQL ${MYSQL_VERSION} 二进制已存在，跳过下载和解压"
  fi
  if [[ "${reuseBinary}" != "yes" ]]; then
    require_download_tools
    mkdir -p "${cacheDir}" "${DATA_ROOT}/runtime"
    if [[ -f "${archive}" && "$(md5_file "${archive}" 2>/dev/null || true)" != "${checksum}" ]]; then
      warn "MySQL 缓存与官方归档摘要不一致，将重新下载"
      rm -f -- "${archive}"
    fi
    if [[ ! -f "${archive}" ]]; then
      [[ "${installMode}" == "auto" ]] || die "缺少 MySQL ${MYSQL_VERSION}；请安装后重试或启用自动依赖安装"
      resolve_dependency_region
      [[ -z "${AID_MYSQL_DOWNLOAD_URL:-}" ]] || urls+=("${AID_MYSQL_DOWNLOAD_URL}")
      urls+=(
        "https://downloads.mysql.com/archives/get/p/23/file/${name}"
        "https://cdn.mysql.com/archives/mysql-5.7/${name}"
      )
      mapfile -t urls < <(rank_download_urls "MySQL ${MYSQL_VERSION}" "${urls[@]}")
      for url in "${urls[@]}"; do
        if try_download "${url}" "${archive}" "MySQL ${MYSQL_VERSION}（${arch}）" md5 "${checksum}"; then
          actual="$(md5_file "${archive}" 2>/dev/null || true)"
          if [[ "${actual}" == "${checksum}" ]]; then downloaded=yes; break; fi
          warn "MySQL 下载文件与 Oracle 官方归档摘要不一致，拒绝使用当前来源"
          rm -f -- "${archive}"
        fi
      done
      if [[ "${downloaded}" != "yes" ]]; then
        prepare_managed_mysql_from_bt_source "${installMode}" "${cacheDir}"
        reuseBinary=yes
      fi
    fi
    if [[ "${reuseBinary}" != "yes" ]]; then
      verify_mysql_archive_signature "${archive}" "${name}" "${cacheDir}"
      install_mysql_runtime_libraries "${installMode}"
      tmp="${MYSQL_HOME}.tmp.$$"
      rm -rf -- "${tmp}"; mkdir -p "${tmp}"
      tar -xzf "${archive}" -C "${tmp}" --strip-components=1 \
        || { rm -rf -- "${tmp}"; die "MySQL 压缩包解压失败"; }
      [[ -x "${tmp}/bin/mysqld" && -x "${tmp}/bin/mysql" ]] \
        || { rm -rf -- "${tmp}"; die "MySQL 压缩包内容不完整"; }
      if ldd "${tmp}/bin/mysqld" 2>/dev/null | grep -q 'not found' \
          || ldd "${tmp}/bin/mysql" 2>/dev/null | grep -q 'not found'; then
        install_mysql_compat_libraries "${installMode}"
      fi
      if ldd "${tmp}/bin/mysqld" 2>/dev/null | grep -q 'not found' \
          || ldd "${tmp}/bin/mysql" 2>/dev/null | grep -q 'not found'; then
        rm -rf -- "${tmp}"
        die "MySQL 5.7 运行库仍不完整，请执行 ldd 检查缺失项"
      fi
      rm -rf -- "${MYSQL_HOME}"; mv "${tmp}" "${MYSQL_HOME}" || die "MySQL 安装目录就位失败"
    fi
  fi

  getent group aidmysql >/dev/null 2>&1 || groupadd --system aidmysql
  id aidmysql >/dev/null 2>&1 || useradd --system --gid aidmysql --home-dir "${mysqlData}" --shell /sbin/nologin aidmysql
  mkdir -p "${mysqlData}" "${mysqlFiles}" "${mysqlRun}" "${mysqlLog}" "${CONFIG_ROOT}"
  chown -R aidmysql:aidmysql "${mysqlData}" "${mysqlFiles}" "${mysqlRun}" "${mysqlLog}"
  chmod 750 "${mysqlData}" "${mysqlFiles}" "${mysqlRun}" "${mysqlLog}"
  dbHost="$(conf_get DB_HOST 127.0.0.1)"; dbPort="$(conf_get DB_PORT 3306)"
  dbName="$(conf_get DB_NAME aid)"; dbUser="$(conf_get DB_USERNAME aid)"
  dbPwd="$(conf_get DB_PASSWORD '')"; rootPwd="$(conf_get MYSQL_ROOT_PASSWORD '')"
  [[ "${dbName}" =~ ^[A-Za-z0-9_]+$ && "${dbUser}" =~ ^[A-Za-z0-9_.-]+$ ]] \
    || die "DB_NAME 或 DB_USERNAME 格式不安全"
  [[ -n "${dbPwd}" && -n "${rootPwd}" ]] || die "自动安装本机 MySQL 前必须生成数据库密码"
  cat > "${mysqlConf}" <<EOF
[mysqld]
basedir=${MYSQL_HOME}
datadir=${mysqlData}
port=${dbPort}
bind-address=127.0.0.1
socket=${mysqlRun}/mysql.sock
pid-file=${mysqlRun}/mysqld.pid
log-error=${mysqlLog}/error.log
user=aidmysql
character-set-server=utf8mb4
collation-server=utf8mb4_general_ci
skip-name-resolve=ON
symbolic-links=0
secure-file-priv=${mysqlFiles}

[client]
port=${dbPort}
socket=${mysqlRun}/mysql.sock
default-character-set=utf8mb4
EOF
  chmod 640 "${mysqlConf}"
  chown root:aidmysql "${mysqlConf}"
  if [[ ! -d "${mysqlData}/mysql" ]]; then
    "${MYSQL_HOME}/bin/mysqld" --defaults-file="${mysqlConf}" --initialize-insecure --user=aidmysql \
      || die "MySQL 5.7 数据目录初始化失败"
  fi
  cat > "/etc/systemd/system/${MYSQL_MANAGED_SERVICE}" <<EOF
# AID_MANAGED_UNIT=1
# AID_DATA_ROOT=${DATA_ROOT}
[Unit]
Description=AID managed MySQL 5.7
After=network.target

[Service]
Type=simple
User=aidmysql
Group=aidmysql
ExecStart=${MYSQL_HOME}/bin/mysqld --defaults-file=${mysqlConf}
Restart=on-failure
RestartSec=5
LimitNOFILE=65535
TimeoutStopSec=120

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable --now "${MYSQL_MANAGED_SERVICE}" >/dev/null 2>&1 || die "受管 MySQL 5.7 启动失败"
  local waited=0
  while (( waited < 60 )) && [[ ! -S "${mysqlRun}/mysql.sock" ]]; do sleep 2; waited=$((waited + 2)); done
  [[ -S "${mysqlRun}/mysql.sock" ]] || die "受管 MySQL 5.7 启动超时"
  normalize_pristine_managed_mysql_credentials "${MYSQL_HOME}/bin/mysql" "${mysqlRun}/mysql.sock" \
    "127.0.0.1" "${dbPort}" "${dbName}" "${dbUser}" "${dbPwd}" "${rootPwd}"
  dbPwd="${MANAGED_DB_PASSWORD}"; rootPwd="${MANAGED_ROOT_PASSWORD}"
  reconcile_managed_mysql_credentials "${MYSQL_HOME}/bin/mysql" "${mysqlRun}/mysql.sock" \
    "127.0.0.1" "${dbPort}" "${dbName}" "${dbUser}" "${dbPwd}" "${rootPwd}"
  command -v mysql >/dev/null 2>&1 || ln -s "${MYSQL_HOME}/bin/mysql" /usr/local/bin/mysql
  command -v mysqldump >/dev/null 2>&1 || ln -s "${MYSQL_HOME}/bin/mysqldump" /usr/local/bin/mysqldump
  ok "MySQL ${MYSQL_VERSION} 已安装为独立服务 ${MYSQL_MANAGED_SERVICE}"
}

ensure_manual_mysql() {
  local installMode="$1" host port user pwd version serverBinaryVersion service
  local managedMysqlHome managedSocket rootPwd database
  host="$(conf_get DB_HOST 127.0.0.1)"; port="$(conf_get DB_PORT 3306)"
  user="$(conf_get DB_USERNAME aid)"; pwd="$(conf_get DB_PASSWORD '')"
  validate_port DB_PORT "${port}"
  if [[ "${host}" == "127.0.0.1" || "${host}" == "localhost" ]]; then
    if ! tcp_reachable "${host}" "${port}"; then
      if command -v mysqld >/dev/null 2>&1; then
        serverBinaryVersion="$(mysqld --version 2>/dev/null | sed -nE 's/.*Ver ([0-9]+\.[0-9]+\.[0-9]+).*/\1/p' | head -n 1)"
        [[ "${serverBinaryVersion}" == 5.7.* ]] \
          || die "检测到本机 MySQL ${serverBinaryVersion:-未知}，AID 手动部署要求 MySQL 5.7"
        for service in aid-mysql mysqld mysql; do systemctl start "${service}" >/dev/null 2>&1 && break; done
      else
        [[ "${installMode}" == "auto" ]] || die "本机未安装 MySQL 5.7，请安装后重试或设置 DEPENDENCY_INSTALL_MODE=auto"
        prepare_managed_mysql "${installMode}"
      fi
    fi
  else
    ok "已配置外部 MySQL ${host}:${port}，不会安装或修改本机 MySQL"
  fi
  managedMysqlHome="${DATA_ROOT}/runtime/mysql-${MYSQL_VERSION}"
  managedSocket="${DATA_ROOT}/run/mysql/mysql.sock"
  if [[ ( "${host}" == "127.0.0.1" || "${host}" == "localhost" ) \
      && -x "${managedMysqlHome}/bin/mysql" && -S "${managedSocket}" ]] \
      && systemctl is-active --quiet "${MYSQL_MANAGED_SERVICE}"; then
    database="$(conf_get DB_NAME aid)"
    rootPwd="$(conf_get MYSQL_ROOT_PASSWORD '')"
    [[ -n "${rootPwd}" ]] || die "受管 MySQL 必须在 ${CONF} 配置 MYSQL_ROOT_PASSWORD"
    normalize_pristine_managed_mysql_credentials "${managedMysqlHome}/bin/mysql" "${managedSocket}" \
      127.0.0.1 "${port}" "${database}" "${user}" "${pwd}" "${rootPwd}"
    pwd="${MANAGED_DB_PASSWORD}"; rootPwd="${MANAGED_ROOT_PASSWORD}"
    reconcile_managed_mysql_credentials "${managedMysqlHome}/bin/mysql" "${managedSocket}" \
      127.0.0.1 "${port}" "${database}" "${user}" "${pwd}" "${rootPwd}"
    # 上一次若在账号初始化阶段中断，prepare_managed_mysql 尚未来得及创建命令入口。
    # 优先复用受管 MySQL 自带客户端，避免再通过 yum 安装 MariaDB 兼容客户端。
    if ! command -v mysql >/dev/null 2>&1 && [[ ! -e /usr/local/bin/mysql && ! -L /usr/local/bin/mysql ]]; then
      ln -s "${managedMysqlHome}/bin/mysql" /usr/local/bin/mysql
    fi
    if ! command -v mysqldump >/dev/null 2>&1 && [[ ! -e /usr/local/bin/mysqldump && ! -L /usr/local/bin/mysqldump ]]; then
      ln -s "${managedMysqlHome}/bin/mysqldump" /usr/local/bin/mysqldump
    fi
  fi
  user="$(conf_get DB_USERNAME aid)"; pwd="$(conf_get DB_PASSWORD '')"
  ensure_host_command mysql "MySQL客户端" "default-mysql-client" "mariadb" "${installMode}"
  tcp_reachable "${host}" "${port}" || die "MySQL ${host}:${port} 不可达"
  version="$(MYSQL_PWD="${pwd}" mysql --protocol=TCP --connect-timeout=5 --host "${host}" --port "${port}" --user "${user}" -N -e 'SELECT VERSION()' 2>/dev/null | head -n 1)"
  [[ "${version}" == 5.7.* ]] || die "数据库认证失败或服务端不是 MySQL 5.7（检测结果: ${version:-不可读取}）"
  ok "MySQL ${version} 已可用且版本符合，跳过安装"
}

compiler_major_version() { # compiler_major_version <gcc路径>
  local version
  version="$("$1" -dumpfullversion -dumpversion 2>/dev/null | head -n 1)"
  printf '%s\n' "${version%%.*}"
}

redis_compiler_pair_is_supported() { # redis_compiler_pair_is_supported <cc> <cxx>
  local cc="$1" cxx="$2" major
  [[ -n "${cc}" && -n "${cxx}" ]] || return 1
  [[ "${cc}" == */* ]] || cc="$(command -v "${cc}" 2>/dev/null || true)"
  [[ "${cxx}" == */* ]] || cxx="$(command -v "${cxx}" 2>/dev/null || true)"
  [[ -x "${cc}" && -x "${cxx}" ]] || return 1
  major="$(compiler_major_version "${cc}")"
  [[ "${major}" =~ ^[0-9]+$ && "${major}" -ge 7 ]] || return 1
  export CC="${cc}" CXX="${cxx}"
  ok "Redis 编译器已就绪: $(${CC} --version 2>/dev/null | head -n 1)"
}

select_redis_build_compiler() {
  local binDir
  redis_compiler_pair_is_supported "${CC:-}" "${CXX:-}" && return 0
  # RHEL/CentOS 的新工具链安装在 /opt/rh，动态扫描可兼容 devtoolset-7/8/9/10/11
  # 与 gcc-toolset-9/10/11/12/13 等版本，不再写死单一目录。
  for binDir in /opt/rh/gcc-toolset-*/root/usr/bin /opt/rh/devtoolset-*/root/usr/bin; do
    [[ -d "${binDir}" ]] || continue
    redis_compiler_pair_is_supported "${binDir}/gcc" "${binDir}/g++" && return 0
  done
  redis_compiler_pair_is_supported "$(command -v gcc 2>/dev/null || true)" \
    "$(command -v g++ 2>/dev/null || true)" && return 0
  redis_compiler_pair_is_supported "$(command -v clang 2>/dev/null || true)" \
    "$(command -v clang++ 2>/dev/null || true)" && return 0
  return 1
}

rpm_key_file_is_verified() { # rpm_key_file_is_verified <文件> <指纹> <SHA256>
  local keyFile="$1" expectedFingerprint="$2" expectedSha256="$3" fingerprint=""
  [[ -s "${keyFile}" ]] || return 1
  file_digest_matches "${keyFile}" sha256 "${expectedSha256}" && return 0
  # 系统自带 key 文件可能只有注释或换行不同；有 GnuPG 时再按主指纹确认其身份。
  if command -v gpg >/dev/null 2>&1; then
    fingerprint="$(gpg_primary_key_fingerprint "${keyFile}")"
    [[ "${fingerprint}" == "${expectedFingerprint}" ]] && return 0
  fi
  return 1
}

ensure_verified_rpm_key() { # ensure_verified_rpm_key <名称> <指纹> <SHA256> <系统路径> <缓存路径> <下载地址...>
  local label="$1" expectedFingerprint="$2" expectedSha256="$3" target="$4" cache="$5"
  local candidate selected="" url
  shift 5
  command -v rpm >/dev/null 2>&1 || die "${label}校验需要 rpm 命令"
  require_download_tools
  for candidate in "${target}" "${cache}"; do
    if rpm_key_file_is_verified "${candidate}" "${expectedFingerprint}" "${expectedSha256}"; then
      selected="${candidate}"
      break
    fi
    [[ "${candidate}" != "${cache}" ]] || rm -f -- "${cache}" "${cache}.part"
  done
  if [[ -z "${selected}" ]]; then
    mkdir -p "$(dirname "${cache}")"
    for url in "$@"; do
      rm -f -- "${cache}" "${cache}.part"
      try_download "${url}" "${cache}" "${label}" || continue
      if rpm_key_file_is_verified "${cache}" "${expectedFingerprint}" "${expectedSha256}"; then
        selected="${cache}"
        break
      fi
      warn "${label}指纹不匹配，拒绝当前来源并切换下一个节点"
    done
  fi
  [[ -n "${selected}" ]] || die "${label}下载失败或指纹不匹配"
  if [[ "${selected}" != "${target}" ]]; then
    install -d -m 0755 "$(dirname "${target}")"
    install -m 0644 "${selected}" "${target}" || die "${label}安装失败"
  fi
  rpm --import "${target}" >/dev/null 2>&1 || die "${label}导入 RPM 数据库失败"
  ok "${label}已通过固定 SHA256/指纹校验"
}

append_aid_yum_repo() { # append_aid_yum_repo <文件> <ID> <名称> <GPG公钥> <baseurl...>
  local repoFile="$1" repoId="$2" repoName="$3" gpgKey="$4" prefix="baseurl=" url
  shift 4
  {
    printf '[%s]\nname=%s\n' "${repoId}" "${repoName}"
    for url in "$@"; do
      printf '%s%s\n' "${prefix}" "${url}"
      prefix='        '
    done
    printf 'enabled=1\ngpgcheck=1\nrepo_gpgcheck=0\ngpgkey=file://%s\nsslverify=1\nskip_if_unavailable=0\n\n' "${gpgKey}"
  } >> "${repoFile}"
}

install_centos7_redis_compiler() {
  local cacheDir gpgDir repoDir repoFile mainKey sclKey machineArch baseRoot1 baseRoot2 baseRoot3
  local sclRoot1 sclRoot2 sclRoot3
  cacheDir="${DATA_ROOT}/build-cache/toolchains/centos-keys"
  gpgDir="${AID_RPM_GPG_DIR:-/etc/pki/rpm-gpg}"
  repoDir="${AID_YUM_REPO_DIR:-/etc/yum.repos.d}"
  mainKey="${gpgDir}/RPM-GPG-KEY-CentOS-7"
  sclKey="${gpgDir}/RPM-GPG-KEY-CentOS-SIG-SCLo"
  machineArch="$(uname -m)"
  case "${machineArch}" in
    x86_64|amd64)
      baseRoot1='https://mirrors.aliyun.com/centos/7.9.2009'
      baseRoot2='https://mirrors.cloud.tencent.com/centos/7.9.2009'
      baseRoot3='https://vault.centos.org/7.9.2009'
      sclRoot1='https://mirrors.aliyun.com/centos/7/sclo'
      sclRoot2='https://mirrors.cloud.tencent.com/centos/7/sclo'
      sclRoot3='https://vault.centos.org/7.9.2009/sclo'
      ;;
    aarch64|arm64)
      baseRoot1='https://mirrors.aliyun.com/centos-altarch/7.9.2009'
      baseRoot2='https://vault.centos.org/altarch/7.9.2009'
      baseRoot3='https://vault.centos.org/altarch/7.9.2009'
      sclRoot1='https://mirrors.aliyun.com/centos-altarch/7/sclo'
      sclRoot2='https://vault.centos.org/altarch/7.9.2009/sclo'
      sclRoot3='https://vault.centos.org/altarch/7.9.2009/sclo'
      ;;
    *) die "CentOS 7 Redis 编译工具链暂不支持架构: ${machineArch}" ;;
  esac

  ensure_verified_rpm_key "CentOS 7 官方RPM公钥" \
    "6341AB2753D78A78A7C27BB124C6A8A7F4A80EB5" \
    "8b48b04b336bd725b9e611c441c65456a4168083c4febc28e88828d8ec14827f" \
    "${mainKey}" "${cacheDir}/RPM-GPG-KEY-CentOS-7" \
    "https://mirrors.aliyun.com/centos/RPM-GPG-KEY-CentOS-7" \
    "https://mirrors.cloud.tencent.com/centos/RPM-GPG-KEY-CentOS-7" \
    "https://www.centos.org/keys/RPM-GPG-KEY-CentOS-7"
  ensure_verified_rpm_key "CentOS SCLo RPM公钥" \
    "C4DBD535B1FBBA14F8BA64A84EB84E71F2EE9D55" \
    "1402b1ea263cd0e51e7fe77c89ab98d64dd08fdc54e7a4afe27b2782ddfc351c" \
    "${sclKey}" "${cacheDir}/RPM-GPG-KEY-CentOS-SIG-SCLo" \
    "https://www.centos.org/keys/RPM-GPG-KEY-CentOS-SIG-SCLo" \
    "https://www.dev.centos.org/keys/RPM-GPG-KEY-CentOS-SIG-SCLo"

  mkdir -p "${repoDir}"
  repoFile="${repoDir}/aid-centos7-redis-build.repo"
  : > "${repoFile}"
  append_aid_yum_repo "${repoFile}" aid-centos7-base "AID CentOS 7.9 Base" "${mainKey}" \
    "${baseRoot1}/os/\$basearch/" "${baseRoot2}/os/\$basearch/" "${baseRoot3}/os/\$basearch/"
  append_aid_yum_repo "${repoFile}" aid-centos7-updates "AID CentOS 7.9 Updates" "${mainKey}" \
    "${baseRoot1}/updates/\$basearch/" "${baseRoot2}/updates/\$basearch/" "${baseRoot3}/updates/\$basearch/"
  append_aid_yum_repo "${repoFile}" aid-centos7-extras "AID CentOS 7.9 Extras" "${mainKey}" \
    "${baseRoot1}/extras/\$basearch/" "${baseRoot2}/extras/\$basearch/" "${baseRoot3}/extras/\$basearch/"
  append_aid_yum_repo "${repoFile}" aid-centos7-sclo-rh "AID CentOS 7 SCLo RH" "${sclKey}" \
    "${sclRoot1}/\$basearch/rh/" "${sclRoot2}/\$basearch/rh/" "${sclRoot3}/\$basearch/rh/"
  chmod 0644 "${repoFile}"
  yum --disablerepo='*' \
    --enablerepo=aid-centos7-base,aid-centos7-updates,aid-centos7-extras,aid-centos7-sclo-rh \
    install -y devtoolset-7-gcc devtoolset-7-gcc-c++ \
    || die "CentOS 7 Redis 编译工具链安装失败，请检查 ${repoFile}"
}

install_centos8_redis_compiler() {
  local variant="${1:-linux}" cacheDir gpgDir repoDir repoFile officialKey root1 root2 root3
  local baseName appstreamName
  cacheDir="${DATA_ROOT}/build-cache/toolchains/centos-keys"
  gpgDir="${AID_RPM_GPG_DIR:-/etc/pki/rpm-gpg}"
  repoDir="${AID_YUM_REPO_DIR:-/etc/yum.repos.d}"
  officialKey="${gpgDir}/RPM-GPG-KEY-centosofficial"
  if [[ "${variant}" == "stream" ]]; then
    root1='https://vault.centos.org/centos/8-stream'
    root2='https://vault.centos.org/centos/8-stream'
    root3='https://vault.centos.org/centos/8-stream'
    baseName='AID CentOS Stream 8 Vault BaseOS'
    appstreamName='AID CentOS Stream 8 Vault AppStream'
  else
    root1='https://mirrors.aliyun.com/centos-vault/8.5.2111'
    root2='https://mirrors.cloud.tencent.com/centos-vault/8.5.2111'
    root3='https://vault.centos.org/8.5.2111'
    baseName='AID CentOS Linux 8.5 Vault BaseOS'
    appstreamName='AID CentOS Linux 8.5 Vault AppStream'
  fi
  ensure_verified_rpm_key "CentOS 8 官方RPM公钥" \
    "99DB70FAE1D7CE227FB6488205B555B38483C65D" \
    "146059788b214d7ba0dd70c1cf21111e594c6cfde201da8a9a88fe7101be8a78" \
    "${officialKey}" "${cacheDir}/RPM-GPG-KEY-CentOS-Official" \
    "https://mirrors.aliyun.com/centos/RPM-GPG-KEY-CentOS-Official" \
    "https://www.centos.org/keys/RPM-GPG-KEY-CentOS-Official"
  mkdir -p "${repoDir}"
  repoFile="${repoDir}/aid-centos8-redis-build.repo"
  : > "${repoFile}"
  if [[ "${variant}" == "stream" ]]; then
    append_aid_yum_repo "${repoFile}" aid-centos8-baseos "${baseName}" "${officialKey}" \
      "${root1}/BaseOS/\$basearch/os/"
    append_aid_yum_repo "${repoFile}" aid-centos8-appstream "${appstreamName}" "${officialKey}" \
      "${root1}/AppStream/\$basearch/os/"
  else
    append_aid_yum_repo "${repoFile}" aid-centos8-baseos "${baseName}" "${officialKey}" \
      "${root1}/BaseOS/\$basearch/os/" "${root2}/BaseOS/\$basearch/os/" "${root3}/BaseOS/\$basearch/os/"
    append_aid_yum_repo "${repoFile}" aid-centos8-appstream "${appstreamName}" "${officialKey}" \
      "${root1}/AppStream/\$basearch/os/" "${root2}/AppStream/\$basearch/os/" "${root3}/AppStream/\$basearch/os/"
  fi
  chmod 0644 "${repoFile}"
  dnf --disablerepo='*' --enablerepo=aid-centos8-baseos,aid-centos8-appstream \
    --setopt=install_weak_deps=False install -y gcc gcc-c++ \
    || die "CentOS 8 Redis 编译工具链安装失败，请检查 ${repoFile}"
}

install_rpm_redis_compiler() {
  if command -v dnf >/dev/null 2>&1; then
    dnf install -y gcc gcc-c++
  elif command -v yum >/dev/null 2>&1; then
    yum install -y gcc gcc-c++
  else
    return 1
  fi
}

ensure_redis_build_compiler() {
  local installMode="$1" osId="" osVersion="" osName="" osReleaseFile
  select_redis_build_compiler && return 0
  [[ "${installMode}" == "auto" ]] \
    || die "编译 Redis ${REDIS_VERSION} 需要 GCC/G++ 7+；请安装后重试"
  osReleaseFile="${AID_OS_RELEASE_FILE:-/etc/os-release}"
  if [[ -f "${osReleaseFile}" ]]; then
    osId="$(. "${osReleaseFile}"; printf '%s' "${ID:-}")"
    osVersion="$(. "${osReleaseFile}"; printf '%s' "${VERSION_ID:-}")"
    osName="$(. "${osReleaseFile}"; printf '%s' "${NAME:-}")"
  fi
  if [[ "${osId}" == "centos" && "${osVersion%%.*}" == "7" ]]; then
    install_centos7_redis_compiler
  elif [[ "${osId}" == "centos" && "${osVersion%%.*}" =~ ^[0-6]$ ]]; then
    die "CentOS ${osVersion:-未知} 已不受支持；请升级至 CentOS 7+ 或使用外部 Redis 6+"
  elif command -v apt-get >/dev/null 2>&1; then
    install_os_packages "Redis GCC/G++ 7+编译器" "build-essential" "gcc gcc-c++"
  elif command -v dnf >/dev/null 2>&1 || command -v yum >/dev/null 2>&1; then
    if ! install_rpm_redis_compiler; then
      if [[ "${osId}" == "centos" && "${osVersion%%.*}" == "8" ]]; then
        if [[ "${osName}" == *Stream* ]]; then
          warn "CentOS Stream 8 系统仓库不可用，切换至经固定公钥校验的官方 Vault 仓库"
          install_centos8_redis_compiler stream
        else
          warn "CentOS Linux 8 系统仓库不可用，切换至经固定公钥校验的 8.5 Vault 仓库"
          install_centos8_redis_compiler linux
        fi
      else
        die "Redis GCC/G++ 编译器安装失败，请检查当前发行版软件源"
      fi
    fi
  else
    die "当前系统无法自动安装 Redis GCC/G++ 7+ 编译器"
  fi
  select_redis_build_compiler \
    || die "系统安装源未提供 GCC/G++ 7+；请升级操作系统编译器或使用外部 Redis 6+"
}

prepare_managed_redis() {
  local installMode="$1" name btChecksum officialChecksum cacheDir archive actual downloaded=no url tmp
  local redisHost redisPort redisUser redisPwd redisData redisRun redisLog redisConf buildLog redisCli
  local -a urls=()
  name="redis-${REDIS_VERSION}.tar.gz"
  # 国内镜像和 Redis 官方归档的源码树一致，但 gzip 打包元数据不同，因此分别固定两个可信摘要。
  btChecksum="1e8beff55b0c798429ca4fc4c62e064000f37c8b7e9742ab4ebd4edfc3888417"
  officialChecksum="012bca956fc7151abc2281950e69768ee9c53ce4b36588772041675bc95fd313"
  cacheDir="${DATA_ROOT}/build-cache/toolchains"
  archive="${cacheDir}/${name}"
  REDIS_HOME="${DATA_ROOT}/runtime/redis-${REDIS_VERSION}"
  redisData="${DATA_ROOT}/redis-data-manual"
  redisRun="${DATA_ROOT}/run/redis"
  redisLog="${DATA_ROOT}/logs/redis"
  redisConf="${CONFIG_ROOT}/redis.conf"
  buildLog="${redisLog}/build-${REDIS_VERSION}.log"
  if [[ ! -x "${REDIS_HOME}/src/redis-server" ]] \
      || ! "${REDIS_HOME}/src/redis-server" --version 2>/dev/null | grep -Fq "v=${REDIS_VERSION}"; then
    require_download_tools
    mkdir -p "${cacheDir}" "${DATA_ROOT}/runtime" "${redisLog}"
    actual="$(sha256_file "${archive}" 2>/dev/null || true)"
    if [[ -f "${archive}" && "${actual}" != "${btChecksum}" && "${actual}" != "${officialChecksum}" ]]; then
      warn "Redis 缓存校验失败，将重新下载"
      rm -f -- "${archive}"
    fi
    if [[ ! -f "${archive}" ]]; then
      [[ "${installMode}" == "auto" ]] || die "缺少 Redis ${REDIS_VERSION}，请安装 Redis 6+ 后重试"
      mapfile -t urls < <(
        [[ -z "${AID_REDIS_DOWNLOAD_URL:-}" ]] || printf '%s\n' "${AID_REDIS_DOWNLOAD_URL}"
        bt_artifact_urls "src/${name}"
        printf '%s\n' "https://download.redis.io/releases/${name}"
      )
      for url in "${urls[@]}"; do
        [[ -n "${url}" ]] || continue
        if try_download "${url}" "${archive}" "Redis ${REDIS_VERSION}"; then
          actual="$(sha256_file "${archive}" 2>/dev/null || true)"
          if [[ "${actual}" == "${btChecksum}" || "${actual}" == "${officialChecksum}" ]]; then
            downloaded=yes
            break
          fi
        fi
        warn "Redis 当前下载地址不可用或 SHA256 不匹配，尝试备用地址"
        rm -f -- "${archive}"
      done
      [[ "${downloaded}" == "yes" ]] || die "Redis ${REDIS_VERSION} 下载失败或校验不通过"
    fi
    ensure_host_command make "Make构建工具" "build-essential" "make" "${installMode}"
    ensure_redis_build_compiler "${installMode}"
    tmp="${REDIS_HOME}.tmp.$$"; rm -rf -- "${tmp}"; mkdir -p "${tmp}"
    tar -xzf "${archive}" -C "${tmp}" --strip-components=1 \
      || { rm -rf -- "${tmp}"; die "Redis 压缩包解压失败"; }
    log "编译 Redis ${REDIS_VERSION}，完整日志: ${buildLog}"
    if ! make -C "${tmp}" -j "$(nproc 2>/dev/null || echo 2)" BUILD_TLS=no MALLOC=libc >"${buildLog}" 2>&1; then
      rm -rf -- "${tmp}"
      die "Redis 源码编译失败，请查看 ${buildLog}"
    fi
    [[ -x "${tmp}/src/redis-server" && -x "${tmp}/src/redis-cli" ]] \
      || { rm -rf -- "${tmp}"; die "Redis 编译产物不完整"; }
    "${tmp}/src/redis-server" --version 2>/dev/null | grep -Fq "v=${REDIS_VERSION}" \
      || { rm -rf -- "${tmp}"; die "Redis 编译产物实际版本不是 ${REDIS_VERSION}"; }
    rm -rf -- "${REDIS_HOME}"; mv "${tmp}" "${REDIS_HOME}" || die "Redis 安装目录就位失败"
    ok "Redis ${REDIS_VERSION} 已通过固定 SHA256 校验并完成本机编译"
  else
    ok "受管 Redis ${REDIS_VERSION} 已存在，跳过下载和编译"
  fi

  getent group aidredis >/dev/null 2>&1 || groupadd --system aidredis
  id aidredis >/dev/null 2>&1 || useradd --system --gid aidredis --home-dir "${redisData}" --shell /sbin/nologin aidredis
  mkdir -p "${redisData}" "${redisRun}" "${redisLog}" "${CONFIG_ROOT}"
  chown -R aidredis:aidredis "${redisData}" "${redisRun}" "${redisLog}"
  chmod 750 "${redisData}" "${redisRun}" "${redisLog}"
  redisHost="$(conf_get REDIS_HOST 127.0.0.1)"; redisPort="$(conf_get REDIS_PORT 6379)"
  redisUser="$(conf_get REDIS_USERNAME '')"; redisPwd="$(conf_get REDIS_PASSWORD '')"
  [[ "${redisUser}" =~ ^[A-Za-z0-9_.-]*$ ]] || die "REDIS_USERNAME 格式不安全"
  cat > "${redisConf}" <<EOF
bind 127.0.0.1
protected-mode yes
port ${redisPort}
daemonize no
supervised no
pidfile ${redisRun}/redis.pid
dir ${redisData}
dbfilename dump.rdb
appendonly yes
appendfsync everysec
logfile ""
EOF
  if [[ -n "${redisUser}" && "${redisUser}" != "default" ]]; then
    if [[ -n "${redisPwd}" ]]; then
      printf 'user default off\nuser %s on >%s ~* &* +@all\n' "${redisUser}" "${redisPwd}" >> "${redisConf}"
    else
      printf 'user default off\nuser %s on nopass ~* &* +@all\n' "${redisUser}" >> "${redisConf}"
    fi
  elif [[ -n "${redisPwd}" ]]; then
    printf 'requirepass %s\n' "${redisPwd}" >> "${redisConf}"
  fi
  chmod 640 "${redisConf}"
  chown root:aidredis "${redisConf}"
  cat > "/etc/systemd/system/${REDIS_MANAGED_SERVICE}" <<EOF
# AID_MANAGED_UNIT=1
# AID_DATA_ROOT=${DATA_ROOT}
[Unit]
Description=AID managed Redis ${REDIS_VERSION}
After=network.target

[Service]
Type=simple
User=aidredis
Group=aidredis
ExecStart=${REDIS_HOME}/src/redis-server ${redisConf}
Restart=on-failure
RestartSec=5
LimitNOFILE=65535
TimeoutStartSec=60
TimeoutStopSec=60

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl reset-failed "${REDIS_MANAGED_SERVICE}" >/dev/null 2>&1 || true
  if ! systemctl enable "${REDIS_MANAGED_SERVICE}" >/dev/null 2>&1 \
      || ! systemctl restart "${REDIS_MANAGED_SERVICE}" >/dev/null 2>&1; then
    manual_service_diagnostics "${REDIS_MANAGED_SERVICE}" "受管 Redis"
    die "受管 Redis 启动失败，诊断日志已输出"
  fi
  wait_managed_redis_ready "${redisPort}" "${redisUser}" "${redisPwd}" \
    || die "受管 Redis 未就绪，诊断日志已输出"
  link_managed_redis_commands
  redisCli="$(resolve_redis_cli_command 2>/dev/null || true)"
  [[ -n "${redisCli}" && -x "${redisCli}" ]] \
    || die "受管 Redis 客户端不可用: ${REDIS_HOME}/src/redis-cli"
  ok "Redis ${REDIS_VERSION} 已安装为独立服务 ${REDIS_MANAGED_SERVICE}"
}

managed_redis_home() {
  printf '%s/runtime/redis-%s\n' "${DATA_ROOT}" "${REDIS_VERSION}"
}

link_managed_redis_commands() {
  local redisHome binDir name source target resolvedTarget currentPath
  redisHome="$(managed_redis_home)"
  binDir="${AID_LOCAL_BIN_DIR:-/usr/local/bin}"
  currentPath="${PATH:-/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}"
  case ":${currentPath}:" in
    *":${binDir}:"*) ;;
    *) currentPath="${binDir}:${currentPath}" ;;
  esac
  export PATH="${currentPath}"
  if ! mkdir -p "${binDir}"; then
    warn "无法创建 Redis 命令目录 ${binDir}，将直接使用受管安装目录中的命令"
    return 0
  fi
  for name in redis-server redis-cli; do
    source="${redisHome}/src/${name}"
    target="${binDir}/${name}"
    [[ -x "${source}" ]] || continue
    if [[ -L "${target}" ]]; then
      resolvedTarget="$(resolve_aid_symlink_target "${target}" 2>/dev/null || true)"
      case "${resolvedTarget}" in
        "${source}") continue ;;
        "${DATA_ROOT}/runtime/"*)
          ln -sfn "${source}" "${target}" \
            || warn "无法修复受管 Redis 命令链接: ${target}"
          ;;
        *) warn "保留已有非 AID Redis 命令链接: ${target}" ;;
      esac
    elif [[ -n "$(command -v "${name}" 2>/dev/null || true)" ]]; then
      continue
    elif [[ ! -e "${target}" ]]; then
      ln -s "${source}" "${target}" \
        || warn "无法创建 Redis 命令链接 ${target}，将直接使用 ${source}"
    elif [[ ! -x "${target}" ]]; then
      warn "保留已有非可执行文件 ${target}，将直接使用 ${source}"
    fi
  done
  return 0
}

resolve_redis_cli_command() {
  local commandPath="" redisHome
  commandPath="$(command -v redis-cli 2>/dev/null || true)"
  if [[ -n "${commandPath}" && -x "${commandPath}" ]]; then
    printf '%s\n' "${commandPath}"
    return 0
  fi
  redisHome="${REDIS_HOME:-$(managed_redis_home)}"
  if [[ -x "${redisHome}/src/redis-cli" ]]; then
    printf '%s\n' "${redisHome}/src/redis-cli"
    return 0
  fi
  commandPath="${AID_LOCAL_BIN_DIR:-/usr/local/bin}/redis-cli"
  if [[ -x "${commandPath}" ]]; then
    printf '%s\n' "${commandPath}"
    return 0
  fi
  return 1
}

managed_redis_service_needs_recovery() {
  local redisHome unitFile
  redisHome="$(managed_redis_home)"
  unitFile="${AID_SYSTEMD_UNIT_DIR:-/etc/systemd/system}/${REDIS_MANAGED_SERVICE}"
  [[ -x "${redisHome}/src/redis-server" ]] || return 1
  [[ -f "${unitFile}" ]] || return 0
  grep -Eq '^Type=notify|--supervised[[:space:]]+systemd' "${unitFile}" && return 0
  systemctl is-active --quiet "${REDIS_MANAGED_SERVICE}" || return 0
  return 1
}

managed_redis_ping() { # managed_redis_ping <端口> <用户名> <密码>
  local redisPort="$1" redisUser="$2" redisPwd="$3" response=""
  [[ -x "${REDIS_HOME}/src/redis-cli" ]] || return 1
  if [[ -n "${redisUser}" && "${redisUser}" != "default" ]]; then
    response="$(REDISCLI_AUTH="${redisPwd}" "${REDIS_HOME}/src/redis-cli" --no-auth-warning \
      -h 127.0.0.1 -p "${redisPort}" --user "${redisUser}" PING 2>/dev/null || true)"
  else
    response="$(REDISCLI_AUTH="${redisPwd}" "${REDIS_HOME}/src/redis-cli" --no-auth-warning \
      -h 127.0.0.1 -p "${redisPort}" PING 2>/dev/null || true)"
  fi
  [[ "${response}" == "PONG" ]]
}

wait_managed_redis_ready() { # wait_managed_redis_ready <端口> <用户名> <密码>
  local redisPort="$1" redisUser="$2" redisPwd="$3" deadline
  deadline=$(( $(date +%s) + 60 ))
  while [[ $(date +%s) -lt ${deadline} ]]; do
    if managed_redis_ping "${redisPort}" "${redisUser}" "${redisPwd}"; then
      ok "受管 Redis 已通过认证与 PING 健康检查"
      return 0
    fi
    if ! systemctl is-active --quiet "${REDIS_MANAGED_SERVICE}"; then
      manual_service_diagnostics "${REDIS_MANAGED_SERVICE}" "受管 Redis"
      return 1
    fi
    sleep 2
  done
  manual_service_diagnostics "${REDIS_MANAGED_SERVICE}" "受管 Redis（等待60秒超时）"
  return 1
}

ensure_manual_host_dependencies() {
  local installMode redisHost redisPort redisUser redisPwd redisVersion redisMajor redisDb redisCli
  local -a redisArgs=()
  installMode="$(dependency_install_mode manual)"
  export AID_DEPENDENCY_INSTALL_MODE="${installMode}"
  command -v systemctl >/dev/null 2>&1 || die "手动部署要求使用 systemd"

  prepare_manual_jdk
  prepare_exact_node

  prepare_ffmpeg_runtime "${installMode}"
  prepare_aid_cjk_font "${installMode}"

  # 手动部署始终准备宿主机构建工具；即使服务器上碰巧存在 Docker，也不把它作为隐式依赖。
  ensure_git_runtime "${installMode}"
  prepare_exact_maven
  prepare_exact_go

  ensure_manual_mysql "${installMode}"
  ensure_nginx_runtime "${installMode}"
  if ! nginx_runtime_active; then
    [[ "${installMode}" == "auto" ]] || die "Nginx 未运行，请启动 ${NGINX_SERVICE:-nginx.service} 后重试"
    start_nginx_runtime
  fi

  # 手动部署只有配置为本机 Redis 时才负责检查/可选安装；外部 Redis 永不改本机。
  redisHost="$(conf_get REDIS_HOST 127.0.0.1)"; redisPort="$(conf_get REDIS_PORT 6379)"
  redisUser="$(conf_get REDIS_USERNAME '')"; redisPwd="$(conf_get REDIS_PASSWORD '')"
  case "${redisHost}" in
    127.0.0.1|localhost)
      # 上一次安装可能已完成编译并拉起进程，却在创建命令链接或确认服务前中断。
      # 遇到旧 notify unit、缺失 unit 或非活跃服务时，直接用受管产物补齐安装，不走系统仓库。
      if managed_redis_service_needs_recovery; then
        warn "检测到未收尾的受管 Redis，正在自动修复服务与客户端命令"
        prepare_managed_redis "${installMode}"
      else
        link_managed_redis_commands
      fi
      if ! tcp_reachable "${redisHost}" "${redisPort}"; then
        [[ "${installMode}" == "auto" ]] || die "本机 Redis ${redisHost}:${redisPort} 不可用，请启动后重试"
        if command -v redis-server >/dev/null 2>&1; then
          redisVersion="$(redis-server --version 2>/dev/null | sed -E 's/.*v=([0-9]+(\.[0-9]+)*).*/\1/')"
          redisMajor="${redisVersion%%.*}"
          [[ "${redisMajor}" =~ ^[0-9]+$ && "${redisMajor}" -ge 6 ]] \
            || die "检测到本机 Redis ${redisVersion:-未知}，版本不符合且脚本不会覆盖已有服务；请升级到6+或改用外部Redis"
          systemctl start aid-redis >/dev/null 2>&1 \
            || systemctl start redis-server >/dev/null 2>&1 \
            || systemctl start redis >/dev/null 2>&1 \
            || die "现有 Redis 版本符合但无法启动，请检查其 systemd 日志"
        else
          prepare_managed_redis "${installMode}"
        fi
        tcp_reachable "${redisHost}" "${redisPort}" || die "Redis 已安装但端口仍不可用"
        ok "本机 Redis 已就绪"
      else
        ok "Redis ${redisHost}:${redisPort} 已可用，跳过安装"
      fi ;;
    *) ok "已配置外部 Redis ${redisHost}:${redisPort}，不会安装本机 Redis" ;;
  esac
  redisCli="$(resolve_redis_cli_command 2>/dev/null || true)"
  if [[ -z "${redisCli}" ]]; then
    ensure_host_command redis-cli "Redis客户端" "redis-tools" "redis" "${installMode}"
    redisCli="$(resolve_redis_cli_command 2>/dev/null || true)"
  fi
  [[ -n "${redisCli}" && -x "${redisCli}" ]] || die "Redis 客户端命令不可用"
  redisDb="$(conf_get REDIS_DATABASE 0)"
  redisArgs=(--no-auth-warning -h "${redisHost}" -p "${redisPort}" -n "${redisDb}")
  [[ -z "${redisUser}" ]] || redisArgs+=(--user "${redisUser}")
  redisVersion="$(REDISCLI_AUTH="${redisPwd}" "${redisCli}" "${redisArgs[@]}" INFO server 2>/dev/null \
    | awk -F: '$1=="redis_version" {gsub("\r", "", $2); print $2; exit}')"
  redisMajor="${redisVersion%%.*}"
  [[ "${redisMajor}" =~ ^[0-9]+$ && "${redisMajor}" -ge 6 ]] \
    || die "Redis 认证失败或版本不符合（需要6+，检测结果: ${redisVersion:-不可读取}）"
  ok "Redis ${redisVersion} 已可用且版本符合，跳过安装"
  check_external_rocketmq_connectivity manual
}

# 供在线升级器在备份、执行增量 SQL 和切换程序前调用。目标版本的部署脚本
# 必须先把对应部署方式需要的运行环境准备完整，避免新版本已经切换后才发现
# FFmpeg、中文字体或运行镜像缺失。
do_upgrade_runtime_preflight() {
  require_root
  local expectedMode="${1:-}" mode installMode
  mode="$(detect_mode)"
  [[ "${mode}" != "none" ]] || die "尚未部署，无法执行升级运行环境检查"
  case "${expectedMode}" in
    '') ;;
    docker|manual)
      [[ "${mode}" == "${expectedMode}" ]] \
        || die "升级运行环境检查方式不一致：当前 ${mode}，要求 ${expectedMode}"
      ;;
    *) die "升级运行环境检查方式仅支持 docker 或 manual" ;;
  esac

  installMode="$(dependency_install_mode "${mode}")"
  export AID_DEPENDENCY_INSTALL_MODE="${installMode}"
  section "升级前运行环境检查"
  case "${mode}" in
    docker)
      [[ -f "${ENV_FILE}" ]] || die "Docker 配置文件不存在: ${ENV_FILE}"
      require_docker_runtime
      prepare_docker_runtime_images
      ;;
    manual)
      [[ -f "${CONF}" ]] || die "手动部署配置文件不存在: ${CONF}"
      ensure_manual_host_dependencies
      ;;
  esac
  ok "目标版本运行环境检查通过（${mode}）"
}

# 当前部署版本优先读取升级器同步维护的 build-info.json，旧环境回退到配置记录。
current_version() {
  local buildInfo="${DATA_ROOT}/app/build-info.json" version=""
  if [[ -f "${buildInfo}" ]]; then
    version="$(grep -E '^[[:space:]]*"version"[[:space:]]*:' "${buildInfo}" 2>/dev/null | head -n 1 \
      | sed -E 's/^[^:]+:[[:space:]]*"([^"]+)".*/\1/')"
  fi
  echo "${version:-$(state_get CURRENT_VERSION '未知')}"
}

# 版本号只能说明本地已有对应产物，不能代表服务已经成功启动。这里单独检查
# 应用栈运行状态，供重复执行 install/update 时决定是直接退出还是进入自愈。
docker_container_running_healthy() { # docker_container_running_healthy <容器名>
  local container="$1" status health
  status="$(docker inspect --format '{{.State.Status}}' "${container}" 2>/dev/null || true)"
  [[ "${status}" == "running" ]] || return 1
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "${container}" 2>/dev/null || true)"
  [[ -z "${health}" || "${health}" == "healthy" ]]
}

updater_health_fresh() {
  local modified now
  [[ -s "${UPDATER_DATA_DIR:-/var/lib/aid-updater}/health.json" ]] || return 1
  modified="$(stat -c '%Y' "${UPDATER_DATA_DIR:-/var/lib/aid-updater}/health.json" 2>/dev/null || echo 0)"
  now="$(date +%s)"
  [[ "${modified}" =~ ^[0-9]+$ ]] && (( now - modified < 60 ))
}

updater_runtime_ready() { # updater_runtime_ready <docker|manual>
  local mode="$1"
  [[ -x "${DATA_ROOT}/app/updater/aid-updater" && -s "${UPDATER_CONFIG_FILE:-/etc/aid-updater/config.json}" ]] \
    || return 1
  case "${mode}" in
    docker) docker_container_running_healthy aid-updater && updater_health_fresh ;;
    manual)
      [[ -x /usr/local/bin/aid-updater ]] \
        && systemctl list-unit-files 2>/dev/null | grep -q '^aid-updater\.service' \
        && systemctl is-active --quiet aid-updater \
        && updater_health_fresh
      ;;
    *) return 1 ;;
  esac
}

deployment_application_ready() { # deployment_application_ready <docker|manual>
  local mode="$1" container
  [[ -f "${DATA_ROOT}/app/web-dist/index.html" \
     && -f "${DATA_ROOT}/app/web-dist/200.html" ]] || return 1
  case "${mode}" in
    docker)
      for container in aid-server aid-web aid-nginx; do
        docker_container_running_healthy "${container}" || return 1
      done
      if docker_profile_enabled mysql; then
        docker_container_running_healthy aid-mysql || return 1
      fi
      if docker_profile_enabled redis; then
        docker_container_running_healthy aid-redis || return 1
      fi
      if docker_profile_enabled mq; then
        docker_container_running_healthy aid-rocketmq-nameserver || return 1
        docker_container_running_healthy aid-rocketmq-broker || return 1
      fi
      if docker_profile_enabled https; then
        docker_container_running_healthy aid-nginx-https || return 1
      fi
      updater_runtime_ready docker || return 1
      ;;
    manual)
      systemctl is-active --quiet aid || return 1
      [[ -f "${DATA_ROOT}/app/web-dist/index.html" \
         && -f "${DATA_ROOT}/app/web-dist/200.html" ]] || return 1
      select_existing_nginx_runtime >/dev/null 2>&1 || return 1
      nginx_runtime_active || return 1
      updater_runtime_ready manual || return 1
      ;;
    *) return 1 ;;
  esac
}

deployment_artifacts_ready() {
  [[ -s "${DATA_ROOT}/app/aid-admin.jar" \
     && -f "${DATA_ROOT}/app/admin-dist/index.html" \
     && -f "${DATA_ROOT}/app/web-dist/index.html" \
     && -f "${DATA_ROOT}/app/web-dist/200.html" ]]
}

# ----------------------------------------------------------------------------
# 官方版本发现、签名核验、源码构建与单文件自举
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

sha512_file() { # sha512_file <文件>
  if command -v sha512sum >/dev/null 2>&1; then
    sha512sum "$1" | awk '{print tolower($1)}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 512 "$1" | awk '{print tolower($1)}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha512 "$1" | awk '{print tolower($NF)}'
  else
    return 1
  fi
}

md5_file() { # md5_file <文件>；仅用于校验 MySQL 官方归档页公布的固定摘要
  if command -v md5sum >/dev/null 2>&1; then
    md5sum "$1" | awk '{print tolower($1)}'
  elif command -v md5 >/dev/null 2>&1; then
    md5 -q "$1" | tr '[:upper:]' '[:lower:]'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -md5 "$1" | awk '{print tolower($NF)}'
  else
    return 1
  fi
}

require_download_tools() {
  local installMode="${AID_DEPENDENCY_INSTALL_MODE:-auto}"
  ensure_host_command curl "curl" "curl ca-certificates" "curl ca-certificates" "${installMode}"
  ensure_host_command tar "tar" "tar" "tar" "${installMode}"
  if ! sha256_file "${BASH_SOURCE[0]}" >/dev/null 2>&1; then
    [[ "${installMode}" == "auto" ]] \
      || die "缺少 SHA256 校验工具（sha256sum/shasum/openssl），请安装后重试"
    install_os_packages "SHA256校验工具" "coreutils" "coreutils"
    sha256_file "${BASH_SOURCE[0]}" >/dev/null 2>&1 || die "SHA256校验工具安装后仍不可用"
  else
    ok "SHA256校验工具已存在，跳过安装"
  fi
}

file_digest_matches() { # file_digest_matches <文件> <sha256|sha512|md5> <固定摘要>
  local file="$1" algorithm="${2,,}" expected="${3,,}" actual=""
  [[ -s "${file}" && -n "${expected}" ]] || return 1
  case "${algorithm}" in
    sha256) actual="$(sha256_file "${file}" 2>/dev/null || true)" ;;
    sha512) actual="$(sha512_file "${file}" 2>/dev/null || true)" ;;
    md5) actual="$(md5_file "${file}" 2>/dev/null || true)" ;;
    *) return 1 ;;
  esac
  [[ "${actual,,}" == "${expected}" ]]
}

download_with_curl_ipv4_fallback() { # <URL> <目标文件> <是否断点续传> <名称>
  local url="$1" output="$2" resume="${3:-no}" label="$4" curlCode=0
  local totalTimeout="" minSpeed="${DOWNLOAD_MIN_SPEED_BYTES}" lowSpeedSeconds="${DOWNLOAD_LOW_SPEED_SECONDS}"
  local -a curlArgs=()
  totalTimeout="$(download_timeout_setting)"
  [[ "${minSpeed}" =~ ^[1-9][0-9]*$ ]] || minSpeed=1024
  [[ "${lowSpeedSeconds}" =~ ^[1-9][0-9]*$ ]] || lowSpeedSeconds=30
  curlArgs=(--fail --location --retry 3 --retry-delay 2 --connect-timeout 15
    --speed-limit "${minSpeed}" --speed-time "${lowSpeedSeconds}"
    --proto '=https' --tlsv1.2 --progress-bar)
  (( totalTimeout == 0 )) || curlArgs+=(--max-time "${totalTimeout}")
  if [[ "${resume}" == "yes" ]]; then
    curl "${curlArgs[@]}" --continue-at - --output "${output}" "${url}" || curlCode=$?
  else
    curl "${curlArgs[@]}" --output "${output}" "${url}" || curlCode=$?
  fi
  (( curlCode == 0 )) && return 0
  # 33/36 表示服务端不支持 Range；调用方会清除 .part 后按原逻辑重试完整文件。
  if [[ "${resume}" == "yes" ]] && (( curlCode == 33 || curlCode == 36 )); then
    return "${curlCode}"
  fi
  warn "${label} 默认网络连接失败（curl ${curlCode}），正在强制 IPv4 重试"
  curlCode=0
  if [[ "${resume}" == "yes" ]]; then
    curl "${curlArgs[@]}" --ipv4 --continue-at - --output "${output}" "${url}" || curlCode=$?
  else
    curl "${curlArgs[@]}" --ipv4 --output "${output}" "${url}" || curlCode=$?
  fi
  return "${curlCode}"
}

download_with_wget_ipv4_fallback() { # <URL> <目标文件> <是否断点续传> <名称>
  local url="$1" output="$2" resume="${3:-no}" label="$4" wgetCode=0
  local -a wgetArgs=()
  # wget 必须具备 HTTPS-only 与 TLS 1.2 强制能力，不能因为切换下载工具而降低传输安全。
  if ! wget --help 2>&1 | grep -Fq -- '--https-only' \
      || ! wget --help 2>&1 | grep -Fq -- '--secure-protocol'; then
    err "当前 wget 不支持 HTTPS/TLS 安全下载参数"
    return 1
  fi
  wgetArgs=(--https-only --secure-protocol=TLSv1_2 --tries=3 --waitretry=2 --timeout=15)
  [[ "${resume}" == "yes" ]] && wgetArgs+=(--continue)
  wget "${wgetArgs[@]}" --output-document="${output}" "${url}" || wgetCode=$?
  (( wgetCode == 0 )) && return 0
  warn "${label} 默认网络连接失败（wget ${wgetCode}），正在强制 IPv4 重试"
  wgetCode=0
  wget "${wgetArgs[@]}" --inet4-only --output-document="${output}" "${url}" || wgetCode=$?
  return "${wgetCode}"
}

try_download() { # try_download <URL> <目标文件> <名称> [摘要算法] [固定摘要]
  local url="$1" target="$2" label="$3" algorithm="${4:-}" expected="${5:-}"
  local part="${2}.part" currentSize=0 downloadCode=0 downloadClient=""
  case "${url}" in
    https://*) ;;
    *) err "拒绝非 HTTPS 下载地址: ${url}"; return 1 ;;
  esac
  if [[ -n "${algorithm}" && -n "${expected}" ]] && file_digest_matches "${target}" "${algorithm}" "${expected}"; then
    ok "${label} 完整缓存校验通过，跳过下载: ${target}"
    return 0
  fi
  if [[ -n "${algorithm}" && -n "${expected}" ]] && file_digest_matches "${part}" "${algorithm}" "${expected}"; then
    mv -f -- "${part}" "${target}"
    ok "${label} 未完成缓存实际已完整，经摘要校验后直接复用"
    return 0
  fi
  if command -v curl >/dev/null 2>&1; then
    downloadClient="curl"
  elif command -v wget >/dev/null 2>&1; then
    downloadClient="wget"
  else
    err "缺少 HTTPS 下载工具（curl/wget）"
    return 1
  fi
  log "${C_BLUE}下载 ${label}${C_RESET}"
  echo "  ${url}"
  if [[ -s "${part}" ]]; then
    currentSize="$(wc -c < "${part}" | tr -d '[:space:]')"
    warn "发现 ${label} 未完成缓存（${currentSize:-0} 字节），从断点继续；切换镜像不会从 0 开始"
    if [[ "${downloadClient}" == "curl" ]]; then
      download_with_curl_ipv4_fallback "${url}" "${part}" yes "${label}" || downloadCode=$?
    else
      download_with_wget_ipv4_fallback "${url}" "${part}" yes "${label}" || downloadCode=$?
    fi
    if [[ "${downloadClient}" == "curl" ]] && (( downloadCode == 33 || downloadCode == 36 )); then
      warn "当前地址不支持断点续传，清理未完成缓存后从该地址重新下载"
      rm -f -- "${part}"
      downloadCode=0
      download_with_curl_ipv4_fallback "${url}" "${part}" no "${label}" || downloadCode=$?
    fi
  else
    if [[ "${downloadClient}" == "curl" ]]; then
      download_with_curl_ipv4_fallback "${url}" "${part}" no "${label}" || downloadCode=$?
    else
      download_with_wget_ipv4_fallback "${url}" "${part}" no "${label}" || downloadCode=$?
    fi
  fi
  if (( downloadCode != 0 )); then
    [[ ! -s "${part}" ]] || warn "下载中断，已保留断点文件: ${part}"
    return 1
  fi
  [[ -s "${part}" ]] || { rm -f -- "${part}"; return 1; }
  if [[ -n "${algorithm}" && -n "${expected}" ]] \
      && ! file_digest_matches "${part}" "${algorithm}" "${expected}"; then
    warn "${label} 下载完成但 ${algorithm^^} 不匹配，已删除不可信文件"
    rm -f -- "${part}"
    return 1
  fi
  mv -f -- "${part}" "${target}"
  return 0
}

bt_node_allowed() {
  local node="$1"
  [[ " ${BT_MIRROR_NODES_CN} " == *" ${node} "* ]]
}

probe_bt_node() { # probe_bt_node <节点>；输出“高速/普通 评分 延迟毫秒”
  local node="$1" testPath="net_test" result meta body code elapsed score latency
  [[ "${node}" != "https://cf1-node.aapanel.com" ]] || testPath="1net_test"
  result="$(curl --silent --show-error --connect-timeout 3 --max-time 3 \
    --proto '=https' --tlsv1.2 --write-out $'\n%{http_code} %{time_total}' \
    "${node}/${testPath}" 2>/dev/null || true)"
  meta="${result##*$'\n'}"; body="${result%$'\n'*}"
  code="${meta%% *}"; elapsed="${meta#* }"
  score="$(printf '%s' "${body}" | tr -cd '0-9' | head -c 12)"
  [[ "${code}" == "200" && "${score}" =~ ^[0-9]+$ && "${elapsed}" =~ ^[0-9]+([.][0-9]+)?$ ]] || return 1
  latency="$(awk -v t="${elapsed}" 'BEGIN { n=int(t*1000)-500; if(n<0)n=0; print n }')"
  (( score >= 1500 )) || return 1
  if (( latency < 300 )); then
    printf 'fast %s %s\n' "${score}" "${latency}"
  else
    printf 'normal %s %s\n' "${score}" "${latency}"
  fi
}

rank_bt_mirror_nodes() { # 按吞吐量与首包延迟输出完整节点顺序，而不是只取一个节点
  local node metrics fast="" normal="" deferred="" seen=$'\n'
  for node in "$@"; do
    [[ -n "${node}" && "${seen}" != *$'\n'"${node}"$'\n'* ]] || continue
    seen+="${node}"$'\n'
    if metrics="$(probe_bt_node "${node}" 2>/dev/null)"; then
      echo "[$(date '+%H:%M:%S')] AID 国内镜像测速: ${metrics%% *} ${metrics#* }  ${node}" >&2
      if [[ "${metrics%% *}" == "fast" ]]; then
        metrics="${metrics#* }"; fast+="${metrics%% *} ${node}"$'\n'
      else
        metrics="${metrics#* }"; normal+="${metrics#* } ${node}"$'\n'
      fi
    else
      echo "[$(date '+%H:%M:%S')] [提示] AID 国内镜像测速不可达，保留为末位重试: ${node}" >&2
      deferred+="${node}"$'\n'
    fi
  done
  [[ -z "${fast}" ]] || printf '%s' "${fast}" | sort -k1,1nr | awk '{print $2}'
  [[ -z "${normal}" ]] || printf '%s' "${normal}" | sort -k1,1n | awk '{print $2}'
  [[ -z "${deferred}" ]] || printf '%s' "${deferred}"
}

resolve_bt_mirror_order() {
  local cacheDir="${DATA_ROOT}/build-cache" cacheFile now modified age node order="" configured
  local candidateSet="" seen=" " cacheCount=0
  local -a candidates=() uniqueCandidates=()
  (( BT_MIRRORS_RESOLVED == 0 )) || return 0
  resolve_dependency_region
  # 新名称面向用户保持 AID 品牌；旧变量仅为已部署环境提供兼容读取。
  configured="${AID_DEPENDENCY_MIRROR_NODES:-${AID_BT_MIRROR_NODES:-}}"
  if [[ -n "${configured}" ]]; then
    configured="${configured//,/ }"
    read -r -a candidates <<< "${configured}"
    for node in "${candidates[@]}"; do
      bt_node_allowed "${node}" || die "AID_DEPENDENCY_MIRROR_NODES 只允许使用内置 AID HTTPS 镜像节点: ${node}"
    done
  elif [[ "${RESOLVED_DEPENDENCY_REGION}" == "cn" ]]; then
    read -r -a candidates <<< "${BT_MIRROR_NODES_CN}"
  else
    read -r -a candidates <<< "${BT_MIRROR_NODES_GLOBAL}"
  fi
  for node in "${candidates[@]}"; do
    [[ "${seen}" != *" ${node} "* ]] || continue
    seen+="${node} "; uniqueCandidates+=("${node}")
  done
  candidates=("${uniqueCandidates[@]}")
  candidateSet=" ${candidates[*]} "
  mkdir -p "${cacheDir}"
  cacheFile="${cacheDir}/bt-mirror-order.conf"; now="$(date +%s)"
  if [[ -f "${cacheFile}" ]]; then
    modified="$(stat -c %Y "${cacheFile}" 2>/dev/null || echo 0)"; age=$((now - modified))
    if (( age >= 0 && age < 86400 )); then
      while IFS= read -r node; do
        if bt_node_allowed "${node}" && [[ "${candidateSet}" == *" ${node} "* ]]; then
          order+="${node}"$'\n'; cacheCount=$((cacheCount + 1))
        fi
      done < "${cacheFile}"
      (( cacheCount == ${#candidates[@]} )) || order=""
    fi
  fi
  if [[ -z "${order}" ]]; then
    order="$(rank_bt_mirror_nodes "${candidates[@]}")"
    [[ -n "${order}" ]] || order="https://download.bt.cn"
    printf '%s\n' "${order}" > "${cacheFile}.tmp.$$"
    chmod 600 "${cacheFile}.tmp.$$"
    mv -f -- "${cacheFile}.tmp.$$" "${cacheFile}"
  else
    ok "复用 24 小时内的 AID 国内镜像测速结果: ${cacheFile}"
  fi
  BT_MIRROR_ORDER="${order}"
  BT_MIRRORS_RESOLVED=1
}

bt_artifact_urls() { # bt_artifact_urls <AID依赖镜像节点内相对路径>
  local path="${1#/}" node
  # resolve 会输出测速/缓存诊断；URL 生产函数的 stdout 必须保持纯净，供 mapfile 安全捕获。
  resolve_bt_mirror_order >&2
  while IFS= read -r node; do
    [[ -n "${node}" ]] && printf '%s/%s\n' "${node}" "${path}"
  done <<< "${BT_MIRROR_ORDER}"
}

probe_download_url() { # probe_download_url <URL>；输出“字节每秒 首包毫秒”
  local url="$1" result code speed first
  result="$(curl --silent --show-error --location --range 0-65535 --output /dev/null \
    --connect-timeout 3 --max-time 8 --proto '=https' --tlsv1.2 \
    --write-out '%{http_code} %{speed_download} %{time_starttransfer}' "${url}" 2>/dev/null || true)"
  code="${result%% *}"; result="${result#* }"
  speed="${result%% *}"; first="${result#* }"
  [[ "${code}" == "200" || "${code}" == "206" ]] || return 1
  [[ "${speed}" =~ ^[0-9]+([.][0-9]+)?$ && "${first}" =~ ^[0-9]+([.][0-9]+)?$ ]] || return 1
  awk -v s="${speed}" -v f="${first}" 'BEGIN { printf "%d %d\n", s, f * 1000 }'
}

# 与成熟面板安装器一致：候选下载源先用 64KiB Range 请求测速，吞吐优先、首包延迟次之。
# 探测失败的源仍保留到末尾参与完整下载，避免 HEAD/Range 协议差异造成误判。
rank_download_urls() { # rank_download_urls <用途> <URL...>
  local label="$1" url metrics ranked="" deferred="" seen=$'\n'
  shift
  for url in "$@"; do
    [[ -n "${url}" && "${seen}" != *$'\n'"${url}"$'\n'* ]] || continue
    seen+="${url}"$'\n'
    if metrics="$(probe_download_url "${url}" 2>/dev/null)"; then
      echo "[$(date '+%H:%M:%S')] ${label}测速: ${metrics%% *}B/s，首包 ${metrics#* }ms  ${url}" >&2
      ranked+="${metrics} ${url}"$'\n'
    else
      echo "[$(date '+%H:%M:%S')] [提示] ${label}测速不可达，保留为末位重试: ${url}" >&2
      deferred+="${url}"$'\n'
    fi
  done
  [[ -z "${ranked}" ]] || printf '%s' "${ranked}" | sort -k1,1nr -k2,2n | awk '{print $3}'
  [[ -z "${deferred}" ]] || printf '%s' "${deferred}"
}

detect_glibc_version() {
  local raw="" LC_ALL=C
  if command -v getconf >/dev/null 2>&1; then
    raw="$(getconf GNU_LIBC_VERSION 2>/dev/null || true)"
  fi
  if [[ "${raw}" =~ ([0-9]+\.[0-9]+) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
    return 0
  fi
  if command -v ldd >/dev/null 2>&1; then
    raw="$(ldd --version 2>&1 | head -n 1 || true)"
  fi
  if [[ "${raw}" =~ ([0-9]+\.[0-9]+) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
    return 0
  fi
  return 1
}

node_runtime_matches() { # node_runtime_matches <node二进制>
  local binary="$1" output="" firstLine=""
  NODE_RUNTIME_ERROR=""
  if [[ ! -x "${binary}" ]]; then
    NODE_RUNTIME_ERROR="未找到可执行文件 ${binary}"
    return 1
  fi
  if ! output="$("${binary}" -v 2>&1)"; then
    output="${output//$'\r'/}"
    firstLine="${output%%$'\n'*}"
    NODE_RUNTIME_ERROR="${firstLine:-执行失败且未返回错误信息}"
    return 1
  fi
  output="${output//$'\r'/}"
  if [[ "${output}" != "v${NODE_VERSION}" ]]; then
    firstLine="${output%%$'\n'*}"
    NODE_RUNTIME_ERROR="期望 v${NODE_VERSION}，实际 ${firstLine:-无版本输出}"
    return 1
  fi
  return 0
}

jdk_home_metadata_matches() { # jdk_home_metadata_matches <JDK目录> <x64|aarch64>
  local home="$1" archiveArch="$2" releaseFile expectedOsArch
  releaseFile="${home}/release"
  case "${archiveArch}" in
    x64) expectedOsArch="x86_64" ;;
    aarch64) expectedOsArch="aarch64" ;;
    *) return 1 ;;
  esac
  [[ -d "${home}" && ! -L "${home}" && -x "${home}/bin/java" && -f "${releaseFile}" ]] \
    || return 1
  grep -Fxq "JAVA_VERSION=\"${JDK_VERSION}\"" "${releaseFile}" \
    && grep -Fxq 'IMPLEMENTOR="Eclipse Adoptium"' "${releaseFile}" \
    && grep -Fxq "IMPLEMENTOR_VERSION=\"Temurin-${JDK_VERSION}+${JDK_BUILD}\"" "${releaseFile}" \
    && grep -Fxq "OS_ARCH=\"${expectedOsArch}\"" "${releaseFile}" \
    && grep -Fxq 'OS_NAME="Linux"' "${releaseFile}"
}

jdk_runtime_matches() { # jdk_runtime_matches <JDK目录>
  local home="$1" output="" firstLine=""
  [[ -x "${home}/bin/java" ]] || return 1
  if ! output="$("${home}/bin/java" -version 2>&1)"; then
    return 1
  fi
  output="${output//$'\r'/}"
  firstLine="${output%%$'\n'*}"
  [[ "${firstLine}" == "openjdk version \"${JDK_VERSION}\""* ]]
}

prepare_exact_jdk() {
  local arch checksum name cacheDir archive officialUrl cnUrl downloaded="no" url tmp installMode
  case "$(uname -m)" in
    x86_64|amd64)
      arch=x64
      checksum=be7668bc030d578b83d6d5ef9221d6d6729bbbca8cf94a7d52e16ac68b5a5a35 ;;
    aarch64|arm64)
      arch=aarch64
      checksum=d143936f473a4cb24e3b0e247d6d0775769d55ec9775c339540e753059a8d77a ;;
    *) die "OpenJDK ${JDK_VERSION} 暂不支持当前架构: $(uname -m)" ;;
  esac
  name="OpenJDK17U-jdk_${arch}_linux_hotspot_${JDK_VERSION}_${JDK_BUILD}.tar.gz"
  cacheDir="${DATA_ROOT}/build-cache/toolchains"
  archive="${cacheDir}/${name}"
  JDK_HOME="${cacheDir}/temurin-${JDK_VERSION}-${arch}"
  # Docker 控制面可能运行在 Alpine/musl 中，不能在这里直接执行 glibc JDK。
  # 固定归档先由 SHA256 与 release 元数据确认，真实执行能力在 Debian 目标镜像内复检。
  if jdk_home_metadata_matches "${JDK_HOME}" "${arch}"; then
    ok "Temurin OpenJDK ${JDK_VERSION} 固定运行时元数据已匹配，跳过下载: ${JDK_HOME}"
    return 0
  fi
  require_download_tools
  mkdir -p "${cacheDir}"
  if [[ -f "${archive}" ]]; then
    actual="$(sha256_file "${archive}" || true)"
    if [[ "${actual}" != "${checksum}" ]]; then
      warn "OpenJDK 缓存校验失败，将重新下载: ${archive}"
      rm -f -- "${archive}"
    fi
  fi
  if [[ ! -f "${archive}" ]]; then
    installMode="${AID_DEPENDENCY_INSTALL_MODE:-auto}"
    [[ "${installMode}" == "auto" ]] \
      || die "缺少 Temurin OpenJDK ${JDK_VERSION}；请放入 ${archive}，或把 DEPENDENCY_INSTALL_MODE 改为 auto"
    resolve_dependency_region
    officialUrl="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-${JDK_VERSION}%2B${JDK_BUILD}/${name}"
    cnUrl="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/${arch}/linux/${name}"
    local -a urls=()
    [[ -z "${AID_JDK_DOWNLOAD_URL:-}" ]] || urls+=("${AID_JDK_DOWNLOAD_URL}")
    if [[ "${RESOLVED_DEPENDENCY_REGION}" == "cn" ]]; then
      urls+=("${cnUrl}" "${officialUrl}")
    else
      urls+=("${officialUrl}" "${cnUrl}")
    fi
    mapfile -t urls < <(rank_download_urls "OpenJDK" "${urls[@]}")
    for url in "${urls[@]}"; do
      if try_download "${url}" "${archive}" "Temurin OpenJDK ${JDK_VERSION}（${arch}）" sha256 "${checksum}"; then
        actual="$(sha256_file "${archive}" || true)"
        if [[ "${actual}" == "${checksum}" ]]; then
          downloaded=yes
          break
        fi
        warn "OpenJDK 下载文件 SHA256 不匹配，拒绝使用并尝试备用地址"
        rm -f -- "${archive}"
      else
        warn "OpenJDK 当前下载地址不可用，尝试备用地址"
      fi
    done
    [[ "${downloaded}" == "yes" ]] \
      || die "Temurin OpenJDK ${JDK_VERSION} 下载失败；国内镜像和官方地址均不可用"
  fi
  tmp="${JDK_HOME}.tmp.$$"
  rm -rf -- "${tmp}"
  mkdir -p "${tmp}"
  if ! tar -xzf "${archive}" -C "${tmp}" --strip-components=1; then
    rm -rf -- "${tmp}"
    die "OpenJDK 压缩包解压失败"
  fi
  if ! jdk_home_metadata_matches "${tmp}" "${arch}"; then
    rm -rf -- "${tmp}"
    die "OpenJDK ${JDK_VERSION} 固定归档元数据或架构不匹配"
  fi
  rm -rf -- "${JDK_HOME}"
  mv "${tmp}" "${JDK_HOME}" || die "OpenJDK 安装目录就位失败"
  ok "Temurin OpenJDK ${JDK_VERSION} 已通过官方 SHA256 校验: ${JDK_HOME}"
}

# 非 Docker 部署使用 AID 国内镜像节点提供的 Oracle JDK 17.0.8 归档；Docker 构建和运行镜像
# 继续使用上面的 Temurin 17.0.20，避免改变已经发布的容器运行时基线。
prepare_manual_jdk() {
  local machineArch btArch oracleArch checksum name cacheDir archive actual downloaded=no url tmp installMode
  local systemJava systemJdkHome=""
  local -a urls=()
  machineArch="$(uname -m)"
  case "${machineArch}" in
    x86_64|amd64)
      btArch=x64; oracleArch=x64
      checksum="74b528a33bb2dfa02b4d74a0d66c9aff52e4f52924ce23a62d7f9eb1a6744657" ;;
    aarch64|arm64)
      btArch=arm; oracleArch=aarch64
      checksum="cd24d7b21ec0791c5a77dfe0d9d7836c5b1a8b4b75db7d33d253d07caa243117" ;;
    *) die "Oracle JDK ${MANUAL_JDK_VERSION} 暂不支持当前架构: ${machineArch}" ;;
  esac
  name="jdk-${MANUAL_JDK_VERSION}.tar.gz"
  cacheDir="${DATA_ROOT}/build-cache/toolchains"
  archive="${cacheDir}/oracle-jdk-${MANUAL_JDK_VERSION}-${btArch}.tar.gz"
  JDK_HOME="${DATA_ROOT}/runtime/jdk-${MANUAL_JDK_VERSION}-${btArch}"
  if [[ ! -x "${JDK_HOME}/bin/java" ]]; then
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" && -x "${JAVA_HOME}/bin/javac" ]]; then
      systemJdkHome="${JAVA_HOME}"
    elif command -v java >/dev/null 2>&1; then
      systemJava="$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)"
      systemJdkHome="$(dirname "$(dirname "${systemJava}")")"
    fi
    if [[ -x "${systemJdkHome}/bin/java" && -x "${systemJdkHome}/bin/javac" ]] \
        && "${systemJdkHome}/bin/java" -version 2>&1 | head -n 1 | grep -Fq "${MANUAL_JDK_VERSION}"; then
      JDK_HOME="${systemJdkHome}"
    fi
  fi
  if [[ -x "${JDK_HOME}/bin/java" && -x "${JDK_HOME}/bin/javac" ]] \
      && "${JDK_HOME}/bin/java" -version 2>&1 | head -n 1 | grep -Fq "${MANUAL_JDK_VERSION}"; then
    ok "Oracle JDK ${MANUAL_JDK_VERSION} 已存在且版本匹配，跳过下载: ${JDK_HOME}"
  else
    require_download_tools
    mkdir -p "${cacheDir}" "${DATA_ROOT}/runtime"
    if [[ -f "${archive}" ]]; then
      actual="$(sha256_file "${archive}" 2>/dev/null || true)"
      if [[ "${actual}" != "${checksum}" ]]; then
        warn "JDK ${MANUAL_JDK_VERSION} 缓存不完整或摘要不匹配，将重新下载"
        rm -f -- "${archive}"
      fi
    fi
    if [[ ! -f "${archive}" ]]; then
      installMode="${AID_DEPENDENCY_INSTALL_MODE:-auto}"
      [[ "${installMode}" == "auto" ]] \
        || die "缺少 Oracle JDK ${MANUAL_JDK_VERSION}；请放入 ${archive}，或把 DEPENDENCY_INSTALL_MODE 改为 auto"
      mapfile -t urls < <(
        [[ -z "${AID_MANUAL_JDK_DOWNLOAD_URL:-}" ]] || printf '%s\n' "${AID_MANUAL_JDK_DOWNLOAD_URL}"
        bt_artifact_urls "src/jdk/${btArch}/${name}"
        printf '%s\n' "https://download.oracle.com/java/17/archive/jdk-${MANUAL_JDK_VERSION}_linux-${oracleArch}_bin.tar.gz"
      )
      for url in "${urls[@]}"; do
        [[ -n "${url}" ]] || continue
        if try_download "${url}" "${archive}" "Oracle JDK ${MANUAL_JDK_VERSION}（${btArch}）" sha256 "${checksum}"; then
          downloaded=yes
          break
        fi
        warn "JDK 当前节点不可用或摘要不匹配，切换下一个 AID 镜像/Oracle 节点"
      done
      [[ "${downloaded}" == "yes" ]] \
        || die "Oracle JDK ${MANUAL_JDK_VERSION} 下载失败或固定 SHA256 校验不通过"
    fi
    tmp="${JDK_HOME}.tmp.$$"
    rm -rf -- "${tmp}"; mkdir -p "${tmp}"
    tar -xzf "${archive}" -C "${tmp}" --strip-components=1 \
      || { rm -rf -- "${tmp}"; die "Oracle JDK 压缩包解压失败"; }
    if [[ ! -x "${tmp}/bin/java" || ! -x "${tmp}/bin/javac" ]] \
        || ! "${tmp}/bin/java" -version 2>&1 | head -n 1 | grep -Fq "${MANUAL_JDK_VERSION}"; then
      rm -rf -- "${tmp}"
      die "Oracle JDK 实际版本不是 ${MANUAL_JDK_VERSION} 或开发工具不完整"
    fi
    rm -rf -- "${JDK_HOME}"
    mv "${tmp}" "${JDK_HOME}" || die "Oracle JDK 安装目录就位失败"
    ok "Oracle JDK ${MANUAL_JDK_VERSION} 已通过 Oracle 固定 SHA256 校验: ${JDK_HOME}"
  fi

  mkdir -p "$(dirname "${JAVA_PROFILE_FILE}")"
  cat > "${JAVA_PROFILE_FILE}" <<EOF
# AID 非 Docker 运行环境；重新登录或执行 source ${JAVA_PROFILE_FILE} 后对交互终端生效。
# AID_MANAGED_JAVA_PROFILE=1
# AID_DATA_ROOT=${DATA_ROOT}
export JAVA_HOME="${JDK_HOME}"
export PATH="\${JAVA_HOME}/bin:\${PATH}"
EOF
  chmod 644 "${JAVA_PROFILE_FILE}"
  export JAVA_HOME="${JDK_HOME}"
  export PATH="${JAVA_HOME}/bin:${PATH}"
  hash -r 2>/dev/null || true
  ok "JAVA_HOME 与 PATH 已在当前安装进程立即生效，并持久化到 ${JAVA_PROFILE_FILE}"
}

prepare_exact_node() {
  local arch checksum name cacheDir archive actual officialUrl cnUrl downloaded="no" url tmp installMode
  local glibcVersion compatibilityBuild="no" buildLabel="官方构建" checksumSource="Node.js 官方发布摘要"
  case "$(uname -m)" in
    x86_64|amd64)
      arch=x64
      checksum=c33c39ed9c80deddde77c960d00119918b9e352426fd604ba41638d6526a4744 ;;
    aarch64|arm64)
      arch=arm64
      checksum=25ba95dfb96871fa2ef977f11f95ea90818c8fa15c0f2110771db08d4ba423be ;;
    *) die "Node.js ${NODE_VERSION} 暂不支持当前架构: $(uname -m)" ;;
  esac
  glibcVersion="$(detect_glibc_version || true)"
  [[ -n "${glibcVersion}" ]] || die "手动部署要求 glibc；当前系统无法识别 glibc 版本，请改用受支持的 Linux 或 Docker 部署"
  if [[ "$(version_compare "${glibcVersion}" "2.28.0")" == "-1" ]]; then
    [[ "${arch}" == "x64" ]] \
      || die "当前 ${arch} 系统的 glibc ${glibcVersion} 低于 Node.js 要求的 2.28；请升级系统或改用 Docker 部署"
    compatibilityBuild=yes
    buildLabel="glibc 2.17兼容构建"
    checksumSource="Node.js unofficial-builds 发布摘要"
    name="node-v${NODE_VERSION}-linux-x64-glibc-217.tar.xz"
    checksum=db4a1d582e6fffcf7fb348149ca4ac8fa685699c5bc46cd7e22bbf9a7e673454
  else
    name="node-v${NODE_VERSION}-linux-${arch}.tar.gz"
  fi
  cacheDir="${DATA_ROOT}/build-cache/toolchains"
  archive="${cacheDir}/${name}"
  NODE_HOME="${cacheDir}/node-${NODE_VERSION}-${arch}"
  if node_runtime_matches "${NODE_HOME}/bin/node"; then
    export PATH="${NODE_HOME}/bin:${PATH}"
    ok "Node.js ${NODE_VERSION}（${buildLabel}）已存在，跳过下载: ${NODE_HOME}"
    return 0
  fi
  if [[ -x "${NODE_HOME}/bin/node" ]]; then
    warn "现有 Node.js 无法运行或版本不匹配，将重新准备: ${NODE_RUNTIME_ERROR:-未知原因}"
  fi
  require_download_tools
  installMode="${AID_DEPENDENCY_INSTALL_MODE:-auto}"
  if [[ "${compatibilityBuild}" == "yes" ]]; then
    ensure_host_command xz "xz解压工具" "xz-utils" "xz" "${installMode}"
  fi
  mkdir -p "${cacheDir}"
  if [[ -f "${archive}" && "$(sha256_file "${archive}" || true)" != "${checksum}" ]]; then
    warn "Node.js 缓存校验失败，将重新下载"
    rm -f -- "${archive}"
  fi
  if [[ ! -f "${archive}" ]]; then
    [[ "${installMode}" == "auto" ]] \
      || die "缺少 Node.js ${NODE_VERSION}；请放入 ${archive}，或把 DEPENDENCY_INSTALL_MODE 改为 auto"
    resolve_dependency_region
    if [[ "${compatibilityBuild}" == "yes" ]]; then
      # Node.js 官方 Linux x64 二进制要求 glibc 2.28+。旧发行版使用 Node.js
      # unofficial-builds 项目在 glibc 2.17 上构建的同版本产物；国内地址只做
      # 字节镜像，最终仍以该项目发布的固定 SHA256 为信任边界。
      officialUrl="https://unofficial-builds.nodejs.org/download/release/v${NODE_VERSION}/${name}"
      # 国内兼容包固定随首个采用该工具链的正式 AID Release 发布，避免为了工具链
      # 暴露额外 Git Tag；内容仍以 Node.js unofficial-builds 固定 SHA256 为信任边界。
      cnUrl="https://gitee.com/gzxx-2025/aid-server/releases/download/v1.0.0-beta.2/${name}"
      warn "检测到 glibc ${glibcVersion}，将使用 Node.js ${NODE_VERSION} 的 glibc 2.17 兼容构建"
    else
      officialUrl="https://nodejs.org/dist/v${NODE_VERSION}/${name}"
      cnUrl="https://npmmirror.com/mirrors/node/v${NODE_VERSION}/${name}"
    fi
    local -a urls=()
    [[ -z "${AID_NODE_DOWNLOAD_URL:-}" ]] || urls+=("${AID_NODE_DOWNLOAD_URL}")
    if [[ "${RESOLVED_DEPENDENCY_REGION}" == "cn" ]]; then urls+=("${cnUrl}" "${officialUrl}"); else urls+=("${officialUrl}" "${cnUrl}"); fi
    mapfile -t urls < <(rank_download_urls "Node.js" "${urls[@]}")
    for url in "${urls[@]}"; do
      if try_download "${url}" "${archive}" "Node.js ${NODE_VERSION}（${buildLabel}）" sha256 "${checksum}" \
          && [[ "$(sha256_file "${archive}" || true)" == "${checksum}" ]]; then
        downloaded=yes; break
      fi
      warn "Node.js 当前下载地址不可用或校验失败，尝试备用地址"
      rm -f -- "${archive}"
    done
    [[ "${downloaded}" == "yes" ]] || die "Node.js ${NODE_VERSION} 下载失败或校验不通过"
  fi
  tmp="${NODE_HOME}.tmp.$$"
  rm -rf -- "${tmp}"; mkdir -p "${tmp}"
  if [[ "${compatibilityBuild}" == "yes" ]]; then
    tar -xJf "${archive}" -C "${tmp}" --strip-components=1 \
      || { rm -rf -- "${tmp}"; die "Node.js 兼容包解压失败"; }
  else
    tar -xzf "${archive}" -C "${tmp}" --strip-components=1 \
      || { rm -rf -- "${tmp}"; die "Node.js 压缩包解压失败"; }
  fi
  if ! node_runtime_matches "${tmp}/bin/node"; then
    actual="${NODE_RUNTIME_ERROR:-未知错误}"
    rm -rf -- "${tmp}"
    die "Node.js 无法在当前系统运行: ${actual}"
  fi
  rm -rf -- "${NODE_HOME}"; mv "${tmp}" "${NODE_HOME}" || die "Node.js 安装目录就位失败"
  export PATH="${NODE_HOME}/bin:${PATH}"
  ok "Node.js ${NODE_VERSION}（${buildLabel}）已通过 ${checksumSource} SHA256 校验: ${NODE_HOME}"
}

prepare_exact_maven() {
  local name cacheDir archive checksum actual officialUrl cnUrl downloaded="no" url tmp installMode
  name="apache-maven-${MAVEN_VERSION}-bin.tar.gz"
  checksum=a555254d6b53d267965a3404ecb14e53c3827c09c3b94b5678835887ab404556bfaf78dcfe03ba76fa2508649dca8531c74bca4d5846513522404d48e8c4ac8b
  cacheDir="${DATA_ROOT}/build-cache/toolchains"
  archive="${cacheDir}/${name}"
  MAVEN_HOME="${cacheDir}/maven-${MAVEN_VERSION}"
  if [[ -x "${MAVEN_HOME}/bin/mvn" ]] \
      && JAVA_HOME="${JDK_HOME}" "${MAVEN_HOME}/bin/mvn" -v 2>/dev/null | head -n 1 | grep -Fq "${MAVEN_VERSION}"; then
    export PATH="${MAVEN_HOME}/bin:${PATH}"
    ok "Maven ${MAVEN_VERSION} 已存在，跳过下载: ${MAVEN_HOME}"
    return 0
  fi
  require_download_tools
  if ! sha512_file "${BASH_SOURCE[0]}" >/dev/null 2>&1; then
    [[ "${AID_DEPENDENCY_INSTALL_MODE:-auto}" == "auto" ]] \
      || die "缺少 SHA512 校验工具，请安装 coreutils 或 openssl"
    install_os_packages "SHA512校验工具" "coreutils" "coreutils"
  fi
  mkdir -p "${cacheDir}"
  if [[ -f "${archive}" && "$(sha512_file "${archive}" || true)" != "${checksum}" ]]; then
    warn "Maven 缓存校验失败，将重新下载"
    rm -f -- "${archive}"
  fi
  if [[ ! -f "${archive}" ]]; then
    installMode="${AID_DEPENDENCY_INSTALL_MODE:-auto}"
    [[ "${installMode}" == "auto" ]] \
      || die "缺少 Maven ${MAVEN_VERSION}；请放入 ${archive}，或把 DEPENDENCY_INSTALL_MODE 改为 auto"
    resolve_dependency_region
    officialUrl="https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/${name}"
    cnUrl="https://repo.huaweicloud.com/apache/maven/maven-3/${MAVEN_VERSION}/binaries/${name}"
    local -a urls=()
    [[ -z "${AID_MAVEN_DOWNLOAD_URL:-}" ]] || urls+=("${AID_MAVEN_DOWNLOAD_URL}")
    if [[ "${RESOLVED_DEPENDENCY_REGION}" == "cn" ]]; then urls+=("${cnUrl}" "${officialUrl}"); else urls+=("${officialUrl}" "${cnUrl}"); fi
    mapfile -t urls < <(rank_download_urls "Maven" "${urls[@]}")
    for url in "${urls[@]}"; do
      if try_download "${url}" "${archive}" "Maven ${MAVEN_VERSION}" sha512 "${checksum}" \
          && [[ "$(sha512_file "${archive}" || true)" == "${checksum}" ]]; then
        downloaded=yes; break
      fi
      warn "Maven 当前下载地址不可用或校验失败，尝试备用地址"
      rm -f -- "${archive}"
    done
    [[ "${downloaded}" == "yes" ]] || die "Maven ${MAVEN_VERSION} 下载失败或校验不通过"
  fi
  tmp="${MAVEN_HOME}.tmp.$$"
  rm -rf -- "${tmp}"; mkdir -p "${tmp}"
  tar -xzf "${archive}" -C "${tmp}" --strip-components=1 || { rm -rf -- "${tmp}"; die "Maven 压缩包解压失败"; }
  [[ -x "${tmp}/bin/mvn" ]] || { rm -rf -- "${tmp}"; die "Maven 压缩包内容不完整"; }
  rm -rf -- "${MAVEN_HOME}"; mv "${tmp}" "${MAVEN_HOME}" || die "Maven 安装目录就位失败"
  export PATH="${MAVEN_HOME}/bin:${PATH}"
  ok "Maven ${MAVEN_VERSION} 已通过 Apache SHA512 校验: ${MAVEN_HOME}"
}

prepare_exact_go() {
  local arch checksum name cacheDir archive officialUrl cnUrl downloaded="no" url tmp installMode
  case "$(uname -m)" in
    x86_64|amd64)
      arch=amd64
      checksum=4fa4f869b0f7fc6bb1eb2660e74657fbf04cdd290b5aef905585c86051b34d43 ;;
    aarch64|arm64)
      arch=arm64
      checksum=fd017e647ec28525e86ae8203236e0653242722a7436929b1f775744e26278e7 ;;
    *) die "Go ${GO_VERSION} 暂不支持当前架构: $(uname -m)" ;;
  esac
  name="go${GO_VERSION}.linux-${arch}.tar.gz"
  cacheDir="${DATA_ROOT}/build-cache/toolchains"
  archive="${cacheDir}/${name}"
  GO_HOME="${cacheDir}/go-${GO_VERSION}-${arch}"
  if [[ -x "${GO_HOME}/bin/go" ]] && "${GO_HOME}/bin/go" version 2>/dev/null | grep -Fq "go${GO_VERSION}"; then
    export PATH="${GO_HOME}/bin:${PATH}"
    ok "Go ${GO_VERSION} 已存在，跳过下载: ${GO_HOME}"
    return 0
  fi
  require_download_tools
  mkdir -p "${cacheDir}"
  if [[ -f "${archive}" && "$(sha256_file "${archive}" || true)" != "${checksum}" ]]; then
    warn "Go 缓存校验失败，将重新下载"
    rm -f -- "${archive}"
  fi
  if [[ ! -f "${archive}" ]]; then
    installMode="${AID_DEPENDENCY_INSTALL_MODE:-auto}"
    [[ "${installMode}" == "auto" ]] \
      || die "缺少 Go ${GO_VERSION}；请放入 ${archive}，或把 DEPENDENCY_INSTALL_MODE 改为 auto"
    resolve_dependency_region
    officialUrl="https://go.dev/dl/${name}"
    cnUrl="https://mirrors.aliyun.com/golang/${name}"
    local -a urls=()
    [[ -z "${AID_GO_DOWNLOAD_URL:-}" ]] || urls+=("${AID_GO_DOWNLOAD_URL}")
    if [[ "${RESOLVED_DEPENDENCY_REGION}" == "cn" ]]; then urls+=("${cnUrl}" "${officialUrl}"); else urls+=("${officialUrl}" "${cnUrl}"); fi
    mapfile -t urls < <(rank_download_urls "Go" "${urls[@]}")
    for url in "${urls[@]}"; do
      if try_download "${url}" "${archive}" "Go ${GO_VERSION}（${arch}）" sha256 "${checksum}" \
          && [[ "$(sha256_file "${archive}" || true)" == "${checksum}" ]]; then
        downloaded=yes; break
      fi
      warn "Go 当前下载地址不可用或校验失败，尝试备用地址"
      rm -f -- "${archive}"
    done
    [[ "${downloaded}" == "yes" ]] || die "Go ${GO_VERSION} 下载失败或校验不通过"
  fi
  tmp="${GO_HOME}.tmp.$$"
  rm -rf -- "${tmp}"; mkdir -p "${tmp}"
  tar -xzf "${archive}" -C "${tmp}" --strip-components=1 || { rm -rf -- "${tmp}"; die "Go 压缩包解压失败"; }
  [[ -x "${tmp}/bin/go" ]] && "${tmp}/bin/go" version 2>/dev/null | grep -Fq "go${GO_VERSION}" \
    || { rm -rf -- "${tmp}"; die "Go 实际版本不是 ${GO_VERSION}"; }
  rm -rf -- "${GO_HOME}"; mv "${tmp}" "${GO_HOME}" || die "Go 安装目录就位失败"
  export PATH="${GO_HOME}/bin:${PATH}"
  ok "Go ${GO_VERSION} 已通过官方 SHA256 校验: ${GO_HOME}"
}

docker_jdk_runtime_matches() { # docker_jdk_runtime_matches <镜像>
  local image="$1" output="" firstLine=""
  if ! output="$(docker run --rm "${image}" java -version 2>&1)"; then
    return 1
  fi
  output="${output//$'\r'/}"
  firstLine="${output%%$'\n'*}"
  [[ "${firstLine}" == "openjdk version \"${JDK_VERSION}\""* ]]
}

prepare_jdk_runtime_image() {
  local baseImage="debian:bookworm-slim" dockerfile context imageRuntime fontManager
  local totalMemoryMb runtimeCpuMilli runtimeMemoryMb runtimeSwapMb runtimeMemorySwapMb runtimeCpuQuota
  local -a runtimeBuildCommand=()
  prepare_exact_jdk
  prepare_ffmpeg_runtime "${AID_DEPENDENCY_INSTALL_MODE:-auto}"
  imageRuntime="${FFMPEG_RUNTIME_ROOT}/ffmpeg-${FFMPEG_RUNTIME_VERSION}-${FFMPEG_RUNTIME_ARCH}"
  if docker image inspect "${JAVA_RUNTIME_IMAGE}" >/dev/null 2>&1; then
    if docker_jdk_runtime_matches "${JAVA_RUNTIME_IMAGE}" \
        && docker run --rm "${JAVA_RUNTIME_IMAGE}" \
          "${imageRuntime}/check-runtime.sh" "${imageRuntime}/ffmpeg" "${imageRuntime}/ffprobe" /tmp \
          "${FFMPEG_MIN_VERSION}" "${FFMPEG_REQUIRED_ENCODERS}" "${FFMPEG_REQUIRED_FILTERS}" \
          "${FFMPEG_RUNTIME_VERSION}" \
        && docker run --rm "${JAVA_RUNTIME_IMAGE}" \
          "${AID_FONT_ROOT}/check-font.sh" validate; then
      ok "OpenJDK ${JDK_VERSION}、AID FFmpeg ${FFMPEG_RUNTIME_VERSION} 与中文字体运行镜像已存在，跳过构建: ${JAVA_RUNTIME_IMAGE}"
      return 0
    fi
    warn "现有 Java 运行镜像的 JDK、FFmpeg 或中文字体能力不完整，将按固定运行时重建"
  fi
  ensure_docker_image "${baseImage}" "OpenJDK运行基础"
  context="$(mktemp -d "${DATA_ROOT}/build-cache/toolchains/.runtime-image.XXXXXX")" \
    || die "无法创建 Java/FFmpeg 镜像构建目录"
  mkdir -p "${context}/java" "${context}/ffmpeg"
  cp -a "${JDK_HOME}/." "${context}/java/" \
    || { rm -rf -- "${context}"; die "复制 OpenJDK 运行时失败"; }
  cp -a "${FFMPEG_RUNTIME_HOME}/." "${context}/ffmpeg/" \
    || { rm -rf -- "${context}"; die "复制 AID FFmpeg 运行时失败"; }
  fontManager="${context}/prepare-cjk-font.sh"
  write_aid_cjk_font_manager "${fontManager}"
  dockerfile="${context}/Dockerfile"
  cat > "${dockerfile}" <<EOF
FROM ${baseImage}
ARG AID_CJK_FONT_TENCENT_URL=
ARG AID_CJK_FONT_ALIYUN_URL=
ENV JAVA_HOME=/opt/java/openjdk
ENV AID_FFMPEG_HOME=${imageRuntime}
ENV AID_FFMPEG_PATH=${FFMPEG_RUNTIME_ROOT}/current/ffmpeg
ENV AID_FFPROBE_PATH=${FFMPEG_RUNTIME_ROOT}/current/ffprobe
ENV PATH=/opt/java/openjdk/bin:${FFMPEG_RUNTIME_ROOT}/current:\${PATH}
COPY java/ /opt/java/openjdk/
COPY ffmpeg/ ${imageRuntime}/
COPY prepare-cjk-font.sh /tmp/prepare-cjk-font.sh
RUN set -eu; \
    mkdir -p ${FFMPEG_RUNTIME_ROOT}; \
    ln -s ffmpeg-${FFMPEG_RUNTIME_VERSION}-${FFMPEG_RUNTIME_ARCH} ${FFMPEG_RUNTIME_ROOT}/current; \
    if ! apt-get update; then \
      if [ -f /etc/apt/sources.list.d/debian.sources ]; then \
        sed -i 's|http://deb.debian.org/debian-security|https://mirrors.aliyun.com/debian-security|g; s|http://deb.debian.org/debian|https://mirrors.aliyun.com/debian|g' /etc/apt/sources.list.d/debian.sources; \
      else \
        sed -i 's|http://deb.debian.org/debian-security|https://mirrors.aliyun.com/debian-security|g; s|http://deb.debian.org/debian|https://mirrors.aliyun.com/debian|g' /etc/apt/sources.list; \
      fi; \
      apt-get update; \
    fi; \
    apt-get install -y --no-install-recommends ca-certificates curl unzip fontconfig; \
    rm -rf /var/lib/apt/lists/*; \
    java -version; \
    ${imageRuntime}/check-runtime.sh ${imageRuntime}/ffmpeg ${imageRuntime}/ffprobe /tmp \
      '${FFMPEG_MIN_VERSION}' '${FFMPEG_REQUIRED_ENCODERS}' '${FFMPEG_REQUIRED_FILTERS}' \
      '${FFMPEG_RUNTIME_VERSION}'; \
    AID_CJK_FONT_TENCENT_URL="\${AID_CJK_FONT_TENCENT_URL}" \
      AID_CJK_FONT_ALIYUN_URL="\${AID_CJK_FONT_ALIYUN_URL}" \
      /tmp/prepare-cjk-font.sh prepare; \
    install -m 700 /tmp/prepare-cjk-font.sh ${AID_FONT_ROOT}/check-font.sh; \
    rm -f /tmp/prepare-cjk-font.sh; \
    ${AID_FONT_ROOT}/check-font.sh validate
EOF
  totalMemoryMb="$(awk '/^MemTotal:/ { print int($2 / 1024); exit }' /proc/meminfo 2>/dev/null || true)"
  [[ "${totalMemoryMb}" =~ ^[0-9]+$ && "${totalMemoryMb}" -gt 0 ]] || totalMemoryMb=4096
  runtimeCpuMilli="${AID_RUNTIME_BUILD_CPU_MILLI:-1000}"
  if [[ -n "${AID_RUNTIME_BUILD_MEMORY_MB:-}" ]]; then
    runtimeMemoryMb="${AID_RUNTIME_BUILD_MEMORY_MB}"
  elif (( totalMemoryMb <= 2048 )); then
    runtimeMemoryMb=512
  elif (( totalMemoryMb <= 5120 )); then
    runtimeMemoryMb=768
  else
    runtimeMemoryMb=1024
  fi
  runtimeSwapMb="${AID_RUNTIME_BUILD_SWAP_MB:-1024}"
  [[ "${runtimeCpuMilli}" =~ ^[1-9][0-9]*$ && "${runtimeCpuMilli}" -ge 100 && "${runtimeCpuMilli}" -le 8000 ]] \
    || { rm -rf -- "${context}"; die "AID_RUNTIME_BUILD_CPU_MILLI 必须为100-8000的整数"; }
  [[ "${runtimeMemoryMb}" =~ ^[1-9][0-9]*$ && "${runtimeMemoryMb}" -ge 384 && "${runtimeMemoryMb}" -le 8192 ]] \
    || { rm -rf -- "${context}"; die "AID_RUNTIME_BUILD_MEMORY_MB 必须为384-8192的整数"; }
  [[ "${runtimeSwapMb}" =~ ^(0|[1-9][0-9]*)$ && "${runtimeSwapMb}" -le 8192 ]] \
    || { rm -rf -- "${context}"; die "AID_RUNTIME_BUILD_SWAP_MB 必须为0-8192的整数"; }
  runtimeCpuQuota=$(( runtimeCpuMilli * 100 ))
  runtimeMemorySwapMb=$(( runtimeMemoryMb + runtimeSwapMb ))
  runtimeBuildCommand=(docker build
    --cpu-period 100000 --cpu-quota "${runtimeCpuQuota}" --cpu-shares 128
    --memory "${runtimeMemoryMb}m" --memory-swap "${runtimeMemorySwapMb}m")
  if command -v nice >/dev/null 2>&1; then
    runtimeBuildCommand=(nice -n 10 "${runtimeBuildCommand[@]}")
  fi
  if command -v ionice >/dev/null 2>&1; then
    runtimeBuildCommand=(ionice -c 2 -n 7 "${runtimeBuildCommand[@]}")
  fi
  log "构建 OpenJDK ${JDK_VERSION} + AID FFmpeg ${FFMPEG_RUNTIME_VERSION} + 中文字体固定运行镜像: ${JAVA_RUNTIME_IMAGE}"
  log "运行镜像构建资源上限：CPU ${runtimeCpuMilli}m，物理内存 ${runtimeMemoryMb}MiB，额外 Swap ${runtimeSwapMb}MiB，低 I/O 优先级"
  if ! "${runtimeBuildCommand[@]}" \
      --build-arg "AID_CJK_FONT_TENCENT_URL=${AID_CJK_FONT_TENCENT_URL:-}" \
      --build-arg "AID_CJK_FONT_ALIYUN_URL=${AID_CJK_FONT_ALIYUN_URL:-}" \
      --pull=false --tag "${JAVA_RUNTIME_IMAGE}" --file "${dockerfile}" "${context}"; then
    rm -rf -- "${context}"
    die "OpenJDK ${JDK_VERSION}、FFmpeg ${FFMPEG_RUNTIME_VERSION} 与中文字体运行镜像构建失败"
  fi
  rm -rf -- "${context}"
  docker_jdk_runtime_matches "${JAVA_RUNTIME_IMAGE}" \
    || die "OpenJDK运行镜像版本校验失败"
  docker run --rm "${JAVA_RUNTIME_IMAGE}" \
    "${imageRuntime}/check-runtime.sh" "${imageRuntime}/ffmpeg" "${imageRuntime}/ffprobe" /tmp \
    "${FFMPEG_MIN_VERSION}" "${FFMPEG_REQUIRED_ENCODERS}" "${FFMPEG_REQUIRED_FILTERS}" \
    "${FFMPEG_RUNTIME_VERSION}" \
    || die "FFmpeg运行镜像校验失败"
  docker run --rm "${JAVA_RUNTIME_IMAGE}" "${AID_FONT_ROOT}/check-font.sh" validate \
    || die "中文字体运行镜像校验失败"
  ok "OpenJDK ${JDK_VERSION}、AID FFmpeg ${FFMPEG_RUNTIME_VERSION} 与中文字体运行镜像已就绪"
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

# 从发布工具生成的格式化 latest.json 中读取指定渠道、平台的升级器制品字段。
# 只解析已经 verify_manifest_signature 校验过的本地清单，不接受任意 JSON 输入。
json_updater_package_string() { # json_updater_package_string <清单> <stable|beta> <linux_amd64|linux_arm64> <url|sha256|mirror>
  local manifest="$1" channel="$2" platform="$3" field="$4"
  awk -v channel="${channel}" -v platform="${platform}" -v field="${field}" '
    BEGIN {
      selected = (channel == "stable")
      inBeta = 0
      prefix = (channel == "beta") ? "    " : "  "
    }
    {
      line = $0
      if (channel == "beta") {
        if (line ~ /^  "beta"[[:space:]]*:[[:space:]]*\{/) { selected = 1; next }
        if (selected && line ~ /^  \},?[[:space:]]*$/) exit
      } else {
        if (line ~ /^  "beta"[[:space:]]*:[[:space:]]*\{/) { inBeta = 1; next }
        if (inBeta) {
          if (line ~ /^  \},?[[:space:]]*$/) inBeta = 0
          next
        }
      }
      if (!selected) next
      if (line ~ ("^" prefix "\"updater\"[[:space:]]*:[[:space:]]*\\{")) { inUpdater = 1; next }
      if (!inUpdater) next
      if (line ~ ("^" prefix "  \"packages\"[[:space:]]*:[[:space:]]*\\{")) { inPackages = 1; next }
      if (!inPackages) next
      if (line ~ ("^" prefix "    \"" platform "\"[[:space:]]*:[[:space:]]*\\{")) { inPlatform = 1; next }
      if (!inPlatform) next
      if (field == "mirror" && line ~ ("^" prefix "      \"mirrors\"[[:space:]]*:")) { inMirrors = 1; next }
      if (field == "mirror" && inMirrors && line ~ ("^" prefix "        \"https://")) {
        value = line
        sub(/^[[:space:]]*"/, "", value); sub(/"[[:space:]]*,?[[:space:]]*$/, "", value)
        gsub(/\\\//, "/", value); print value; exit
      }
      if (field != "mirror" && line ~ ("^" prefix "      \"" field "\"[[:space:]]*:")) {
        value = line
        sub(/^[^:]+:[[:space:]]*"/, "", value); sub(/"[[:space:]]*,?[[:space:]]*$/, "", value)
        gsub(/\\\//, "/", value); print value; exit
      }
    }
  ' "${manifest}"
}

json_updater_version() { # json_updater_version <清单> <stable|beta>
  local manifest="$1" channel="$2"
  awk -v channel="${channel}" '
    BEGIN { selected = (channel == "stable"); inBeta = 0; prefix = (channel == "beta") ? "    " : "  " }
    {
      line = $0
      if (channel == "beta") {
        if (line ~ /^  "beta"[[:space:]]*:[[:space:]]*\{/) { selected = 1; next }
        if (selected && line ~ /^  \},?[[:space:]]*$/) exit
      } else {
        if (line ~ /^  "beta"[[:space:]]*:[[:space:]]*\{/) { inBeta = 1; next }
        if (inBeta) { if (line ~ /^  \},?[[:space:]]*$/) inBeta = 0; next }
      }
      if (!selected) next
      if (line ~ ("^" prefix "\"updater\"[[:space:]]*:[[:space:]]*\\{")) { inUpdater = 1; next }
      if (inUpdater && line ~ ("^" prefix "  \"version\"[[:space:]]*:")) {
        value = line
        sub(/^[^:]+:[[:space:]]*"/, "", value); sub(/"[[:space:]]*,?[[:space:]]*$/, "", value)
        print value; exit
      }
    }
  ' "${manifest}"
}

manifest_payload_contains_updater() { # manifest_payload_contains_updater <清单> <版本> <URL> <SHA256>
  local manifest="$1" version="$2" url="$3" sha256="$4" payloadB64 payload
  command -v base64 >/dev/null 2>&1 || return 0
  payloadB64="$(json_signature_string "${manifest}" payload)"
  [[ -n "${payloadB64}" ]] || return 1
  payload="$(printf '%s' "${payloadB64}" | base64 -d 2>/dev/null)" || return 1
  grep -Fq "\"version\":\"${version}\"" <<< "${payload}" \
    && grep -Fq "\"url\":\"${url}\"" <<< "${payload}" \
    && grep -Fq "\"sha256\":\"${sha256}\"" <<< "${payload}"
}

verify_manifest_signature() { # verify_manifest_signature <清单> <版本>
  local manifest="$1" version="$2"
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
  # 源码构建只消费版本号；版本必须存在于已签名载荷中，不能只替换清单外层字段。
  if ! grep -Fq "\"productVersion\":\"${version}\"" "${payloadFile}"; then
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

release_source_valid() { # release_source_valid <版本> <URL> <SHA256> <sourceBuild>
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || return 1
  [[ "$4" == "true" ]] && return 0
  # 兼容切换期旧清单：旧版本只声明发布包，也允许改走固定版本标签的源码构建。
  release_fields_valid "$1" "$2" "$3"
}

resolve_official_release() {
  fetch_release_manifest
  local requested="${AID_RELEASE_CHANNEL:-$(state_get RELEASE_CHANNEL auto)}"
  local stableVersion stableUrl stableSha stableSource betaVersion betaUrl betaSha betaSource indent
  case "${requested}" in
    auto|stable|beta) ;;
    *) die "发布渠道只支持 auto、stable 或 beta（当前: ${requested}）" ;;
  esac
  REQUESTED_RELEASE_CHANNEL="${requested}"

  stableVersion="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" '  ' productVersion)"
  stableUrl="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" '  ' packageUrl)"
  stableSha="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" '  ' packageSha256)"
  stableSource="$(grep -m 1 '^  "sourceBuild"[[:space:]]*:' "${RESOLVED_MANIFEST_PATH}" 2>/dev/null | sed -E 's/.*:[[:space:]]*(true|false).*/\1/' || true)"
  betaVersion="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" '    ' productVersion)"
  betaUrl="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" '    ' packageUrl)"
  betaSha="$(json_direct_string "${RESOLVED_MANIFEST_PATH}" '    ' packageSha256)"
  betaSource="$(grep -m 1 '^    "sourceBuild"[[:space:]]*:' "${RESOLVED_MANIFEST_PATH}" 2>/dev/null | sed -E 's/.*:[[:space:]]*(true|false).*/\1/' || true)"

  if [[ "${requested}" == "auto" ]]; then
    if release_source_valid "${stableVersion}" "${stableUrl}" "${stableSha}" "${stableSource}"; then
      requested="stable"
    elif release_source_valid "${betaVersion}" "${betaUrl}" "${betaSha}" "${betaSource}"; then
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
  local selectedSource
  selectedSource="${stableSource}"
  [[ "${requested}" == "beta" ]] && selectedSource="${betaSource}"
  release_source_valid "${RESOLVED_VERSION}" "${RESOLVED_PACKAGE_URL}" "${RESOLVED_PACKAGE_SHA256}" "${selectedSource}" \
    || die "官方清单中的 ${RESOLVED_CHANNEL} 版本信息不完整"
  verify_manifest_signature "${RESOLVED_MANIFEST_PATH}" "${RESOLVED_VERSION}"
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
  grep -Eq '(^|/)web-dist/index\.html$' "${listFile}" || { rm -f "${listFile}"; die "发布包缺少 Web 静态入口"; }
  grep -Eq '(^|/)web-dist/200\.html$' "${listFile}" || { rm -f "${listFile}"; die "发布包缺少 Web SPA 通用入口"; }
  grep -Eq '(^|/)build-info\.json$' "${listFile}" || { rm -f "${listFile}"; die "发布包缺少 build-info.json"; }
  if grep -Eq '(^|/)installer/deploy/docker/\.env$' "${listFile}"; then
    rm -f "${listFile}"
    die "发布包不得携带用户运行配置 .env，已拒绝使用"
  fi
  case "$(uname -m)" in
    x86_64) archEntry='updater/aid-updater_linux_amd64' ;;
    aarch64) archEntry='updater/aid-updater_linux_arm64' ;;
  esac
  if [[ -n "${archEntry}" ]]; then
    grep -Eq "(^|/)${archEntry}$" "${listFile}" || { rm -f "${listFile}"; die "发布包缺少本机架构的升级器"; }
  fi
  if [[ "${requireInstaller}" == "yes" ]]; then
    for entry in installer/deploy/aid.sh installer/deploy/aid-deploy.conf.example \
        installer/deploy/docker/.env.example \
        installer/deploy/docker/docker-compose.yml installer/deploy/docker/nginx/aid-https.conf.template \
        installer/deploy/docker/nginx/web-static.conf \
        installer/deploy/docker/rocketmq/broker-entrypoint.sh \
        installer/sql/aid-init.sql; do
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

package_is_source_build() { # package_is_source_build <包>
  local entry
  entry="$(tar -tzf "$1" 2>/dev/null | grep -E '(^|/)build-info\.json$' | head -n 1 || true)"
  [[ -n "${entry}" ]] || return 1
  tar -xOzf "$1" "${entry}" 2>/dev/null | grep -q '"builtBy"[[:space:]]*:[[:space:]]*"remote-source-build"'
}

# 只读判断已有源码构建缓存是否符合当前静态 Web 发布契约。此函数不调用 die、
# 不执行包内脚本，也不修改缓存；严格的路径/类型/权限校验仍由 validate_release_package 完成。
source_package_cache_matches_current_contract() { # source_package_cache_matches_current_contract <包>
  local package="$1" listFile entry archEntry=""
  listFile="$(mktemp)" || return 1
  if ! tar -tzf "${package}" > "${listFile}" 2>/dev/null; then
    rm -f -- "${listFile}"
    return 1
  fi
  for entry in \
      '(^|/)backend/aid-admin\.jar$' \
      '(^|/)admin-dist/index\.html$' \
      '(^|/)web-dist/index\.html$' \
      '(^|/)web-dist/200\.html$' \
      '(^|/)build-info\.json$' \
      '(^|/)installer/deploy/aid\.sh$' \
      '(^|/)installer/deploy/build-release-from-source\.sh$' \
      '(^|/)installer/deploy/docker/docker-compose\.yml$' \
      '(^|/)installer/deploy/docker/nginx/web-static\.conf$'; do
    if ! grep -Eq "${entry}" "${listFile}"; then
      rm -f -- "${listFile}"
      return 1
    fi
  done
  case "$(uname -m)" in
    x86_64) archEntry='(^|/)updater/aid-updater_linux_amd64$' ;;
    aarch64) archEntry='(^|/)updater/aid-updater_linux_arm64$' ;;
  esac
  if [[ -n "${archEntry}" ]] && ! grep -Eq "${archEntry}" "${listFile}"; then
    rm -f -- "${listFile}"
    return 1
  fi
  rm -f -- "${listFile}"
  return 0
}

source_builder_supports_explicit_mode() { # source_builder_supports_explicit_mode <脚本路径>
  local candidate="$1" size
  [[ -f "${candidate}" && ! -L "${candidate}" ]] || return 1
  size="$(wc -c < "${candidate}" 2>/dev/null || true)"
  [[ "${size}" =~ ^[0-9]+$ ]] && (( size > 0 && size <= 1048576 )) || return 1
  grep -Fqx '# AID_SOURCE_BUILD_MODE_CAPABILITY=explicit-v1' "${candidate}" \
    && grep -Fq 'SOURCE_BUILD_MODE="${AID_SOURCE_BUILD_MODE:-auto}"' "${candidate}" \
    && grep -Fq 'case "$SOURCE_BUILD_MODE" in' "${candidate}"
}

sync_source_builder_to_installer() { # sync_source_builder_to_installer <已验证构建器>
  local candidate="$1" target staged
  [[ -d "${INSTALLER_ROOT}/deploy" ]] || return 0
  target="${INSTALLER_ROOT}/deploy/${SOURCE_BUILDER_NAME}"
  # 仅更新受管安装器目录；候选文件已在 DATA_ROOT/packages，绝不改写当前源码仓库中的 deploy 文件。
  [[ ! -L "${target}" ]] || die "受管源码构建器不能是符号链接: ${target}"
  if [[ -f "${target}" ]] && cmp -s "${candidate}" "${target}"; then
    return 0
  fi
  staged="${target}.tmp.$$"
  install -m 0700 "${candidate}" "${staged}" \
    && mv -f -- "${staged}" "${target}" \
    || { rm -f -- "${staged}"; die "同步受管源码构建器失败: ${target}"; }
  ok "已同步支持显式构建模式的构建器: ${target}"
}

bootstrap_source_builder() { # bootstrap_source_builder <docker|host>
  local sourceBuildMode="$1" builder="${SCRIPT_DIR}/${SOURCE_BUILDER_NAME}" tmpDir base repoUrl cloned sourceRef remoteRef
  case "${sourceBuildMode}" in
    docker)
      command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 \
        || die "Docker 容器源码构建需要可用的 Docker Engine"
      ensure_docker_image "${SOURCE_GIT_IMAGE}" "Git源码拉取"
      log "Docker 源码构建器将使用隔离的 ${SOURCE_GIT_IMAGE} 容器拉取源码"
      ;;
    host)
      command -v git >/dev/null 2>&1 \
        || die "非 Docker 宿主机源码构建需要 Git；请先完成手动部署依赖安装后重试"
      ;;
    *) die "未知源码构建模式: ${sourceBuildMode}" ;;
  esac
  if [[ -f "${builder}" ]] && source_builder_supports_explicit_mode "${builder}"; then
    sync_source_builder_to_installer "${builder}"
    SOURCE_BUILDER_PATH="${builder}"
    return 0
  fi
  [[ ! -f "${builder}" ]] || warn "本地源码构建器不支持 Docker/非 Docker 显式隔离，将从公开 master 刷新构建器"
  tmpDir="$(mktemp -d)"
  # 安装器本身从公开 master 获取最新修复，源码构建器也应遵循同一策略；
  # 三端业务源码仍由构建器严格拉取版本标签，不会混入 master 业务代码。
  for sourceRef in master "v${RESOLVED_VERSION}"; do
    [[ "${sourceRef}" != "master" ]] \
      && warn "公开 master 构建工具不可用，回退版本内置构建器；业务源码仍固定使用 v${RESOLVED_VERSION} 标签"
    remoteRef="refs/tags/${sourceRef}"
    [[ "${sourceRef}" == "master" ]] && remoteRef="refs/heads/master"
    for base in https://gitee.com/gzxx-2025 https://github.com/gzxx-2025; do
      rm -rf "${tmpDir}/server"
      log "获取源码构建器 ${sourceRef}: ${base}"
      repoUrl="${base}/aid-server.git"
      cloned="no"
      if [[ "${sourceBuildMode}" == "docker" ]] && command -v timeout >/dev/null 2>&1; then
        if timeout 240 docker run --rm --user "$(id -u):$(id -g)" -v "${tmpDir}:/work" -w /work \
            "${SOURCE_GIT_IMAGE}" clone --depth 1 --single-branch --branch "${sourceRef}" \
            "${repoUrl}" server >/dev/null 2>&1; then
          cloned="yes"
        fi
      elif [[ "${sourceBuildMode}" == "docker" ]] && docker run --rm --user "$(id -u):$(id -g)" -v "${tmpDir}:/work" -w /work \
          "${SOURCE_GIT_IMAGE}" clone --depth 1 --single-branch --branch "${sourceRef}" \
          "${repoUrl}" server >/dev/null 2>&1; then
        cloned="yes"
      elif command -v git >/dev/null 2>&1; then
        if command -v timeout >/dev/null 2>&1; then
          if GIT_TERMINAL_PROMPT=0 GIT_HTTP_LOW_SPEED_LIMIT=1 GIT_HTTP_LOW_SPEED_TIME=12 \
              timeout 25 git ls-remote --exit-code "${repoUrl}" "${remoteRef}" >/dev/null 2>&1 \
              && GIT_TERMINAL_PROMPT=0 timeout 180 git clone --quiet --depth 1 --single-branch \
                --branch "${sourceRef}" "${repoUrl}" "${tmpDir}/server" 2>/dev/null; then
            cloned="yes"
          fi
        elif GIT_TERMINAL_PROMPT=0 GIT_HTTP_LOW_SPEED_LIMIT=1 GIT_HTTP_LOW_SPEED_TIME=12 \
            git ls-remote --exit-code "${repoUrl}" "${remoteRef}" >/dev/null 2>&1 \
            && GIT_TERMINAL_PROMPT=0 git clone --quiet --depth 1 --single-branch \
              --branch "${sourceRef}" "${repoUrl}" "${tmpDir}/server" 2>/dev/null; then
          cloned="yes"
        fi
      fi
      if [[ "${cloned}" == "yes" && -f "${tmpDir}/server/deploy/${SOURCE_BUILDER_NAME}" ]]; then
        mkdir -p "${DATA_ROOT}/packages"
        builder="${DATA_ROOT}/packages/${SOURCE_BUILDER_NAME}"
        install -m 0755 "${tmpDir}/server/deploy/${SOURCE_BUILDER_NAME}" "${builder}"
        if ! source_builder_supports_explicit_mode "${builder}"; then
          warn "公开 ${sourceRef} 中的源码构建器不支持 Docker/非 Docker 显式隔离，拒绝使用"
          rm -f "${builder}"
          continue
        fi
        sync_source_builder_to_installer "${builder}"
        rm -rf "${tmpDir}"
        SOURCE_BUILDER_PATH="${builder}"
        return 0
      fi
    done
  done
  rm -rf "${tmpDir}"
  die "未获得支持显式构建模式的源码构建器；请重新执行最新远程 aid.sh 后运行 sudo aid setup-updater 修复"
}

source_build_failure_diagnostics() { # source_build_failure_diagnostics <构建日志>
  local buildLog="$1"
  err "三端源码构建未完成，下面是构建日志最后160行："
  if [[ -s "${buildLog}" ]]; then
    # Maven 下载进度使用回车刷新；先展开回车再截取，避免一条超长进度行淹没真正错误。
    tail -n 240 "${buildLog}" 2>/dev/null | tr '\r' '\n' | tail -n 160 >&2 || true
  else
    echo "  构建日志不存在或为空: ${buildLog}" >&2
  fi
  echo "  完整构建日志: ${buildLog}" >&2
}

ensure_source_package() {
  mkdir -p "${DATA_ROOT}/packages"
  RESOLVED_PACKAGE_PATH="${DATA_ROOT}/packages/aid-v${RESOLVED_VERSION}.tar.gz"
  local sourceBuildMode builder actual checksumFile manifestFingerprintFile currentManifestFingerprint
  local ownerMode owner modeBits fingerprintOwnerMode fingerprintOwner fingerprintModeBits buildLog buildStamp
  local -a buildStatuses
  require_source_build_mode
  sourceBuildMode="${AID_SOURCE_BUILD_MODE}"
  checksumFile="${RESOLVED_PACKAGE_PATH}.sha256"
  manifestFingerprintFile="${RESOLVED_PACKAGE_PATH}.manifest.sha256"
  [[ -f "${RESOLVED_MANIFEST_PATH:-}" && ! -L "${RESOLVED_MANIFEST_PATH}" ]] \
    || die "官方版本清单不可用，拒绝复用或生成源码构建包"
  currentManifestFingerprint="$(sha256_file "${RESOLVED_MANIFEST_PATH}" || true)"
  [[ "${currentManifestFingerprint}" =~ ^[0-9a-f]{64}$ ]] \
    || die "无法计算官方版本清单 SHA256"

  if [[ -e "${RESOLVED_PACKAGE_PATH}" || -e "${checksumFile}" || -e "${manifestFingerprintFile}" ]]; then
    if [[ -f "${RESOLVED_PACKAGE_PATH}" && -f "${checksumFile}" && -f "${manifestFingerprintFile}" \
        && "${AID_FORCE_SOURCE_REBUILD:-0}" != "1" ]]; then
      actual="$(sha256_file "${RESOLVED_PACKAGE_PATH}" || true)"
      ownerMode="$(stat -c '%u:%a' "${RESOLVED_PACKAGE_PATH}" 2>/dev/null || true)"
      owner="${ownerMode%%:*}"
      modeBits="${ownerMode#*:}"
      fingerprintOwnerMode="$(stat -c '%u:%a' "${manifestFingerprintFile}" 2>/dev/null || true)"
      fingerprintOwner="${fingerprintOwnerMode%%:*}"
      fingerprintModeBits="${fingerprintOwnerMode#*:}"
      if [[ "${actual}" == "$(awk '{print tolower($1)}' "${checksumFile}" 2>/dev/null)" \
          && "${currentManifestFingerprint}" == "$(awk 'NR == 1 {print tolower($1)}' "${manifestFingerprintFile}" 2>/dev/null)" \
          && "${owner}" == "0" && "${modeBits}" =~ ^[0-7]+$ \
          && "${fingerprintOwner}" == "0" && "${fingerprintModeBits}" == "600" ]] \
          && (( (8#${modeBits} & 8#022) == 0 )) \
          && package_is_source_build "${RESOLVED_PACKAGE_PATH}"; then
        if source_package_cache_matches_current_contract "${RESOLVED_PACKAGE_PATH}"; then
          validate_release_package "${RESOLVED_PACKAGE_PATH}" no
          ok "复用与当前签名清单一致且校验通过的源码构建包: ${RESOLVED_PACKAGE_PATH}"
          RESOLVED_PACKAGE_SHA256="${actual}"
          return 0
        fi
        warn "旧缓存结构不兼容，自动删除并重新构建: ${RESOLVED_PACKAGE_PATH}"
      elif [[ "${currentManifestFingerprint}" != "$(awk 'NR == 1 {print tolower($1)}' "${manifestFingerprintFile}" 2>/dev/null)" ]]; then
        warn "官方版本清单已更新，自动淘汰同版本源码构建缓存"
      else
        risk "源码构建缓存校验、清单指纹或权限不安全，将重新构建，不会使用旧缓存"
      fi
    elif [[ "${AID_FORCE_SOURCE_REBUILD:-0}" == "1" ]]; then
      warn "已要求强制重新构建源码发布包"
    else
      warn "旧源码构建缓存缺少当前签名清单指纹，自动删除并重新构建"
    fi
    rm -f -- "${RESOLVED_PACKAGE_PATH}" "${checksumFile}" "${manifestFingerprintFile}"
  fi

  bootstrap_source_builder "${sourceBuildMode}"
  prepare_source_build_images "${sourceBuildMode}"
  builder="${SOURCE_BUILDER_PATH}"
  section "远程源码构建 AID v${RESOLVED_VERSION}"
  warn "只拉取三个公开仓库的 v${RESOLVED_VERSION} 标签；优先 Gitee，失败时整组回退到 GitHub"
  if [[ "${sourceBuildMode}" == "docker" ]]; then
    log "源码构建模式：Docker 容器源码构建"
    warn "首次构建需要下载 Maven/npm/Go 依赖及 Docker 构建镜像，请预留至少 15GB 磁盘与足够时间"
  else
    log "源码构建模式：非 Docker 宿主机源码构建"
    warn "首次构建需要下载 Maven/npm/Go 依赖；不会拉取或调用 Docker，请预留至少 15GB 磁盘与足够时间"
  fi
  mkdir -p "${DATA_ROOT}/logs" || die "无法创建构建日志目录: ${DATA_ROOT}/logs"
  buildStamp="$(date '+%Y%m%d-%H%M%S')"
  buildLog="${DATA_ROOT}/logs/source-build-v${RESOLVED_VERSION}-${buildStamp}.log"
  : > "${buildLog}" || die "无法创建三端构建日志: ${buildLog}"
  chmod 600 "${buildLog}" 2>/dev/null || true
  log "三端编译实时日志将同时保存到: ${buildLog}"
  if [[ "${sourceBuildMode}" == "docker" ]]; then
    AID_DATA_ROOT="${DATA_ROOT}" AID_SOURCE_BUILD_MODE="${sourceBuildMode}" AID_MANIFEST_PUBLIC_KEY="${TRUSTED_MANIFEST_PUBLIC_KEY}" \
      AID_DEPENDENCY_REGION="$(dependency_region_setting)" \
      AID_DOWNLOAD_TIMEOUT_SECONDS="$(download_timeout_setting)" \
      AID_DOCKER_MIRRORS="$(docker_mirror_setting)" \
      AID_MANAGER_SCRIPT="${SCRIPT_DIR}/$(basename "${BASH_SOURCE[0]}")" \
      sh "${builder}" --version "${RESOLVED_VERSION}" --output "${RESOLVED_PACKAGE_PATH}" \
        --work-dir "${DATA_ROOT}/source-build/v${RESOLVED_VERSION}"
  else
    # 手动构建不继承也不传递 Docker 镜像参数，避免环境残留导致两条链路混用。
    (
      unset AID_DOCKER_MIRRORS AID_DOCKER_CN_MIRROR
      AID_DATA_ROOT="${DATA_ROOT}" AID_SOURCE_BUILD_MODE="${sourceBuildMode}" AID_MANIFEST_PUBLIC_KEY="${TRUSTED_MANIFEST_PUBLIC_KEY}" \
        AID_DEPENDENCY_REGION="$(dependency_region_setting)" \
        AID_DOWNLOAD_TIMEOUT_SECONDS="$(download_timeout_setting)" \
        AID_MANAGER_SCRIPT="${SCRIPT_DIR}/$(basename "${BASH_SOURCE[0]}")" \
        sh "${builder}" --version "${RESOLVED_VERSION}" --output "${RESOLVED_PACKAGE_PATH}" \
          --work-dir "${DATA_ROOT}/source-build/v${RESOLVED_VERSION}"
    )
  fi 2>&1 | tee -a "${buildLog}"
  buildStatuses=( "${PIPESTATUS[@]}" )
  if (( buildStatuses[0] != 0 )); then
    source_build_failure_diagnostics "${buildLog}"
    die "三端源码构建失败；现有部署未被修改，请查看日志 ${buildLog}"
  fi
  if (( buildStatuses[1] != 0 )); then
    die "三端源码构建日志写入失败；现有部署未被修改，请检查磁盘空间: ${buildLog}"
  fi
  validate_release_package "${RESOLVED_PACKAGE_PATH}" no
  actual="$(sha256_file "${RESOLVED_PACKAGE_PATH}" || true)"
  [[ -n "${actual}" ]] || die "无法计算源码构建包 SHA256"
  printf '%s  %s\n' "${actual}" "$(basename "${RESOLVED_PACKAGE_PATH}")" > "${checksumFile}"
  printf '%s\n' "${currentManifestFingerprint}" > "${manifestFingerprintFile}"
  chmod 600 "${RESOLVED_PACKAGE_PATH}" "${checksumFile}" "${manifestFingerprintFile}" 2>/dev/null \
    || die "无法收紧源码构建缓存权限"
  RESOLVED_PACKAGE_SHA256="${actual}"
  ok "三端源码构建、包结构与本地 SHA256 校验通过；完整日志: ${buildLog}"
}

deployment_runtime_ready() {
  [[ -f "${SCRIPT_DIR}/aid-deploy.conf.example" \
     && -f "${COMPOSE_DIR}/.env.example" \
     && -f "${COMPOSE_DIR}/docker-compose.yml" \
     && -f "${COMPOSE_DIR}/nginx/web-static.conf" \
     && -f "${REPO_DIR}/sql/aid-init.sql" ]]
}

handoff_to_managed_installer() { # handoff_to_managed_installer <原始参数...>
  local managedDir currentManager stagedManager
  # 彻底卸载若正由 DATA_ROOT 内的受管脚本执行，会先安全换出到临时脚本；
  # 此时绝不能再切回即将删除的安装器目录。
  [[ "${AID_UNINSTALL_SAFE_REEXEC:-0}" == "1" ]] && return 0
  managedDir="$(dirname "${MANAGED_SCRIPT}")"
  if [[ "${AID_REMOTE_BOOTSTRAP:-0}" == "1" ]]; then
    currentManager="${SCRIPT_DIR}/$(basename "${BASH_SOURCE[0]}")"
    # 显式远程引导不仅本次使用最新逻辑，也把它原子同步到已部署的受管目录。
    # 因此即使业务版本号未变化，下一次 sudo aid default/update 仍是新命令集。
    if [[ "${SCRIPT_DIR}" != "${managedDir}" \
        && -f "${currentManager}" && ! -L "${currentManager}" \
        && -f "${INSTALLER_ROOT}/deploy/docker/docker-compose.yml" \
        && -f "${INSTALLER_ROOT}/sql/aid-init.sql" ]]; then
      if cmp -s "${currentManager}" "${MANAGED_SCRIPT}" 2>/dev/null; then
        ok "已启用远程最新引导脚本，受管 aid.sh 已是最新"
      else
        stagedManager="${managedDir}/.aid.sh.remote.$$"
        if install -m 0700 "${currentManager}" "${stagedManager}" 2>/dev/null \
            && mv -f -- "${stagedManager}" "${MANAGED_SCRIPT}" 2>/dev/null; then
          ok "已启用远程最新引导脚本，并同步到受管命令 sudo aid"
        else
          rm -f -- "${stagedManager}" 2>/dev/null || true
          warn "当前仍使用远程最新脚本，但无法同步 ${MANAGED_SCRIPT}；下次请继续使用远程命令"
        fi
      fi
    else
      ok "已启用远程最新引导脚本，不切换到旧版受管 aid.sh"
    fi
    # 受管脚本一旦存在就立即恢复统一命令，即使上次部署在服务启动前中断，
    # 用户也可以直接用 sudo aid setup-updater/status/restart 继续排障。
    [[ -f "${MANAGED_SCRIPT}" ]] && install_management_command
    return 0
  fi
  if [[ -f "${MANAGED_SCRIPT}" && "${SCRIPT_DIR}" != "${managedDir}" \
      && -f "${INSTALLER_ROOT}/deploy/docker/docker-compose.yml" \
      && -f "${INSTALLER_ROOT}/sql/aid-init.sql" ]]; then
    log "切换到已安装的统一管理脚本: ${MANAGED_SCRIPT}"
    exec bash "${MANAGED_SCRIPT}" "$@"
  fi
}

extract_installer_from_package() { # extract_installer_from_package <发布包>
  local package="$1" listFile scriptEntry installerEntry normalizedEntry
  local extractRoot sourceRoot required
  mkdir -p "${DATA_ROOT}" || { err "无法创建数据目录: ${DATA_ROOT}"; return 1; }
  if [[ -L "${INSTALLER_ROOT}" ]]; then
    err "安装器目录不能是符号链接: ${INSTALLER_ROOT}"
    return 1
  fi

  listFile="$(mktemp)"
  if ! tar -tzf "${package}" > "${listFile}" 2>/dev/null; then
    rm -f -- "${listFile}"
    err "无法读取发布包目录: ${package}"
    return 1
  fi
  # 同时兼容 installer/...、./installer/... 和带单层发布包根目录的
  # aid-vX/installer/...；只提取已通过发布包安全检查的 installer 子树。
  scriptEntry="$(grep -E '(^|/)installer/deploy/aid\.sh$' "${listFile}" | head -n 1 || true)"
  if [[ -z "${scriptEntry}" ]]; then
    rm -f -- "${listFile}"
    err "发布包缺少一键安装脚本"
    return 1
  fi
  installerEntry="${scriptEntry%/deploy/aid.sh}"

  extractRoot="$(mktemp -d "${DATA_ROOT}/.installer-extract.XXXXXX")" \
    || { rm -f -- "${listFile}"; err "无法创建安装器临时目录"; return 1; }
  # 使用归档清单中的真实目录名直接提取。GNU tar 会递归提取该目录，同时保留
  # ./installer 与 installer 的区别；-T 会规范化 ./ 前缀，因此这里不能使用清单文件。
  if ! tar -xzf "${package}" -C "${extractRoot}" "${installerEntry}"; then
    rm -rf -- "${extractRoot}"
    rm -f -- "${listFile}"
    err "提取发布包安装器目录失败: ${installerEntry}"
    return 1
  fi
  normalizedEntry="${installerEntry#./}"
  sourceRoot="${extractRoot}/${normalizedEntry}"
  for required in deploy/aid.sh deploy/aid-deploy.conf.example \
      deploy/docker/.env.example deploy/docker/docker-compose.yml \
      deploy/docker/nginx/aid-https.conf.template \
      deploy/docker/nginx/web-static.conf \
      deploy/docker/rocketmq/broker-entrypoint.sh sql/aid-init.sql; do
    if [[ ! -f "${sourceRoot}/${required}" ]]; then
      rm -rf -- "${extractRoot}"
      rm -f -- "${listFile}"
      err "提取后的安装器缺少组件: ${required}"
      return 1
    fi
  done

  mkdir -p "${INSTALLER_ROOT}" \
    || { rm -rf -- "${extractRoot}"; rm -f -- "${listFile}"; err "无法创建安装器目录"; return 1; }
  # 仅覆盖版本控制的安装器文件。发布包禁止携带 .env，因此用户现有配置不会被覆盖或删除。
  if ! cp -a "${sourceRoot}/." "${INSTALLER_ROOT}/"; then
    rm -rf -- "${extractRoot}"
    rm -f -- "${listFile}"
    err "写入安装器目录失败: ${INSTALLER_ROOT}"
    return 1
  fi
  rm -rf -- "${extractRoot}"
  rm -f -- "${listFile}"
}

bootstrap_installer_if_needed() { # bootstrap_installer_if_needed <发布包> <install-docker|install-manual>
  local package="$1" action="$2" managedDir
  managedDir="$(dirname "${MANAGED_SCRIPT}")"
  if [[ "${SCRIPT_DIR}" == "${managedDir}" ]] && deployment_runtime_ready; then
    return 0
  fi
  validate_release_package "${package}" yes
  section "初始化 AID 一键安装器"
  extract_installer_from_package "${package}" \
    || die "从发布包提取安装器失败；发布包和现有配置均未被修改"
  # 用户通过官方远程命令明确启用最新引导脚本时，发布包只提供其余受管文件；
  # aid.sh 本身必须保留当前远程版本，不能再次退回缓存源码包内的旧脚本。
  if [[ "${AID_REMOTE_BOOTSTRAP:-0}" == "1" && "${SCRIPT_DIR}" != "${managedDir}" ]]; then
    local currentManager="${SCRIPT_DIR}/$(basename "${BASH_SOURCE[0]}")"
    [[ -f "${currentManager}" && ! -L "${currentManager}" ]] \
      || die "远程最新引导脚本不可读取: ${currentManager}"
    cp -f -- "${currentManager}" "${MANAGED_SCRIPT}" \
      || die "同步远程最新引导脚本失败"
    ok "远程最新引导脚本已同步到受管安装目录"
  fi
  chmod 700 "${MANAGED_SCRIPT}" 2>/dev/null || true
  [[ -f "${MANAGED_SCRIPT}" ]] || die "安装器落盘失败: ${MANAGED_SCRIPT}"
  ok "完整安装器已安全落盘: ${INSTALLER_ROOT}"
  install_management_command
  export AID_RELEASE_CHANNEL="${REQUESTED_RELEASE_CHANNEL:-${RESOLVED_CHANNEL:-auto}}"
  export AID_TRUSTED_SOURCE_PACKAGE=1
  exec bash "${MANAGED_SCRIPT}" "${action}" "${package}"
}

refresh_managed_installer() { # refresh_managed_installer <发布包>
  local package="$1"
  if tar -tzf "${package}" 2>/dev/null | grep -qE '(^|/)installer/deploy/aid\.sh$'; then
    extract_installer_from_package "${package}" \
      || { warn "程序已升级，但安装器文件刷新失败，请保留当前终端并人工检查"; return 1; }
    chmod 700 "${MANAGED_SCRIPT}" 2>/dev/null || true
    ok "部署管理脚本已同步到新版本"
  fi
}

install_management_command() {
  local commandPath="${AID_LOCAL_BIN_DIR}/aid" existingTarget=""
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
    && ok "已安装管理命令: sudo aid（更新: sudo aid update；查看地址: sudo aid default；数据库: sudo aid mysql）" \
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
    REQUESTED_RELEASE_CHANNEL="${AID_RELEASE_CHANNEL:-$(state_get RELEASE_CHANNEL auto)}"
    case "${REQUESTED_RELEASE_CHANNEL}" in
      auto|stable|beta) ;;
      *) die "发布渠道只支持 auto、stable 或 beta（当前: ${REQUESTED_RELEASE_CHANNEL}）" ;;
    esac
    if [[ "${RESOLVED_VERSION}" == *-* ]]; then RESOLVED_CHANNEL="beta"; else RESOLVED_CHANNEL="stable"; fi
    if [[ "${AID_TRUSTED_SOURCE_PACKAGE:-0}" != "1" ]]; then
      risk "你选择了本地发布包，脚本只能校验包结构，无法确认它是否来自官方 Release"
    fi
    validate_release_package "${RESOLVED_PACKAGE_PATH}" no
    return 0
  fi
  resolve_official_release
  ensure_source_package
}

first_install_unmanaged_entry() { # first_install_unmanaged_entry <docker|manual>
  local mode="$1" entry name rootOwned="no"
  case "${mode}" in
    docker|manual) ;;
    *) die "未知部署方式，无法检查数据目录: ${mode}" ;;
  esac
  [[ -d "${DATA_ROOT}" ]] || return 1
  # 启动时没有强 AID 证据的既有内容，不能被本轮稍后生成的配置或标记静默认领。
  # 用户仍可在首次确认页明确选择继续；若本轮随后失败，下一次则可凭已经落盘的
  # 强证据进入幂等恢复，不会反复误报。
  if [[ "${AID_DATA_ROOT_OWNED_ON_ENTRY:-0}" != "1" \
      && -n "${AID_DATA_ROOT_UNMANAGED_ON_ENTRY:-}" ]]; then
    printf '%s\n' "${AID_DATA_ROOT_UNMANAGED_ON_ENTRY}"
    return 0
  fi
  # 配置确认阶段已经为数据根写入强所有权证据。后续源码构建、依赖准备或上一次
  # 失败重试可能留下另一种部署方式也会复用的标准目录；这些都属于 AID，不能
  # 因当前选择 docker/manual 不同而误报。没有所有权证据时仍只放行安装器在
  # 确认前创建的最小缓存集合，防止把任意现有目录冒充成 AID 数据。
  aid_data_root_has_ownership_evidence && rootOwned="yes"
  while IFS= read -r -d '' entry; do
    name="${entry##*/}"
    case "${name}" in
      packages|installer|config|source-build|build-cache|logs)
        [[ -d "${entry}" && ! -L "${entry}" ]] && continue
        ;;
      aid-deploy.conf)
        [[ -f "${entry}" && ! -L "${entry}" ]] && continue
        ;;
      aid-nginx.conf)
        [[ "${rootOwned}" == "yes" && -f "${entry}" && ! -L "${entry}" ]] && continue
        ;;
      app|backups|runtime|run|mysql-data|redis-data|rocketmq|uploadPath|uploadPath-private|mysql-data-manual|mysql-files|redis-data-manual)
        [[ "${rootOwned}" == "yes" && -d "${entry}" && ! -L "${entry}" ]] && continue
        ;;
      .installer-extract.*)
        # 进程被中断时可能留下受限安装器解压临时目录；仅在数据根归属已确认时复用。
        [[ "${rootOwned}" == "yes" && -d "${entry}" && ! -L "${entry}" ]] && continue
        ;;
    esac
    printf '%s\n' "${entry}"
    return 0
  done < <(find "${DATA_ROOT}" -mindepth 1 -maxdepth 1 -print0 2>/dev/null)
  return 1
}

confirm_first_install() { # confirm_first_install <docker|manual>
  local mode="$1" answer defaultAnswer="y" unmanagedEntry=""
  section "首次部署确认"
  echo -e "  部署方式 : ${C_GREEN}${mode}${C_RESET}"
  echo -e "  目标版本 : ${C_GREEN}${RESOLVED_VERSION}${C_RESET} (${RESOLVED_CHANNEL})"
  echo "  数据目录 : ${DATA_ROOT}"
  if [[ "${mode}" == "docker" ]]; then
    echo "  用户端口 : $(env_get HTTP_PORT 80)"
    echo "  管理端口 : $(env_get ADMIN_PORT 8089)"
    echo "  后端端口 : $(env_get BACKEND_PORT 8080)"
    warn "Docker 部署会拉取所需镜像、创建 AID 容器并占用以上端口；不会自动开放防火墙或修改域名解析"
  else
    echo "  用户端口 : $(conf_get HTTP_PORT 80)"
    echo "  管理端口 : $(conf_get ADMIN_PORT 8089)"
    echo "  后端端口 : $(conf_get BACKEND_PORT 8080)"
    warn "非 Docker 部署会按配置检查或准备宿主机依赖、写入 AID systemd/Nginx 配置并占用以上端口；不会自动开放防火墙或修改域名解析"
  fi
  risk "后台初始账号为 admin / admin123，首次登录后必须立即修改密码"
  warn "官方媒体资产包体积较大且存储目标因人而异，本步骤不会静默下载或写入 OSS/COS"
  unmanagedEntry="$(first_install_unmanaged_entry "${mode}" || true)"
  if [[ -n "${unmanagedEntry}" ]]; then
    risk "${DATA_ROOT} 存在非 AID 安装缓存内容（检测到: ${unmanagedEntry}）；继续前请确认这里不是其他业务的数据目录"
    defaultAnswer="n"
  fi
  if [[ "${AID_ASSUME_YES:-0}" == "1" ]]; then
    risk "AID_ASSUME_YES=1：已跳过人工确认，请确保这是你明确授权的全新服务器"
    return 0
  fi
  answer="$(ask_yes_no '确认开始部署？' "${defaultAnswer}")"
  [[ "${answer}" == "y" ]] || die "已取消部署，未启动任何 AID 服务"
}

# ----------------------------------------------------------------------------
# 配置收集（首次部署 / 修改配置共用；默认值 = 已保存值 > 出厂默认）
# ----------------------------------------------------------------------------
write_embedded_config_defaults() { # write_embedded_config_defaults <docker|manual> <目标文件>
  local mode="$1" target="$2"
  if [[ "${mode}" == "docker" ]]; then
    cat > "${target}" <<EOF
DATA_ROOT=${DATA_ROOT}
DEPENDENCY_INSTALL_MODE=auto
DEPENDENCY_REGION=auto
DOCKER_MIRRORS=${DEFAULT_DOCKER_MIRRORS}
DOWNLOAD_TIMEOUT_SECONDS=0
HTTP_PORT=80
ADMIN_PORT=8089
HTTPS_PORT=443
HTTPS_PUBLIC_DOMAIN=www.example.com
HTTPS_ADMIN_DOMAIN=admin.example.com
HTTPS_CERT_PATH=${DATA_ROOT}/config/ssl/fullchain.pem
HTTPS_KEY_PATH=${DATA_ROOT}/config/ssl/privkey.pem
MYSQL_ROOT_PASSWORD=
DB_HOST=mysql
DB_PORT=3306
DB_NAME=aid
DB_USERNAME=aid
DB_PASSWORD=
MYSQL_PORT=3306
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_USERNAME=
REDIS_PASSWORD=
REDIS_DATABASE=0
TOKEN_SECRET=
BACKEND_PORT=8080
#JAVA_OPTS=-Xms2g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/home/aid/logs
#MYSQL_BUFFER_POOL=2G
#MYSQL_MAX_CONNECTIONS=500
#REDIS_MAXMEMORY=1gb
#REDIS_MAXMEMORY_POLICY=noeviction
COMPOSE_PROFILES=mysql,redis
ROCKETMQ_ENABLED=false
ROCKETMQ_NAMESERVER=rocketmq-nameserver:9876
ROCKETMQ_FLUSH_DISK_TYPE=ASYNC_FLUSH
ROCKETMQ_ACCESS_KEY=
ROCKETMQ_SECRET_KEY=
#MQ_NAMESRV_JAVA_OPTS=-Xms256m -Xmx256m -Xmn128m
#MQ_BROKER_JAVA_OPTS=-Xms2g -Xmx2g -Xmn1g
EOF
  else
    cat > "${target}" <<EOF
DATA_ROOT=${DATA_ROOT}
DEPENDENCY_INSTALL_MODE=auto
DEPENDENCY_REGION=auto
DOCKER_MIRRORS=${DEFAULT_DOCKER_MIRRORS}
DOWNLOAD_TIMEOUT_SECONDS=0
HTTP_PORT=80
ADMIN_PORT=8089
BACKEND_PORT=8080
HTTPS_ENABLED=false
HTTPS_PORT=443
HTTPS_PUBLIC_DOMAIN=www.example.com
HTTPS_ADMIN_DOMAIN=admin.example.com
HTTPS_CERT_PATH=${DATA_ROOT}/config/ssl/fullchain.pem
HTTPS_KEY_PATH=${DATA_ROOT}/config/ssl/privkey.pem
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=aid
MYSQL_ROOT_PASSWORD=
DB_USERNAME=aid
DB_PASSWORD=
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_USERNAME=
REDIS_PASSWORD=
REDIS_DATABASE=0
TOKEN_SECRET=
JAVA_OPTS=-Xms1g -Xmx2g
ROCKETMQ_ENABLED=false
ROCKETMQ_NAMESERVER=127.0.0.1:9876
ROCKETMQ_FLUSH_DISK_TYPE=ASYNC_FLUSH
ROCKETMQ_ACCESS_KEY=
ROCKETMQ_SECRET_KEY=
EOF
  fi
}

merge_missing_config_keys() { # merge_missing_config_keys <本地配置> <官方模板> <来源说明>
  local configFile="$1" templateFile="$2" sourceLabel="$3" line key backup merged stamp suffix=0
  local -a missingLines=() missingKeys=()
  local -A existingActiveKeys=() existingKnownKeys=() scheduledKeys=()
  [[ -f "${configFile}" && -f "${templateFile}" ]] || return 0
  [[ ! -L "${configFile}" && ! -L "${templateFile}" ]] \
    || die "配置文件或模板禁止使用软链接"

  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line%$'\r'}"
    if [[ "${line}" =~ ^([A-Z][A-Z0-9_]*)= ]]; then
      existingActiveKeys["${BASH_REMATCH[1]}"]=1
      existingKnownKeys["${BASH_REMATCH[1]}"]=1
    elif [[ "${line}" =~ ^#([A-Z][A-Z0-9_]*)= ]]; then
      existingKnownKeys["${BASH_REMATCH[1]}"]=1
    fi
  done < "${configFile}"

  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line%$'\r'}"
    if [[ "${line}" =~ ^([A-Z][A-Z0-9_]*)= ]]; then
      key="${BASH_REMATCH[1]}"
      [[ -z "${existingActiveKeys[${key}]:-}" ]] || continue
    elif [[ "${line}" =~ ^#([A-Z][A-Z0-9_]*)= ]]; then
      key="${BASH_REMATCH[1]}"
      [[ -z "${existingKnownKeys[${key}]:-}" ]] || continue
    else
      continue
    fi
    [[ -z "${scheduledKeys[${key}]:-}" ]] || continue
    missingLines+=("${line}")
    missingKeys+=("${key}")
    scheduledKeys["${key}"]=1
  done < "${templateFile}"

  (( ${#missingLines[@]} > 0 )) || return 0
  stamp="$(date '+%Y%m%d-%H%M%S')"
  backup="${configFile}.bak.${stamp}"
  while [[ -e "${backup}" ]]; do
    suffix=$((suffix + 1)); backup="${configFile}.bak.${stamp}.${suffix}"
  done
  cp -p -- "${configFile}" "${backup}" || die "备份原配置失败: ${backup}"
  chmod 600 "${backup}" 2>/dev/null || true
  merged="$(mktemp "${configFile}.merge.XXXXXX")" \
    || die "无法在配置目录创建临时文件"
  cp -p -- "${configFile}" "${merged}" \
    || { rm -f -- "${merged}"; die "准备配置合并失败，原配置未修改"; }
  {
    printf '\n# ---------------- AID 自动补齐配置（%s） ----------------\n' "${stamp}"
    printf '# 来源：%s；原有配置和值均未修改，原文件备份见：%s\n' "${sourceLabel}" "$(basename "${backup}")"
    printf '%s\n' "${missingLines[@]}"
  } >> "${merged}" \
    || { rm -f -- "${merged}"; die "补齐配置失败，原配置未修改；备份: ${backup}"; }
  chmod 600 "${merged}" 2>/dev/null || true
  mv -f -- "${merged}" "${configFile}" \
    || { rm -f -- "${merged}"; die "替换配置失败，原配置未修改；备份: ${backup}"; }
  chmod 600 "${configFile}" 2>/dev/null || true
  ok "配置已按官方模板补齐 ${#missingLines[@]} 项，原有值未修改"
  echo "  新增参数 : ${missingKeys[*]}"
  echo "  原配置备份: ${backup}"
}

merge_runtime_configuration() { # merge_runtime_configuration <docker|manual>
  local mode="$1" configFile templateFile="" combined embedded
  if [[ "${mode}" == "docker" ]]; then
    configFile="${ENV_FILE}"
    [[ -f "${COMPOSE_DIR}/.env.example" ]] && templateFile="${COMPOSE_DIR}/.env.example"
  else
    configFile="${CONF}"
    if [[ -f "${SCRIPT_DIR}/aid-deploy.conf.example" ]]; then
      templateFile="${SCRIPT_DIR}/aid-deploy.conf.example"
    elif [[ -f "${INSTALLER_ROOT}/deploy/aid-deploy.conf.example" ]]; then
      templateFile="${INSTALLER_ROOT}/deploy/aid-deploy.conf.example"
    fi
  fi
  [[ -f "${configFile}" ]] || return 0
  combined="$(mktemp)"; embedded="$(mktemp)"
  if [[ -n "${templateFile}" ]]; then cat "${templateFile}" > "${combined}"; fi
  write_embedded_config_defaults "${mode}" "${embedded}"
  cat "${embedded}" >> "${combined}"
  merge_missing_config_keys "${configFile}" "${combined}" "当前受管模板与最新 aid.sh 内置模板"
  rm -f -- "${combined}" "${embedded}"
}

merge_release_configuration() { # merge_release_configuration <发布包> <docker|manual>
  local package="$1" mode="$2" pattern member template
  if [[ "${mode}" == "docker" ]]; then
    pattern='(^|/)installer/deploy/docker/\.env\.example$'
  else
    pattern='(^|/)installer/deploy/aid-deploy\.conf\.example$'
  fi
  member="$(tar -tzf "${package}" 2>/dev/null | grep -E "${pattern}" | head -n 1 || true)"
  [[ -n "${member}" ]] || { warn "目标版本包未提供配置模板，跳过参数补齐"; return 0; }
  template="$(mktemp)"
  tar -xOf "${package}" "${member}" > "${template}" \
    || { rm -f -- "${template}"; die "读取目标版本配置模板失败"; }
  if [[ "${mode}" == "docker" ]]; then
    merge_missing_config_keys "${ENV_FILE}" "${template}" "目标版本发布包 ${RESOLVED_VERSION:-未知}"
  else
    merge_missing_config_keys "${CONF}" "${template}" "目标版本发布包 ${RESOLVED_VERSION:-未知}"
  fi
  rm -f -- "${template}"
}

# ----------------------------------------------------------------------------
# 手动部署配置校验：aid-deploy.conf 首次由模板自动创建（唯一配置真源），
# 仅提示输入必要的外部数据库密码；TOKEN_SECRET 留空时自动生成强随机值写回
# ----------------------------------------------------------------------------
ensure_conf_file() {
  if [[ ! -f "${CONF}" ]]; then
    mkdir -p "$(dirname "${CONF}")"
    if [[ -f "${SCRIPT_DIR}/aid-deploy.conf.example" ]]; then
      cp "${SCRIPT_DIR}/aid-deploy.conf.example" "${CONF}"
    else
      cat > "${CONF}" <<EOF
# ============================================================================
# AID 手动部署配置（唯一配置真源）
# 修改后执行 sudo aid restart 生效。
# 没有域名时保持 HTTPS_ENABLED=false，HTTP 仍可通过服务器 IP 正常访问。
# 密码与密钥禁止发送给他人；TOKEN_SECRET 留空时由脚本生成强随机值。
# ============================================================================

# ---------------- 数据目录 ----------------
DATA_ROOT=${DATA_ROOT}

# ---------------- 依赖处理 ----------------
# auto=固定工具链下载到隔离缓存、系统服务按需安装；manual=只提示，不修改系统。
DEPENDENCY_INSTALL_MODE=auto
# auto=按网络自动选择；cn=国内镜像优先；global=官方地址优先。
DEPENDENCY_REGION=auto
# Docker Hub 国内代理前缀，逗号分隔；自动测速排序、失败逐级回退并校验官方摘要。
DOCKER_MIRRORS=${DEFAULT_DOCKER_MIRRORS}
# 下载总时长上限（秒）；0=不限总时长，正整数（例如1500）=到时停止。
DOWNLOAD_TIMEOUT_SECONDS=0

# ---------------- HTTP 访问（无需域名） ----------------
# 用户端：http://服务器IP；非 80 端口需在地址后追加端口。
HTTP_PORT=80
# 管理端访问码在首次完成数据库初始化后随机生成，部署完成时打印完整地址。
ADMIN_PORT=8089
# Java 后端仅供本机 Nginx 反向代理。
BACKEND_PORT=8080

# ---------------- HTTPS（可选，需要域名和证书） ----------------
# 无域名或尚未完成 DNS 解析时必须保持 false；以下 HTTPS 字段会被忽略。
HTTPS_ENABLED=false
HTTPS_PORT=443
HTTPS_PUBLIC_DOMAIN=www.example.com
HTTPS_ADMIN_DOMAIN=admin.example.com
# 启用 HTTPS 前，把证书复制到数据目录的 config/ssl 下。
HTTPS_CERT_PATH=${DATA_ROOT}/config/ssl/fullchain.pem
HTTPS_KEY_PATH=${DATA_ROOT}/config/ssl/privkey.pem

# ---------------- MySQL 5.7 ----------------
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=aid
# 仅由脚本新建本机 MySQL 5.7 时使用；留空会自动生成，外部 MySQL 不使用。
MYSQL_ROOT_PASSWORD=
# 新建本机数据库默认使用 aid 业务账号；外部数据库填写已有账号。
DB_USERNAME=aid
# 新建本机数据库留空会自动生成；外部/已有数据库必须填写真实密码。
DB_PASSWORD=

# ---------------- Redis ----------------
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
# Redis 6+ ACL 填用户名；传统 requirepass 或无认证模式留空。
REDIS_USERNAME=
# 无密码可留空。
REDIS_PASSWORD=
REDIS_DATABASE=0

# ---------------- 后端安全与运行参数 ----------------
# 留空时由脚本自动生成；生成后请妥善备份，禁止随意更换。
TOKEN_SECRET=
JAVA_OPTS=-Xms1g -Xmx2g

# ---------------- RocketMQ（可选） ----------------
# false 表示使用本地任务模式，不要求安装 RocketMQ。
ROCKETMQ_ENABLED=false
ROCKETMQ_NAMESERVER=127.0.0.1:9876
# Broker刷盘：ASYNC_FLUSH性能优先；SYNC_FLUSH持久性优先。
ROCKETMQ_FLUSH_DISK_TYPE=ASYNC_FLUSH
# 外部 RocketMQ 启用 ACL 时两项同时填写；未启用 ACL 时同时留空。
ROCKETMQ_ACCESS_KEY=
ROCKETMQ_SECRET_KEY=
EOF
    fi
    chmod 600 "${CONF}"
    ok "已自动生成手动部署配置: ${CONF}"
    warn "手动部署使用本机或外部 MySQL/Redis/Node/Nginx，默认连接参数不一定适合你的环境"
  else
    merge_runtime_configuration manual
  fi
  chmod 600 "${CONF}" 2>/dev/null || true
  local configuredDataRoot
  configuredDataRoot="$(conf_get DATA_ROOT "${DATA_ROOT}")"
  [[ "${configuredDataRoot}" == "${DATA_ROOT}" ]] \
    || die "DATA_ROOT 必须与脚本数据目录一致；自定义目录请设置 AID_DATA_ROOT 后重试"
  local requiredKey
  for requiredKey in DB_HOST DB_PORT DB_NAME DB_USERNAME REDIS_HOST REDIS_PORT; do
    [[ -n "$(conf_get "${requiredKey}" '')" ]] || die "${requiredKey} 不能为空"
  done
  # 全新本机 MySQL 由安装器生成独立 root/业务密码；已有或外部数据库必须由管理员提供真实密码。
  local dbPwd dbHost dbPort installMode rootPwd
  dbPwd="$(conf_get DB_PASSWORD '')"
  dbHost="$(conf_get DB_HOST '')"; dbPort="$(conf_get DB_PORT 3306)"
  installMode="$(dependency_install_mode manual)"
  if [[ -z "${dbPwd}" ]]; then
    if [[ "${installMode}" == "auto" && ( "${dbHost}" == "127.0.0.1" || "${dbHost}" == "localhost" ) ]] \
        && ! command -v mysqld >/dev/null 2>&1 && ! tcp_reachable "${dbHost}" "${dbPort}"; then
      dbPwd="$(gen_database_secret)"
      conf_set DB_PASSWORD "${dbPwd}"
      rootPwd="$(conf_get MYSQL_ROOT_PASSWORD '')"
      if [[ -z "${rootPwd}" ]]; then
        rootPwd="$(gen_database_secret)"
        conf_set MYSQL_ROOT_PASSWORD "${rootPwd}"
      fi
      ok "全新本机 MySQL 将使用自动生成的12位 root/业务密码"
    else
      risk "已有或外部 MySQL 必须填写真实数据库密码"
      dbPwd="$(ask_secret '请输入数据库密码（输入内容不会显示）')"
      [[ -n "${dbPwd}" ]] || die "数据库密码不能为空；如需调整主机/端口，请编辑 ${CONF} 后重试"
      validate_secret 'DB_PASSWORD' "${dbPwd}"
      conf_set DB_PASSWORD "${dbPwd}"
    fi
  fi
  validate_secret 'DB_PASSWORD' "${dbPwd}"
  rootPwd="$(conf_get MYSQL_ROOT_PASSWORD '')"
  [[ -z "${rootPwd}" ]] || validate_secret 'MYSQL_ROOT_PASSWORD' "${rootPwd}"
  local redisPwd
  redisPwd="$(conf_get REDIS_PASSWORD '')"
  [[ -n "${redisPwd}" ]] && validate_secret 'REDIS_PASSWORD' "${redisPwd}"
  local redisUser mqAccessKey mqSecretKey
  redisUser="$(conf_get REDIS_USERNAME '')"
  [[ -n "${redisUser}" ]] && validate_secret 'REDIS_USERNAME' "${redisUser}"
  mqAccessKey="$(conf_get ROCKETMQ_ACCESS_KEY '')"; mqSecretKey="$(conf_get ROCKETMQ_SECRET_KEY '')"
  [[ -n "${mqAccessKey}" ]] && validate_secret 'ROCKETMQ_ACCESS_KEY' "${mqAccessKey}"
  [[ -n "${mqSecretKey}" ]] && validate_secret 'ROCKETMQ_SECRET_KEY' "${mqSecretKey}"
  [[ -z "${mqAccessKey}" && -z "${mqSecretKey}" || -n "${mqAccessKey}" && -n "${mqSecretKey}" ]] \
    || die "RocketMQ ACL 的 AccessKey 与 SecretKey 必须同时填写或同时留空"
  if [[ -n "${mqAccessKey}" ]]; then
    [[ "${mqAccessKey}" =~ ^[A-Za-z0-9]+$ && "${mqSecretKey}" =~ ^[A-Za-z0-9]+$ ]] \
      || die "RocketMQ ACL 凭证仅允许字母和数字"
  fi
  case "$(conf_get ROCKETMQ_ENABLED false)" in true|false) ;; *) die "ROCKETMQ_ENABLED 只支持 true 或 false" ;; esac
  dependency_install_mode manual >/dev/null
  case "$(conf_get ROCKETMQ_FLUSH_DISK_TYPE ASYNC_FLUSH)" in
    ASYNC_FLUSH|SYNC_FLUSH) ;;
    *) die "ROCKETMQ_FLUSH_DISK_TYPE 只支持 ASYNC_FLUSH 或 SYNC_FLUSH" ;;
  esac
  if [[ "$(conf_get ROCKETMQ_ENABLED false)" == "true" ]]; then
    [[ -n "$(conf_get ROCKETMQ_NAMESERVER '')" ]] || die "启用 RocketMQ 时必须配置 ROCKETMQ_NAMESERVER"
  fi
  validate_rocketmq_mode manual
  [[ "$(conf_get REDIS_DATABASE 0)" =~ ^[0-9]+$ ]] || die "REDIS_DATABASE 必须是非负整数"
  case "$(conf_get HTTPS_ENABLED false)" in true|false) ;; *) die "HTTPS_ENABLED 只支持 true 或 false" ;; esac
  if [[ "$(conf_get HTTPS_ENABLED false)" == "true" ]]; then
    local httpsDomain httpsAdminDomain httpsCertPath httpsKeyPath
    validate_port HTTPS_PORT "$(conf_get HTTPS_PORT 443)"
    httpsDomain="$(conf_get HTTPS_PUBLIC_DOMAIN '')"
    httpsAdminDomain="$(conf_get HTTPS_ADMIN_DOMAIN '')"
    [[ "${httpsDomain}" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || die "HTTPS_PUBLIC_DOMAIN 格式错误"
    [[ "${httpsAdminDomain}" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || die "HTTPS_ADMIN_DOMAIN 格式错误"
    [[ "${httpsDomain}" != "${httpsAdminDomain}" ]] || die "用户端与管理端 HTTPS 域名不能相同"
    [[ "$(conf_get HTTPS_PORT 443)" != "$(conf_get HTTP_PORT 80)" \
       && "$(conf_get HTTPS_PORT 443)" != "$(conf_get ADMIN_PORT 8089)" ]] \
      || die "HTTPS_PORT 不能与 HTTP_PORT 或 ADMIN_PORT 重复"
    mkdir -p "${DATA_ROOT}/config/ssl"; chmod 700 "${DATA_ROOT}/config/ssl"
    httpsCertPath="$(conf_get HTTPS_CERT_PATH "${DATA_ROOT}/config/ssl/fullchain.pem")"
    httpsKeyPath="$(conf_get HTTPS_KEY_PATH "${DATA_ROOT}/config/ssl/privkey.pem")"
    validate_https_file HTTPS_CERT_PATH "${httpsCertPath}"
    validate_https_file HTTPS_KEY_PATH "${httpsKeyPath}"
  fi
  # JWT 密钥留空自动生成写回
  if [[ -z "$(conf_get TOKEN_SECRET '')" ]]; then
    conf_set TOKEN_SECRET "$(gen_secret)"
    ok "TOKEN_SECRET 留空，已自动生成强随机值写入配置"
  fi
  write_deployment_descriptor systemd "${CONF}"
  return 0
}

# ----------------------------------------------------------------------------
# Docker 模式配置校验：.env 首次由 .env.example 自动创建（唯一配置真源），
# 后续不覆盖用户配置；仅在关键密钥留空时自动生成强随机值写回
# ----------------------------------------------------------------------------
ensure_env_file() {
  local existedBefore=0 legacyMissingDbHost=0
  # 任何 Docker 配置读取前先将旧版 /tmp/.../docker/.env 迁移到受控目录。
  # 迁移不通过就终止，绝不会为已有部署生成一份新默认配置覆盖原值。
  migrate_legacy_docker_config
  [[ -f "${ENV_FILE}" ]] && existedBefore=1
  if [[ "${existedBefore}" == "1" ]] && ! grep -qE '^DB_HOST=' "${ENV_FILE}" 2>/dev/null; then
    legacyMissingDbHost=1
  fi
  if [[ ! -f "${ENV_FILE}" ]]; then
    mkdir -p "$(dirname "${ENV_FILE}")"
    if [[ -f "${COMPOSE_DIR}/.env.example" ]]; then
      cp "${COMPOSE_DIR}/.env.example" "${ENV_FILE}"
    else
      cat > "${ENV_FILE}" <<EOF
# ============================================================================
# AID Docker 部署配置（唯一配置真源）
# 修改后执行 sudo aid restart 生效。
# 没有域名时仍须保留 HTTP_PORT 和 ADMIN_PORT，并且不要启用 https Profile。
# 密码与密钥留空时由脚本生成强随机值；生成后禁止公开或提交到仓库。
# ============================================================================

# ---------------- 数据目录 ----------------
DATA_ROOT=${DATA_ROOT}

# ---------------- 依赖处理 ----------------
# auto=自动下载缺失镜像；manual=缺镜像时停止并打印 docker pull 命令。
DEPENDENCY_INSTALL_MODE=auto
# auto=按网络自动选择；cn=国内镜像优先；global=官方地址优先。
DEPENDENCY_REGION=auto
# Docker Hub 国内代理前缀，逗号分隔；自动测速排序、失败逐级回退并校验官方摘要。
DOCKER_MIRRORS=${DEFAULT_DOCKER_MIRRORS}
# 下载总时长上限（秒）；0=不限总时长，正整数（例如1500）=到时停止。
DOWNLOAD_TIMEOUT_SECONDS=0

# ---------------- HTTP 访问（无需域名） ----------------
# 用户端：http://服务器IP；非 80 端口需在地址后追加端口。
HTTP_PORT=80
# 管理端访问码在首次完成数据库初始化后随机生成，部署完成时打印完整地址。
ADMIN_PORT=8089

# ---------------- HTTPS（可选，需要域名和证书） ----------------
# 只有 COMPOSE_PROFILES 包含 https 时才会启用。
# 无域名或尚未完成 DNS 解析时，以下字段仅为占位，不会被读取或校验。
HTTPS_PORT=443
HTTPS_PUBLIC_DOMAIN=www.example.com
HTTPS_ADMIN_DOMAIN=admin.example.com
HTTPS_CERT_PATH=${DATA_ROOT}/config/ssl/fullchain.pem
HTTPS_KEY_PATH=${DATA_ROOT}/config/ssl/privkey.pem

# ---------------- MySQL 5.7 ----------------
# 内置 MySQL 使用默认 Profile；密码留空时由脚本生成。
MYSQL_ROOT_PASSWORD=
DB_HOST=mysql
DB_PORT=3306
DB_NAME=aid
DB_USERNAME=aid
DB_PASSWORD=
# 内置 MySQL 对宿主机的映射端口；外部 MySQL 模式不使用。
MYSQL_PORT=3306

# ---------------- Redis ----------------
REDIS_HOST=redis
REDIS_PORT=6379
# Redis 6+ ACL 填用户名；传统 requirepass 或无认证模式留空。
REDIS_USERNAME=
# 无密码可留空。
REDIS_PASSWORD=
REDIS_DATABASE=0

# ---------------- 后端安全与端口 ----------------
# 留空时由脚本自动生成；生成后请妥善备份，禁止随意更换。
TOKEN_SECRET=
BACKEND_PORT=8080

# ---------------- 内置组件开关 ----------------
# 默认启动内置 MySQL 5.7 + Redis，不启用 HTTPS 和 RocketMQ。
# 启用 HTTPS：mysql,redis,https；启用内置 MQ：mysql,redis,mq。
COMPOSE_PROFILES=mysql,redis

# ---------------- RocketMQ（可选） ----------------
# false 表示使用本地任务模式，不启动也不连接 RocketMQ。
ROCKETMQ_ENABLED=false
ROCKETMQ_NAMESERVER=rocketmq-nameserver:9876
# 内置Broker刷盘：ASYNC_FLUSH性能优先；SYNC_FLUSH持久性优先。
ROCKETMQ_FLUSH_DISK_TYPE=ASYNC_FLUSH
# 启用 ACL 时两项同时填写；未启用 ACL 时同时留空。
ROCKETMQ_ACCESS_KEY=
ROCKETMQ_SECRET_KEY=
EOF
    fi
    chmod 600 "${ENV_FILE}"
    ok "已自动生成 Docker 配置: ${ENV_FILE}"
    warn "首次安装采用安全默认方案：内置 MySQL + Redis、暂不启用 RocketMQ、密码与 JWT 密钥自动生成"
  else
    merge_runtime_configuration docker
  fi
  # 兼容旧版配置：此前 MySQL 固定内置，配置中没有 DB_HOST/DB_PORT，且 Profile
  # 只写 redis。升级后显式补成 mysql Profile，防止旧用户被误判为外部数据库。
  if [[ "${legacyMissingDbHost}" == "1" ]]; then
    warn "检测到旧版 Docker 配置，已保持原 COMPOSE_PROFILES 值并兼容启用内置 MySQL"
  elif ! grep -qE '^DB_PORT=' "${ENV_FILE}" 2>/dev/null; then
    env_set DB_PORT 3306
  fi
  # 关键密钥留空自动生成（字母数字强随机，写回 .env 持久化）
  local key
  for key in TOKEN_SECRET; do
    if [[ -z "$(env_get "${key}" '')" ]]; then
      env_set "${key}" "$(gen_secret)"
      ok "${key} 留空，已自动生成强随机值写入 .env"
    fi
  done
  if docker_profile_enabled mysql; then
    for key in MYSQL_ROOT_PASSWORD DB_PASSWORD; do
      if [[ -z "$(env_get "${key}" '')" ]]; then
        env_set "${key}" "$(gen_database_secret)"
        ok "${key} 留空，已为内置 MySQL 生成12位随机值写入 .env"
      fi
    done
  fi
  local requiredKey
  for requiredKey in DATA_ROOT DB_HOST DB_PORT DB_NAME DB_USERNAME DB_PASSWORD TOKEN_SECRET REDIS_HOST REDIS_PORT; do
    [[ -n "$(env_get "${requiredKey}" '')" ]] || die "${requiredKey} 不能为空"
  done
  # 校验密码字符不破坏 .env/JSON 解析
  [[ -z "$(env_get MYSQL_ROOT_PASSWORD '')" ]] || validate_secret 'MYSQL_ROOT_PASSWORD' "$(env_get MYSQL_ROOT_PASSWORD '')"
  validate_secret 'DB_PASSWORD' "$(env_get DB_PASSWORD '')"
  local redisPwd
  redisPwd="$(env_get REDIS_PASSWORD '')"
  [[ -n "${redisPwd}" ]] && validate_secret 'REDIS_PASSWORD' "${redisPwd}"
  validate_docker_extended_config
  # 提醒 DATA_ROOT 与脚本运行目录保持一致
  local envDataRoot
  envDataRoot="$(env_get DATA_ROOT /data/aid)"
  [[ "${envDataRoot}" == "${DATA_ROOT}" ]] \
    || die "Docker 配置 DATA_ROOT(${envDataRoot}) 与本次受管目录(${DATA_ROOT}) 不一致；请使用 AID_DATA_ROOT=${envDataRoot} 重新运行，或修正 ${ENV_FILE}"
  write_deployment_descriptor docker "${ENV_FILE}"
  return 0
}

confirm_initial_configuration() { # confirm_initial_configuration <docker|manual>
  local mode="$1" configFile marker currentHash editNow confirmed
  if [[ "${mode}" == "docker" ]]; then configFile="${ENV_FILE}"; else configFile="${CONF}"; fi
  mkdir -p "${CONFIG_ROOT}"
  marker="${CONFIG_ROOT}/.${mode}-config-confirmed.sha256"
  currentHash="$(config_sha256 "${configFile}")"
  if [[ -n "${currentHash}" && -f "${marker}" && "$(tr -d '[:space:]' < "${marker}")" == "${currentHash}" ]]; then
    ok "部署配置已确认且未发生变化: ${configFile}"
    return 0
  fi

  section "部署配置（必须先完成）"
  echo -e "  部署方式 : ${C_GREEN}${mode}${C_RESET}"
  echo -e "  配置文件 : ${C_GREEN}${configFile}${C_RESET}"
  warn "配置未确认前不会拉取三端业务源码、构建程序、初始化数据库或启动任何 AID 服务"
  if [[ "${AID_ASSUME_YES:-0}" == "1" ]]; then
    [[ "${AID_CONFIG_CONFIRMED:-0}" == "1" ]] \
      || die "非交互部署必须同时设置 AID_CONFIG_CONFIRMED=1，明确确认已检查配置文件"
  else
    editNow="$(ask_yes_no '现在打开配置文件检查和修改？' 'y')"
    if [[ "${editNow}" == "y" ]]; then
      "${EDITOR:-vi}" "${configFile}" </dev/tty >/dev/tty \
        || die "配置编辑未正常完成，请检查 ${configFile} 后重新运行"
    fi
  fi

  # 编辑完成后重新执行必填项、端口和密钥校验；任何不合法内容都在构建前中止。
  if [[ "${mode}" == "docker" ]]; then ensure_env_file; else ensure_conf_file; fi
  if [[ "${mode}" == "docker" ]]; then
    validate_port HTTP_PORT "$(env_get HTTP_PORT 80)"
    validate_port ADMIN_PORT "$(env_get ADMIN_PORT 8089)"
    validate_port BACKEND_PORT "$(env_get BACKEND_PORT 8080)"
    validate_port DB_PORT "$(env_get DB_PORT 3306)"
    docker_profile_enabled mysql && validate_port MYSQL_PORT "$(env_get MYSQL_PORT 3306)"
    validate_port REDIS_PORT "$(env_get REDIS_PORT 6379)"
    docker_profile_enabled https && validate_port HTTPS_PORT "$(env_get HTTPS_PORT 443)"
  else
    validate_port HTTP_PORT "$(conf_get HTTP_PORT 80)"
    validate_port ADMIN_PORT "$(conf_get ADMIN_PORT 8089)"
    validate_port BACKEND_PORT "$(conf_get BACKEND_PORT 8080)"
    validate_port DB_PORT "$(conf_get DB_PORT 3306)"
    validate_port REDIS_PORT "$(conf_get REDIS_PORT 6379)"
  fi
  if [[ "${mode}" == "docker" ]]; then
    echo "  用户端口 : $(env_get HTTP_PORT 80)"
    echo "  管理端口 : $(env_get ADMIN_PORT 8089)"
    echo "  后端端口 : $(env_get BACKEND_PORT 8080)"
    if docker_profile_enabled mysql; then
      echo "  数据库   : 内置 MySQL 5.7:$(env_get MYSQL_PORT 3306)/$(env_get DB_NAME aid)"
    else
      echo "  数据库   : 外部 MySQL $(env_get DB_HOST):$(env_get DB_PORT 3306)/$(env_get DB_NAME aid)（不会启动内置容器）"
    fi
    if docker_profile_enabled redis; then
      echo "  Redis    : 内置容器 $(env_get REDIS_HOST redis):$(env_get REDIS_PORT 6379)"
    else
      echo "  Redis    : 外部服务 $(env_get REDIS_HOST):$(env_get REDIS_PORT 6379)（不会启动内置容器）"
    fi
    echo "  HTTP网关 : 内置 Nginx 容器"
    echo "  依赖处理 : $(dependency_install_mode docker)（已有镜像自动跳过）"
    if docker_profile_enabled https; then
      echo "  HTTPS    : $(env_get HTTPS_PUBLIC_DOMAIN) / $(env_get HTTPS_ADMIN_DOMAIN) :$(env_get HTTPS_PORT 443)"
    else
      echo "  HTTPS    : 未启用"
    fi
    if [[ "$(env_get ROCKETMQ_ENABLED false)" != "true" ]]; then
      echo "  RocketMQ : 未启用（本地任务模式）"
    elif docker_profile_enabled mq; then
      echo "  RocketMQ : 内置容器，$(env_get ROCKETMQ_FLUSH_DISK_TYPE ASYNC_FLUSH)"
    else
      echo "  RocketMQ : 外部服务 $(env_get ROCKETMQ_NAMESERVER)"
    fi
  else
    echo "  用户端口 : $(conf_get HTTP_PORT 80)"
    echo "  管理端口 : $(conf_get ADMIN_PORT 8089)"
    echo "  后端端口 : $(conf_get BACKEND_PORT 8080)"
    echo "  数据库   : $(conf_get DB_HOST 127.0.0.1):$(conf_get DB_PORT 3306)/$(conf_get DB_NAME aid)"
    echo "  Redis    : $(conf_get REDIS_HOST 127.0.0.1):$(conf_get REDIS_PORT 6379)"
    echo "  依赖处理 : $(dependency_install_mode manual)（已有且版本合格自动跳过）"
    if [[ "$(conf_get HTTPS_ENABLED false)" == "true" ]]; then
      echo "  HTTPS    : $(conf_get HTTPS_PUBLIC_DOMAIN) / $(conf_get HTTPS_ADMIN_DOMAIN) :$(conf_get HTTPS_PORT 443)"
    else
      echo "  HTTPS    : 未启用"
    fi
    if [[ "$(conf_get ROCKETMQ_ENABLED false)" == "true" ]]; then
      echo "  RocketMQ : $(conf_get ROCKETMQ_NAMESERVER)"
    else
      echo "  RocketMQ : 未启用（本地任务模式）"
    fi
  fi
  if [[ "${AID_ASSUME_YES:-0}" != "1" ]]; then
    confirmed="$(ask_yes_no '确认以上配置作为本次部署唯一配置真源？' 'n')"
    [[ "${confirmed}" == "y" ]] || die "配置尚未确认，部署已停止"
  fi
  currentHash="$(config_sha256 "${configFile}")"
  [[ -n "${currentHash}" ]] || die "无法计算配置文件摘要"
  printf '%s\n' "${currentHash}" > "${marker}"
  chmod 600 "${marker}"
  ok "配置已确认，允许进入环境检查与源码构建"
}

compose_cmd() {
  local profiles
  profiles="$(env_get COMPOSE_PROFILES mysql,redis | tr -d '[:space:]')"
  if [[ "$(env_get DB_HOST mysql)" == "mysql" && ",${profiles}," != *",mysql,"* ]]; then
    if [[ -n "${profiles}" ]]; then profiles="${profiles},mysql"; else profiles="mysql"; fi
  fi
  # --env-file 仅负责 Compose 文件变量替换；部分 Compose 版本不会据此激活
  # COMPOSE_PROFILES。每次调用都显式传递（包括空值），避免内置 Redis/MQ/HTTPS
  # 未启动，或继承当前 Shell 的旧 Profile 意外启动可选服务。
  COMPOSE_PROFILES="${profiles}" docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_DIR}/docker-compose.yml" "$@"
}

validate_https_runtime() {
  docker_profile_enabled https || return 0
  log "校验 HTTPS 证书与 Nginx 配置..."
  compose_cmd run --rm --no-deps nginx-https nginx -t \
    || die "HTTPS 证书、私钥或 Nginx 配置校验失败"
}

wait_https_healthy() {
  docker_profile_enabled https || return 0
  local elapsed=0 status=""
  while (( elapsed < 90 )); do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' aid-nginx-https 2>/dev/null || true)"
    [[ "${status}" == "healthy" ]] && { ok "HTTPS 入口已就绪"; return 0; }
    [[ "${status}" == "unhealthy" || "${status}" == "exited" || "${status}" == "dead" ]] \
      && { err "HTTPS 容器状态异常: ${status}"; return 1; }
    sleep 2; elapsed=$((elapsed + 2))
  done
  err "HTTPS 容器健康检查超时"
  return 1
}

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
  [[ -f "${DATA_ROOT}/app/admin-dist/index.html" ]] \
    || die "管理端静态入口缺失: ${DATA_ROOT}/app/admin-dist/index.html"
  [[ -f "${DATA_ROOT}/app/web-dist/index.html" ]] \
    || die "Web 用户端静态入口缺失: ${DATA_ROOT}/app/web-dist/index.html"
  [[ -f "${DATA_ROOT}/app/web-dist/200.html" ]] \
    || die "Web 用户端 SPA 通用入口缺失: ${DATA_ROOT}/app/web-dist/200.html"
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

# 从已签名官方清单中按宿主机架构取得小型升级器包。这条路径不构建三端，
# 专用于老环境补装、升级器损坏恢复，以及主程序升级前的升级器优先更新。
ensure_official_updater_binary() {
  local platform updaterVersion currentVersion currentComparison currentBinarySha
  local recordedVersion recordedPackageSha recordedBinarySha installedBinarySha
  local url mirror sha256 archive listFile extractDir source actual
  local downloaded="no"
  case "$(uname -m)" in
    x86_64) platform="linux_amd64" ;;
    aarch64) platform="linux_arm64" ;;
    *) die "升级器不支持当前架构: $(uname -m)" ;;
  esac
  [[ -n "${RESOLVED_MANIFEST_PATH:-}" && -f "${RESOLVED_MANIFEST_PATH}" ]] || resolve_official_release
  updaterVersion="$(json_updater_version "${RESOLVED_MANIFEST_PATH}" "${RESOLVED_CHANNEL}")"
  url="$(json_updater_package_string "${RESOLVED_MANIFEST_PATH}" "${RESOLVED_CHANNEL}" "${platform}" url)"
  mirror="$(json_updater_package_string "${RESOLVED_MANIFEST_PATH}" "${RESOLVED_CHANNEL}" "${platform}" mirror)"
  sha256="$(json_updater_package_string "${RESOLVED_MANIFEST_PATH}" "${RESOLVED_CHANNEL}" "${platform}" sha256 | tr 'A-F' 'a-f')"
  release_fields_valid "${updaterVersion}" "${url}" "${sha256}" \
    || die "官方清单缺少 ${platform} 升级器制品"
  manifest_payload_contains_updater "${RESOLVED_MANIFEST_PATH}" "${updaterVersion}" "${url}" "${sha256}" \
    || die "升级器制品与签名清单载荷不一致"

  currentVersion="$("${DATA_ROOT}/app/updater/aid-updater" -version 2>/dev/null || true)"
  if [[ "${currentVersion}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
    currentComparison="$(version_compare "${currentVersion}" "${updaterVersion}")"
    if [[ "${currentComparison}" == "1" ]]; then
      ok "本地升级器 ${currentVersion} 高于官方 ${updaterVersion}，保留本地版本且不执行降级"
      return 0
    fi
    if [[ "${currentComparison}" == "0" ]]; then
      recordedVersion="$(state_get OFFICIAL_UPDATER_VERSION '')"
      recordedPackageSha="$(state_get OFFICIAL_UPDATER_PACKAGE_SHA256 '' | tr 'A-F' 'a-f')"
      recordedBinarySha="$(state_get OFFICIAL_UPDATER_BINARY_SHA256 '' | tr 'A-F' 'a-f')"
      currentBinarySha="$(sha256_file "${DATA_ROOT}/app/updater/aid-updater" 2>/dev/null || true)"
      if [[ "${recordedVersion}" == "${updaterVersion}" \
          && "${recordedPackageSha}" == "${sha256}" \
          && "${recordedBinarySha}" =~ ^[0-9a-f]{64}$ \
          && "${currentBinarySha}" == "${recordedBinarySha}" ]]; then
        ok "升级器 ${currentVersion} 与当前签名清单制品一致，跳过下载"
        return 0
      fi
      if [[ -z "${recordedPackageSha}" || -z "${recordedBinarySha}" ]]; then
        warn "当前升级器缺少官方制品或二进制 SHA256 记录，将安全刷新一次"
      elif [[ "${currentBinarySha}" != "${recordedBinarySha}" ]]; then
        warn "当前升级器二进制与受信状态不一致，将重新下载并校验"
      else
        warn "官方升级器同版本制品已更新，将重新下载并校验"
      fi
    fi
  fi

  require_download_tools
  mkdir -p "${DATA_ROOT}/packages" "${DATA_ROOT}/app/updater"
  archive="${DATA_ROOT}/packages/aid-updater_${updaterVersion}_${platform}.tar.gz"
  for source in "${url}" "${mirror}"; do
    [[ -n "${source}" ]] || continue
    if try_download "${source}" "${archive}" "AID 升级器 ${updaterVersion}（${platform}）" sha256 "${sha256}"; then
      downloaded="yes"
      break
    fi
    warn "升级器当前下载地址不可用，切换备用地址"
  done
  [[ "${downloaded}" == "yes" ]] || die "AID 升级器 ${updaterVersion} 下载失败"
  actual="$(sha256_file "${archive}" || true)"
  [[ "${actual}" == "${sha256}" ]] || die "AID 升级器 SHA256 校验失败"

  listFile="$(mktemp)"
  if ! tar -tzf "${archive}" > "${listFile}" 2>/dev/null \
      || [[ "$(grep -Ec '^(\./)?aid-updater$' "${listFile}")" -ne 1 ]] \
      || [[ "$(wc -l < "${listFile}")" -ne 1 ]] \
      || tar -tvzf "${archive}" 2>/dev/null | awk 'substr($1,1,1) != "-" { bad=1 } END { exit(bad ? 0 : 1) }'; then
    rm -f -- "${listFile}"
    rm -f -- "${archive}"
    die "升级器压缩包结构不安全，已拒绝解压"
  fi
  rm -f -- "${listFile}"
  extractDir="$(mktemp -d "${DATA_ROOT}/packages/.updater-extract.XXXXXX")"
  if ! tar --no-same-owner --no-same-permissions -xzf "${archive}" -C "${extractDir}"; then
    rm -rf -- "${extractDir}"
    die "升级器压缩包解压失败"
  fi
  source="${extractDir}/aid-updater"
  [[ -f "${source}" && ! -L "${source}" ]] \
    || { rm -rf -- "${extractDir}"; die "升级器压缩包缺少可执行文件"; }
  install -m 0755 "${source}" "${DATA_ROOT}/app/updater/aid-updater" \
    || { rm -rf -- "${extractDir}"; die "升级器二进制落盘失败"; }
  rm -rf -- "${extractDir}"
  currentVersion="$("${DATA_ROOT}/app/updater/aid-updater" -version 2>/dev/null || true)"
  [[ "${currentVersion}" == "${updaterVersion}" ]] \
    || die "升级器二进制版本校验失败"
  installedBinarySha="$(sha256_file "${DATA_ROOT}/app/updater/aid-updater" 2>/dev/null || true)"
  [[ "${installedBinarySha}" =~ ^[0-9a-f]{64}$ ]] \
    || die "升级器二进制 SHA256 计算失败"
  # 仅在下载、摘要校验、安装、版本和二进制摘要复核全部成功后持久化官方制品身份。
  state_set OFFICIAL_UPDATER_VERSION "${updaterVersion}"
  state_set OFFICIAL_UPDATER_PACKAGE_SHA256 "${sha256}"
  state_set OFFICIAL_UPDATER_BINARY_SHA256 "${installedBinarySha}"
  ok "升级器 ${updaterVersion} 已从官方发布制品安全恢复"
}

# ----------------------------------------------------------------------------
# 升级器（aid-updater）安装：两种部署方式自动完成，页面即可一键升级
#   docker 模式 → compose 内 aid-updater 容器运行（写配置即可，容器随编排拉起）
#   manual 模式 → systemd 服务运行
# ----------------------------------------------------------------------------
UPDATER_CONFIG_DIR="${AID_UPDATER_CONFIG_DIR:-/etc/aid-updater}"
UPDATER_CONFIG_FILE="${UPDATER_CONFIG_DIR}/config.json"
UPDATER_DATA_DIR="${AID_UPDATER_DATA_DIR:-/var/lib/aid-updater}"

write_aid_updater_markers() {
  local directory marker tmp
  for directory in "${UPDATER_CONFIG_DIR}" "${UPDATER_DATA_DIR}"; do
    marker="${directory}/.aid-managed"
    tmp="$(mktemp "${directory}/.aid-managed.XXXXXX")" || die "无法写入升级器受管标记: ${directory}"
    cat > "${tmp}" <<EOF
AID_MANAGED_UPDATER=1
AID_DATA_ROOT=${DATA_ROOT}
AID_MANAGER_SCRIPT=${MANAGED_SCRIPT}
EOF
    chmod 600 "${tmp}"
    mv -f -- "${tmp}" "${marker}"
  done
}

write_updater_config() { # write_updater_config <docker|manual>
  local mode="$1" serviceManager backendService restartServices healthUrl execContainer clientImage dockerNetwork dbHost dbPort dbUser dbPwd dbName configPath defaultConfigPath
  mkdir -p "${UPDATER_CONFIG_DIR}" "${UPDATER_DATA_DIR}/inbox" "${UPDATER_DATA_DIR}/work" "${UPDATER_DATA_DIR}/backups"
  if [[ "${mode}" == "docker" ]]; then
    serviceManager="docker"; backendService="aid-server"
    restartServices='["aid-web", "aid-nginx"]'
    docker_profile_enabled https && restartServices='["aid-web", "aid-nginx", "aid-nginx-https"]'
    # 升级器容器与后端同网络，直连服务名探活，不受宿主机端口映射影响
    healthUrl="http://aid-server:8080"
    if docker_profile_enabled mysql; then
      # 内置数据库直接复用 aid-mysql 内的客户端。
      execContainer="aid-mysql"; clientImage=""; dockerNetwork=""
      dbHost="127.0.0.1"; dbPort="3306"
      dbUser="root"; dbPwd="$(env_get MYSQL_ROOT_PASSWORD '')"
    else
      # 外部数据库使用一次性 MySQL 5.7 客户端容器，升级器本身无需安装客户端。
      execContainer=""; clientImage="mysql:5.7"; dockerNetwork="host"
      dbHost="$(env_get DB_HOST)"; dbPort="$(env_get DB_PORT 3306)"
      dbUser="$(env_get DB_USERNAME)"; dbPwd="$(env_get DB_PASSWORD '')"
    fi
    dbName="$(env_get DB_NAME aid)"
    configPath="${ENV_FILE}"; defaultConfigPath="${DEFAULT_DOCKER_CONFIG}"
    write_deployment_descriptor docker "${configPath}"
  else
    serviceManager="systemd"; backendService="aid"
    # 手动部署的 Web 为 Nginx 直读静态目录，替换产物后无需重启不存在的 aid-web 服务。
    restartServices='[]'
    healthUrl="http://127.0.0.1:$(conf_get BACKEND_PORT 8080)"
    execContainer=""; clientImage=""; dockerNetwork=""
    dbHost="$(conf_get DB_HOST 127.0.0.1)"; dbPort="$(conf_get DB_PORT 3306)"
    dbUser="$(conf_get DB_USERNAME aid)"; dbPwd="$(conf_get DB_PASSWORD '')"
    dbName="$(conf_get DB_NAME aid)"
    configPath="${CONF}"; defaultConfigPath="${DEFAULT_MANUAL_CONFIG}"
    write_deployment_descriptor systemd "${configPath}"
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
  "sourceBuildScript": "${INSTALLER_ROOT}/deploy/${SOURCE_BUILDER_NAME}",
  "sourceBuildTimeoutSeconds": 7200,
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
    "execContainer": "${execContainer}",
    "clientImage": "${clientImage}",
    "dockerNetwork": "${dockerNetwork}"
  },
  "deployment": {
    "descriptorFile": "${DEPLOYMENT_DESCRIPTOR}",
    "configPath": "${configPath}",
    "defaultConfigPath": "${defaultConfigPath}",
    "allowedConfigRoot": "${CONFIG_ROOT}",
    "composeFile": "${INSTALLER_ROOT}/deploy/docker/docker-compose.yml",
    "managerScript": "${MANAGED_SCRIPT}"
  }
  }
EOF
  chmod 600 "${UPDATER_CONFIG_FILE}"
  write_aid_updater_markers
}

stop_conflicting_updater_runtime() { # stop_conflicting_updater_runtime <docker|manual>
  local targetMode="$1" unitPath="${AID_SYSTEMD_UNIT_DIR}/aid-updater.service" running="false"
  if [[ "${targetMode}" == "docker" ]]; then
    if aid_systemd_unit_belongs_to_current_install "${unitPath}"; then
      systemctl disable --now aid-updater >/dev/null 2>&1 \
        || { err "停止旧手动部署升级器失败"; return 1; }
      ok "已停止旧手动部署升级器，避免与 Docker 升级器重复消费任务"
    elif command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet aid-updater; then
      err "检测到活动的同名 systemd 升级器，但无法确认属于当前 AID 数据目录"
      echo "  请先核对: systemctl status aid-updater" >&2
      echo "  确认归属后由管理员停止该服务，再重新执行部署；脚本不会处理未知服务" >&2
      return 1
    fi
    return 0
  fi
  [[ "${targetMode}" == "manual" ]] || { err "未知升级器部署方式: ${targetMode}"; return 1; }
  if aid_docker_daemon_available \
      && docker inspect aid-updater >/dev/null 2>&1; then
    if aid_docker_container_belongs_to_current_install aid-updater; then
      # 删除而不只 stop：容器的 restart=always 不能在 daemon 重启后重新参与任务竞争；
      # 后续切回 Docker 时 Compose 会按当前配置重新创建。
      docker rm -f aid-updater >/dev/null 2>&1 \
        || { err "停止旧 Docker 升级器失败"; return 1; }
      ok "已移除旧 Docker 升级器，避免与手动部署升级器重复消费任务"
    else
      running="$(docker inspect --format '{{.State.Running}}' aid-updater 2>/dev/null || true)"
      if [[ "${running}" == "true" ]]; then
        err "检测到活动的同名 Docker 升级器，但无法确认属于当前 AID 数据目录"
        echo "  请先核对: docker inspect aid-updater" >&2
        echo "  确认归属后由管理员停止该容器，再重新执行部署；脚本不会处理未知容器" >&2
        return 1
      fi
    fi
  fi
}

# 安装/修复升级器（幂等；两种部署方式通用）
setup_updater() { # setup_updater <docker|manual>
  local mode="$1" deadline
  [[ -x "${DATA_ROOT}/app/updater/aid-updater" ]] \
    || { err "缺少升级器二进制: ${DATA_ROOT}/app/updater/aid-updater"; return 1; }
  stop_conflicting_updater_runtime "${mode}" || return 1
  write_updater_config "${mode}"
  # 清除旧心跳，本次必须由新进程重新上报，避免 60 秒窗口内将启动失败误判为健康。
  rm -f -- "${UPDATER_DATA_DIR}/health.json"
  if [[ "${mode}" == "docker" ]]; then
    # 容器模式：任何 Compose 或健康检查错误都必须返回失败，禁止误报“已启动”。
    ensure_docker_image "docker:27-cli" "升级器Docker客户端"
    section "安装/修复 Docker 在线升级器"
    if ! compose_cmd up -d aid-updater || ! compose_cmd restart aid-updater; then
      docker_container_diagnostics aid-updater "在线升级器"
      stop_failed_docker_service aid-updater "在线升级器"
      return 1
    fi
    if ! wait_docker_container_healthy aid-updater "在线升级器" 120; then
      stop_failed_docker_service aid-updater "在线升级器"
      return 1
    fi
  else
    install -m 0755 "${DATA_ROOT}/app/updater/aid-updater" /usr/local/bin/aid-updater
    cat > /etc/systemd/system/aid-updater.service <<EOF
# AID_MANAGED_UNIT=1
# AID_DATA_ROOT=${DATA_ROOT}
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
    systemctl restart aid-updater || { manual_service_diagnostics aid-updater "在线升级器"; return 1; }
    deadline=$(( $(date +%s) + 60 ))
    while [[ $(date +%s) -lt ${deadline} ]]; do
      updater_runtime_ready manual && break
      systemctl is-active --quiet aid-updater \
        || { manual_service_diagnostics aid-updater "在线升级器"; return 1; }
      sleep 2
    done
    if ! updater_runtime_ready manual; then
      manual_service_diagnostics aid-updater "在线升级器（健康上报超时）"
      return 1
    fi
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

# Docker 模式统一数据库客户端：内置库经 aid-mysql 执行；外部库使用一次性
# mysql:5.7 客户端容器，不要求宿主机或升级器容器安装 mysql/mysqldump。
docker_mysql_tool() { # docker_mysql_tool <mysql|mysqldump> [参数...]
  local tool="$1" AID_DEPENDENCY_INSTALL_MODE
  shift
  if docker_profile_enabled mysql; then
    # 内置 MySQL 的 root 账号以 socket 方式完成凭证迁移和校验。后续管理查询
    # 必须走同一入口，不能改用 TCP 触发另一个 root@host 账号而误报初始化失败。
    MYSQL_PWD="$(env_get MYSQL_ROOT_PASSWORD '')" \
      docker exec -i -e MYSQL_PWD aid-mysql "${tool}" \
        --protocol=socket --user root "$@"
  else
    AID_DEPENDENCY_INSTALL_MODE="$(dependency_install_mode docker)"
    ensure_docker_image "mysql:5.7" "MySQL5.7客户端"
    MYSQL_PWD="$(env_get DB_PASSWORD '')" \
      docker run --rm -i --network host \
        --add-host host.docker.internal:host-gateway \
        -e MYSQL_PWD mysql:5.7 "${tool}" \
        --host "$(env_get DB_HOST)" --port "$(env_get DB_PORT 3306)" \
        --user "$(env_get DB_USERNAME)" "$@"
  fi
}

# 读取已部署数据库中的后台入口配置，用于部署完成后输出真实登录地址。
# 新库由安装器生成随机访问码；旧库始终保留管理员已经设置的值。
read_admin_entry_settings() {
  local mode dbName rows query
  ADMIN_ENTRY_ENABLED_VALUE="true"
  ADMIN_ENTRY_CODE_VALUE="${DEFAULT_ADMIN_ENTRY_CODE}"
  mode="$(detect_mode)"
  dbName="$(setting_get DB_NAME aid)"
  query="SELECT config_name, config_value FROM aid_config WHERE category='admin_entry' AND config_name IN ('enabled','access_code')"
  if [[ "${mode}" == "docker" ]]; then
    rows="$(docker_mysql_tool mysql --batch --skip-column-names "${dbName}" --execute "${query}" 2>/dev/null)" || return 1
  else
    rows="$(MYSQL_PWD="$(conf_get DB_PASSWORD '')" mysql \
      --protocol=TCP \
      --host "$(conf_get DB_HOST 127.0.0.1)" --port "$(conf_get DB_PORT 3306)" \
      --user "$(conf_get DB_USERNAME aid)" --database "${dbName}" \
      --batch --skip-column-names --execute "${query}" 2>/dev/null)" || return 1
  fi
  local dbEnabled dbCode
  dbEnabled="$(printf '%s\n' "${rows}" | awk -F '\t' '$1 == "enabled" {print $2; exit}')"
  dbCode="$(printf '%s\n' "${rows}" | awk -F '\t' '$1 == "access_code" {print $2; exit}')"
  [[ -n "${dbEnabled}" ]] && ADMIN_ENTRY_ENABLED_VALUE="${dbEnabled}"
  if [[ "${dbCode}" =~ ^[A-Za-z0-9]+$ ]]; then
    ADMIN_ENTRY_CODE_VALUE="${dbCode}"
  elif [[ -n "${rows}" ]]; then
    ADMIN_ENTRY_CODE_VALUE=""
  fi
  return 0
}

ensure_admin_entry_code() { # ensure_admin_entry_code <docker|manual>
  local mode="$1" dbName currentCode newCode query
  dbName="$(if [[ "${mode}" == "docker" ]]; then env_get DB_NAME aid; else conf_get DB_NAME aid; fi)"
  query="SELECT config_value FROM aid_config WHERE category='admin_entry' AND config_name='access_code' LIMIT 1"
  if [[ "${mode}" == "docker" ]]; then
    currentCode="$(docker_mysql_tool mysql --batch --skip-column-names "${dbName}" --execute "${query}" 2>/dev/null | tail -n 1)" \
      || die "读取后台登录入口失败"
  else
    currentCode="$(MYSQL_PWD="$(conf_get DB_PASSWORD '')" mysql \
      --protocol=TCP \
      --host "$(conf_get DB_HOST 127.0.0.1)" --port "$(conf_get DB_PORT 3306)" \
      --user "$(conf_get DB_USERNAME aid)" --database "${dbName}" \
      --batch --skip-column-names --execute "${query}" 2>/dev/null | tail -n 1)" \
      || die "读取后台登录入口失败"
  fi
  [[ -z "${currentCode}" ]] || { ok "后台登录访问码已配置，保持原值"; return 0; }
  newCode="$(gen_secret | head -c 12)"
  [[ "${newCode}" =~ ^[A-Za-z0-9]{12}$ ]] || die "生成后台随机访问码失败"
  query="UPDATE aid_config SET config_value='${newCode}', update_by='installer', update_time=NOW() WHERE category='admin_entry' AND config_name='access_code'"
  if [[ "${mode}" == "docker" ]]; then
    docker_mysql_tool mysql "${dbName}" --execute "${query}" >/dev/null \
      || die "写入后台随机访问码失败"
  else
    MYSQL_PWD="$(conf_get DB_PASSWORD '')" mysql \
      --protocol=TCP \
      --host "$(conf_get DB_HOST 127.0.0.1)" --port "$(conf_get DB_PORT 3306)" \
      --user "$(conf_get DB_USERNAME aid)" --database "${dbName}" --execute "${query}" >/dev/null \
      || die "写入后台随机访问码失败"
  fi
  ok "已为新部署生成12位随机后台访问码"
}

docker_container_env_value() { # docker_container_env_value <容器> <变量名>
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$1" 2>/dev/null \
    | sed -n "s/^$2=//p" | head -n 1
}

docker_managed_mysql_root_exec() { # <password> [mysql参数...]
  local password="$1"
  shift
  MYSQL_PWD="${password}" docker exec -i -e MYSQL_PWD aid-mysql mysql \
    --connect-timeout=3 --protocol=socket -uroot "$@"
}

docker_managed_mysql_business_exec() { # <password> <database> <user> [mysql参数...]
  local password="$1" database="$2" user="$3"
  shift 3
  MYSQL_PWD="${password}" docker exec -i -e MYSQL_PWD aid-mysql mysql \
    --connect-timeout=3 --protocol=TCP --host=127.0.0.1 --port=3306 \
    --database="${database}" --user="${user}" "$@"
}

wait_docker_managed_mysql_bootstrap_complete() {
  local rootPwd deadline skipNetworking="" status=""
  rootPwd="$(env_get MYSQL_ROOT_PASSWORD '')"
  [[ -n "${rootPwd}" ]] || { err "内置 MySQL root 凭证不能为空"; return 1; }
  deadline=$(( $(date +%s) + 180 ))
  log "等待内置 MySQL 完成首次初始化..."
  while [[ $(date +%s) -lt ${deadline} ]]; do
    # mysql:5.7 entrypoint 首次导入期间会以 --skip-networking 临时启动 mysqld。
    # 该阶段 socket 已可连接但 /docker-entrypoint-initdb.d 尚未执行完，不能并发改账号或补导 SQL。
    skipNetworking="$(docker_managed_mysql_root_exec "${rootPwd}" --batch --skip-column-names \
      --execute 'SELECT @@skip_networking' 2>/dev/null | tail -n 1 || true)"
    case "${skipNetworking}" in
      0|OFF|off) ok "内置 MySQL 首次初始化已完成"; return 0 ;;
    esac
    status="$(docker inspect --format '{{.State.Status}}' aid-mysql 2>/dev/null || true)"
    if [[ "${status}" == "exited" || "${status}" == "dead" ]]; then
      docker_container_diagnostics aid-mysql "内置 MySQL 5.7"
      return 1
    fi
    sleep 3
  done
  err "内置 MySQL 在180秒内未完成首次初始化"
  docker_container_diagnostics aid-mysql "内置 MySQL 5.7"
  return 1
}

# MySQL 镜像只会在空数据目录首次应用 MYSQL_* 环境变量。这里在每次启动时把
# 已存在数据目录中的账号重新同步到正式 .env，确保修改配置后不会出现容器健康、
# 但业务账号仍使用旧密码的分裂状态。
reconcile_docker_managed_mysql_credentials() { # reconcile_docker_managed_mysql_credentials <上次容器root密码或空>
  local previousRootPwd="$1" rootPwd dbPwd database user rootAuth="" deadline status
  rootPwd="$(env_get MYSQL_ROOT_PASSWORD '')"; dbPwd="$(env_get DB_PASSWORD '')"
  database="$(env_get DB_NAME aid)"; user="$(env_get DB_USERNAME aid)"
  [[ -n "${rootPwd}" && -n "${dbPwd}" ]] || { err "内置 MySQL 凭证不能为空"; return 1; }
  [[ "${user}" != "root" ]] \
    || { err "Docker 内置 MySQL 的 DB_USERNAME 不能使用 root，请配置独立业务账号"; return 1; }

  deadline=$(( $(date +%s) + 120 ))
  while [[ $(date +%s) -lt ${deadline} ]]; do
    if docker_managed_mysql_root_exec "${rootPwd}" -e 'SELECT 1' >/dev/null 2>&1; then
      rootAuth="${rootPwd}"
      break
    fi
    if [[ -n "${previousRootPwd}" && "${previousRootPwd}" != "${rootPwd}" ]] \
        && docker_managed_mysql_root_exec "${previousRootPwd}" -e 'SELECT 1' >/dev/null 2>&1; then
      rootAuth="${previousRootPwd}"
      break
    fi
    if docker_managed_mysql_root_exec "" -e 'SELECT 1' >/dev/null 2>&1; then
      rootAuth=""
      break
    fi
    status="$(docker inspect --format '{{.State.Status}}' aid-mysql 2>/dev/null || true)"
    if [[ "${status}" == "exited" || "${status}" == "dead" ]]; then
      docker_container_diagnostics aid-mysql "内置 MySQL 5.7"
      return 1
    fi
    sleep 3
  done
  if [[ -z "${rootAuth}" ]] \
      && ! docker_managed_mysql_root_exec "" -e 'SELECT 1' >/dev/null 2>&1; then
    err "内置 MySQL root 密码与 ${ENV_FILE} 不一致，且无法从上次容器配置恢复"
    return 1
  fi

  docker_managed_mysql_root_exec "${rootAuth}" -e \
    "CREATE DATABASE IF NOT EXISTS \`${database}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; CREATE USER IF NOT EXISTS '${user}'@'%' IDENTIFIED BY '${dbPwd}'; ALTER USER '${user}'@'%' IDENTIFIED BY '${dbPwd}'; GRANT ALL PRIVILEGES ON \`${database}\`.* TO '${user}'@'%'; ALTER USER 'root'@'localhost' IDENTIFIED BY '${rootPwd}'; FLUSH PRIVILEGES;" \
    >/dev/null || { err "内置 MySQL 账号同步失败"; return 1; }
  docker_managed_mysql_root_exec "${rootPwd}" -e 'SELECT 1' >/dev/null 2>&1 \
    || { err "内置 MySQL root 凭证同步校验失败"; return 1; }
  docker_managed_mysql_business_exec "${dbPwd}" "${database}" "${user}" -e 'SELECT 1' >/dev/null 2>&1 \
    || { err "内置 MySQL 业务凭证同步校验失败"; return 1; }
  ok "内置 MySQL 数据库、root 与业务账号已和配置文件保持一致"
}

# 确保 MySQL 就绪。外部模式只做连接及 5.7 版本校验，绝不拉起 aid-mysql。
ensure_mysql_ready() {
  local mode AID_DEPENDENCY_INSTALL_MODE previousRootPwd="" freshData="0"; mode="${1:-$(detect_mode)}"
  if [[ "${mode}" != "docker" ]]; then return 0; fi
  AID_DEPENDENCY_INSTALL_MODE="$(dependency_install_mode docker)"
  ensure_docker_image "mysql:5.7" "MySQL5.7"
  if docker_profile_enabled mysql; then
    log "启动并检查内置 MySQL 5.7..."
    previousRootPwd="$(docker_container_env_value aid-mysql MYSQL_ROOT_PASSWORD || true)"
    # MySQL 官方镜像首次初始化会在临时实例中导入 /docker-entrypoint-initdb.d。
    # 空数据目录先等待 Compose 健康检查完成，再执行账号同步，避免与初始化 SQL 并发。
    [[ -d "${DATA_ROOT}/mysql-data/mysql" ]] || freshData="1"
    if ! compose_cmd up -d mysql; then
      docker_container_diagnostics aid-mysql "内置 MySQL 5.7"
      return 1
    fi
    if [[ "${freshData}" == "1" ]]; then
      wait_docker_managed_mysql_bootstrap_complete || return 1
      wait_docker_container_healthy aid-mysql "内置 MySQL 5.7" 120 || return 1
      reconcile_docker_managed_mysql_credentials "${previousRootPwd}" || return 1
    else
      # 已有数据目录可能保留旧 root 密码，需先通过 socket 用旧凭证迁移，再检查健康。
      reconcile_docker_managed_mysql_credentials "${previousRootPwd}" || return 1
      wait_docker_container_healthy aid-mysql "内置 MySQL 5.7" 120 || return 1
    fi
  else
    local version
    log "检查外部 MySQL: $(env_get DB_HOST):$(env_get DB_PORT 3306)"
    version="$(docker_mysql_tool mysql --batch --skip-column-names --execute 'SELECT VERSION()' 2>/dev/null | head -n 1)" \
      || { err "外部 MySQL 连接失败，请检查地址、账号、密码及网络白名单"; return 1; }
    [[ "${version}" == 5.7.* ]] \
      || { err "外部数据库版本必须是 MySQL 5.7（当前: ${version:-未知}）"; return 1; }
    ok "外部 MySQL 5.7 连接正常"
  fi
  return 0
}

docker_container_diagnostics() { # docker_container_diagnostics <容器名> <组件名>
  local container="$1" label="$2"
  err "${label} 未就绪，容器诊断信息如下："
  docker ps -a --filter "name=^/${container}$" \
    --format '  {{.Names}}  {{.Status}}  {{.Image}}' 2>/dev/null || true
  docker inspect "${container}" --format \
    '  status={{.State.Status}} restartCount={{.RestartCount}} exitCode={{.State.ExitCode}} oomKilled={{.State.OOMKilled}} error={{.State.Error}}' \
    2>/dev/null || true
  docker inspect "${container}" --format \
    '{{if .State.Health}}{{range .State.Health.Log}}{{println "  health" .Start "exit=" .ExitCode .Output}}{{end}}{{end}}' \
    2>/dev/null | tail -n 8 || true
  echo "  最近日志（最多120行）: docker logs --tail 120 ${container}" >&2
  docker logs --tail 120 "${container}" 2>&1 | tail -n 120 >&2 || true
}

wait_docker_container_healthy() { # wait_docker_container_healthy <容器名> <组件名> [超时秒]
  local container="$1" label="$2" timeoutSeconds="${3:-120}"
  local deadline status="" initialRestarts currentRestarts
  deadline=$(( $(date +%s) + timeoutSeconds ))
  initialRestarts="$(docker inspect --format '{{.RestartCount}}' "${container}" 2>/dev/null || echo 0)"
  [[ "${initialRestarts}" =~ ^[0-9]+$ ]] || initialRestarts=0
  while [[ $(date +%s) -lt ${deadline} ]]; do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container}" 2>/dev/null || true)"
    [[ "${status}" == "healthy" ]] && { ok "${label} 已就绪"; return 0; }
    if [[ "${status}" == "unhealthy" || "${status}" == "exited" || "${status}" == "dead" ]]; then
      docker_container_diagnostics "${container}" "${label}"
      return 1
    fi
    currentRestarts="$(docker inspect --format '{{.RestartCount}}' "${container}" 2>/dev/null || echo 0)"
    if [[ "${currentRestarts}" =~ ^[0-9]+$ ]] && (( currentRestarts >= initialRestarts + 2 )); then
      docker_container_diagnostics "${container}" "${label}（检测到循环重启）"
      return 1
    fi
    sleep 3
  done
  docker_container_diagnostics "${container}" "${label}（等待 ${timeoutSeconds}s 超时）"
  return 1
}

ensure_redis_ready() { # Docker：内置 Redis 启动探活；外部 Redis 校验认证与版本
  local redisHost redisPort redisUser redisPwd redisDb redisInfo redisVersion redisMajor
  local -a redisArgs=()
  redisHost="$(env_get REDIS_HOST redis)"; redisPort="$(env_get REDIS_PORT 6379)"
  redisUser="$(env_get REDIS_USERNAME '')"; redisPwd="$(env_get REDIS_PASSWORD '')"
  redisDb="$(env_get REDIS_DATABASE 0)"
  if docker_profile_enabled redis; then
    log "启动并检查内置 Redis..."
    if ! compose_cmd up -d redis; then
      docker_container_diagnostics aid-redis "内置 Redis"
      return 1
    fi
    wait_docker_container_healthy aid-redis "内置 Redis" 120 || return 1
    return 0
  fi

  log "从 Docker 容器网络检查外部 Redis: ${redisHost}:${redisPort}"
  ensure_docker_image "redis:7-alpine" "外部Redis校验客户端"
  redisArgs=(redis-cli --no-auth-warning -h "${redisHost}" -p "${redisPort}" -n "${redisDb}")
  [[ -z "${redisUser}" ]] || redisArgs+=(--user "${redisUser}")
  redisInfo="$(REDISCLI_AUTH="${redisPwd}" docker run --rm --pull=never --network bridge \
    --add-host host.docker.internal:host-gateway -e REDISCLI_AUTH redis:7-alpine \
    "${redisArgs[@]}" INFO server 2>/dev/null || true)"
  redisVersion="$(printf '%s\n' "${redisInfo}" | awk -F: '$1=="redis_version" {gsub("\r", "", $2); print $2; exit}')"
  redisMajor="${redisVersion%%.*}"
  if [[ ! "${redisMajor}" =~ ^[0-9]+$ || "${redisMajor}" -lt 6 ]]; then
    err "外部 Redis 认证失败、网络不可达或版本低于6（检测结果: ${redisVersion:-不可读取}）"
    echo "  请修改配置文件: ${ENV_FILE}" >&2
    return 1
  fi
  ok "外部 Redis ${redisVersion} 认证、网络与版本校验通过"
}

docker_managed_mysql_table_count() { # docker_managed_mysql_table_count <SQL>
  local query="$1" database user password
  database="$(env_get DB_NAME aid)"
  user="$(env_get DB_USERNAME aid)"
  password="$(env_get DB_PASSWORD '')"
  docker_managed_mysql_business_exec "${password}" "${database}" "${user}" \
    --batch --skip-column-names --execute "${query}" 2>/dev/null | tail -n 1
}

initialize_docker_managed_mysql_schema() {
  local dbName initSql rootPwd
  dbName="$(env_get DB_NAME aid)"
  rootPwd="$(env_get MYSQL_ROOT_PASSWORD '')"
  initSql="${REPO_DIR}/sql/aid-init.sql"
  [[ -f "${initSql}" ]] || { err "未找到数据库基线脚本: ${initSql}"; return 1; }
  [[ -n "${rootPwd}" ]] || { err "内置 MySQL root 凭证不能为空"; return 1; }

  # Compose 首次初始化异常中断时，数据目录会阻止官方 entrypoint 再次自动导入。
  # 仅在目标库确认为空时补导入基线；任何已有表的库均拒绝覆盖，避免损坏业务数据。
  log "AID 数据库为空，导入基线 aid-init.sql..."
  docker_managed_mysql_root_exec "${rootPwd}" --default-character-set=utf8mb4 "${dbName}" < "${initSql}" \
    || { err "AID 数据库基线导入失败"; return 1; }
  return 0
}

wait_docker_database_schema_ready() {
  local dbName deadline tableCount="" coreTableCount="" importAttempted="0"
  dbName="$(env_get DB_NAME aid)"
  deadline=$(( $(date +%s) + 300 ))
  log "等待 AID 数据库初始化与核心表校验..."
  while [[ $(date +%s) -lt ${deadline} ]]; do
    if docker_profile_enabled mysql; then
      # 使用已由 reconcile 校验过的业务账号检查业务库，避免 root@127.0.0.1
      # 与 root@localhost 的账户匹配差异造成错误的“初始化超时”。
      tableCount="$(docker_managed_mysql_table_count \
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${dbName}'" || true)"
      coreTableCount="$(docker_managed_mysql_table_count \
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${dbName}' AND table_name IN ('aid_config','sys_user')" || true)"
    else
      tableCount="$(docker_mysql_tool mysql --batch --skip-column-names \
        --execute "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${dbName}'" \
        2>/dev/null | tail -n 1 || true)"
      coreTableCount="$(docker_mysql_tool mysql --batch --skip-column-names \
        --execute "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${dbName}' AND table_name IN ('aid_config','sys_user')" \
        2>/dev/null | tail -n 1 || true)"
    fi
    [[ "${coreTableCount}" == "2" ]] \
      && { ok "AID 数据库初始化完成，核心表校验通过"; return 0; }

    if [[ "${tableCount}" =~ ^[0-9]+$ ]]; then
      if docker_profile_enabled mysql && [[ "${tableCount}" == "0" && "${importAttempted}" == "0" ]]; then
        initialize_docker_managed_mysql_schema || return 1
        importAttempted="1"
        continue
      fi
      if [[ "${tableCount}" == "0" && "${importAttempted}" == "1" ]]; then
        err "AID 数据库基线导入后仍缺少核心表"
      elif docker_profile_enabled mysql; then
        err "AID 数据库已有 ${tableCount} 张表但核心表不完整，拒绝重复导入"
      else
        err "外部数据库已有 ${tableCount} 张表但核心表不完整"
      fi
      docker_profile_enabled mysql && docker_container_diagnostics aid-mysql "内置 MySQL 5.7"
      return 1
    fi
    sleep 3
  done
  err "AID 数据库在300秒内未完成初始化，核心表数量: ${coreTableCount:-不可读取}"
  if docker_profile_enabled mysql; then
    docker_container_diagnostics aid-mysql "内置 MySQL 5.7"
  else
    echo "  请检查外部数据库 ${dbName} 的初始化结果与账号权限" >&2
  fi
  return 1
}

prepare_docker_runtime_dependencies() {
  section "启动前置服务并执行健康检查"
  ensure_mysql_ready docker || return 1
  wait_docker_database_schema_ready || return 1
  ensure_redis_ready || return 1
  check_external_rocketmq_connectivity docker
  if docker_profile_enabled mq; then
    log "启动并检查内置 RocketMQ NameServer 与 Broker..."
    if ! compose_cmd up -d rocketmq-nameserver rocketmq-broker; then
      docker_container_diagnostics aid-rocketmq-nameserver "RocketMQ NameServer"
      docker_container_diagnostics aid-rocketmq-broker "RocketMQ Broker"
      return 1
    fi
    wait_docker_container_healthy aid-rocketmq-nameserver "RocketMQ NameServer" 120 || return 1
    wait_docker_container_healthy aid-rocketmq-broker "RocketMQ Broker" 180 || return 1
  fi
  ok "MySQL、Redis 与可选 RocketMQ 前置检查全部通过"
}

initialize_external_mysql() {
  docker_profile_enabled mysql && return 0
  ensure_mysql_ready || return 1
  local dbName schemaCount tableCount coreTableCount sqlFile existingInternal="false"
  docker ps -a --format '{{.Names}}' 2>/dev/null | grep -qx 'aid-mysql' && existingInternal="true"
  dbName="$(env_get DB_NAME aid)"
  schemaCount="$(docker_mysql_tool mysql --batch --skip-column-names \
    --execute "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${dbName}'" 2>/dev/null | tail -n 1)" \
    || return 1
  if [[ "${schemaCount}" == "0" ]]; then
    log "外部 MySQL 中创建数据库 ${dbName}..."
    docker_mysql_tool mysql --execute \
      "CREATE DATABASE \`${dbName}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci" \
      || { err "外部 MySQL 账号无建库权限，请预先创建 ${dbName} 并授权"; return 1; }
  fi
  tableCount="$(docker_mysql_tool mysql --batch --skip-column-names \
    --execute "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${dbName}'" 2>/dev/null | tail -n 1)" \
    || return 1
  if [[ "${tableCount}" =~ ^[0-9]+$ && "${tableCount}" -gt 0 ]]; then
    coreTableCount="$(docker_mysql_tool mysql --batch --skip-column-names \
      --execute "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${dbName}' AND table_name IN ('aid_config','sys_user')" 2>/dev/null | tail -n 1)" \
      || return 1
    [[ "${coreTableCount}" == "2" ]] \
      || { err "外部数据库已有表但缺少 AID 核心表，请检查目标库或先完成数据库迁移"; return 1; }
    ok "外部数据库 ${dbName} 已有 ${tableCount} 张表，跳过首次初始化"
    return 0
  fi
  if [[ "${existingInternal}" == "true" ]]; then
    err "检测到现有内置 MySQL，但外部数据库为空；禁止自动切库以避免业务数据丢失"
    err "请先把 ${DATA_ROOT}/mysql-data 中的业务库迁移到外部 MySQL，再重新应用配置"
    return 1
  fi
  log "外部数据库为空，按文件名顺序导入初始化 SQL..."
  for sqlFile in "${REPO_DIR}/sql/"*.sql; do
    [[ -f "${sqlFile}" ]] || continue
    log "导入 $(basename "${sqlFile}")"
    docker_mysql_tool mysql --default-character-set=utf8mb4 "${dbName}" < "${sqlFile}" \
      || { err "初始化 SQL 执行失败: $(basename "${sqlFile}")"; return 1; }
  done
  coreTableCount="$(docker_mysql_tool mysql --batch --skip-column-names \
    --execute "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${dbName}' AND table_name IN ('aid_config','sys_user')" 2>/dev/null | tail -n 1)" \
    || return 1
  [[ "${coreTableCount}" == "2" ]] \
    || { err "外部 MySQL 初始化后核心表校验失败"; return 1; }
  ok "外部 MySQL 初始化完成"
}

disable_internal_mysql_for_external() {
  docker_profile_enabled mysql && return 0
  initialize_external_mysql || return 1
  if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -qx 'aid-mysql'; then
    docker rm -f aid-mysql >/dev/null 2>&1 || return 1
    ok "已停用内置 MySQL 容器（数据目录保留，可用于人工回退）"
  fi
}

# Compose Profile 关闭后不会自动删除之前由该 Profile 启动的容器。显式移除这些
# 可选服务，保证切换到外部组件后本机不再继续运行或占用端口；绑定数据目录保留。
disable_unused_docker_services() {
  local container
  if ! docker_profile_enabled redis; then
    docker rm -f aid-redis >/dev/null 2>&1 || true
  fi
  if ! docker_profile_enabled mq; then
    for container in aid-rocketmq-broker aid-rocketmq-nameserver; do
      docker rm -f "${container}" >/dev/null 2>&1 || true
    done
  fi
  if ! docker_profile_enabled https; then
    docker rm -f aid-nginx-https >/dev/null 2>&1 || true
  fi
}

# 数据库全量备份到指定文件（两种部署模式通用）
# docker 模式经容器内客户端执行，宿主机无需安装 mysql；密码经 MYSQL_PWD 传递，
# 不拼进命令行（避免特殊字符断参与 ps 泄露）
backup_database() { # backup_database <输出文件.sql.gz>
  local outFile="$1" mode
  mode="$(detect_mode)"
  if ! command -v gzip >/dev/null 2>&1; then err "缺少 gzip"; return 1; fi
  if [[ "${mode}" == "docker" ]]; then
    docker_mysql_tool mysqldump --single-transaction --routines --triggers "$(env_get DB_NAME aid)" \
      | gzip > "${outFile}" || return 1
  else
    command -v mysqldump >/dev/null 2>&1 || { err "缺少 mysqldump（数据库备份需要）"; return 1; }
    MYSQL_PWD="$(conf_get DB_PASSWORD)" mysqldump --protocol=TCP --host "$(conf_get DB_HOST 127.0.0.1)" --port "$(conf_get DB_PORT 3306)" \
      --user "$(conf_get DB_USERNAME aid)" --single-transaction --routines --triggers "$(conf_get DB_NAME aid)" \
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
    gunzip < "${dumpFile}" | docker_mysql_tool mysql --default-character-set=utf8mb4 "$(env_get DB_NAME aid)" || return 1
  else
    gunzip < "${dumpFile}" | MYSQL_PWD="$(conf_get DB_PASSWORD)" mysql --protocol=TCP --host "$(conf_get DB_HOST 127.0.0.1)" --port "$(conf_get DB_PORT 3306)" \
      --user "$(conf_get DB_USERNAME aid)" --default-character-set=utf8mb4 "$(conf_get DB_NAME aid)" || return 1
  fi
  return 0
}

# 执行单个 SQL 文件（两种部署模式通用；docker 模式经容器内客户端执行）
run_sql_file() { # run_sql_file <sql文件>
  local sqlFile="$1" mode
  mode="$(detect_mode)"
  if [[ "${mode}" == "docker" ]]; then
    docker_mysql_tool mysql --default-character-set=utf8mb4 "$(env_get DB_NAME aid)" < "${sqlFile}" || return 1
  else
    MYSQL_PWD="$(conf_get DB_PASSWORD)" mysql --protocol=TCP --host "$(conf_get DB_HOST 127.0.0.1)" --port "$(conf_get DB_PORT 3306)" \
      --user "$(conf_get DB_USERNAME aid)" --default-character-set=utf8mb4 "$(conf_get DB_NAME aid)" < "${sqlFile}" || return 1
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
        if docker_profile_enabled mysql; then
          echo "排查: docker logs --tail 100 aid-server / docker logs --tail 100 aid-mysql"
        else
          echo "排查: docker logs --tail 100 aid-server，并检查外部 MySQL $(env_get DB_HOST):$(env_get DB_PORT 3306) 的网络与授权"
        fi
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

stop_failed_docker_service() { # stop_failed_docker_service <容器名> <组件名>
  local container="$1" label="$2"
  docker stop "${container}" >/dev/null 2>&1 || true
  risk "${label} 启动失败，已停止该容器的循环重启；MySQL/Redis 等前置服务保持运行，修复配置后重新执行安装或重启"
}

stop_unhealthy_docker_application_containers() {
  local container status health restarts stopped=0
  for container in aid-server aid-web aid-nginx aid-nginx-https aid-updater; do
    status="$(docker inspect --format '{{.State.Status}}' "${container}" 2>/dev/null || true)"
    [[ -n "${status}" ]] || continue
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "${container}" 2>/dev/null || true)"
    restarts="$(docker inspect --format '{{.RestartCount}}' "${container}" 2>/dev/null || echo 0)"
    [[ "${restarts}" =~ ^[0-9]+$ ]] || restarts=0
    if [[ "${status}" == "restarting" || "${health}" == "unhealthy" \
        || "${health}" == "starting" && "${restarts}" -gt 0 ]]; then
      docker stop "${container}" >/dev/null 2>&1 || true
      warn "已停止上次部署遗留的异常容器: ${container}（status=${status}, health=${health:-无}, restarts=${restarts}）"
      stopped=$((stopped + 1))
    fi
  done
  (( stopped == 0 )) || ok "异常业务容器已止损；数据服务不受影响"
}

start_docker_application_stack() {
  validate_https_runtime

  [[ -f "${DATA_ROOT}/app/web-dist/index.html" && -f "${DATA_ROOT}/app/web-dist/200.html" ]] \
    || { err "Web 静态入口不完整: ${DATA_ROOT}/app/web-dist/index.html 或 200.html 缺失"; return 1; }

  section "启动 AID 后端并执行健康检查"
  if ! compose_cmd up -d aid-server || ! compose_cmd restart aid-server; then
    docker_container_diagnostics aid-server "AID 后端"
    stop_failed_docker_service aid-server "AID 后端"
    return 1
  fi
  if ! wait_docker_container_healthy aid-server "AID 后端" "${HEALTH_WAIT_SECONDS}" \
      || ! wait_backend_healthy; then
    stop_failed_docker_service aid-server "AID 后端"
    return 1
  fi

  section "启动 Web 用户端与 Nginx 网关"
  if ! compose_cmd up -d aid-web || ! compose_cmd restart aid-web; then
    docker_container_diagnostics aid-web "Web 用户端"
    stop_failed_docker_service aid-web "Web 用户端"
    return 1
  fi
  if ! wait_docker_container_healthy aid-web "Web 用户端" 120; then
    stop_failed_docker_service aid-web "Web 用户端"
    return 1
  fi
  if ! compose_cmd up -d nginx || ! compose_cmd restart nginx; then
    docker_container_diagnostics aid-nginx "Nginx 网关"
    stop_failed_docker_service aid-nginx "Nginx 网关"
    return 1
  fi
  if ! wait_docker_container_healthy aid-nginx "Nginx 网关" 120; then
    stop_failed_docker_service aid-nginx "Nginx 网关"
    return 1
  fi
  if docker_profile_enabled https; then
    if ! compose_cmd up -d nginx-https || ! compose_cmd restart nginx-https; then
      docker_container_diagnostics aid-nginx-https "HTTPS 入口"
      stop_failed_docker_service aid-nginx-https "HTTPS 入口"
      return 1
    fi
    if ! wait_https_healthy; then
      docker_container_diagnostics aid-nginx-https "HTTPS 入口"
      stop_failed_docker_service aid-nginx-https "HTTPS 入口"
      return 1
    fi
  fi

  if [[ "${AID_SKIP_UPDATER_RESTART:-0}" != "1" ]]; then
    [[ -x "${DATA_ROOT}/app/updater/aid-updater" ]] \
      || { err "在线升级器二进制未就位，拒绝完成不可升级的部署"; return 1; }
    rm -f -- "${UPDATER_DATA_DIR}/health.json"
    section "启动在线升级器并执行健康检查"
    if ! compose_cmd up -d aid-updater || ! compose_cmd restart aid-updater; then
      docker_container_diagnostics aid-updater "在线升级器"
      stop_failed_docker_service aid-updater "在线升级器"
      return 1
    fi
    if ! wait_docker_container_healthy aid-updater "在线升级器" 120; then
      stop_failed_docker_service aid-updater "在线升级器"
      return 1
    fi
  fi
  ok "AID 后端、Web 静态站点、Nginx 与升级器已按顺序启动"
}

valid_ipv4() { # valid_ipv4 <IPv4>
  local value="$1" octet octets=()
  [[ "${value}" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1
  IFS='.' read -r -a octets <<< "${value}"
  for octet in "${octets[@]}"; do
    (( 10#${octet} >= 0 && 10#${octet} <= 255 )) || return 1
  done
}

private_ipv4() { # private_ipv4 <IPv4>
  local value="$1" second
  valid_ipv4 "${value}" || return 1
  case "${value}" in
    10.*|192.168.*) return 0 ;;
    172.*)
      second="${value#172.}"; second="${second%%.*}"
      (( 10#${second} >= 16 && 10#${second} <= 31 )) ;;
    *) return 1 ;;
  esac
}

public_ipv4() { # public_ipv4 <可路由公网IPv4>
  local value="$1" first second
  valid_ipv4 "${value}" || return 1
  private_ipv4 "${value}" && return 1
  first="${value%%.*}"
  case "${value}" in
    0.*|127.*|169.254.*|192.0.2.*|198.51.100.*|203.0.113.*) return 1 ;;
    100.*)
      second="${value#100.}"; second="${second%%.*}"
      (( 10#${second} < 64 || 10#${second} > 127 )) || return 1 ;;
  esac
  (( 10#${first} >= 1 && 10#${first} <= 223 ))
}

detect_private_ipv4() {
  local candidate="" value iface cidr
  candidate="${AID_PRIVATE_IP:-}"
  private_ipv4 "${candidate}" && { echo "${candidate}"; return 0; }
  if command -v ip >/dev/null 2>&1; then
    candidate="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')"
    private_ipv4 "${candidate}" && { echo "${candidate}"; return 0; }
    # 默认路由没有私网地址时再检查真实物理/云网卡，排除 Docker/容器网桥，
    # 避免把 172.17.0.1 之类仅供容器使用的地址误报成服务器内网入口。
    while read -r iface cidr; do
      case "${iface}" in lo|docker*|br-*|veth*|virbr*|cni*|flannel*) continue ;; esac
      candidate="${cidr%%/*}"
      private_ipv4 "${candidate}" && { echo "${candidate}"; return 0; }
    done < <(ip -4 -o addr show scope global 2>/dev/null | awk '{print $2, $4}')
    return 1
  fi
  if command -v hostname >/dev/null 2>&1; then
    for value in $(hostname -I 2>/dev/null || true); do
      private_ipv4 "${value}" && { echo "${value}"; return 0; }
    done
  fi
  return 1
}

detect_public_ipv4() {
  local candidate="" endpoint value
  candidate="${AID_PUBLIC_IP:-}"
  public_ipv4 "${candidate}" && { echo "${candidate}"; return 0; }
  if command -v curl >/dev/null 2>&1; then
    for endpoint in https://4.ipw.cn https://api-ipv4.ip.sb/ip https://api.ipify.org; do
      candidate="$(curl -4 -fsSL --connect-timeout 3 --max-time 5 "${endpoint}" 2>/dev/null \
        | tr -d '[:space:]' || true)"
      public_ipv4 "${candidate}" && { echo "${candidate}"; return 0; }
    done
  fi
  # 无 NAT 的公网服务器可直接从默认路由源地址得到公网 IP。
  if command -v ip >/dev/null 2>&1; then
    candidate="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')"
    public_ipv4 "${candidate}" && { echo "${candidate}"; return 0; }
  fi
  if command -v hostname >/dev/null 2>&1; then
    for value in $(hostname -I 2>/dev/null || true); do
      public_ipv4 "${value}" && { echo "${value}"; return 0; }
    done
  fi
  return 1
}

print_https_guidance() { # print_https_guidance <模式> <配置文件> <公网IP或空> <管理端路径>
  local mode="$1" configFile="$2" publicIp="$3" adminPath="$4" profiles httpsProfiles
  echo ""
  echo "域名与 HTTPS："
  if [[ "${mode}" == "docker" ]] && docker_profile_enabled https; then
    echo "  DNS A记录 : $(env_get HTTPS_PUBLIC_DOMAIN)、$(env_get HTTPS_ADMIN_DOMAIN) -> ${publicIp:-服务器公网IP}"
    echo "  用户域名 : https://$(env_get HTTPS_PUBLIC_DOMAIN):$(env_get HTTPS_PORT 443)/"
    echo "  管理域名 : https://$(env_get HTTPS_ADMIN_DOMAIN):$(env_get HTTPS_PORT 443)${adminPath}"
    echo "  证书文件 : $(env_get HTTPS_CERT_PATH "${DATA_ROOT}/config/ssl/fullchain.pem")"
    echo "  私钥文件 : $(env_get HTTPS_KEY_PATH "${DATA_ROOT}/config/ssl/privkey.pem")"
  elif [[ "${mode}" == "manual" && "$(conf_get HTTPS_ENABLED false)" == "true" ]]; then
    echo "  DNS A记录 : $(conf_get HTTPS_PUBLIC_DOMAIN)、$(conf_get HTTPS_ADMIN_DOMAIN) -> ${publicIp:-服务器公网IP}"
    echo "  用户域名 : https://$(conf_get HTTPS_PUBLIC_DOMAIN):$(conf_get HTTPS_PORT 443)/"
    echo "  管理域名 : https://$(conf_get HTTPS_ADMIN_DOMAIN):$(conf_get HTTPS_PORT 443)${adminPath}"
    echo "  证书文件 : $(conf_get HTTPS_CERT_PATH "${DATA_ROOT}/config/ssl/fullchain.pem")"
    echo "  私钥文件 : $(conf_get HTTPS_KEY_PATH "${DATA_ROOT}/config/ssl/privkey.pem")"
  else
    echo "  1. 在域名服务商添加两个 A 记录：用户域名、管理域名 -> ${publicIp:-服务器公网IP}"
    echo "  2. 申请覆盖两个域名的 SAN/通配符证书，将 fullchain.pem、privkey.pem 放入 ${DATA_ROOT}/config/ssl/"
    echo "  3. 编辑配置文件: ${configFile}"
    if [[ "${mode}" == "docker" ]]; then
      profiles="$(env_get COMPOSE_PROFILES mysql,redis | tr -d '[:space:]')"
      httpsProfiles="${profiles}"
      [[ ",${httpsProfiles}," == *",https,"* ]] || httpsProfiles="${httpsProfiles:+${httpsProfiles},}https"
      echo "     COMPOSE_PROFILES=${httpsProfiles}"
    else
      echo "     HTTPS_ENABLED=true"
    fi
    echo "     HTTPS_PUBLIC_DOMAIN=你的用户域名"
    echo "     HTTPS_ADMIN_DOMAIN=你的管理域名"
    echo "     HTTPS_CERT_PATH=${DATA_ROOT}/config/ssl/fullchain.pem"
    echo "     HTTPS_KEY_PATH=${DATA_ROOT}/config/ssl/privkey.pem"
    echo "  4. 放行 TCP 443 后执行: sudo aid restart"
  fi
  echo "  说明：DNS 解析不会关闭 IP/HTTP 访问；当前 80 和管理端口仍可使用。HTTPS 用 IP 访问会因证书域名不匹配而报警，应使用域名。"
}

print_mysql_access_guidance() { # print_mysql_access_guidance <模式> <公网IP或空> <配置文件>
  local mode="$1" publicIp="$2" configFile="$3" dbHost dbPort dbName dbUser mysqlPort dbPwd rootPwd
  if [[ "${mode}" == "docker" ]]; then
    dbHost="$(env_get DB_HOST mysql)"; dbPort="$(env_get DB_PORT 3306)"
    dbName="$(env_get DB_NAME aid)"; dbUser="$(env_get DB_USERNAME aid)"
    dbPwd="$(env_get DB_PASSWORD '')"; rootPwd="$(env_get MYSQL_ROOT_PASSWORD '')"
    mysqlPort="$(env_get MYSQL_PORT 3306)"
    if docker_profile_enabled mysql; then
      echo ""
      echo "MySQL 数据库信息（敏感信息，仅限服务器管理员查看）："
      echo "  部署类型       : Docker 内置 MySQL 5.7"
      echo "  SSH主机/端口 : ${publicIp:-服务器公网IP}:22（使用服务器运维账号）"
      echo "  MySQL主机/端口: 127.0.0.1:${mysqlPort}"
      echo "  数据库/用户名 : ${dbName} / ${dbUser}"
      echo "  业务账号密码   : ${dbPwd}"
      echo "  root账号密码   : ${rootPwd}"
      echo "  配置文件       : ${configFile}"
      echo "  Navicat建议    : 使用 SSH 隧道，不要向公网开放 3306"
      echo "  安全提醒       : 请勿截图、转发或把以上密码写入公开日志"
      return 0
    fi
  else
    dbHost="$(conf_get DB_HOST 127.0.0.1)"; dbPort="$(conf_get DB_PORT 3306)"
    dbName="$(conf_get DB_NAME aid)"; dbUser="$(conf_get DB_USERNAME aid)"
    dbPwd="$(conf_get DB_PASSWORD '')"; rootPwd="$(conf_get MYSQL_ROOT_PASSWORD '')"
    if [[ "${dbHost}" == "127.0.0.1" || "${dbHost}" == "localhost" ]]; then
      echo ""
      echo "MySQL 数据库信息（敏感信息，仅限服务器管理员查看）："
      echo "  部署类型       : 本机 MySQL 5.7"
      echo "  SSH主机/端口 : ${publicIp:-服务器公网IP}:22（使用服务器运维账号）"
      echo "  MySQL主机/端口: 127.0.0.1:${dbPort}"
      echo "  数据库/用户名 : ${dbName} / ${dbUser}"
      echo "  业务账号密码   : ${dbPwd}"
      [[ -z "${rootPwd}" ]] || echo "  root账号密码   : ${rootPwd}"
      echo "  配置文件       : ${configFile}"
      echo "  Navicat建议    : 使用 SSH 隧道，不要向公网开放 3306"
      echo "  安全提醒       : 请勿截图、转发或把以上密码写入公开日志"
      return 0
    fi
  fi
  echo ""
  echo "外部 MySQL 数据库信息（敏感信息，仅限服务器管理员查看）："
  echo "  AID 当前连接   : ${dbHost}:${dbPort}/${dbName}"
  echo "  业务账号       : ${dbUser}"
  echo "  业务账号密码   : ${dbPwd}"
  echo "  配置文件       : ${configFile}"
  echo "  请使用数据库服务商提供的可访问地址；若仅内网开放，应通过数据库所在网络的 SSH/云数据库代理连接。"
  echo "  安全提醒       : 请勿截图、转发或把以上密码写入公开日志"
}

print_access_info() { # print_access_info [strict]
  local strict="${1:-no}" mode configFile adminPort adminPath httpPort publicIp privateIp entryReadOk=1
  mode="$(detect_mode)"
  if [[ "${mode}" == "docker" ]]; then configFile="${ENV_FILE}"; else configFile="${CONF}"; fi
  adminPort="$(setting_get ADMIN_PORT 8089)"
  httpPort="$(setting_get HTTP_PORT 80)"
  publicIp="$(detect_public_ipv4 || true)"
  privateIp="$(detect_private_ipv4 || true)"
  if ! read_admin_entry_settings; then
    entryReadOk=0
    [[ "${strict}" != "strict" ]] \
      || die "无法从数据库读取管理端访问码；请确认 MySQL 已运行，或先执行 sudo aid restart"
    warn "数据库暂不可读，无法确认管理端实际访问码；下面的管理端地址仅作排查提示"
  fi
  if [[ "${entryReadOk}" == "0" ]]; then
    adminPath="/<访问码读取失败>"
  elif [[ "${ADMIN_ENTRY_ENABLED_VALUE}" =~ ^(true|TRUE|Y|1)$ && -n "${ADMIN_ENTRY_CODE_VALUE}" ]]; then
    adminPath="/${ADMIN_ENTRY_CODE_VALUE}"
  else
    adminPath="/login"
  fi
  echo ""
  echo -e "${C_GREEN}=================== 操作完成 ===================${C_RESET}"
  echo "访问地址:"
  if [[ -n "${publicIp}" ]]; then
    echo "  用户端外网访问入口: http://${publicIp}:${httpPort}/"
  else
    echo "  用户端外网访问入口: 未自动识别公网 IPv4，请在云服务器控制台查看后使用 http://公网IP:${httpPort}/"
  fi
  if [[ -n "${privateIp}" ]]; then
    echo "  用户端内网访问入口: http://${privateIp}:${httpPort}/"
  else
    echo "  用户端内网访问入口: 未检测到真实内网 IPv4；无私有网络的单网卡服务器可忽略"
  fi
  if [[ -n "${publicIp}" ]]; then
    echo "  管理端外网访问入口: http://${publicIp}:${adminPort}${adminPath}"
  else
    echo "  管理端外网访问入口: 未自动识别公网 IPv4，请使用 http://公网IP:${adminPort}${adminPath}"
  fi
  if [[ -n "${privateIp}" ]]; then
    echo "  管理端内网访问入口: http://${privateIp}:${adminPort}${adminPath}"
  else
    echo "  管理端内网访问入口: 未检测到真实内网 IPv4；无私有网络的单网卡服务器可忽略"
  fi
  echo ""
  echo "数据库初始化管理员："
  echo "  初始账号: admin"
  echo "  初始密码: admin123"
  echo "  说明：以上是 sql/aid-init.sql 的数据库初始化默认值；若已修改，请以数据库当前账号为准。"
  echo "        密码以不可逆摘要保存，无法通过本命令反查；本命令不会重置账号、密码或数据库。"
  print_https_guidance "${mode}" "${configFile}" "${publicIp}" "${adminPath}"
  print_mysql_access_guidance "${mode}" "${publicIp}" "${configFile}"
  echo "AID FFmpeg 运行时："
  print_ffmpeg_runtime_paths
  echo "AID 中文字体："
  echo "  后台推荐 中文字体路径: ${AID_CJK_FONT_PATH}"
  echo "数据目录: ${DATA_ROOT}（程序/上传/日志/数据/备份全部在此）"
  echo "配置文件: ${configFile}（菜单「修改配置」可调整）"
  [[ -f "${MANAGED_SCRIPT}" ]] && echo "管理命令: sudo aid 或 sudo bash ${MANAGED_SCRIPT}"
  return 0
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
  goOn="$(ask_yes_no '确认继续？' 'n')"
  [[ "${goOn}" == "y" ]] || { log "已取消"; return 1; }
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
  # 配置是部署的第一道闸门：完成生成、编辑、校验和人工确认后才检查环境或拉取源码。
  ensure_env_file
  confirm_initial_configuration docker
  export AID_DEPENDENCY_INSTALL_MODE="$(dependency_install_mode docker)"
  set_source_build_mode docker
  require_docker_runtime
  if [[ "$(uname -m)" == "aarch64" ]]; then
    die "Docker 一键部署固定使用 MySQL 5.7，官方镜像不支持 ARM64；请改用 x86_64 服务器"
  fi
  prepare_install_package "${1:-}"
  package="${RESOLVED_PACKAGE_PATH}"
  prepare_docker_runtime_images
  check_external_rocketmq_connectivity docker
  bootstrap_installer_if_needed "${package}" install-docker

  [[ "${existingMode}" == "none" ]] && confirm_first_install docker
  # 外部 MySQL 必须在启动业务容器前完成连通性、版本和库结构校验；校验成功后
  # 才停用可能存在的内置容器，且始终保留 mysql-data 目录。
  disable_internal_mysql_for_external \
    || die "外部 MySQL 未准备完成，部署已中止且不会启动内置 MySQL"
  # 硬件校验基线按 .env 实际配置评估：profiles 含 mq 才计入内置 MQ 内存
  local mqPlan="no"
  docker_profile_enabled mq && mqPlan="yes"
  check_hardware docker "${mqPlan}"

  # 所有前置服务必须在主程序启动前独立达到健康状态。
  stop_unhealthy_docker_application_containers
  if docker_profile_enabled mq; then
    mkdir -p "${DATA_ROOT}/rocketmq/broker-data" "${DATA_ROOT}/rocketmq/broker-logs" "${DATA_ROOT}/rocketmq/namesrv-logs"
    chown -R 3000:3000 "${DATA_ROOT}/rocketmq" 2>/dev/null || true
  fi
  prepare_docker_runtime_dependencies \
    || die "前置服务检查失败，AID 主程序尚未启动；请按上方组件日志处理后重试"
  ensure_admin_entry_code docker
  disable_unused_docker_services

  place_artifacts "${package}"
  # 升级器配置先于容器编排就位，aid-updater 容器首次拉起即可正常运行
  if [[ -f "${DATA_ROOT}/app/updater/aid-updater" ]]; then
    write_updater_config docker
  fi
  start_docker_application_stack \
    || die "AID 服务分阶段启动失败；失败容器已停止循环重启，前置数据服务保持运行"
  targetVersion="${RESOLVED_VERSION:-$(version_from_package "${package}")}"
  targetChannel="${REQUESTED_RELEASE_CHANNEL:-auto}"
  # 只有健康检查成功后才写入“已部署”状态，避免失败的首次安装被当成升级。
  state_set DEPLOY_MODE "docker"
  state_set DATA_ROOT "${DATA_ROOT}"
  state_set CURRENT_VERSION "${targetVersion}"
  state_set RELEASE_CHANNEL "${targetChannel}"
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
remove_legacy_manual_web_unit() {
  local unitFile="${AID_SYSTEMD_UNIT_DIR}/aid-web.service"
  [[ -f "${unitFile}" && ! -L "${unitFile}" ]] || return 0
  if grep -Fxq '# AID_MANAGED_UNIT=1' "${unitFile}" 2>/dev/null \
      && grep -Fxq "# AID_DATA_ROOT=${DATA_ROOT}" "${unitFile}" 2>/dev/null; then
    systemctl disable --now aid-web >/dev/null 2>&1 || true
    rm -f -- "${unitFile}" || die "移除旧版 Web SSR 服务失败: ${unitFile}"
    ok "旧版 aid-web.service 已停用，用户端改由 Nginx 托管静态文件"
  else
    warn "检测到非当前 AID 管理的 aid-web.service，已保留: ${unitFile}"
  fi
}

write_systemd_units() {
  local javaBin aidUnit
  [[ -x "${JDK_HOME}/bin/java" ]] \
    && "${JDK_HOME}/bin/java" -version 2>&1 | head -n 1 | grep -Fq "${MANUAL_JDK_VERSION}" \
    || prepare_manual_jdk
  javaBin="${JDK_HOME}/bin/java"
  configure_ffmpeg_runtime_paths
  mkdir -p "${AID_SYSTEMD_UNIT_DIR}"
  aidUnit="${AID_SYSTEMD_UNIT_DIR}/aid.service"
  cat > "${aidUnit}" <<EOF
# AID_MANAGED_UNIT=1
# AID_DATA_ROOT=${DATA_ROOT}
[Unit]
Description=AID Server
After=network-online.target

[Service]
Type=simple
WorkingDirectory=${DATA_ROOT}/app
EnvironmentFile=${CONF}
Environment=AID_PROFILE=${DATA_ROOT}/uploadPath
Environment=LOG_PATH=${DATA_ROOT}/logs
Environment=LANG=C.UTF-8
Environment=LC_ALL=C.UTF-8
Environment=SERVER_PORT=$(conf_get BACKEND_PORT 8080)
Environment=JAVA_HOME=${JDK_HOME}
Environment=AID_FFMPEG_PATH=${FFMPEG_RUNTIME_FFMPEG}
Environment=AID_FFPROBE_PATH=${FFMPEG_RUNTIME_FFPROBE}
Environment=PATH=${JDK_HOME}/bin:${FFMPEG_RUNTIME_ROOT}/current:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin
ExecStart=${javaBin} $(conf_get JAVA_OPTS '-Xms1g -Xmx2g') -jar ${DATA_ROOT}/app/aid-admin.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
  chmod 600 "${aidUnit}"

  remove_legacy_manual_web_unit
  systemctl daemon-reload
}

write_nginx_site() {
  local httpPort adminPort backendPort content httpsPort httpsDomain httpsAdminDomain certPath keyPath backupPath siteFile
  httpPort="$(conf_get HTTP_PORT 80)"
  adminPort="$(conf_get ADMIN_PORT 8089)"
  backendPort="$(conf_get BACKEND_PORT 8080)"
  content="# AID 站点：${httpPort}=C端用户端，${adminPort}=后台管理端（根路径托管）
# AID_MANAGED_NGINX=1
# AID_DATA_ROOT=${DATA_ROOT}
server {
    listen ${httpPort};
    server_name _;
    client_max_body_size 1024m;
    root ${DATA_ROOT}/app/web-dist;
    index index.html;
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
        try_files \$uri \$uri/ /200.html;
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
  if [[ "$(conf_get HTTPS_ENABLED false)" == "true" ]]; then
    httpsPort="$(conf_get HTTPS_PORT 443)"
    httpsDomain="$(conf_get HTTPS_PUBLIC_DOMAIN)"
    httpsAdminDomain="$(conf_get HTTPS_ADMIN_DOMAIN)"
    certPath="$(conf_get HTTPS_CERT_PATH "${DATA_ROOT}/config/ssl/fullchain.pem")"
    keyPath="$(conf_get HTTPS_KEY_PATH "${DATA_ROOT}/config/ssl/privkey.pem")"
    content="${content}

ssl_protocols TLSv1.2 TLSv1.3;
ssl_session_cache shared:AIDSSL:10m;
ssl_session_timeout 1d;
ssl_session_tickets off;

server {
    listen ${httpsPort} ssl http2;
    server_name ${httpsDomain};
    ssl_certificate ${certPath};
    ssl_certificate_key ${keyPath};
    client_max_body_size 1024m;
    root ${DATA_ROOT}/app/web-dist;
    index index.html;
    location = /healthz { access_log off; return 200 \"ok\"; }
    location /aid/ {
        proxy_pass http://127.0.0.1:${backendPort}/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 300s;
        proxy_buffering off;
    }
    location /profile/ {
        proxy_pass http://127.0.0.1:${backendPort}/profile/;
        proxy_set_header X-Forwarded-Proto https;
    }
    location / {
        try_files \$uri \$uri/ /200.html;
    }
}

server {
    listen ${httpsPort} ssl http2;
    server_name ${httpsAdminDomain};
    ssl_certificate ${certPath};
    ssl_certificate_key ${keyPath};
    client_max_body_size 1024m;
    root ${DATA_ROOT}/app/admin-dist;
    index index.html;
    location = /healthz { access_log off; return 200 \"ok\"; }
    location / { try_files \$uri \$uri/ /index.html; }
    location /prod-api/ {
        proxy_pass http://127.0.0.1:${backendPort}/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 300s;
        proxy_buffering off;
    }
    location /profile/ {
        proxy_pass http://127.0.0.1:${backendPort}/profile/;
        proxy_set_header X-Forwarded-Proto https;
    }
}"
  fi
  if [[ -n "${NGINX_BIN}" && -x "${NGINX_BIN}" && -n "${NGINX_SITE_DIR}" ]]; then
    mkdir -p "${NGINX_SITE_DIR}"
    siteFile="${NGINX_SITE_DIR}/aid.conf"
    backupPath=""
    if [[ -f "${siteFile}" ]] && ! aid_nginx_site_belongs_to_current_install "${siteFile}"; then
      backupPath="${siteFile}.aid-before-install.$(date +%s)"
      cp -p -- "${siteFile}" "${backupPath}" || die "备份已有 Nginx 站点失败: ${siteFile}"
      write_aid_nginx_backup_state "${siteFile}" "${backupPath}"
    fi
    echo "${content}" > "${siteFile}"
    if ! "${NGINX_BIN}" -t >/dev/null 2>&1; then
      if [[ -n "${backupPath}" ]]; then
        cp -- "${backupPath}" "${siteFile}" || die "恢复原 Nginx 配置失败: ${siteFile}"
        rm -f -- "${backupPath}" "${AID_NGINX_STATE_FILE}" \
          || die "清理 Nginx 安装失败状态失败，请检查: ${AID_NGINX_STATE_FILE}"
      else
        rm -f -- "${siteFile}" || die "清理无效 Nginx 配置失败: ${siteFile}"
      fi
      die "Nginx 配置校验失败，已恢复原站点配置"
    fi
    reload_nginx_runtime || die "Nginx 重载失败，请检查 ${NGINX_SERVICE:-${NGINX_BIN}}"
    ok "Nginx 站点已生效"
  else
    [[ "$(conf_get HTTPS_ENABLED false)" != "true" ]] || die "启用 HTTPS 前必须先安装并启动 Nginx"
    echo "${content}" > "${DATA_ROOT}/aid-nginx.conf"
    warn "未检测到 nginx，站点配置已生成到 ${DATA_ROOT}/aid-nginx.conf 供手工放置"
  fi
}

manual_service_diagnostics() { # manual_service_diagnostics <systemd服务> <组件名>
  local service="$1" label="$2"
  err "${label} 未就绪，systemd 诊断信息如下："
  systemctl --no-pager --lines 8 status "${service}" 2>&1 || true
  echo "  最近日志（最多120行）: journalctl -u ${service} --no-pager -n 120" >&2
  journalctl -u "${service}" --no-pager -n 120 2>&1 || true
}

wait_manual_static_web_healthy() {
  local httpPort deadline
  httpPort="$(conf_get HTTP_PORT 80)"
  [[ -f "${DATA_ROOT}/app/web-dist/index.html" && -f "${DATA_ROOT}/app/web-dist/200.html" ]] \
    || { err "Web 静态入口不完整: ${DATA_ROOT}/app/web-dist/index.html 或 200.html 缺失"; return 1; }
  deadline=$(( $(date +%s) + 30 ))
  while [[ $(date +%s) -lt ${deadline} ]]; do
    if nginx_runtime_active \
        && curl -sf -o /dev/null "http://127.0.0.1:${httpPort}/" 2>/dev/null; then
      ok "Web 用户端静态站点已就绪"
      return 0
    fi
    sleep 2
  done
  err "Web 用户端静态站点未就绪，请检查 Nginx 配置与 ${DATA_ROOT}/app/web-dist/index.html、200.html"
  [[ -n "${NGINX_BIN:-}" && -x "${NGINX_BIN}" ]] && "${NGINX_BIN}" -t 2>&1 || true
  return 1
}

start_manual_application_stack() {
  write_systemd_units
  section "启动 AID 后端并执行健康检查"
  systemctl enable aid >/dev/null 2>&1 || true
  if ! systemctl restart aid; then
    manual_service_diagnostics aid "AID 后端"
    systemctl stop aid >/dev/null 2>&1 || true
    return 1
  fi
  if ! wait_backend_healthy; then
    manual_service_diagnostics aid "AID 后端"
    systemctl stop aid >/dev/null 2>&1 || true
    risk "AID 后端启动失败，已停止循环重启；MySQL/Redis 保持运行，修复配置后重新执行安装或重启"
    return 1
  fi

  [[ -f "${DATA_ROOT}/app/web-dist/index.html" && -f "${DATA_ROOT}/app/web-dist/200.html" ]] \
    || { err "Web 静态入口不完整: ${DATA_ROOT}/app/web-dist/index.html 或 200.html 缺失"; return 1; }
  section "启用 Web 用户端静态站点与 Nginx 网关"
  write_nginx_site
  wait_manual_static_web_healthy || return 1

  if [[ "${AID_SKIP_UPDATER_RESTART:-0}" != "1" ]]; then
    setup_updater manual
    if systemctl list-unit-files 2>/dev/null | grep -q '^aid-updater\.service' \
        && ! systemctl is-active --quiet aid-updater; then
      manual_service_diagnostics aid-updater "在线升级器"
      return 1
    fi
  fi
  ok "AID 后端、Web 静态站点、Nginx 与升级器已按顺序启动"
}

do_install_manual() {
  require_root
  local existingMode package targetVersion targetChannel
  existingMode="$(detect_mode)"
  confirm_reinstall manual || return 0
  # 手动部署同样先完成配置，避免安装环境或源码构建结束后才发现数据库参数不可用。
  ensure_conf_file
  confirm_initial_configuration manual
  export AID_DEPENDENCY_INSTALL_MODE="$(dependency_install_mode manual)"
  set_source_build_mode manual
  ensure_manual_host_dependencies
  prepare_install_package "${1:-}"
  package="${RESOLVED_PACKAGE_PATH}"
  bootstrap_installer_if_needed "${package}" install-manual

  [[ "${existingMode}" == "none" ]] && confirm_first_install manual
  # 手动部署不自动安装 RocketMQ，启用时只连接外部实例，因此不计入本机内存基线。
  local mqPlan="no"
  check_hardware manual "${mqPlan}"

  # 长时间源码构建结束后再次复检运行环境；全部通过才允许启动主程序。
  section "启动前运行环境复检"
  ensure_manual_host_dependencies
  ok "JDK、Node、MySQL、Redis、Nginx 与可选 RocketMQ 前置检查全部通过"

  # 数据库连通性与初始化
  local dbHost dbPort dbUser dbPwd dbName tableCount
  dbHost="$(conf_get DB_HOST)"; dbPort="$(conf_get DB_PORT)"; dbUser="$(conf_get DB_USERNAME)"
  dbPwd="$(conf_get DB_PASSWORD)"; dbName="$(conf_get DB_NAME)"
  MYSQL_PWD="${dbPwd}" mysql --protocol=TCP --host "${dbHost}" --port "${dbPort}" --user "${dbUser}" -e "SELECT 1" >/dev/null 2>&1 \
    || die "数据库连接失败，请检查配置（菜单「修改配置」可修改后重试）"
  tableCount="$(MYSQL_PWD="${dbPwd}" mysql --protocol=TCP --host "${dbHost}" --port "${dbPort}" --user "${dbUser}" \
    -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${dbName}'" 2>/dev/null || echo 0)"
  if [[ "${tableCount}" -gt 0 ]]; then
    ok "数据库 ${dbName} 已有 ${tableCount} 张表，跳过初始化"
  else
    local initSql="${REPO_DIR}/sql/aid-init.sql"
    [[ -f "${initSql}" ]] || die "未找到初始化脚本 ${initSql}"
    log "创建数据库并导入基线（约 1 分钟）..."
    MYSQL_PWD="${dbPwd}" mysql --protocol=TCP --host "${dbHost}" --port "${dbPort}" --user "${dbUser}" \
      -e "CREATE DATABASE IF NOT EXISTS \`${dbName}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
    MYSQL_PWD="${dbPwd}" mysql --protocol=TCP --host "${dbHost}" --port "${dbPort}" --user "${dbUser}" \
      --default-character-set=utf8mb4 "${dbName}" < "${initSql}" || die "基线导入失败"
    ok "数据库初始化完成"
  fi
  ensure_admin_entry_code manual

  place_artifacts "${package}"
  start_manual_application_stack \
    || die "AID 服务分阶段启动失败；失败服务已停止循环重启，前置数据服务保持运行"
  targetVersion="${RESOLVED_VERSION:-$(version_from_package "${package}")}"
  targetChannel="${REQUESTED_RELEASE_CHANNEL:-auto}"
  # 只有后端健康检查成功后才写入“已部署”状态。
  state_set DEPLOY_MODE "manual"
  state_set DATA_ROOT "${DATA_ROOT}"
  state_set CURRENT_VERSION "${targetVersion}"
  state_set RELEASE_CHANNEL "${targetChannel}"
  install_management_command
  print_access_info
}

# ----------------------------------------------------------------------------
# 更新到指定版本（两种模式通用）
# ----------------------------------------------------------------------------
updater_task_string_field() { # updater_task_string_field <字段名>
  local field="$1" healthFile="${UPDATER_DATA_DIR}/health.json"
  [[ -s "${healthFile}" ]] || return 1
  sed -nE "s/^[[:space:]]*\"${field}\"[[:space:]]*:[[:space:]]*\"(.*)\"[,]?[[:space:]]*$/\1/p" "${healthFile}" \
    | tail -n 1
}

updater_task_number_field() { # updater_task_number_field <字段名>
  local field="$1" healthFile="${UPDATER_DATA_DIR}/health.json"
  [[ -s "${healthFile}" ]] || return 1
  sed -nE "s/^[[:space:]]*\"${field}\"[[:space:]]*:[[:space:]]*([0-9]+)[,]?[[:space:]]*$/\1/p" "${healthFile}" \
    | tail -n 1
}

updater_action_label() { # updater_action_label <动作>
  case "$1" in
    UPGRADE) echo "主程序升级" ;;
    ROLLBACK) echo "版本回退" ;;
    UPDATER_UPGRADE) echo "升级器升级" ;;
    *) echo "$1" ;;
  esac
}

updater_version_task_active() {
  local action state
  action="$(updater_task_string_field action 2>/dev/null || true)"
  state="$(updater_task_string_field state 2>/dev/null || true)"
  [[ "${state}" == "RUNNING" ]] || return 1
  case "${action}" in
    UPGRADE|ROLLBACK|UPDATER_UPGRADE) return 0 ;;
    *) return 1 ;;
  esac
}

ensure_no_active_version_task() {
  local action phase progress
  updater_version_task_active || return 0
  action="$(updater_task_string_field action 2>/dev/null || true)"
  phase="$(updater_task_string_field phase 2>/dev/null || true)"
  progress="$(updater_task_number_field progress 2>/dev/null || echo 0)"
  err "已有$(updater_action_label "${action}")正在执行（${progress:-0}% ${phase:-处理中}），禁止重复提交"
  log "请执行 sudo aid progress 查看实时进度"
  return 1
}

print_updater_log_delta() { # print_updater_log_delta <日志文件> <已输出行数变量名>
  local logFile="$1" lineVar="$2" total=0 start=0
  [[ -f "${logFile}" ]] || return 0
  total="$(wc -l < "${logFile}" 2>/dev/null || echo 0)"
  [[ "${total}" =~ ^[0-9]+$ ]] || total=0
  local previous="${!lineVar}"
  [[ "${previous}" =~ ^[0-9]+$ ]] || previous=0
  # 日志被轮转或截断后，从新文件开头继续输出。
  (( total >= previous )) || previous=0
  if (( previous == 0 && total > 120 )); then
    start=$((total - 119))
  else
    start=$((previous + 1))
  fi
  if (( total >= start && total > 0 )); then
    sed -n "${start},${total}p" "${logFile}"
  fi
  printf -v "${lineVar}" '%s' "${total}"
}

do_upgrade_progress() {
  require_root
  local healthFile="${UPDATER_DATA_DIR}/health.json" logFile="${UPDATER_DATA_DIR}/updater.log"
  local action state progress phase message taskId startedAt updatedAt snapshot lastSnapshot="" displayedLines=0 key=""
  if ! updater_version_task_active; then
    warn "当前没有正在执行的升级、升级器升级或版本回退任务"
    return 0
  fi

  section "升级 / 回退实时进度"
  echo -e "${C_BOLD}黑色终端同源日志正在实时输出；按 q 可返回，任务会继续在后台执行。${C_RESET}"
  echo "  健康状态: ${healthFile}"
  echo "  完整日志: ${logFile}"
  echo "------------------------------------------------------"

  while :; do
    action="$(updater_task_string_field action 2>/dev/null || true)"
    state="$(updater_task_string_field state 2>/dev/null || true)"
    progress="$(updater_task_number_field progress 2>/dev/null || echo 0)"
    phase="$(updater_task_string_field phase 2>/dev/null || true)"
    message="$(updater_task_string_field message 2>/dev/null || true)"
    taskId="$(updater_task_string_field taskId 2>/dev/null || true)"
    startedAt="$(updater_task_string_field startedAt 2>/dev/null || true)"
    updatedAt="$(updater_task_string_field updatedAt 2>/dev/null || true)"
    [[ "${progress}" =~ ^[0-9]+$ ]] || progress=0
    snapshot="${action}|${state}|${progress}|${phase}|${message}|${updatedAt}"
    if [[ "${snapshot}" != "${lastSnapshot}" ]]; then
      echo -e "${C_CYAN}[$(date '+%H:%M:%S')]${C_RESET} ${C_BOLD}$(updater_action_label "${action}")${C_RESET}  ${C_GREEN}${progress}%${C_RESET}  ${phase:-处理中}"
      [[ -z "${message}" ]] || echo "  ${message}"
      [[ -z "${taskId}" ]] || echo "  任务: ${taskId}  开始: ${startedAt:-未知}"
      lastSnapshot="${snapshot}"
    fi
    print_updater_log_delta "${logFile}" displayedLines

    if [[ "${state}" != "RUNNING" ]]; then
      echo "------------------------------------------------------"
      case "${state}" in
        SUCCESS) ok "$(updater_action_label "${action}")已完成" ;;
        FAILED) err "$(updater_action_label "${action}")失败：${message:-请查看完整日志}" ;;
        *) warn "任务状态已变更为 ${state:-未知}：${message:-请查看完整日志}" ;;
      esac
      return 0
    fi

    if [[ -t 0 && -r /dev/tty ]]; then
      if read -r -s -n 1 -t 1 key </dev/tty && [[ "${key,,}" == "q" ]]; then
        warn "已退出进度查看，任务仍在后台执行；可随时运行 sudo aid progress 继续查看"
        return 0
      fi
    else
      sleep 1
    fi
  done
}

do_update() {
  require_root
  local mode package supplied current target comparison go backupDir dist old f targetChannel repairMode=0
  mode="$(detect_mode)"
  [[ "${mode}" != "none" ]] || die "尚未部署，请先执行首次部署"
  ensure_no_active_version_task || return 1
  # 先用当前远程引导脚本/受管模板补齐旧配置；仅追加缺失键，原值不变且同目录留备份。
  if [[ "${mode}" == "docker" ]]; then ensure_env_file; else ensure_conf_file; fi
  export AID_DEPENDENCY_INSTALL_MODE="$(dependency_install_mode "${mode}")"
  set_source_build_mode "${mode}"
  # Docker 升级同样先校验容器运行时，避免后续缺 Git 时给出误导性报错。
  if [[ "${mode}" == "docker" ]]; then
    require_docker_runtime
  else
    ensure_manual_host_dependencies
  fi
  supplied="${1:-}"
  current="$(current_version)"

  if [[ -n "${supplied}" ]]; then
    prepare_install_package "${supplied}"
    package="${RESOLVED_PACKAGE_PATH}"
    target="${RESOLVED_VERSION}"
  else
    resolve_official_release
    target="${RESOLVED_VERSION}"
    # 升级器是主程序升级的执行与回滚代理，必须优先恢复/更新并通过健康检查。
    # 这里只下载当前架构的小型升级器制品，不会提前构建或替换三端主程序。
    section "主程序升级前检查在线升级器"
    ensure_official_updater_binary
    setup_updater "${mode}" || die "升级器未就绪，主程序升级已中止"
    install_management_command
    if [[ "${current}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
      comparison="$(version_compare "${target}" "${current}")"
      if [[ "${comparison}" == "0" ]]; then
        if deployment_application_ready "${mode}"; then
          ok "当前已是 ${RESOLVED_CHANNEL} 渠道最新版且服务运行正常: ${current}"
          return 0
        fi
        if deployment_artifacts_ready; then
          warn "当前产物已是 ${RESOLVED_CHANNEL} 渠道最新版 ${current}，但服务未完整运行，开始执行同版本自愈启动"
          do_restart
          state_set DEPLOY_MODE "${mode}"
          state_set DATA_ROOT "${DATA_ROOT}"
          state_set CURRENT_VERSION "${current}"
          state_set RELEASE_CHANNEL "${REQUESTED_RELEASE_CHANNEL:-$(state_get RELEASE_CHANNEL auto)}"
          install_management_command
          ok "同版本自愈完成，AID 服务已恢复运行"
          print_access_info
          return 0
        fi
        repairMode=1
        warn "当前记录为最新版 ${current}，但服务未运行且程序产物不完整，将重新取得同版本发布包修复部署"
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

  if [[ "${repairMode}" == "1" ]]; then
    section "同版本部署修复确认"
  else
    section "版本升级确认"
  fi
  echo -e "  当前版本 : ${C_YELLOW}${current}${C_RESET}"
  echo -e "  目标版本 : ${C_GREEN}${target}${C_RESET} (${RESOLVED_CHANNEL:-本地包})"
  if [[ "${repairMode}" == "1" ]]; then
    risk "修复会重新放置当前版本程序产物并重启服务；数据库数据与已有配置不会被删除"
  else
    risk "升级会短暂停止服务，并可能执行包内增量 SQL；请勿在生成任务运行期间操作"
  fi
  warn "脚本将在替换任何程序或执行 SQL 前备份三端产物与数据库；备份失败会立即中止"
  warn "后台「项目升级配置」仍是首选入口，具备签名版本校验、源码构建、SQL 历史记录和失败自动回滚"
  if [[ "${AID_ASSUME_YES:-0}" == "1" ]]; then
    risk "AID_ASSUME_YES=1：已跳过升级人工确认"
    go="y"
  else
    if [[ "${repairMode}" == "1" ]]; then
      go="$(ask_yes_no "确认重新部署当前版本 ${target}？" 'n')"
    else
      go="$(ask_yes_no "确认从 ${current} 更新到 ${target}？" 'n')"
    fi
  fi
  [[ "${go}" == "y" ]] || { log "已取消"; return; }

  if [[ -z "${supplied}" ]]; then
    ensure_source_package
    package="${RESOLVED_PACKAGE_PATH}"
  fi
  # 再与目标版本包中的官方模板比较，确保跨多个版本更新时不会漏掉中间新增参数。
  merge_release_configuration "${package}" "${mode}"
  if [[ "${mode}" == "docker" ]]; then ensure_env_file; else ensure_conf_file; fi
  export AID_DEPENDENCY_INSTALL_MODE="$(dependency_install_mode "${mode}")"
  [[ "${mode}" != "docker" ]] || prepare_docker_runtime_images

  # docker 模式先确保数据库容器可用（备份与增量 SQL 都依赖它）
  ensure_mysql_ready || die "数据库未就绪，升级已中止（未做任何变更）"
  # 兼容早期初始化脚本留下的空访问码；已有非空访问码绝不修改。
  ensure_admin_entry_code "${mode}"

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

  # 包中不包含用户维护的 .env；先刷新受版本控制的 Compose/Nginx/部署脚本，
  # 本次重启即可使用新版模板，同时保留已有配置与密钥。
  refresh_managed_installer "${package}" || die "新版部署模板刷新失败，服务尚未重启"
  do_restart
  wait_backend_healthy || die "新版本未就绪，可执行菜单「回滚到升级前备份」还原: ${backupDir}"
  updater_runtime_ready "${mode}" || die "主程序已更新，但升级器健康检查未通过"
  target="$(current_version)"
  [[ "${target}" == "未知" ]] && target="${RESOLVED_VERSION:-$(version_from_package "${package}")}"
  targetChannel="${REQUESTED_RELEASE_CHANNEL:-$(state_get RELEASE_CHANNEL auto)}"
  state_set CURRENT_VERSION "${target}"
  state_set RELEASE_CHANNEL "${targetChannel}"
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
  ensure_no_active_version_task || return 1

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
  go="$(ask_yes_no '确认回滚程序产物？' 'n')"
  [[ "${go}" == "y" ]] || { log "已取消"; return; }

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
    restoreDb="$(ask_yes_no '是否同时还原数据库？会丢失升级后产生的全部数据！' 'n')"
    if [[ "${restoreDb}" == "y" ]]; then
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
  state_set CURRENT_VERSION "${targetVer}"
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
      ensure_env_file
      export AID_DEPENDENCY_INSTALL_MODE="$(dependency_install_mode docker)"
      require_docker_runtime
      prepare_docker_runtime_images
      disable_internal_mysql_for_external \
        || die "外部 MySQL 未准备完成，配置未生效"
      stop_unhealthy_docker_application_containers
      if docker_profile_enabled mq; then
        mkdir -p "${DATA_ROOT}/rocketmq/broker-data" "${DATA_ROOT}/rocketmq/broker-logs" "${DATA_ROOT}/rocketmq/namesrv-logs"
        chown -R 3000:3000 "${DATA_ROOT}/rocketmq" 2>/dev/null || true
      fi
      prepare_docker_runtime_dependencies \
        || die "前置服务检查失败，AID 主程序未重启；请按上方组件日志处理后重试"
      disable_unused_docker_services
      # 每次重启都从唯一配置真源重建升级器配置，确保数据库凭证、配置路径等
      # 与业务容器一致；随后显式重启升级器，不能依赖旧进程内存中的配置。
      if [[ "${AID_SKIP_UPDATER_RESTART:-0}" != "1" && -f "${DATA_ROOT}/app/updater/aid-updater" ]]; then
        write_updater_config docker
      fi
      start_docker_application_stack \
        || die "AID 服务分阶段重启失败；失败容器已停止循环重启，前置数据服务保持运行"
      ;;
    manual)
      ensure_conf_file
      export AID_DEPENDENCY_INSTALL_MODE="$(dependency_install_mode manual)"
      ensure_manual_host_dependencies
      start_manual_application_stack \
        || die "AID 服务分阶段重启失败；失败服务已停止循环重启，前置数据服务保持运行"
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

do_default() {
  require_root
  local mode
  mode="$(detect_mode)"
  [[ "${mode}" != "none" ]] || die "尚未部署，请先完成首次部署"
  if [[ "${mode}" == "docker" ]]; then
    [[ -f "${ENV_FILE}" ]] || die "Docker 配置文件不存在: ${ENV_FILE}"
    command -v docker >/dev/null 2>&1 || die "未检测到 Docker，无法读取实际管理端访问码"
  else
    [[ -f "${CONF}" ]] || die "手动部署配置文件不存在: ${CONF}"
    command -v mysql >/dev/null 2>&1 || die "未检测到 mysql 客户端，无法读取实际管理端访问码"
  fi
  print_access_info strict
}

do_mysql_info() {
  require_root
  local mode configFile publicIp
  mode="$(detect_mode)"
  [[ "${mode}" != "none" ]] || die "尚未部署，请先完成首次部署"
  if [[ "${mode}" == "docker" ]]; then
    configFile="${ENV_FILE}"
  else
    configFile="${CONF}"
  fi
  [[ -f "${configFile}" ]] || die "部署配置文件不存在: ${configFile}"
  publicIp="$(detect_public_ipv4 || true)"
  print_mysql_access_guidance "${mode}" "${publicIp}" "${configFile}"
}

aid_systemd_unit_names() {
  printf '%s\n' aid.service aid-web.service aid-updater.service aid-mysql.service aid-redis.service aid-nginx.service
}

aid_docker_known_container() { # aid_docker_known_container <container>
  case "$1" in
    aid-nginx-https|aid-nginx|aid-web|aid-server|aid-updater|aid-rocketmq-broker|aid-rocketmq-nameserver|aid-redis|aid-mysql) return 0 ;;
  esac
  return 1
}

aid_docker_daemon_available() {
  command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1
}

aid_docker_deployment_evidence_present() {
  local stateMode="$(state_get DEPLOY_MODE '')" container network
  [[ "${stateMode}" == "docker" ]] \
    && grep -Fxq "DATA_ROOT=${DATA_ROOT}" "${STATE_FILE}" 2>/dev/null && return 0
  [[ -f "${DEPLOYMENT_DESCRIPTOR}" && ! -L "${DEPLOYMENT_DESCRIPTOR}" ]] \
    && grep -Fq '"mode": "docker"' "${DEPLOYMENT_DESCRIPTOR}" 2>/dev/null \
    && grep -Fq "\"dataRoot\": \"${DATA_ROOT}\"" "${DEPLOYMENT_DESCRIPTOR}" 2>/dev/null && return 0
  [[ -f "${ENV_FILE}" && ! -L "${ENV_FILE}" ]] \
    && grep -Fq '# AID Docker 部署配置（唯一配置真源）' "${ENV_FILE}" 2>/dev/null \
    && grep -Fxq "DATA_ROOT=${DATA_ROOT}" "${ENV_FILE}" 2>/dev/null && return 0
  aid_docker_daemon_available || return 1
  while IFS= read -r container; do
    docker inspect "${container}" >/dev/null 2>&1 || continue
    aid_docker_container_belongs_to_current_install "${container}" && return 0
  done < <(aid_docker_candidate_container_list)
  while IFS= read -r network; do
    [[ -n "${network}" ]] || continue
    aid_docker_network_belongs_to_current_install "${network}" && return 0
  done < <(docker network ls --format '{{.Name}}' 2>/dev/null || true)
  return 1
}

require_docker_for_uninstall_if_needed() { # require_docker_for_uninstall_if_needed <detected mode>
  local mode="$1"
  if [[ "${mode}" == "docker" ]] || aid_docker_deployment_evidence_present; then
    aid_docker_daemon_available \
      || die "检测到 Docker 部署痕迹，但 Docker 守护进程不可用；请启动 Docker 后重试，当前不会删除任何 AID 内容"
  fi
}

aid_docker_container_belongs_to_current_install() { # aid_docker_container_belongs_to_current_install <container>
  local container="$1" marker="" root="" mount=""
  marker="$(docker inspect --format '{{index .Config.Labels "com.aid.managed"}}' "${container}" 2>/dev/null || true)"
  root="$(docker inspect --format '{{index .Config.Labels "com.aid.data_root"}}' "${container}" 2>/dev/null || true)"
  if [[ -n "${marker}" || -n "${root}" ]]; then
    [[ "${marker}" == "true" && "${root}" == "${DATA_ROOT}" ]]
    return
  fi
  # 旧版没有标签时，只接受固定名称且至少一个 bind mount 在当前 DATA_ROOT 内。
  aid_docker_known_container "${container}" || return 1
  while IFS= read -r mount; do
    case "${mount}" in
      "${DATA_ROOT}"|"${DATA_ROOT}/"*) return 0 ;;
    esac
  done < <(docker inspect --format '{{range .Mounts}}{{if eq .Type "bind"}}{{.Source}}{{"\n"}}{{end}}{{end}}' "${container}" 2>/dev/null || true)
  return 1
}

aid_docker_image_repository() { # aid_docker_image_repository <image ref>
  local reference="$1" tail=""
  reference="${reference%@*}"
  tail="${reference##*/}"
  [[ "${tail}" != *:* ]] || reference="${reference%:*}"
  printf '%s\n' "${reference}"
}

remember_owned_aid_docker_image() { # remember_owned_aid_docker_image <image id> <image ref>
  local imageId="$1" reference="$2" repository existing
  [[ -n "${imageId}" ]] || return 0
  repository="$(aid_docker_image_repository "${reference}")"
  [[ "${repository}" == aid/* ]] || return 0
  for existing in "${AID_OWNED_DOCKER_IMAGE_IDS[@]+"${AID_OWNED_DOCKER_IMAGE_IDS[@]}"}"; do
    [[ "${existing%%|*}" == "${imageId}" ]] && return 0
  done
  AID_OWNED_DOCKER_IMAGE_IDS+=("${imageId}|${repository}")
  AID_OWNED_DOCKER_IMAGE_COUNT=$((AID_OWNED_DOCKER_IMAGE_COUNT + 1))
}

remove_aid_docker_runtime() { # remove_aid_docker_runtime <keep|purge>
  local cleanupMode="$1" container network imageInfo imageId imageRef entry repository networks="" referencingContainers=""
  local -a containers=()
  local -a unresolvedImages=()
  aid_docker_daemon_available || return 0
  AID_OWNED_DOCKER_IMAGE_IDS=()
  AID_OWNED_DOCKER_IMAGE_COUNT=0
  while IFS= read -r container; do
    [[ -n "${container}" ]] && containers+=("${container}")
  done < <(aid_docker_candidate_container_list)
  networks="$(
    {
      for container in "${containers[@]+"${containers[@]}"}"; do
        docker inspect "${container}" >/dev/null 2>&1 || continue
        if aid_docker_container_belongs_to_current_install "${container}"; then
          docker inspect --format '{{range $name, $value := .NetworkSettings.Networks}}{{$name}}{{"\n"}}{{end}}' "${container}" 2>/dev/null || true
        elif aid_docker_known_container "${container}"; then
          warn "保留非当前 AID 安装的同名 Docker 容器: ${container}"
        fi
      done
      docker network ls --format '{{.Name}}' 2>/dev/null || true
    } | sort -u
  )"
  for container in "${containers[@]+"${containers[@]}"}"; do
    docker inspect "${container}" >/dev/null 2>&1 || continue
    aid_docker_container_belongs_to_current_install "${container}" || continue
    imageInfo="$(docker inspect --format '{{.Image}}|{{.Config.Image}}' "${container}" 2>/dev/null || true)"
    imageId="${imageInfo%%|*}"; imageRef="${imageInfo#*|}"
    remember_owned_aid_docker_image "${imageId}" "${imageRef}"
    docker rm -f "${container}" >/dev/null || die "删除受管 Docker 容器失败: ${container}"
    log "已删除受管 Docker 容器: ${container}"
  done
  while IFS= read -r network; do
    [[ -n "${network}" ]] || continue
    aid_docker_network_belongs_to_current_install "${network}" || continue
    if docker network rm "${network}" >/dev/null 2>&1; then
      log "已删除 AID Compose 网络: ${network}"
    else
      warn "无法删除 AID Compose 网络，稍后会作为卸载残留报错: ${network}"
    fi
  done <<< "${networks}"
  [[ "${cleanupMode}" == "purge" ]] || return 0
  # 仅删除刚从“已确认属于当前根”的容器记录到的 aid/* 镜像；不用 -f。
  for entry in "${AID_OWNED_DOCKER_IMAGE_IDS[@]+"${AID_OWNED_DOCKER_IMAGE_IDS[@]}"}"; do
    imageId="${entry%%|*}"; repository="${entry#*|}"
    if docker image rm "${imageId}" >/dev/null 2>&1; then
      log "已删除当前 AID 自建镜像: ${repository} (${imageId})"
    else
      referencingContainers="$(docker ps -a --filter "ancestor=${imageId}" --format '{{.ID}}' 2>/dev/null || true)"
      if [[ -n "${referencingContainers}" ]]; then
        warn "镜像仍被其他容器引用，已安全保留: ${repository} (${imageId})"
      else
        warn "当前 AID 自建镜像删除失败，需要人工核验: ${repository} (${imageId})"
        unresolvedImages+=("${entry}")
      fi
    fi
  done
  AID_OWNED_DOCKER_IMAGE_IDS=()
  AID_OWNED_DOCKER_IMAGE_COUNT=0
  for entry in "${unresolvedImages[@]+"${unresolvedImages[@]}"}"; do
    AID_OWNED_DOCKER_IMAGE_IDS+=("${entry}")
    AID_OWNED_DOCKER_IMAGE_COUNT=$((AID_OWNED_DOCKER_IMAGE_COUNT + 1))
  done
}

aid_docker_network_belongs_to_current_install() { # aid_docker_network_belongs_to_current_install <network>
  local network="$1" marker="" root="" workdir=""
  marker="$(docker network inspect --format '{{index .Labels "com.aid.managed"}}' "${network}" 2>/dev/null || true)"
  root="$(docker network inspect --format '{{index .Labels "com.aid.data_root"}}' "${network}" 2>/dev/null || true)"
  if [[ -n "${marker}" || -n "${root}" ]]; then
    [[ "${marker}" == "true" && "${root}" == "${DATA_ROOT}" ]]
    return
  fi
  workdir="$(docker network inspect --format '{{index .Labels "com.docker.compose.project.working_dir"}}' "${network}" 2>/dev/null || true)"
  [[ "${workdir}" == "${DATA_ROOT}/installer/deploy/docker" ]]
}

aid_docker_containers_present() {
  local container
  if ! aid_docker_daemon_available; then
    if aid_docker_deployment_evidence_present; then err "Docker 守护进程不可用，无法核验 AID Docker 容器残留"; return 0; fi
    return 1
  fi
  while IFS= read -r container; do
    docker inspect "${container}" >/dev/null 2>&1 || continue
    aid_docker_container_belongs_to_current_install "${container}" || continue
    err "卸载残留当前 AID Docker 容器: ${container}"
    return 0
  done < <(aid_docker_candidate_container_list)
  return 1
}

aid_docker_known_container_list() {
  printf '%s\n' aid-nginx-https aid-nginx aid-web aid-server aid-updater aid-rocketmq-broker aid-rocketmq-nameserver aid-redis aid-mysql
}

aid_docker_candidate_container_list() {
  # 新版按标签识别，不依赖固定容器名；固定名仅用于兼容无标签旧部署。
  {
    docker ps -a --format '{{.Names}}' 2>/dev/null || true
    aid_docker_known_container_list
  } | awk 'NF && !seen[$0]++'
}

aid_docker_images_present() {
  local entry imageId repository
  if ! aid_docker_daemon_available; then
    [[ "${AID_OWNED_DOCKER_IMAGE_COUNT}" -eq 0 ]] && return 1
    err "Docker 守护进程不可用，无法核验当前 AID 镜像残留"
    return 0
  fi
  for entry in "${AID_OWNED_DOCKER_IMAGE_IDS[@]+"${AID_OWNED_DOCKER_IMAGE_IDS[@]}"}"; do
    imageId="${entry%%|*}"; repository="${entry#*|}"
    docker image inspect "${imageId}" >/dev/null 2>&1 || continue
    err "卸载残留当前 AID 自建镜像: ${repository} (${imageId})"
    return 0
  done
  return 1
}

aid_docker_networks_present() {
  local network
  if ! aid_docker_daemon_available; then
    if aid_docker_deployment_evidence_present; then err "Docker 守护进程不可用，无法核验 AID Compose 网络残留"; return 0; fi
    return 1
  fi
  while IFS= read -r network; do
    [[ -n "${network}" ]] || continue
    aid_docker_network_belongs_to_current_install "${network}" || continue
    err "卸载残留 AID Compose 网络: ${network}"
    return 0
  done < <(docker network ls --format '{{.Name}}' 2>/dev/null)
  return 1
}


normalize_aid_absolute_path() { # normalize_aid_absolute_path <absolute path>
  local input="$1" part lastIndex normalizedCount=0
  local -a parts=() normalized=()
  [[ "${input}" == /* ]] || return 1
  IFS='/' read -r -a parts <<< "${input}"
  for part in "${parts[@]+"${parts[@]}"}"; do
    case "${part}" in
      ''|.) ;;
      ..)
        if [[ "${normalizedCount}" -gt 0 ]]; then
          lastIndex=$((normalizedCount - 1))
          unset "normalized[${lastIndex}]"
          normalizedCount=$((normalizedCount - 1))
        fi
        ;;
      *) normalized+=("${part}"); normalizedCount=$((normalizedCount + 1)) ;;
    esac
  done
  if [[ "${normalizedCount}" -eq 0 ]]; then
    printf '/\n'
  else
    local IFS='/'
    printf '/%s\n' "${normalized[*]}"
  fi
}

resolve_aid_symlink_target() { # resolve_aid_symlink_target <link>
  local path="$1" target="" directory="" resolved=""
  [[ -L "${path}" ]] || return 1
  # 已存在的目标可按真实路径解析；悬空链接则退回到原始目标的词法归一化，
  # 因而仍能识别 "aid -> ../data/aid/..." 这类受管入口。
  resolved="$(readlink -f -- "${path}" 2>/dev/null || true)"
  [[ -n "${resolved}" ]] && { printf '%s\n' "${resolved}"; return 0; }
  target="$(readlink -- "${path}" 2>/dev/null || true)"
  [[ -n "${target}" ]] || return 1
  case "${target}" in
    /*) normalize_aid_absolute_path "${target}" ;;
    *)
      directory="$(cd -P -- "$(dirname -- "${path}")" 2>/dev/null && pwd)" || return 1
      normalize_aid_absolute_path "${directory}/${target}"
      ;;
  esac
}

is_aid_management_command() { # is_aid_management_command <path>
  local path="$1" target=""
  [[ -L "${path}" ]] || return 1
  target="$(resolve_aid_symlink_target "${path}" 2>/dev/null || true)"
  case "${target}" in
    "${DATA_ROOT}/installer/deploy/aid.sh"|"${MANAGED_SCRIPT}") return 0 ;;
  esac
  return 1
}

is_aid_updater_command() { # is_aid_updater_command <path>
  local path="$1" target=""
  [[ -e "${path}" || -L "${path}" ]] || return 1
  target="$(resolve_aid_symlink_target "${path}" 2>/dev/null || true)"
  case "${target}" in
    "${DATA_ROOT}/app/updater/"*) return 0 ;;
  esac
  [[ -f "${path}" && -f "${DATA_ROOT}/app/updater/aid-updater" ]] \
    && cmp -s -- "${path}" "${DATA_ROOT}/app/updater/aid-updater"
}

aid_command_residuals_present() {
  local path target link
  path="${AID_LOCAL_BIN_DIR}/aid"
  if is_aid_management_command "${path}" \
      || [[ "${AID_MANAGEMENT_COMMAND_WAS_MANAGED}" == "1" && ( -e "${path}" || -L "${path}" ) ]]; then
    err "卸载残留 AID 管理命令: ${path}"
    return 0
  fi
  path="${AID_LOCAL_BIN_DIR}/aid-updater"
  if is_aid_updater_command "${path}" \
      || [[ "${AID_UPDATER_COMMAND_WAS_MANAGED}" == "1" && ( -e "${path}" || -L "${path}" ) ]]; then
    err "卸载残留 AID 升级器入口: ${path}"
    return 0
  fi
  for link in mysql mysqldump redis-server redis-cli nginx; do
    path="${AID_LOCAL_BIN_DIR}/${link}"
    [[ -L "${path}" ]] || continue
    target="$(resolve_aid_symlink_target "${path}" 2>/dev/null || true)"
    case "${target}" in
      "${DATA_ROOT}/runtime/"*)
        err "卸载残留 AID 工具入口: ${path}"
        return 0
        ;;
    esac
  done
  return 1
}

remove_aid_command_links() {
  local path target link
  path="${AID_LOCAL_BIN_DIR}/aid"
  if is_aid_management_command "${path}"; then
    AID_MANAGEMENT_COMMAND_WAS_MANAGED=1
    rm -f -- "${path}" || die "删除 AID 管理命令失败: ${path}"
  fi
  path="${AID_LOCAL_BIN_DIR}/aid-updater"
  if is_aid_updater_command "${path}"; then
    AID_UPDATER_COMMAND_WAS_MANAGED=1
    rm -f -- "${path}" || die "删除 AID 升级器入口失败: ${path}"
  fi

  # 手动部署可能创建指向 DATA_ROOT 隔离工具链的命令链接。只删除目标
  # 明确位于本项目 runtime 下的链接，不影响系统自带 mysql/redis。
  for link in mysql mysqldump redis-server redis-cli nginx; do
    path="${AID_LOCAL_BIN_DIR}/${link}"
    [[ -L "${path}" ]] || continue
    target="$(resolve_aid_symlink_target "${path}" 2>/dev/null || true)"
    case "${target}" in
      "${DATA_ROOT}/runtime/"*) rm -f -- "${path}" || die "删除 AID 工具入口失败: ${path}" ;;
    esac
  done
}

validate_aid_updater_runtime_dir() { # validate_aid_updater_runtime_dir <path>
  local path="$1" testRoot="${AID_UNINSTALL_TEST_ROOT:-}"
  case "${path}" in
    /etc/aid-updater|/var/lib/aid-updater) return 0 ;;
  esac
  [[ "${AID_UNINSTALL_TEST_MODE:-0}" == "1" && -n "${testRoot}" && "${path}" == "${testRoot}"/* && ! -L "${path}" ]] \
    || die "拒绝清理非标准升级器目录: ${path}"
}

is_recognized_aid_bootstrap() { # is_recognized_aid_bootstrap <absolute path>
  local path="$1"
  [[ "${path}" == /* && -f "${path}" && ! -L "${path}" ]] || return 1
  grep -Fq -- "${AID_BOOTSTRAP_MARKER}" "${path}" 2>/dev/null
}

is_aid_project_source_root() { # is_aid_project_source_root <resolved root>
  local root="$1"
  [[ -n "${root}" && "${root}" != "/" && -d "${root}" ]] || return 1
  [[ -d "${root}/.git" || -f "${root}/pom.xml" ]]
}

bootstrap_path_is_protected() { # bootstrap_path_is_protected <path>
  local path="$1" resolved repoResolved
  resolved="$(readlink -f -- "${path}" 2>/dev/null || true)"
  repoResolved="$(readlink -f -- "${REPO_DIR}" 2>/dev/null || true)"
  [[ -n "${resolved}" && "${resolved}" == "${SCRIPT_PATH}" ]] && return 0
  is_aid_project_source_root "${repoResolved}" || return 1
  case "${resolved}" in
    "${repoResolved}"|"${repoResolved}/"*) return 0 ;;
  esac
  return 1
}

remove_aid_bootstrap_scripts() {
  local path
  local -a paths=()
  IFS=':' read -r -a paths <<< "${AID_BOOTSTRAP_PATHS}"
  for path in "${paths[@]+"${paths[@]}"}"; do
    [[ -n "${path}" ]] || continue
    is_recognized_aid_bootstrap "${path}" || continue
    if bootstrap_path_is_protected "${path}"; then
      warn "保留受保护的当前脚本或源码文件: ${path}"
      continue
    fi
    rm -f -- "${path}" && log "已删除 AID 引导脚本: ${path}"
  done
}

aid_bootstrap_residuals_present() {
  local path
  local -a paths=()
  IFS=':' read -r -a paths <<< "${AID_BOOTSTRAP_PATHS}"
  for path in "${paths[@]+"${paths[@]}"}"; do
    [[ -n "${path}" ]] || continue
    if is_recognized_aid_bootstrap "${path}"; then
      err "卸载残留 AID 引导脚本: ${path}"
      return 0
    fi
  done
  return 1
}

purge_current_script_requires_handoff() {
  local path resolved
  case "${SCRIPT_PATH}" in "${DATA_ROOT}"/*) return 0 ;; esac
  local -a paths=()
  IFS=':' read -r -a paths <<< "${AID_BOOTSTRAP_PATHS}"
  for path in "${paths[@]+"${paths[@]}"}"; do
    [[ -n "${path}" ]] || continue
    resolved="$(readlink -f -- "${path}" 2>/dev/null || true)"
    [[ -n "${resolved}" && "${resolved}" == "${SCRIPT_PATH}" ]] && return 0
  done
  return 1
}

handoff_purge_to_safe_script() {
  local tempDir safeScript
  [[ "${AID_UNINSTALL_SAFE_REEXEC:-0}" == "1" ]] && return 0
  purge_current_script_requires_handoff || return 0
  tempDir="${AID_UNINSTALL_TEMP_DIR:-/tmp}"
  [[ "${tempDir}" == /* && -d "${tempDir}" && ! -L "${tempDir}" ]] \
    || die "无法创建安全卸载临时脚本，拒绝删除正在执行的 AID 文件: ${tempDir}"
  safeScript="$(mktemp "${tempDir%/}/aid-uninstall.XXXXXXXX.sh")" \
    || die "无法创建安全卸载临时脚本，拒绝继续"
  log "当前脚本位于待清理目录，已切换到临时安全执行环境"
  # exec 后原脚本不再执行；外层进程等待临时脚本结束并删除临时副本，避免
  # 删除当前正在执行的文件，也不会在 /tmp 留下 AID 脚本。
  exec /bin/bash -c '
    tmp="$1"; source="$2"
    cp -- "${source}" "${tmp}" || exit 1
    chmod 700 "${tmp}" || { rm -f -- "${tmp}"; exit 1; }
    env AID_DATA_ROOT="$3" AID_SH_LIBRARY_MODE=0 AID_UNINSTALL_SAFE_REEXEC=1 bash "${tmp}" uninstall --purge
    status=$?
    rm -f -- "${tmp}"
    exit "${status}"
  ' bash "${safeScript}" "${SCRIPT_PATH}" "${DATA_ROOT}"
}

aid_managed_swap_is_active() { # aid_managed_swap_is_active <swap文件>
  local wanted="$1" procSwaps="/proc/swaps" listed decoded rest
  if [[ "${AID_UNINSTALL_TEST_MODE:-0}" == "1" && -n "${AID_UNINSTALL_TEST_PROC_SWAPS:-}" ]]; then
    procSwaps="${AID_UNINSTALL_TEST_PROC_SWAPS}"
  fi
  [[ -r "${procSwaps}" ]] || die "无法读取Swap状态，拒绝卸载: ${procSwaps}"
  while read -r listed rest; do
    [[ -n "${listed}" && "${listed}" != "Filename" ]] || continue
    # /proc/swaps 使用八进制转义空格、制表符和反斜杠；%b仅解码内核提供的首列后精确比较。
    decoded="$(printf '%b' "${listed}")"
    [[ "${decoded}" == "${wanted}" ]] && return 0
  done < "${procSwaps}"
  return 1
}

aid_managed_swap_layout_is_owned() { # aid_managed_swap_layout_is_owned <目录> <swap文件> <marker>
  local swapDir="$1" swapFile="$2" marker="$3"
  [[ "$(command stat -c '%u:%a' "${swapDir}" 2>/dev/null || true)" == "0:700" ]] \
    && [[ "$(command stat -c '%u:%a' "${swapFile}" 2>/dev/null || true)" == "0:600" ]] \
    && [[ "$(command stat -c '%u:%a' "${marker}" 2>/dev/null || true)" == "0:600" ]]
}

deactivate_aid_managed_swap() { # deactivate_aid_managed_swap <keep|purge>
  local cleanupMode="$1" cacheDir="${DATA_ROOT}/build-cache" swapDir swapFile marker markerLine="" active=0 invalid=""
  swapDir="${cacheDir}/.aid-swap"
  swapFile="${swapDir}/aid-source-build.swap"
  marker="${swapFile}.owner"
  aid_managed_swap_is_active "${swapFile}" && active=1

  if [[ ! -e "${swapFile}" && ! -L "${swapFile}" && ! -e "${marker}" && ! -L "${marker}" ]]; then
    if [[ "${active}" == "1" ]]; then
      invalid="受管Swap仍处于激活状态但文件或归属标记不存在"
    else
      return 0
    fi
  elif [[ -L "${cacheDir}" || ! -d "${cacheDir}" \
      || -L "${swapDir}" || ! -d "${swapDir}" \
      || -L "${swapFile}" || ! -f "${swapFile}" \
      || -L "${marker}" || ! -f "${marker}" ]]; then
    invalid="受管Swap目录、文件或归属标记类型异常"
  else
    IFS= read -r markerLine < "${marker}" || true
    [[ "${markerLine}" == "AID_SOURCE_BUILD_SWAP_V1" ]] \
      || invalid="受管Swap归属标记不匹配"
    if [[ -z "${invalid}" ]] && ! aid_managed_swap_layout_is_owned "${swapDir}" "${swapFile}" "${marker}"; then
      invalid="受管Swap必须由root持有且权限为目录0700、文件0600"
    fi
  fi

  if [[ -n "${invalid}" ]]; then
    if [[ "${cleanupMode}" == "purge" ]]; then
      die "${invalid}，拒绝停用或删除保留路径: ${swapFile}"
    fi
    warn "${invalid}，按非AID资源保留且不执行swapoff: ${swapFile}"
    return 0
  fi
  [[ "${active}" == "1" ]] || return 0
  command -v swapoff >/dev/null 2>&1 || die "缺少swapoff，无法安全卸载AID受管Swap"
  swapoff "${swapFile}" || die "停用AID受管Swap失败，卸载已中止且数据目录保持不变: ${swapFile}"
  aid_managed_swap_is_active "${swapFile}" \
    && die "AID受管Swap在swapoff后仍处于激活状态，卸载已中止: ${swapFile}"
  log "已停用AID受管Swap: ${swapFile}"
}

purge_aid_data() {
  deactivate_aid_managed_swap purge
  validate_aid_purge_root
  [[ ! -e "${DATA_ROOT}" ]] || rm -rf -- "${DATA_ROOT}" \
    || die "删除 AID 数据目录失败: ${DATA_ROOT}"
  remove_aid_manual_accounts
  remove_aid_bootstrap_scripts
}

aid_systemd_unit_belongs_to_current_install() { # aid_systemd_unit_belongs_to_current_install <unit path>
  local unitPath="$1"
  [[ -f "${unitPath}" && ! -L "${unitPath}" ]] || return 1
  if grep -Fxq '# AID_MANAGED_UNIT=1' "${unitPath}" 2>/dev/null \
      && grep -Fxq "# AID_DATA_ROOT=${DATA_ROOT}" "${unitPath}" 2>/dev/null; then
    return 0
  fi
  # 旧升级器单元没有 DATA_ROOT 注释；只有同一升级器配置已证明归属时才允许回收。
  [[ "$(basename -- "${unitPath}")" == "aid-updater.service" ]] \
    && grep -Fq "${UPDATER_CONFIG_FILE}" "${unitPath}" 2>/dev/null \
    && aid_updater_config_belongs_to_current_install && return 0
  # Legacy unit: a fixed AID unit name plus a precise current-root runtime reference.
  grep -Fq "${DATA_ROOT}/app/" "${unitPath}" 2>/dev/null \
    || grep -Fq "${DATA_ROOT}/runtime/" "${unitPath}" 2>/dev/null \
    || grep -Fq "${DATA_ROOT}/config/" "${unitPath}" 2>/dev/null
}

aid_java_profile_belongs_to_current_install() { # aid_java_profile_belongs_to_current_install <profile path>
  local path="$1"
  [[ -f "${path}" && ! -L "${path}" ]] || return 1
  if grep -Fxq '# AID_MANAGED_JAVA_PROFILE=1' "${path}" 2>/dev/null \
      && grep -Fxq "# AID_DATA_ROOT=${DATA_ROOT}" "${path}" 2>/dev/null; then
    return 0
  fi
  grep -Fq 'AID 非 Docker 运行环境' "${path}" 2>/dev/null \
    && grep -Fq "${DATA_ROOT}/runtime/jdk-" "${path}" 2>/dev/null
}

aid_updater_marker_belongs_to_current_install() { # aid_updater_marker_belongs_to_current_install <directory>
  local marker="$1/.aid-managed"
  [[ -f "${marker}" && ! -L "${marker}" ]] \
    && grep -Fxq 'AID_MANAGED_UPDATER=1' "${marker}" 2>/dev/null \
    && grep -Fxq "AID_DATA_ROOT=${DATA_ROOT}" "${marker}" 2>/dev/null \
    && grep -Fxq "AID_MANAGER_SCRIPT=${MANAGED_SCRIPT}" "${marker}" 2>/dev/null
}

aid_updater_config_belongs_to_current_install() {
  aid_updater_marker_belongs_to_current_install "${UPDATER_CONFIG_DIR}" && return 0
  [[ -f "${UPDATER_CONFIG_FILE}" && ! -L "${UPDATER_CONFIG_FILE}" ]] \
    && grep -Fq "\"managerScript\": \"${MANAGED_SCRIPT}\"" "${UPDATER_CONFIG_FILE}" 2>/dev/null \
    && grep -Fq "\"backendJar\": \"${DATA_ROOT}/app/aid-admin.jar\"" "${UPDATER_CONFIG_FILE}" 2>/dev/null
}

aid_updater_data_belongs_to_current_install() {
  aid_updater_marker_belongs_to_current_install "${UPDATER_DATA_DIR}" && return 0
  aid_updater_config_belongs_to_current_install
}

remove_aid_updater_runtime() { # remove_aid_updater_runtime <keep|purge>
  local cleanupMode="$1" configOwned=0 dataOwned=0
  aid_updater_config_belongs_to_current_install && configOwned=1
  # 在删除配置前保存归属结论：旧版 data 目录可能没有 marker，需要依赖旧配置识别。
  aid_updater_data_belongs_to_current_install && dataOwned=1
  validate_aid_updater_runtime_dir "${UPDATER_CONFIG_DIR}"
  if [[ "${configOwned}" == "1" ]]; then
    rm -rf -- "${UPDATER_CONFIG_DIR}" || die "删除升级器配置失败: ${UPDATER_CONFIG_DIR}"
    [[ ! -e "${UPDATER_CONFIG_DIR}" && ! -L "${UPDATER_CONFIG_DIR}" ]] \
      || die "升级器配置仍有残留: ${UPDATER_CONFIG_DIR}"
  elif [[ -e "${UPDATER_CONFIG_DIR}" || -L "${UPDATER_CONFIG_DIR}" ]]; then
    warn "保留非当前 AID 安装的升级器配置目录: ${UPDATER_CONFIG_DIR}"
  fi
  [[ "${cleanupMode}" == "purge" ]] || return 0
  validate_aid_updater_runtime_dir "${UPDATER_DATA_DIR}"
  if [[ "${dataOwned}" == "1" ]]; then
    rm -rf -- "${UPDATER_DATA_DIR}" || die "删除升级器数据失败: ${UPDATER_DATA_DIR}"
    [[ ! -e "${UPDATER_DATA_DIR}" && ! -L "${UPDATER_DATA_DIR}" ]] \
      || die "升级器数据仍有残留: ${UPDATER_DATA_DIR}"
  elif [[ -e "${UPDATER_DATA_DIR}" || -L "${UPDATER_DATA_DIR}" ]]; then
    warn "保留非当前 AID 安装的升级器数据目录: ${UPDATER_DATA_DIR}"
  fi
}

aid_updater_residuals_present() {
  if aid_updater_config_belongs_to_current_install && [[ -e "${UPDATER_CONFIG_DIR}" || -L "${UPDATER_CONFIG_DIR}" ]]; then
    err "卸载残留当前 AID 升级器配置: ${UPDATER_CONFIG_DIR}"
    return 0
  fi
  if aid_updater_data_belongs_to_current_install && [[ -e "${UPDATER_DATA_DIR}" || -L "${UPDATER_DATA_DIR}" ]]; then
    err "卸载残留当前 AID 升级器数据: ${UPDATER_DATA_DIR}"
    return 0
  fi
  return 1
}

aid_nginx_site_belongs_to_current_install() { # aid_nginx_site_belongs_to_current_install <site file>
  local path="$1"
  [[ -f "${path}" && ! -L "${path}" ]] || return 1
  if grep -Fxq '# AID_MANAGED_NGINX=1' "${path}" 2>/dev/null \
      && grep -Fxq "# AID_DATA_ROOT=${DATA_ROOT}" "${path}" 2>/dev/null; then
    return 0
  fi
  # 旧版没有 marker，只接受安装器生成配置的三个强特征，禁止用 DATA_ROOT 子串宽松匹配。
  grep -Fq '# AID 站点' "${path}" 2>/dev/null \
    && grep -Fq "    root ${DATA_ROOT}/app/admin-dist;" "${path}" 2>/dev/null \
    && grep -Fq '        proxy_pass http://127.0.0.1:3000;' "${path}" 2>/dev/null
}

write_aid_nginx_backup_state() { # write_aid_nginx_backup_state <site file> <backup file>
  local siteFile="$1" backupFile="$2" backupSha tmp
  [[ -f "${backupFile}" && ! -L "${backupFile}" ]] || die "Nginx 原配置备份无效: ${backupFile}"
  backupSha="$(sha256_file "${backupFile}" 2>/dev/null || true)"
  [[ "${backupSha}" =~ ^[0-9A-Fa-f]{64}$ ]] || die "无法校验 Nginx 原配置备份: ${backupFile}"
  mkdir -p "$(dirname "${AID_NGINX_STATE_FILE}")"
  tmp="$(mktemp "${AID_NGINX_STATE_FILE}.XXXXXX")" || die "无法写入 Nginx 受管状态"
  cat > "${tmp}" <<EOF
AID_MANAGED_NGINX_STATE=1
AID_DATA_ROOT=${DATA_ROOT}
SITE_FILE=${siteFile}
BACKUP_FILE=${backupFile}
BACKUP_SHA256=${backupSha}
EOF
  chmod 600 "${tmp}"
  mv -f -- "${tmp}" "${AID_NGINX_STATE_FILE}"
}

aid_nginx_backup_for_site() { # aid_nginx_backup_for_site <site file>
  local siteFile="$1" stateSite="" backup="" expectedSha="" actualSha="" suffix=""
  [[ -f "${AID_NGINX_STATE_FILE}" && ! -L "${AID_NGINX_STATE_FILE}" ]] || return 1
  grep -Fxq 'AID_MANAGED_NGINX_STATE=1' "${AID_NGINX_STATE_FILE}" 2>/dev/null \
    && grep -Fxq "AID_DATA_ROOT=${DATA_ROOT}" "${AID_NGINX_STATE_FILE}" 2>/dev/null || return 1
  stateSite="$(sed -nE 's|^SITE_FILE=(.*)$|\1|p' "${AID_NGINX_STATE_FILE}" | head -n 1)"
  backup="$(sed -nE 's|^BACKUP_FILE=(.*)$|\1|p' "${AID_NGINX_STATE_FILE}" | head -n 1)"
  expectedSha="$(sed -nE 's|^BACKUP_SHA256=([0-9A-Fa-f]{64})$|\1|p' "${AID_NGINX_STATE_FILE}" | head -n 1)"
  [[ "${stateSite}" == "${siteFile}" && -n "${backup}" \
    && "$(dirname "${backup}")" == "$(dirname "${siteFile}")" \
    && "${backup}" == "${siteFile}.aid-before-install."* \
    && -f "${backup}" && ! -L "${backup}" ]] || return 1
  suffix="${backup#${siteFile}.aid-before-install.}"
  [[ "${suffix}" =~ ^[0-9]+$ ]] || return 1
  actualSha="$(sha256_file "${backup}" 2>/dev/null || true)"
  [[ -n "${expectedSha}" && "${actualSha,,}" == "${expectedSha,,}" ]] || return 1
  printf '%s\n' "${backup}"
}

remove_aid_nginx_site() {
  local dir siteFile backup
  local -a dirs=()
  IFS=':' read -r -a dirs <<< "${AID_NGINX_SITE_DIRS}"
  for dir in "${dirs[@]+"${dirs[@]}"}"; do
    [[ -n "${dir}" && -d "${dir}" ]] || continue
    siteFile="${dir}/aid.conf"
    backup="$(aid_nginx_backup_for_site "${siteFile}" 2>/dev/null || true)"
    if [[ ! -e "${siteFile}" && ! -L "${siteFile}" ]]; then
      # 受管站点意外丢失时，仍优先恢复本次安装前保存的同一路径配置。
      if [[ -n "${backup}" ]]; then
        cp -p -- "${backup}" "${siteFile}" || die "恢复原 Nginx 配置失败: ${siteFile}"
        rm -f -- "${backup}" "${AID_NGINX_STATE_FILE}" || die "清理已恢复的 Nginx 备份状态失败"
        log "已恢复安装 AID 前的 Nginx 配置: ${siteFile}"
      fi
      continue
    fi
    if ! aid_nginx_site_belongs_to_current_install "${siteFile}"; then
      warn "保留非当前 AID 安装的同名 Nginx 配置: ${siteFile}"
      continue
    fi
    if [[ -n "${backup}" ]]; then
      cp -p -- "${backup}" "${siteFile}" || die "恢复原 Nginx 配置失败: ${siteFile}"
      rm -f -- "${backup}" "${AID_NGINX_STATE_FILE}" || die "清理已恢复的 Nginx 备份状态失败"
      log "已恢复安装 AID 前的 Nginx 配置: ${siteFile}"
    else
      rm -f -- "${siteFile}" || die "删除 AID Nginx 配置失败: ${siteFile}"
      if [[ -f "${AID_NGINX_STATE_FILE}" ]] \
          && grep -Fxq "AID_DATA_ROOT=${DATA_ROOT}" "${AID_NGINX_STATE_FILE}" 2>/dev/null; then
        rm -f -- "${AID_NGINX_STATE_FILE}" || die "删除 AID Nginx 状态失败: ${AID_NGINX_STATE_FILE}"
      fi
    fi
  done
  if select_existing_nginx_runtime >/dev/null 2>&1 && "${NGINX_BIN}" -t >/dev/null 2>&1; then
    reload_nginx_runtime >/dev/null 2>&1 || true
  fi
}

aid_nginx_site_residuals_present() {
  local dir siteFile
  local -a dirs=()
  IFS=':' read -r -a dirs <<< "${AID_NGINX_SITE_DIRS}"
  for dir in "${dirs[@]+"${dirs[@]}"}"; do
    [[ -n "${dir}" && -d "${dir}" ]] || continue
    siteFile="${dir}/aid.conf"
    if aid_nginx_site_belongs_to_current_install "${siteFile}"; then
      err "卸载残留当前 AID Nginx 配置: ${siteFile}"
      return 0
    fi
  done
  return 1
}

aid_systemd_runtime_control_required() {
  [[ "${AID_SYSTEMD_UNIT_DIR}" == "/etc/systemd/system" ]] && return 0
  [[ "${AID_UNINSTALL_TEST_MODE:-0}" == "1" && "${AID_UNINSTALL_TEST_SYSTEMD:-0}" == "1" ]]
}

remove_aid_system_services() {
  local unitDir="${AID_SYSTEMD_UNIT_DIR}" unit unitPath
  local stopFailed=0 activeStatus=0
  AID_OWNED_SYSTEMD_SERVICES=()
  AID_OWNED_SYSTEMD_SERVICE_COUNT=0
  while IFS= read -r unit; do
    unitPath="${unitDir}/${unit}"
    [[ -e "${unitPath}" || -L "${unitPath}" ]] || continue
    if aid_systemd_unit_belongs_to_current_install "${unitPath}"; then
      AID_OWNED_SYSTEMD_SERVICES+=("${unit}")
      AID_OWNED_SYSTEMD_SERVICE_COUNT=$((AID_OWNED_SYSTEMD_SERVICE_COUNT + 1))
    else
      warn "保留非当前 AID 安装的同名 systemd unit: ${unitPath}"
    fi
  done < <(aid_systemd_unit_names)
  if aid_systemd_runtime_control_required && [[ "${AID_OWNED_SYSTEMD_SERVICE_COUNT}" -gt 0 ]]; then
    command -v systemctl >/dev/null 2>&1 \
      || die "无法验证 AID systemd 服务状态，未删除任何 unit"
    for unit in "${AID_OWNED_SYSTEMD_SERVICES[@]+"${AID_OWNED_SYSTEMD_SERVICES[@]}"}"; do
      stopFailed=0
      systemctl disable --now "${unit}" >/dev/null 2>&1 || stopFailed=1
      if systemctl is-active --quiet "${unit}"; then activeStatus=0; else activeStatus=$?; fi
      case "${activeStatus}" in
        0) die "AID 服务仍在运行，拒绝删除 unit: ${unit}" ;;
        3|4) ;;
        *) die "无法确认 AID 服务已停止，拒绝删除 unit: ${unit}" ;;
      esac
      [[ "${stopFailed}" == "0" ]] || warn "${unit} 停止/禁用命令返回异常，但服务已确认停止"
    done
  fi
  for unit in "${AID_OWNED_SYSTEMD_SERVICES[@]+"${AID_OWNED_SYSTEMD_SERVICES[@]}"}"; do
    rm -f -- "${unitDir}/${unit}" || die "删除 AID systemd unit 失败: ${unitDir}/${unit}"
  done
  if aid_java_profile_belongs_to_current_install "${JAVA_PROFILE_FILE}"; then
    rm -f -- "${JAVA_PROFILE_FILE}" || die "删除 AID Java 环境文件失败: ${JAVA_PROFILE_FILE}"
  elif [[ -e "${JAVA_PROFILE_FILE}" || -L "${JAVA_PROFILE_FILE}" ]]; then
    warn "保留非当前 AID 安装的 Java profile: ${JAVA_PROFILE_FILE}"
  fi
  if aid_systemd_runtime_control_required && [[ "${AID_OWNED_SYSTEMD_SERVICE_COUNT}" -gt 0 ]]; then
    systemctl daemon-reload >/dev/null 2>&1 || die "systemd 配置重载失败"
    systemctl reset-failed >/dev/null 2>&1 || warn "systemd 失败状态清理未完成"
  fi
}

aid_systemd_residuals_present() {
  local unitDir="$1" unit unitPath recorded activeStatus
  while IFS= read -r unit; do
    unitPath="${unitDir}/${unit}"
    aid_systemd_unit_belongs_to_current_install "${unitPath}" || continue
    err "卸载残留当前 AID systemd unit: ${unitPath}"
    return 0
  done < <(aid_systemd_unit_names)
  if aid_java_profile_belongs_to_current_install "${JAVA_PROFILE_FILE}"; then
    err "卸载残留当前 AID Java 环境文件: ${JAVA_PROFILE_FILE}"
    return 0
  fi
  if aid_systemd_runtime_control_required && [[ "${AID_OWNED_SYSTEMD_SERVICE_COUNT}" -gt 0 ]]; then
    command -v systemctl >/dev/null 2>&1 || { err "无法核验 AID systemd 服务运行状态"; return 0; }
    for recorded in "${AID_OWNED_SYSTEMD_SERVICES[@]+"${AID_OWNED_SYSTEMD_SERVICES[@]}"}"; do
      if systemctl is-active --quiet "${recorded}"; then activeStatus=0; else activeStatus=$?; fi
      case "${activeStatus}" in
        0) err "卸载残留运行中的 AID systemd 服务: ${recorded}"; return 0 ;;
        3|4) ;;
        *) err "无法核验 AID systemd 服务状态: ${recorded}"; return 0 ;;
      esac
    done
  fi
  return 1
}

aid_manual_account_belongs_to_current_install() { # aid_manual_account_belongs_to_current_install <aidmysql|aidredis>
  local name="$1" expectedHome="" account home shell
  case "${name}" in
    aidmysql) expectedHome="${DATA_ROOT}/mysql-data-manual" ;;
    aidredis) expectedHome="${DATA_ROOT}/redis-data-manual" ;;
    *) return 1 ;;
  esac
  account="$(getent passwd "${name}" 2>/dev/null || true)"
  [[ -n "${account}" ]] || return 1
  IFS=':' read -r _ _ _ _ _ home shell <<< "${account}"
  [[ "${home}" == "${expectedHome}" ]] || return 1
  [[ "${shell}" == "/sbin/nologin" || "${shell}" == "/usr/sbin/nologin" || "${shell}" == "nologin" ]]
}

remove_aid_manual_accounts() {
  local name
  AID_OWNED_MANUAL_ACCOUNTS=()
  AID_OWNED_MANUAL_ACCOUNT_COUNT=0
  AID_OWNED_MANUAL_GROUPS=()
  AID_OWNED_MANUAL_GROUP_COUNT=0
  for name in aidmysql aidredis; do
    if aid_manual_account_belongs_to_current_install "${name}"; then
      AID_OWNED_MANUAL_ACCOUNTS+=("${name}")
      AID_OWNED_MANUAL_ACCOUNT_COUNT=$((AID_OWNED_MANUAL_ACCOUNT_COUNT + 1))
      if getent group "${name}" >/dev/null 2>&1; then
        AID_OWNED_MANUAL_GROUPS+=("${name}")
        AID_OWNED_MANUAL_GROUP_COUNT=$((AID_OWNED_MANUAL_GROUP_COUNT + 1))
      fi
      userdel "${name}" >/dev/null 2>&1 || warn "无法删除受管系统账号，稍后会作为卸载残留报错: ${name}"
      if getent group "${name}" >/dev/null 2>&1; then
        groupdel "${name}" >/dev/null 2>&1 \
          || warn "无法删除受管系统用户组，稍后会作为卸载残留报错: ${name}"
      fi
    elif id "${name}" >/dev/null 2>&1 || getent group "${name}" >/dev/null 2>&1; then
      warn "保留非当前 AID 安装的同名系统账号或用户组: ${name}"
    fi
  done
}

aid_manual_accounts_present() {
  local name
  for name in "${AID_OWNED_MANUAL_ACCOUNTS[@]+"${AID_OWNED_MANUAL_ACCOUNTS[@]}"}"; do
    if id "${name}" >/dev/null 2>&1 || getent passwd "${name}" >/dev/null 2>&1; then
      err "卸载残留当前 AID 受管系统账号: ${name}"
      return 0
    fi
  done
  for name in "${AID_OWNED_MANUAL_GROUPS[@]+"${AID_OWNED_MANUAL_GROUPS[@]}"}"; do
    if getent group "${name}" >/dev/null 2>&1; then
      err "卸载残留当前 AID 受管系统用户组: ${name}"
      return 0
    fi
  done
  for name in aidmysql aidredis; do
    if aid_manual_account_belongs_to_current_install "${name}"; then
      err "卸载残留当前 AID 受管系统账号: ${name}"
      return 0
    fi
  done
  return 1
}

aid_data_root_has_ownership_evidence() {
  [[ -f "${AID_ROOT_MARKER}" && ! -L "${AID_ROOT_MARKER}" ]] \
    && grep -Fxq 'AID_MANAGED_ROOT=1' "${AID_ROOT_MARKER}" 2>/dev/null \
    && grep -Fxq "AID_DATA_ROOT=${DATA_ROOT}" "${AID_ROOT_MARKER}" 2>/dev/null \
    && grep -Fxq "AID_MANAGER_SCRIPT=${MANAGED_SCRIPT}" "${AID_ROOT_MARKER}" 2>/dev/null && return 0
  [[ -f "${DEPLOYMENT_DESCRIPTOR}" && ! -L "${DEPLOYMENT_DESCRIPTOR}" ]] \
    && grep -Fq "\"dataRoot\": \"${DATA_ROOT}\"" "${DEPLOYMENT_DESCRIPTOR}" 2>/dev/null \
    && grep -Eq '"mode"[[:space:]]*:[[:space:]]*"(docker|manual|systemd)"' "${DEPLOYMENT_DESCRIPTOR}" 2>/dev/null && return 0
  [[ -f "${STATE_FILE}" && ! -L "${STATE_FILE}" ]] \
    && grep -Fxq "DATA_ROOT=${DATA_ROOT}" "${STATE_FILE}" 2>/dev/null \
    && grep -Eq '^DEPLOY_MODE=(docker|manual)$' "${STATE_FILE}" 2>/dev/null && return 0
  [[ -f "${CONF}" && ! -L "${CONF}" ]] \
    && grep -Fq '# AID 手动部署配置（唯一配置真源' "${CONF}" 2>/dev/null \
    && grep -Fxq "DATA_ROOT=${DATA_ROOT}" "${CONF}" 2>/dev/null && return 0
  [[ -f "${ENV_FILE}" && ! -L "${ENV_FILE}" ]] \
    && grep -Fq '# AID Docker 部署配置（唯一配置真源）' "${ENV_FILE}" 2>/dev/null \
    && grep -Fxq "DATA_ROOT=${DATA_ROOT}" "${ENV_FILE}" 2>/dev/null && return 0
  return 1
}

aid_uninstall_target_evidence_present() {
  local unit unitPath dir siteFile backup container network
  local -a dirs=()
  aid_data_root_has_ownership_evidence && return 0
  aid_updater_config_belongs_to_current_install && return 0
  aid_updater_data_belongs_to_current_install && return 0
  while IFS= read -r unit; do
    unitPath="${AID_SYSTEMD_UNIT_DIR}/${unit}"
    aid_systemd_unit_belongs_to_current_install "${unitPath}" && return 0
  done < <(aid_systemd_unit_names)
  IFS=':' read -r -a dirs <<< "${AID_NGINX_SITE_DIRS}"
  for dir in "${dirs[@]+"${dirs[@]}"}"; do
    [[ -n "${dir}" ]] || continue
    siteFile="${dir}/aid.conf"
    aid_nginx_site_belongs_to_current_install "${siteFile}" && return 0
    backup="$(aid_nginx_backup_for_site "${siteFile}" 2>/dev/null || true)"
    [[ -z "${backup}" ]] || return 0
  done
  is_aid_management_command "${AID_LOCAL_BIN_DIR}/aid" && return 0
  is_aid_updater_command "${AID_LOCAL_BIN_DIR}/aid-updater" && return 0
  if aid_docker_daemon_available; then
    while IFS= read -r container; do
      docker inspect "${container}" >/dev/null 2>&1 || continue
      aid_docker_container_belongs_to_current_install "${container}" && return 0
    done < <(aid_docker_candidate_container_list)
    while IFS= read -r network; do
      [[ -n "${network}" ]] || continue
      aid_docker_network_belongs_to_current_install "${network}" && return 0
    done < <(docker network ls --format '{{.Name}}' 2>/dev/null || true)
  fi
  return 1
}

validate_aid_purge_root() {
  local resolved=""
  [[ "${DATA_ROOT}" == /* ]] || die "拒绝清理非绝对数据目录: ${DATA_ROOT}"
  case "${DATA_ROOT}" in
    /|/bin|/boot|/data|/dev|/etc|/home|/opt|/root|/run|/srv|/tmp|/usr|/var) die "拒绝清理高风险目录: ${DATA_ROOT}" ;;
  esac
  [[ "${DATA_ROOT#/}" == */* ]] || die "数据目录层级过浅，拒绝清理: ${DATA_ROOT}"
  [[ ! -L "${DATA_ROOT}" ]] || die "数据目录是软链接，拒绝清理: ${DATA_ROOT}"
  if [[ -e "${DATA_ROOT}" ]]; then
    resolved="$(readlink -f -- "${DATA_ROOT}" 2>/dev/null || true)"
    [[ "${resolved}" == "${DATA_ROOT}" ]] || die "数据目录解析异常，拒绝清理: ${resolved:-未知}"
    aid_data_root_has_ownership_evidence || die "数据目录缺少 AID 受管证据，拒绝递归删除: ${DATA_ROOT}"
  fi
}

verify_aid_purge_cleanup() {
  local failed=0
  [[ ! -e "${DATA_ROOT}" && ! -L "${DATA_ROOT}" ]] || { err "卸载残留数据目录: ${DATA_ROOT}"; failed=1; }
  aid_updater_residuals_present && failed=1
  aid_systemd_residuals_present "${AID_SYSTEMD_UNIT_DIR}" && failed=1
  aid_docker_containers_present && failed=1
  aid_docker_images_present && failed=1
  aid_docker_networks_present && failed=1
  aid_nginx_site_residuals_present && failed=1
  aid_command_residuals_present && failed=1
  aid_manual_accounts_present && failed=1
  aid_bootstrap_residuals_present && failed=1
  [[ "${failed}" -eq 0 ]]
}

do_uninstall() { # do_uninstall [keep|purge|--keep|--purge]
  require_root
  local requested="${1:-}" cleanupMode="" choice confirm mode
  mode="$(detect_mode)"
  aid_uninstall_target_evidence_present \
    || die "未找到属于 ${DATA_ROOT} 的 AID 安装证据；未删除任何内容。自定义目录请显式指定 AID_DATA_ROOT=/实际目录"
  # Docker 部署必须先确认守护进程可访问；否则不能把“无法核验”误报为已彻底卸载。
  require_docker_for_uninstall_if_needed "${mode}"
  echo ""
  risk "卸载会立即停止 AID；请先确认没有生成任务运行，并已完成异机备份"
  echo "  当前部署方式: ${mode}"
  echo "  AID 数据目录 : ${DATA_ROOT}"
  echo "  1) 仅卸载服务与运行入口，保留数据库、上传文件、配置、备份和构建缓存"
  echo "  2) 彻底清除 AID，包括内置数据库数据、上传文件、配置、备份和缓存（不可恢复）"
  echo "  0) 取消"
  case "${requested}" in
    keep|--keep) cleanupMode="keep" ;;
    purge|--purge) cleanupMode="purge" ;;
    '')
      choice="$(ask '请选择卸载方式' '0')"
      case "${choice}" in 1) cleanupMode="keep" ;; 2) cleanupMode="purge" ;; *) log "已取消卸载"; return 0 ;; esac
      ;;
    *) die "卸载参数只支持 keep/--keep 或 purge/--purge" ;;
  esac

  if [[ "${cleanupMode}" == "purge" ]]; then
    validate_aid_purge_root
    # 受管脚本先换出到临时安全副本；副本只携带 SAFE_REEXEC，随后在自身流程
    # 中展示风险并要求唯一一次 DELETE-AID，任何环境变量均不能绕过该确认。
    handoff_purge_to_safe_script
    risk "将永久删除 ${DATA_ROOT}；内置 MySQL/Redis/MQ 数据与所有本机备份都无法恢复"
    warn "外部或用户原有的 MySQL/Redis/RocketMQ、OSS/COS 对象不会被删除，请到对应平台单独处理"
    confirm="$(ask '请输入 DELETE-AID 确认彻底清除' '')"
    [[ "${confirm}" == "DELETE-AID" ]] || { log "确认文字不匹配，已取消卸载"; return 0; }
  else
    confirm="$(ask_yes_no '确认停止并卸载 AID，同时保留全部数据？' 'n')"
    [[ "${confirm}" == "y" ]] || { log "已取消卸载"; return 0; }
  fi

  # Swap属于AID运行资源：keep仅停用并保留文件，purge停用后再随数据目录删除。
  # 必须在移除任何运行入口前完成，swapoff失败不得造成部分卸载或误报成功。
  deactivate_aid_managed_swap "${cleanupMode}"
  section "卸载 AID"
  remove_aid_docker_runtime "${cleanupMode}"
  remove_aid_system_services
  remove_aid_nginx_site
  remove_aid_command_links
  remove_aid_updater_runtime "${cleanupMode}"
  [[ "${cleanupMode}" != "purge" ]] || purge_aid_data

  if [[ "${cleanupMode}" == "purge" ]]; then
    verify_aid_purge_cleanup || die "AID 卸载残留核验失败；未报告成功，请根据上方路径处理后重试"
    ok "AID 已彻底清除；Docker/JDK/Nginx/Git 等共享系统环境及外部服务未删除"
  else
    ok "AID 服务与运行入口已卸载，数据完整保留在 ${DATA_ROOT}"
    echo "重新安装时下载最新 aid.sh 并选择原部署方式，脚本会复用现有配置和数据。"
  fi
}

do_uninstall_all() {
  do_uninstall purge
}

do_status() {
  local mode; mode="$(detect_mode)"
  echo ""
  echo "部署方式: ${mode}    版本: $(current_version)    数据目录: ${DATA_ROOT}"
  case "${mode}" in
    docker) compose_cmd ps ;;
    manual)
      systemctl --no-pager --lines 0 status aid 2>/dev/null | head -n 5 || true
      if select_existing_nginx_runtime >/dev/null 2>&1; then
        if nginx_runtime_active; then ok "Web 静态站点由 Nginx 托管且运行中"
        else warn "Web 静态站点文件存在，但 Nginx 未运行"; fi
      else
        warn "未检测到托管 Web 静态站点的 Nginx"
      fi
      systemctl --no-pager --lines 0 status aid-updater 2>/dev/null | head -n 5 || true
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
  echo "  3) 用户端静态站点（Nginx）日志"
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
      elif select_existing_nginx_runtime >/dev/null 2>&1 && [[ -n "${NGINX_SERVICE:-}" ]]; then
        journalctl -u "${NGINX_SERVICE}" -f -n 200
      else
        warn "未检测到 Nginx systemd 服务，请查看 Nginx access/error 日志"
      fi ;;
    4)
      if [[ "${mode}" == "docker" ]]; then
        if docker_profile_enabled mysql; then
          docker logs -f --tail 100 aid-mysql
        else
          warn "当前使用外部 MySQL $(env_get DB_HOST):$(env_get DB_PORT 3306)，请到数据库服务端查看日志"
        fi
      else warn "手动部署的 MySQL 日志位置取决于你的安装方式"; fi ;;
    5)
      if [[ "${mode}" == "docker" ]]; then
        if docker inspect aid-updater >/dev/null 2>&1; then docker logs -f --tail 100 aid-updater
        else warn "Docker 升级器未安装（修复: sudo aid setup-updater）"; fi
      # 先判断服务是否安装，避免 Ctrl+C 退出日志跟踪时误报"未安装"
      elif systemctl list-unit-files 2>/dev/null | grep -q '^aid-updater\.service'; then journalctl -u aid-updater -f -n 100
      else warn "systemd 升级器未安装（修复: sudo aid setup-updater）"; fi ;;
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
  editNow="$(ask_yes_no "现在用 vi 打开编辑？" 'n')"
  if [[ "${editNow}" == "y" ]]; then
    "${EDITOR:-vi}" "${configFile}" </dev/tty >/dev/tty || true
    local apply
    apply="$(ask_yes_no '立即重启使配置生效？' 'y')"
    if [[ "${apply}" == "y" ]]; then
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
  if [[ "${mode}" == "docker" ]]; then
    require_docker_runtime
    ensure_env_file
  else
    ensure_conf_file
  fi
  export AID_DEPENDENCY_INSTALL_MODE="$(dependency_install_mode "${mode}")"
  set_source_build_mode "${mode}"
  if [[ "${mode}" == "manual" ]]; then
    ensure_git_runtime "${AID_DEPENDENCY_INSTALL_MODE}"
  fi
  resolve_official_release
  # 只刷新并验证构建器，不构建三端源码；升级器后续必须使用与部署方式一致的构建链路。
  bootstrap_source_builder "${AID_SOURCE_BUILD_MODE}"
  source_builder_supports_explicit_mode "${SOURCE_BUILDER_PATH}" \
    || die "源码构建器不支持显式构建模式；请重新执行最新远程 aid.sh 后重试"
  ensure_official_updater_binary
  setup_updater "${mode}" || die "升级器安装/修复失败，请查看上方诊断日志"
  install_management_command
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
  echo -e " 部署方式: ${C_GREEN}${mode}${C_RESET}    当前版本: ${C_GREEN}$(current_version)${C_RESET}    渠道: ${C_GREEN}$(state_get RELEASE_CHANNEL auto)${C_RESET}"
  echo -e " 数据目录: ${DATA_ROOT}"
  echo "------------------------------------------------------"
  echo "  1) 一键首次部署（Docker，源码构建，推荐）"
  echo "  2) 首次部署（手动 systemd，源码构建）"
  echo "  3) 自动检查并升级到当前渠道最新版（升级前完整备份）"
  echo "  4) 回滚到升级前备份（最近 3 份可选）"
  echo "  5) 重启服务（配置变更后生效）"
  echo "  6) 停止服务"
  echo "  7) 查看状态"
  echo "  8) 查看日志"
  echo "  9) 修改配置（编辑配置文件后一键生效）"
  echo " 10) 立即备份（数据库+上传文件）"
  echo " 11) 安装/修复在线升级器"
  echo " 12) 查看登录地址与数据库初始化账号"
  echo " 13) 彻底卸载 AID（删除全部 AID 数据）"
  echo " 14) 查看 MySQL 数据库连接与账号信息"
  if updater_version_task_active; then
    echo -e " ${C_GREEN}15) 查看升级/回退实时进度（当前有任务）${C_RESET}"
  fi
  echo "  0) 退出"
  echo "------------------------------------------------------"
}

main() {
  # 内部升级前置入口必须执行当前目标包中的脚本，不能交接回旧版受管脚本。
  # 该入口不展示在用户菜单，仅由签名升级包解压后的升级器调用。
  if [[ "${1:-}" == "__upgrade-runtime-preflight" ]]; then
    do_upgrade_runtime_preflight "${2:-}"
    return $?
  fi
  # 用户以后仍执行最初下载的单文件时，自动切换到源码构建包持久化安装的最新版管理脚本。
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
    progress)       do_upgrade_progress; exit $? ;;
    restart)        do_restart; exit $? ;;
    stop)           do_stop; exit $? ;;
    status)         do_status; exit $? ;;
    default)        do_default; exit $? ;;
    mysql|db)       do_mysql_info; exit $? ;;
    uninstall)      do_uninstall "${2:-}"; exit $? ;;
    uninstall-all)  do_uninstall_all; exit $? ;;
    logs)           do_logs; exit $? ;;
    config)         do_config; exit $? ;;
    backup)         do_backup; exit $? ;;
    setup-updater)  do_setup_updater; exit $? ;;
    '') ;;
    *) die "未知子命令: $1（可用: install/auto/install-docker/install-manual/update/rollback/progress/restart/stop/status/default/mysql/logs/config/backup/setup-updater/uninstall/uninstall-all）" ;;
  esac

  # 交互启动时如果检测到进行中的升级/回退，优先进入实时进度，避免任务藏在菜单底部。
  if updater_version_task_active; then
    do_upgrade_progress || true
  fi

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
      12) do_default || true ;;
      13) do_uninstall_all || true ;;
      14) do_mysql_info || true ;;
      15)
        if updater_version_task_active; then
          do_upgrade_progress || true
        else
          warn "当前没有正在执行的升级或回退任务"
        fi
        ;;
      0) exit 0 ;;
      *) warn "无效选择" ;;
    esac
  done
}

if [[ "${AID_SH_LIBRARY_MODE:-0}" != "1" ]]; then
  main "$@"
fi
