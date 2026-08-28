/**
 * 创作流程页 NuxtPage `out-in` 过渡的导航串行化与崩溃识别。
 * 并发 router.push/replace（含仅改 query 触发的二次 remount）会打断 Transition，
 * 触发 null.type / null.nextSibling 并留下永久空白内容区。
 */

export function isCreateFlowNavPath(fullPath: string): boolean {
  const path = (fullPath.split('?')[0].split('#')[0] || '/').replace(/\/$/, '') || '/'
  return path === '/create' || path.startsWith('/create/')
}

export function shouldDeferCreateFlowNavigation(options: {
  toPath: string
  fromPath: string
  toFullPath: string
  fromFullPath: string
  busy: boolean
}): boolean {
  if (!options.busy) return false
  if (!isCreateFlowNavPath(options.toPath) || !isCreateFlowNavPath(options.fromPath)) return false
  if (options.toFullPath === options.fromFullPath) return false
  return true
}

/**
 * 流程内 page-key：按 path + 作品/剧集 区分实例。
 * - 步骤切换（path 变）仍走 out-in 动画
 * - 清理 stepInitAdvance 等无关 query 不再二次 remount
 * - 同步骤切作品/剧集仍会重挂，避免本地态串号
 */
export function createFlowPageKey(route: {
  path: string
  fullPath: string
  query?: Record<string, unknown>
}): string {
  if (!isCreateFlowNavPath(route.path)) return route.fullPath
  const q = route.query ?? {}
  const projectId = String(q.projectId ?? q.id ?? q.workId ?? '')
  const episodeId = String(q.episodeId ?? '')
  return `${route.path}__p=${projectId}__e=${episodeId}`
}

export function shouldSkipCreateFlowSyncRoute(options: {
  currentPath: string
  targetPath: string
}): boolean {
  return options.currentPath === options.targetPath
}

export function isCreateFlowTransitionCrashError(error: unknown): boolean {
  if (error == null) return false
  const message =
    typeof error === 'string'
      ? error
      : error instanceof Error
        ? error.message
        : typeof error === 'object' &&
            error &&
            'message' in error &&
            typeof (error as { message: unknown }).message === 'string'
          ? (error as { message: string }).message
          : String(error)
  if (!message.includes('Cannot read properties of null')) return false
  return (
    message.includes("'type'") ||
    message.includes("'nextSibling'") ||
    message.includes("'exposed'") ||
    message.includes("'parentNode'")
  )
}

/** 略长于 create-step-route（180ms）+ chunk，仅作死锁兜底 */
export const CREATE_FLOW_NAV_GATE_TIMEOUT_MS = 2200

type GateWaiter = () => void

let gateActive = false
let gateQueue: GateWaiter[] = []
let gateTimer: ReturnType<typeof setTimeout> | null = null

function clearGateTimer() {
  if (!gateTimer) return
  clearTimeout(gateTimer)
  gateTimer = null
}

function armGateTimer() {
  clearGateTimer()
  gateTimer = setTimeout(() => {
    endCreateFlowNavTransition()
  }, CREATE_FLOW_NAV_GATE_TIMEOUT_MS)
}

export function isCreateFlowNavGateBusy(): boolean {
  return gateActive || gateQueue.length > 0
}

/**
 * 单飞队列：同一时刻只允许一次 create 步骤 path 导航进入 Transition。
 * 在 beforeEach（path 变化）调用；在 onAfterEnter / 失败兜底调用 end。
 */
export function beginCreateFlowNavTransition(): Promise<void> {
  return new Promise((resolve) => {
    if (!gateActive) {
      gateActive = true
      armGateTimer()
      resolve()
      return
    }
    gateQueue.push(resolve)
  })
}

export function endCreateFlowNavTransition(): void {
  clearGateTimer()
  const next = gateQueue.shift()
  if (next) {
    armGateTimer()
    next()
    return
  }
  gateActive = false
}

/** 离开创作壳或崩溃恢复时清空门闩，避免 onAfterEnter 未触发导致永久排队 */
export function flushCreateFlowNavGate(): void {
  clearGateTimer()
  gateActive = false
  const waiters = gateQueue
  gateQueue = []
  for (const waiter of waiters) waiter()
}

/** 测试用：重置门闩 */
export function resetCreateFlowNavGateForTest(): void {
  flushCreateFlowNavGate()
}
