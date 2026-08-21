#!/bin/sh
# AID_SOURCE_BUILD_MODE_CAPABILITY=explicit-v1
# AID_BUILD_RESOURCE_CONTROL_CAPABILITY=governor-v1
# AID 远程源码构建器：从同一版本标签拉取三端公开源码，在临时目录完成构建并组装本地安装包。
# Gitee 三仓均可访问时优先使用 Gitee；任一不可用则整组回退到 GitHub，禁止混用来源。

set -eu

VERSION=""
OUTPUT=""
WORK_DIR=""
FORGE="${AID_SOURCE_FORGE:-auto}"
SOURCE_BUILD_MODE="${AID_SOURCE_BUILD_MODE:-auto}"
DATA_ROOT="${AID_DATA_ROOT:-/data/aid}"
CACHE_DIR="${AID_BUILD_CACHE_DIR:-$DATA_ROOT/build-cache}"
RESOURCE_RESERVE_PERCENT="${AID_BUILD_RESERVE_PERCENT:-15}"
RESOURCE_RESUME_PERCENT="${AID_BUILD_RESUME_PERCENT:-20}"
PREFLIGHT_SAMPLES="${AID_BUILD_PREFLIGHT_SAMPLES:-6}"
PREFLIGHT_INTERVAL_SECONDS="${AID_BUILD_PREFLIGHT_INTERVAL_SECONDS:-5}"
GATE_CPU_SAMPLE_SECONDS="${AID_BUILD_GATE_CPU_SAMPLE_SECONDS:-1}"
MONITOR_INTERVAL_SECONDS="${AID_BUILD_MONITOR_INTERVAL_SECONDS:-5}"
PRESSURE_SUSTAINED_SAMPLES="${AID_BUILD_PRESSURE_SAMPLES:-2}"
PRESSURE_RECOVERY_SAMPLES="${AID_BUILD_RECOVERY_SAMPLES:-2}"
PRESSURE_MAX_WAIT_SECONDS="${AID_BUILD_PRESSURE_MAX_WAIT_SECONDS:-900}"
MIN_AVAILABLE_MEMORY_MB="${AID_BUILD_MIN_AVAILABLE_MEMORY_MB:-512}"
MIN_FREE_DISK_MB="${AID_BUILD_MIN_FREE_DISK_MB:-10240}"
DANGER_MEMORY_PERCENT="${AID_BUILD_DANGER_MEMORY_PERCENT:-5}"
DANGER_AVAILABLE_MEMORY_MB="${AID_BUILD_DANGER_AVAILABLE_MEMORY_MB:-256}"
DANGER_DISK_PERCENT="${AID_BUILD_DANGER_DISK_PERCENT:-5}"
DANGER_FREE_DISK_MB="${AID_BUILD_DANGER_FREE_DISK_MB:-1024}"
MANAGED_SWAP_MODE="${AID_BUILD_MANAGED_SWAP:-auto}"
MANAGED_SWAP_TARGET_MB="${AID_BUILD_MANAGED_SWAP_TARGET_MB:-4096}"
MANAGED_SWAP_MAX_RAM_MB="${AID_BUILD_MANAGED_SWAP_MAX_RAM_MB:-5120}"
MANAGED_SWAP_FILE="${AID_BUILD_MANAGED_SWAP_FILE:-$DATA_ROOT/build-cache/.aid-swap/aid-source-build.swap}"
BUILD_IO_WEIGHT="${AID_BUILD_IO_WEIGHT:-100}"
PAUSED_CPU_MILLI="${AID_BUILD_PAUSED_CPU_MILLI:-50}"

GITHUB_BASE="https://github.com/gzxx-2025"
GITEE_BASE="https://gitee.com/gzxx-2025"
SERVER_REPO="aid-server"
ADMIN_REPO="aid-admin"
WEB_REPO="aid-web"
GIT_IMAGE="${AID_GIT_IMAGE:-alpine/git:2.47.2}"
MAVEN_IMAGE="${AID_MAVEN_IMAGE:-maven:3.9.9-eclipse-temurin-17}"
NODE_IMAGE="${AID_NODE_IMAGE:-node:22.22.0-bookworm-slim}"
GO_IMAGE="${AID_GO_IMAGE:-golang:1.22.12-bookworm}"
DEPENDENCY_INSTALL_MODE="${AID_DEPENDENCY_INSTALL_MODE:-auto}"
DEPENDENCY_REGION="${AID_DEPENDENCY_REGION:-auto}"
DEFAULT_DOCKER_MIRRORS="docker.m.daocloud.io,dockerproxy.net"
DOCKER_MIRRORS="${AID_DOCKER_MIRRORS:-${AID_DOCKER_CN_MIRROR:-$DEFAULT_DOCKER_MIRRORS}}"
DOCKER_MIRROR_ORDER=""
DOCKER_MIRRORS_RESOLVED=0
IMAGE_PULL_TIMEOUT_SECONDS="${AID_IMAGE_PULL_TIMEOUT_SECONDS:-900}"
IMAGE_MANIFEST_PROBE_TIMEOUT_SECONDS="${AID_IMAGE_MANIFEST_PROBE_TIMEOUT_SECONDS:-45}"
JDK_VERSION="17.0.20"
JDK_BUILD="8"
JDK_HOME=""
FFMPEG_RUNTIME_VERSION="7.0.2"
AID_CJK_FONT_VERSION="noto-sans-sc-2.004"
JAVA_RUNTIME_IMAGE="aid/openjdk:17.0.20-ffmpeg${FFMPEG_RUNTIME_VERSION}-font2.004"
MANIFEST_PUBLIC_KEY="${AID_MANIFEST_PUBLIC_KEY:-9Ez/VMofgjCU0CNmE6Jq8LKLNyfDQqbbvNTTGV5BYrk=}"
SCRIPT_NAME="$(basename "$0")"
SCRIPT_HOME="$(dirname "$0")"
SCRIPT_HOME="$(CDPATH='' cd "$SCRIPT_HOME" && pwd)"
SCRIPT_PATH="$SCRIPT_HOME/$SCRIPT_NAME"
ACTIVE_DOCKER_CONTAINER=""
ACTIVE_HOST_PID=""
ACTIVE_HOST_PID_FILE=""
ACTIVE_HOST_UNIT=""
ACTIVE_HOST_CGROUP_MODE=""
ACTIVE_HOST_CGROUP_PATHS=""
ACTIVE_STAGE_PAUSED=no
STAGE_SEQUENCE=0
DOCKER_ROOT_DIR=""
GOVERNOR_CPU_TOTAL=""
GOVERNOR_CPU_IDLE=""

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
warn() { printf '[%s] [提示] %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
die() { printf '[%s] [失败] %s\n' "$(date '+%H:%M:%S')" "$*" >&2; exit 1; }

require_uint_range() {
  value_name="$1"; value="$2"; minimum="$3"; maximum="$4"
  case "$value" in ''|*[!0-9]*) die "$value_name 必须是整数" ;; esac
  case "$value" in 0|[1-9]*) ;; *) die "$value_name 不能包含前导零" ;; esac
  [ "$value" -ge "$minimum" ] && [ "$value" -le "$maximum" ] \
    || die "$value_name 必须在 $minimum 到 $maximum 之间"
}

max_value() {
  if [ "$1" -ge "$2" ]; then printf '%s\n' "$1"; else printf '%s\n' "$2"; fi
}

format_tenths() {
  printf '%s.%s' "$(( $1 / 10 ))" "$(( $1 % 10 ))"
}

read_cpu_counters() {
  set -- $(awk '/^cpu / { total=0; for (i=2; i<=NF; i++) total += $i; printf "%.0f %.0f\n", total, $5; exit }' /proc/stat)
  [ "$#" -eq 2 ] || die '无法读取系统 CPU 状态'
  CPU_COUNTER_TOTAL="$1"
  CPU_COUNTER_IDLE="$2"
}

cpu_idle_tenths_since() {
  previous_total="$1"; previous_idle="$2"
  read_cpu_counters
  total_delta=$((CPU_COUNTER_TOTAL - previous_total))
  idle_delta=$((CPU_COUNTER_IDLE - previous_idle))
  if [ "$total_delta" -le 0 ]; then
    CPU_IDLE_TENTHS=1000
  else
    CPU_IDLE_TENTHS=$((idle_delta * 1000 / total_delta))
  fi
}

read_memory_metrics() {
  set -- $(awk '
    /^MemTotal:/ { total=$2 }
    /^MemAvailable:/ { available=$2 }
    /^MemFree:/ { free=$2 }
    /^Buffers:/ { buffers=$2 }
    /^Cached:/ { cached=$2 }
    /^SReclaimable:/ { reclaimable=$2 }
    /^Shmem:/ { shmem=$2 }
    /^SwapTotal:/ { swap_total=$2 }
    /^SwapFree:/ { swap_free=$2 }
    END {
      if (available <= 0) available=free+buffers+cached+reclaimable-shmem
      if (available < 0) available=0
      printf "%d %d %d %d\n", total/1024, available/1024, swap_total/1024, swap_free/1024
    }
  ' /proc/meminfo)
  [ "$#" -eq 4 ] || die '无法读取系统内存状态'
  SYSTEM_MEMORY_TOTAL_MB="$1"
  SYSTEM_MEMORY_AVAILABLE_MB="$2"
  SYSTEM_SWAP_TOTAL_MB="$3"
  SYSTEM_SWAP_FREE_MB="$4"
  [ "$SYSTEM_MEMORY_TOTAL_MB" -gt 0 ] || die '系统内存总量异常'
  MEMORY_AVAILABLE_TENTHS=$((SYSTEM_MEMORY_AVAILABLE_MB * 1000 / SYSTEM_MEMORY_TOTAL_MB))
}

nearest_existing_disk_path() {
  disk_candidate="$1"
  [ -n "$disk_candidate" ] || return 1
  while [ ! -e "$disk_candidate" ]; do
    disk_parent="$(dirname "$disk_candidate")"
    [ "$disk_parent" != "$disk_candidate" ] || return 1
    disk_candidate="$disk_parent"
  done
  printf '%s\n' "$disk_candidate"
}

evaluate_disk_path() {
  disk_label="$1"; configured_path="$2"
  [ -n "$configured_path" ] || return 0
  probe_path="$(nearest_existing_disk_path "$configured_path" 2>/dev/null || true)"
  [ -n "$probe_path" ] || die "无法定位磁盘检查路径: $configured_path"
  set -- $(df -Pk "$probe_path" 2>/dev/null | awk 'NR > 1 { size=$2; available=$4; mount=$NF } END { print size, available, mount }')
  [ "$#" -ge 3 ] && [ "$1" -gt 0 ] 2>/dev/null || die "无法读取磁盘空间: $configured_path"
  disk_size_kb="$1"; disk_available_kb="$2"; disk_mount="$3"
  disk_total_mb=$((disk_size_kb / 1024))
  disk_available_mb=$((disk_available_kb / 1024))
  disk_available_tenths=$((disk_available_kb * 1000 / disk_size_kb))
  reserve_ratio_score=$((disk_available_tenths * 100 / (RESOURCE_RESERVE_PERCENT * 10)))
  reserve_absolute_score=$((disk_available_mb * 100 / MIN_FREE_DISK_MB))
  reserve_score="$reserve_ratio_score"; [ "$reserve_absolute_score" -ge "$reserve_score" ] || reserve_score="$reserve_absolute_score"
  resume_ratio_score=$((disk_available_tenths * 100 / (RESOURCE_RESUME_PERCENT * 10)))
  resume_absolute_score=$((disk_available_mb * 100 / MIN_FREE_DISK_MB))
  resume_score="$resume_ratio_score"; [ "$resume_absolute_score" -ge "$resume_score" ] || resume_score="$resume_absolute_score"
  danger_ratio_score=$((disk_available_tenths * 100 / (DANGER_DISK_PERCENT * 10)))
  danger_absolute_score=$((disk_available_mb * 100 / DANGER_FREE_DISK_MB))
  danger_score="$danger_ratio_score"; [ "$danger_absolute_score" -ge "$danger_score" ] || danger_score="$danger_absolute_score"

  if [ "$reserve_score" -lt "$DISK_RESERVE_WORST_SCORE" ]; then
    DISK_RESERVE_WORST_SCORE="$reserve_score"; DISK_WORST_PATH="$disk_label:$configured_path"; DISK_WORST_MOUNT="$disk_mount"
    DISK_AVAILABLE_MB="$disk_available_mb"; DISK_AVAILABLE_TENTHS="$disk_available_tenths"; DISK_TOTAL_MB="$disk_total_mb"
  fi
  if [ "$resume_score" -lt "$DISK_RESUME_WORST_SCORE" ]; then
    DISK_RESUME_WORST_SCORE="$resume_score"; DISK_RESUME_PATH="$disk_label:$configured_path"; DISK_RESUME_MOUNT="$disk_mount"
    DISK_RESUME_AVAILABLE_MB="$disk_available_mb"; DISK_RESUME_AVAILABLE_TENTHS="$disk_available_tenths"
  fi
  if [ "$danger_score" -lt "$DISK_DANGER_WORST_SCORE" ]; then
    DISK_DANGER_WORST_SCORE="$danger_score"; DISK_DANGER_PATH="$disk_label:$configured_path"; DISK_DANGER_MOUNT="$disk_mount"
    DISK_DANGER_AVAILABLE_MB="$disk_available_mb"; DISK_DANGER_AVAILABLE_TENTHS="$disk_available_tenths"
  fi
  if [ "$disk_available_tenths" -lt "$((RESOURCE_RESERVE_PERCENT * 10))" ] \
      || [ "$disk_available_mb" -lt "$MIN_FREE_DISK_MB" ]; then DISK_RESERVE_LOW=yes; fi
  if [ "$disk_available_tenths" -lt "$((RESOURCE_RESUME_PERCENT * 10))" ] \
      || [ "$disk_available_mb" -lt "$MIN_FREE_DISK_MB" ]; then DISK_RESUME_LOW=yes; fi
  if [ "$disk_available_tenths" -lt "$((DANGER_DISK_PERCENT * 10))" ] \
      || [ "$disk_available_mb" -lt "$DANGER_FREE_DISK_MB" ]; then DISK_DANGER_LOW=yes; fi
}

read_disk_metrics() {
  DISK_RESERVE_LOW=no; DISK_RESUME_LOW=no; DISK_DANGER_LOW=no
  DISK_RESERVE_WORST_SCORE=2147483647; DISK_RESUME_WORST_SCORE=2147483647; DISK_DANGER_WORST_SCORE=2147483647
  DISK_WORST_PATH=""; DISK_WORST_MOUNT=""; DISK_RESUME_PATH=""; DISK_RESUME_MOUNT=""; DISK_DANGER_PATH=""; DISK_DANGER_MOUNT=""
  evaluate_disk_path DATA_ROOT "$DATA_ROOT"
  evaluate_disk_path CACHE_DIR "$CACHE_DIR"
  evaluate_disk_path WORK_DIR "${WORK_DIR:-}"
  if [ -n "${OUTPUT:-}" ]; then evaluate_disk_path OUTPUT "$(dirname "$OUTPUT")"; fi
  if [ "${USE_DOCKER:-no}" = yes ]; then evaluate_disk_path DockerRootDir "$DOCKER_ROOT_DIR"; fi
  [ -n "$DISK_WORST_PATH" ] || die '没有可用的磁盘检查路径'
}

log_disk_targets() {
  log "磁盘检查路径：DATA_ROOT=$DATA_ROOT；CACHE_DIR=$CACHE_DIR；WORK_DIR=${WORK_DIR:-未设置}；OUTPUT=${OUTPUT:-未设置}${DOCKER_ROOT_DIR:+；DockerRootDir=$DOCKER_ROOT_DIR}"
}

validate_resource_settings() {
  require_uint_range AID_BUILD_RESERVE_PERCENT "$RESOURCE_RESERVE_PERCENT" 5 50
  require_uint_range AID_BUILD_RESUME_PERCENT "$RESOURCE_RESUME_PERCENT" 6 70
  [ "$RESOURCE_RESUME_PERCENT" -gt "$RESOURCE_RESERVE_PERCENT" ] \
    || die 'AID_BUILD_RESUME_PERCENT 必须大于保留比例'
  require_uint_range AID_BUILD_PREFLIGHT_SAMPLES "$PREFLIGHT_SAMPLES" 2 30
  require_uint_range AID_BUILD_PREFLIGHT_INTERVAL_SECONDS "$PREFLIGHT_INTERVAL_SECONDS" 1 60
  require_uint_range AID_BUILD_GATE_CPU_SAMPLE_SECONDS "$GATE_CPU_SAMPLE_SECONDS" 1 10
  require_uint_range AID_BUILD_MONITOR_INTERVAL_SECONDS "$MONITOR_INTERVAL_SECONDS" 1 60
  require_uint_range AID_BUILD_PRESSURE_SAMPLES "$PRESSURE_SUSTAINED_SAMPLES" 1 20
  require_uint_range AID_BUILD_RECOVERY_SAMPLES "$PRESSURE_RECOVERY_SAMPLES" 1 20
  require_uint_range AID_BUILD_PRESSURE_MAX_WAIT_SECONDS "$PRESSURE_MAX_WAIT_SECONDS" 60 7200
  require_uint_range AID_BUILD_MIN_AVAILABLE_MEMORY_MB "$MIN_AVAILABLE_MEMORY_MB" 256 262144
  require_uint_range AID_BUILD_MIN_FREE_DISK_MB "$MIN_FREE_DISK_MB" 1024 1048576
  require_uint_range AID_BUILD_DANGER_MEMORY_PERCENT "$DANGER_MEMORY_PERCENT" 1 20
  require_uint_range AID_BUILD_DANGER_AVAILABLE_MEMORY_MB "$DANGER_AVAILABLE_MEMORY_MB" 128 8192
  require_uint_range AID_BUILD_DANGER_DISK_PERCENT "$DANGER_DISK_PERCENT" 1 20
  require_uint_range AID_BUILD_DANGER_FREE_DISK_MB "$DANGER_FREE_DISK_MB" 128 102400
  [ "$DANGER_MEMORY_PERCENT" -lt "$RESOURCE_RESERVE_PERCENT" ] \
    || die '内存危险比例必须小于资源保留比例'
  [ "$DANGER_AVAILABLE_MEMORY_MB" -lt "$MIN_AVAILABLE_MEMORY_MB" ] \
    || die '内存绝对危险线必须小于内存绝对安全线'
  [ "$DANGER_DISK_PERCENT" -lt "$RESOURCE_RESERVE_PERCENT" ] \
    || die '磁盘危险比例必须小于资源保留比例'
  [ "$DANGER_FREE_DISK_MB" -lt "$MIN_FREE_DISK_MB" ] \
    || die '磁盘绝对危险线必须小于磁盘绝对安全线'
  require_uint_range AID_BUILD_MANAGED_SWAP_TARGET_MB "$MANAGED_SWAP_TARGET_MB" 512 32768
  require_uint_range AID_BUILD_MANAGED_SWAP_MAX_RAM_MB "$MANAGED_SWAP_MAX_RAM_MB" 1024 32768
  require_uint_range AID_BUILD_IO_WEIGHT "$BUILD_IO_WEIGHT" 10 1000
  require_uint_range AID_BUILD_PAUSED_CPU_MILLI "$PAUSED_CPU_MILLI" 10 250
  [ "$PRESSURE_SUSTAINED_SAMPLES" -le "$PREFLIGHT_SAMPLES" ] \
    || die 'AID_BUILD_PRESSURE_SAMPLES 不能大于准入采样次数'
  case "$MANAGED_SWAP_MODE" in auto|yes|no) ;; *) die 'AID_BUILD_MANAGED_SWAP 仅支持 auto、yes 或 no' ;; esac
}

