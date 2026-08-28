/** form_image / form_image_batch SSE complete 与 task.resultData 结构 */
export interface FormImageBatchCompleteData {
  totalCount?: number
  successCount?: number
  failCount?: number
  successItems?: unknown[]
  failedItems?: Array<{ formId?: number; assetId?: number; index?: number; message?: string }>
}

export type FormImageBatchCompleteOutcome =
  | { ok: true; successCount: number; failCount: number; partialFailMessages?: string[] }
  | { ok: false; errorMessage: string; failCount?: number }

function parseResultPayload(raw: unknown): FormImageBatchCompleteData | null {
  if (raw == null) return null
  if (typeof raw === 'object' && !Array.isArray(raw)) return raw as FormImageBatchCompleteData
  if (typeof raw === 'string') {
    const text = raw.trim()
    if (!text) return null
    try {
      const parsed = JSON.parse(text) as unknown
      return typeof parsed === 'object' && parsed != null && !Array.isArray(parsed)
        ? (parsed as FormImageBatchCompleteData)
        : null
    } catch {
      return null
    }
  }
  return null
}

/**
 * 失败文案去重（保留首次出现顺序）。
 * 单项失败常返回相同文案；也会拆开已用「；」或「;」拼好的长串，避免 toast 重复。
 */
export function uniqueFailMessages(messages: Array<string | null | undefined>): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const raw of messages) {
    if (raw == null) continue
    const parts = String(raw)
      .split(/[；;]+/)
      .map((s) => s.trim())
      .filter(Boolean)
    for (const part of parts) {
      if (seen.has(part)) continue
      seen.add(part)
      out.push(part)
    }
  }
  return out
}

/** 去重后用分隔符拼接失败文案 */
export function uniqueJoinFailMessages(
  messages: Array<string | null | undefined>,
  separator = '；'
): string {
  return uniqueFailMessages(messages).join(separator)
}

/**
 * 解析形态图批量任务 complete / resultData。
 * 后端在单项失败时仍推 complete（非 error），须根据 successCount / failedItems 判断成败。
 */
export function resolveFormImageBatchCompleteOutcome(data: unknown): FormImageBatchCompleteOutcome | null {
  const o = parseResultPayload(data)
  if (!o) return null

  const failedItems = Array.isArray(o.failedItems) ? o.failedItems : []
  const successItems = Array.isArray(o.successItems) ? o.successItems : []
  const total = typeof o.totalCount === 'number' ? o.totalCount : undefined
  const success = typeof o.successCount === 'number' ? o.successCount : undefined
  const fail = typeof o.failCount === 'number' ? o.failCount : undefined

  const hasBatchFields =
    total != null || success != null || fail != null || failedItems.length > 0 || successItems.length > 0
  if (!hasBatchFields) return null

  const successCount = success ?? successItems.length
  const failCount = fail ?? failedItems.length
  const failMessages = uniqueFailMessages(
    failedItems.map((x) => String(x?.message ?? '').trim()).filter(Boolean)
  )

  if (successCount <= 0 && failCount > 0) {
    const errorMessage = uniqueJoinFailMessages(failMessages) || '形态图生成失败'
    return { ok: false as const, errorMessage, failCount }
  }

  if (successCount > 0 && failCount > 0) {
    return {
      ok: true as const,
      successCount,
      failCount,
      partialFailMessages: failMessages
    }
  }

  return { ok: true as const, successCount: Math.max(successCount, 0), failCount: Math.max(failCount, 0) }
}

/** startTrackTask finally → waitForStep3FormImageTaskDone 的成败判定入参 */
export type Step3FormImageTrackDoneRes =
  | { type: 'complete'; data: unknown }
  | { type: 'partial_failed'; data: unknown; errorMessage?: string }
  | { type: 'error'; errorMessage?: string }

/**
 * 形态图 SSE 跟进收尾：不得仅凭 event type=complete 判成功。
 * 后端单项全失败仍推 complete，须用 successCount / failedItems 判定，并回传 SSE 文案。
 */
export function resolveStep3FormImageTrackDoneOutcome(payload: {
  didFinalizeStep3Task: boolean
  res: Step3FormImageTrackDoneRes | null | undefined
}): { ok: boolean; errorMessage?: string } {
  if (!payload.didFinalizeStep3Task || payload.res == null) {
    return { ok: false, errorMessage: '形态图生成未完成' }
  }
  const res = payload.res
  if (res.type === 'error') {
    return {
      ok: false,
      errorMessage: String(res.errorMessage || '').trim() || '形态图生成未完成'
    }
  }
  if (res.type === 'complete' || res.type === 'partial_failed') {
    const batch = resolveFormImageBatchCompleteOutcome(res.data)
    if (batch?.ok === false) {
      return { ok: false, errorMessage: batch.errorMessage }
    }
    return { ok: true }
  }
  return { ok: false, errorMessage: '形态图生成未完成' }
}
