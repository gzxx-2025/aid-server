import { parseTaskResultPayload } from '~/utils/taskChainOutcome'

/**
 * 合并接口（image-with-prompt / video-with-prompt）提示词终态事件中的出图/出片子任务 ID。
 * 见接口文档：complete / partial_failed payload → chainChildTaskIds（优先）/ chainChildTaskId（兼容）。
 */

function pushPositiveId(ids: number[], raw: unknown) {
  const n = Number(raw)
  if (Number.isFinite(n) && n > 0) ids.push(n)
}

/** 从 SSE complete / partial_failed payload 或 task.resultData JSON 解析子任务 ID 列表 */
export function extractChainChildTaskIds(payload: unknown): number[] {
  const o = parseTaskResultPayload(payload)
  if (!o) return []
  const ids: number[] = []

  if (Array.isArray(o.chainChildTaskIds)) {
    for (const v of o.chainChildTaskIds) pushPositiveId(ids, v)
  }
  if (!ids.length && o.chainChildTaskId != null) {
    pushPositiveId(ids, o.chainChildTaskId)
  }
  return [...new Set(ids)]
}

/** 从 task/detail 的 resultData 解析 chainChildTaskIds（断线恢复 / 跳过 SSE 短路径） */
export function extractChainChildTaskIdsFromTaskDetail(
  detail: {
    resultData?: string | null
  } | null
): number[] {
  if (!detail) return []
  return extractChainChildTaskIds(detail.resultData ?? null)
}
