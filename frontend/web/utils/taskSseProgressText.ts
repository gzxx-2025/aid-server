/**
 * SSE `progress` / `queued` 事件进度字段（对齐 `components/steps/接口.md` § SSE 实时进度推送）
 *
 * 批量任务以 `processedCount`（已处理 = 成功 + 失败）为进度分子；`successCount` / `failCount` 独立展示。
 * `currentCount` 为旧字段，语义同 `processedCount`。
 */
export type TaskEtaProgress = {
  phase?: string
  displayProgress?: number
  progressSource?: string
  remainingSecondsP50?: number
  remainingSecondsP90?: number
  estimatedStartAt?: number
  estimatedFinishAtP50?: number
  estimatedFinishAtP90?: number
  confidence?: string
  sampleCount?: number
  calculatedAt?: number
  totalCount?: number
  completedCount?: number
  runningCount?: number
  queuedCount?: number
  delayed?: boolean
  predictionVersion?: string
}

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
  /** 后端统一预计进度；前端基于时间戳本地倒计时，不额外轮询。 */
  eta?: TaskEtaProgress
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
  eta?: TaskEtaProgress
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

function normalizeEta(raw: unknown): TaskEtaProgress | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined
  const o = raw as Record<string, unknown>
  const percent = normalizePercent(o.displayProgress)
  const number = (key: string, min = 0): number | undefined => {
    const value = finiteInt(o[key])
    return value != null && value >= min ? value : undefined
  }
  return {
    phase: typeof o.phase === 'string' ? o.phase : undefined,
    displayProgress: percent,
    progressSource: typeof o.progressSource === 'string' ? o.progressSource : undefined,
    remainingSecondsP50: number('remainingSecondsP50'),
    remainingSecondsP90: number('remainingSecondsP90'),
    estimatedStartAt: number('estimatedStartAt'),
    estimatedFinishAtP50: number('estimatedFinishAtP50'),
    estimatedFinishAtP90: number('estimatedFinishAtP90'),
    confidence: typeof o.confidence === 'string' ? o.confidence : undefined,
    sampleCount: number('sampleCount'),
    calculatedAt: number('calculatedAt'),
    totalCount: number('totalCount'),
    completedCount: number('completedCount'),
    runningCount: number('runningCount'),
    queuedCount: number('queuedCount'),
    delayed: typeof o.delayed === 'boolean' ? o.delayed : undefined,
    predictionVersion:
      typeof o.predictionVersion === 'string' ? o.predictionVersion : undefined
  }
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
    progressText: String(o.progressText ?? '').trim(),
    eta: normalizeEta(o.eta)
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
    durationMs: durationMs != null && durationMs >= 0 ? durationMs : undefined,
    eta: normalizeEta(obj.eta)
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
    progressText: textFields.progressText ?? cur.progressText,
    eta: p.eta ?? cur.eta
  }
}

function resolveRemainingSeconds(
  eta: TaskEtaProgress,
  percentile: 'P50' | 'P90',
  now: number
): number | undefined {
  const finish = percentile === 'P50' ? eta.estimatedFinishAtP50 : eta.estimatedFinishAtP90
  if (typeof finish === 'number' && Number.isFinite(finish)) {
    return Math.max(0, Math.ceil((finish - now) / 1000))
  }
  const original =
    percentile === 'P50' ? eta.remainingSecondsP50 : eta.remainingSecondsP90
  if (typeof original !== 'number' || !Number.isFinite(original)) return undefined
  const elapsed =
    typeof eta.calculatedAt === 'number' ? Math.max(0, (now - eta.calculatedAt) / 1000) : 0
  return Math.max(0, Math.ceil(original - elapsed))
}

function formatEtaMinutes(seconds: number): string {
  if (seconds < 60) return '少于 1 分钟'
  if (seconds < 3600) return `${Math.max(1, Math.ceil(seconds / 60))} 分钟`
  const hours = seconds / 3600
  return `${hours < 10 ? hours.toFixed(1) : Math.ceil(hours)} 小时`
}

