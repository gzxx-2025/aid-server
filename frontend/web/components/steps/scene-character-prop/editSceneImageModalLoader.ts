'use client'

import { lazy } from 'react'

/**
 * 重型编辑弹窗按需加载，避免首次进入素材准备页时拉取上百个模块。
 * 原 createPreloadableAsyncComponent(() => import('./EditSceneImageModal.vue'), AsyncModalLoading)
 * 的 React 版：lazy + 手动 preload（Suspense fallback 由 ScpModalsView 提供 AsyncModalLoading）。
 */
const importEditSceneImageModal = () => import('../EditSceneImageModal')

let preloadPromise: Promise<unknown> | null = null

export function preloadEditSceneImageModal(): Promise<unknown> {
  if (!preloadPromise) {
    preloadPromise = importEditSceneImageModal().catch(() => {
      // 失败允许下次重试（网络抖动时不永久卡死 preload）
      preloadPromise = null
    })
  }
  return preloadPromise ?? Promise.resolve()
}

export const EditSceneImageModalLazy = lazy(() =>
  importEditSceneImageModal().then((m) => ({ default: m.EditSceneImageModal }))
)

/** 原 preloadComponentWhenIdle：空闲时预热重型弹窗 chunk；返回取消函数 */
export function preloadEditSceneImageModalWhenIdle(): () => void {
  if (typeof window === 'undefined') return () => {}
  const w = window as Window & {
    requestIdleCallback?: (cb: () => void, opts?: { timeout?: number }) => number
    cancelIdleCallback?: (id: number) => void
  }
  if (typeof w.requestIdleCallback === 'function') {
    const id = w.requestIdleCallback(() => void preloadEditSceneImageModal(), { timeout: 3000 })
    return () => w.cancelIdleCallback?.(id)
  }
  const timer = setTimeout(() => void preloadEditSceneImageModal(), 1500)
  return () => clearTimeout(timer)
}
