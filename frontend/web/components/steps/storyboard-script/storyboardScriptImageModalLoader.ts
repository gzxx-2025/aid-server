'use client'

import { lazy } from 'react'

const importStoryboardImageModal = () => import('../EditStoryboardImageModal')
let preloadPromise: Promise<unknown> | null = null

export function preloadStoryboardImageModal(): Promise<unknown> {
  if (!preloadPromise) {
    preloadPromise = importStoryboardImageModal().catch(() => {
      preloadPromise = null
    })
  }
  return preloadPromise ?? Promise.resolve()
}

export const EditStoryboardImageModalLazy = lazy(() =>
  importStoryboardImageModal().then((module) => ({
    default: module.EditStoryboardImageModal
  }))
)

export function preloadStoryboardImageModalWhenIdle(): () => void {
  if (typeof window === 'undefined') return () => {}
  const browser = window as Window & {
    requestIdleCallback?: (callback: () => void, options?: { timeout?: number }) => number
    cancelIdleCallback?: (id: number) => void
  }
  if (browser.requestIdleCallback) {
    const id = browser.requestIdleCallback(() => void preloadStoryboardImageModal(), {
      timeout: 3000
    })
    return () => browser.cancelIdleCallback?.(id)
  }
  const timer = window.setTimeout(() => void preloadStoryboardImageModal(), 1500)
  return () => window.clearTimeout(timer)
}
