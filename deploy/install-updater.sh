#!/bin/bash
# ----------------------------------------------------------------------------
# aid-updater 安装脚本（Linux + systemd）
# 用法：把本脚本与对应架构的 aid-updater 二进制放在同一目录后执行：
#   sudo bash install-updater.sh
# 幂等：重复执行会更新二进制与服务定义，不会覆盖已有配置文件。
# ----------------------------------------------------------------------------

set -euo pipefail

BIN_TARGET="/usr/local/bin/aid-updater"
CONFIG_DIR="/etc/aid-updater"
CONFIG_FILE="${CONFIG_DIR}/config.json"
DATA_DIR="/var/lib/aid-updater"
SERVICE_FILE="/etc/systemd/system/aid-updater.service"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

if [[ "$(id -u)" -ne 0 ]]; then
  log "请使用 root 执行（sudo bash install-updater.sh）"
  exit 1
fi

if ! command -v systemctl >/dev/null 2>&1; then
  log "未检测到 systemd，aid-updater 仅支持 systemd 环境"
  exit 1
fi

# 1. 定位二进制：优先同目录 aid-updater，其次按架构后缀
ARCH="$(uname -m)"
case "${ARCH}" in
  x86_64)  ARCH_SUFFIX="linux_amd64" ;;
  aarch64) ARCH_SUFFIX="linux_arm64" ;;
  *) log "不支持的架构: ${ARCH}"; exit 1 ;;
esac

BIN_SOURCE=""
if [[ -f "${SCRIPT_DIR}/aid-updater" ]]; then
  BIN_SOURCE="${SCRIPT_DIR}/aid-updater"
elif [[ -f "${SCRIPT_DIR}/aid-updater_${ARCH_SUFFIX}" ]]; then
  BIN_SOURCE="${SCRIPT_DIR}/aid-updater_${ARCH_SUFFIX}"
else
  log "未找到升级器二进制，请将 aid-updater 或 aid-updater_${ARCH_SUFFIX} 放到脚本同目录"
  exit 1
fi

# 2. 安装二进制
install -m 0755 "${BIN_SOURCE}" "${BIN_TARGET}"
log "已安装二进制: ${BIN_TARGET} ($("${BIN_TARGET}" -version))"

# 3. 目录与配置（配置已存在则保留）
mkdir -p "${CONFIG_DIR}" "${DATA_DIR}/inbox" "${DATA_DIR}/work" "${DATA_DIR}/backups"
if [[ ! -f "${CONFIG_FILE}" ]]; then
  if [[ -f "${SCRIPT_DIR}/aid-updater.config.example.json" ]]; then
    cp "${SCRIPT_DIR}/aid-updater.config.example.json" "${CONFIG_FILE}"
    log "已生成默认配置: ${CONFIG_FILE}（请按实际部署路径修改）"
  else
    log "缺少配置模板 aid-updater.config.example.json，请手工创建 ${CONFIG_FILE}"
    exit 1
  fi
else
  log "保留已有配置: ${CONFIG_FILE}"
fi

# 4. systemd 服务
if [[ -f "${SCRIPT_DIR}/aid-updater.service" ]]; then
  cp "${SCRIPT_DIR}/aid-updater.service" "${SERVICE_FILE}"
else
  log "缺少服务模板 aid-updater.service"
  exit 1
fi
systemctl daemon-reload
systemctl enable aid-updater >/dev/null 2>&1
systemctl restart aid-updater
sleep 1
systemctl --no-pager --lines 0 status aid-updater || true

log "安装完成。请到后台「项目升级配置 → 升级源配置」中确认："
log "  升级器健康文件路径 = ${DATA_DIR}/health.json"
log "  升级器任务文件路径 = ${DATA_DIR}/inbox/task.json"