resource_preflight() {
  cpu_low_streak=0; memory_low_streak=0; disk_low_streak=0
  log_disk_targets
  log "构建资源准入：采样 $PREFLIGHT_SAMPLES 次、间隔 ${PREFLIGHT_INTERVAL_SECONDS}s；最终连续 $PRESSURE_SUSTAINED_SAMPLES 次低于安全线才拒绝"
  read_cpu_counters
  previous_cpu_total="$CPU_COUNTER_TOTAL"; previous_cpu_idle="$CPU_COUNTER_IDLE"
  sample=1
  while [ "$sample" -le "$PREFLIGHT_SAMPLES" ]; do
    sleep "$PREFLIGHT_INTERVAL_SECONDS"
    cpu_idle_tenths_since "$previous_cpu_total" "$previous_cpu_idle"
    previous_cpu_total="$CPU_COUNTER_TOTAL"; previous_cpu_idle="$CPU_COUNTER_IDLE"
    read_memory_metrics
    read_disk_metrics

    if [ "$CPU_IDLE_TENTHS" -lt "$((RESOURCE_RESERVE_PERCENT * 10))" ]; then
      cpu_low_streak=$((cpu_low_streak + 1))
    else
      cpu_low_streak=0
    fi
    if [ "$MEMORY_AVAILABLE_TENTHS" -lt "$((RESOURCE_RESERVE_PERCENT * 10))" ] \
        || [ "$SYSTEM_MEMORY_AVAILABLE_MB" -lt "$MIN_AVAILABLE_MEMORY_MB" ]; then
      memory_low_streak=$((memory_low_streak + 1))
    else
      memory_low_streak=0
    fi
    if [ "$DISK_RESERVE_LOW" = yes ]; then
      disk_low_streak=$((disk_low_streak + 1))
    else
      disk_low_streak=0
    fi
    log "[资源][准入][$sample/$PREFLIGHT_SAMPLES] CPU空闲 $(format_tenths "$CPU_IDLE_TENTHS")%，物理内存可用 ${SYSTEM_MEMORY_AVAILABLE_MB}MiB ($(format_tenths "$MEMORY_AVAILABLE_TENTHS")%)，最差磁盘 ${DISK_WORST_PATH} 挂载点${DISK_WORST_MOUNT} 可用 ${DISK_AVAILABLE_MB}MiB ($(format_tenths "$DISK_AVAILABLE_TENTHS")%)"
    sample=$((sample + 1))
  done

  [ "$cpu_low_streak" -lt "$PRESSURE_SUSTAINED_SAMPLES" ] \
    || die "CPU空闲率连续低于 ${RESOURCE_RESERVE_PERCENT}%，本次不启动构建"
  [ "$memory_low_streak" -lt "$PRESSURE_SUSTAINED_SAMPLES" ] \
    || die "物理内存连续低于 ${RESOURCE_RESERVE_PERCENT}% 或 ${MIN_AVAILABLE_MEMORY_MB}MiB，本次不启动构建"
  [ "$disk_low_streak" -lt "$PRESSURE_SUSTAINED_SAMPLES" ] \
    || die "磁盘空间连续低于安全线：${DISK_WORST_PATH} 挂载点${DISK_WORST_MOUNT}，需保留 ${RESOURCE_RESERVE_PERCENT}% 且不少于 ${MIN_FREE_DISK_MB}MiB"
  GOVERNOR_CPU_TOTAL="$CPU_COUNTER_TOTAL"; GOVERNOR_CPU_IDLE="$CPU_COUNTER_IDLE"
  log '构建资源准入通过；后续压力波动将自动降速或暂停，不会立即取消升级'
}

swap_file_is_active() {
  awk -v path="$1" 'NR > 1 && $1 == path { found=1 } END { exit(found ? 0 : 1) }' /proc/swaps
}

verify_managed_swap_path() {
  managed_swap_cache_dir="$DATA_ROOT/build-cache"
  if [ -e "$managed_swap_cache_dir" ]; then
    [ -d "$managed_swap_cache_dir" ] && [ ! -L "$managed_swap_cache_dir" ] \
      || die "受管Swap父目录类型非法: $managed_swap_cache_dir"
  else
    mkdir -p "$managed_swap_cache_dir" || die "无法创建受管Swap父目录: $managed_swap_cache_dir"
  fi
  managed_swap_dir="$managed_swap_cache_dir/.aid-swap"
  if [ -e "$managed_swap_dir" ]; then
    [ -d "$managed_swap_dir" ] && [ ! -L "$managed_swap_dir" ] \
      || die "受管Swap目录类型非法: $managed_swap_dir"
    [ "$(stat -c '%u' "$managed_swap_dir" 2>/dev/null)" = 0 ] \
      || die "受管Swap目录必须属于root: $managed_swap_dir"
  else
    old_umask="$(umask)"; umask 077
    mkdir -p "$managed_swap_dir" || { umask "$old_umask"; die "无法创建受管Swap目录: $managed_swap_dir"; }
    umask "$old_umask"
  fi
  chmod 700 "$managed_swap_dir" || die "无法设置受管Swap目录权限: $managed_swap_dir"
  [ "$(stat -c '%u:%a' "$managed_swap_dir" 2>/dev/null)" = 0:700 ] \
    || die "受管Swap目录权限必须为root:0700: $managed_swap_dir"
  managed_swap_real_dir="$(CDPATH='' cd "$managed_swap_dir" && pwd -P)"
  swap_parent="$(dirname "$MANAGED_SWAP_FILE")"
  mkdir -p "$swap_parent" || die "无法创建受管Swap父目录: $swap_parent"
  swap_real_parent="$(CDPATH='' cd "$swap_parent" && pwd -P)"
  [ "$swap_real_parent" = "$managed_swap_real_dir" ] \
    || die 'AID_BUILD_MANAGED_SWAP_FILE 必须直接位于 DATA_ROOT/build-cache/.aid-swap'
  [ ! -L "$MANAGED_SWAP_FILE" ] || die "受管Swap文件不能是软链接: $MANAGED_SWAP_FILE"
  MANAGED_SWAP_FILE="$swap_real_parent/$(basename "$MANAGED_SWAP_FILE")"
  MANAGED_SWAP_MARKER="$MANAGED_SWAP_FILE.owner"
  [ ! -L "$MANAGED_SWAP_MARKER" ] || die "受管Swap标记不能是软链接: $MANAGED_SWAP_MARKER"
}

cleanup_swap_temps() {
  rm -f -- "$MANAGED_SWAP_FILE.tmp.$$" "$MANAGED_SWAP_MARKER.tmp.$$"
}

write_swap_file() {
  target_file="$1"; size_mb="$2"; allocation_mode="$3"
  rm -f -- "$target_file"
  if [ "$allocation_mode" = fallocate ] && command -v fallocate >/dev/null 2>&1; then
    : > "$target_file" || return 1
    command -v chattr >/dev/null 2>&1 && chattr +C "$target_file" >/dev/null 2>&1 || true
    fallocate -l "${size_mb}M" "$target_file" || return 1
  else
    command -v dd >/dev/null 2>&1 || return 1
    dd if=/dev/zero of="$target_file" bs=1M count="$size_mb" 2>/dev/null || return 1
  fi
  chmod 600 "$target_file" || return 1
  mkswap "$target_file" >/dev/null 2>&1 || return 1
}

activate_new_managed_swap() {
  size_mb="$1"; allocation_mode="$2"
  swap_tmp="$MANAGED_SWAP_FILE.tmp.$$"
  marker_tmp="$MANAGED_SWAP_MARKER.tmp.$$"
  rm -f -- "$swap_tmp" "$marker_tmp"
  if ! write_swap_file "$swap_tmp" "$size_mb" "$allocation_mode"; then
    cleanup_swap_temps
    return 1
  fi
  printf '%s\n' 'AID_SOURCE_BUILD_SWAP_V1' > "$marker_tmp" || { cleanup_swap_temps; return 1; }
  chmod 600 "$marker_tmp" || { cleanup_swap_temps; return 1; }
  mv -f "$swap_tmp" "$MANAGED_SWAP_FILE" || { cleanup_swap_temps; return 1; }
  mv -f "$marker_tmp" "$MANAGED_SWAP_MARKER" || { cleanup_swap_temps; rm -f -- "$MANAGED_SWAP_FILE"; return 1; }
  if swapon "$MANAGED_SWAP_FILE" >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

read_swap_disk_metrics() {
  swap_probe="$(nearest_existing_disk_path "$1" 2>/dev/null || true)"
  [ -n "$swap_probe" ] || die "无法定位Swap磁盘: $1"
  set -- $(df -Pk "$swap_probe" 2>/dev/null | awk 'NR > 1 { size=$2; available=$4 } END { print size, available }')
  [ "$#" -eq 2 ] && [ "$1" -gt 0 ] 2>/dev/null || die "无法读取Swap磁盘: $1"
  SWAP_DISK_TOTAL_MB=$(( $1 / 1024 ))
  SWAP_DISK_AVAILABLE_MB=$(( $2 / 1024 ))
}

ensure_managed_swap() {
  read_memory_metrics
  # 在线Docker升级会在aid-updater容器内运行本构建器。该容器刻意不授予
  # SYS_ADMIN，不能也不应修改宿主机Swap；构建阶段仍由容器硬限制、进程数
  # 限制和15%动态压力治理共同保护宿主机。直接在宿主机运行时仍准备受管Swap。
  if [ "$USE_DOCKER" = yes ] && { [ -f /.dockerenv ] || grep -Eqa '(docker|containerd|kubepods)' /proc/1/cgroup 2>/dev/null; }; then
    [ "$SYSTEM_SWAP_TOTAL_MB" -ge "$MANAGED_SWAP_TARGET_MB" ] \
      || warn "在线Docker升级不接管宿主机Swap；当前Swap ${SYSTEM_SWAP_TOTAL_MB}MiB，将使用容器硬限制与动态降速保护整机"
    return 0
  fi
  if [ "$MANAGED_SWAP_MODE" = no ]; then
    [ "$SYSTEM_SWAP_TOTAL_MB" -ge "$MANAGED_SWAP_TARGET_MB" ] \
      || warn "受管Swap已禁用；当前Swap ${SYSTEM_SWAP_TOTAL_MB}MiB，低于建议值 ${MANAGED_SWAP_TARGET_MB}MiB"
    return 0
  fi
  if [ "$MANAGED_SWAP_MODE" = auto ] && [ "$SYSTEM_MEMORY_TOTAL_MB" -gt "$MANAGED_SWAP_MAX_RAM_MB" ]; then
    log "物理内存 ${SYSTEM_MEMORY_TOTAL_MB}MiB，高于自动Swap准备范围，保留现有Swap配置"
    return 0
  fi
  if [ "$SYSTEM_SWAP_TOTAL_MB" -ge "$MANAGED_SWAP_TARGET_MB" ]; then
    log "系统Swap ${SYSTEM_SWAP_TOTAL_MB}MiB 已满足构建缓冲要求，不做修改"
    return 0
  fi
  [ "$(id -u)" -eq 0 ] || die '低内存源码构建需要root准备AID受管Swap'
  command -v mkswap >/dev/null 2>&1 || die '缺少mkswap，无法准备AID受管Swap'
  command -v swapon >/dev/null 2>&1 || die '缺少swapon，无法启用AID受管Swap'
  verify_managed_swap_path
  cleanup_swap_temps
  if [ -e "$MANAGED_SWAP_FILE" ] || [ -e "$MANAGED_SWAP_MARKER" ]; then
    [ -f "$MANAGED_SWAP_FILE" ] && [ -f "$MANAGED_SWAP_MARKER" ] \
      || die "受管Swap文件状态不完整，请人工检查: $MANAGED_SWAP_FILE"
    [ "$(sed -n '1p' "$MANAGED_SWAP_MARKER" 2>/dev/null)" = AID_SOURCE_BUILD_SWAP_V1 ] \
      || die "Swap文件不属于AID，拒绝接管: $MANAGED_SWAP_FILE"
    [ "$(stat -c '%u' "$MANAGED_SWAP_FILE" 2>/dev/null)" = 0 ] \
      && [ "$(stat -c '%u' "$MANAGED_SWAP_MARKER" 2>/dev/null)" = 0 ] \
      || die "受管Swap所有者异常，拒绝接管: $MANAGED_SWAP_FILE"
    chmod 600 "$MANAGED_SWAP_FILE" "$MANAGED_SWAP_MARKER" || die '无法修正受管Swap权限'
    if swap_file_is_active "$MANAGED_SWAP_FILE"; then
      warn "AID受管Swap已在使用但系统总Swap仍低于目标；为避免swapoff影响业务，本次安全复用现有文件"
      return 0
    fi
    rm -f -- "$MANAGED_SWAP_FILE" "$MANAGED_SWAP_MARKER"
  fi

  swap_needed_mb=$((MANAGED_SWAP_TARGET_MB - SYSTEM_SWAP_TOTAL_MB))
  read_swap_disk_metrics "$managed_swap_real_dir"
  remaining_disk_mb=$((SWAP_DISK_AVAILABLE_MB - swap_needed_mb))
  disk_reserve_mb=$((SWAP_DISK_TOTAL_MB * RESOURCE_RESERVE_PERCENT / 100))
  disk_reserve_mb="$(max_value "$disk_reserve_mb" "$MIN_FREE_DISK_MB")"
  [ "$remaining_disk_mb" -ge "$disk_reserve_mb" ] \
    || die "创建 ${swap_needed_mb}MiB Swap后磁盘将低于安全线 ${disk_reserve_mb}MiB"
  log "准备AID受管Swap ${swap_needed_mb}MiB（仅位于DATA_ROOT，不写fstab，不修改用户Swap）"
  if ! activate_new_managed_swap "$swap_needed_mb" fallocate; then
    warn '稀疏/CoW Swap启用失败，改用完整写入方式重建'
    rm -f -- "$MANAGED_SWAP_FILE" "$MANAGED_SWAP_MARKER"
    activate_new_managed_swap "$swap_needed_mb" dd \
      || die "AID受管Swap创建失败，请检查文件系统与磁盘: $MANAGED_SWAP_FILE"
  fi
  cleanup_swap_temps
  read_memory_metrics
  log "AID受管Swap已启用并将安全复用: $MANAGED_SWAP_FILE（系统Swap ${SYSTEM_SWAP_TOTAL_MB}MiB）"
}

clamp_profile_memory() {
  total_mb="$1"; percent="$2"; minimum="$3"; maximum="$4"
  profile_value=$((total_mb * percent / 100))
  [ "$profile_value" -ge "$minimum" ] || profile_value="$minimum"
  [ "$profile_value" -le "$maximum" ] || profile_value="$maximum"
  profile_machine_cap=$((total_mb * 70 / 100))
  [ "$profile_value" -le "$profile_machine_cap" ] || profile_value="$profile_machine_cap"
  printf '%s\n' "$profile_value"
}

validate_profile() {
  profile_label="$1"; cpu_milli="$2"; memory_high="$3"; memory_max="$4"; swap_max="$5"; pids_max="$6"
  require_uint_range "$profile_label CPU毫核" "$cpu_milli" 50 256000
  require_uint_range "$profile_label 内存软限制" "$memory_high" 128 262144
  require_uint_range "$profile_label 内存硬限制" "$memory_max" 256 262144
  require_uint_range "$profile_label Swap上限" "$swap_max" 0 65536
  require_uint_range "$profile_label 进程上限" "$pids_max" 32 4096
  [ "$cpu_milli" -le "$CPU_BUILD_CAP_MILLI" ] \
    || die "$profile_label CPU上限超过系统可分配的85%"
  [ "$memory_high" -le "$memory_max" ] || die "$profile_label 内存软限制不能超过硬限制"
  [ "$memory_max" -le "$MEMORY_PROFILE_CAP_MB" ] || die "$profile_label 内存硬限制不能超过物理内存70%"
}

configure_resource_profiles() {
  read_memory_metrics
  CPU_COUNT="$(getconf _NPROCESSORS_ONLN 2>/dev/null || true)"
  case "$CPU_COUNT" in ''|*[!0-9]*|0) CPU_COUNT="$(nproc 2>/dev/null || printf 1)" ;; esac
  require_uint_range 系统CPU核数 "$CPU_COUNT" 1 256
  CPU_TOTAL_MILLI=$((CPU_COUNT * 1000))
  CPU_BUILD_CAP_MILLI=$((CPU_TOTAL_MILLI * (100 - RESOURCE_RESERVE_PERCENT) / 100))
  if [ "$CPU_COUNT" -le 4 ]; then
    default_heavy_cpu=$((CPU_TOTAL_MILLI / 2))
  else
    default_heavy_cpu=$((CPU_TOTAL_MILLI * 60 / 100))
    [ "$default_heavy_cpu" -le 8000 ] || default_heavy_cpu=8000
  fi
  [ "$default_heavy_cpu" -le "$CPU_BUILD_CAP_MILLI" ] || default_heavy_cpu="$CPU_BUILD_CAP_MILLI"
  default_light_cpu=$((CPU_TOTAL_MILLI / 4))
  [ "$default_light_cpu" -ge 250 ] || default_light_cpu=250
  [ "$default_light_cpu" -le 4000 ] || default_light_cpu=4000
  [ "$default_light_cpu" -le "$CPU_BUILD_CAP_MILLI" ] || default_light_cpu="$CPU_BUILD_CAP_MILLI"

  MAVEN_CPU_MILLI="${AID_BUILD_MAVEN_CPU_MILLI:-$default_heavy_cpu}"
  NODE_CPU_MILLI="${AID_BUILD_NODE_CPU_MILLI:-$default_heavy_cpu}"
  GO_CPU_MILLI="${AID_BUILD_GO_CPU_MILLI:-$default_light_cpu}"
  PACKAGE_CPU_MILLI="${AID_BUILD_PACKAGE_CPU_MILLI:-$default_light_cpu}"
  MAVEN_MEMORY_HIGH_MB="${AID_BUILD_MAVEN_MEMORY_HIGH_MB:-$(clamp_profile_memory "$SYSTEM_MEMORY_TOTAL_MB" 25 512 1536)}"
  MAVEN_MEMORY_MAX_MB="${AID_BUILD_MAVEN_MEMORY_MAX_MB:-$(clamp_profile_memory "$SYSTEM_MEMORY_TOTAL_MB" 35 768 2048)}"
  NODE_MEMORY_HIGH_MB="${AID_BUILD_NODE_MEMORY_HIGH_MB:-$(clamp_profile_memory "$SYSTEM_MEMORY_TOTAL_MB" 30 640 1536)}"
  NODE_MEMORY_MAX_MB="${AID_BUILD_NODE_MEMORY_MAX_MB:-$(clamp_profile_memory "$SYSTEM_MEMORY_TOTAL_MB" 40 768 2304)}"
  GO_MEMORY_HIGH_MB="${AID_BUILD_GO_MEMORY_HIGH_MB:-$(clamp_profile_memory "$SYSTEM_MEMORY_TOTAL_MB" 13 384 768)}"
  GO_MEMORY_MAX_MB="${AID_BUILD_GO_MEMORY_MAX_MB:-$(clamp_profile_memory "$SYSTEM_MEMORY_TOTAL_MB" 19 512 1024)}"
  PACKAGE_MEMORY_HIGH_MB="${AID_BUILD_PACKAGE_MEMORY_HIGH_MB:-$(clamp_profile_memory "$SYSTEM_MEMORY_TOTAL_MB" 13 384 768)}"
  PACKAGE_MEMORY_MAX_MB="${AID_BUILD_PACKAGE_MEMORY_MAX_MB:-$(clamp_profile_memory "$SYSTEM_MEMORY_TOTAL_MB" 19 512 1024)}"
  MAVEN_SWAP_MAX_MB="${AID_BUILD_MAVEN_SWAP_MAX_MB:-3072}"
  NODE_SWAP_MAX_MB="${AID_BUILD_NODE_SWAP_MAX_MB:-3072}"
  GO_SWAP_MAX_MB="${AID_BUILD_GO_SWAP_MAX_MB:-1024}"
  PACKAGE_SWAP_MAX_MB="${AID_BUILD_PACKAGE_SWAP_MAX_MB:-1024}"
  MAVEN_PIDS_MAX="${AID_BUILD_MAVEN_PIDS_MAX:-512}"
  NODE_PIDS_MAX="${AID_BUILD_NODE_PIDS_MAX:-512}"
  GO_PIDS_MAX="${AID_BUILD_GO_PIDS_MAX:-256}"
  PACKAGE_PIDS_MAX="${AID_BUILD_PACKAGE_PIDS_MAX:-128}"
  MEMORY_PROFILE_CAP_MB=$((SYSTEM_MEMORY_TOTAL_MB * 70 / 100))

  validate_profile Maven "$MAVEN_CPU_MILLI" "$MAVEN_MEMORY_HIGH_MB" "$MAVEN_MEMORY_MAX_MB" "$MAVEN_SWAP_MAX_MB" "$MAVEN_PIDS_MAX"
  validate_profile Node "$NODE_CPU_MILLI" "$NODE_MEMORY_HIGH_MB" "$NODE_MEMORY_MAX_MB" "$NODE_SWAP_MAX_MB" "$NODE_PIDS_MAX"
  validate_profile Go "$GO_CPU_MILLI" "$GO_MEMORY_HIGH_MB" "$GO_MEMORY_MAX_MB" "$GO_SWAP_MAX_MB" "$GO_PIDS_MAX"
  validate_profile 打包 "$PACKAGE_CPU_MILLI" "$PACKAGE_MEMORY_HIGH_MB" "$PACKAGE_MEMORY_MAX_MB" "$PACKAGE_SWAP_MAX_MB" "$PACKAGE_PIDS_MAX"
  [ "$PAUSED_CPU_MILLI" -lt "$MAVEN_CPU_MILLI" ] \
    && [ "$PAUSED_CPU_MILLI" -lt "$NODE_CPU_MILLI" ] \
    && [ "$PAUSED_CPU_MILLI" -lt "$GO_CPU_MILLI" ] \
    && [ "$PAUSED_CPU_MILLI" -lt "$PACKAGE_CPU_MILLI" ] \
    || die '暂停CPU配额必须小于所有阶段的正常CPU配额'

  default_maven_heap=$((MAVEN_MEMORY_MAX_MB - 512)); [ "$default_maven_heap" -ge 256 ] || default_maven_heap=256; [ "$default_maven_heap" -le 768 ] || default_maven_heap=768
  default_node_heap=$((NODE_MEMORY_MAX_MB - 384)); [ "$default_node_heap" -ge 384 ] || default_node_heap=384; [ "$default_node_heap" -le 1024 ] || default_node_heap=1024
  MAVEN_HEAP_MB="${AID_BUILD_MAVEN_HEAP_MB:-$default_maven_heap}"
  MAVEN_METASPACE_MB="${AID_BUILD_MAVEN_METASPACE_MB:-256}"
  NODE_HEAP_MB="${AID_BUILD_NODE_HEAP_MB:-$default_node_heap}"
  require_uint_range AID_BUILD_MAVEN_HEAP_MB "$MAVEN_HEAP_MB" 256 4096
  require_uint_range AID_BUILD_MAVEN_METASPACE_MB "$MAVEN_METASPACE_MB" 128 2048
  require_uint_range AID_BUILD_NODE_HEAP_MB "$NODE_HEAP_MB" 384 8192
  [ "$((MAVEN_HEAP_MB + MAVEN_METASPACE_MB + 128))" -le "$MAVEN_MEMORY_MAX_MB" ] \
    || die 'Maven堆、元空间与基础开销超过Maven内存硬限制'
  [ "$((NODE_HEAP_MB + 256))" -le "$NODE_MEMORY_MAX_MB" ] \
    || die 'Node堆与基础开销超过Node内存硬限制'
  MAVEN_OPTS_VALUE="-Xms128m -Xmx${MAVEN_HEAP_MB}m -XX:MaxMetaspaceSize=${MAVEN_METASPACE_MB}m -XX:+UseSerialGC"
  NODE_OPTIONS_VALUE="--max-old-space-size=${NODE_HEAP_MB}"
  GO_MEMORY_LIMIT_MB="${AID_BUILD_GO_MEMORY_LIMIT_MB:-$GO_MEMORY_HIGH_MB}"
  GO_MAX_PROCS="${AID_BUILD_GO_MAX_PROCS:-$(( (GO_CPU_MILLI + 999) / 1000 ))}"
  require_uint_range AID_BUILD_GO_MEMORY_LIMIT_MB "$GO_MEMORY_LIMIT_MB" 128 262144
  require_uint_range AID_BUILD_GO_MAX_PROCS "$GO_MAX_PROCS" 1 256
  [ "$GO_MEMORY_LIMIT_MB" -le "$GO_MEMORY_MAX_MB" ] || die 'Go运行时内存上限不能超过Go cgroup内存硬限制'
  [ "$GO_MAX_PROCS" -le "$CPU_COUNT" ] || die 'Go运行时线程数不能超过系统CPU核数'
  GO_MEMORY_LIMIT="${GO_MEMORY_LIMIT_MB}MiB"

  log "[资源][档位] CPU总核数 $CPU_COUNT，单构建任务上限不超过 ${CPU_BUILD_CAP_MILLI}m（预留 ${RESOURCE_RESERVE_PERCENT}%）"
  log "[资源][档位] Maven ${MAVEN_CPU_MILLI}m/${MAVEN_MEMORY_HIGH_MB}MiB high/${MAVEN_MEMORY_MAX_MB}MiB max；Node ${NODE_CPU_MILLI}m/${NODE_MEMORY_HIGH_MB}MiB high/${NODE_MEMORY_MAX_MB}MiB max"
  log "[资源][档位] Go ${GO_CPU_MILLI}m/${GO_MEMORY_HIGH_MB}MiB high/${GO_MEMORY_MAX_MB}MiB max；打包 ${PACKAGE_CPU_MILLI}m/${PACKAGE_MEMORY_HIGH_MB}MiB high/${PACKAGE_MEMORY_MAX_MB}MiB max"
}