/**
 * 兼容部分任务把可读预计时长直接放在 `updateTime` 的返回形式。
 *
 * 通用任务文档中的 `updateTime` 仍可能是 `yyyy-MM-dd HH:mm:ss` 更新时间；日期时间不能
 * 当成预计耗时展示。只有明确包含时长单位或 `HH:mm:ss` 的值才参与预计文案。
 */
function formatDurationUpdateTime(updateTime: string | undefined): string {
  const value = String(updateTime || '').trim()
  if (!value) return ''
  if (/^\d{4}[-/]\d{1,2}[-/]\d{1,2}(?:[ T]|$)/u.test(value)) return ''
  if (/^\d{10,13}$/u.test(value)) return ''
  if (!/(?:毫秒|秒钟?|分钟?|小时|天)|^\d{1,3}:\d{2}(?::\d{2})?$/u.test(value)) return ''
  return /^预计/u.test(value) ? value : `预计${value}`
}

/** 格式化预计百分比与 P50–P90 剩余时间区间。 */
export function formatTaskEtaText(eta: TaskEtaProgress | undefined, now = Date.now()): string {
  if (!eta) return ''
  if (eta.phase === 'COMPLETED' || eta.displayProgress === 100) return '已完成 · 100%'
  const p50 = resolveRemainingSeconds(eta, 'P50', now)
  const p90 = resolveRemainingSeconds(eta, 'P90', now)
  const delayed =
    eta.delayed === true ||
    (typeof eta.estimatedFinishAtP90 === 'number' && eta.estimatedFinishAtP90 <= now)
  const percent =
    typeof eta.displayProgress === 'number'
      ? `${Math.min(95, Math.max(0, Math.round(eta.displayProgress)))}%`
      : ''
  let remaining = ''
  if (p50 === 0 && p90 === 0) {
    remaining = delayed ? '耗时超出常规区间' : '预计少于 1 分钟'
  } else if (p50 != null && p90 != null) {
    const p50Text = formatEtaMinutes(p50)
    const p90Text = formatEtaMinutes(Math.max(p50, p90))
    remaining = p50Text === p90Text ? `预计约 ${p50Text}` : `预计 ${p50Text}–${p90Text}`
  } else if (p50 != null) {
    remaining = `预计约 ${formatEtaMinutes(p50)}`
  }
  if (delayed && remaining !== '耗时超出常规区间') {
    remaining = remaining ? `${remaining}（已超出常规区间）` : '耗时超出常规区间'
  }
  return [remaining, percent].filter(Boolean).join(' · ')
}

/** 生成态使用的预计时间文案：文档 eta 优先，并兼容可读时长型 updateTime。 */
export function formatTaskSseTimingText(
  p: Pick<TaskSseProgressInput, 'eta' | 'updateTime' | 'progress' | 'status'>,
  now = Date.now()
): string {
  const terminalStatus = String(p.status || '').toUpperCase()
  if (
    ['SUCCEEDED', 'FAILED', 'CANCELLED', 'PARTIAL_FAILED'].includes(terminalStatus) ||
    p.eta?.phase === 'COMPLETED' ||
    p.eta?.displayProgress === 100 ||
    p.progress === 100
  ) {
    return ''
  }

  const etaText = formatTaskEtaText(p.eta, now)
  const updateTimeText = formatDurationUpdateTime(p.updateTime)
  if (!updateTimeText) return etaText
  if (!etaText) return updateTimeText
  if (/预计|耗时/u.test(etaText)) return etaText
  return `${updateTimeText} · ${etaText}`
}

function appendTimingText(text: string, timingText: string): string {
  if (!timingText || text.includes(timingText) || /(?:预计|耗时超出常规区间)/u.test(text)) {
    return text
  }
  return text ? `${text}，${timingText}` : timingText
}

