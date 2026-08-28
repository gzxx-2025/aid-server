import {
createTaskStream,
taskStreamDoneForRace,
type TaskStreamResult
} from '~/composables/useTaskStream'
import type { UserTaskDetailData } from '~/types/business-api'
import { invalidateUserTaskDetailCache,userTaskDetailCached } from '~/utils/businessApi'
import {
suspendTaskSseFollowSlots,
type TaskSseFollowSlot
} from '~/utils/taskSseFollowRegistry'
import type { TaskSseProgressInput } from '~/utils/taskSseProgressText'
import { isBenignTaskSseDisconnectMessage } from '~/utils/taskSseSilentDisconnect'
import { scheduleUserBalanceRefresh } from '~/utils/userBalanceRefresh'

export type TaskSseTerminalWaitResult =
  | { kind: 'sse'; event: TaskStreamResult }
  | { kind: 'timeout' }
  /** 同 taskId 已被新的 SSE 跟随接管（关弹窗再进 / 双重 restore），旧跟随应静默退出 */
  | { kind: 'superseded' }

export function normalizeTaskStatus(status: unknown): string {
  return String(status ?? '').trim().toUpperCase()
}

export function isTerminalTaskStatus(status: unknown): boolean {
  const st = normalizeTaskStatus(status)
  return st === 'SUCCEEDED' || st === 'FAILED' || st === 'CANCELLED' || st === 'PARTIAL_FAILED'
}

export function isOngoingUserTaskStatus(status: unknown): boolean {
  const s = normalizeTaskStatus(status)
  // 与任务中心 isTaskOngoingStatus 对齐：部分接口用 '0' 表示进行中
  return (
    s === 'PENDING' ||
    s === 'PROCESSING' ||
    s === 'RUNNING' ||
    s === 'QUEUED' ||
    s === 'WAITING' ||
    s === '0'
  )
}

export type UserTaskTerminalResolution =
  | { kind: 'succeeded'; taskId: number; detail: UserTaskDetailData | null }
  | { kind: 'partial_failed'; taskId: number; detail: UserTaskDetailData | null }
  | { kind: 'failed'; taskId: number; errorMessage: string; detail?: UserTaskDetailData | null }
  | { kind: 'cancelled'; taskId: number; message: string; detail?: UserTaskDetailData | null }
  | { kind: 'ongoing'; taskId: number; detail?: UserTaskDetailData | null }

/** 任务是否已在服务端终态（用于避免对已结束任务重连空 SSE） */
export async function isUserTaskTerminal(taskId: number): Promise<boolean> {
  const detail = await userTaskDetailCached(taskId)
  if (!detail) return false
  return isTerminalTaskStatus(detail.status)
}

/** SSE 无事件/断连时补查 task/detail，避免误报失败 */
export async function resolveUserTaskTerminalOutcome(
  taskId: number,
  options?: { force?: boolean }
): Promise<UserTaskTerminalResolution> {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) {
    return { kind: 'failed', taskId: id, errorMessage: '任务ID无效' }
  }
  const detail = options?.force
    ? await fetchUserTaskDetailOnce(id)
    : await userTaskDetailCached(id)
  const st = normalizeTaskStatus(detail?.status ?? '')
  /** 一并返回 detail，调用方解析 chainChildTaskIds 等字段时无需再打一次 task/detail */
  if (st === 'SUCCEEDED') return { kind: 'succeeded', taskId: id, detail }
  if (st === 'PARTIAL_FAILED') return { kind: 'partial_failed', taskId: id, detail }
  if (st === 'CANCELLED') {
    return {
      kind: 'cancelled',
      taskId: id,
      message: String(detail?.errorMessage || '任务已取消'),
      detail
    }
  }
  if (st === 'FAILED') {
    return {
      kind: 'failed',
      taskId: id,
      errorMessage: String(detail?.errorMessage || '任务失败'),
      detail
    }
  }
  return { kind: 'ongoing', taskId: id, detail }
}

