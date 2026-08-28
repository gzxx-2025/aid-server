/** 上拉加载：追加新内容前的最低等待时间（ms） */
export const INFINITE_SCROLL_APPEND_DELAY_MS = 1000

/** 保证从 startedAt 起至少等待 delayMs 后再追加内容 */
export function waitInfiniteScrollAppendDelay(
  startedAt: number,
  delayMs = INFINITE_SCROLL_APPEND_DELAY_MS
): Promise<void> {
  const remaining = Math.max(0, delayMs - (Date.now() - startedAt))
  if (remaining <= 0) return Promise.resolve()
  return new Promise((resolve) => {
    setTimeout(resolve, remaining)
  })
}