instant_stage_gate() {
  gate_label="$1"
  gate_cpu_low_streak=0; gate_cpu_worst_tenths=1000; gate_sample=1
  read_cpu_counters
  gate_previous_cpu_total="$CPU_COUNTER_TOTAL"; gate_previous_cpu_idle="$CPU_COUNTER_IDLE"
  while [ "$gate_sample" -le "$PRESSURE_SUSTAINED_SAMPLES" ]; do
    sleep "$GATE_CPU_SAMPLE_SECONDS"
    cpu_idle_tenths_since "$gate_previous_cpu_total" "$gate_previous_cpu_idle"
    gate_previous_cpu_total="$CPU_COUNTER_TOTAL"; gate_previous_cpu_idle="$CPU_COUNTER_IDLE"
    [ "$CPU_IDLE_TENTHS" -ge "$gate_cpu_worst_tenths" ] || gate_cpu_worst_tenths="$CPU_IDLE_TENTHS"
    if [ "$CPU_IDLE_TENTHS" -lt "$((RESOURCE_RESERVE_PERCENT * 10))" ]; then
      gate_cpu_low_streak=$((gate_cpu_low_streak + 1))
    else
      gate_cpu_low_streak=0
    fi
    gate_sample=$((gate_sample + 1))
  done
  GOVERNOR_CPU_TOTAL="$CPU_COUNTER_TOTAL"; GOVERNOR_CPU_IDLE="$CPU_COUNTER_IDLE"
  read_memory_metrics
  read_disk_metrics
  memory_safe_mb=$((SYSTEM_MEMORY_TOTAL_MB * RESOURCE_RESERVE_PERCENT / 100))
  memory_safe_mb="$(max_value "$memory_safe_mb" "$MIN_AVAILABLE_MEMORY_MB")"
  [ "$gate_cpu_low_streak" -lt "$PRESSURE_SUSTAINED_SAMPLES" ] \
    || die "$gate_label 启动前CPU连续短采样均低于 ${RESOURCE_RESERVE_PERCENT}%（最后$(format_tenths "$CPU_IDLE_TENTHS")%，最差$(format_tenths "$gate_cpu_worst_tenths")%），构建命令尚未启动"
  [ "$SYSTEM_MEMORY_AVAILABLE_MB" -ge "$memory_safe_mb" ] \
    && [ "$MEMORY_AVAILABLE_TENTHS" -ge "$((RESOURCE_RESERVE_PERCENT * 10))" ] \
    || die "$gate_label 启动前物理内存低于安全线，构建命令尚未启动"
  [ "$DISK_RESERVE_LOW" = no ] \
    || die "$gate_label 启动前磁盘低于安全线：${DISK_WORST_PATH} 挂载点${DISK_WORST_MOUNT}，可用${DISK_AVAILABLE_MB}MiB"
  log "[资源][$gate_label][门禁] CPU最后/最差短采样$(format_tenths "$CPU_IDLE_TENTHS")%/$(format_tenths "$gate_cpu_worst_tenths")%（${PRESSURE_SUSTAINED_SAMPLES}次×${GATE_CPU_SAMPLE_SECONDS}s），内存${SYSTEM_MEMORY_AVAILABLE_MB}MiB，最差磁盘${DISK_WORST_PATH}@${DISK_WORST_MOUNT} ${DISK_AVAILABLE_MB}MiB"
}

load_stage_profile() {
  case "$1" in
    maven)
      STAGE_CPU_MILLI="$MAVEN_CPU_MILLI"; STAGE_MEMORY_HIGH_MB="$MAVEN_MEMORY_HIGH_MB"
      STAGE_MEMORY_MAX_MB="$MAVEN_MEMORY_MAX_MB"; STAGE_SWAP_MAX_MB="$MAVEN_SWAP_MAX_MB"; STAGE_PIDS_MAX="$MAVEN_PIDS_MAX"
      ;;
    node)
      STAGE_CPU_MILLI="$NODE_CPU_MILLI"; STAGE_MEMORY_HIGH_MB="$NODE_MEMORY_HIGH_MB"
      STAGE_MEMORY_MAX_MB="$NODE_MEMORY_MAX_MB"; STAGE_SWAP_MAX_MB="$NODE_SWAP_MAX_MB"; STAGE_PIDS_MAX="$NODE_PIDS_MAX"
      ;;
    go)
      STAGE_CPU_MILLI="$GO_CPU_MILLI"; STAGE_MEMORY_HIGH_MB="$GO_MEMORY_HIGH_MB"
      STAGE_MEMORY_MAX_MB="$GO_MEMORY_MAX_MB"; STAGE_SWAP_MAX_MB="$GO_SWAP_MAX_MB"; STAGE_PIDS_MAX="$GO_PIDS_MAX"
      ;;
    package)
      STAGE_CPU_MILLI="$PACKAGE_CPU_MILLI"; STAGE_MEMORY_HIGH_MB="$PACKAGE_MEMORY_HIGH_MB"
      STAGE_MEMORY_MAX_MB="$PACKAGE_MEMORY_MAX_MB"; STAGE_SWAP_MAX_MB="$PACKAGE_SWAP_MAX_MB"; STAGE_PIDS_MAX="$PACKAGE_PIDS_MAX"
      ;;
    *) die "未知构建资源档位: $1" ;;
  esac
}

cpu_milli_to_decimal() {
  printf '%s.%03d\n' "$(( $1 / 1000 ))" "$(( $1 % 1000 ))"
}

cleanup_host_cgroups() {
  old_ifs="$IFS"; IFS='|'
  for cgroup_path in $ACTIVE_HOST_CGROUP_PATHS; do
    [ -n "$cgroup_path" ] || continue
    cleanup_attempt=0
    while [ "$cleanup_attempt" -lt 10 ] && [ -d "$cgroup_path" ]; do
      rmdir "$cgroup_path" >/dev/null 2>&1 && break
      sleep 0.1
      cleanup_attempt=$((cleanup_attempt + 1))
    done
    [ ! -d "$cgroup_path" ] || warn "临时cgroup未能自动清理，请检查: $cgroup_path"
  done
  IFS="$old_ifs"
  ACTIVE_HOST_CGROUP_PATHS=""
  ACTIVE_HOST_CGROUP_MODE=""
  HOST_V2_CGROUP=""
  HOST_CPU_CGROUP=""
  HOST_MEMORY_CGROUP=""
  HOST_PIDS_CGROUP=""
  HOST_IO_CGROUP=""
}

terminate_direct_host_cgroup() {
  case "$ACTIVE_HOST_CGROUP_MODE" in
    direct-v2) task_file="$HOST_V2_CGROUP/cgroup.procs" ;;
    direct-v1) task_file="$HOST_PIDS_CGROUP/tasks" ;;
    *) return 0 ;;
  esac
  [ -f "$task_file" ] || return 0
  for task_pid in $(sed -n '/^[0-9][0-9]*$/p' "$task_file" 2>/dev/null); do
    kill -CONT "$task_pid" >/dev/null 2>&1 || true
    kill -TERM "$task_pid" >/dev/null 2>&1 || true
  done
  attempts=0
  while [ "$attempts" -lt 10 ] && [ -n "$(sed -n '1p' "$task_file" 2>/dev/null)" ]; do
    sleep 0.1
    attempts=$((attempts + 1))
  done
  for task_pid in $(sed -n '/^[0-9][0-9]*$/p' "$task_file" 2>/dev/null); do
    kill -KILL "$task_pid" >/dev/null 2>&1 || true
  done
  attempts=0
  while [ "$attempts" -lt 10 ] && [ -n "$(sed -n '1p' "$task_file" 2>/dev/null)" ]; do
    sleep 0.1
    attempts=$((attempts + 1))
  done
}

cleanup_active_stage() {
  if [ -n "$ACTIVE_DOCKER_CONTAINER" ] && command -v docker >/dev/null 2>&1; then
    docker unpause "$ACTIVE_DOCKER_CONTAINER" >/dev/null 2>&1 || true
    docker rm -f "$ACTIVE_DOCKER_CONTAINER" >/dev/null 2>&1 || true
  fi
  ACTIVE_DOCKER_CONTAINER=""
  ACTIVE_STAGE_PAUSED=no
  if [ -n "$ACTIVE_HOST_PID" ] && kill -0 "$ACTIVE_HOST_PID" >/dev/null 2>&1; then
    kill -CONT "$ACTIVE_HOST_PID" >/dev/null 2>&1 || true
    kill -TERM "$ACTIVE_HOST_PID" >/dev/null 2>&1 || true
  fi
  terminate_direct_host_cgroup
  if [ -n "$ACTIVE_HOST_UNIT" ] && command -v systemctl >/dev/null 2>&1; then
    systemctl stop "$ACTIVE_HOST_UNIT.scope" >/dev/null 2>&1 || true
    systemctl reset-failed "$ACTIVE_HOST_UNIT.scope" >/dev/null 2>&1 || true
  fi
  ACTIVE_HOST_PID=""
  if [ -n "$ACTIVE_HOST_PID_FILE" ]; then rm -f -- "$ACTIVE_HOST_PID_FILE"; fi
  ACTIVE_HOST_PID_FILE=""
  ACTIVE_HOST_UNIT=""
  cleanup_host_cgroups
}

cleanup_on_exit() {
  cleanup_active_stage
}

trap cleanup_on_exit EXIT
trap 'cleanup_active_stage; exit 130' HUP INT TERM

probe_docker_resource_features() {
  probe_name="aid-source-build-probe-$$"
  ACTIVE_DOCKER_CONTAINER="$probe_name"
  docker rm -f "$probe_name" >/dev/null 2>&1 || true
  if ! docker run --rm --name "$probe_name" --entrypoint /bin/sh \
      --cpus 0.100 --memory 64m --memory-swap 128m --pids-limit 32 \
      "$GIT_IMAGE" -c 'exit 0' >/dev/null 2>&1; then
    docker rm -f "$probe_name" >/dev/null 2>&1 || true
    die 'Docker不支持CPU、内存、内存+Swap或进程数强制限制，拒绝无隔离构建'
  fi
  docker rm -f "$probe_name" >/dev/null 2>&1 || true
  if docker info 2>&1 | grep -Eqi 'no swap limit support|swap limit capabilities.*false'; then
    warn '宿主机内核未启用cgroup Swap记账；Docker仍强制传入memory-swap，物理内存硬限制与全机危险线继续生效'
  fi

  DOCKER_USE_RESERVATION=yes
  if ! docker run --rm --name "$probe_name" --entrypoint /bin/sh \
      --memory 64m --memory-reservation 32m "$GIT_IMAGE" -c 'exit 0' >/dev/null 2>&1; then
    DOCKER_USE_RESERVATION=no
    warn 'Docker不支持memory-reservation；仍保留内存硬限制、Swap限制与全机压力监控'
  fi
  docker rm -f "$probe_name" >/dev/null 2>&1 || true

  DOCKER_USE_BLKIO=yes
  if ! docker run --rm --name "$probe_name" --entrypoint /bin/sh \
      --blkio-weight "$BUILD_IO_WEIGHT" "$GIT_IMAGE" -c 'exit 0' >/dev/null 2>&1; then
    DOCKER_USE_BLKIO=no
    warn 'Docker不支持blkio权重；构建仍使用CPU/内存/pids硬隔离，I/O动态保护由全机压力监控兜底'
  fi
  docker rm -f "$probe_name" >/dev/null 2>&1 || true
  ACTIVE_DOCKER_CONTAINER=""
}

