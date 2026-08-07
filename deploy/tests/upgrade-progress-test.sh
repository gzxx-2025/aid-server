#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

export AID_SH_LIBRARY_MODE=1
export AID_DATA_ROOT="${TMP_ROOT}/data"
export AID_UPDATER_DATA_DIR="${TMP_ROOT}/updater"
# shellcheck source=../aid.sh
source "${ROOT_DIR}/deploy/aid.sh"
require_root() { :; }

mkdir -p "${UPDATER_DATA_DIR}"
cat > "${UPDATER_DATA_DIR}/health.json" <<'EOF'
{
  "status": "RUNNING",
  "lastTask": {
    "taskId": "task-progress-1",
    "action": "UPGRADE",
    "state": "RUNNING",
    "message": "正在构建三端源码",
    "progress": 38,
    "phase": "构建源码",
    "startedAt": "2026-08-07 23:00:00",
    "updatedAt": "2026-08-07 23:01:00"
  }
}
EOF
cat > "${UPDATER_DATA_DIR}/updater.log" <<'EOF'
2026/08/07 23:00:00 开始执行升级任务
2026/08/07 23:01:00 [进度 38%] 构建源码
EOF

updater_version_task_active \
  || { echo 'FAIL: running upgrade must be detected' >&2; exit 1; }
[[ "$(updater_task_number_field progress)" == '38' ]] \
  || { echo 'FAIL: progress field parsing failed' >&2; exit 1; }
[[ "$(updater_task_string_field phase)" == '构建源码' ]] \
  || { echo 'FAIL: phase field parsing failed' >&2; exit 1; }
if ensure_no_active_version_task >/dev/null 2>&1; then
  echo 'FAIL: duplicate version task was not blocked' >&2
  exit 1
fi
show_menu | grep -q '15) 查看升级/回退实时进度' \
  || { echo 'FAIL: active progress menu item is missing' >&2; exit 1; }

(
  sleep 0.2
  cat > "${UPDATER_DATA_DIR}/health.json" <<'EOF'
{
  "status": "RUNNING",
  "lastTask": {
    "taskId": "task-progress-1",
    "action": "UPGRADE",
    "state": "SUCCESS",
    "message": "升级完成",
    "progress": 100,
    "phase": "执行完成",
    "startedAt": "2026-08-07 23:00:00",
    "updatedAt": "2026-08-07 23:02:00",
    "finishedAt": "2026-08-07 23:02:00"
  }
}
EOF
) &
progressOutput="$(do_upgrade_progress)"
wait
grep -q '38%' <<< "${progressOutput}" \
  || { echo 'FAIL: live progress output is missing' >&2; exit 1; }
grep -q '主程序升级已完成' <<< "${progressOutput}" \
  || { echo 'FAIL: progress command did not report completion' >&2; exit 1; }

if updater_version_task_active; then
  echo 'FAIL: completed upgrade must not remain active' >&2
  exit 1
fi
if show_menu | grep -q '15) 查看升级/回退实时进度'; then
  echo 'FAIL: progress menu item must be hidden without an active task' >&2
  exit 1
fi

echo 'upgrade progress tests passed'
