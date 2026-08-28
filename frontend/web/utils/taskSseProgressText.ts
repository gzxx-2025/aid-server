/**
 * SSE `progress` / `queued` 事件进度字段（对齐 `components/steps/接口.md` § SSE 实时进度推送）
 *
 * 批量任务以 `processedCount`（已处理 = 成功 + 失败）为进度分子；`successCount` / `failCount` 独立展示。
 * `currentCount` 为旧字段，语义同 `processedCount`。
 */
export type TaskSseProgressInput = {
  taskId?: number
  status?: string
  stage?: string
  progress?: number
  message?: string
  stepId?: string
  stepTitle?: string
  stepIndex?: number
  stepTotal?: number
  updateTime?: string
  updateMillis?: number
  /** 子项总数 */
  totalCount?: number
  /** 已处理数（成功 + 失败），进度分子 */
  processedCount?: number
  /** 兼容旧字段，同 processedCount */
  currentCount?: number
  /** 提交阶段已向调度中心提交的条数（视频批量出片） */
  submittedCount?: number
  successCount?: number
  failCount?: number
  /** 如 "6/14" */
  progressText?: string
  /** queued 事件：排队位次（1-based） */
  position?: number
  ahead?: number
  queueTotal?: number
  blockedBy?: string | null
  /** 对口型等单条任务：分镜 ID */
  storyboardId?: number
  /** 对口型配音阶段：TTS 记录 ID */
  audioRecordId?: number
  /** 对口型配音阶段：可试听音频 URL（通常已拼域名） */
  audioUrl?: string
  /** 对口型配音阶段：音频时长（毫秒） */
  durationMs?: number
}

/** 带计数的批量任务进度（completed/total + SSE 文案，可持久化到 Pinia） */
export type CountProgressSnapshot = {
  /** 已处理数，对应 SSE processedCount */
  completed: number
  total: number
  successCount: number
  failCount: number
  message: string
  stepTitle: string
  progressText: string
}

export const EMPTY_COUNT_PROGRESS: CountProgressSnapshot = {
  completed: 0,
  total: 0,
  successCount: 0,
  failCount: 0,
  message: '',
  stepTitle: '',
  progressText: ''
}

function finiteInt(v: unknown): number | null {
  const n = Number(v)
  if (!Number.isFinite(n)) return null
  return Math.trunc(n)
}

function normalizePercent(p: unknown): number | undefined {
  const n = Number(p)
  if (!Number.isFinite(n)) return undefined
  return Math.min(100, Math.max(0, n))
}

/** 从 Pinia 持久化或旧版快照恢复进度（兼容无 message / successCount 等字段的历史数据） */
export function normalizeCountProgress(raw: unknown): CountProgressSnapshot {
  if (!raw || typeof raw !== 'object') {
    return { ...EMPTY_COUNT_PROGRESS }
  }
  const o = raw as Record<string, unknown>
  return {
    completed: Number.isFinite(Number(o.completed)) ? Number(o.completed) : 0,
    total: Number.isFinite(Number(o.total)) ? Number(o.total) : 0,
    successCount: Number.isFinite(Number(o.successCount)) ? Number(o.successCount) : 0,
    failCount: Number.isFinite(Number(o.failCount)) ? Number(o.failCount) : 0,
    message: String(o.message ?? '').trim(),
    stepTitle: String(o.stepTitle ?? '').trim(),
    progressText: String(o.progressText ?? '').trim()
  }
}

/**
 * 解析 SSE progress / queued 的 data JSON。
 * `expectedTaskId` 存在时严格校验 taskId，不一致则丢弃（防多任务串写）。
 */
