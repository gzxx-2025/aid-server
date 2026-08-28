export type AsyncIdleBarrier = {
  notifyStateChange: () => void
  waitForIdle: () => Promise<void>
}

/**
 * Event-driven idle barrier for an async owner group.
 *
 * Call `notifyStateChange` whenever an owner releases. Waiters are resolved only
 * after the whole group is idle, so lifecycle handoff never needs polling or a
 * retry timer.
 */
export function createAsyncIdleBarrier(isBusy: () => boolean): AsyncIdleBarrier {
  const waiters = new Set<() => void>()

  const notifyStateChange = () => {
    if (isBusy()) return
    const pending = [...waiters]
    waiters.clear()
    for (const resolve of pending) resolve()
  }

  const waitForIdle = async () => {
    while (isBusy()) {
      await new Promise<void>((resolve) => {
        waiters.add(resolve)
        // Re-check after registration so an owner release at the handoff edge
        // cannot leave this waiter behind.
        if (!isBusy() && waiters.delete(resolve)) resolve()
      })
    }
  }

  return { notifyStateChange, waitForIdle }
}
