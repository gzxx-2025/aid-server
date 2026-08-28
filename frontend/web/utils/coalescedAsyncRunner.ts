export type CoalescedAsyncRunner = {
  request: () => Promise<void>
  dispose: () => void
  isRunning: () => boolean
}

/**
 * 合并式异步执行器：任意时刻只有一个 owner；执行期间的多次 request 合并为下一轮。
 * owner 在自身 Promise 结算前释放，因此不存在「已结束但仍显示 in-flight」的丢信号窗口。
 */
export function createCoalescedAsyncRunner(
  runOnce: () => void | Promise<void>
): CoalescedAsyncRunner {
  let requested = false
  let disposed = false
  let owner: Promise<void> | null = null

  const drain = async () => {
    try {
      while (requested && !disposed) {
        requested = false
        await runOnce()
      }
    } finally {
      // 与最后一次 requested 检查处于同一 async 临界区：不存在 drain 已退出但
      // owner 仍非空、导致新 request 只置位却无人继续消费的微任务窗口。
      owner = null
      if (requested && !disposed) void request()
    }
  }

  const request = (): Promise<void> => {
    if (disposed) return Promise.resolve()
    requested = true
    if (!owner) {
      const pending = Promise.resolve().then(drain)
      owner = pending
    }
    return owner
  }

  return {
    request,
    dispose: () => {
      disposed = true
      requested = false
    },
    isRunning: () => owner != null
  }
}
