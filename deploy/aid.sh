#!/bin/bash
# ============================================================================
# AID 统一部署管理脚本（菜单式，Docker 与手动部署通用）
#
# 用法：
#   sudo bash aid.sh              # 交互菜单（首次部署按版本标签拉取三端源码并构建）
#   sudo bash aid.sh <子命令>     # 直通执行：install/auto/install-docker/install-manual/update/rollback/
#                                 # restart/stop/status/default/logs/config/backup/setup-updater/uninstall
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
DOWNLOAD_MIN_SPEED_BYTES="${AID_DOWNLOAD_MIN_SPEED_BYTES:-32768}"
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

select_existing_nginx_runtime() {
  local candidate version
  local -a candidates=()
  NGINX_HOME="${DATA_ROOT}/runtime/nginx-${NGINX_VERSION}"
  [[ -x "${NGINX_HOME}/sbin/nginx" ]] && candidates+=("${NGINX_HOME}/sbin/nginx")
  command -v nginx >/dev/null 2>&1 && candidates+=("$(command -v nginx)")
  [[ -x /www/server/nginx/sbin/nginx ]] && candidates+=(/www/server/nginx/sbin/nginx)
  for candidate in "${candidates[@]}"; do
    version="$(nginx_binary_version "${candidate}")"
    [[ -n "${version}" ]] && version_at_least "${version}" "${NGINX_MIN_VERSION}" || continue
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
    return 0
  done
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

mysql_gpg_key_fingerprint() { # mysql_gpg_key_fingerprint <公钥文件>，兼容 CentOS 7 的旧版 GnuPG
  local keyFile="$1" inspectHome fingerprint=""
  inspectHome="$(mktemp -d)"; chmod 700 "${inspectHome}"
  if GNUPGHOME="${inspectHome}" gpg --batch --quiet --import "${keyFile}" >/dev/null 2>&1; then
    fingerprint="$(GNUPGHOME="${inspectHome}" gpg --batch --with-colons --fingerprint 2>/dev/null \
      | awk -F: '$1=="fpr" {print toupper($10); exit}')"
  fi
  rm -rf -- "${inspectHome}"
  printf '%s\n' "${fingerprint}"
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
  local rootAuth="" accountReady=no
  if MYSQL_PWD="${dbPwd}" "${MYSQL_HOME}/bin/mysql" --connect-timeout=3 \
      -h 127.0.0.1 -P "${dbPort}" -u "${dbUser}" -e 'SELECT 1' >/dev/null 2>&1; then
    accountReady=yes
    ok "MySQL 数据目录与业务账号已初始化，跳过重复建库授权"
  elif MYSQL_PWD="${rootPwd}" "${MYSQL_HOME}/bin/mysql" --protocol=socket -uroot -e 'SELECT 1' >/dev/null 2>&1; then
    rootAuth="${rootPwd}"
  elif "${MYSQL_HOME}/bin/mysql" --protocol=socket -uroot -e 'SELECT 1' >/dev/null 2>&1; then
    rootAuth=""
  else
    die "受管 MySQL 已存在但 root 凭证与配置不一致，未修改任何账号"
  fi
  if [[ "${accountReady}" != "yes" ]]; then
    if [[ "${dbUser}" == "root" ]]; then
      MYSQL_PWD="${rootAuth}" "${MYSQL_HOME}/bin/mysql" --protocol=socket -uroot -e \
        "CREATE DATABASE IF NOT EXISTS \`${dbName}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; ALTER USER 'root'@'localhost' IDENTIFIED BY '${rootPwd}'; FLUSH PRIVILEGES;" \
        || die "MySQL root 初始化失败"
      conf_set DB_PASSWORD "${rootPwd}"
    else
      MYSQL_PWD="${rootAuth}" "${MYSQL_HOME}/bin/mysql" --protocol=socket -uroot -e \
        "CREATE DATABASE IF NOT EXISTS \`${dbName}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; CREATE USER IF NOT EXISTS '${dbUser}'@'127.0.0.1' IDENTIFIED BY '${dbPwd}'; ALTER USER '${dbUser}'@'127.0.0.1' IDENTIFIED BY '${dbPwd}'; GRANT ALL PRIVILEGES ON \`${dbName}\`.* TO '${dbUser}'@'127.0.0.1'; ALTER USER 'root'@'localhost' IDENTIFIED BY '${rootPwd}'; FLUSH PRIVILEGES;" \
        || die "MySQL 业务账号初始化失败"
    fi
  fi
  command -v mysql >/dev/null 2>&1 || ln -s "${MYSQL_HOME}/bin/mysql" /usr/local/bin/mysql
  command -v mysqldump >/dev/null 2>&1 || ln -s "${MYSQL_HOME}/bin/mysqldump" /usr/local/bin/mysqldump
  ok "MySQL ${MYSQL_VERSION} 已安装为独立服务 ${MYSQL_MANAGED_SERVICE}"
}

ensure_manual_mysql() {
  local installMode="$1" host port user pwd version serverBinaryVersion service
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

select_redis_build_compiler() {
  local cc cxx major
  while IFS='|' read -r cc cxx; do
    [[ -x "${cc}" && -x "${cxx}" ]] || continue
    major="$(compiler_major_version "${cc}")"
    [[ "${major}" =~ ^[0-9]+$ && "${major}" -ge 7 ]] || continue
    export CC="${cc}" CXX="${cxx}"
    ok "Redis 编译器已就绪: $(${CC} --version 2>/dev/null | head -n 1)"
    return 0
  done <<EOF
${CC:-}|${CXX:-}
/opt/rh/gcc-toolset-13/root/usr/bin/gcc|/opt/rh/gcc-toolset-13/root/usr/bin/g++
/opt/rh/devtoolset-7/root/usr/bin/gcc|/opt/rh/devtoolset-7/root/usr/bin/g++
$(command -v gcc 2>/dev/null || true)|$(command -v g++ 2>/dev/null || true)
EOF
  return 1
}

install_centos7_redis_compiler() {
  local cacheDir releaseRpm checksum downloaded=no url repoFile
  cacheDir="${DATA_ROOT}/build-cache/toolchains"
  releaseRpm="${cacheDir}/centos-release-scl-rh-2-3.el7.centos.noarch.rpm"
  checksum="7941441bef911de9a9659743847aca92ea63e73d0199d53800626339f55d41d7"
  mkdir -p "${cacheDir}"
  if ! rpm -q centos-release-scl-rh >/dev/null 2>&1; then
    require_download_tools
    if [[ -f "${releaseRpm}" ]] && ! file_digest_matches "${releaseRpm}" sha256 "${checksum}"; then
      warn "CentOS SCL 仓库引导包摘要不匹配，将重新下载"
      rm -f -- "${releaseRpm}"
    fi
    if [[ ! -f "${releaseRpm}" ]]; then
      for url in \
        "https://mirrors.aliyun.com/centos/7.9.2009/extras/x86_64/Packages/centos-release-scl-rh-2-3.el7.centos.noarch.rpm" \
        "https://mirrors.cloud.tencent.com/centos/7.9.2009/extras/x86_64/Packages/centos-release-scl-rh-2-3.el7.centos.noarch.rpm"; do
        if try_download "${url}" "${releaseRpm}" "CentOS 7 SCL仓库引导包" sha256 "${checksum}"; then
          downloaded=yes; break
        fi
      done
      [[ "${downloaded}" == "yes" ]] || die "CentOS 7 SCL 仓库引导包下载失败"
    fi
    rpm -K "${releaseRpm}" 2>/dev/null | grep -Eqi 'rsa.*ok|pgp.*ok|digests.*ok' \
      || die "CentOS 7 SCL 仓库引导包签名校验失败"
    rpm -Uvh --replacepkgs "${releaseRpm}" >/dev/null \
      || die "CentOS 7 SCL 仓库引导包安装失败"
  fi

  repoFile="/etc/yum.repos.d/aid-centos-sclo-rh.repo"
  cat > "${repoFile}" <<'EOF'
# AID 为 EOL CentOS 7 提供的只读 SCL 国内镜像；不修改系统原有仓库文件。
[aid-centos-sclo-rh]
name=AID CentOS-7 SCLo rh
baseurl=https://mirrors.aliyun.com/centos/7/sclo/$basearch/rh/
        https://mirrors.cloud.tencent.com/centos/7/sclo/$basearch/rh/
        https://vault.epel.cloud/centos/7/sclo/$basearch/rh/
enabled=1
gpgcheck=1
gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-CentOS-SIG-SCLo
EOF
  yum --disablerepo=centos-sclo-rh --enablerepo=aid-centos-sclo-rh \
    install -y devtoolset-7-gcc devtoolset-7-gcc-c++ \
    || die "CentOS 7 Redis 编译工具链安装失败"
}

ensure_redis_build_compiler() {
  local installMode="$1" osId="" osVersion=""
  select_redis_build_compiler && return 0
  [[ "${installMode}" == "auto" ]] \
    || die "编译 Redis ${REDIS_VERSION} 需要 GCC/G++ 7+；请安装后重试"
  if [[ -f /etc/os-release ]]; then
    osId="$(. /etc/os-release; printf '%s' "${ID:-}")"
    osVersion="$(. /etc/os-release; printf '%s' "${VERSION_ID:-}")"
  fi
  if [[ "${osId}" == "centos" && "${osVersion%%.*}" == "7" ]]; then
    install_centos7_redis_compiler
  elif command -v apt-get >/dev/null 2>&1; then
    install_os_packages "Redis GCC/G++ 7+编译器" "build-essential" "gcc gcc-c++"
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y gcc gcc-c++ || die "Redis GCC/G++ 编译器安装失败"
  elif command -v yum >/dev/null 2>&1; then
    yum install -y gcc gcc-c++ || die "Redis GCC/G++ 编译器安装失败"
  else
    die "当前系统无法自动安装 Redis GCC/G++ 7+ 编译器"
  fi
  select_redis_build_compiler \
    || die "系统安装源未提供 GCC/G++ 7+；请升级操作系统编译器或使用外部 Redis 6+"
}

prepare_managed_redis() {
  local installMode="$1" name btChecksum officialChecksum cacheDir archive actual downloaded=no url tmp
  local redisHost redisPort redisUser redisPwd redisData redisRun redisLog redisConf buildLog
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
supervised systemd
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
[Unit]
Description=AID managed Redis ${REDIS_VERSION}
After=network.target

[Service]
Type=notify
User=aidredis
Group=aidredis
ExecStart=${REDIS_HOME}/src/redis-server ${redisConf} --supervised systemd
Restart=on-failure
RestartSec=5
LimitNOFILE=65535
TimeoutStopSec=60

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable --now "${REDIS_MANAGED_SERVICE}" >/dev/null 2>&1 || die "受管 Redis 启动失败"
  command -v redis-server >/dev/null 2>&1 || ln -s "${REDIS_HOME}/src/redis-server" /usr/local/bin/redis-server
  command -v redis-cli >/dev/null 2>&1 || ln -s "${REDIS_HOME}/src/redis-cli" /usr/local/bin/redis-cli
  ok "Redis ${REDIS_VERSION} 已安装为独立服务 ${REDIS_MANAGED_SERVICE}"
}

ensure_manual_host_dependencies() {
  local installMode redisHost redisPort redisUser redisPwd redisVersion redisMajor redisDb
  local -a redisArgs=()
  installMode="$(dependency_install_mode manual)"
  export AID_DEPENDENCY_INSTALL_MODE="${installMode}"
  command -v systemctl >/dev/null 2>&1 || die "手动部署要求使用 systemd"

  prepare_manual_jdk
  prepare_exact_node

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
  ensure_host_command redis-cli "Redis客户端" "redis-tools" "redis" "${installMode}"
  redisDb="$(conf_get REDIS_DATABASE 0)"
  redisArgs=(--no-auth-warning -h "${redisHost}" -p "${redisPort}" -n "${redisDb}")
  [[ -z "${redisUser}" ]] || redisArgs+=(--user "${redisUser}")
  redisVersion="$(REDISCLI_AUTH="${redisPwd}" redis-cli "${redisArgs[@]}" INFO server 2>/dev/null \
    | awk -F: '$1=="redis_version" {gsub("\r", "", $2); print $2; exit}')"
  redisMajor="${redisVersion%%.*}"
  [[ "${redisMajor}" =~ ^[0-9]+$ && "${redisMajor}" -ge 6 ]] \
    || die "Redis 认证失败或版本不符合（需要6+，检测结果: ${redisVersion:-不可读取}）"
  ok "Redis ${redisVersion} 已可用且版本符合，跳过安装"
  check_external_rocketmq_connectivity manual
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

deployment_application_ready() { # deployment_application_ready <docker|manual>
  local mode="$1" container
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
      if [[ -f "${DATA_ROOT}/app/updater/aid-updater" ]]; then
        docker_container_running_healthy aid-updater || return 1
      fi
      ;;
    manual)
      systemctl is-active --quiet aid || return 1
      if [[ -f "${DATA_ROOT}/app/web-dist/server/index.mjs" ]]; then
        systemctl is-active --quiet aid-web || return 1
      fi
      select_existing_nginx_runtime >/dev/null 2>&1 || return 1
      nginx_runtime_active || return 1
      if systemctl list-unit-files 2>/dev/null | grep -q '^aid-updater\.service'; then
        systemctl is-active --quiet aid-updater || return 1
      fi
      ;;
    *) return 1 ;;
  esac
}

deployment_artifacts_ready() {
  [[ -s "${DATA_ROOT}/app/aid-admin.jar" \
     && -f "${DATA_ROOT}/app/admin-dist/index.html" \
     && -f "${DATA_ROOT}/app/web-dist/server/index.mjs" ]]
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

try_download() { # try_download <URL> <目标文件> <名称> [摘要算法] [固定摘要]
  local url="$1" target="$2" label="$3" algorithm="${4:-}" expected="${5:-}"
  local part="${2}.part" currentSize=0 curlCode=0 minSpeed="${DOWNLOAD_MIN_SPEED_BYTES}"
  local lowSpeedSeconds="${DOWNLOAD_LOW_SPEED_SECONDS}"
  local -a curlArgs=()
  case "${url}" in
    https://*) ;;
    *) err "拒绝非 HTTPS 下载地址: ${url}"; return 1 ;;
  esac
  [[ "${minSpeed}" =~ ^[0-9]+$ ]] || minSpeed=32768
  [[ "${lowSpeedSeconds}" =~ ^[0-9]+$ ]] || lowSpeedSeconds=30
  if [[ -n "${algorithm}" && -n "${expected}" ]] && file_digest_matches "${target}" "${algorithm}" "${expected}"; then
    ok "${label} 完整缓存校验通过，跳过下载: ${target}"
    return 0
  fi
  if [[ -n "${algorithm}" && -n "${expected}" ]] && file_digest_matches "${part}" "${algorithm}" "${expected}"; then
    mv -f -- "${part}" "${target}"
    ok "${label} 未完成缓存实际已完整，经摘要校验后直接复用"
    return 0
  fi
  curlArgs=(--fail --location --retry 3 --retry-delay 2 --connect-timeout 15
    --max-time "${DOWNLOAD_TIMEOUT_SECONDS}" --speed-limit "${minSpeed}"
    --speed-time "${lowSpeedSeconds}" --proto '=https' --tlsv1.2 --progress-bar)
  log "${C_BLUE}下载 ${label}${C_RESET}"
  echo "  ${url}"
  if [[ -s "${part}" ]]; then
    currentSize="$(wc -c < "${part}" | tr -d '[:space:]')"
    warn "发现 ${label} 未完成缓存（${currentSize:-0} 字节），从断点继续；切换镜像不会从 0 开始"
    curl "${curlArgs[@]}" --continue-at - --output "${part}" "${url}" || curlCode=$?
    if (( curlCode == 33 || curlCode == 36 )); then
      warn "当前地址不支持断点续传，清理未完成缓存后从该地址重新下载"
      rm -f -- "${part}"
      curlCode=0
      curl "${curlArgs[@]}" --output "${part}" "${url}" || curlCode=$?
    fi
  else
    curl "${curlArgs[@]}" --output "${part}" "${url}" || curlCode=$?
  fi
  if (( curlCode != 0 )); then
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
  resolve_bt_mirror_order
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
  if [[ ! -x "${tmp}/bin/java" ]] \
      || ! "${tmp}/bin/java" -version 2>&1 | head -n 1 | grep -Fq '17.0.20'; then
    rm -rf -- "${tmp}"
    die "OpenJDK 实际版本不是17.0.20"
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
      cnUrl="https://gitee.com/gzxx-2025/aid-server/releases/download/toolchain-node-v${NODE_VERSION}/${name}"
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
    for entry in installer/deploy/aid.sh installer/deploy/aid-deploy.conf.example \
        installer/deploy/docker/.env.example \
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
    AID_DOCKER_MIRRORS="$(docker_mirror_setting)" \
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
  local managedDir currentManager stagedManager
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
    && ok "已安装管理命令: sudo aid（更新: sudo aid update；查看地址: sudo aid default）" \
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
  local mode="$1" entry name
  [[ -d "${DATA_ROOT}" ]] || return 1
  while IFS= read -r -d '' entry; do
    name="${entry##*/}"
    case "${name}" in
      packages|installer|config|source-build|build-cache|logs)
        [[ -d "${entry}" && ! -L "${entry}" ]] && continue
        ;;
      aid-deploy.conf)
        [[ -f "${entry}" && ! -L "${entry}" ]] && continue
        ;;
      runtime|mysql-data-manual|mysql-files|redis-data-manual|run)
        # 手动部署会在首次确认前准备受管 MySQL/Redis；这些目录仍属于 AID。
        [[ "${mode}" == "manual" && -d "${entry}" && ! -L "${entry}" ]] && continue
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
  fi
  warn "安装会拉取 Docker 镜像、创建服务并占用以上端口；不会自动开放防火墙或修改域名解析"
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
DOCKER_MIRRORS=${DEFAULT_DOCKER_MIRRORS}
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
      dbPwd="$(gen_secret)"
      conf_set DB_PASSWORD "${dbPwd}"
      rootPwd="$(conf_get MYSQL_ROOT_PASSWORD '')"
      if [[ -z "${rootPwd}" ]]; then
        rootPwd="$(gen_secret)"
        conf_set MYSQL_ROOT_PASSWORD "${rootPwd}"
      fi
      ok "全新本机 MySQL 将使用自动生成的强随机 root/业务密码"
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

