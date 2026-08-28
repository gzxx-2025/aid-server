import { describe,expect,it,vi } from 'vitest'
import { createLiveStateStore } from './liveStateStore'
describe('createLiveStateStore', () => {
  it('keeps synchronous reads and subscriber notifications aligned', () => {
    const store = createLiveStateStore(1)
    const listener = vi.fn()
    const unsubscribe = store.subscribe(listener)

    store.set(2)

    expect(store.getSnapshot()).toBe(2)
    expect(listener).toHaveBeenCalledTimes(1)

    unsubscribe()
    store.set(3)
    expect(store.getSnapshot()).toBe(3)
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it('does not notify when the snapshot identity is unchanged', () => {
    const value = { id: 1 }
    const store = createLiveStateStore(value)
    const listener = vi.fn()
    store.subscribe(listener)

    store.set(value)

    expect(listener).not.toHaveBeenCalled()
  })

  it('evaluates a lazy initializer once per store', () => {
    const initializer = vi.fn(() => ({ ready: true }))
    const store = createLiveStateStore(initializer)

    expect(store.getSnapshot()).toEqual({ ready: true })
    expect(store.getSnapshot()).toEqual({ ready: true })
    expect(initializer).toHaveBeenCalledTimes(1)
  })
})
