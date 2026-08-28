/** 合并生成任务的链式下一步终态。 */
export interface TaskChainFailure {
  message: string
  childTaskType?: string
}

/** 将 SSE payload 或 task/detail.resultData 统一解析为对象。 */
export function parseTaskResultPayload(payload: unknown): Record<string, unknown> | null {
  let current = payload
  for (let depth = 0; depth < 2 && typeof current === 'string'; depth++) {
    const text = current.trim()
    if (!text) return null
    try {
      current = JSON.parse(text)
    } catch {
      return null
    }
  }
  if (!current || typeof current !== 'object' || Array.isArray(current)) return null
  return current as Record<string, unknown>
}

/**
 * 结构化识别提示词成功、自动出图/出片提交失败。
 * 只信任 chainFailed 字段，禁止通过余额等展示文案猜测业务状态。
 */
export function parseTaskChainFailure(payload: unknown): TaskChainFailure | null {
  const data = parseTaskResultPayload(payload)
  if (!data || data.chainFailed !== true) return null
  const message = String(data.chainMessage ?? '').trim() || '下一步提交失败'
  const childTaskType = String(data.chainChildTaskType ?? '').trim()
  return {
    message,
    ...(childTaskType ? { childTaskType } : {})
  }
}
