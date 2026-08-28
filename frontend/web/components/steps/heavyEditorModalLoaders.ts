'use client'

import { lazy } from 'react'

const importVideoEditor = () => import('./EditStoryboardVideoModal')
const importDubbingEditor = () => import('./EditStoryboardDubbingModal')
let videoPreload: Promise<unknown> | null = null
let dubbingPreload: Promise<unknown> | null = null

export const EditStoryboardVideoModalLazy = lazy(() =>
  importVideoEditor().then((module) => ({ default: module.EditStoryboardVideoModal }))
)

export const EditStoryboardDubbingModalLazy = lazy(() =>
  importDubbingEditor().then((module) => ({ default: module.EditStoryboardDubbingModal }))
)

export function preloadStoryboardVideoEditor() {
  if (!videoPreload) {
    videoPreload = importVideoEditor().catch(() => {
      videoPreload = null
    })
  }
  return videoPreload ?? Promise.resolve()
}

export function preloadStoryboardDubbingEditor() {
  if (!dubbingPreload) {
    dubbingPreload = importDubbingEditor().catch(() => {
      dubbingPreload = null
    })
  }
  return dubbingPreload ?? Promise.resolve()
}

function preloadWhenIdle(load: () => void): () => void {
  if (typeof window === 'undefined') return () => {}
  const browser = window as Window & {
    requestIdleCallback?: (callback: () => void, options?: { timeout?: number }) => number
    cancelIdleCallback?: (id: number) => void
  }
  if (browser.requestIdleCallback) {
    const id = browser.requestIdleCallback(load, { timeout: 3000 })
    return () => browser.cancelIdleCallback?.(id)
  }
  const timer = window.setTimeout(load, 1500)
  return () => window.clearTimeout(timer)
}

export const preloadStoryboardVideoEditorWhenIdle = () =>
  preloadWhenIdle(() => void preloadStoryboardVideoEditor())

export const preloadStoryboardDubbingEditorWhenIdle = () =>
  preloadWhenIdle(() => void preloadStoryboardDubbingEditor())

export function preloadPreviewEditorModalsWhenIdle(): () => void {
  return preloadWhenIdle(() => {
    void preloadStoryboardVideoEditor()
    void preloadStoryboardDubbingEditor()
  })
}
