/**
 * 同一 taskId 短窗内 SSE 建连次数熔断，防止任意调用方 tight-loop 打爆 `/task/stream/:id`。
 * 正常路径（单任务单跟随 + 偶发重连）远低于阈值；风暴时拒绝新建连。
 */

const DEFAULT_WINDOW_MS = 3000
const DEFAULT_MAX_CONNECTS = 6
const PRUNE_THRESHOLD = 256

const recentConnectAtByTaskId = new Map<number, number[]>()

function pruneExpiredTaskEntries(now: number, windowMs: number) {
  if (recentConnectAtByTaskId.size < PRUNE_THRESHOLD) return
  for (const [id, timestamps] of recentConnectAtByTaskId) {
    const recent = timestamps.filter((timestamp) => now - timestamp < windowMs)
    if (recent.length) recentConnectAtByTaskId.set(id, recent)
    else recentConnectAtByTaskId.delete(id)
  }
}

export function claimTaskStreamConnectSlot(
  taskId: number,
  options?: { now?: number; windowMs?: number; maxConnects?: number }
): boolean {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return false

  const now = options?.now ?? Date.now()
  const windowMs = options?.windowMs ?? DEFAULT_WINDOW_MS
  const maxConnects = options?.maxConnects ?? DEFAULT_MAX_CONNECTS
  pruneExpiredTaskEntries(now, windowMs)

  const prev = recentConnectAtByTaskId.get(id) || []
  const recent = prev.filter((t) => now - t < windowMs)
  if (recent.length >= maxConnects) {
    recentConnectAtByTaskId.set(id, recent)
    return false
  }
  recent.push(now)
  recentConnectAtByTaskId.set(id, recent)
  return true
}

/** 仅测试用：清空建连记录 */
export function resetTaskStreamConnectGuardForTests() {
  recentConnectAtByTaskId.clear()
}
