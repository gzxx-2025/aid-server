#!/bin/bash
# ============================================================================
# AID 统一部署管理脚本（菜单式，Docker 与手动部署通用）
#
# 用法：
#   sudo bash aid.sh              # 交互菜单（首次部署按版本标签拉取三端源码并构建）
#   sudo bash aid.sh <子命令>     # 直通执行：install/auto/install-docker/install-manual/update/rollback/
#                                 # restart/stop/status/logs/config/backup/setup-updater
#
# 设计：
#   - 全部数据统一放在 DATA_ROOT（默认 /data/aid）：程序、上传文件、日志、
#     中间件数据、备份、源码构建缓存
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
COMPOSE_DIR="${SCRIPT_DIR}/docker"
CONFIG_ROOT="${DATA_ROOT}/config"
DEPLOYMENT_DESCRIPTOR="${CONFIG_ROOT}/deployment.json"
STATE_FILE="${CONFIG_ROOT}/install-state.conf"
DEFAULT_MANUAL_CONFIG="${DATA_ROOT}/aid-deploy.conf"
DEFAULT_DOCKER_CONFIG="${COMPOSE_DIR}/.env"
descriptorMode=""
descriptorPath=""
if [[ -f "${DEPLOYMENT_DESCRIPTOR}" ]]; then
  descriptorMode="$(grep -E '"mode"[[:space:]]*:' "${DEPLOYMENT_DESCRIPTOR}" 2>/dev/null | head -n 1 | sed -E 's/.*:[[:space:]]*"([^"]+)".*/\1/')"
  descriptorPath="$(grep -E '"configPath"[[:space:]]*:' "${DEPLOYMENT_DESCRIPTOR}" 2>/dev/null | head -n 1 | sed -E 's/.*:[[:space:]]*"([^"]+)".*/\1/')"
fi
CONF="${DEFAULT_MANUAL_CONFIG}"
ENV_FILE="${DEFAULT_DOCKER_CONFIG}"
if [[ ( "${descriptorMode}" == "manual" || "${descriptorMode}" == "systemd" ) && "${descriptorPath}" == /* ]]; then CONF="${descriptorPath}"; fi
if [[ "${descriptorMode}" == "docker" && "${descriptorPath}" == /* ]]; then ENV_FILE="${descriptorPath}"; fi
# 单文件首次启动时 deploy/docker 尚未落盘，先把配置放进数据目录的受控配置区；
# 安装器落盘后仍通过 deployment.json 读取同一文件，不会生成第二份配置。
if [[ ! -d "${COMPOSE_DIR}" && ! -f "${DEPLOYMENT_DESCRIPTOR}" ]]; then
  ENV_FILE="${CONFIG_ROOT}/docker.env"
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
  DEFAULT_DOCKER_CONFIG="${COMPOSE_DIR}/.env"
  if [[ "${descriptorMode}" != "docker" && -f "${DEFAULT_DOCKER_CONFIG}" ]]; then
    ENV_FILE="${DEFAULT_DOCKER_CONFIG}"
  fi
fi
DOWNLOAD_TIMEOUT_SECONDS="${AID_DOWNLOAD_TIMEOUT_SECONDS:-1800}"
SOURCE_BUILDER_NAME="build-release-from-source.sh"
SOURCE_GIT_IMAGE="${AID_GIT_IMAGE:-alpine/git:2.47.2}"
SOURCE_MAVEN_IMAGE="${AID_MAVEN_IMAGE:-maven:3.9.9-eclipse-temurin-17}"
SOURCE_NODE_IMAGE="${AID_NODE_IMAGE:-node:22.22.0-bookworm-slim}"
SOURCE_GO_IMAGE="${AID_GO_IMAGE:-golang:1.22.12-bookworm}"
DOCKER_CN_MIRROR="${AID_DOCKER_CN_MIRROR:-docker.m.daocloud.io}"
IMAGE_PULL_TIMEOUT_SECONDS="${AID_IMAGE_PULL_TIMEOUT_SECONDS:-900}"
JDK_VERSION="17.0.20"
JDK_BUILD="8"
JDK_HOME=""
NODE_VERSION="22.22.0"
NODE_HOME=""
MAVEN_VERSION="3.9.9"
MAVEN_HOME=""
GO_VERSION="1.22.12"
GO_HOME=""
JAVA_RUNTIME_IMAGE="aid/openjdk:17.0.20"
DEFAULT_ADMIN_ENTRY_CODE=""
OS_PACKAGE_INDEX_READY=0

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

# Docker 部署配置真源：默认 deploy/docker/.env；单文件首次部署或后台迁移时可位于 DATA_ROOT/config。
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

write_deployment_descriptor() { # write_deployment_descriptor <docker|manual> <配置绝对路径>
  local mode="$1" configPath="$2" tmp
  [[ "${configPath}" == /* ]] || die "部署配置路径必须是绝对路径"
  mkdir -p "${CONFIG_ROOT}"
  tmp="$(mktemp "${CONFIG_ROOT}/.deployment.XXXXXX")"
  cat > "${tmp}" <<EOF
{
  "mode": "${mode}",
  "configPath": "${configPath}"
}
EOF
  chmod 600 "${tmp}"
  mv -f "${tmp}" "${DEPLOYMENT_DESCRIPTOR}"
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
  local dbHost dbPort redisUser redisPwd mqAccessKey mqSecretKey domain adminDomain certPath keyPath
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
  redisUser="$(env_get REDIS_USERNAME '')"; redisPwd="$(env_get REDIS_PASSWORD '')"
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
    # Docker 全栈：MySQL(~1.5G) + Redis(0.5G) + 后端JVM(~2.5G) + Node SSR(~0.4G) + Nginx + 系统(~1G)
    minCpu=2; minMem=$((4 * 1024 - 512)); recCpu=4; recMem=$((8 * 1024 - 512)); minDisk=40
  else
    # 手动部署：中间件可能在本机也可能在别机，按"后端+SSR在本机"计算下限
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

require_docker_runtime() {
  if ! command -v docker >/dev/null 2>&1; then
    risk "未检测到 Docker Engine；AID 不会自动安装 Docker，也不会修改服务器软件源"
    die "请管理员安装 Docker Engine 24+ 与 Compose v2 插件，确认 docker version 正常后重新运行"
  fi
  docker info >/dev/null 2>&1 \
    || die "Docker 已安装但守护进程未运行，请先启动 Docker 服务后重试"
  docker compose version >/dev/null 2>&1 \
    || die "未检测到 docker compose v2 插件，请安装 Compose 插件后重试（脚本不会自动安装）"
  ok "Docker 运行环境可用；Git/JDK/Nginx/Redis 将按构建流程与 COMPOSE_PROFILES 使用容器"
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
    case "${descriptorMode}" in
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

dockerhub_mirror_image() { # dockerhub_mirror_image <标准镜像>
  local image="$1" first="${1%%/*}"
  if [[ "${image}" == */* ]]; then
    [[ "${first}" != *.* && "${first}" != *:* && "${first}" != "localhost" ]] || return 1
    echo "${DOCKER_CN_MIRROR}/${image}"
  else
    echo "${DOCKER_CN_MIRROR}/library/${image}"
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
    node:22.22.0-alpine) echo 'sha256:e4bf2a82ad0a4037d28035ae71529873c069b13eb0455466ae0bc13363826e34' ;;
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

ensure_docker_image() { # ensure_docker_image <镜像> <用途>
  local image="$1" label="$2" installMode="${AID_DEPENDENCY_INSTALL_MODE:-auto}" mirrorImage=""
  local digest="" officialRef="" mirrorRef=""
  digest="$(docker_image_digest "${image}" 2>/dev/null || true)"
  if docker image inspect "${image}" >/dev/null 2>&1; then
    if [[ -z "${digest}" ]] || local_image_matches_digest "${image}" "${digest}"; then
      ok "${label}镜像已存在，跳过下载: ${image}"
      return 0
    fi
    warn "${label}本地镜像摘要不符合当前发布清单，将重新拉取已固定版本: ${image}"
  fi
  resolve_dependency_region
  mirrorImage="$(dockerhub_mirror_image "${image}" 2>/dev/null || true)"
  if [[ -n "${digest}" ]]; then
    officialRef="$(image_with_digest "${image}" "${digest}")"
    # 部分国内代理可按标签返回正确官方摘要，但不支持直接使用清单摘要作为 URL。
    # 因此镜像站按标签拉取，完成后必须核对 RepoDigest，匹配才允许映射为标准名称。
    mirrorRef="${mirrorImage}"
  else
    officialRef="${image}"
    mirrorRef="${mirrorImage}"
    warn "${label}使用了自定义或未固定镜像，无法与官方发布摘要核对: ${image}"
  fi
  if [[ -n "${mirrorImage}" ]] && docker image inspect "${mirrorImage}" >/dev/null 2>&1 \
      && { [[ -z "${digest}" ]] || local_image_matches_digest "${mirrorImage}" "${digest}"; }; then
    docker tag "${mirrorImage}" "${image}" || die "${label}镜像名称映射失败: ${image}"
    ok "${label}国内镜像已存在，已映射为标准名称: ${image}"
    return 0
  fi
  if [[ "${installMode}" == "manual" ]]; then
    die "缺少${label}镜像 ${image}；请手动拉取后重试，或把 DEPENDENCY_INSTALL_MODE 改为 auto"
  fi
  if [[ -n "${mirrorRef}" && "${RESOLVED_DEPENDENCY_REGION}" == "cn" ]]; then
    log "通过国内镜像下载${label}: ${mirrorRef}"
    if pull_docker_image "${mirrorRef}"; then
      if [[ -z "${digest}" ]] || local_image_matches_digest "${mirrorRef}" "${digest}"; then
        docker tag "${mirrorRef}" "${image}" || die "${label}镜像名称映射失败: ${image}"
        return 0
      fi
      warn "${label}国内镜像摘要与官方发布清单不一致，已拒绝使用"
      docker image rm "${mirrorRef}" >/dev/null 2>&1 || true
    fi
    warn "国内镜像下载失败，自动回退官方地址: ${officialRef}"
  fi
  log "通过官方地址下载${label}: ${officialRef}"
  if pull_docker_image "${officialRef}"; then
    [[ "${officialRef}" == "${image}" ]] || docker tag "${officialRef}" "${image}" \
      || die "${label}镜像名称映射失败: ${image}"
    return 0
  fi
  if [[ -n "${mirrorRef}" && "${RESOLVED_DEPENDENCY_REGION}" != "cn" ]]; then
    warn "官方地址下载失败，自动回退国内镜像: ${mirrorRef}"
    if pull_docker_image "${mirrorRef}"; then
      if [[ -z "${digest}" ]] || local_image_matches_digest "${mirrorRef}" "${digest}"; then
        docker tag "${mirrorRef}" "${image}" || die "${label}镜像名称映射失败: ${image}"
        return 0
      fi
      warn "${label}国内镜像摘要与官方发布清单不一致，已拒绝使用"
      docker image rm "${mirrorRef}" >/dev/null 2>&1 || true
    fi
  fi
  die "${label}镜像下载失败；官方地址和备用镜像均不可用: ${image}"
}

prepare_source_build_images() {
  command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 || return 0
  [[ -n "$(command -v git 2>/dev/null || true)" ]] || ensure_docker_image "${SOURCE_GIT_IMAGE}" "Git源码拉取"
  ensure_docker_image "${SOURCE_MAVEN_IMAGE}" "Maven构建基础"
  ensure_docker_image "${SOURCE_NODE_IMAGE}" "Node.js 22.22.0构建"
  ensure_docker_image "${SOURCE_GO_IMAGE}" "Go构建"
}

prepare_docker_runtime_images() {
  prepare_jdk_runtime_image
  ensure_docker_image "node:22.22.0-alpine" "Web运行时"
  ensure_docker_image "nginx:1.25-alpine" "Nginx网关"
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

version_at_least() { # version_at_least <当前版本> <最低版本>
  local current="$1" required="$2" first
  first="$(printf '%s\n%s\n' "${required}" "${current}" | sort -V | head -n 1)"
  [[ "${first}" == "${required}" ]]
}

tcp_reachable() { # tcp_reachable <host> <port>
  command -v timeout >/dev/null 2>&1 \
    && timeout 3 bash -c 'exec 3<>/dev/tcp/"$1"/"$2"' _ "$1" "$2" >/dev/null 2>&1
}

ensure_manual_host_dependencies() {
  local installMode redisHost redisPort redisUser redisPwd redisVersion redisMajor
  installMode="$(dependency_install_mode manual)"
  export AID_DEPENDENCY_INSTALL_MODE="${installMode}"
  command -v systemctl >/dev/null 2>&1 || die "手动部署要求使用 systemd"

  prepare_exact_jdk
  prepare_exact_node

  ensure_host_command mysql "MySQL客户端" "default-mysql-client" "mariadb" "${installMode}"
  ensure_host_command nginx "Nginx" "nginx" "nginx" "${installMode}"
  if ! systemctl is-active --quiet nginx; then
    [[ "${installMode}" == "auto" ]] || die "Nginx 未运行，请启动后重试"
    systemctl enable --now nginx >/dev/null 2>&1 || die "Nginx 自动启动失败，请执行 systemctl status nginx 排查"
    ok "Nginx 已启用并启动"
  fi

  # 有可用 Docker 时源码构建全部走容器，不要求宿主机重复安装 Git/Maven/Go。
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    prepare_source_build_images
  else
    ensure_host_command git "Git" "git" "git" "${installMode}"
    prepare_exact_maven
    prepare_exact_go
  fi

  # 手动部署只有配置为本机 Redis 时才负责检查/可选安装；外部 Redis 永不改本机。
  redisHost="$(conf_get REDIS_HOST 127.0.0.1)"; redisPort="$(conf_get REDIS_PORT 6379)"
  redisUser="$(conf_get REDIS_USERNAME '')"; redisPwd="$(conf_get REDIS_PASSWORD '')"
  case "${redisHost}" in
    127.0.0.1|localhost)
      if ! tcp_reachable "${redisHost}" "${redisPort}"; then
        [[ "${installMode}" == "auto" ]] || die "本机 Redis ${redisHost}:${redisPort} 不可用，请启动后重试"
        [[ -z "${redisUser}" && -z "${redisPwd}" ]] \
          || die "配置了 Redis 认证，脚本不会覆盖本机 Redis 安全配置；请人工配置并启动"
        ensure_host_command redis-server "Redis" "redis-server" "redis" "${installMode}"
        redisVersion="$(redis-server --version 2>/dev/null | sed -E 's/.*v=([0-9]+(\.[0-9]+)*).*/\1/')"
        redisMajor="${redisVersion%%.*}"
        [[ "${redisMajor}" =~ ^[0-9]+$ && "${redisMajor}" -ge 6 ]] \
          || die "系统软件源中的 Redis 版本过低（当前${redisVersion:-未知}，需要6+），请人工升级或使用外部Redis"
        systemctl enable --now redis-server >/dev/null 2>&1 \
          || systemctl enable --now redis >/dev/null 2>&1 \
          || die "Redis 自动启动失败，请检查 systemctl status redis"
        tcp_reachable "${redisHost}" "${redisPort}" || die "Redis 已安装但端口仍不可用"
        ok "本机 Redis 已安装并启动"
      else
        ok "Redis ${redisHost}:${redisPort} 已可用，跳过安装"
      fi ;;
    *) ok "已配置外部 Redis ${redisHost}:${redisPort}，不会安装本机 Redis" ;;
  esac
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

prepare_exact_jdk() {
  local arch checksum name cacheDir archive actual officialUrl cnUrl downloaded="no" url tmp installMode
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
  if [[ -x "${JDK_HOME}/bin/java" ]] \
      && "${JDK_HOME}/bin/java" -version 2>&1 | head -n 1 | grep -Fq '17.0.20'; then
    ok "Temurin OpenJDK ${JDK_VERSION} 已存在，跳过下载: ${JDK_HOME}"
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
    for url in "${urls[@]}"; do
      if try_download "${url}" "${archive}" "Temurin OpenJDK ${JDK_VERSION}（${arch}）"; then
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
  if [[ ! -x "${tmp}/bin/java" ]] \
      || ! "${tmp}/bin/java" -version 2>&1 | head -n 1 | grep -Fq '17.0.20'; then
    rm -rf -- "${tmp}"
    die "OpenJDK 实际版本不是17.0.20"
  fi
  rm -rf -- "${JDK_HOME}"
  mv "${tmp}" "${JDK_HOME}" || die "OpenJDK 安装目录就位失败"
  ok "Temurin OpenJDK ${JDK_VERSION} 已通过官方 SHA256 校验: ${JDK_HOME}"
}

prepare_exact_node() {
  local arch checksum name cacheDir archive actual officialUrl cnUrl downloaded="no" url tmp installMode
  case "$(uname -m)" in
    x86_64|amd64)
      arch=x64
      checksum=c33c39ed9c80deddde77c960d00119918b9e352426fd604ba41638d6526a4744 ;;
    aarch64|arm64)
      arch=arm64
      checksum=25ba95dfb96871fa2ef977f11f95ea90818c8fa15c0f2110771db08d4ba423be ;;
    *) die "Node.js ${NODE_VERSION} 暂不支持当前架构: $(uname -m)" ;;
  esac
  name="node-v${NODE_VERSION}-linux-${arch}.tar.gz"
  cacheDir="${DATA_ROOT}/build-cache/toolchains"
  archive="${cacheDir}/${name}"
  NODE_HOME="${cacheDir}/node-${NODE_VERSION}-${arch}"
  if [[ -x "${NODE_HOME}/bin/node" && "$("${NODE_HOME}/bin/node" -v 2>/dev/null)" == "v${NODE_VERSION}" ]]; then
    export PATH="${NODE_HOME}/bin:${PATH}"
    ok "Node.js ${NODE_VERSION} 已存在，跳过下载: ${NODE_HOME}"
    return 0
  fi
  require_download_tools
  mkdir -p "${cacheDir}"
  if [[ -f "${archive}" && "$(sha256_file "${archive}" || true)" != "${checksum}" ]]; then
    warn "Node.js 缓存校验失败，将重新下载"
    rm -f -- "${archive}"
  fi
  if [[ ! -f "${archive}" ]]; then
    installMode="${AID_DEPENDENCY_INSTALL_MODE:-auto}"
    [[ "${installMode}" == "auto" ]] \
      || die "缺少 Node.js ${NODE_VERSION}；请放入 ${archive}，或把 DEPENDENCY_INSTALL_MODE 改为 auto"
    resolve_dependency_region
    officialUrl="https://nodejs.org/dist/v${NODE_VERSION}/${name}"
    cnUrl="https://npmmirror.com/mirrors/node/v${NODE_VERSION}/${name}"
    local -a urls=()
    [[ -z "${AID_NODE_DOWNLOAD_URL:-}" ]] || urls+=("${AID_NODE_DOWNLOAD_URL}")
    if [[ "${RESOLVED_DEPENDENCY_REGION}" == "cn" ]]; then urls+=("${cnUrl}" "${officialUrl}"); else urls+=("${officialUrl}" "${cnUrl}"); fi
    for url in "${urls[@]}"; do
      if try_download "${url}" "${archive}" "Node.js ${NODE_VERSION}（${arch}）" \
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
  tar -xzf "${archive}" -C "${tmp}" --strip-components=1 || { rm -rf -- "${tmp}"; die "Node.js 压缩包解压失败"; }
  [[ "$("${tmp}/bin/node" -v 2>/dev/null)" == "v${NODE_VERSION}" ]] \
    || { rm -rf -- "${tmp}"; die "Node.js 实际版本不是 ${NODE_VERSION}"; }
  rm -rf -- "${NODE_HOME}"; mv "${tmp}" "${NODE_HOME}" || die "Node.js 安装目录就位失败"
  export PATH="${NODE_HOME}/bin:${PATH}"
  ok "Node.js ${NODE_VERSION} 已通过官方 SHA256 校验: ${NODE_HOME}"
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
    for url in "${urls[@]}"; do
      if try_download "${url}" "${archive}" "Maven ${MAVEN_VERSION}" \
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
    for url in "${urls[@]}"; do
      if try_download "${url}" "${archive}" "Go ${GO_VERSION}（${arch}）" \
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

prepare_jdk_runtime_image() {
  local baseImage="debian:bookworm-slim" dockerfile actual
  prepare_exact_jdk
  if docker image inspect "${JAVA_RUNTIME_IMAGE}" >/dev/null 2>&1; then
    actual="$(docker run --rm "${JAVA_RUNTIME_IMAGE}" java -version 2>&1 | head -n 1 || true)"
    if [[ "${actual}" == *'17.0.20'* ]]; then
      ok "OpenJDK ${JDK_VERSION} 运行镜像已存在，跳过构建: ${JAVA_RUNTIME_IMAGE}"
      return 0
    fi
    warn "现有 Java 运行镜像版本不正确，将用已校验的 OpenJDK ${JDK_VERSION} 重建"
  fi
  ensure_docker_image "${baseImage}" "OpenJDK运行基础"
  dockerfile="${DATA_ROOT}/build-cache/toolchains/Dockerfile.openjdk-${JDK_VERSION}"
  cat > "${dockerfile}" <<EOF
FROM ${baseImage}
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=/opt/java/openjdk/bin:\${PATH}
COPY . /opt/java/openjdk/
RUN java -version
EOF
  log "构建固定版本 Java 运行镜像: ${JAVA_RUNTIME_IMAGE}"
  docker build --pull=false --tag "${JAVA_RUNTIME_IMAGE}" --file "${dockerfile}" "${JDK_HOME}" \
    || die "OpenJDK ${JDK_VERSION} 运行镜像构建失败"
  actual="$(docker run --rm "${JAVA_RUNTIME_IMAGE}" java -version 2>&1 | head -n 1 || true)"
  [[ "${actual}" == *'17.0.20'* ]] || die "OpenJDK运行镜像版本校验失败"
  ok "OpenJDK ${JDK_VERSION} 运行镜像已就绪"
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
  grep -Eq '(^|/)web-dist/server/index\.mjs$' "${listFile}" || { rm -f "${listFile}"; die "发布包缺少 Web SSR 产物"; }
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
    for entry in installer/deploy/aid.sh installer/deploy/docker/.env.example \
        installer/deploy/docker/docker-compose.yml installer/deploy/docker/nginx/aid-https.conf.template \
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

bootstrap_source_builder() {
  local builder="${SCRIPT_DIR}/${SOURCE_BUILDER_NAME}" tmpDir base repoUrl cloned sourceRef remoteRef
  if [[ -f "${builder}" ]]; then
    SOURCE_BUILDER_PATH="${builder}"
    return 0
  fi
  if ! command -v git >/dev/null 2>&1; then
    command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 \
      || die "源码构建需要 Git 或可用的 Docker Engine；Docker 部署请先启动 Docker，手动部署可安装 Git"
    ensure_docker_image "${SOURCE_GIT_IMAGE}" "Git源码拉取"
    warn "宿主机未安装 Git，将使用隔离的 ${SOURCE_GIT_IMAGE} 容器拉取源码，不会修改系统软件"
  fi
  tmpDir="$(mktemp -d)"
  for sourceRef in "v${RESOLVED_VERSION}" master; do
    [[ "${sourceRef}" == "master" ]] \
      && warn "目标版本尚未内置源码构建器，改从公开 master 获取构建工具；业务源码仍固定使用 v${RESOLVED_VERSION} 标签"
    remoteRef="refs/tags/${sourceRef}"
    [[ "${sourceRef}" == "master" ]] && remoteRef="refs/heads/master"
    for base in https://gitee.com/gzxx-2025 https://github.com/gzxx-2025; do
      rm -rf "${tmpDir}/server"
      log "获取源码构建器 ${sourceRef}: ${base}"
      repoUrl="${base}/aid-server.git"
      cloned="no"
      if command -v git >/dev/null 2>&1; then
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
      elif command -v timeout >/dev/null 2>&1; then
        if timeout 240 docker run --rm --user "$(id -u):$(id -g)" -v "${tmpDir}:/work" -w /work \
            "${SOURCE_GIT_IMAGE}" clone --depth 1 --single-branch --branch "${sourceRef}" \
            "${repoUrl}" server >/dev/null 2>&1; then
          cloned="yes"
        fi
      elif docker run --rm --user "$(id -u):$(id -g)" -v "${tmpDir}:/work" -w /work \
          "${SOURCE_GIT_IMAGE}" clone --depth 1 --single-branch --branch "${sourceRef}" \
          "${repoUrl}" server >/dev/null 2>&1; then
        cloned="yes"
      fi
      if [[ "${cloned}" == "yes" && -f "${tmpDir}/server/deploy/${SOURCE_BUILDER_NAME}" ]]; then
        mkdir -p "${DATA_ROOT}/packages"
        builder="${DATA_ROOT}/packages/${SOURCE_BUILDER_NAME}"
        install -m 0755 "${tmpDir}/server/deploy/${SOURCE_BUILDER_NAME}" "${builder}"
        rm -rf "${tmpDir}"
        SOURCE_BUILDER_PATH="${builder}"
        return 0
      fi
    done
  done
  rm -rf "${tmpDir}"
  die "版本 v${RESOLVED_VERSION} 缺少源码构建器，或 Gitee/GitHub 均不可访问"
}

ensure_source_package() {
  mkdir -p "${DATA_ROOT}/packages"
  RESOLVED_PACKAGE_PATH="${DATA_ROOT}/packages/aid-v${RESOLVED_VERSION}.tar.gz"
  local builder actual checksumFile ownerMode owner modeBits buildLog buildStamp
  local -a buildStatuses
  checksumFile="${RESOLVED_PACKAGE_PATH}.sha256"
  if [[ -f "${RESOLVED_PACKAGE_PATH}" && -f "${checksumFile}" && "${AID_FORCE_SOURCE_REBUILD:-0}" != "1" ]]; then
    actual="$(sha256_file "${RESOLVED_PACKAGE_PATH}" || true)"
    ownerMode="$(stat -c '%u:%a' "${RESOLVED_PACKAGE_PATH}" 2>/dev/null || true)"
    owner="${ownerMode%%:*}"
    modeBits="${ownerMode#*:}"
    if [[ "${actual}" == "$(awk '{print tolower($1)}' "${checksumFile}" 2>/dev/null)" \
        && "${owner}" == "0" && "${modeBits}" =~ ^[0-7]+$ ]] \
        && (( (8#${modeBits} & 8#022) == 0 )) \
        && package_is_source_build "${RESOLVED_PACKAGE_PATH}"; then
      validate_release_package "${RESOLVED_PACKAGE_PATH}" no
      ok "复用 root 权限保护且校验通过的源码构建包: ${RESOLVED_PACKAGE_PATH}"
      RESOLVED_PACKAGE_SHA256="${actual}"
      return 0
    fi
    risk "源码构建缓存校验或权限不安全，将重新构建，不会使用旧缓存"
    rm -f "${RESOLVED_PACKAGE_PATH}" "${checksumFile}"
  fi

  bootstrap_source_builder
  prepare_source_build_images
  builder="${SOURCE_BUILDER_PATH}"
  section "远程源码构建 AID v${RESOLVED_VERSION}"
  warn "只拉取三个公开仓库的 v${RESOLVED_VERSION} 标签；优先 Gitee，失败时整组回退到 GitHub"
  warn "首次构建需要下载 Maven/npm/Go 依赖及构建镜像，请预留至少 15GB 磁盘与足够时间"
  mkdir -p "${DATA_ROOT}/logs" || die "无法创建构建日志目录: ${DATA_ROOT}/logs"
  buildStamp="$(date '+%Y%m%d-%H%M%S')"
  buildLog="${DATA_ROOT}/logs/source-build-v${RESOLVED_VERSION}-${buildStamp}.log"
  : > "${buildLog}" || die "无法创建三端构建日志: ${buildLog}"
  chmod 600 "${buildLog}" 2>/dev/null || true
  log "三端编译实时日志将同时保存到: ${buildLog}"
  AID_DATA_ROOT="${DATA_ROOT}" AID_MANIFEST_PUBLIC_KEY="${TRUSTED_MANIFEST_PUBLIC_KEY}" \
    AID_DEPENDENCY_REGION="$(dependency_region_setting)" \
    AID_MANAGER_SCRIPT="${SCRIPT_DIR}/$(basename "${BASH_SOURCE[0]}")" \
    sh "${builder}" --version "${RESOLVED_VERSION}" --output "${RESOLVED_PACKAGE_PATH}" \
      --work-dir "${DATA_ROOT}/source-build/v${RESOLVED_VERSION}" 2>&1 | tee -a "${buildLog}"
  buildStatuses=( "${PIPESTATUS[@]}" )
  if (( buildStatuses[0] != 0 )); then
    die "三端源码构建失败；现有部署未被修改，请查看日志 ${buildLog}"
  fi
  if (( buildStatuses[1] != 0 )); then
    die "三端源码构建日志写入失败；现有部署未被修改，请检查磁盘空间: ${buildLog}"
  fi
  validate_release_package "${RESOLVED_PACKAGE_PATH}" no
  actual="$(sha256_file "${RESOLVED_PACKAGE_PATH}" || true)"
  [[ -n "${actual}" ]] || die "无法计算源码构建包 SHA256"
  printf '%s  %s\n' "${actual}" "$(basename "${RESOLVED_PACKAGE_PATH}")" > "${checksumFile}"
  chmod 600 "${RESOLVED_PACKAGE_PATH}" "${checksumFile}" 2>/dev/null || true
  RESOLVED_PACKAGE_SHA256="${actual}"
  ok "三端源码构建、包结构与本地 SHA256 校验通过；完整日志: ${buildLog}"
}

deployment_runtime_ready() {
  [[ -f "${SCRIPT_DIR}/aid-deploy.conf.example" \
     && -f "${COMPOSE_DIR}/.env.example" \
     && -f "${COMPOSE_DIR}/docker-compose.yml" \
     && -f "${REPO_DIR}/sql/aid-init.sql" ]]
}

handoff_to_managed_installer() { # handoff_to_managed_installer <原始参数...>
  local managedDir
  if [[ "${AID_REMOTE_BOOTSTRAP:-0}" == "1" ]]; then
    ok "已启用远程最新引导脚本，不切换到旧版受管 aid.sh"
    return 0
  fi
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
  export AID_TRUSTED_SOURCE_PACKAGE=1
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

confirm_first_install() { # confirm_first_install <docker|manual>
  local mode="$1" answer defaultAnswer="y"
  section "首次部署确认"
  echo -e "  部署方式 : ${C_GREEN}${mode}${C_RESET}"
  echo -e "  目标版本 : ${C_GREEN}${RESOLVED_VERSION}${C_RESET} (${RESOLVED_CHANNEL})"
  echo "  数据目录 : ${DATA_ROOT}"
  if [[ "${mode}" == "docker" ]]; then
    echo "  用户端口 : $(env_get HTTP_PORT 80)"
    echo "  管理端口 : $(env_get ADMIN_PORT 8089)"
    echo "  后端端口 : $(env_get BACKEND_PORT 8080)"
  fi
  warn "安装会拉取 Docker 镜像、创建服务并占用以上端口；不会自动开放防火墙或修改域名解析"
  risk "后台初始账号为 admin / admin123，首次登录后必须立即修改密码"
  warn "官方媒体资产包体积较大且存储目标因人而异，本步骤不会静默下载或写入 OSS/COS"
  if [[ -d "${DATA_ROOT}" ]] && find "${DATA_ROOT}" -mindepth 1 -maxdepth 1 \
      ! -name packages ! -name installer ! -name config ! -name aid-deploy.conf -print -quit 2>/dev/null | grep -q .; then
    risk "${DATA_ROOT} 已存在非安装缓存内容；继续前请确认这里不是其他业务的数据目录"
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
#WEB_NODE_OPTIONS=--max-old-space-size=1024
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
DB_USERNAME=root
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
DB_USERNAME=root
# 手动部署必须填写现有数据库账号的真实密码。
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
        env_set "${key}" "$(gen_secret)"
        ok "${key} 留空，已为内置 MySQL 生成强随机值写入 .env"
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
  if [[ "${envDataRoot}" != "${DATA_ROOT}" ]]; then
    warn ".env 的 DATA_ROOT(${envDataRoot}) 与脚本数据目录(${DATA_ROOT}) 不一致，以 .env 为准需同步设置 AID_DATA_ROOT 环境变量"
  fi
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
    COMPOSE_PROFILES="${profiles}" docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_DIR}/docker-compose.yml" "$@"
    return $?
  fi
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_DIR}/docker-compose.yml" "$@"
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
      && die "HTTPS 容器状态异常: ${status}，请查看 docker logs aid-nginx-https"
    sleep 2; elapsed=$((elapsed + 2))
  done
  die "HTTPS 容器健康检查超时，请查看 docker logs aid-nginx-https"
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
    restartServices='["aid-web"]'
    healthUrl="http://127.0.0.1:$(conf_get BACKEND_PORT 8080)"
    execContainer=""; clientImage=""; dockerNetwork=""
    dbHost="$(conf_get DB_HOST 127.0.0.1)"; dbPort="$(conf_get DB_PORT 3306)"
    dbUser="$(conf_get DB_USERNAME root)"; dbPwd="$(conf_get DB_PASSWORD '')"
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

# Docker 模式统一数据库客户端：内置库经 aid-mysql 执行；外部库使用一次性
# mysql:5.7 客户端容器，不要求宿主机或升级器容器安装 mysql/mysqldump。
docker_mysql_tool() { # docker_mysql_tool <mysql|mysqldump> [参数...]
  local tool="$1" AID_DEPENDENCY_INSTALL_MODE
  shift
  if docker_profile_enabled mysql; then
    MYSQL_PWD="$(env_get MYSQL_ROOT_PASSWORD '')" \
      docker exec -i -e MYSQL_PWD aid-mysql "${tool}" \
        --host 127.0.0.1 --port 3306 --user root "$@"
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
    rows="$(docker_mysql_tool mysql --batch --skip-column-names "${dbName}" --execute "${query}" 2>/dev/null)" || return 0
  else
    rows="$(MYSQL_PWD="$(conf_get DB_PASSWORD '')" mysql \
      --host "$(conf_get DB_HOST 127.0.0.1)" --port "$(conf_get DB_PORT 3306)" \
      --user "$(conf_get DB_USERNAME root)" --database "${dbName}" \
      --batch --skip-column-names --execute "${query}" 2>/dev/null)" || return 0
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
      --host "$(conf_get DB_HOST 127.0.0.1)" --port "$(conf_get DB_PORT 3306)" \
      --user "$(conf_get DB_USERNAME root)" --database "${dbName}" \
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
      --host "$(conf_get DB_HOST 127.0.0.1)" --port "$(conf_get DB_PORT 3306)" \
      --user "$(conf_get DB_USERNAME root)" --database "${dbName}" --execute "${query}" >/dev/null \
      || die "写入后台随机访问码失败"
  fi
  ok "已为新部署生成12位随机后台访问码"
}

# 确保 MySQL 就绪。外部模式只做连接及 5.7 版本校验，绝不拉起 aid-mysql。
ensure_mysql_ready() {
  local mode AID_DEPENDENCY_INSTALL_MODE; mode="${1:-$(detect_mode)}"
  if [[ "${mode}" != "docker" ]]; then return 0; fi
  AID_DEPENDENCY_INSTALL_MODE="$(dependency_install_mode docker)"
  ensure_docker_image "mysql:5.7" "MySQL5.7"
  if docker_profile_enabled mysql; then
    compose_cmd up -d mysql >/dev/null 2>&1 || true
    local deadline=$(( $(date +%s) + 90 ))
    until [[ "$(docker inspect -f '{{.State.Health.Status}}' aid-mysql 2>/dev/null)" == "healthy" ]]; do
      [[ $(date +%s) -ge ${deadline} ]] && { err "MySQL 容器未就绪"; return 1; }
      sleep 3
    done
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
    gunzip < "${dumpFile}" | docker_mysql_tool mysql --default-character-set=utf8mb4 "$(env_get DB_NAME aid)" || return 1
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
    docker_mysql_tool mysql --default-character-set=utf8mb4 "$(env_get DB_NAME aid)" < "${sqlFile}" || return 1
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

print_access_info() {
  local mode configFile adminPort adminPath
  mode="$(detect_mode)"
  if [[ "${mode}" == "docker" ]]; then configFile="${ENV_FILE}"; else configFile="${CONF}"; fi
  adminPort="$(setting_get ADMIN_PORT 8089)"
  read_admin_entry_settings
  if [[ "${ADMIN_ENTRY_ENABLED_VALUE}" =~ ^(true|TRUE|Y|1)$ && -n "${ADMIN_ENTRY_CODE_VALUE}" ]]; then
    adminPath="/${ADMIN_ENTRY_CODE_VALUE}"
  else
    adminPath="/login"
  fi
  echo ""
  echo -e "${C_GREEN}=================== 操作完成 ===================${C_RESET}"
  echo "访问地址:"
  echo "  管理端登录入口: http://服务器IP:${adminPort}${adminPath}"
  echo "  管理端本机示例: http://localhost:${adminPort}${adminPath}"
  echo "  管理端默认账号: admin / admin123（首次登录后立即修改密码）"
  echo "  用户端: http://服务器IP:$(setting_get HTTP_PORT 80)/"
  if [[ "${mode}" == "docker" ]] && docker_profile_enabled https; then
    local httpsPort httpsPortSuffix=""
    httpsPort="$(env_get HTTPS_PORT 443)"
    [[ "${httpsPort}" == "443" ]] || httpsPortSuffix=":${httpsPort}"
    echo "  HTTPS用户端: https://$(env_get HTTPS_PUBLIC_DOMAIN)${httpsPortSuffix}/"
    echo "  HTTPS管理端: https://$(env_get HTTPS_ADMIN_DOMAIN)${httpsPortSuffix}${adminPath}"
  elif [[ "${mode}" == "manual" && "$(conf_get HTTPS_ENABLED false)" == "true" ]]; then
    local httpsPort httpsPortSuffix=""
    httpsPort="$(conf_get HTTPS_PORT 443)"
    [[ "${httpsPort}" == "443" ]] || httpsPortSuffix=":${httpsPort}"
    echo "  HTTPS用户端: https://$(conf_get HTTPS_PUBLIC_DOMAIN)${httpsPortSuffix}/"
    echo "  HTTPS管理端: https://$(conf_get HTTPS_ADMIN_DOMAIN)${httpsPortSuffix}${adminPath}"
  fi
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
  require_docker_runtime
  if [[ "$(uname -m)" == "aarch64" ]]; then
    die "Docker 一键部署固定使用 MySQL 5.7，官方镜像不支持 ARM64；请改用 x86_64 服务器"
  fi
  prepare_install_package "${1:-}"
  package="${RESOLVED_PACKAGE_PATH}"
  prepare_docker_runtime_images
  bootstrap_installer_if_needed "${package}" install-docker

  [[ "${existingMode}" == "none" ]] && confirm_first_install docker
  # 外部 MySQL 必须在启动业务容器前完成连通性、版本和库结构校验；校验成功后
  # 才停用可能存在的内置容器，且始终保留 mysql-data 目录。
  disable_internal_mysql_for_external \
    || die "外部 MySQL 未准备完成，部署已中止且不会启动内置 MySQL"
  ensure_mysql_ready docker || die "数据库未就绪，部署已中止"
  ensure_admin_entry_code docker
  disable_unused_docker_services

  # 硬件校验基线按 .env 实际配置评估：profiles 含 mq 才计入内置 MQ 内存
  local mqPlan="no"
  docker_profile_enabled mq && mqPlan="yes"
  check_hardware docker "${mqPlan}"

  place_artifacts "${package}"
  # 升级器配置先于容器编排就位，aid-updater 容器首次拉起即可正常运行
  if [[ -f "${DATA_ROOT}/app/updater/aid-updater" ]]; then
    write_updater_config docker
  fi
  # RocketMQ 数据目录属主（镜像内 rocketmq 用户 uid=3000）；profiles 含 mq 即启用了内置 MQ
  if docker_profile_enabled mq; then
    mkdir -p "${DATA_ROOT}/rocketmq/broker-data" "${DATA_ROOT}/rocketmq/broker-logs" "${DATA_ROOT}/rocketmq/namesrv-logs"
    chown -R 3000:3000 "${DATA_ROOT}/rocketmq" 2>/dev/null || true
  fi

  log "启动容器编排..."
  validate_https_runtime
  compose_cmd up -d || die "容器编排启动失败"
  if ! wait_backend_healthy; then
    if docker_profile_enabled mysql; then
      die "部署未完成；若确认是首次内置数据库初始化失败，请备份后再按日志人工处理 ${DATA_ROOT}/mysql-data"
    fi
    die "部署未完成；外部 MySQL 模式不会创建或清理本地 mysql-data，请按上方连接信息排查"
  fi
  wait_https_healthy
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
write_systemd_units() {
  local javaBin nodeBin
  [[ -x "${JDK_HOME}/bin/java" ]] || prepare_exact_jdk
  javaBin="${JDK_HOME}/bin/java"
  nodeBin="$(command -v node)"
  cat > /etc/systemd/system/aid.service <<EOF
[Unit]
Description=AID Server
After=network-online.target

[Service]
Type=simple
WorkingDirectory=${DATA_ROOT}/app
EnvironmentFile=${CONF}
Environment=AID_PROFILE=${DATA_ROOT}/uploadPath
Environment=LOG_PATH=${DATA_ROOT}/logs
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
  local httpPort adminPort backendPort content httpsPort httpsDomain httpsAdminDomain certPath keyPath
  httpPort="$(conf_get HTTP_PORT 80)"
  adminPort="$(conf_get ADMIN_PORT 8089)"
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
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$connection_upgrade;
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
  if command -v nginx >/dev/null 2>&1 && [[ -d /etc/nginx/conf.d ]]; then
    local backupPath=""
    if [[ -f /etc/nginx/conf.d/aid.conf ]]; then
      backupPath="/etc/nginx/conf.d/aid.conf.bak.$(date +%s)"
      cp /etc/nginx/conf.d/aid.conf "${backupPath}"
    fi
    echo "${content}" > /etc/nginx/conf.d/aid.conf
    if ! nginx -t >/dev/null 2>&1; then
      if [[ -n "${backupPath}" ]]; then cp "${backupPath}" /etc/nginx/conf.d/aid.conf; else rm -f /etc/nginx/conf.d/aid.conf; fi
      die "Nginx 配置校验失败，已恢复原站点配置"
    fi
    systemctl reload nginx || die "Nginx 重载失败"
    ok "Nginx 站点已生效"
  else
    [[ "$(conf_get HTTPS_ENABLED false)" != "true" ]] || die "启用 HTTPS 前必须先安装并启动 Nginx"
    echo "${content}" > "${DATA_ROOT}/aid-nginx.conf"
    warn "未检测到 nginx，站点配置已生成到 ${DATA_ROOT}/aid-nginx.conf 供手工放置"
  fi
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
  ensure_manual_host_dependencies
  prepare_install_package "${1:-}"
  package="${RESOLVED_PACKAGE_PATH}"
  bootstrap_installer_if_needed "${package}" install-manual

  [[ "${existingMode}" == "none" ]] && confirm_first_install manual
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
  ensure_admin_entry_code manual

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
do_update() {
  require_root
  local mode package supplied current target comparison go backupDir dist old f targetChannel
  mode="$(detect_mode)"
  [[ "${mode}" != "none" ]] || die "尚未部署，请先执行首次部署"
  # 先用当前远程引导脚本/受管模板补齐旧配置；仅追加缺失键，原值不变且同目录留备份。
  if [[ "${mode}" == "docker" ]]; then ensure_env_file; else ensure_conf_file; fi
  export AID_DEPENDENCY_INSTALL_MODE="$(dependency_install_mode "${mode}")"
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
  warn "后台「项目升级配置」仍是首选入口，具备签名版本校验、源码构建、SQL 历史记录和失败自动回滚"
  if [[ "${AID_ASSUME_YES:-0}" == "1" ]]; then
    risk "AID_ASSUME_YES=1：已跳过升级人工确认"
    go="y"
  else
    go="$(ask_yes_no "确认从 ${current} 更新到 ${target}？" 'n')"
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
      disable_unused_docker_services
      # 每次重启都从唯一配置真源重建升级器配置，确保数据库凭证、配置路径等
      # 与业务容器一致；随后显式重启升级器，不能依赖旧进程内存中的配置。
      if [[ "${AID_SKIP_UPDATER_RESTART:-0}" != "1" && -f "${DATA_ROOT}/app/updater/aid-updater" ]]; then
        write_updater_config docker
      fi
      # .env 由用户维护，up -d 会应用其中的变更（环境/端口/内存等按需重建容器）
      log "重启容器编排（.env 配置变更同时生效）..."
      validate_https_runtime
      compose_cmd up -d
      compose_cmd restart aid-server aid-web nginx 2>/dev/null || true
      if docker_profile_enabled https; then
        compose_cmd restart nginx-https 2>/dev/null || true
      else
        # Compose 不会自动删除已退出当前 Profile 的旧服务，关闭 HTTPS 时显式释放443。
        docker rm -f aid-nginx-https >/dev/null 2>&1 || true
      fi
      wait_https_healthy
      [[ "${AID_SKIP_UPDATER_RESTART:-0}" == "1" ]] || compose_cmd restart aid-updater 2>/dev/null || true
      ;;
    manual)
      ensure_conf_file
      export AID_DEPENDENCY_INSTALL_MODE="$(dependency_install_mode manual)"
      ensure_manual_host_dependencies
      if [[ "${AID_SKIP_UPDATER_RESTART:-0}" != "1" && -f "${DATA_ROOT}/app/updater/aid-updater" ]]; then
        write_updater_config manual
      fi
      write_systemd_units
      systemctl restart aid
      systemctl restart aid-web 2>/dev/null || true
      write_nginx_site
      [[ "${AID_SKIP_UPDATER_RESTART:-0}" == "1" ]] || systemctl restart aid-updater 2>/dev/null || true
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
      if [[ "${mode}" == "docker" ]]; then
        if docker_profile_enabled mysql; then
          docker logs -f --tail 100 aid-mysql
        else
          warn "当前使用外部 MySQL $(env_get DB_HOST):$(env_get DB_PORT 3306)，请到数据库服务端查看日志"
        fi
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
  echo "  0) 退出"
  echo "------------------------------------------------------"
}

main() {
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
