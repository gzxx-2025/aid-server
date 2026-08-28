/**
 * 任务终态后刷新侧栏积分：防抖合并短时多次结算为一次 /api/user/balance。
 */

export const USER_BALANCE_REFRESH_DEBOUNCE_MS = 400

/** resolveUserTaskTerminalOutcome 的终态 kind */
export function shouldRefreshBalanceOnTerminalKind(kind: string): boolean {
  return (
    kind === 'succeeded' ||
    kind === 'partial_failed' ||
    kind === 'failed' ||
    kind === 'cancelled'
  )
}

/** waitUserTaskSseTerminal 返回的 SSE 事件类型（不含良性断连 error） */
export function shouldRefreshBalanceOnSseEventType(type: string): boolean {
  return (
    type === 'complete' ||
    type === 'partial_failed' ||
    type === 'error' ||
    type === 'cancelled'
  )
}

type RefreshFn = () => Promise<unknown>

let debounceTimer: ReturnType<typeof setTimeout> | null = null
let refreshImpl: RefreshFn | null = null

/** 测试用：注入刷新实现；传 null 恢复默认（走 userStore.fetchBalance） */
export function setUserBalanceRefreshImpl(fn: RefreshFn | null) {
  refreshImpl = fn
}

/** 测试用：清掉未触发的防抖定时器 */
export function resetUserBalanceRefreshForTest() {
  if (debounceTimer) {
    clearTimeout(debounceTimer)
    debounceTimer = null
  }
}

async function defaultRefresh(): Promise<unknown> {
  const { useUserStore } = await import('~/stores/user')
  return useUserStore.getState().fetchBalance()
}

/**
 * 调度一次积分刷新（短防抖）。多次终态在窗口内只打一枪。
 */
export function scheduleUserBalanceRefresh(options?: {
  debounceMs?: number
}): void {
  // SSR 不调度；单测可注入 refreshImpl 绕过 client 判断
  if (refreshImpl == null && (typeof window !== 'undefined') === false) return
  if (refreshImpl == null && typeof window === 'undefined') return
  const debounceMs = options?.debounceMs ?? USER_BALANCE_REFRESH_DEBOUNCE_MS
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    debounceTimer = null
    const run = refreshImpl ?? defaultRefresh
    void run().catch(() => {
      /* 静默：积分展示失败不打扰用户 */
    })
  }, debounceMs)
}