start_docker_stage() {
  stage_name="$1"; stage_label="$2"; shift 2
  instant_stage_gate "$stage_label"
  load_stage_profile "$stage_name"
  STAGE_SEQUENCE=$((STAGE_SEQUENCE + 1))
  ACTIVE_DOCKER_CONTAINER="aid-source-build-$$-$STAGE_SEQUENCE"
  stage_cpus="$(cpu_milli_to_decimal "$STAGE_CPU_MILLI")"
  stage_memory_swap_mb=$((STAGE_MEMORY_MAX_MB + STAGE_SWAP_MAX_MB))
  log "[资源][$stage_label] Docker隔离：CPU ${STAGE_CPU_MILLI}m，内存 ${STAGE_MEMORY_HIGH_MB}/${STAGE_MEMORY_MAX_MB}MiB，Swap额外 ${STAGE_SWAP_MAX_MB}MiB，pids ${STAGE_PIDS_MAX}"
  if [ "$DOCKER_USE_RESERVATION" = yes ] && [ "$DOCKER_USE_BLKIO" = yes ]; then
    docker run --rm --name "$ACTIVE_DOCKER_CONTAINER" --cpus "$stage_cpus" \
      --memory-reservation "${STAGE_MEMORY_HIGH_MB}m" --memory "${STAGE_MEMORY_MAX_MB}m" \
      --memory-swap "${stage_memory_swap_mb}m" --pids-limit "$STAGE_PIDS_MAX" \
      --blkio-weight "$BUILD_IO_WEIGHT" "$@" &
  elif [ "$DOCKER_USE_RESERVATION" = yes ]; then
    docker run --rm --name "$ACTIVE_DOCKER_CONTAINER" --cpus "$stage_cpus" \
      --memory-reservation "${STAGE_MEMORY_HIGH_MB}m" --memory "${STAGE_MEMORY_MAX_MB}m" \
      --memory-swap "${stage_memory_swap_mb}m" --pids-limit "$STAGE_PIDS_MAX" "$@" &
  elif [ "$DOCKER_USE_BLKIO" = yes ]; then
    docker run --rm --name "$ACTIVE_DOCKER_CONTAINER" --cpus "$stage_cpus" \
      --memory "${STAGE_MEMORY_MAX_MB}m" --memory-swap "${stage_memory_swap_mb}m" \
      --pids-limit "$STAGE_PIDS_MAX" --blkio-weight "$BUILD_IO_WEIGHT" "$@" &
  else
    docker run --rm --name "$ACTIVE_DOCKER_CONTAINER" --cpus "$stage_cpus" \
      --memory "${STAGE_MEMORY_MAX_MB}m" --memory-swap "${stage_memory_swap_mb}m" \
      --pids-limit "$STAGE_PIDS_MAX" "$@" &
  fi
  ACTIVE_RUNNER_PID=$!
}

find_cgroup2_mount() {
  awk '$3 == "cgroup2" { print $2; exit }' /proc/mounts
}

find_cgroup1_mount() {
  controller="$1"
  awk -v wanted="$controller" '$3 == "cgroup" {
    count=split($4, options, ",")
    for (i=1; i<=count; i++) if (options[i] == wanted) { print $2; exit }
  }' /proc/mounts
}

self_cgroup1_path() {
  controller="$1"
  awk -F: -v wanted="$controller" '{
    count=split($2, controllers, ",")
    for (i=1; i<=count; i++) if (controllers[i] == wanted) { print $3; exit }
  }' /proc/self/cgroup
}

write_and_verify() {
  control_file="$1"; expected="$2"
  printf '%s\n' "$expected" > "$control_file" 2>/dev/null || return 1
  actual="$(sed -n '1p' "$control_file" 2>/dev/null)"
  [ "$actual" = "$expected" ]
}

configure_cgroup_v2() {
  cgroup_path="$1"
  memory_high_bytes=$((STAGE_MEMORY_HIGH_MB * 1024 * 1024))
  memory_max_bytes=$((STAGE_MEMORY_MAX_MB * 1024 * 1024))
  swap_max_bytes=$((STAGE_SWAP_MAX_MB * 1024 * 1024))
  cpu_quota=$((STAGE_CPU_MILLI * 100))
  for control_file in cpu.max memory.high memory.max pids.max; do
    [ -f "$cgroup_path/$control_file" ] || return 1
  done
  write_and_verify "$cgroup_path/cpu.max" "$cpu_quota 100000" || return 1
  write_and_verify "$cgroup_path/memory.high" "$memory_high_bytes" || return 1
  write_and_verify "$cgroup_path/memory.max" "$memory_max_bytes" || return 1
  write_and_verify "$cgroup_path/pids.max" "$STAGE_PIDS_MAX" || return 1
  if [ -f "$cgroup_path/memory.oom.group" ]; then
    write_and_verify "$cgroup_path/memory.oom.group" 1 || return 1
  fi
  if [ -f "$cgroup_path/memory.swap.max" ]; then
    write_and_verify "$cgroup_path/memory.swap.max" "$swap_max_bytes" || return 1
  else
    warn 'cgroup v2未提供memory.swap.max；物理内存硬限制与全机危险线仍生效'
  fi
  if [ -f "$cgroup_path/io.weight" ]; then
    if ! write_and_verify "$cgroup_path/io.weight" "default $BUILD_IO_WEIGHT"; then
      warn 'cgroup v2 I/O权重不可写；宿主机构建将继续使用ionice低优先级'
      HOST_IO_CGROUP=""
    fi
  else
    warn 'cgroup v2未启用I/O控制器；宿主机构建将继续使用ionice低优先级'
    HOST_IO_CGROUP=""
  fi
  HOST_V2_CGROUP="$cgroup_path"
  HOST_CPU_CGROUP="$cgroup_path"
  HOST_MEMORY_CGROUP="$cgroup_path"
  HOST_PIDS_CGROUP="$cgroup_path"
  HOST_IO_CGROUP="${HOST_IO_CGROUP:-$cgroup_path}"
}

configure_cgroup_v1() {
  cpu_path="$1"; memory_path="$2"; pids_path="$3"; io_path="$4"
  memory_high_bytes=$((STAGE_MEMORY_HIGH_MB * 1024 * 1024))
  memory_max_bytes=$((STAGE_MEMORY_MAX_MB * 1024 * 1024))
  memory_swap_bytes=$(((STAGE_MEMORY_MAX_MB + STAGE_SWAP_MAX_MB) * 1024 * 1024))
  cpu_quota=$((STAGE_CPU_MILLI * 100))
  for control_file in "$cpu_path/cpu.cfs_period_us" "$cpu_path/cpu.cfs_quota_us" \
      "$memory_path/memory.soft_limit_in_bytes" "$memory_path/memory.limit_in_bytes" \
      "$pids_path/pids.max"; do
    [ -f "$control_file" ] || return 1
  done
  write_and_verify "$cpu_path/cpu.cfs_period_us" 100000 || return 1
  write_and_verify "$cpu_path/cpu.cfs_quota_us" "$cpu_quota" || return 1
  write_and_verify "$memory_path/memory.soft_limit_in_bytes" "$memory_high_bytes" || return 1
  write_and_verify "$memory_path/memory.limit_in_bytes" "$memory_max_bytes" || return 1
  if [ -f "$memory_path/memory.memsw.limit_in_bytes" ]; then
    write_and_verify "$memory_path/memory.memsw.limit_in_bytes" "$memory_swap_bytes" || return 1
  else
    warn 'cgroup v1未启用memory.memsw；物理内存硬限制与全机危险线仍生效'
  fi
  write_and_verify "$pids_path/pids.max" "$STAGE_PIDS_MAX" || return 1
  io_control_applied=no
  if [ -n "$io_path" ] && [ -f "$io_path/blkio.weight" ]; then
    if ! write_and_verify "$io_path/blkio.weight" "$BUILD_IO_WEIGHT"; then
      warn 'cgroup v1 I/O权重不可写；宿主机构建将继续使用ionice低优先级'
    else
      io_control_applied=yes
    fi
  else
    warn 'cgroup v1未启用blkio控制器；宿主机构建将继续使用ionice低优先级'
  fi
  HOST_CPU_CGROUP="$cpu_path"
  HOST_MEMORY_CGROUP="$memory_path"
  HOST_PIDS_CGROUP="$pids_path"
  if [ "$io_control_applied" = yes ]; then HOST_IO_CGROUP="$io_path"; else HOST_IO_CGROUP=""; fi
}

wait_for_stopped_process() {
  runner_pid="$1"; attempts=0
  while [ "$attempts" -lt 100 ]; do
    [ -r "/proc/$runner_pid/status" ] || return 1
    process_state="$(awk '/^State:/ { print $2; exit }' "/proc/$runner_pid/status" 2>/dev/null)"
    case "$process_state" in T|t) return 0 ;; esac
    sleep 0.1
    attempts=$((attempts + 1))
  done
  return 1
}

wait_for_stage_pid_file() {
  pid_file="$1"; runner_pid="$2"; attempts=0
  while [ "$attempts" -lt 100 ]; do
    kill -0 "$runner_pid" >/dev/null 2>&1 || return 1
    if [ -s "$pid_file" ]; then
      HOST_COMMAND_PID="$(sed -n '1p' "$pid_file" 2>/dev/null)"
      case "$HOST_COMMAND_PID" in ''|*[!0-9]*) return 1 ;; esac
      wait_for_stopped_process "$HOST_COMMAND_PID" && return 0
    fi
    sleep 0.1
    attempts=$((attempts + 1))
  done
  return 1
}

systemd_control_group() {
  systemctl show -p ControlGroup "$1.scope" 2>/dev/null | sed -n 's/^ControlGroup=//p' | head -n 1
}

prepare_v1_paths_for_relative_group() {
  relative_group="$1"
  cpu_mount="$(find_cgroup1_mount cpu)"; memory_mount="$(find_cgroup1_mount memory)"
  pids_mount="$(find_cgroup1_mount pids)"; io_mount="$(find_cgroup1_mount blkio)"
  [ -n "$cpu_mount" ] && [ -n "$memory_mount" ] && [ -n "$pids_mount" ] \
    || return 1
  HOST_CPU_CGROUP="$cpu_mount$relative_group"
  HOST_MEMORY_CGROUP="$memory_mount$relative_group"
  HOST_PIDS_CGROUP="$pids_mount$relative_group"
  if [ -n "$io_mount" ]; then HOST_IO_CGROUP="$io_mount$relative_group"; else HOST_IO_CGROUP=""; fi
}

setup_systemd_host_cgroup() {
  command_pid="$1"; unit_name="$2"
  wait_for_stopped_process "$command_pid" || return 1
  relative_group="$(systemd_control_group "$unit_name")"
  [ -n "$relative_group" ] || return 1
  cgroup2_mount="$(find_cgroup2_mount)"
  if [ -n "$cgroup2_mount" ]; then
    HOST_V2_CGROUP="$cgroup2_mount$relative_group"
    [ -d "$HOST_V2_CGROUP" ] || return 1
    configure_cgroup_v2 "$HOST_V2_CGROUP" || return 1
    grep -Fqx "$command_pid" "$HOST_V2_CGROUP/cgroup.procs" 2>/dev/null || return 1
    ACTIVE_HOST_CGROUP_MODE=systemd-v2
  else
    prepare_v1_paths_for_relative_group "$relative_group" || return 1
    configure_cgroup_v1 "$HOST_CPU_CGROUP" "$HOST_MEMORY_CGROUP" "$HOST_PIDS_CGROUP" "$HOST_IO_CGROUP" || return 1
    grep -Fqx "$command_pid" "$HOST_CPU_CGROUP/tasks" 2>/dev/null || return 1
    grep -Fqx "$command_pid" "$HOST_MEMORY_CGROUP/tasks" 2>/dev/null || return 1
    grep -Fqx "$command_pid" "$HOST_PIDS_CGROUP/tasks" 2>/dev/null || return 1
    ACTIVE_HOST_CGROUP_MODE=systemd-v1
  fi
  ACTIVE_HOST_CGROUP_PATHS=""
}

create_direct_v2_cgroup() {
  runner_pid="$1"; group_name="$2"
  cgroup2_mount="$(find_cgroup2_mount)"; [ -n "$cgroup2_mount" ] || return 1
  self_relative="$(awk -F: '$1 == "0" { print $3; exit }' /proc/self/cgroup)"
  [ -n "$self_relative" ] || self_relative=/
  parent_path="$cgroup2_mount$self_relative"
  HOST_V2_CGROUP="$parent_path/$group_name"
  mkdir "$HOST_V2_CGROUP" 2>/dev/null || return 1
  ACTIVE_HOST_CGROUP_PATHS="$HOST_V2_CGROUP"
  configure_cgroup_v2 "$HOST_V2_CGROUP" || return 1
  printf '%s\n' "$runner_pid" > "$HOST_V2_CGROUP/cgroup.procs" 2>/dev/null || return 1
  grep -Fqx "$runner_pid" "$HOST_V2_CGROUP/cgroup.procs" 2>/dev/null || return 1
  ACTIVE_HOST_CGROUP_MODE=direct-v2
}

create_direct_v1_cgroup() {
  runner_pid="$1"; group_name="$2"
  cpu_mount="$(find_cgroup1_mount cpu)"; memory_mount="$(find_cgroup1_mount memory)"
  pids_mount="$(find_cgroup1_mount pids)"; io_mount="$(find_cgroup1_mount blkio)"
  cpu_relative="$(self_cgroup1_path cpu)"; memory_relative="$(self_cgroup1_path memory)"
  pids_relative="$(self_cgroup1_path pids)"; io_relative="$(self_cgroup1_path blkio)"
  [ -n "$cpu_mount" ] && [ -n "$memory_mount" ] && [ -n "$pids_mount" ] \
    && [ -n "$cpu_relative" ] && [ -n "$memory_relative" ] && [ -n "$pids_relative" ] || return 1
  HOST_CPU_CGROUP="$cpu_mount$cpu_relative/$group_name"
  HOST_MEMORY_CGROUP="$memory_mount$memory_relative/$group_name"
  HOST_PIDS_CGROUP="$pids_mount$pids_relative/$group_name"
  HOST_IO_CGROUP=""
  if [ -n "$io_mount" ] && [ -n "$io_relative" ]; then HOST_IO_CGROUP="$io_mount$io_relative/$group_name"; fi
  ACTIVE_HOST_CGROUP_PATHS=""
  for cgroup_path in "$HOST_CPU_CGROUP" "$HOST_MEMORY_CGROUP" "$HOST_PIDS_CGROUP" "$HOST_IO_CGROUP"; do
    [ -n "$cgroup_path" ] || continue
    case "|$ACTIVE_HOST_CGROUP_PATHS|" in
      *"|$cgroup_path|"*) ;;
      *)
        if [ -n "$ACTIVE_HOST_CGROUP_PATHS" ]; then
          ACTIVE_HOST_CGROUP_PATHS="$ACTIVE_HOST_CGROUP_PATHS|$cgroup_path"
        else
          ACTIVE_HOST_CGROUP_PATHS="$cgroup_path"
        fi
        ;;
    esac
  done
  old_ifs="$IFS"; IFS='|'
  for cgroup_path in $ACTIVE_HOST_CGROUP_PATHS; do mkdir "$cgroup_path" 2>/dev/null || { IFS="$old_ifs"; return 1; }; done
  IFS="$old_ifs"
  configure_cgroup_v1 "$HOST_CPU_CGROUP" "$HOST_MEMORY_CGROUP" "$HOST_PIDS_CGROUP" "$HOST_IO_CGROUP" || return 1
  old_ifs="$IFS"; IFS='|'
  for cgroup_path in $ACTIVE_HOST_CGROUP_PATHS; do
    printf '%s\n' "$runner_pid" > "$cgroup_path/tasks" 2>/dev/null || { IFS="$old_ifs"; return 1; }
    grep -Fqx "$runner_pid" "$cgroup_path/tasks" 2>/dev/null || { IFS="$old_ifs"; return 1; }
  done
  IFS="$old_ifs"
  ACTIVE_HOST_CGROUP_MODE=direct-v1
}

start_host_stage() {
  stage_name="$1"; stage_label="$2"; work_dir="$3"; shift 3
  instant_stage_gate "$stage_label"
  load_stage_profile "$stage_name"
  STAGE_SEQUENCE=$((STAGE_SEQUENCE + 1))
  host_group="aid-source-build-$$-$STAGE_SEQUENCE"
  ACTIVE_HOST_PID_FILE="$WORK_DIR/.${host_group}.pid"
  rm -f -- "$ACTIVE_HOST_PID_FILE"
  log "[资源][$stage_label] 宿主机隔离：CPU ${STAGE_CPU_MILLI}m，内存 ${STAGE_MEMORY_HIGH_MB}/${STAGE_MEMORY_MAX_MB}MiB，Swap额外 ${STAGE_SWAP_MAX_MB}MiB，pids ${STAGE_PIDS_MAX}"
  systemd_cgroup_ready=no
  if command -v systemd-run >/dev/null 2>&1 && command -v systemctl >/dev/null 2>&1 \
      && [ "$(ps -p 1 -o comm= 2>/dev/null | tr -d '[:space:]')" = systemd ]; then
    ACTIVE_HOST_UNIT="$host_group"
    systemd-run --scope --quiet --unit "$ACTIVE_HOST_UNIT" -- \
      sh -c 'umask 077; printf "%s\n" "$$" > "$1"; pid_file="$1"; work_dir="$2"; shift 2; kill -STOP "$$"; cd "$work_dir" || exit 111; rm -f -- "$pid_file"; if command -v ionice >/dev/null 2>&1; then exec ionice -c 2 -n 7 nice -n 10 "$@"; else exec nice -n 10 "$@"; fi' \
      aid-stage "$ACTIVE_HOST_PID_FILE" "$work_dir" "$@" &
    ACTIVE_RUNNER_PID=$!
    if wait_for_stage_pid_file "$ACTIVE_HOST_PID_FILE" "$ACTIVE_RUNNER_PID"; then
      ACTIVE_HOST_PID="$HOST_COMMAND_PID"
      # 主流systemd会在scope内exec目标命令，但这里不依赖PID相等：PID文件追踪已STOP的包装命令，
      # systemd-run的PID只用于同步wait真实退出码；只有命令PID已在scope且限制回读成功后才CONT。
      if setup_systemd_host_cgroup "$ACTIVE_HOST_PID" "$ACTIVE_HOST_UNIT"; then
        systemd_cgroup_ready=yes
      fi
    fi
    if [ "$systemd_cgroup_ready" != yes ]; then
      cleanup_active_stage
      wait "$ACTIVE_RUNNER_PID" >/dev/null 2>&1 || true
      warn 'systemd临时scope未能完整设置并回读强制限制；构建命令尚未启动，改用当前层级的直接cgroup隔离'
      ACTIVE_HOST_PID_FILE="$WORK_DIR/.${host_group}-direct.pid"
      rm -f -- "$ACTIVE_HOST_PID_FILE"
    fi
  fi
  if [ "$systemd_cgroup_ready" != yes ]; then
    ACTIVE_HOST_UNIT=""
    sh -c 'umask 077; printf "%s\n" "$$" > "$1"; pid_file="$1"; work_dir="$2"; shift 2; kill -STOP "$$"; cd "$work_dir" || exit 111; rm -f -- "$pid_file"; if command -v ionice >/dev/null 2>&1; then exec ionice -c 2 -n 7 nice -n 10 "$@"; else exec nice -n 10 "$@"; fi' \
      aid-stage "$ACTIVE_HOST_PID_FILE" "$work_dir" "$@" &
    ACTIVE_RUNNER_PID=$!
    wait_for_stage_pid_file "$ACTIVE_HOST_PID_FILE" "$ACTIVE_RUNNER_PID" || { cleanup_active_stage; die '宿主机构建进程无法在隔离前安全暂停'; }
    ACTIVE_HOST_PID="$HOST_COMMAND_PID"
    [ "$ACTIVE_HOST_PID" = "$ACTIVE_RUNNER_PID" ] \
      || { cleanup_active_stage; die '宿主机构建进程PID不一致，拒绝无法追踪退出码的构建'; }
    if [ -n "$(find_cgroup2_mount)" ]; then
      create_direct_v2_cgroup "$ACTIVE_HOST_PID" "${host_group}-direct" \
        || { cleanup_active_stage; die 'cgroup v2 CPU/内存/pids限制不可用，构建命令尚未启动'; }
    else
      create_direct_v1_cgroup "$ACTIVE_HOST_PID" "${host_group}-direct" \
        || { cleanup_active_stage; die 'cgroup v1 CPU/内存/pids限制不可用，构建命令尚未启动'; }
    fi
  fi
  log "[资源][$stage_label] 已回读确认 ${ACTIVE_HOST_CGROUP_MODE} 强制限制"
  kill -CONT "$ACTIVE_HOST_PID" >/dev/null 2>&1 || { cleanup_active_stage; die '无法启动已隔离的宿主机构建进程'; }
}