/**
 * 为所有 SSE 订阅者统一补齐展示文案。保留原始 `stepTitle` 供阶段判断，预计时间只写入
 * `message`，避免各业务弹窗分别拼接并产生不一致。
 */
export function withTaskSseDisplayTiming(
  p: TaskSseProgressInput,
  now = Date.now()
): TaskSseProgressInput {
  const timingText = formatTaskSseTimingText(p, now)
  if (!timingText) return p
  const message = appendTimingText(String(p.message || p.stepTitle || '').trim(), timingText)
  return message === p.message ? p : { ...p, message }
}

/**
 * 只推进浏览器内的预计百分比。服务端真实 progress 保持不变，且预计值在非终态最高 95%。
 */
export function advanceTaskEtaProgress(
  eta: TaskEtaProgress | undefined,
  now = Date.now()
): TaskEtaProgress | undefined {
  if (!eta || eta.phase === 'COMPLETED' || eta.displayProgress === 100) return eta
  const startAt = eta.calculatedAt
  const finishAt = eta.estimatedFinishAtP90
  if (
    typeof startAt !== 'number' ||
    typeof finishAt !== 'number' ||
    !Number.isFinite(startAt) ||
    !Number.isFinite(finishAt) ||
    finishAt <= startAt
  ) {
    return { ...eta }
  }
  const ratio = Math.min(1, Math.max(0, (now - startAt) / (finishAt - startAt)))
  const base = Math.min(95, Math.max(0, eta.displayProgress ?? 0))
  const displayProgress = Math.min(95, Math.max(base, Math.round(base + (95 - base) * ratio)))
  return {
    ...eta,
    displayProgress,
    delayed: eta.delayed || now >= finishAt
  }
}

function appendSseTimingText(
  text: string,
  p: Pick<TaskSseProgressInput, 'eta' | 'updateTime' | 'progress' | 'status'>
): string {
  return appendTimingText(text, formatTaskSseTimingText(p))
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
  p: Partial<CountProgressSnapshot & TaskSseProgressInput>,
  fallback: string
): string {
  const msg = String(p.message || '').trim()
  const step = String(p.stepTitle || '').trim()
  const live = msg || step
  return appendSseTimingText(live || fallback, p)
}

/**
 * 拼接 SSE stepTitle / message；两者相同（解析层常把 message 回填到 stepTitle）时不去重成「A · A」。
 */
export function formatTaskSseJoinedLiveText(
  p: Partial<CountProgressSnapshot & TaskSseProgressInput>,
  fallback: string
): string {
  const msg = String(p.message || '').trim()
  const step = String(p.stepTitle || '').trim()
  if (step && msg && step !== msg) {
    if (msg.includes(step)) return appendSseTimingText(msg, p)
    if (step.includes(msg)) return appendSseTimingText(step, p)
    return appendSseTimingText(`${step} · ${msg}`, p)
  }
  return appendSseTimingText(step || msg || fallback, p)
}

/** 优先 SSE 文案；无文案时用 progressText 或 completed/total 兜底句式 */
export function formatTaskSseLiveTextWithCounts(
  p: Partial<CountProgressSnapshot & TaskSseProgressInput>,
  fallbackPrefix: string
): string {
  const message = String(p.message || '').trim()
  const step = String(p.stepTitle || '').trim()
  const live = message || step
  if (live) return appendSseTimingText(live, p)
  const progressText = String(p.progressText || '').trim()
  if (progressText) return appendSseTimingText(`${fallbackPrefix} ${progressText}…`, p)
  if (p.total != null && p.total > 0) {
    const failHint =
      typeof p.failCount === 'number' && p.failCount > 0 ? `，失败 ${p.failCount}` : ''
    return appendSseTimingText(
      `${fallbackPrefix} ${p.completed ?? 0}/${p.total}${failHint}…`,
      p
    )
  }
  return appendSseTimingText(`${fallbackPrefix}…`, p)
}
