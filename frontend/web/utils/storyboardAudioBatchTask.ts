/**
 * 分镜批量配音任务（框架无关部分）：提交 / SSE 跟随 / 终态数据解析 / 结果回写。
 * 有状态的批量实例（loading 分桶、恢复、切作品交接）见 `hooks/useStoryboardAudioBatchGenerate.ts`。
 */
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import { userStoryboardGenerateAudioBatch } from '~/utils/businessApi'
import { openRechargeModalFromInsufficientBalance } from '~/utils/api'
import {
  fetchUserTaskDetailOnce,
  isOngoingUserTaskStatus,
  normalizeTaskStatus,
  TASK_SSE_TIMEOUT_MS,
  waitUserTaskSseTerminal
} from '~/composables/useTaskSseFollow'
import {
  modalGenSessionScopeFromStore,
  readScopedSessionItem,
  removeScopedSessionItem,
  writeScopedSessionItem,
  type ModalGenSessionScope
} from '~/utils/modalGenSessionScope'
import { formatStoryboardSpeakerRoles } from '~/utils/storyboardDubbingSpeaker'
import {
  isNavigationOrSuspendBatchMessage,
  isTaskBackgroundRunningMessage,
  shouldPreferSseBusinessTerminalOverOngoingDetail
} from '~/utils/taskSseSilentDisconnect'
import type { useCreationStore } from '~/stores/creation'
import type {
  StoryboardAudioBatchRequest,
  StoryboardAudioBatchResultData,
  StoryboardAudioBatchResultItem,
  UserTaskRow
} from '~/types/business-api'
import type { DubbingPanel, StoryboardPanel } from '~/types'

const AUDIO_BATCH_RESTORE_SESSION_KEY = 'create-flow:storyboard-audio-batch-restore'
export const TASK_BACKGROUND_RUNNING_MESSAGE = '任务仍在后台执行，请稍候或刷新页面自动恢复进度'

export function bizErr(e: unknown): string {
  const x = e as { msg?: string; message?: string }
  return x?.msg || x?.message || '操作失败'
}

export function parseTaskId(raw: unknown): number | null {
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

function normStoryboardAudioBatchTaskType(ty: unknown): string {
  return String(ty ?? '')
    .trim()
    .toLowerCase()
    .replace(/-/g, '_')
}

export function isStoryboardAudioBatchTaskType(ty: unknown): boolean {
  return normStoryboardAudioBatchTaskType(ty) === 'storyboard_audio_generate'
}

export function shouldKeepAudioBatchLoadingAfterFollowMessage(msg: unknown): boolean {
  return isTaskBackgroundRunningMessage(msg) || isNavigationOrSuspendBatchMessage(msg)
}

export function parseAudioBatchTerminalData(raw: unknown): StoryboardAudioBatchResultData | null {
  if (raw == null) return null
  let o: Record<string, unknown>
  if (typeof raw === 'string') {
    const s = raw.trim()
    if (!s) return null
    try {
      o = JSON.parse(s) as Record<string, unknown>
    } catch {
      return null
    }
  } else if (typeof raw === 'object' && !Array.isArray(raw)) {
    o = raw as Record<string, unknown>
  } else {
    return null
  }
  const num = (v: unknown) => {
    const n = Number(v)
    return Number.isFinite(n) ? n : undefined
  }
  const items = Array.isArray(o.items) ? (o.items as StoryboardAudioBatchResultItem[]) : undefined
  return {
    totalCount: num(o.totalCount),
    successCount: num(o.successCount),
    failCount: num(o.failCount),
    items
  }
}

export type StoryboardAudioBatchProgress = {
  message?: string
  percent?: number
  stepTitle?: string
  stepIndex?: number
  stepTotal?: number
  taskId?: number
}

export type StoryboardAudioBatchFollowResult =
  | { ok: true; taskId: number; partial?: boolean; data?: StoryboardAudioBatchResultData | null }
  | { ok: false; errorMessage: string }

export type AudioBatchRestoreSession = {
  taskId: number
  storyboardIds: number[]
  panelIndices: number[]
}

export function readAudioBatchRestoreSession(
  creationStore: ReturnType<typeof useCreationStore>,
  sessionScope?: ModalGenSessionScope | null
): AudioBatchRestoreSession | null {
  const raw = readScopedSessionItem(
    AUDIO_BATCH_RESTORE_SESSION_KEY,
    sessionScope ?? modalGenSessionScopeFromStore(creationStore)
  )
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as AudioBatchRestoreSession
    const taskId = parseTaskId(parsed?.taskId)
    if (!taskId) return null
    const storyboardIds = (Array.isArray(parsed.storyboardIds) ? parsed.storyboardIds : [])
      .map((id) => Number(id))
      .filter((id) => Number.isFinite(id) && id > 0)
    const panelIndices = (Array.isArray(parsed.panelIndices) ? parsed.panelIndices : [])
      .map((i) => Number(i))
      .filter((i) => Number.isFinite(i) && i >= 0)
    return { taskId, storyboardIds, panelIndices }
  } catch {
    return null
  }
}