set_stage_cpu_milli() {
  backend="$1"; cpu_milli="$2"
  if [ "$backend" = docker ]; then
    docker update --cpus "$(cpu_milli_to_decimal "$cpu_milli")" "$ACTIVE_DOCKER_CONTAINER" >/dev/null 2>&1
  elif [ "$ACTIVE_HOST_CGROUP_MODE" = systemd-v2 ] || [ "$ACTIVE_HOST_CGROUP_MODE" = direct-v2 ]; then
    write_and_verify "$HOST_CPU_CGROUP/cpu.max" "$((cpu_milli * 100)) 100000"
  else
    write_and_verify "$HOST_CPU_CGROUP/cpu.cfs_quota_us" "$((cpu_milli * 100))"
  fi
}

pause_stage() {
  backend="$1"
  if [ "$backend" = docker ]; then
    docker pause "$ACTIVE_DOCKER_CONTAINER" >/dev/null 2>&1 || return 1
  else
    set_stage_cpu_milli host "$PAUSED_CPU_MILLI" || return 1
    if [ -n "$HOST_MEMORY_CGROUP" ] && [ -f "$HOST_MEMORY_CGROUP/memory.reclaim" ]; then
      printf '%s\n' $((64 * 1024 * 1024)) > "$HOST_MEMORY_CGROUP/memory.reclaim" 2>/dev/null || true
    fi
  fi
  ACTIVE_STAGE_PAUSED=yes
}

resume_stage_throttled() {
  backend="$1"; throttled_cpu="$2"
  if [ "$backend" = docker ]; then
    set_stage_cpu_milli "$backend" "$throttled_cpu" || return 1
    docker unpause "$ACTIVE_DOCKER_CONTAINER" >/dev/null 2>&1 || return 1
  else
    set_stage_cpu_milli "$backend" "$throttled_cpu" || return 1
  fi
  ACTIVE_STAGE_PAUSED=no
}

terminate_active_stage() {
  backend="$1"
  if [ "$backend" = docker ]; then
    docker unpause "$ACTIVE_DOCKER_CONTAINER" >/dev/null 2>&1 || true
    docker rm -f "$ACTIVE_DOCKER_CONTAINER" >/dev/null 2>&1 || true
  elif [ -n "$ACTIVE_HOST_UNIT" ]; then
    systemctl stop "$ACTIVE_HOST_UNIT.scope" >/dev/null 2>&1 || true
  elif [ -n "$ACTIVE_HOST_PID" ]; then
    kill -CONT "$ACTIVE_HOST_PID" >/dev/null 2>&1 || true
    kill -TERM "$ACTIVE_HOST_PID" >/dev/null 2>&1 || true
    terminate_direct_host_cgroup
  fi
}

runner_is_active() {
  runner_pid="$1"
  kill -0 "$runner_pid" >/dev/null 2>&1 || return 1
  if [ -r "/proc/$runner_pid/stat" ]; then
    runner_state="$(awk '{ print $3 }' "/proc/$runner_pid/stat" 2>/dev/null)"
    [ "$runner_state" != Z ] || return 1
  fi
  return 0
}

governed_stage_is_running() {
  control_backend="$1"; control_runner_pid="$2"
  if [ "$control_backend" = host ] && [ -n "$ACTIVE_HOST_PID" ]; then
    runner_is_active "$ACTIVE_HOST_PID" || return 1
  else
    runner_is_active "$control_runner_pid" || return 1
  fi
  if [ "$control_backend" = docker ]; then
    [ "$(docker inspect --format '{{.State.Running}}' "$ACTIVE_DOCKER_CONTAINER" 2>/dev/null)" = true ] || return 1
  fi
  return 0
}

monitor_stage() {
  backend="$1"; stage_label="$2"; normal_cpu="$3"; runner_pid="$4"
  throttled_cpu=$((normal_cpu / 2)); [ "$throttled_cpu" -ge 100 ] || throttled_cpu=100
  pressure_streak=0; safe_recovery_streak=0; full_recovery_streak=0; pressure_started=0; last_pause_log=0; pressure_level=0
  read_cpu_counters
  previous_cpu_total="$CPU_COUNTER_TOTAL"; previous_cpu_idle="$CPU_COUNTER_IDLE"
  GOVERNOR_CPU_TOTAL="$CPU_COUNTER_TOTAL"; GOVERNOR_CPU_IDLE="$CPU_COUNTER_IDLE"
  while governed_stage_is_running "$backend" "$runner_pid"; do
    sleep "$MONITOR_INTERVAL_SECONDS"
    governed_stage_is_running "$backend" "$runner_pid" || break
    cpu_idle_tenths_since "$previous_cpu_total" "$previous_cpu_idle"
    previous_cpu_total="$CPU_COUNTER_TOTAL"; previous_cpu_idle="$CPU_COUNTER_IDLE"
    GOVERNOR_CPU_TOTAL="$CPU_COUNTER_TOTAL"; GOVERNOR_CPU_IDLE="$CPU_COUNTER_IDLE"
    read_memory_metrics
    read_disk_metrics
    memory_danger_mb=$((SYSTEM_MEMORY_TOTAL_MB * DANGER_MEMORY_PERCENT / 100))
    memory_danger_mb="$(max_value "$memory_danger_mb" "$DANGER_AVAILABLE_MEMORY_MB")"
    if [ "$SYSTEM_MEMORY_AVAILABLE_MB" -lt "$memory_danger_mb" ] \
        || [ "$MEMORY_AVAILABLE_TENTHS" -lt "$((DANGER_MEMORY_PERCENT * 10))" ]; then
      governed_stage_is_running "$backend" "$runner_pid" || break
      MONITOR_ABORT_REASON="物理内存进入硬危险线：${SYSTEM_MEMORY_AVAILABLE_MB}MiB ($(format_tenths "$MEMORY_AVAILABLE_TENTHS")%)"
      return 2
    fi
    if [ "$DISK_DANGER_LOW" = yes ]; then
      governed_stage_is_running "$backend" "$runner_pid" || break
      MONITOR_ABORT_REASON="磁盘进入硬危险线：${DISK_DANGER_PATH} 挂载点${DISK_DANGER_MOUNT}，可用${DISK_DANGER_AVAILABLE_MB}MiB ($(format_tenths "$DISK_DANGER_AVAILABLE_TENTHS")%)"
      return 2
    fi

    memory_safe_mb=$((SYSTEM_MEMORY_TOTAL_MB * RESOURCE_RESERVE_PERCENT / 100))
    memory_safe_mb="$(max_value "$memory_safe_mb" "$MIN_AVAILABLE_MEMORY_MB")"
    memory_resume_mb=$((SYSTEM_MEMORY_TOTAL_MB * RESOURCE_RESUME_PERCENT / 100))
    memory_resume_mb="$(max_value "$memory_resume_mb" "$MIN_AVAILABLE_MEMORY_MB")"
    pressure=no
    pressure_detail=""
    if [ "$CPU_IDLE_TENTHS" -lt "$((RESOURCE_RESERVE_PERCENT * 10))" ]; then pressure=yes; pressure_detail="CPU空闲$(format_tenths "$CPU_IDLE_TENTHS")%"; fi
    if [ "$SYSTEM_MEMORY_AVAILABLE_MB" -lt "$memory_safe_mb" ] \
        || [ "$MEMORY_AVAILABLE_TENTHS" -lt "$((RESOURCE_RESERVE_PERCENT * 10))" ]; then pressure=yes; pressure_detail="${pressure_detail:+$pressure_detail，}内存可用${SYSTEM_MEMORY_AVAILABLE_MB}MiB"; fi
    if [ "$DISK_RESERVE_LOW" = yes ]; then pressure=yes; pressure_detail="${pressure_detail:+$pressure_detail，}磁盘${DISK_WORST_PATH}@${DISK_WORST_MOUNT}可用${DISK_AVAILABLE_MB}MiB/$(format_tenths "$DISK_AVAILABLE_TENTHS")%"; fi

    now_epoch="$(date +%s)"
    if [ "$pressure" = yes ]; then
      pressure_streak=$((pressure_streak + 1)); safe_recovery_streak=0; full_recovery_streak=0
      [ "$pressure_started" -ne 0 ] || pressure_started="$now_epoch"
      if [ "$pressure_level" -eq 0 ] && [ "$pressure_streak" -ge "$PRESSURE_SUSTAINED_SAMPLES" ]; then
        governed_stage_is_running "$backend" "$runner_pid" || break
        if ! set_stage_cpu_milli "$backend" "$throttled_cpu"; then
          governed_stage_is_running "$backend" "$runner_pid" || break
          MONITOR_ABORT_REASON='动态CPU降速失败，拒绝继续无治理构建'; return 2
        fi
        pressure_level=1
        log "[资源][$stage_label] 系统压力持续（$pressure_detail），构建CPU降至 ${throttled_cpu}m；内存high将优先回收/换出"
      elif [ "$pressure_level" -eq 1 ] && [ "$pressure_streak" -ge "$((PRESSURE_SUSTAINED_SAMPLES * 2))" ]; then
        governed_stage_is_running "$backend" "$runner_pid" || break
        if ! pause_stage "$backend"; then
          governed_stage_is_running "$backend" "$runner_pid" || break
          MONITOR_ABORT_REASON='构建暂停失败，拒绝继续无治理构建'; return 2
        fi
        pressure_level=2; last_pause_log="$now_epoch"
        log "[资源][$stage_label] 压力仍未恢复，暂停构建CPU；暂停不视为释放内存，继续依赖memory high与Swap回收"
      fi
      if [ "$pressure_level" -eq 2 ] && [ "$((now_epoch - last_pause_log))" -ge 60 ]; then
        log "[资源][$stage_label] 构建仍在安全暂停，等待系统回到 ${RESOURCE_RESERVE_PERCENT}%（已等待 $((now_epoch - pressure_started))s）"
        last_pause_log="$now_epoch"
      fi
      if [ "$((now_epoch - pressure_started))" -ge "$PRESSURE_MAX_WAIT_SECONDS" ]; then
        governed_stage_is_running "$backend" "$runner_pid" || break
        MONITOR_ABORT_REASON="系统持续低于 ${RESOURCE_RESERVE_PERCENT}% 达 ${PRESSURE_MAX_WAIT_SECONDS}s"
        return 2
      fi
    else
      pressure_streak=0; pressure_started=0
      safe_recovery_streak=$((safe_recovery_streak + 1))
      if [ "$CPU_IDLE_TENTHS" -ge "$((RESOURCE_RESUME_PERCENT * 10))" ] \
          && [ "$SYSTEM_MEMORY_AVAILABLE_MB" -ge "$memory_resume_mb" ] \
          && [ "$MEMORY_AVAILABLE_TENTHS" -ge "$((RESOURCE_RESUME_PERCENT * 10))" ] \
          && [ "$DISK_RESUME_LOW" = no ]; then
        full_recovery_streak=$((full_recovery_streak + 1))
      else
        full_recovery_streak=0
      fi
      if [ "$pressure_level" -eq 2 ] && [ "$safe_recovery_streak" -ge "$PRESSURE_RECOVERY_SAMPLES" ]; then
        governed_stage_is_running "$backend" "$runner_pid" || break
        if ! resume_stage_throttled "$backend" "$throttled_cpu"; then
          governed_stage_is_running "$backend" "$runner_pid" || break
          MONITOR_ABORT_REASON='构建恢复失败'; return 2
        fi
        pressure_level=1; safe_recovery_streak=0; full_recovery_streak=0
        log "[资源][$stage_label] 系统恢复到 ${RESOURCE_RESERVE_PERCENT}% 安全线，以 ${throttled_cpu}m 继续慢速构建"
      elif [ "$pressure_level" -eq 1 ] && [ "$full_recovery_streak" -ge "$PRESSURE_RECOVERY_SAMPLES" ]; then
        governed_stage_is_running "$backend" "$runner_pid" || break
        if ! set_stage_cpu_milli "$backend" "$normal_cpu"; then
          governed_stage_is_running "$backend" "$runner_pid" || break
          MONITOR_ABORT_REASON='CPU配额恢复失败'; return 2
        fi
        pressure_level=0; safe_recovery_streak=0; full_recovery_streak=0
        log "[资源][$stage_label] 系统资源持续达到 ${RESOURCE_RESUME_PERCENT}%，恢复正常构建配额 ${normal_cpu}m"
      fi
    fi
  done
  return 0
}

finish_governed_stage() {
  backend="$1"; stage_label="$2"
  load_stage_profile "$3"
  monitor_status=0
  monitor_stage "$backend" "$stage_label" "$STAGE_CPU_MILLI" "$ACTIVE_RUNNER_PID" || monitor_status=$?
  if [ "$monitor_status" -eq 2 ]; then
    if governed_stage_is_running "$backend" "$ACTIVE_RUNNER_PID"; then
      terminate_active_stage "$backend"
      wait "$ACTIVE_RUNNER_PID" >/dev/null 2>&1 || true
      cleanup_active_stage
      die "$stage_label 已安全终止：$MONITOR_ABORT_REASON；旧版本尚未切换"
    fi
  fi
  if wait "$ACTIVE_RUNNER_PID"; then stage_status=0; else stage_status=$?; fi
  if [ "$backend" = docker ]; then
    docker unpause "$ACTIVE_DOCKER_CONTAINER" >/dev/null 2>&1 || true
    docker rm -f "$ACTIVE_DOCKER_CONTAINER" >/dev/null 2>&1 || true
    ACTIVE_DOCKER_CONTAINER=""
  else
    if [ -n "$ACTIVE_HOST_UNIT" ]; then
      systemctl stop "$ACTIVE_HOST_UNIT.scope" >/dev/null 2>&1 || true
      systemctl reset-failed "$ACTIVE_HOST_UNIT.scope" >/dev/null 2>&1 || true
      ACTIVE_HOST_UNIT=""
    else
      # 主进程退出后仍可能留下同阶段子进程；只清理由本次构建创建的cgroup。
      terminate_direct_host_cgroup
      cleanup_host_cgroups
    fi
    ACTIVE_HOST_PID=""
    cleanup_host_cgroups
  fi
  ACTIVE_STAGE_PAUSED=no
  if [ -n "$ACTIVE_HOST_PID_FILE" ]; then rm -f -- "$ACTIVE_HOST_PID_FILE"; fi
  ACTIVE_HOST_PID_FILE=""
  if [ "$stage_status" -eq 137 ]; then
    die "$stage_label 触发资源硬限制并被终止；可通过合法的AID_BUILD阶段配额调整后重试，旧版本尚未切换"
  fi
  return "$stage_status"
}

run_docker_stage() {
  stage_name="$1"; stage_label="$2"; shift 2
  start_docker_stage "$stage_name" "$stage_label" "$@"
  finish_governed_stage docker "$stage_label" "$stage_name"
}

run_host_stage() {
  stage_name="$1"; stage_label="$2"; work_dir="$3"; shift 3
  start_host_stage "$stage_name" "$stage_label" "$work_dir" "$@"
  finish_governed_stage host "$stage_label" "$stage_name"
}

usage() {
  cat <<'EOF'
用法: build-release-from-source.sh --version <版本> --output <tar.gz> [--work-dir <目录>] [--forge auto|github|gitee]

环境变量:
  AID_DATA_ROOT               数据目录，默认 /data/aid
  AID_SOURCE_BUILD_MODE       源码构建模式：docker（强制容器构建）、host（强制宿主机构建）；官方部署会显式传入
  AID_NPM_REGISTRY            覆盖首选 npm 镜像
  AID_MAVEN_MIRROR_URL        覆盖首选 Maven 镜像
  AID_MAVEN_FALLBACK_URL      覆盖备用 Maven 仓库
  AID_GO_PROXY                覆盖 Go 模块代理链
  AID_DEPENDENCY_REGION       依赖线路：auto、cn 或 global；auto 按服务器公网出口地区选择
  AID_DOCKER_MIRRORS          Docker Hub 国内镜像前缀，逗号分隔；自动测速排序
  AID_DOCKER_CN_MIRROR        兼容旧版单镜像设置（新配置优先使用 AID_DOCKER_MIRRORS）
  AID_JDK_DOWNLOAD_URL        覆盖 Temurin OpenJDK 17.0.20 下载地址
  AID_*_IMAGE                 覆盖 Docker 构建镜像
  AID_BUILD_RESERVE_PERCENT   构建前及构建中系统资源保留比例，默认15
  AID_BUILD_RESUME_PERCENT    暂停后恢复阈值，默认20
  AID_BUILD_MIN_AVAILABLE_MEMORY_MB  构建前物理内存绝对安全线，默认512MiB
  AID_BUILD_DANGER_AVAILABLE_MEMORY_MB  构建中物理内存绝对危险线，默认256MiB
  AID_BUILD_MIN_FREE_DISK_MB  构建前磁盘绝对安全线，默认10240MiB
  AID_BUILD_PREFLIGHT_SAMPLES 构建前采样次数，默认6；采样间隔默认5秒
  AID_BUILD_GATE_CPU_SAMPLE_SECONDS 每阶段启动前CPU独立短采样时长，默认1秒；范围1-10秒
  AID_BUILD_PRESSURE_SAMPLES  连续低压确认次数，默认2；不能大于准入采样次数
  AID_BUILD_PRESSURE_MAX_WAIT_SECONDS  持续低于15%的最长等待，默认900秒
  AID_BUILD_MANAGED_SWAP      AID受管Swap策略：auto、yes或no，默认auto；目标总量4096MiB
  AID_BUILD_MANAGED_SWAP_FILE 受管Swap文件，默认位于 DATA_ROOT/build-cache/.aid-swap
  AID_BUILD_*_CPU_MILLI       Maven/Node/Go/打包阶段CPU毫核上限
  AID_BUILD_*_MEMORY_HIGH_MB  各阶段内存软限制（Maven/Node/Go/Package）
  AID_BUILD_*_MEMORY_MAX_MB   各阶段物理内存硬限制（Maven/Node/Go/Package）
  AID_BUILD_*_SWAP_MAX_MB     各阶段额外Swap上限（Maven/Node/Go/Package）
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version) [ "$#" -ge 2 ] || die '--version 缺少值'; VERSION="$2"; shift 2 ;;
    --output) [ "$#" -ge 2 ] || die '--output 缺少值'; OUTPUT="$2"; shift 2 ;;
    --work-dir) [ "$#" -ge 2 ] || die '--work-dir 缺少值'; WORK_DIR="$2"; shift 2 ;;
    --forge) [ "$#" -ge 2 ] || die '--forge 缺少值'; FORGE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "未知参数: $1" ;;
  esac
