'use client'

import { useState,useSyncExternalStore } from 'react'
import { createLiveStateStore } from '~/utils/liveStateStore'
export interface Mirrored<T> {
  /** 当前渲染快照 */
  value: T
  /** 事件回调和异步流程同步读取最新值 */
  get: () => T
  /** 同步更新快照并通知 React 渲染 */
  set: (value: T) => void
}

/**
 * 同步状态适配器：渲染读取不可变快照，异步回调通过 get 读取最新值。
 * 用于迁移期需要“写入后立即可读”的复杂任务流，对外契约保持稳定。
 */
export function useMirrored<T>(initial: T | (() => T)): Mirrored<T> {
  const [store] = useState(() => createLiveStateStore(initial))
  const value = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getSnapshot)
  return { value, get: store.getSnapshot, set: store.set }
}

/** 等待当前调用栈结束，让已排队的 React 更新有机会提交。 */
export function nextTick(callback?: () => void): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(() => {
      callback?.()
      resolve()
    }, 0)
  })
}
