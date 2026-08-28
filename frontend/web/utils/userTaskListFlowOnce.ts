import type { UserTaskRow } from '~/types/business-api'
import { invalidateUserTaskListCache,userTaskListRecentPage } from '~/utils/businessApi'

export const FLOW_USER_TASK_LIST_READY_EVENT = 'create-flow-user-task-list-ready'

export type FlowUserTaskListReadyDetail = {
  projectId: number
  rows: UserTaskRow[]
}

/**
 * list 调度意图（创作流程内唯一入口）：
 * - read：步骤 restore / 角标读，复用缓存或 inflight
 * - bootstrap：进入作品时权威预拉（无缓存才打网，并发合并）
 * - mutate：提交/终态后需刷新角标（可清 burst；quiet window 内只记脏）
 */
export type FlowUserTaskListIntent = 'read' | 'bootstrap' | 'mutate'

type FlowTaskListRequest = {
  owner: symbol
  /** mutate 请求可顶替非 mutate 的 inflight */
  isMutate: boolean
  promise: Promise<UserTaskRow[]>
}

type QuietWindowState = {
  depth: number
  dirty: boolean
}

const inflightByProjectId = new Map<number, FlowTaskListRequest>()
const sessionCache = new Map<number, UserTaskRow[]>()
const quietWindowByProjectId = new Map<number, QuietWindowState>()

const refreshDebounceTimers = new Map<number, ReturnType<typeof setTimeout>>()

function isValidProjectId(projectId: number): boolean {
  return Number.isFinite(projectId) && projectId > 0
}

/**
 * 剧集隔离：任务列表接口按 projectId 返回同作品全部集的任务。
 * 恢复/续跟/角标场景必须先过滤：明确归属其它集（episodeId > 0 且 ≠ 当前集）的行剔除；
 * episodeId 缺失或 ≤ 0（项目级/历史任务）保留。currentEpisodeId 无效（null/0）时不过滤。
 */
export function filterUserTaskRowsForEpisode(
  rows: UserTaskRow[],
  currentEpisodeId: number | null | undefined
): UserTaskRow[] {
  const currentEp = Number(currentEpisodeId)
  if (!Number.isFinite(currentEp) || currentEp <= 0) return rows
  return rows.filter((row) => {
    const rowEp = Number(row?.episodeId)
    if (!Number.isFinite(rowEp) || rowEp <= 0) return true
    return rowEp === currentEp
  })
}

export function getCachedFlowUserTaskList(projectId: number): UserTaskRow[] | null {
  if (!isValidProjectId(projectId)) return null
  const rows = sessionCache.get(projectId)
  return rows ? [...rows] : null
}

export function hasFlowUserTaskListCache(projectId: number): boolean {
  return isValidProjectId(projectId) && sessionCache.has(projectId)
}

export function dispatchFlowUserTaskListReady(projectId: number, rows: UserTaskRow[]): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(
    new CustomEvent<FlowUserTaskListReadyDetail>(FLOW_USER_TASK_LIST_READY_EVENT, {
      detail: { projectId, rows }
    })
  )
}

export function isFlowTaskListQuietWindowActive(projectId: number): boolean {
  const pid = Number(projectId)
  if (!isValidProjectId(pid)) return false
  return (quietWindowByProjectId.get(pid)?.depth ?? 0) > 0
}

function markFlowTaskListDirty(projectId: number): void {
  const pid = Number(projectId)
  if (!isValidProjectId(pid)) return
  const state = quietWindowByProjectId.get(pid)
  if (!state) {
    quietWindowByProjectId.set(pid, { depth: 0, dirty: true })
    return
  }
  state.dirty = true
}

/**
 * 恢复/bootstrap 窗口：期间 mutate / schedule 只记脏不发网，结束时最多补一次 mutate。
 * 支持嵌套（壳层 + 步骤 restore）。
 */
export function beginFlowTaskListQuietWindow(projectId: number): void {
  const pid = Number(projectId)
  if (!isValidProjectId(pid)) return
  const existing = quietWindowByProjectId.get(pid)
  if (existing) {
    existing.depth += 1
    return
  }
  quietWindowByProjectId.set(pid, { depth: 1, dirty: false })
}

/**
 * 结束 quiet window；depth 归零且 dirty 时补一次 mutate 刷新角标。
 * 不关闭、不打断任何 SSE follow。
 */
export function endFlowTaskListQuietWindow(projectId: number): void {
  const pid = Number(projectId)
  if (!isValidProjectId(pid)) return
  const state = quietWindowByProjectId.get(pid)
  if (!state) return
  state.depth = Math.max(0, state.depth - 1)
  if (state.depth > 0) return
  const shouldMutate = state.dirty
  quietWindowByProjectId.delete(pid)
  if (shouldMutate) {
    void fetchFlowUserTaskList(pid, { intent: 'mutate' })
  }
}