done

case "$VERSION" in
  ''|*[!0-9A-Za-z.-]*) die "版本号非法: ${VERSION:-空}" ;;
esac
case "$VERSION" in
  [0-9]*.[0-9]*.[0-9]*) ;;
  *) die "版本号不符合 SemVer: $VERSION" ;;
esac
case "$FORGE" in auto|github|gitee) ;; *) die "源码平台仅支持 auto、github 或 gitee" ;; esac
case "$SOURCE_BUILD_MODE" in auto|docker|host) ;; *) die 'AID_SOURCE_BUILD_MODE 仅支持 auto、docker 或 host' ;; esac
case "$DEPENDENCY_INSTALL_MODE" in auto|manual) ;; *) die 'AID_DEPENDENCY_INSTALL_MODE 仅支持 auto 或 manual' ;; esac
case "$DEPENDENCY_REGION" in auto|cn|global) ;; *) die 'AID_DEPENDENCY_REGION 仅支持 auto、cn 或 global' ;; esac
[ -n "$OUTPUT" ] || die '--output 不能为空'
case "$OUTPUT" in /*) ;; *) OUTPUT="$(pwd)/$OUTPUT" ;; esac

TAG="v$VERSION"
if [ -z "$WORK_DIR" ]; then
  WORK_DIR="$DATA_ROOT/source-build/$TAG"
fi
case "$WORK_DIR" in /*) ;; *) WORK_DIR="$(pwd)/$WORK_DIR" ;; esac

command -v tar >/dev/null 2>&1 || die '未检测到 tar'
command -v sha256sum >/dev/null 2>&1 || die '未检测到 sha256sum'

USE_DOCKER=no
case "$SOURCE_BUILD_MODE" in
  docker)
    command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 \
      || die 'Docker 容器源码构建需要可用的 Docker Engine'
    USE_DOCKER=yes
    log '源码构建模式：Docker 容器源码构建'
    ;;
  host)
    command -v git >/dev/null 2>&1 \
      || die '非 Docker 宿主机源码构建需要 Git；请先完成手动部署依赖安装后重试'
    log '源码构建模式：非 Docker 宿主机源码构建（不会探测、拉取或调用 Docker）'
    ;;
  auto)
    # 仅保留给开发者直接调用构建器的兼容行为；AID 官方部署和升级会显式指定 docker 或 host。
    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
      USE_DOCKER=yes
    fi
    if ! command -v git >/dev/null 2>&1 && [ "$USE_DOCKER" != yes ]; then
      die '未检测到 Git，且 Docker 不可用；请先安装 Git'
    fi
    log "源码构建模式：兼容自动选择（${USE_DOCKER}）"
    ;;
esac

detect_dependency_region() {
  case "$DEPENDENCY_REGION" in
    cn|global) RESOLVED_DEPENDENCY_REGION="$DEPENDENCY_REGION" ;;
    auto)
      detected_country=''
      if command -v curl >/dev/null 2>&1; then
        for country_url in https://ipinfo.io/country https://ifconfig.co/country-iso; do
          detected_country="$(curl -fsSL --connect-timeout 3 --max-time 6 "$country_url" 2>/dev/null \
            | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]' | head -c 2 || true)"
          case "$detected_country" in [A-Z][A-Z]) break ;; *) detected_country='' ;; esac
        done
      elif command -v wget >/dev/null 2>&1; then
        detected_country="$(wget -qO- --timeout=6 https://ipinfo.io/country 2>/dev/null \
          | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]' | head -c 2 || true)"
        case "$detected_country" in [A-Z][A-Z]) ;; *) detected_country='' ;; esac
      fi
      if [ "$detected_country" = CN ]; then
        RESOLVED_DEPENDENCY_REGION=cn
      elif [ -n "$detected_country" ]; then
        RESOLVED_DEPENDENCY_REGION=global
      elif [ "${SOURCE_FORGE:-}" = gitee ]; then
        RESOLVED_DEPENDENCY_REGION=cn
      elif [ "${SOURCE_FORGE:-}" = github ]; then
        RESOLVED_DEPENDENCY_REGION=global
      elif command -v curl >/dev/null 2>&1 && curl -fsSI --connect-timeout 5 --max-time 8 https://github.com >/dev/null 2>&1; then
        RESOLVED_DEPENDENCY_REGION=global
      elif command -v wget >/dev/null 2>&1 && wget -q --spider --timeout=8 https://github.com >/dev/null 2>&1; then
        RESOLVED_DEPENDENCY_REGION=global
      else
        RESOLVED_DEPENDENCY_REGION=cn
      fi ;;
  esac
}

normalize_docker_mirror() {
  mirror_value="$1"
  mirror_value="$(printf '%s' "$mirror_value" | tr '[:upper:]' '[:lower:]')"
  mirror_value="${mirror_value#https://}"
  mirror_value="${mirror_value#http://}"
  mirror_value="${mirror_value%/}"
  printf '%s\n' "$mirror_value" | grep -Eq '^[a-z0-9.-]+(:[0-9]+)?(/[a-z0-9._/-]+)?$' || return 1
  printf '%s\n' "$mirror_value"
}

probe_docker_mirror() {
  mirror_probe="$1"
  mirror_registry="${mirror_probe%%/*}"
  command -v curl >/dev/null 2>&1 || return 1
  mirror_result="$(curl -sS -o /dev/null --connect-timeout 3 --max-time 6 \
    -w '%{http_code} %{time_total}' "https://$mirror_registry/v2/" 2>/dev/null || true)"
  mirror_code="${mirror_result%% *}"
  mirror_seconds="${mirror_result#* }"
  case "$mirror_code" in 200|401) ;; *) return 1 ;; esac
  awk -v value="$mirror_seconds" 'BEGIN { printf "%d\n", value * 1000 }'
}

resolve_docker_mirror_order() {
  [ "$DOCKER_MIRRORS_RESOLVED" -eq 0 ] || return 0
  mirror_ranked=''; mirror_deferred=''; mirror_seen=' '
  old_ifs="$IFS"; IFS=','
  for mirror_candidate in $DOCKER_MIRRORS; do
    IFS="$old_ifs"
    mirror_candidate="$(printf '%s' "$mirror_candidate" | tr -d '[:space:]')"
    [ -n "$mirror_candidate" ] || { IFS=','; continue; }
    mirror_normalized="$(normalize_docker_mirror "$mirror_candidate" 2>/dev/null || true)"
    if [ -z "$mirror_normalized" ]; then
      warn "已忽略非法 Docker 镜像地址: $mirror_candidate"
      IFS=','; continue
    fi
    case "$mirror_seen" in *" $mirror_normalized "*) IFS=','; continue ;; esac
    mirror_seen="$mirror_seen$mirror_normalized "
    if mirror_latency="$(probe_docker_mirror "$mirror_normalized" 2>/dev/null)"; then
      log "Docker镜像测速: $mirror_normalized ${mirror_latency}ms"
      mirror_ranked="${mirror_ranked}${mirror_latency} ${mirror_normalized}\n"
    else
      warn "Docker镜像测速不可达，保留为末位重试: $mirror_normalized"
      mirror_deferred="$mirror_deferred$mirror_normalized "
    fi
    IFS=','
  done
  IFS="$old_ifs"
  mirror_sorted=''
  if [ -n "$mirror_ranked" ]; then
    mirror_sorted="$(printf '%b' "$mirror_ranked" | sort -n -k1,1 | awk '{printf "%s ", $2}')"
  fi
  DOCKER_MIRROR_ORDER="$mirror_sorted$mirror_deferred"
  DOCKER_MIRROR_ORDER="${DOCKER_MIRROR_ORDER% }"
  DOCKER_MIRRORS_RESOLVED=1
  if [ -n "$DOCKER_MIRROR_ORDER" ]; then
    log "Docker国内镜像尝试顺序: $(printf '%s' "$DOCKER_MIRROR_ORDER" | sed 's/ / -> /g')"
  else
    warn '未配置有效 Docker 国内镜像，将只尝试 Docker Hub 官方地址'
  fi
}

dockerhub_mirror_image() {
  mirror_prefix="$1"
  image="$2"
  first="${image%%/*}"
  case "$image" in
    */*)
      case "$first" in *.*|*:*|localhost) return 1 ;; esac
      printf '%s/%s\n' "$mirror_prefix" "$image" ;;
    *) printf '%s/library/%s\n' "$mirror_prefix" "$image" ;;
  esac
}

docker_image_digest() {
  case "$1" in
    alpine/git:2.47.2) echo 'sha256:062a01ad7a0eb17cff382bc5e26086b4d710e56dfdfdf001109a49b6d9bd378c' ;;
    maven:3.9.9-eclipse-temurin-17) echo 'sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc' ;;
    node:22.22.0-bookworm-slim) echo 'sha256:dd9d21971ec4395903fa6143c2b9267d048ae01ca6d3ea96f16cb30df6187d94' ;;
    golang:1.22.12-bookworm) echo 'sha256:3d699e4d15d0f8f13c9195c0632a16702b8cbdece2955af1c23b37ae5d55a253' ;;
    debian:bookworm-slim) echo 'sha256:7b140f374b289a7c2befc338f42ebe6441b7ea838a042bbd5acbfca6ec875818' ;;
    nginx:1.25-alpine) echo 'sha256:516475cc129da42866742567714ddc681e5eed7b9ee0b9e9c015e464b4221a00' ;;
    docker:27-cli) echo 'sha256:851f91d241214e7c6db86513b270d58776379aacc5eb9c4a87e5b47115e3065c' ;;
    *) return 1 ;;
  esac
}

image_with_digest() {
  image="$1"; digest="$2"
  printf '%s@%s\n' "${image%:*}" "$digest"
}

local_image_matches_digest() {
  docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$1" 2>/dev/null \
    | grep -Fq "@$2"
}

pull_docker_image() {
  pull_image_ref="$1"
  if command -v timeout >/dev/null 2>&1; then
    timeout "$IMAGE_PULL_TIMEOUT_SECONDS" docker pull "$pull_image_ref"
  else
    docker pull "$pull_image_ref"
  fi
}

probe_docker_image_manifest() {
  manifest_probe_ref="$1"
  if command -v timeout >/dev/null 2>&1; then
    timeout "$IMAGE_MANIFEST_PROBE_TIMEOUT_SECONDS" docker manifest inspect "$manifest_probe_ref" >/dev/null 2>&1
  else
    docker manifest inspect "$manifest_probe_ref" >/dev/null 2>&1
  fi
}

try_docker_mirrors() {
  mirror_try_image="$1"; mirror_try_label="$2"; mirror_try_digest="$3"
  resolve_docker_mirror_order
  mirror_ready=''; mirror_deferred=''
  for mirror_try_prefix in $DOCKER_MIRROR_ORDER; do
    mirror_try_ref="$(dockerhub_mirror_image "$mirror_try_prefix" "$mirror_try_image" 2>/dev/null || true)"
    [ -n "$mirror_try_ref" ] || continue
    if probe_docker_image_manifest "$mirror_try_ref"; then
      log "Docker镜像清单可用: $mirror_try_ref"
      mirror_ready="$mirror_ready$mirror_try_prefix "
    else
      warn "Docker镜像清单预检失败，保留为末位重试: $mirror_try_ref"
      mirror_deferred="$mirror_deferred$mirror_try_prefix "
    fi
  done
  mirror_candidates="$mirror_ready$mirror_deferred"
  mirror_candidates="${mirror_candidates% }"
  for mirror_try_prefix in $mirror_candidates; do
    mirror_try_ref="$(dockerhub_mirror_image "$mirror_try_prefix" "$mirror_try_image" 2>/dev/null || true)"
    [ -n "$mirror_try_ref" ] || continue
    log "通过国内镜像下载 $mirror_try_label: $mirror_try_ref"
    if pull_docker_image "$mirror_try_ref"; then
      if [ -z "$mirror_try_digest" ] || local_image_matches_digest "$mirror_try_ref" "$mirror_try_digest"; then
        docker tag "$mirror_try_ref" "$mirror_try_image" || die "$mirror_try_label 镜像名称映射失败: $mirror_try_image"
        log "$mirror_try_label 镜像下载成功: $mirror_try_prefix"
        return 0
      fi
      warn "$mirror_try_label 镜像摘要与官方发布清单不一致，已拒绝来源: $mirror_try_prefix"
      docker image rm "$mirror_try_ref" >/dev/null 2>&1 || true
    else
      warn "$mirror_try_label 镜像下载失败，继续下一个来源: $mirror_try_prefix"
    fi
  done
  return 1
}

ensure_docker_image() {
  image="$1"; label="$2"; digest=''; official_ref=''
  digest="$(docker_image_digest "$image" 2>/dev/null || true)"
  if docker image inspect "$image" >/dev/null 2>&1; then
    if [ -z "$digest" ] || local_image_matches_digest "$image" "$digest"; then
      log "$label 镜像已存在，跳过下载: $image"
      return 0
    fi
    warn "$label 本地镜像摘要不符合当前发布清单，将重新拉取: $image"
  fi
  resolve_docker_mirror_order
  if [ -n "$digest" ]; then
    official_ref="$(image_with_digest "$image" "$digest")"
  else
    official_ref="$image"
    warn "$label 使用了自定义或未固定镜像，无法与官方发布摘要核对: $image"
  fi
  for mirror_prefix in $DOCKER_MIRROR_ORDER; do
    mirror_image="$(dockerhub_mirror_image "$mirror_prefix" "$image" 2>/dev/null || true)"
    if [ -n "$mirror_image" ] && docker image inspect "$mirror_image" >/dev/null 2>&1 \
        && { [ -z "$digest" ] || local_image_matches_digest "$mirror_image" "$digest"; }; then
      docker tag "$mirror_image" "$image"
      log "$label 国内镜像缓存有效，已映射为标准名称: $image"
      return 0
    fi
  done
  if [ "$DEPENDENCY_INSTALL_MODE" = manual ]; then
    die "缺少 $label 镜像 $image；请从已配置镜像或官方地址手动拉取后重试，或把 DEPENDENCY_INSTALL_MODE 改为 auto"
  fi
  if [ "$RESOLVED_DEPENDENCY_REGION" = cn ]; then
    if try_docker_mirrors "$image" "$label" "$digest"; then return 0; fi
    warn "全部国内镜像均失败，自动回退官方地址: $official_ref"
  fi
  log "通过官方地址下载 $label: $official_ref"
  if pull_docker_image "$official_ref"; then
    [ "$official_ref" = "$image" ] || docker tag "$official_ref" "$image"
    return 0
  fi
  if [ "$RESOLVED_DEPENDENCY_REGION" != cn ]; then
    warn '官方地址下载失败，自动尝试测速后的国内镜像列表'
    if try_docker_mirrors "$image" "$label" "$digest"; then return 0; fi
  fi
  die "$label 镜像下载失败；官方地址和全部国内镜像均不可用: $image"
}

mkdir -p "$DATA_ROOT" || die "无法创建数据目录: $DATA_ROOT"
if [ "$USE_DOCKER" = yes ]; then
  DOCKER_ROOT_DIR="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null | sed -n '1p')"
  [ -n "$DOCKER_ROOT_DIR" ] || die '无法读取DockerRootDir，拒绝遗漏Docker数据盘检查'
fi
validate_resource_settings
resource_preflight
ensure_managed_swap
configure_resource_profiles

if [ "$USE_DOCKER" = yes ]; then
  detect_dependency_region
  ensure_docker_image "$GIT_IMAGE" 'Git源码拉取'
  probe_docker_resource_features
fi

