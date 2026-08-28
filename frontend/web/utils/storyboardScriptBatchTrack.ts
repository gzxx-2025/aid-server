/**
 * 分镜脚本批量生成：SSE 跟踪与终态判定（原 composables/useStoryboardScriptBatchGenerate.ts
 * 模块级纯函数 + trackTaskUntilDone 拆分，闭包状态经 ctx 显式传入，主体见
 * hooks/useStoryboardScriptBatchGenerate.ts）。
 */

import { applyStoryboardScriptPanelsFromApi } from '~/composables/useCreateFlowStoryboardSync'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import {
fetchUserTaskDetailOnce,
normalizeTaskStatus
} from '~/composables/useTaskSseFollow'
import {
createTaskStream,
type TaskProgressEventData,
type TaskStreamResult
} from '~/composables/useTaskStream'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel } from '~/types'
import type { UserTaskDetailData,UserTaskRow } from '~/types/business-api'
import { userStoryboardList } from '~/utils/businessApi'
import {
getPersistedStoryboardScriptPanels,
mapStoryboardListRowToPanel
} from '~/utils/storyboardPanelMap'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
formatPartialFailedMessage,
parseTaskPartialFailedData,
type TaskPartialFailedData
} from '~/utils/taskPartialFailed'
import {
isBenignTaskSseDisconnectMessage,
isNavigationOrSuspendBatchMessage
} from '~/utils/taskSseSilentDisconnect'

export function storyboardScriptBizErr(e: unknown): string {
  const x = e as { msg?: string; message?: string }
  return x?.msg || x?.message || '操作失败'
}

