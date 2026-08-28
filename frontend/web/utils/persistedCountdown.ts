/**
 * 基于 localStorage 的验证码发送倒计时（存结束时间戳，刷新后按剩余秒数恢复）。
 */
export function buildCountdownStorageKey(scope: string, target: string): string {
  const t = target.trim().toLowerCase() || '_'
  return `countdown:${scope}:${t}`
}

export function getCountdownRemainingSeconds(storageKey: string): number {
  if (typeof window === 'undefined') return 0
  try {
    const raw = localStorage.getItem(storageKey)
    if (!raw) return 0
    const endAt = Number(raw)
    if (!Number.isFinite(endAt)) {
      localStorage.removeItem(storageKey)
      return 0
    }
    const remaining = Math.ceil((endAt - Date.now()) / 1000)
    if (remaining <= 0) {
      localStorage.removeItem(storageKey)
      return 0
    }
    return remaining
  } catch {
    try {
      localStorage.removeItem(storageKey)
    } catch {
      /* ignore */
    }
    return 0
  }
}

export function setCountdownEndAt(storageKey: string, durationSeconds: number): void {
  if (typeof window === 'undefined') return
  const endAt = Date.now() + durationSeconds * 1000
  localStorage.setItem(storageKey, String(endAt))
}

export function clearCountdown(storageKey: string): void {
  if (typeof window === 'undefined') return
  try {
    localStorage.removeItem(storageKey)
  } catch {
    /* ignore */
  }
}