# 删除范围必须严格位于 source-build 或升级器 work 目录下，避免配置错误导致误删。
safe_reset_work_dir() {
  case "$WORK_DIR" in
    "$DATA_ROOT"/source-build/*|/var/lib/aid-updater/work/*) ;;
    *) die "构建目录不在允许范围内: $WORK_DIR" ;;
  esac
  rm -rf -- "$WORK_DIR"
  mkdir -p "$WORK_DIR/repos" "$WORK_DIR/staging" "$CACHE_DIR"
}

git_probe() {
  repo_url="$1"
  if [ "$USE_DOCKER" = yes ]; then
    if command -v timeout >/dev/null 2>&1; then
      timeout 30 docker run --rm "$GIT_IMAGE" -c http.lowSpeedLimit=1 -c http.lowSpeedTime=12 \
        ls-remote --exit-code "$repo_url" "refs/tags/$TAG" 2>/dev/null
    else
      docker run --rm "$GIT_IMAGE" -c http.lowSpeedLimit=1 -c http.lowSpeedTime=12 \
        ls-remote --exit-code "$repo_url" "refs/tags/$TAG" 2>/dev/null
    fi
    return $?
  fi
  if command -v git >/dev/null 2>&1; then
    if command -v timeout >/dev/null 2>&1; then
      GIT_TERMINAL_PROMPT=0 GIT_HTTP_LOW_SPEED_LIMIT=1 GIT_HTTP_LOW_SPEED_TIME=12 \
        timeout 20 git ls-remote --exit-code "$repo_url" "refs/tags/$TAG" 2>/dev/null
    else
      GIT_TERMINAL_PROMPT=0 GIT_HTTP_LOW_SPEED_LIMIT=1 GIT_HTTP_LOW_SPEED_TIME=12 \
        git ls-remote --exit-code "$repo_url" "refs/tags/$TAG" 2>/dev/null
    fi
    return $?
  fi
  return 1
}

probe_forge() {
  base="$1"
  for repo in "$SERVER_REPO" "$ADMIN_REPO" "$WEB_REPO"; do
    git_probe "$base/$repo.git" >/dev/null || return 1
  done
  return 0
}

select_forge() {
  case "$FORGE" in
    github)
      probe_forge "$GITHUB_BASE" || die "GitHub 三端源码标签 $TAG 不完整或网络不可达"
      SOURCE_BASE="$GITHUB_BASE"; SOURCE_FORGE=github ;;
    gitee)
      probe_forge "$GITEE_BASE" || die "Gitee 三端源码标签 $TAG 不完整或网络不可达"
      SOURCE_BASE="$GITEE_BASE"; SOURCE_FORGE=gitee ;;
    auto)
      log "检测 Gitee 三端源码标签 $TAG"
      if probe_forge "$GITEE_BASE"; then
        SOURCE_BASE="$GITEE_BASE"; SOURCE_FORGE=gitee
        log 'Gitee 可用，使用 Gitee 主源'
      else
        warn 'Gitee 不可用或标签不完整，整组切换到 GitHub 备用源'
        probe_forge "$GITHUB_BASE" || die "Gitee 与 GitHub 均无法提供完整的三端标签 $TAG"
        SOURCE_BASE="$GITHUB_BASE"; SOURCE_FORGE=github
        log 'GitHub 可用，使用 GitHub 备用源'
      fi ;;
  esac
}

clone_repo() {
  repo="$1"
  dest="$2"
  url="$SOURCE_BASE/$repo.git"
  if [ "$USE_DOCKER" = yes ]; then
    parent="$(dirname "$dest")"; name="$(basename "$dest")"
    if command -v timeout >/dev/null 2>&1; then
      timeout 300 docker run --rm --user "$(id -u):$(id -g)" -v "$parent:/work" -w /work "$GIT_IMAGE" \
        clone --depth 1 --single-branch --branch "$TAG" "$url" "$name"
    else
      docker run --rm --user "$(id -u):$(id -g)" -v "$parent:/work" -w /work "$GIT_IMAGE" \
        clone --depth 1 --single-branch --branch "$TAG" "$url" "$name"
    fi
  elif command -v git >/dev/null 2>&1; then
    if command -v timeout >/dev/null 2>&1; then
      GIT_TERMINAL_PROMPT=0 timeout 300 git clone --depth 1 --single-branch --branch "$TAG" "$url" "$dest"
    else
      GIT_TERMINAL_PROMPT=0 git clone --depth 1 --single-branch --branch "$TAG" "$url" "$dest"
    fi
  else
    die '宿主机 Git 在源码构建过程中不可用；非 Docker 构建拒绝使用 Docker 回退'
  fi
}

clone_release_set() {
  rm -rf -- "$WORK_DIR/repos/server" "$WORK_DIR/repos/admin" "$WORK_DIR/repos/web"
  clone_repo "$SERVER_REPO" "$WORK_DIR/repos/server" || return 1
  clone_repo "$ADMIN_REPO" "$WORK_DIR/repos/admin" || return 1
  clone_repo "$WEB_REPO" "$WORK_DIR/repos/web" || return 1
  return 0
}

repo_commit() {
  repo_dir="$1"
  if [ "$USE_DOCKER" = yes ]; then
    docker run --rm --user "$(id -u):$(id -g)" -v "$repo_dir:/repo" -w /repo "$GIT_IMAGE" rev-parse HEAD
  elif command -v git >/dev/null 2>&1; then
    # CentOS 7 自带 Git 1.8.3.1 不支持 -C；进入子目录执行可兼容全部受支持版本。
    (cd "$repo_dir" && git rev-parse HEAD)
  else
    die '宿主机 Git 在源码构建过程中不可用；非 Docker 构建拒绝使用 Docker 回退'
  fi
}

prepare_dependency_mirrors() {
  detect_dependency_region
  if [ "$RESOLVED_DEPENDENCY_REGION" = cn ]; then
    NPM_REGISTRY="${AID_NPM_REGISTRY:-https://registry.npmmirror.com}"
    NPM_REGISTRY_FALLBACK="https://registry.npmjs.org"
    GO_PROXY="${AID_GO_PROXY:-https://goproxy.cn|https://proxy.golang.org|direct}"
    if [ "$USE_DOCKER" = yes ]; then
      warn '已自动选择国内依赖线路；容器、JDK、Maven、npm、Go 均优先使用国内镜像并保留官方回退'
    else
      warn '已自动选择国内依赖线路；JDK、Maven、npm、Go 均优先使用国内镜像并保留官方回退'
    fi
  else
    NPM_REGISTRY="${AID_NPM_REGISTRY:-https://registry.npmjs.org}"
    NPM_REGISTRY_FALLBACK="https://registry.npmmirror.com"
    GO_PROXY="${AID_GO_PROXY:-https://proxy.golang.org|https://goproxy.cn|direct}"
    if [ "$USE_DOCKER" = yes ]; then
      log '已自动选择国际依赖线路；容器、JDK、npm、Go 均保留国内备用线路'
    else
      log '已自动选择国际依赖线路；JDK、npm、Go 均保留国内备用线路'
    fi
  fi
  # 线上源码构建固定优先使用国内 Maven 镜像；国内镜像不可用时，重新执行
  # Maven 构建并回退到原始 Maven Central，避免单一镜像故障阻断部署。
  MAVEN_MIRROR_URL="${AID_MAVEN_MIRROR_URL:-https://maven.aliyun.com/repository/public}"
  MAVEN_MIRROR_FALLBACK_URL="${AID_MAVEN_FALLBACK_URL:-https://repo.maven.apache.org/maven2}"
  case "$MAVEN_MIRROR_URL" in https://*) ;; *) die "Maven 首选仓库必须使用 HTTPS: $MAVEN_MIRROR_URL" ;; esac
  case "$MAVEN_MIRROR_FALLBACK_URL" in https://*) ;; *) die "Maven 备用仓库必须使用 HTTPS: $MAVEN_MIRROR_FALLBACK_URL" ;; esac
  log "Maven 仓库：国内主源 $MAVEN_MIRROR_URL；官方备用 $MAVEN_MIRROR_FALLBACK_URL"
  write_maven_settings "$WORK_DIR/maven-settings.xml" "$MAVEN_MIRROR_URL" aid-build-primary
  write_maven_settings "$WORK_DIR/maven-settings-fallback.xml" "$MAVEN_MIRROR_FALLBACK_URL" aid-build-fallback
}

write_maven_settings() {
  settings_file="$1"; mirror_url="$2"; mirror_id="$3"
  cat > "$settings_file" <<EOF
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>$mirror_id</id>
      <mirrorOf>*</mirrorOf>
      <url>$mirror_url</url>
    </mirror>
  </mirrors>
</settings>
EOF
}

download_file() {
  url="$1"; target="$2"; part="$2.part"; expected_checksum="${3:-}"
  case "$url" in https://*) ;; *) warn "拒绝非 HTTPS 下载地址: $url"; return 1 ;; esac
  if [ -n "$expected_checksum" ] && [ -s "$target" ] \
      && [ "$(sha256sum "$target" 2>/dev/null | awk '{print $1}')" = "$expected_checksum" ]; then
    log "完整缓存校验通过，跳过下载: $target"
    return 0
  fi
  if [ -n "$expected_checksum" ] && [ -s "$part" ] \
      && [ "$(sha256sum "$part" 2>/dev/null | awk '{print $1}')" = "$expected_checksum" ]; then
    mv -f "$part" "$target"
    log "未完成缓存实际已完整，经 SHA256 校验后直接复用: $target"
    return 0
  fi
  [ ! -s "$part" ] || warn "发现未完成缓存，将从断点继续: $part"
  if command -v curl >/dev/null 2>&1; then
    if [ -s "$part" ]; then
      download_rc=0
      curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 15 \
        --max-time 1800 --speed-limit 32768 --speed-time 30 --proto '=https' --tlsv1.2 \
        --progress-bar --continue-at - --output "$part" "$url" || download_rc=$?
      if [ "$download_rc" -eq 33 ] || [ "$download_rc" -eq 36 ]; then
        warn '当前地址不支持断点续传，将从该地址重新下载'
        rm -f "$part"; download_rc=0
        curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 15 \
          --max-time 1800 --speed-limit 32768 --speed-time 30 --proto '=https' --tlsv1.2 \
          --progress-bar --output "$part" "$url" || download_rc=$?
      fi
      if [ "$download_rc" -ne 0 ]; then
        warn "下载中断，断点文件已保留: $part"
        return 1
      fi
    else
      curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 15 \
        --max-time 1800 --speed-limit 32768 --speed-time 30 --proto '=https' --tlsv1.2 \
        --progress-bar --output "$part" "$url" || {
          [ ! -s "$part" ] || warn "下载中断，断点文件已保留: $part"; return 1;
        }
    fi
  elif command -v wget >/dev/null 2>&1; then
    wget --continue --https-only --timeout=30 --tries=3 --output-document="$part" "$url" || {
      [ ! -s "$part" ] || warn "下载中断，断点文件已保留: $part"; return 1;
    }
  else
    die '下载 OpenJDK 需要 curl 或 wget'
  fi
  [ -s "$part" ] || { rm -f "$part"; return 1; }
  if [ -n "$expected_checksum" ] \
      && [ "$(sha256sum "$part" 2>/dev/null | awk '{print $1}')" != "$expected_checksum" ]; then
    warn "下载完成但 SHA256 不匹配，已删除不可信文件"
    rm -f "$part"
    return 1
  fi
  mv -f "$part" "$target"
}

jdk_home_metadata_matches() { # jdk_home_metadata_matches <JDK目录> <x64|aarch64>
  jdk_meta_home="$1"
  jdk_meta_arch="$2"
  jdk_meta_release="$jdk_meta_home/release"
  case "$jdk_meta_arch" in
    x64) jdk_meta_os_arch=x86_64 ;;
    aarch64) jdk_meta_os_arch=aarch64 ;;
    *) return 1 ;;
  esac
  [ -d "$jdk_meta_home" ] && [ ! -L "$jdk_meta_home" ] \
    && [ -x "$jdk_meta_home/bin/java" ] && [ -f "$jdk_meta_release" ] \
    && grep -Fxq "JAVA_VERSION=\"$JDK_VERSION\"" "$jdk_meta_release" \
    && grep -Fxq 'IMPLEMENTOR="Eclipse Adoptium"' "$jdk_meta_release" \
    && grep -Fxq "IMPLEMENTOR_VERSION=\"Temurin-$JDK_VERSION+$JDK_BUILD\"" "$jdk_meta_release" \
    && grep -Fxq "OS_ARCH=\"$jdk_meta_os_arch\"" "$jdk_meta_release" \
    && grep -Fxq 'OS_NAME="Linux"' "$jdk_meta_release"
}

jdk_runtime_matches() { # jdk_runtime_matches <JDK目录>
  jdk_runtime_home="$1"
  [ -x "$jdk_runtime_home/bin/java" ] || return 1
  jdk_runtime_output="$($jdk_runtime_home/bin/java -version 2>&1)" || return 1
  jdk_runtime_output="$(printf '%s' "$jdk_runtime_output" | tr -d '\r')"
  jdk_runtime_first="${jdk_runtime_output%%
*}"
  case "$jdk_runtime_first" in
    "openjdk version \"$JDK_VERSION\""*) return 0 ;;
    *) return 1 ;;
  esac
}

prepare_exact_jdk() {
  case "$(uname -m)" in
    x86_64|amd64)
      jdk_arch=x64
      jdk_checksum=be7668bc030d578b83d6d5ef9221d6d6729bbbca8cf94a7d52e16ac68b5a5a35 ;;
    aarch64|arm64)
      jdk_arch=aarch64
      jdk_checksum=d143936f473a4cb24e3b0e247d6d0775769d55ec9775c339540e753059a8d77a ;;
    *) die "OpenJDK $JDK_VERSION 暂不支持当前架构: $(uname -m)" ;;
  esac
  jdk_name="OpenJDK17U-jdk_${jdk_arch}_linux_hotspot_${JDK_VERSION}_${JDK_BUILD}.tar.gz"
  jdk_cache_dir="$CACHE_DIR/toolchains"
  jdk_archive="$jdk_cache_dir/$jdk_name"
  JDK_HOME="$jdk_cache_dir/temurin-${JDK_VERSION}-${jdk_arch}"
  if jdk_home_metadata_matches "$JDK_HOME" "$jdk_arch" \
      && { [ "$USE_DOCKER" = yes ] || jdk_runtime_matches "$JDK_HOME"; }; then
    log "Temurin OpenJDK $JDK_VERSION 已存在，跳过下载: $JDK_HOME"
    return 0
  fi
  mkdir -p "$jdk_cache_dir"
  if [ -f "$jdk_archive" ]; then
    actual_checksum="$(sha256sum "$jdk_archive" | awk '{print $1}')"
    if [ "$actual_checksum" != "$jdk_checksum" ]; then
      warn "OpenJDK 缓存校验失败，将重新下载: $jdk_archive"
      rm -f "$jdk_archive"
    fi
  fi
  if [ ! -f "$jdk_archive" ]; then
    official_url="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-${JDK_VERSION}%2B${JDK_BUILD}/$jdk_name"
    cn_url="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/${jdk_arch}/linux/$jdk_name"
    if [ -n "${AID_JDK_DOWNLOAD_URL:-}" ]; then
      jdk_urls="${AID_JDK_DOWNLOAD_URL}
$cn_url
$official_url"
    elif [ "$RESOLVED_DEPENDENCY_REGION" = cn ]; then
      jdk_urls="$cn_url
$official_url"
    else
      jdk_urls="$official_url
$cn_url"
    fi
    downloaded=no
    old_ifs="$IFS"; IFS='
'
    for jdk_url in $jdk_urls; do
      [ -n "$jdk_url" ] || continue
      log "下载 Temurin OpenJDK $JDK_VERSION（$jdk_arch）: $jdk_url"
      if download_file "$jdk_url" "$jdk_archive" "$jdk_checksum"; then
        actual_checksum="$(sha256sum "$jdk_archive" | awk '{print $1}')"
        if [ "$actual_checksum" = "$jdk_checksum" ]; then
          downloaded=yes
          break
        fi
        warn "OpenJDK 下载文件 SHA256 不匹配，拒绝使用并尝试备用地址"
        rm -f "$jdk_archive"
      else
        warn 'OpenJDK 当前下载地址不可用，尝试备用地址'
      fi
    done
    IFS="$old_ifs"
    [ "$downloaded" = yes ] || die "Temurin OpenJDK $JDK_VERSION 下载失败；国内镜像和官方地址均不可用"
  fi
  jdk_tmp="$JDK_HOME.tmp.$$"
  rm -rf -- "$jdk_tmp"
  mkdir -p "$jdk_tmp"
  if ! tar -xzf "$jdk_archive" -C "$jdk_tmp" --strip-components=1; then
    rm -rf -- "$jdk_tmp"
    die 'OpenJDK 压缩包解压失败'
  fi
  if ! jdk_home_metadata_matches "$jdk_tmp" "$jdk_arch" \
      || { [ "$USE_DOCKER" != yes ] && ! jdk_runtime_matches "$jdk_tmp"; }; then
    rm -rf -- "$jdk_tmp"
    die "OpenJDK $JDK_VERSION 固定归档元数据、架构或运行能力不匹配"
  fi
  rm -rf -- "$JDK_HOME"
  mv "$jdk_tmp" "$JDK_HOME"
  log "Temurin OpenJDK $JDK_VERSION 已校验并就绪: $JDK_HOME"
}

prepare_jdk_runtime_image() {
  [ "$USE_DOCKER" = yes ] || return 0
  runtime_manager="$SERVER_DIR/deploy/aid.sh"
  if [ ! -f "$runtime_manager" ] && [ -n "${AID_MANAGER_SCRIPT:-}" ]; then
    runtime_manager="$AID_MANAGER_SCRIPT"
  fi
  [ -f "$runtime_manager" ] || die '缺少 AID FFmpeg 与中文字体统一运行时管理脚本'
  command -v bash >/dev/null 2>&1 || die '构建固定 Java/FFmpeg/中文字体运行镜像需要 Bash'
  log "通过 AID 统一校验链准备 OpenJDK $JDK_VERSION + FFmpeg $FFMPEG_RUNTIME_VERSION + 中文字体 $AID_CJK_FONT_VERSION 运行镜像"
  AID_SH_LIBRARY_MODE=1 \
    AID_DATA_ROOT="$DATA_ROOT" \
    AID_DEPENDENCY_INSTALL_MODE="${AID_DEPENDENCY_INSTALL_MODE:-auto}" \
    AID_MANAGER_SCRIPT="$runtime_manager" \
    bash -c 'source "$1"; prepare_jdk_runtime_image' aid-runtime "$runtime_manager" \
    || die 'OpenJDK、AID FFmpeg与中文字体固定运行镜像准备失败'
  docker image inspect "$JAVA_RUNTIME_IMAGE" >/dev/null 2>&1 \
    || die "统一运行时脚本未生成预期镜像: $JAVA_RUNTIME_IMAGE"
}

prepare_runtime_images() {
  [ "$USE_DOCKER" = yes ] || return 0
  prepare_jdk_runtime_image
  ensure_docker_image 'nginx:1.25-alpine' 'Web静态站点与Nginx网关'
  ensure_docker_image 'docker:27-cli' '升级器Docker客户端'
}

prepare_build_images() {
  [ "$USE_DOCKER" = yes ] || return 0
  ensure_docker_image "$MAVEN_IMAGE" 'Maven构建基础'
  ensure_docker_image "$NODE_IMAGE" 'Node.js 22.22.0构建'
  ensure_docker_image "$GO_IMAGE" 'Go构建'
}

docker_maven_build() {
  settings_file="$1"
  run_docker_stage maven '服务端Maven' --user "$uid_gid" \
    -e "MAVEN_OPTS=$MAVEN_OPTS_VALUE" \
    -v "$SERVER_DIR:/workspace" -v "$CACHE_DIR/m2:/cache/m2" \
    -v "$settings_file:/tmp/settings.xml:ro" \
    -v "$JDK_HOME:/opt/aid-jdk:ro" -w /workspace "$MAVEN_IMAGE" sh -lc \
    'export JAVA_HOME=/opt/aid-jdk; export PATH="$JAVA_HOME/bin:$PATH"; \
     java -version 2>&1 | head -n 1 | grep -F "17.0.20" >/dev/null \
       || { echo "[失败] Maven未使用OpenJDK 17.0.20" >&2; exit 1; }; \
     exec mvn --batch-mode --no-transfer-progress -s /tmp/settings.xml -Dmaven.repo.local=/cache/m2 clean package -DskipTests'
}

read_project_npm_version() {
  source_dir="$1"; label="$2"
  package_file="$source_dir/package.json"
  [ -f "$package_file" ] || die "$label 缺少 package.json"
  npm_version="$(sed -n 's/^[[:space:]]*"packageManager"[[:space:]]*:[[:space:]]*"npm@\([^"]*\)".*/\1/p' \
    "$package_file" | head -n 1)"
  if ! printf '%s\n' "$npm_version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    die "$label 必须在 package.json 的 packageManager 中固定完整 npm 版本（例如 npm@10.9.4）"
  fi
  printf '%s\n' "$npm_version"
}