export function parseStoryboardScriptTaskId(raw: unknown): number | null {
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

function normStoryboardScriptTaskType(ty: unknown): string {
  return String(ty ?? '').trim().toLowerCase().replace(/-/g, '_')
}

export function isStoryboardScriptBatchTask(ty: unknown): boolean {
  return normStoryboardScriptTaskType(ty) === 'storyboard_script_batch'
}

export function isOngoingStoryboardScriptTaskStatus(status: unknown): boolean {
  const s = String(status ?? '').toUpperCase()
  return s === 'PENDING' || s === 'PROCESSING' || s === 'RUNNING' || s === 'QUEUED' || s === 'WAITING'
}

/** 后端防重：同 project+episode 已有进行中的分镜脚本任务 */
export function isStoryboardScriptTaskBusyMessage(msg: string): boolean {
  const m = String(msg ?? '').trim()
  if (!m) return false
  return (
    m === '任务处理中' ||
    m.includes('任务处理中') ||
    m.includes('正在执行的分镜任务') ||
    m.includes('项目下有正在执行')
  )
}

export function storyboardScriptSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export function hasPersistedStoryboards(panels: StoryboardPanel[]): boolean {
  return panels.some((p) => parseServerStoryboardId(p.id) != null)
}

export function safeParseResultData(raw: unknown): unknown {
  if (raw == null) return null
  if (typeof raw === 'object') return raw
  const s = String(raw).trim()
  if (!s) return null
  try {
    return JSON.parse(s)
  } catch {
    return null
  }
}

/* ---------- 状态无关的 store 结果落地助手（原创建器内函数，仅依赖全局 store） ---------- */

const getStore = () => useCreationStore.getState()

export function resolveScenePlotCountHint(): number {
  const scenes = getStore().formData.sceneCharacter?.scenes || []
  const count = scenes.filter((s: unknown) => String(s ?? '').trim().length > 0).length
  return Math.max(count, 1)
}

/** 拉取分镜列表并作为唯一数据源写回 store（终态后调用，不在生成中轮询） */
export async function refreshStoryboardScriptPanelsFromApi(): Promise<StoryboardPanel[]> {
  const ctx = await resolveStoryScriptSaveContext(getStore(), getRouteLikeSnapshot())
  if (!ctx) return []
  const list = await userStoryboardList({
    projectId: ctx.projectId,
    episodeId: ctx.episodeId
  })
  const sorted = [...list].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
  const panels = sorted.map((row, index) => mapStoryboardListRowToPanel(row, index))
  applyStoryboardScriptPanelsFromApi(panels)
  return panels
}

export function seedStoryboardScriptProgressFromDetailRecord(
  detail: UserTaskDetailData | null | undefined
) {
  if (!detail) return
  const totalBatches = Number((detail as { totalBatches?: number }).totalBatches)
  const total = Number.isFinite(totalBatches) && totalBatches > 0 ? totalBatches : 0
  if (total > 0) {
    const cur = getStore().storyboardGenerationProgress
    if (!cur.total || cur.total < total) {
      getStore().setStoryboardProgress(Math.min(cur.completed, total), total)
    }
  }
}

export function applyStoryboardScriptSuccessOutcome() {
  getStore().clearStoryboardScriptGenerationOutcome()
  getStore().setStoryboardGenerating(false)
}

function resetStoryboardScriptListToEmpty() {
  applyStoryboardScriptPanelsFromApi([])
  getStore().setStoryboardProgress(0, 0)
}

function shouldPersistStoryboardScriptGenerationError(panels: StoryboardPanel[]): boolean {
  return getPersistedStoryboardScriptPanels(panels).length > 0
}

export function applyStoryboardScriptPartialFailedOutcome(
  taskId: number,
  msg: string,
  partialData: TaskPartialFailedData | null,
  panels: StoryboardPanel[]
) {
  if (!shouldPersistStoryboardScriptGenerationError(panels)) {
    getStore().clearStoryboardScriptGenerationOutcome()
    resetStoryboardScriptListToEmpty()
    getStore().setStoryboardGenerating(false)
    return
  }
  getStore().setStoryboardPartialFailedOutcome(msg, taskId, partialData)
}

export function applyStoryboardScriptFailedOutcome(msg: string, panels: StoryboardPanel[]) {
  getStore().clearStoryboardScriptGenerationOutcome()
  if (shouldPersistStoryboardScriptGenerationError(panels)) {
    getStore().setStoryboardError(msg)
  } else {
    resetStoryboardScriptListToEmpty()
  }
  getStore().setStoryboardGenerating(false)
}

export function pickOngoingStoryboardScriptTask(
  tasks: UserTaskRow[],
  preferredTaskId?: number | null
): UserTaskRow | null {
  const ongoing = tasks
    .filter(
      (t) =>
        t && isStoryboardScriptBatchTask(t.taskType) && isOngoingStoryboardScriptTaskStatus(t.status)
    )
    .sort((a, b) => Number(b.id || 0) - Number(a.id || 0))

  if (!ongoing.length) return null

  const pref = parseStoryboardScriptTaskId(preferredTaskId)
  if (pref != null) {
    const hit = ongoing.find((t) => Number(t.id) === pref)
    if (hit) return hit
  }
  return ongoing[0] ?? null
}

export type StoryboardScriptTrackOutcome = {
  ok: boolean
  partial?: boolean
  message?: string
  partialData?: TaskPartialFailedData | null
  /** SSE 断连但服务端任务仍在跑：勿清 taskId / 勿当终态失败 */
  ongoing?: boolean
}

export function resolveOutcomeFromTaskDetail(
  detail: UserTaskDetailData | null
): StoryboardScriptTrackOutcome | null {
  if (!detail) return null
  const status = normalizeTaskStatus(detail.status)
  if (status === 'SUCCEEDED') {
    return { ok: true }
  }
  if (status === 'PARTIAL_FAILED') {
    const parsed = parseTaskPartialFailedData(
      detail.resultData ? safeParseResultData(detail.resultData) : null
    )
    return {
      ok: false,
      partial: true,
      message: formatPartialFailedMessage(
        parsed,
        detail.errorMessage || '部分场次生成失败，可点击续生重试失败项'
      ),
      partialData: parsed
    }
  }
  if (status === 'FAILED' || status === 'CANCELLED') {
    return { ok: false, message: detail.errorMessage || '分镜生成失败' }
  }
  if (isOngoingStoryboardScriptTaskStatus(status)) {
    return null
  }
  return { ok: true }
}

export function mapStreamResultToOutcome(res: TaskStreamResult): StoryboardScriptTrackOutcome {
  if (res.type === 'cancelled') {
    return { ok: false, message: res.message || '任务已取消' }
  }
  if (res.type === 'error') {
    return { ok: false, message: res.errorMessage || '分镜生成失败' }
  }
  if (res.type === 'partial_failed') {
    return {
      ok: false,
      partial: true,
      message: formatPartialFailedMessage(
        res.data,
        '部分场次生成失败，可点击续生重试失败项'
      ),
      partialData: res.data
    }
  }
  return { ok: true }
}

/** trackTaskUntilDone 的闭包状态由主体 hook 显式注入（stopRequested / 恢复代数 / 流关闭器槽位） */
export interface StoryboardScriptTrackCtx {
  isStopRequested: () => boolean
  getResumeFollowGeneration: () => number
  setStreamCloser: (closer: (() => void) | null) => void
}

export async function trackStoryboardScriptTaskUntilDone(
  taskId: number,
  onSseProgress: (p: TaskProgressEventData) => void,
  ctx: StoryboardScriptTrackCtx,
  options?: { startDetail?: UserTaskDetailData | null }
): Promise<StoryboardScriptTrackOutcome> {
  if (ctx.isStopRequested()) {
    return { ok: false, message: '已停止生成' }
  }

  const startDetail = options?.startDetail ?? null
  if (startDetail) {
    const early = resolveOutcomeFromTaskDetail(startDetail)
    if (early?.ok || early?.partial) {
      return early
    }
    const startStatus = normalizeTaskStatus(startDetail.status)
    if (startStatus === 'FAILED' || startStatus === 'CANCELLED') {
      return early ?? { ok: false, message: startDetail.errorMessage || '分镜生成失败' }
    }
  }

  const maxReconnects = 3
  let lastStreamOutcome: StoryboardScriptTrackOutcome | null = null
  const streamGen = ctx.getResumeFollowGeneration()

  for (let attempt = 0; attempt <= maxReconnects; attempt++) {
    if (ctx.isStopRequested()) {
      return { ok: false, message: '已停止生成' }
    }
    if (streamGen !== ctx.getResumeFollowGeneration()) {
      return {
        ok: false,
        ongoing: true,
        message: '任务仍在后台执行，请稍候或刷新页面自动恢复进度'
      }
    }

    const stream = createTaskStream(taskId)
    ctx.setStreamCloser(() => {
      try {
        stream.close()
      } catch {
        /* ignore */
      }
    })

    // 原 watch(stream.lastProgress, cb, { immediate: true })：订阅时已有进度会立即回调一次
    const stopWatchProgress = stream.subscribeProgress((p) => {
      if (p) onSseProgress(p)
    })

    let streamResult: TaskStreamResult | null = null
    try {
      streamResult = await stream.done
    } catch {
      streamResult = null
    } finally {
      stopWatchProgress()
      try {
        stream.close()
      } catch {
        /* ignore */
      }
      ctx.setStreamCloser(null)
    }

    if (ctx.isStopRequested()) {
      return { ok: false, message: '已停止生成' }
    }

    if (streamResult) {
      const mapped = mapStreamResultToOutcome(streamResult)
      if (mapped.ok || mapped.partial) {
        return mapped
      }
      lastStreamOutcome = mapped
      if (streamResult.type === 'cancelled' || streamResult.type === 'error') {
        break
      }
    }

    if (attempt < maxReconnects) {
      await storyboardScriptSleep(800)
    }
  }

  // SSE 结束后仅补查一次 task/detail（不做轮询）
  const endDetail = await fetchUserTaskDetailOnce(taskId)
  const fromDetail = resolveOutcomeFromTaskDetail(endDetail)
  if (fromDetail) {
    return fromDetail
  }
  // SSE 业务失败优先于滞后的 PROCESSING detail，避免永久保活 loading
  if (lastStreamOutcome && !lastStreamOutcome.ok && lastStreamOutcome.message) {
    const msg = String(lastStreamOutcome.message)
    const isBenignDisconnect =
      isBenignTaskSseDisconnectMessage(msg) || isNavigationOrSuspendBatchMessage(msg)
    if (!isBenignDisconnect) {
      return lastStreamOutcome
    }
  }
  if (isOngoingStoryboardScriptTaskStatus(endDetail?.status)) {
    return {
      ok: false,
      ongoing: true,
      message: '任务仍在后台执行，请稍候或刷新页面自动恢复进度'
    }
  }
  // 无终态详情时：视为后台仍在执行（切步/suspend 断流），禁止「连接中断请稍后重试」假失败
  if (lastStreamOutcome && !lastStreamOutcome.ok && lastStreamOutcome.message) {
    const msg = String(lastStreamOutcome.message)
    if (
      msg.includes('连接中断') ||
      msg.includes('连接异常') ||
      /abort|superseded|ended unexpectedly/i.test(msg)
    ) {
      return {
        ok: false,
        ongoing: true,
        message: '任务仍在后台执行，请稍候或刷新页面自动恢复进度'
      }
    }
    return lastStreamOutcome
  }
  return {
    ok: false,
    ongoing: true,
    message:
      endDetail?.errorMessage && !String(endDetail.errorMessage).includes('连接中断')
        ? String(endDetail.errorMessage)
        : '任务仍在后台执行，请稍候或刷新页面自动恢复进度'
  }
}
