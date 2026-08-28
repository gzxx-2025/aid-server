/**
 * 弹窗 SSE deferred 后续跟策略。
 *
 * 根因防护：旧跟随收到 superseded / 良性断连后若立刻删锁并 restore，
 * 会与仍在进行的新跟随互抢同一 taskId，形成 `/task/stream/:id` 请求风暴。
 */

export function isModalFollowSupersededMessage(errorMessage: unknown): boolean {
  return String(errorMessage ?? '')
    .trim()
    .toLowerCase()
    .includes('superseded')
}

export type DeferredModalFollowHandling =
  | { kind: 'superseded' }
  | { kind: 'restore' }
  | { kind: 'stop' }

const DEFAULT_MAX_RESTORE_ATTEMPTS = 2

/**
 * deferred 结果如何处理：
 * - superseded：已被同 task 新跟随接管 → 禁止删锁 / 禁止 restore / 禁止 endFollow
 * - restore：良性断连且未超次数 → 可释放本路锁后延迟 restore
 * - stop：已达上限 → 停止自动续跟，避免 tight-loop
 */
export function resolveDeferredModalFollowHandling(input: {
  errorMessage?: unknown
  restoreAttemptCount: number
  maxRestoreAttempts?: number
}): DeferredModalFollowHandling {
  if (isModalFollowSupersededMessage(input.errorMessage)) {
    return { kind: 'superseded' }
  }
  const max = input.maxRestoreAttempts ?? DEFAULT_MAX_RESTORE_ATTEMPTS
  const attempts = Number(input.restoreAttemptCount)
  const count = Number.isFinite(attempts) && attempts > 0 ? Math.floor(attempts) : 0
  if (count >= max) return { kind: 'stop' }
  return { kind: 'restore' }
}

/** 指数退避（封顶），避免断连后立刻连环重连 */
export function nextModalSseRestoreDelayMs(input: {
  attemptCount: number
  baseDelayMs?: number
  maxDelayMs?: number
}): number {
  const base = input.baseDelayMs ?? 800
  const max = input.maxDelayMs ?? 5000
  const n = Number(input.attemptCount)
  const attempt = Number.isFinite(n) && n > 0 ? Math.floor(n) : 0
  const delay = base * 2 ** attempt
  return Math.min(max, delay)
}

/** 原子占锁：已存在则失败，杜绝并发 restore 双开 SSE */
export function tryAcquireModalFollowLock(locks: Set<string>, lockKey: string): boolean {
  const key = String(lockKey || '').trim()
  if (!key) return false
  if (locks.has(key)) return false
  locks.add(key)
  return true
}