# 确保 MySQL 就绪。外部模式只做连接及 5.7 版本校验，绝不拉起 aid-mysql。
ensure_mysql_ready() {
  local mode AID_DEPENDENCY_INSTALL_MODE; mode="${1:-$(detect_mode)}"
  if [[ "${mode}" != "docker" ]]; then return 0; fi
  AID_DEPENDENCY_INSTALL_MODE="$(dependency_install_mode docker)"
  ensure_docker_image "mysql:5.7" "MySQL5.7"
  if docker_profile_enabled mysql; then
    log "启动并检查内置 MySQL 5.7..."
    if ! compose_cmd up -d mysql; then
      docker_container_diagnostics aid-mysql "内置 MySQL 5.7"
      return 1
    fi
    wait_docker_container_healthy aid-mysql "内置 MySQL 5.7" 120 || return 1
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

wait_docker_database_schema_ready() {
  local dbName deadline coreTableCount=""
  dbName="$(env_get DB_NAME aid)"
  deadline=$(( $(date +%s) + 300 ))
  log "等待 AID 数据库初始化与核心表校验..."
  while [[ $(date +%s) -lt ${deadline} ]]; do
    coreTableCount="$(docker_mysql_tool mysql --batch --skip-column-names \
      --execute "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${dbName}' AND table_name IN ('aid_config','sys_user')" \
      2>/dev/null | tail -n 1 || true)"
    [[ "${coreTableCount}" == "2" ]] \
      && { ok "AID 数据库初始化完成，核心表校验通过"; return 0; }
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

  if [[ "${AID_SKIP_UPDATER_RESTART:-0}" != "1" && -f "${DATA_ROOT}/app/updater/aid-updater" ]]; then
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
  ok "AID 后端、Web、Nginx 与升级器已按顺序启动"
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
  local mode="$1" publicIp="$2" configFile="$3" dbHost dbPort dbName dbUser mysqlPort
  if [[ "${mode}" == "docker" ]]; then
    dbHost="$(env_get DB_HOST mysql)"; dbPort="$(env_get DB_PORT 3306)"
    dbName="$(env_get DB_NAME aid)"; dbUser="$(env_get DB_USERNAME aid)"
    mysqlPort="$(env_get MYSQL_PORT 3306)"
    if docker_profile_enabled mysql; then
      echo ""
      echo "Navicat 连接内置 MySQL（推荐 SSH 隧道，不开放公网 3306）："
      echo "  SSH主机/端口 : ${publicIp:-服务器公网IP}:22（使用服务器运维账号）"
      echo "  MySQL主机/端口: 127.0.0.1:${mysqlPort}"
      echo "  数据库/用户名 : ${dbName} / ${dbUser}"
      echo "  数据库密码位置: ${ENV_FILE} 中的 DB_PASSWORD（不会在终端明文打印）"
      return 0
    fi
  else
    dbHost="$(conf_get DB_HOST 127.0.0.1)"; dbPort="$(conf_get DB_PORT 3306)"
    dbName="$(conf_get DB_NAME aid)"; dbUser="$(conf_get DB_USERNAME aid)"
    if [[ "${dbHost}" == "127.0.0.1" || "${dbHost}" == "localhost" ]]; then
      echo ""
      echo "Navicat 连接本机 MySQL（推荐 SSH 隧道，不开放公网 3306）："
      echo "  SSH主机/端口 : ${publicIp:-服务器公网IP}:22（使用服务器运维账号）"
      echo "  MySQL主机/端口: 127.0.0.1:${dbPort}"
      echo "  数据库/用户名 : ${dbName} / ${dbUser}"
      echo "  数据库密码位置: ${CONF} 中的 DB_PASSWORD（不会在终端明文打印）"
      return 0
    fi
  fi
  echo ""
  echo "Navicat 连接外部 MySQL："
  echo "  AID 当前连接: ${dbHost}:${dbPort}/${dbName}（用户 ${dbUser}）"
  echo "  请使用数据库服务商提供的可访问地址；若仅内网开放，应通过数据库所在网络的 SSH/云数据库代理连接。"
  echo "  数据库密码保存在 ${configFile} 的 DB_PASSWORD 中，不会在终端明文打印。"
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
write_systemd_units() {
  local javaBin nodeBin
  [[ -x "${JDK_HOME}/bin/java" ]] \
    && "${JDK_HOME}/bin/java" -version 2>&1 | head -n 1 | grep -Fq "${MANUAL_JDK_VERSION}" \
    || prepare_manual_jdk
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
Environment=JAVA_HOME=${JDK_HOME}
Environment=PATH=${JDK_HOME}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin
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
  local httpPort adminPort backendPort content httpsPort httpsDomain httpsAdminDomain certPath keyPath backupPath siteFile
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
  if [[ -n "${NGINX_BIN}" && -x "${NGINX_BIN}" && -n "${NGINX_SITE_DIR}" ]]; then
    mkdir -p "${NGINX_SITE_DIR}"
    siteFile="${NGINX_SITE_DIR}/aid.conf"
    backupPath=""
    if [[ -f "${siteFile}" ]]; then
      backupPath="${siteFile}.bak.$(date +%s)"
      cp "${siteFile}" "${backupPath}"
    fi
    echo "${content}" > "${siteFile}"
    if ! "${NGINX_BIN}" -t >/dev/null 2>&1; then
      if [[ -n "${backupPath}" ]]; then cp "${backupPath}" "${siteFile}"; else rm -f "${siteFile}"; fi
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

wait_manual_web_healthy() {
  local deadline=$(( $(date +%s) + 120 ))
  while [[ $(date +%s) -lt ${deadline} ]]; do
    curl -sf -o /dev/null http://127.0.0.1:3000/ 2>/dev/null \
      && { ok "Web 用户端已就绪"; return 0; }
    if ! systemctl is-active --quiet aid-web; then
      manual_service_diagnostics aid-web "Web 用户端"
      return 1
    fi
    sleep 3
  done
  manual_service_diagnostics aid-web "Web 用户端（等待120s超时）"
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

  if [[ -f "${DATA_ROOT}/app/web-dist/server/index.mjs" ]]; then
    section "启动 Web 用户端与 Nginx 网关"
    systemctl enable aid-web >/dev/null 2>&1 || true
    if ! systemctl restart aid-web || ! wait_manual_web_healthy; then
      systemctl stop aid-web >/dev/null 2>&1 || true
      risk "Web 用户端启动失败，已停止循环重启；AID 后端与数据服务保持运行"
      return 1
    fi
  else
    warn "web-dist 无 SSR 产物，aid-web 服务暂不启动"
  fi
  write_nginx_site

  if [[ "${AID_SKIP_UPDATER_RESTART:-0}" != "1" ]]; then
    setup_updater manual
    if systemctl list-unit-files 2>/dev/null | grep -q '^aid-updater\.service' \
        && ! systemctl is-active --quiet aid-updater; then
      manual_service_diagnostics aid-updater "在线升级器"
      return 1
    fi
  fi
  ok "AID 后端、Web、Nginx 与升级器已按顺序启动"
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
do_update() {
  require_root
  local mode package supplied current target comparison go backupDir dist old f targetChannel repairMode=0
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

remove_aid_docker_runtime() { # remove_aid_docker_runtime <keep|purge>
  local cleanupMode="$1" container network workdir repository imageId networks=""
  local -a containers=(
    aid-nginx-https aid-nginx aid-web aid-server aid-updater
    aid-rocketmq-broker aid-rocketmq-nameserver aid-redis aid-mysql
  )
  command -v docker >/dev/null 2>&1 || return 0

  # 删除容器前记录它们连接的网络；容器删除后只清理工作目录明确属于
  # 当前 DATA_ROOT 的 Compose 网络，绝不执行 docker system/network prune。
  networks="$(
    for container in "${containers[@]}"; do
      docker inspect --format '{{range $name, $value := .NetworkSettings.Networks}}{{$name}}{{"\n"}}{{end}}' \
        "${container}" 2>/dev/null || true
    done | sort -u
  )"
  for container in "${containers[@]}"; do
    if docker inspect "${container}" >/dev/null 2>&1; then
      docker rm -f "${container}" >/dev/null
      log "已删除容器: ${container}"
    fi
  done
  while IFS= read -r network; do
    [[ -n "${network}" ]] || continue
    workdir="$(docker network inspect --format '{{index .Labels "com.docker.compose.project.working_dir"}}' \
      "${network}" 2>/dev/null || true)"
    case "${workdir}" in
      "${DATA_ROOT}/installer/deploy/docker"*)
        docker network rm "${network}" >/dev/null 2>&1 || true
        ;;
    esac
  done <<< "${networks}"

  [[ "${cleanupMode}" == "purge" ]] || return 0
  # 只删除仓库名以 aid/ 开头的本项目自建镜像；MySQL/Redis/Nginx/Node
  # 等公共镜像可能被其他项目复用，卸载器始终保留。
  while read -r repository imageId; do
    [[ "${repository}" == aid/* && -n "${imageId}" ]] || continue
    docker image rm -f "${imageId}" >/dev/null 2>&1 || true
  done < <(docker image ls --format '{{.Repository}} {{.ID}}' 2>/dev/null)
}

remove_aid_system_services() {
  local -a services=(aid.service aid-web.service aid-updater.service aid-mysql.service aid-redis.service aid-nginx.service)
  if command -v systemctl >/dev/null 2>&1; then
    systemctl disable --now "${services[@]}" >/dev/null 2>&1 || true
  fi
  rm -f -- \
    /etc/systemd/system/aid.service \
    /etc/systemd/system/aid-web.service \
    /etc/systemd/system/aid-updater.service \
    /etc/systemd/system/aid-mysql.service \
    /etc/systemd/system/aid-redis.service \
    /etc/systemd/system/aid-nginx.service \
    "${JAVA_PROFILE_FILE}"
  if command -v systemctl >/dev/null 2>&1; then
    systemctl daemon-reload >/dev/null 2>&1 || true
    systemctl reset-failed >/dev/null 2>&1 || true
  fi
}

remove_aid_nginx_site() {
  local dir
  for dir in "${CONFIG_ROOT}/nginx/conf.d" /etc/nginx/conf.d /www/server/panel/vhost/nginx; do
    [[ -d "${dir}" ]] || continue
    rm -f -- "${dir}/aid.conf"
    find "${dir}" -maxdepth 1 -type f -name 'aid.conf.bak.*' -delete 2>/dev/null || true
  done
  if select_existing_nginx_runtime >/dev/null 2>&1 && "${NGINX_BIN}" -t >/dev/null 2>&1; then
    reload_nginx_runtime >/dev/null 2>&1 || true
  fi
}

remove_aid_command_links() {
  local path target link
  path="/usr/local/bin/aid"
  if [[ -L "${path}" ]]; then
    target="$(readlink "${path}" 2>/dev/null || true)"
    case "${target}" in
      "${DATA_ROOT}/installer/deploy/aid.sh"|"${MANAGED_SCRIPT}") rm -f -- "${path}" ;;
    esac
  fi
  rm -f -- /usr/local/bin/aid-updater

  # 手动部署可能创建指向 DATA_ROOT 隔离工具链的命令链接。只删除目标
  # 明确位于本项目 runtime 下的链接，不影响系统自带 mysql/redis。
  for link in mysql mysqldump redis-server redis-cli nginx; do
    path="/usr/local/bin/${link}"
    [[ -L "${path}" ]] || continue
    target="$(readlink -f "${path}" 2>/dev/null || true)"
    case "${target}" in "${DATA_ROOT}/runtime/"*) rm -f -- "${path}" ;; esac
  done
}

remove_aid_updater_runtime() { # remove_aid_updater_runtime <keep|purge>
  local cleanupMode="$1"
  rm -rf -- /etc/aid-updater
  if [[ "${cleanupMode}" == "purge" ]]; then
    rm -rf -- /var/lib/aid-updater
  fi
  return 0
}

validate_aid_purge_root() {
  local resolved=""
  [[ "${DATA_ROOT}" == /* ]] || die "拒绝清理非绝对数据目录: ${DATA_ROOT}"
  case "${DATA_ROOT}" in
    /|/bin|/boot|/data|/dev|/etc|/home|/opt|/root|/run|/srv|/tmp|/usr|/var)
      die "拒绝清理高风险目录: ${DATA_ROOT}" ;;
  esac
  [[ "${DATA_ROOT#/}" == */* ]] || die "数据目录层级过浅，拒绝清理: ${DATA_ROOT}"
  [[ ! -L "${DATA_ROOT}" ]] || die "数据目录是软链接，拒绝清理: ${DATA_ROOT}"
  if [[ -e "${DATA_ROOT}" ]]; then
    resolved="$(readlink -f -- "${DATA_ROOT}" 2>/dev/null || true)"
    [[ "${resolved}" == "${DATA_ROOT}" ]] || die "数据目录解析异常，拒绝清理: ${resolved:-未知}"
  fi
}

remove_aid_manual_accounts() {
  # 手动部署专用系统账号仅服务于 DATA_ROOT 内的隔离 MySQL/Redis；彻底
  # 清理时一并删除。普通卸载保留账号与数据，便于无损重新安装。
  id aidmysql >/dev/null 2>&1 && userdel aidmysql >/dev/null 2>&1 || true
  id aidredis >/dev/null 2>&1 && userdel aidredis >/dev/null 2>&1 || true
  getent group aidmysql >/dev/null 2>&1 && groupdel aidmysql >/dev/null 2>&1 || true
  getent group aidredis >/dev/null 2>&1 && groupdel aidredis >/dev/null 2>&1 || true
}

purge_aid_data() {
  validate_aid_purge_root
  [[ ! -e "${DATA_ROOT}" ]] || rm -rf -- "${DATA_ROOT}"
  remove_aid_manual_accounts
}

do_uninstall() { # do_uninstall [keep|purge|--keep|--purge]
  require_root
  local requested="${1:-}" cleanupMode="" choice confirm mode
  mode="$(detect_mode)"
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
    risk "将永久删除 ${DATA_ROOT}；内置 MySQL/Redis/MQ 数据与所有本机备份都无法恢复"
    warn "外部或用户原有的 MySQL/Redis/RocketMQ、OSS/COS 对象不会被删除，请到对应平台单独处理"
    confirm="$(ask '请输入 DELETE-AID 确认彻底清除' '')"
    [[ "${confirm}" == "DELETE-AID" ]] || { log "确认文字不匹配，已取消卸载"; return 0; }
  else
    confirm="$(ask_yes_no '确认停止并卸载 AID，同时保留全部数据？' 'n')"
    [[ "${confirm}" == "y" ]] || { log "已取消卸载"; return 0; }
  fi

  section "卸载 AID"
  remove_aid_docker_runtime "${cleanupMode}"
  remove_aid_system_services
  remove_aid_nginx_site
  remove_aid_command_links
  remove_aid_updater_runtime "${cleanupMode}"
  [[ "${cleanupMode}" != "purge" ]] || purge_aid_data

  if [[ "${cleanupMode}" == "purge" ]]; then
    ok "AID 已彻底清除；Docker/JDK/Nginx/Git 等共享系统环境及外部服务未删除"
  else
    ok "AID 服务与运行入口已卸载，数据完整保留在 ${DATA_ROOT}"
    echo "重新安装时下载最新 aid.sh 并选择原部署方式，脚本会复用现有配置和数据。"
  fi
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
  echo " 12) 查看登录地址与数据库初始化账号"
  echo " 13) 卸载 AID（可选保留数据或彻底清除）"
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
    default)        do_default; exit $? ;;
    uninstall)      do_uninstall "${2:-}"; exit $? ;;
    logs)           do_logs; exit $? ;;
    config)         do_config; exit $? ;;
    backup)         do_backup; exit $? ;;
    setup-updater)  do_setup_updater; exit $? ;;
    '') ;;
    *) die "未知子命令: $1（可用: install/auto/install-docker/install-manual/update/rollback/restart/stop/status/default/logs/config/backup/setup-updater/uninstall）" ;;
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
      12) do_default || true ;;
      13) do_uninstall || true ;;
      0) exit 0 ;;
      *) warn "无效选择" ;;
    esac
  done
}

if [[ "${AID_SH_LIBRARY_MODE:-0}" != "1" ]]; then
  main "$@"
fi
