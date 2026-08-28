export type LiveStateListener = () => void

export interface LiveStateStore<T> {
  getSnapshot: () => T
  set: (value: T) => void
  subscribe: (listener: LiveStateListener) => () => void
}

function resolveInitialValue<T>(initial: T | (() => T)): T {
  return typeof initial === 'function' ? (initial as () => T)() : initial
}

/** 创建带同步快照读取能力的最小外部状态源。 */
export function createLiveStateStore<T>(initial: T | (() => T)): LiveStateStore<T> {
  let current = resolveInitialValue(initial)
  const listeners = new Set<LiveStateListener>()

  return {
    getSnapshot: () => current,
    set: (value) => {
      if (Object.is(current, value)) return
      current = value
      listeners.forEach((listener) => listener())
    },
    subscribe: (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    }
  }
}
