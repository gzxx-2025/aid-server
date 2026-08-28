/**
 * 弹窗顶部 Tab SSE 互斥纯规则。
 * 组件负责真正 suspend/restore；本模块只回答「该断谁 / 能否建连 / restore 走哪条路」。
 */

export type ModalTabFollowRef = {
  tabKey: string
  taskId: number
}

export type ModalTabTerminalKind = 'terminal' | 'ongoing' | 'unknown'

export type ModalTabRestoreAction = 'settle' | 'reconnect' | 'retry-detail'

export type ModalTabActivationResult = 'completed' | 'superseded'

export type ModalTabSkeletonController = {
  start: () => void
  clear: () => void
}

export type ModalTaskOwnerRecord = {
  editorScopeKey?: unknown
  taskId?: unknown
} | null | undefined

export type ModalTaskOwnerCleanupDecision = {
  clearSession: boolean
  clearSnapshot: boolean
  canClearUi: boolean
}

function normTabKey(key: unknown): string {
  return String(key ?? '').trim()
}

function normTaskId(taskId: unknown): number | null {
  const n = Number(taskId)
  if (!Number.isFinite(n) || n <= 0) return null
  return Math.floor(n)
}

/** 当前可见 Tab 之外、仍占浏览器 SSE 的 taskId（应 suspend） */
export function listModalTabFollowsToSuspend(input: {
  currentTabKey: string
  activeFollows: ModalTabFollowRef[]
}): number[] {
  const current = normTabKey(input.currentTabKey)
  const out: number[] = []
  const seen = new Set<number>()
  for (const f of input.activeFollows || []) {
    const tid = normTaskId(f?.taskId)
    if (tid == null || seen.has(tid)) continue
    const key = normTabKey(f?.tabKey)
    if (current && key === current) continue
    seen.add(tid)
    out.push(tid)
  }
  return out
}

/** 仅当 target 就是当前可见 Tab 时允许建连 */
export function shouldAllowModalTabSseConnect(input: {
  currentTabKey: string
  targetTabKey: string
}): boolean {
  const current = normTabKey(input.currentTabKey)
  const target = normTabKey(input.targetTabKey)
  if (!current || !target) return false
  return current === target
}

/**
 * 切回 Tab / 打开弹窗 restore 时的动作：
 * - terminal：结算清 loading，禁止建空 SSE
 * - ongoing：重连
 * - unknown：有限次再查 detail，禁止长期假 loading
 */
export function decideModalTabRestoreAction(input: {
  terminalKind: ModalTabTerminalKind
}): ModalTabRestoreAction {
  if (input.terminalKind === 'terminal') return 'settle'
  if (input.terminalKind === 'ongoing') return 'reconnect'
  return 'retry-detail'
}

/**
 * 已知任务完成时只允许删除自己的 session / Pinia snapshot。
 * 不同 Tab 的新任务不影响旧 Tab 收尾；同一 editorScope 的新 taskId 则拥有 loading/UI，旧任务不得清理。
 */
export function decideModalTaskOwnerCleanup(input: {
  expectedEditorScopeKey: string
  expectedTaskId?: number | null
  currentSession?: ModalTaskOwnerRecord
  currentSnapshot?: ModalTaskOwnerRecord
}): ModalTaskOwnerCleanupDecision {
  const expectedScope = normTabKey(input.expectedEditorScopeKey)
  const expectedTaskId = normTaskId(input.expectedTaskId)

  const classify = (record: ModalTaskOwnerRecord) => {
    if (!record || normTabKey(record.editorScopeKey) !== expectedScope) {
      return { owned: false, conflicting: false }
    }
    const owned = expectedTaskId != null && normTaskId(record.taskId) === expectedTaskId
    return { owned, conflicting: !owned }
  }

  if (!expectedScope) {
    return { clearSession: false, clearSnapshot: false, canClearUi: false }
  }

  const session = classify(input.currentSession)
  const snapshot = classify(input.currentSnapshot)
  return {
    clearSession: session.owned,
    clearSnapshot: snapshot.owned,
    canClearUi: !session.conflicting && !snapshot.conflicting
  }
}

/**
 * 顶部 Tab 激活的唯一恢复顺序。每个异步边界后校验 ownership，旧 Tab 只允许请求结束，
 * 禁止继续刷新当前列表、恢复 SSE 或回写新 Tab。
 */
export async function runModalTabActivation(input: {
  ensureLoadingState: () => void | Promise<void>
  refreshImages: () => void | Promise<void>
  primeLoadingUi: () => void
  restoreTask: () => void | Promise<void>
  isCurrent: () => boolean
}): Promise<ModalTabActivationResult> {
  await input.ensureLoadingState()
  if (!input.isCurrent()) return 'superseded'

  await input.refreshImages()
  if (!input.isCurrent()) return 'superseded'

  input.primeLoadingUi()
  if (!input.isCurrent()) return 'superseded'

  await input.restoreTask()
  return input.isCurrent() ? 'completed' : 'superseded'
}

/**
 * Tab 骨架屏的计时所有权：旧 Tab 的 timer 不得释放新 Tab 的 loading，clear 则立即释放。
 */
export function createModalTabSkeletonController(
  setLoading: (loading: boolean) => void,
  delayMs: number
): ModalTabSkeletonController {
  let generation = 0
  return {
    start: () => {
      const owner = ++generation
      setLoading(true)
      setTimeout(() => {
        if (owner === generation) setLoading(false)
      }, delayMs)
    },
    clear: () => {
      generation++
      setLoading(false)
    }
  }
}

/**
 * 一次 Tab 激活内的 detail loader：同 taskId 成功返回后在该激活内复用，不同 taskId 仍独立请求。
 * 失败不保留，使 fallback 的后续匹配路径仍可重试。
 */
export function createModalActivationTaskDetailLoader<T>(
  load: (taskId: number) => Promise<T>
): (taskId: number) => Promise<T> {
  const loadedByTaskId = new Map<number, Promise<T>>()
  return (taskId) => {
    const id = Number(taskId)
    const existing = loadedByTaskId.get(id)
    if (existing) return existing
    const pending = load(id).catch((error) => {
      if (loadedByTaskId.get(id) === pending) loadedByTaskId.delete(id)
      throw error
    })
    loadedByTaskId.set(id, pending)
    return pending
  }
}
