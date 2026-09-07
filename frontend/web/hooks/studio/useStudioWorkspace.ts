'use client'

import { useCallback, useEffect, useRef } from 'react'
import { getStudioWorkspaceData, useStudioUiStore } from '@/stores/studioUi'
import {
  loadStudioFlowLayout,
  saveStudioFlowLayout,
  saveStudioFlowLayoutSync
} from '@/utils/studio/studioFlowLayoutStorage'
import { serializeStudioFlowLayoutWorkspace } from '@/utils/studio/studioFlowWorkspace'

export function useStudioWorkspace(scopeKey: string, options?: { readOnly?: boolean }) {
  const readOnly = options?.readOnly === true
  const dirtyRevision = useStudioUiStore((state) => state.dirtyRevision)
  const hydrated = useStudioUiStore((state) => state.hydrated)
  const hydrate = useStudioUiStore((state) => state.hydrate)
  const beginLoad = useStudioUiStore((state) => state.beginLoad)
  const setLoadError = useStudioUiStore((state) => state.setLoadError)
  const markSaving = useStudioUiStore((state) => state.markSaving)
  const markSaved = useStudioUiStore((state) => state.markSaved)
  const markSaveError = useStudioUiStore((state) => state.markSaveError)
  const loadedScopeRef = useRef('')
  const skipFirstSaveRef = useRef(true)
  const saveTransactionsRef = useRef(new Map<string, { promise: Promise<void>; queued: boolean }>())

  useEffect(() => {
    let cancelled = false
    beginLoad(scopeKey)
    loadedScopeRef.current = ''
    skipFirstSaveRef.current = true
    void loadStudioFlowLayout(scopeKey)
      .then((workspace) => {
        if (cancelled) return
        loadedScopeRef.current = scopeKey
        hydrate(workspace)
      })
      .catch((error: unknown) => {
        if (cancelled) return
        setLoadError(error instanceof Error ? error.message : '流程画布加载失败')
      })
    return () => { cancelled = true }
  }, [beginLoad, hydrate, scopeKey, setLoadError])

  const saveNow = useCallback(async () => {
    if (readOnly || !hydrated || loadedScopeRef.current !== scopeKey) return
    const existing = saveTransactionsRef.current.get(scopeKey)
    if (existing) {
      existing.queued = true
      return existing.promise
    }
    const transaction = { promise: Promise.resolve(), queued: false }
    const savePromise = (async () => {
      do {
        transaction.queued = false
        const workspace = serializeStudioFlowLayoutWorkspace(getStudioWorkspaceData())
        if (workspace.scopeKey !== scopeKey || loadedScopeRef.current !== scopeKey) break
        const revision = useStudioUiStore.getState().dirtyRevision
        markSaving()
        try {
          const { savedAt } = await saveStudioFlowLayout(workspace)
          if (loadedScopeRef.current === scopeKey) markSaved(savedAt, revision)
        } catch (error: unknown) {
          if (loadedScopeRef.current === scopeKey) {
            markSaveError(error instanceof Error ? error.message : '布局保存失败，请重试')
          }
          break
        }
      } while (transaction.queued && loadedScopeRef.current === scopeKey)
    })().finally(() => {
      if (saveTransactionsRef.current.get(scopeKey) === transaction) {
        saveTransactionsRef.current.delete(scopeKey)
      }
    })
    transaction.promise = savePromise
    saveTransactionsRef.current.set(scopeKey, transaction)
    return savePromise
  }, [hydrated, markSaveError, markSaved, markSaving, readOnly, scopeKey])

  useEffect(() => {
    if (readOnly || !hydrated || loadedScopeRef.current !== scopeKey) return
    if (skipFirstSaveRef.current) {
      skipFirstSaveRef.current = false
      return
    }
    if (!dirtyRevision) return
    const timer = window.setTimeout(() => void saveNow(), 650)
    return () => window.clearTimeout(timer)
  }, [dirtyRevision, hydrated, readOnly, saveNow, scopeKey])

  useEffect(() => {
    if (readOnly) return
    const handleBeforeUnload = () => {
      if (!useStudioUiStore.getState().hydrated) return
      saveStudioFlowLayoutSync(serializeStudioFlowLayoutWorkspace(getStudioWorkspaceData()))
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => {
      window.removeEventListener('beforeunload', handleBeforeUnload)
      if (useStudioUiStore.getState().hydrated) void saveNow()
    }
  }, [readOnly, saveNow])

  return { saveNow }
}