export function parseTaskSseProgressPayload(
  raw: unknown,
  expectedTaskId?: number
): TaskSseProgressInput | null {
  if (raw == null) return null

  let obj: Record<string, unknown>
  if (typeof raw === 'string') {
    const text = raw.trim()
    if (!text) return null
    try {
      const parsed = JSON.parse(text)
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
        return { message: text, stepTitle: text }
      }
      obj = parsed as Record<string, unknown>
    } catch {
      return { message: text, stepTitle: text }
    }
  } else if (typeof raw === 'object' && !Array.isArray(raw)) {
    obj = raw as Record<string, unknown>
  } else {
    return null
  }

  if (expectedTaskId != null && obj.taskId != null) {
    if (String(obj.taskId) !== String(expectedTaskId)) return null
  }

  const stepIndex = finiteInt(obj.stepIndex)
  const stepTotal = finiteInt(obj.stepTotal)
  const processedCount = finiteInt(obj.processedCount ?? obj.currentCount)
  const totalCount = finiteInt(obj.totalCount)
  const submittedCount = finiteInt(obj.submittedCount)
  const successCount = finiteInt(obj.successCount)
  const failCount = finiteInt(obj.failCount)
  const position = finiteInt(obj.position)
  const ahead = finiteInt(obj.ahead)
  const queueTotal = finiteInt(obj.queueTotal)
  const taskId = finiteInt(obj.taskId)
  const updateMillis = finiteInt(obj.updateMillis)
  const storyboardId = finiteInt(obj.storyboardId)
  const audioRecordId = finiteInt(obj.audioRecordId)
  const durationMs = finiteInt(obj.durationMs)

  const msg = typeof obj.message === 'string' ? obj.message : undefined
  const title = typeof obj.stepTitle === 'string' ? obj.stepTitle : undefined
  const audioUrl = typeof obj.audioUrl === 'string' ? obj.audioUrl.trim() : ''

  const progressFromServer = normalizePercent(obj.progress)
  const progressFromStep =
    stepIndex != null && stepTotal != null && stepTotal > 0
      ? normalizePercent((stepIndex / stepTotal) * 100)
      : undefined
  const progressFromProcessed =
    processedCount != null && totalCount != null && totalCount > 0
      ? normalizePercent((processedCount / totalCount) * 100)
      : undefined

  return {
    taskId: taskId != null && taskId > 0 ? taskId : undefined,
    status: typeof obj.status === 'string' ? obj.status : undefined,
    stage: typeof obj.stage === 'string' ? obj.stage : undefined,
    progress: progressFromServer ?? progressFromProcessed ?? progressFromStep,
    message: msg,
    stepId: typeof obj.stepId === 'string' ? obj.stepId : undefined,
    stepTitle: title || msg,
    stepIndex: stepIndex != null && stepIndex >= 0 ? stepIndex : undefined,
    stepTotal: stepTotal != null && stepTotal > 0 ? stepTotal : undefined,
    updateTime: typeof obj.updateTime === 'string' ? obj.updateTime : undefined,
    updateMillis: updateMillis != null && updateMillis >= 0 ? updateMillis : undefined,
    totalCount: totalCount != null && totalCount >= 0 ? totalCount : undefined,
    processedCount: processedCount != null && processedCount >= 0 ? processedCount : undefined,
    currentCount: processedCount != null && processedCount >= 0 ? processedCount : undefined,
    submittedCount: submittedCount != null && submittedCount >= 0 ? submittedCount : undefined,
    successCount: successCount != null && successCount >= 0 ? successCount : undefined,
    failCount: failCount != null && failCount >= 0 ? failCount : undefined,
    progressText: typeof obj.progressText === 'string' ? obj.progressText : undefined,
    position: position != null && position > 0 ? position : undefined,
    ahead: ahead != null && ahead >= 0 ? ahead : undefined,
    queueTotal: queueTotal != null && queueTotal >= 0 ? queueTotal : undefined,
    blockedBy:
      obj.blockedBy === null
        ? null
        : typeof obj.blockedBy === 'string'
          ? obj.blockedBy
          : undefined,
    storyboardId: storyboardId != null && storyboardId > 0 ? storyboardId : undefined,
    audioRecordId: audioRecordId != null && audioRecordId > 0 ? audioRecordId : undefined,
    audioUrl: audioUrl || undefined,
    durationMs: durationMs != null && durationMs >= 0 ? durationMs : undefined
  }
}

export function pickSseTextFields(
  p: TaskSseProgressInput
): Partial<Pick<CountProgressSnapshot, 'message' | 'stepTitle' | 'progressText'>> {
  const msg = String(p.message || '').trim()
  const step = String(p.stepTitle || '').trim()
  const progressText = String(p.progressText || '').trim()
  const out: Partial<Pick<CountProgressSnapshot, 'message' | 'stepTitle' | 'progressText'>> = {}
  if (msg) out.message = msg
  if (step) out.stepTitle = step
  if (progressText) out.progressText = progressText
  return out
}

/**
 * 从 SSE progress 推断 completed/total。
 * 优先级：processedCount+totalCount > stepIndex+stepTotal > 百分比估算。
 */