export function writeAudioBatchRestoreSession(
  creationStore: ReturnType<typeof useCreationStore>,
  payload: AudioBatchRestoreSession,
  sessionScope?: ModalGenSessionScope | null
) {
  writeScopedSessionItem(
    AUDIO_BATCH_RESTORE_SESSION_KEY,
    JSON.stringify(payload),
    sessionScope ?? modalGenSessionScopeFromStore(creationStore)
  )
}

export function clearAudioBatchRestoreSession(
  creationStore: ReturnType<typeof useCreationStore>,
  sessionScope?: ModalGenSessionScope | null
) {
  removeScopedSessionItem(
    AUDIO_BATCH_RESTORE_SESSION_KEY,
    sessionScope ?? modalGenSessionScopeFromStore(creationStore)
  )
}

export async function followStoryboardAudioBatchTask(payload: {
  taskId: number
  onProgress?: (p: StoryboardAudioBatchProgress) => void
}): Promise<StoryboardAudioBatchFollowResult> {
  const taskId = Number(payload.taskId)
  const { onProgress } = payload
  if (!Number.isFinite(taskId) || taskId <= 0) {
    return { ok: false, errorMessage: '任务ID无效' }
  }

  try {
    const terminal = await waitUserTaskSseTerminal({
      taskId,
      timeoutMs: TASK_SSE_TIMEOUT_MS,
      onProgress: (p) => {
        onProgress?.({
          taskId,
          ...p,
          percent: p.percent ?? (typeof p.progress === 'number' ? p.progress : undefined)
        })
      }
    })

    if (terminal.kind === 'superseded') {
      return { ok: false, errorMessage: 'Task SSE superseded' }
    }

    if (terminal.kind === 'timeout') {
      const detail = await fetchUserTaskDetailOnce(taskId)
      const st = normalizeTaskStatus(detail?.status)
      if (st === 'SUCCEEDED') {
        return {
          ok: true,
          taskId,
          data: parseAudioBatchTerminalData(detail?.resultData)
        }
      }
      if (st === 'PARTIAL_FAILED') {
        return {
          ok: true,
          taskId,
          partial: true,
          data: parseAudioBatchTerminalData(detail?.resultData)
        }
      }
      if (isOngoingUserTaskStatus(st)) {
        return { ok: false, errorMessage: TASK_BACKGROUND_RUNNING_MESSAGE }
      }
      if (st === 'CANCELLED') return { ok: false, errorMessage: '任务已取消' }
      if (st === 'FAILED') {
        const msg = String(detail?.errorMessage || '配音生成失败')
        openRechargeModalFromInsufficientBalance(msg)
        return { ok: false, errorMessage: msg }
      }
      return { ok: false, errorMessage: '配音生成超时，请稍后在生成记录中查看' }
    }

    const r = terminal.event
    let terminalData: StoryboardAudioBatchResultData | null = null
    if (r.type === 'complete' || r.type === 'partial_failed') {
      terminalData = parseAudioBatchTerminalData(r.data)
    }

    if (r.type === 'complete') {
      return { ok: true, taskId, data: terminalData }
    }
    if (r.type === 'partial_failed') {
      return { ok: true, taskId, partial: true, data: terminalData }
    }

    const detail = await fetchUserTaskDetailOnce(taskId)
    const st = normalizeTaskStatus(detail?.status)
    if (st === 'SUCCEEDED') {
      return {
        ok: true,
        taskId,
        data: parseAudioBatchTerminalData(detail?.resultData) ?? terminalData
      }
    }
    if (st === 'PARTIAL_FAILED') {
      return {
        ok: true,
        taskId,
        partial: true,
        data: parseAudioBatchTerminalData(detail?.resultData) ?? terminalData
      }
    }
    // SSE 业务 error/cancelled 优先于滞后的 PROCESSING detail，避免永久保活 loading
    if (isOngoingUserTaskStatus(st) && !shouldPreferSseBusinessTerminalOverOngoingDetail(r)) {
      return { ok: false, errorMessage: TASK_BACKGROUND_RUNNING_MESSAGE }
    }

    if (r.type === 'error') {
      const msg = r.errorMessage || String(detail?.errorMessage || '配音生成失败')
      openRechargeModalFromInsufficientBalance(msg)
      return { ok: false, errorMessage: msg }
    }
    if (r.type === 'cancelled') {
      return { ok: false, errorMessage: r.message || '任务已取消' }
    }

    return { ok: false, errorMessage: '批量配音未完成' }
  } catch (e: unknown) {
    const msg = String((e as Error)?.message || '批量配音任务异常')
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }
}