function resolveCachedOrInflight(pid: number): Promise<UserTaskRow[]> | null {
  const cached = sessionCache.get(pid)
  if (cached) return Promise.resolve([...cached])
  const inflight = inflightByProjectId.get(pid)
  if (inflight) return inflight.promise
  return null
}

function startNetworkFetch(pid: number, isMutate: boolean): Promise<UserTaskRow[]> {
  if (isMutate) {
    /** 必须清掉 businessApi 层 3s burst，否则 mutate 仍可能打到旧「进行中」行 */
    invalidateUserTaskListCache()
  }

  const owner = Symbol('flow-task-list-request')
  const promise = userTaskListRecentPage({ projectId: pid })
    .then((rows) => {
      if (inflightByProjectId.get(pid)?.owner === owner) {
        sessionCache.set(pid, rows)
        dispatchFlowUserTaskListReady(pid, rows)
      }
      return rows
    })
    .finally(() => {
      if (inflightByProjectId.get(pid)?.owner === owner) {
        inflightByProjectId.delete(pid)
      }
    })

  inflightByProjectId.set(pid, { owner, isMutate, promise })
  return promise
}

/**
 * 创作流程内 task/list 唯一调度入口。
 * SSE 续跟请用返回的 rows + Pinia taskId；本函数不管理 SSE 连接。
 */
export function fetchFlowUserTaskList(
  projectId: number,
  options?: { intent?: FlowUserTaskListIntent }
): Promise<UserTaskRow[]> {
  const pid = Number(projectId)
  if (!isValidProjectId(pid)) return Promise.resolve([])

  const intent: FlowUserTaskListIntent = options?.intent ?? 'read'

  if (intent === 'read' || intent === 'bootstrap') {
    const hit = resolveCachedOrInflight(pid)
    if (hit) return hit
    return startNetworkFetch(pid, false)
  }

  /** mutate */
  if (isFlowTaskListQuietWindowActive(pid)) {
    markFlowTaskListDirty(pid)
    const hit = resolveCachedOrInflight(pid)
    if (hit) return hit
    return Promise.resolve([])
  }

  const inflight = inflightByProjectId.get(pid)
  if (inflight?.isMutate) return inflight.promise
  return startNetworkFetch(pid, true)
}

/**
 * @deprecated 请优先用 {@link fetchFlowUserTaskList} 的 intent。
 * `force: true` 等价于 `intent: 'mutate'`；否则等价于 `intent: 'read'`。
 */
export function fetchFlowUserTaskListOnce(
  projectId: number,
  options?: { force?: boolean }
): Promise<UserTaskRow[]> {
  return fetchFlowUserTaskList(projectId, {
    intent: options?.force === true ? 'mutate' : 'read'
  })
}

/** SSE 完成 / 任务状态变更：合并防抖后最多刷新一次 list；quiet window 内只记脏 */
export function scheduleFlowUserTaskListRefresh(
  projectId: number,
  options?: { force?: boolean; debounceMs?: number }
): void {
  const pid = Number(projectId)
  if (!isValidProjectId(pid)) return

  /** 兼容旧调用：force 默认 true → mutate；force:false 走 read（无网络副作用） */
  const intent: FlowUserTaskListIntent = options?.force === false ? 'read' : 'mutate'

  if (intent === 'read') {
    void fetchFlowUserTaskList(pid, { intent: 'read' })
    return
  }

  if (isFlowTaskListQuietWindowActive(pid)) {
    markFlowTaskListDirty(pid)
    return
  }

  const existingTimer = refreshDebounceTimers.get(pid)
  if (existingTimer) clearTimeout(existingTimer)
  /** 并行多任务终态错峰完成时，拉长合并窗口，避免 N 次 list */
  const debounceMs = options?.debounceMs ?? 900

  const timer = setTimeout(() => {
    if (refreshDebounceTimers.get(pid) !== timer) return
    refreshDebounceTimers.delete(pid)
    if (isFlowTaskListQuietWindowActive(pid)) {
      markFlowTaskListDirty(pid)
      return
    }
    void fetchFlowUserTaskList(pid, { intent: 'mutate' })
  }, debounceMs)
  refreshDebounceTimers.set(pid, timer)
}

export function invalidateFlowUserTaskListCache(projectId?: number): void {
  if (projectId == null) {
    for (const timer of refreshDebounceTimers.values()) clearTimeout(timer)
    refreshDebounceTimers.clear()
    inflightByProjectId.clear()
    sessionCache.clear()
    quietWindowByProjectId.clear()
    return
  }
  const pid = Number(projectId)
  const timer = refreshDebounceTimers.get(pid)
  if (timer) clearTimeout(timer)
  refreshDebounceTimers.delete(pid)
  inflightByProjectId.delete(pid)
  sessionCache.delete(pid)
  quietWindowByProjectId.delete(pid)
}
