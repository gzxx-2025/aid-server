import type { UserTaskRow } from '~/types/business-api'
import { userTaskDetailCached } from '~/utils/businessApi'
/** SSE 已终态的 taskId：短时内 list 若仍标进行中，不再逐条打 detail */
const locallyTerminalTaskIds = new Map<number, number>()
const LOCALLY_TERMINAL_TTL_MS = 20_000

export function markUserTaskLocallyTerminal(taskId: number): void {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return
  locallyTerminalTaskIds.set(id, Date.now() + LOCALLY_TERMINAL_TTL_MS)
}

export function isUserTaskLocallyTerminal(taskId: number): boolean {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return false
  const exp = locallyTerminalTaskIds.get(id)
  if (exp == null) return false
  if (Date.now() > exp) {
    locallyTerminalTaskIds.delete(id)
    return false
  }
  return true
}

export function isTaskOngoingStatus(status: unknown): boolean {
  const st = String(status ?? '').trim().toUpperCase()
  return (
    st === 'PENDING' ||
    st === 'PROCESSING' ||
    st === 'QUEUED' ||
    st === 'RUNNING' ||
    st === 'WAITING' ||
    st === '0'
  )
}

/** 通用任务（aid_extract_task）是否仍在进行中 */
export async function isUserOrMediaTaskOngoing(taskId: number): Promise<boolean> {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return false
  if (isUserTaskLocallyTerminal(id)) return false
  try {
    const detail = await userTaskDetailCached(id)
    return detail ? isTaskOngoingStatus(detail.status) : false
  } catch {
    return false
  }
}

/**
 * 列表接口可能短于 detail/SSE 终态（含前端 list 缓存）。
 * 对仍标记为进行中的行补查 task/detail，避免任务已完成仍留在「进行中」。
 * SSE 刚终态的 taskId 见 {@link markUserTaskLocallyTerminal}，不再打 detail。
 */
export async function refreshOngoingUserTaskRowsFromDetail(
  rows: UserTaskRow[],
  options?: { reconcileWithDetail?: boolean }
): Promise<UserTaskRow[]> {
  if (!rows.length) return []
  const reconcile = options?.reconcileWithDetail !== false

  return Promise.all(
    rows.map(async (row) => {
      const id = Number(row.id)
      if (Number.isFinite(id) && id > 0 && isUserTaskLocallyTerminal(id)) {
        if (isTaskOngoingStatus(row.status)) {
          return { ...row, status: 'SUCCEEDED' }
        }
        return row
      }
      if (!reconcile || !isTaskOngoingStatus(row.status)) return row
      if (!Number.isFinite(id) || id <= 0) return row
      try {
        const detail = await userTaskDetailCached(id)
        if (!detail) return row
        const detailStatus = detail.status
        if (detailStatus != null && String(detailStatus).trim() !== '') {
          return { ...row, status: detailStatus }
        }
      } catch {
        /* 保留 list 行 */
      }
      return row
    })
  )
}
