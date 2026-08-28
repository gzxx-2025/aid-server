/**
 * 分镜视频批量 refresh restore：决定先跟哪条 SSE。
 * 任务中心能显示「进行中出片」时，restore 必须以 list 为准优先出片，
 * 禁止被「已终态的提示词 taskId」分支挡住导致永不连 /task/stream。
 */

export type VideoBatchRestoreFollowTarget =
  | { kind: 'video'; taskId: number }
  | { kind: 'prompt'; taskId: number }

function parsePositiveId(raw: unknown): number | null {
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

export function resolveVideoBatchRestoreFollowTarget(input: {
  /** task/list 中进行中的出片任务 id（已按集过滤） */
  listOngoingVideoTaskId?: number | null
  /** task/list 中进行中的提示词任务 id */
  listOngoingPromptTaskId?: number | null
  /** store 持久化出片 taskId */
  storeVideoTaskId?: number | null
  /** store 持久化提示词 taskId */
  storePromptTaskId?: number | null
  /** store 出片 id 经 detail 判定仍进行中 / 详情暂不可用但仍 generating */
  storeVideoTaskTrusted?: boolean
  /** store 提示词 id 可信任（进行中或需解析 chain） */
  storePromptTaskTrusted?: boolean
}): VideoBatchRestoreFollowTarget | null {
  const listVideo = parsePositiveId(input.listOngoingVideoTaskId)
  if (listVideo != null) return { kind: 'video', taskId: listVideo }

  const storeVideo = parsePositiveId(input.storeVideoTaskId)
  if (storeVideo != null && input.storeVideoTaskTrusted) {
    return { kind: 'video', taskId: storeVideo }
  }

  const listPrompt = parsePositiveId(input.listOngoingPromptTaskId)
  if (listPrompt != null) return { kind: 'prompt', taskId: listPrompt }

  const storePrompt = parsePositiveId(input.storePromptTaskId)
  if (storePrompt != null && input.storePromptTaskTrusted) {
    return { kind: 'prompt', taskId: storePrompt }
  }

  return null
}