export function resolveCountProgressFromSse(
  p: TaskSseProgressInput,
  cur: Pick<CountProgressSnapshot, 'completed' | 'total'>
): Pick<CountProgressSnapshot, 'completed' | 'total'> | null {
  const processed = p.processedCount ?? p.currentCount
  const totalFromCount =
    typeof p.totalCount === 'number' && p.totalCount > 0 ? p.totalCount : null
  const processedFromCount =
    typeof processed === 'number' && processed >= 0 ? processed : null

  if (totalFromCount != null && processedFromCount != null) {
    return { completed: processedFromCount, total: totalFromCount }
  }

  const totalFromSteps =
    typeof p.stepTotal === 'number' && p.stepTotal > 0 ? p.stepTotal : null
  const completedFromSteps =
    typeof p.stepIndex === 'number' && p.stepIndex >= 0 ? p.stepIndex : null

  if (totalFromSteps != null && completedFromSteps != null) {
    return { completed: completedFromSteps, total: totalFromSteps }
  }

  const percent = typeof p.progress === 'number' ? p.progress : null
  const total = Math.max(cur.total || 1, 1)
  if (percent != null) {
    const completed = Math.min(total, Math.max(0, Math.round((percent / 100) * total)))
    return { completed, total }
  }

  return null
}

function resolveSuccessFailFromSse(
  p: TaskSseProgressInput,
  cur: Pick<CountProgressSnapshot, 'successCount' | 'failCount'>
): Pick<CountProgressSnapshot, 'successCount' | 'failCount'> {
  return {
    successCount:
      typeof p.successCount === 'number' && p.successCount >= 0
        ? p.successCount
        : cur.successCount,
    failCount:
      typeof p.failCount === 'number' && p.failCount >= 0 ? p.failCount : cur.failCount
  }
}

/** 合并 SSE 事件到当前进度快照（计数 + 文案） */
export function mergeCountProgressFromSse(
  cur: CountProgressSnapshot,
  p: TaskSseProgressInput
): CountProgressSnapshot {
  const counts = resolveCountProgressFromSse(p, cur)
  const textFields = pickSseTextFields(p)
  const successFail = resolveSuccessFailFromSse(p, cur)
  return {
    completed: counts?.completed ?? cur.completed,
    total: counts?.total ?? cur.total,
    successCount: successFail.successCount,
    failCount: successFail.failCount,
    message: textFields.message ?? cur.message,
    stepTitle: textFields.stepTitle ?? cur.stepTitle,
    progressText: textFields.progressText ?? cur.progressText
  }
}

/** 将 SSE 进度映射为 step3 / 提取 UI 用的 stepIndex / stepTotal（优先 processedCount） */
export function resolveStepIndexTotalFromSse(p: TaskSseProgressInput): {
  stepIndex: number | null
  stepTotal: number | null
} {
  const counts = resolveCountProgressFromSse(p, { completed: 0, total: 0 })
  if (counts && counts.total > 0) {
    return { stepIndex: counts.completed, stepTotal: counts.total }
  }
  return {
    stepIndex: typeof p.stepIndex === 'number' ? p.stepIndex : null,
    stepTotal: typeof p.stepTotal === 'number' ? p.stepTotal : null
  }
}

/** 优先 SSE message / stepTitle，否则返回 fallback */
export function formatTaskSseLiveText(
  p: Partial<CountProgressSnapshot>,
  fallback: string
): string {
  const msg = String(p.message || '').trim()
  const step = String(p.stepTitle || '').trim()
  const live = msg || step
  return live || fallback
}

/**
 * 拼接 SSE stepTitle / message；两者相同（解析层常把 message 回填到 stepTitle）时不去重成「A · A」。
 */
export function formatTaskSseJoinedLiveText(
  p: Partial<Pick<CountProgressSnapshot, 'message' | 'stepTitle'>> & {
    message?: string
    stepTitle?: string
  },
  fallback: string
): string {
  const msg = String(p.message || '').trim()
  const step = String(p.stepTitle || '').trim()
  if (step && msg && step !== msg) return `${step} · ${msg}`
  return step || msg || fallback
}

/** 优先 SSE 文案；无文案时用 progressText 或 completed/total 兜底句式 */
export function formatTaskSseLiveTextWithCounts(
  p: Partial<CountProgressSnapshot>,
  fallbackPrefix: string
): string {
  const live = formatTaskSseLiveText(p, '')
  if (live) return live
  const progressText = String(p.progressText || '').trim()
  if (progressText) return `${fallbackPrefix} ${progressText}…`
  if (p.total != null && p.total > 0) {
    const failHint =
      typeof p.failCount === 'number' && p.failCount > 0 ? `，失败 ${p.failCount}` : ''
    return `${fallbackPrefix} ${p.completed ?? 0}/${p.total}${failHint}…`
  }
  return `${fallbackPrefix}…`
}