docker_npm_build() {
  source_dir="$1"; cache_dir="$2"; label="$3"; npm_version="$4"; npm_script="${5:-build}"; selected_registry="$NPM_REGISTRY"
  log "[构建][$label][依赖] npm@$npm_version ci，首选源: $NPM_REGISTRY"
  if ! run_docker_stage node "$label npm ci" --user "$uid_gid" -e NUXT_TELEMETRY_DISABLED=1 \
      -e "NODE_OPTIONS=$NODE_OPTIONS_VALUE" \
      -e "AID_NPM_VERSION=$npm_version" \
      -e "npm_config_registry=$NPM_REGISTRY" -e npm_config_cache=/cache/npm \
      -v "$source_dir:/workspace" -v "$cache_dir:/cache/npm" \
      -w /workspace "$NODE_IMAGE" sh -lc \
      '[ "$(node -v)" = v22.22.0 ] || { echo "[失败] Node.js实际版本不是22.22.0" >&2; exit 1; }; \
       exec npm exec --yes "--package=npm@$AID_NPM_VERSION" -- npm ci'; then
    warn "$label npm 依赖从首选源安装失败，切换备用源: $NPM_REGISTRY_FALLBACK"
    selected_registry="$NPM_REGISTRY_FALLBACK"
    run_docker_stage node "$label npm ci备用源" --user "$uid_gid" -e NUXT_TELEMETRY_DISABLED=1 \
      -e "NODE_OPTIONS=$NODE_OPTIONS_VALUE" \
      -e "AID_NPM_VERSION=$npm_version" \
      -e "npm_config_registry=$selected_registry" -e npm_config_cache=/cache/npm \
      -v "$source_dir:/workspace" -v "$cache_dir:/cache/npm" \
      -w /workspace "$NODE_IMAGE" sh -lc \
      '[ "$(node -v)" = v22.22.0 ] || exit 1; \
       npm exec --yes "--package=npm@$AID_NPM_VERSION" -- npm ci' \
      || die "$label npm@$npm_version ci 失败；如日志出现 EUSAGE/Missing，请同步提交 package.json 与 package-lock.json"
  fi
  log "[构建][$label][编译] npm@$npm_version run $npm_script，使用源: $selected_registry"
  run_docker_stage node "$label npm $npm_script" --user "$uid_gid" -e NUXT_TELEMETRY_DISABLED=1 \
    -e "NODE_OPTIONS=$NODE_OPTIONS_VALUE" \
    -e "AID_NPM_VERSION=$npm_version" -e "AID_NPM_SCRIPT=$npm_script" \
    -e "npm_config_registry=$selected_registry" -e npm_config_cache=/cache/npm \
    -v "$source_dir:/workspace" -v "$cache_dir:/cache/npm" \
    -w /workspace "$NODE_IMAGE" sh -lc \
    'exec npm exec --yes "--package=npm@$AID_NPM_VERSION" -- npm run "$AID_NPM_SCRIPT"'
  log "[构建][$label][完成] 生产构建成功"
}

host_npm_build() {
  source_dir="$1"; cache_dir="$2"; label="$3"; npm_version="$4"; npm_script="${5:-build}"; selected_registry="$NPM_REGISTRY"
  log "[构建][$label][依赖] npm@$npm_version ci，首选源: $NPM_REGISTRY"
  if ! run_host_stage node "$label npm ci" "$source_dir" env NUXT_TELEMETRY_DISABLED=1 \
      "NODE_OPTIONS=$NODE_OPTIONS_VALUE" "npm_config_registry=$NPM_REGISTRY" "npm_config_cache=$cache_dir" \
      npm exec --yes "--package=npm@$npm_version" -- npm ci; then
    warn "$label npm 依赖从首选源安装失败，切换备用源: $NPM_REGISTRY_FALLBACK"
    selected_registry="$NPM_REGISTRY_FALLBACK"
    run_host_stage node "$label npm ci备用源" "$source_dir" env NUXT_TELEMETRY_DISABLED=1 \
      "NODE_OPTIONS=$NODE_OPTIONS_VALUE" "npm_config_registry=$selected_registry" "npm_config_cache=$cache_dir" \
      npm exec --yes "--package=npm@$npm_version" -- npm ci \
      || die "$label npm@$npm_version ci 失败；如日志出现 EUSAGE/Missing，请同步提交 package.json 与 package-lock.json"
  fi
  log "[构建][$label][编译] npm@$npm_version run $npm_script，使用源: $selected_registry"
  run_host_stage node "$label npm $npm_script" "$source_dir" env NUXT_TELEMETRY_DISABLED=1 \
    "NODE_OPTIONS=$NODE_OPTIONS_VALUE" "npm_config_registry=$selected_registry" "npm_config_cache=$cache_dir" \
    npm exec --yes "--package=npm@$npm_version" -- npm run "$npm_script"
  log "[构建][$label][完成] 生产构建成功"
}

require_local_build_tools() {
  command -v mvn >/dev/null 2>&1 || die '源码构建需要 Maven 3.8+'
  command -v node >/dev/null 2>&1 || die '源码构建需要 Node.js 22+'
  command -v npm >/dev/null 2>&1 || die '源码构建需要 npm'
  command -v go >/dev/null 2>&1 || die '源码构建升级器需要 Go 1.22+'
  node_major="$(node -v 2>/dev/null | sed -E 's/v([0-9]+).*/\1/')"
  case "$node_major" in
    ''|*[!0-9]*) die '无法识别 Node.js 版本，请安装 Node.js 22+' ;;
  esac
  [ "$node_major" -ge 22 ] || die "Node.js版本过低（当前${node_major}，需要22+）"
}

detect_current_updater_arch() {
  machine_arch="$(uname -m 2>/dev/null || true)"
  case "$machine_arch" in
    x86_64|amd64) CURRENT_UPDATER_ARCH=amd64 ;;
    aarch64|arm64) CURRENT_UPDATER_ARCH=arm64 ;;
    *) die "当前CPU架构不受升级器支持: ${machine_arch:-未知}" ;;
  esac
  log "本地源码发布包仅编译当前服务器升级器架构: linux/$CURRENT_UPDATER_ARCH"
}

build_with_docker() {
  uid_gid="$(id -u):$(id -g)"
  log "[构建][服务端][开始] Temurin OpenJDK $JDK_VERSION + Maven，国内主源: $MAVEN_MIRROR_URL"
  if ! docker_maven_build "$WORK_DIR/maven-settings.xml"; then
    warn "Maven 从首选仓库构建失败，切换备用仓库: $MAVEN_MIRROR_FALLBACK_URL"
    docker_maven_build "$WORK_DIR/maven-settings-fallback.xml"
  fi
  log '[构建][服务端][完成] aid-admin.jar 构建成功'

  log "[构建][后台管理端][开始] Node.js 22.22.0 + npm@$ADMIN_NPM_VERSION"
  docker_npm_build "$ADMIN_DIR" "$CACHE_DIR/npm-admin" '后台管理端' "$ADMIN_NPM_VERSION"

  log "[构建][Web用户端][开始] Node.js 22.22.0 + npm@$WEB_NPM_VERSION"
  docker_npm_build "$WEB_DIR" "$CACHE_DIR/npm-web" 'Web用户端' "$WEB_NPM_VERSION" generate

  arch="$CURRENT_UPDATER_ARCH"
  log "编译当前服务器架构升级器 linux/$arch"
  run_docker_stage go "升级器Go linux/$arch" --user "$uid_gid" -e GOOS=linux -e "GOARCH=$arch" -e CGO_ENABLED=0 \
    -e "GOMEMLIMIT=$GO_MEMORY_LIMIT" -e "GOMAXPROCS=$GO_MAX_PROCS" \
    -e "GOPROXY=$GO_PROXY" -e GOCACHE=/cache/build -e GOMODCACHE=/cache/mod \
    -v "$SERVER_DIR:/workspace" -v "$CACHE_DIR/go-build:/cache/build" \
    -v "$CACHE_DIR/go-mod:/cache/mod" -v "$STAGING_DIR/updater:/out" \
    -w /workspace/deploy/updater "$GO_IMAGE" \
    go build -ldflags "-X main.version=$VERSION -X aid-updater/internal/manifest.trustedPublicKey=$MANIFEST_PUBLIC_KEY" \
    -o "/out/aid-updater_linux_$arch" ./cmd/aid-updater
}

build_with_host() {
  require_local_build_tools
  log "[构建][服务端][开始] 隔离 Temurin OpenJDK $JDK_VERSION + Maven，国内主源: $MAVEN_MIRROR_URL"
  if ! run_host_stage maven '服务端Maven' "$SERVER_DIR" env "JAVA_HOME=$JDK_HOME" "PATH=$JDK_HOME/bin:$PATH" \
      "MAVEN_OPTS=$MAVEN_OPTS_VALUE" mvn --batch-mode --no-transfer-progress -s "$WORK_DIR/maven-settings.xml" \
      -Dmaven.repo.local="$CACHE_DIR/m2" clean package -DskipTests; then
    warn "Maven 从首选仓库构建失败，切换备用仓库: $MAVEN_MIRROR_FALLBACK_URL"
    run_host_stage maven '服务端Maven备用源' "$SERVER_DIR" env "JAVA_HOME=$JDK_HOME" "PATH=$JDK_HOME/bin:$PATH" \
      "MAVEN_OPTS=$MAVEN_OPTS_VALUE" mvn --batch-mode --no-transfer-progress -s "$WORK_DIR/maven-settings-fallback.xml" \
      -Dmaven.repo.local="$CACHE_DIR/m2" clean package -DskipTests
  fi
  log '[构建][服务端][完成] aid-admin.jar 构建成功'
  log "[构建][后台管理端][开始] 使用服务器本机 Node.js + npm@$ADMIN_NPM_VERSION"
  host_npm_build "$ADMIN_DIR" "$CACHE_DIR/npm-admin" '后台管理端' "$ADMIN_NPM_VERSION"
  log "[构建][Web用户端][开始] 使用服务器本机 Node.js + npm@$WEB_NPM_VERSION"
  host_npm_build "$WEB_DIR" "$CACHE_DIR/npm-web" 'Web用户端' "$WEB_NPM_VERSION" generate
  arch="$CURRENT_UPDATER_ARCH"
  log "编译当前服务器架构升级器 linux/$arch"
  run_host_stage go "升级器Go linux/$arch" "$SERVER_DIR/deploy/updater" env GOOS=linux "GOARCH=$arch" CGO_ENABLED=0 \
    "GOMEMLIMIT=$GO_MEMORY_LIMIT" "GOMAXPROCS=$GO_MAX_PROCS" "GOPROXY=$GO_PROXY" \
    "GOCACHE=$CACHE_DIR/go-build" "GOMODCACHE=$CACHE_DIR/go-mod" \
    go build -ldflags "-X main.version=$VERSION -X aid-updater/internal/manifest.trustedPublicKey=$MANIFEST_PUBLIC_KEY" \
    -o "$STAGING_DIR/updater/aid-updater_linux_$arch" ./cmd/aid-updater
}

copy_installer_file() {
  relative="$1"
  source_file="$SERVER_DIR/$relative"
  [ -f "$source_file" ] || die "源码缺少安装文件: $relative"
  target_file="$STAGING_DIR/installer/$relative"
  mkdir -p "$(dirname "$target_file")"
  cp "$source_file" "$target_file"
}

assemble_package() {
  backend_jar="$SERVER_DIR/aid-admin/target/aid-admin.jar"
  [ -f "$backend_jar" ] || die '服务端构建产物缺失: aid-admin/target/aid-admin.jar'
  [ -d "$ADMIN_DIR/dist" ] || die '后台管理端构建产物缺失: dist/'
  if [ -f "$WEB_DIR/dist/public/index.html" ] && [ -f "$WEB_DIR/dist/public/200.html" ]; then
    web_output="$WEB_DIR/dist/public"
  elif [ -f "$WEB_DIR/.output/public/index.html" ] && [ -f "$WEB_DIR/.output/public/200.html" ]; then
    web_output="$WEB_DIR/.output/public"
  else
    die 'Web 静态生成产物缺少 index.html 或 200.html（期望 dist/public 或 .output/public）'
  fi

  mkdir -p "$STAGING_DIR/backend" "$STAGING_DIR/installer/sql" "$STAGING_DIR/sql"
  cp "$backend_jar" "$STAGING_DIR/backend/aid-admin.jar"
  cp -R "$ADMIN_DIR/dist" "$STAGING_DIR/admin-dist"
  mkdir -p "$STAGING_DIR/web-dist"
  cp -R "$web_output"/. "$STAGING_DIR/web-dist/"
  [ -f "$STAGING_DIR/web-dist/index.html" ] || die 'Web 静态产物装配失败：web-dist/index.html 不存在'
  [ -f "$STAGING_DIR/web-dist/200.html" ] || die 'Web 静态产物装配失败：web-dist/200.html 不存在'

  for file in \
    deploy/README.md \
    deploy/aid-deploy.conf.example \
    deploy/aid-updater.config.example.json \
    deploy/aid-updater.service \
    deploy/install-updater.sh \
    sql/aid-init.sql; do
    copy_installer_file "$file"
  done
  manager_source="$SERVER_DIR/deploy/aid.sh"
  if [ ! -f "$SERVER_DIR/deploy/build-release-from-source.sh" ] && [ -n "${AID_MANAGER_SCRIPT:-}" ]; then
    manager_source="$AID_MANAGER_SCRIPT"
  fi
  [ -f "$manager_source" ] || die "缺少部署管理脚本: $manager_source"
  mkdir -p "$STAGING_DIR/installer/deploy"
  cp "$manager_source" "$STAGING_DIR/installer/deploy/aid.sh"
  if [ -f "$SERVER_DIR/deploy/build-release-from-source.sh" ]; then
    cp "$SERVER_DIR/deploy/build-release-from-source.sh" "$STAGING_DIR/installer/deploy/build-release-from-source.sh"
  else
    cp "$SCRIPT_PATH" "$STAGING_DIR/installer/deploy/build-release-from-source.sh"
  fi
  [ -d "$SERVER_DIR/deploy/docker" ] || die '源码缺少 deploy/docker'
  cp -R "$SERVER_DIR/deploy/docker" "$STAGING_DIR/installer/deploy/docker"

  has_sql=false
  for sql_file in "$SERVER_DIR"/sql/v*.sql; do
    [ -f "$sql_file" ] || continue
    cp "$sql_file" "$STAGING_DIR/sql/"
    has_sql=true
  done
  [ "$has_sql" = true ] || rmdir "$STAGING_DIR/sql"

  server_commit="$(repo_commit "$SERVER_DIR")"
  admin_commit="$(repo_commit "$ADMIN_DIR")"
  web_commit="$(repo_commit "$WEB_DIR")"
  channel=stable
  case "$VERSION" in *-*) channel=beta ;; esac
  cat > "$STAGING_DIR/build-info.json" <<EOF
{
  "product": "AID",
  "version": "$VERSION",
  "channel": "$channel",
  "builtAt": "$(date '+%Y-%m-%d %H:%M:%S')",
  "builtBy": "remote-source-build",
  "buildHost": "$(hostname 2>/dev/null || printf unknown)",
  "sourceForge": "$SOURCE_FORGE",
  "dependencyRegion": "$RESOLVED_DEPENDENCY_REGION",
  "sourceRef": "$TAG",
  "jdkVersion": "$JDK_VERSION",
  "nodeVersion": "22.22.0",
  "adminNpmVersion": "$ADMIN_NPM_VERSION",
  "webNpmVersion": "$WEB_NPM_VERSION",
  "serverCommit": "$server_commit",
  "adminCommit": "$admin_commit",
  "webCommit": "$web_commit",
  "containsWeb": true,
  "containsSql": $has_sql,
  "containsUpdater": true,
  "updaterArchitectures": ["$CURRENT_UPDATER_ARCH"],
  "containsInstaller": true
}
EOF

  mkdir -p "$(dirname "$OUTPUT")"
  output_part="$OUTPUT.part"
  checksum_part="$output_part.sha256"
  rm -f "$output_part" "$checksum_part"
  # 根目录成员使用规范名称（installer/...），避免不同 tar 实现对 ./installer
  # 与 installer 的精确匹配行为不一致；aid.sh 仍兼容旧包中的 ./ 前缀。
  if [ "$USE_DOCKER" = yes ]; then
    output_dir="$(dirname "$output_part")"
    output_part_name="$(basename "$output_part")"
    checksum_part_name="$(basename "$checksum_part")"
    run_docker_stage package '发布包压缩与SHA256校验' --user "$uid_gid" --entrypoint /bin/sh \
      -e "AID_OUTPUT_PART=$output_part_name" -e "AID_CHECKSUM_PART=$checksum_part_name" \
      -v "$STAGING_DIR:/staging:ro" -v "$output_dir:/out" -w /staging "$GIT_IMAGE" -c \
      'tar -czf "/out/$AID_OUTPUT_PART" * && sha256sum "/out/$AID_OUTPUT_PART" > "/out/$AID_CHECKSUM_PART"'
  else
    run_host_stage package '发布包压缩与SHA256校验' "$STAGING_DIR" sh -c \
      'tar -czf "$1" * && sha256sum "$1" > "$2"' aid-package "$output_part" "$checksum_part"
  fi
  [ -s "$output_part" ] && [ -s "$checksum_part" ] || die '发布包压缩或SHA256校验失败'
  output_sha256="$(awk 'NR == 1 { print $1 }' "$checksum_part")"
  printf '%s\n' "$output_sha256" | grep -Eq '^[0-9a-f]{64}$' || die '发布包SHA256结果非法'
  mv -f "$output_part" "$OUTPUT"
  rm -f "$checksum_part"
  log "源码构建包已生成: $OUTPUT"
  log "SHA256: $output_sha256"
}

safe_reset_work_dir
select_forge
prepare_dependency_mirrors

SERVER_DIR="$WORK_DIR/repos/server"
ADMIN_DIR="$WORK_DIR/repos/admin"
WEB_DIR="$WORK_DIR/repos/web"
STAGING_DIR="$WORK_DIR/staging"

log "按版本标签拉取三端源码: $TAG"
if ! clone_release_set; then
  if [ "$FORGE" = auto ] && [ "$SOURCE_FORGE" = gitee ]; then
    warn 'Gitee 探测成功但拉取中断，清理未完成源码后整组重试 GitHub 备用源'
    probe_forge "$GITHUB_BASE" || die "GitHub 三端源码标签 $TAG 不完整或网络不可达"
    SOURCE_BASE="$GITHUB_BASE"
    SOURCE_FORGE=github
    prepare_dependency_mirrors
    clone_release_set || die "从 GitHub 备用源拉取三端源码失败"
  elif [ "$FORGE" = auto ] && [ "$SOURCE_FORGE" = github ]; then
    warn 'GitHub 备用源探测成功但拉取中断，重新检查 Gitee 主源'
    probe_forge "$GITEE_BASE" || die "Gitee 与 GitHub 均无法稳定提供三端源码标签 $TAG"
    SOURCE_BASE="$GITEE_BASE"
    SOURCE_FORGE=gitee
    prepare_dependency_mirrors
    clone_release_set || die "从 Gitee 主源拉取三端源码失败"
  else
    die "从 $SOURCE_FORGE 拉取三端源码失败"
  fi
fi

ADMIN_NPM_VERSION="$(read_project_npm_version "$ADMIN_DIR" '后台管理端')"
WEB_NPM_VERSION="$(read_project_npm_version "$WEB_DIR" 'Web用户端')"
log "前端锁定工具链: 后台管理端 npm@$ADMIN_NPM_VERSION，Web用户端 npm@$WEB_NPM_VERSION"

mkdir -p "$STAGING_DIR/updater" "$CACHE_DIR/m2" "$CACHE_DIR/npm-admin" \
  "$CACHE_DIR/npm-web" "$CACHE_DIR/go-build" "$CACHE_DIR/go-mod"
detect_dependency_region
prepare_dependency_mirrors
prepare_exact_jdk
detect_current_updater_arch
if [ "$USE_DOCKER" = yes ]; then
  prepare_build_images
  prepare_runtime_images
  build_with_docker
else
  build_with_host
fi
assemble_package
