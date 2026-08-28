import type { StoryboardRecordRow } from '~/types/business-api'

/**
 * list-by-storyboard v2.57.7 仅返回已落库记录（含 fileUrl），不含 status。
 * 无 fileUrl 且无 status 时视为非进行中占位。
 */
export function isPendingStoryboardRecord(r: StoryboardRecordRow): boolean {
  const url = String(r.fileUrl ?? '').trim()
  if (url) return false
  const st = String(r.status ?? '').trim().toUpperCase()
  if (!st) return false
  if (st === 'SUCCEEDED' || st === '1' || st === 'FAILED' || st === '2' || st === 'CANCELLED') {
    return false
  }
  return (
    st === 'PENDING' ||
    st === 'PROCESSING' ||
    st === 'QUEUED' ||
    st === 'RUNNING' ||
    st === 'WAITING' ||
    st === '0'
  )
}

export function findPendingStoryboardRecordTaskId(rows: StoryboardRecordRow[]): number | null {
  for (const r of rows) {
    if (!isPendingStoryboardRecord(r)) continue
    const tid = Number(r.taskId)
    if (Number.isFinite(tid) && tid > 0) return tid
  }
  return null
}

export function findPendingStoryboardRecordId(rows: StoryboardRecordRow[]): number | null {
  for (const r of rows) {
    if (!isPendingStoryboardRecord(r)) continue
    const rid = Number(r.id)
    if (Number.isFinite(rid) && rid > 0) return rid
  }
  return null
}