/** SSE 结束后最多补查一次 task/detail（不做轮询；强制绕过恢复阶段缓存） */
export async function fetchUserTaskDetailOnce(taskId: number): Promise<UserTaskDetailData | null> {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return null
  const now = Date.now()
  const existing = terminalDetailOnceByTaskId.get(id)
  if (existing && existing.expiresAt > now) return existing.promise

  invalidateUserTaskDetailCache(id)
  const promise = userTaskDetailCached(id, { force: true }).catch((error) => {
    terminalDetailOnceByTaskId.delete(id)
    throw error
  })
  terminalDetailOnceByTaskId.set(id, {
    promise,
    expiresAt: now + TERMINAL_DETAIL_ONCE_TTL_MS
  })
  return promise
}

const TERMINAL_DETAIL_ONCE_TTL_MS = 10_000
const terminalDetailOnceByTaskId = new Map<
  number,
  { promise: Promise<UserTaskDetailData | null>; expiresAt: number }
>()

/** 与后端 SSE 连接最长存活时间对齐（30 分钟） */
export const TASK_SSE_TIMEOUT_MS = 30 * 60 * 1000

const DEFAULT_TASK_SSE_TIMEOUT_MS = TASK_SSE_TIMEOUT_MS

/** 同一 taskId 同时只允许一条 SSE 跟随；新跟随会静默中断旧跟随 */
const activeTaskSseFollowSlots = new Map<number, TaskSseFollowSlot>()

/**
 * 挂起当前创作页持有的全部任务 SSE。
 * 只释放浏览器连接；持久化任务快照保持不动，目标页（或回访本页）可安全恢复。
 */
export function suspendAllTaskSseFollows(): number {
  return suspendTaskSseFollowSlots(activeTaskSseFollowSlots)
}

/** 挂起单个 SSE 跟随，不取消其服务端任务。 */
export function suspendTaskSseFollow(taskId: number): boolean {
  return suspendTaskSseFollowSlots(activeTaskSseFollowSlots, taskId) > 0
}

/**
 * 弹窗 waitUserTaskSseTerminal 槽是否仍存活（未 suspend/superseded）。
 * 外层 startTrackTask 用此避免与弹窗 registry 双连。
 */
export function hasLiveTaskSseFollow(taskId: number): boolean {
  const tid = Number(taskId)
  if (!Number.isFinite(tid) || tid <= 0) return false
  const slot = activeTaskSseFollowSlots.get(tid)
  return !!slot && !slot.superseded
}

function claimTaskSseFollowSlot(taskId: number): TaskSseFollowSlot {
  const prev = activeTaskSseFollowSlots.get(taskId)
  if (prev) {
    prev.superseded = true
    try {
      prev.abort()
    } catch {
      /* ignore */
    }
  }
  const slot: TaskSseFollowSlot = {
    superseded: false,
    abort: () => {
      /* filled after stream created */
    }
  }
  activeTaskSseFollowSlots.set(taskId, slot)
  return slot
}

function releaseTaskSseFollowSlot(taskId: number, slot: TaskSseFollowSlot) {
  if (activeTaskSseFollowSlots.get(taskId) === slot) {
    activeTaskSseFollowSlots.delete(taskId)
  }
}

/**
 * 仅通过 SSE 等待任务终态；不再与 task/detail 轮询竞速。
 * 同 taskId 重复跟随时，旧连接以 {@link TaskSseTerminalWaitResult} `superseded` 结束。
 * SSE 正常结束时由调用方按需 {@link fetchUserTaskDetailOnce} 补查一次详情。
 */