export async function runStoryboardAudioBatchTask(payload: {
  body: StoryboardAudioBatchRequest
  onProgress?: (p: StoryboardAudioBatchProgress) => void
  onSubmitted?: (p: { taskId: number; totalCount?: number }) => void
}): Promise<StoryboardAudioBatchFollowResult> {
  const { body, onProgress, onSubmitted } = payload
  let submitted: { taskId?: number; status?: string; totalCount?: number }
  try {
    submitted = await userStoryboardGenerateAudioBatch(body)
  } catch (e: unknown) {
    const msg = bizErr(e)
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }

  const taskId = parseTaskId(submitted?.taskId)
  if (!taskId) {
    return { ok: false, errorMessage: '提交失败：未返回任务ID' }
  }

  onSubmitted?.({ taskId, totalCount: submitted.totalCount })

  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
  }

  const submitStatus = normalizeTaskStatus(submitted.status)
  if (submitStatus === 'SUCCEEDED') {
    const detail = await fetchUserTaskDetailOnce(taskId)
    return {
      ok: true,
      taskId,
      data: parseAudioBatchTerminalData(detail?.resultData)
    }
  }
  if (submitStatus === 'PARTIAL_FAILED') {
    const detail = await fetchUserTaskDetailOnce(taskId)
    return {
      ok: true,
      taskId,
      partial: true,
      data: parseAudioBatchTerminalData(detail?.resultData)
    }
  }
  if (submitStatus === 'FAILED') {
    return { ok: false, errorMessage: '批量配音失败' }
  }

  onProgress?.({ taskId, message: '批量配音任务已提交…', stepTitle: '批量配音任务已提交…' })
  return followStoryboardAudioBatchTask({ taskId, onProgress })
}

function buildStoryboardIdToIndexMap(scriptPanels: StoryboardPanel[]): Map<number, number> {
  const map = new Map<number, number>()
  scriptPanels.forEach((panel, index) => {
    const sid = parseServerStoryboardId(panel.id)
    if (sid != null) map.set(sid, index)
  })
  return map
}

export function applyAudioBatchResultToPanels(
  panels: DubbingPanel[],
  scriptPanels: StoryboardPanel[],
  data: StoryboardAudioBatchResultData | null | undefined,
  fallbackVoiceName: string
): DubbingPanel[] {
  const items = data?.items ?? []
  if (!items.length) return panels
  const idToIndex = buildStoryboardIdToIndexMap(scriptPanels)
  const next = [...panels]

  for (const item of items) {
    const sid = Number(item.storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) continue
    const index = idToIndex.get(sid)
    if (index == null || index < 0 || index >= next.length) continue
    const status = String(item.status || '')
      .trim()
      .toUpperCase()
    if (status !== 'SUCCEEDED') continue
    const videoUrl = String(item.dubbedVideoUrl || '').trim()
    if (!videoUrl) continue

    const panel = next[index]!
    const script = scriptPanels[index]?.scriptContent?.trim() || panel.dialogue?.trim() || ''
    const speakerRole = formatStoryboardSpeakerRoles(item.speakerRoles)
    const voiceName = panel.dubbingVoiceName || fallbackVoiceName || '无音色'
    const emotion = panel.dubbingEmotion || '中性'
    const itemId = `batch-${item.dubbedVideoRecordId ?? Date.now()}-${index}`
    const newItem = {
      id: itemId,
      url: videoUrl,
      title: `配音合成 | ${voiceName} ${emotion} ${new Date().toLocaleString('sv-SE').replace(' ', ' ')}`,
      dialogue: script,
      voiceName,
      emotion
    }
    const prevHistory = panel.dubbingGenHistory || []
    next[index] = {
      ...panel,
      dialogue: script || panel.dialogue,
      speakerRole: speakerRole !== '暂无' ? speakerRole : panel.speakerRole,
      dubbingLipSyncVideoUrl: videoUrl,
      dubbingLipSyncKey: itemId,
      dubbingGenHistory: [...prevHistory, newItem],
      status: 'done',
      storyboardDubbingConfirmed: true
    }
  }

  return next
}

export function pickOngoingAudioBatchTask(
  tasks: UserTaskRow[],
  preferredTaskId?: number | null
): UserTaskRow | null {
  const ongoing = tasks
    .filter(
      (t) => t && isStoryboardAudioBatchTaskType(t.taskType) && isOngoingUserTaskStatus(t.status)
    )
    .sort((a, b) => Number(b.id || 0) - Number(a.id || 0))
  if (!ongoing.length) return null
  const pref = parseTaskId(preferredTaskId)
  if (pref != null) {
    const hit = ongoing.find((t) => Number(t.id) === pref)
    if (hit) return hit
  }
  return ongoing[0] ?? null
}