export async function waitUserTaskSseTerminal(payload: {
  taskId: number
  timeoutMs?: number
  onProgress?: (p: TaskSseProgressInput & { percent?: number }) => void
}): Promise<TaskSseTerminalWaitResult> {
  const taskId = Number(payload.taskId)
  const timeoutMs = payload.timeoutMs ?? DEFAULT_TASK_SSE_TIMEOUT_MS
  const { onProgress } = payload

  if (!Number.isFinite(taskId) || taskId <= 0) {
    return { kind: 'timeout' }
  }

  const slot = claimTaskSseFollowSlot(taskId)

  const runOnce = async (): Promise<TaskSseTerminalWaitResult> => {
    if (slot.superseded) return { kind: 'superseded' }

    const stream = createTaskStream(taskId)
    slot.abort = () => {
      try {
        stream.close()
      } catch {
        /* ignore */
      }
    }

    const stopProgress = stream.subscribeProgress((p) => {
      onProgress?.({
        ...p,
        percent: typeof p.progress === 'number' ? p.progress : undefined
      })
    })

    let settled = false
    let timeoutTimer: ReturnType<typeof setTimeout> | null = null
    const cleanup = () => {
      if (settled) return
      settled = true
      if (timeoutTimer) {
        clearTimeout(timeoutTimer)
        timeoutTimer = null
      }
      stopProgress()
      try {
        stream.close()
      } catch {
        /* ignore */
      }
    }

    try {
      const ssePromise = taskStreamDoneForRace(stream).then((event) => ({
        kind: 'sse' as const,
        event
      }))
      const timeoutPromise = new Promise<{ kind: 'timeout' }>((resolve) => {
        timeoutTimer = setTimeout(() => {
          timeoutTimer = null
          resolve({ kind: 'timeout' })
        }, timeoutMs)
      })
      const winner = await Promise.race([ssePromise, timeoutPromise])
      cleanup()
      if (slot.superseded) return { kind: 'superseded' }
      return winner
    } catch {
      cleanup()
      if (slot.superseded) return { kind: 'superseded' }
      return { kind: 'timeout' }
    }
  }

  try {
    // 良性断连且任务仍进行中时自动重连一次（覆盖弹窗重进抢占失败后的空窗）
    for (let attempt = 0; attempt < 2; attempt++) {
      const winner = await runOnce()
      if (winner.kind === 'superseded') return winner
      if (winner.kind === 'timeout') {
        // SSE 超时未推终态时，用 detail 兜底：已结算则补刷积分
        await maybeScheduleBalanceRefreshIfTaskTerminal(taskId)
        return winner
      }

      const ev = winner.event
      const benignError = ev.type === 'error' && isBenignTaskSseDisconnectMessage(ev.errorMessage)
      if (!benignError || attempt >= 1) return winner

      if (slot.superseded) return { kind: 'superseded' }
      const outcome = await resolveUserTaskTerminalOutcome(taskId)
      if (slot.superseded) return { kind: 'superseded' }
      if (outcome.kind !== 'ongoing') {
        // 断连空窗内任务已结算：SSE 未走到业务终态事件，补刷一次
        scheduleUserBalanceRefresh()
        return winner
      }
      // 仍进行中：重连 SSE，不向上抛「Task SSE ended unexpectedly」
    }
    return { kind: 'timeout' }
  } finally {
    releaseTaskSseFollowSlot(taskId, slot)
  }
}

/** timeout / 断连后 detail 已终态时补刷（不覆盖 restore 只读 resolve） */
async function maybeScheduleBalanceRefreshIfTaskTerminal(taskId: number) {
  try {
    const detail = await fetchUserTaskDetailOnce(taskId)
    if (isTerminalTaskStatus(detail?.status)) {
      scheduleUserBalanceRefresh()
    }
  } catch {
    /* ignore */
  }
}

/**
 * follow* 助手：SSE 被抢占，或良性断连且任务仍在进行中 → 调用方应静默退出，勿清状态 / 勿 toast。
 */
export async function shouldDeferModalTaskFollowFailure(
  taskId: number,
  errorMessage: unknown
): Promise<boolean> {
  if (!isBenignTaskSseDisconnectMessage(errorMessage)) return false
  const outcome = await resolveUserTaskTerminalOutcome(taskId)
  return outcome.kind === 'ongoing'
}
