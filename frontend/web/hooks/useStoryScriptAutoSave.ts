'use client'

import { message, Modal } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { getRouteLikeSnapshot } from '~/hooks/useRouteLike'
import { useCreationStore } from '~/stores/creation'
import {
  resolveStoryScriptEditorHtmlAfterApiLoad,
  storyScriptOriginalTextForApi
} from '~/utils/htmlPlain'
import {
  getStoryScriptAutoSaveCoordinator,
  storyScriptScopeKey,
  type StoryScriptAutoSaveCoordinator,
  type StoryScriptFlushResult,
  type StoryScriptPersistenceState
} from '~/utils/storyScriptPersistence'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'

const INITIAL_STATE: StoryScriptPersistenceState = {
  status: 'saved',
  lastSavedAt: null,
  errorMessage: null,
  conflict: null
}

/**
 * 剧本自动保存：停止输入5秒保存，持续编辑最多等待20秒，并统一处理重试与内容冲突。
 */
export function useStoryScriptAutoSave(
  htmlContent: string,
  applyEditorContent?: (html: string) => void
) {
  const [saveState, setSaveState] = useState<StoryScriptPersistenceState>(INITIAL_STATE)
  const coordinatorRef = useRef<StoryScriptAutoSaveCoordinator | null>(null)
  const unsubscribeRef = useRef<(() => void) | null>(null)
  const initializedScopesRef = useRef(new Set<string>())
  const resolveGenerationRef = useRef(0)
  const dialogOpenRef = useRef(false)
  const disposedRef = useRef(false)
  const applyEditorContentRef = useRef(applyEditorContent)

  useEffect(() => {
    applyEditorContentRef.current = applyEditorContent
  }, [applyEditorContent])

  useEffect(() => {
    const generation = ++resolveGenerationRef.current
    const apiText = storyScriptOriginalTextForApi(htmlContent)

    void (async () => {
      const ctx = await resolveStoryScriptSaveContext(
        useCreationStore.getState(),
        getRouteLikeSnapshot()
      )
      if (!ctx || disposedRef.current || generation !== resolveGenerationRef.current) return

      const key = storyScriptScopeKey(ctx)
      const previous = coordinatorRef.current
      if (previous && storyScriptScopeKey(previous.context) !== key) {
        void previous.flush()
        unsubscribeRef.current?.()
        unsubscribeRef.current = null
      }

      const coordinator = getStoryScriptAutoSaveCoordinator(ctx)
      coordinatorRef.current = coordinator
      if (!unsubscribeRef.current) {
        unsubscribeRef.current = coordinator.subscribe(() => setSaveState(coordinator.getState()))
      }
      setSaveState(coordinator.getState())

      const shouldSchedule = initializedScopesRef.current.has(key)
      coordinator.updateContent(apiText, shouldSchedule)
      initializedScopesRef.current.add(key)
    })()
  }, [htmlContent])

  useEffect(() => {
    disposedRef.current = false
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'hidden') void coordinatorRef.current?.flush()
    }
    const handlePageHide = () => {
      void coordinatorRef.current?.flush()
    }
    const handleOnline = () => {
      void coordinatorRef.current?.retryNow()
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)
    window.addEventListener('pagehide', handlePageHide)
    window.addEventListener('online', handleOnline)
    return () => {
      disposedRef.current = true
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      window.removeEventListener('pagehide', handlePageHide)
      window.removeEventListener('online', handleOnline)
      void coordinatorRef.current?.flush()
      unsubscribeRef.current?.()
      unsubscribeRef.current = null
    }
  }, [])

  useEffect(() => {
    const coordinator = coordinatorRef.current
    if (!saveState.conflict || !coordinator || dialogOpenRef.current) return
    dialogOpenRef.current = true
    Modal.confirm({
      title: '剧本内容冲突',
      content: '服务器上的剧本已在其他页面更新，请选择保留当前内容重新保存，或加载服务器内容。',
      okText: '保留本地并重试',
      cancelText: '加载服务器内容',
      closable: false,
      maskClosable: false,
      onOk: async () => {
        const result = await coordinator.keepLocalAfterConflict()
        if (result === 'error') {
          message.error(coordinator.getState().errorMessage || '重新保存失败')
          return Promise.reject()
        }
        if (result === 'conflict') {
          message.warning('服务器内容再次更新，请重新选择')
          return Promise.reject()
        }
      },
      onCancel: () => {
        const server = coordinator.useServerAfterConflict()
        const html = resolveStoryScriptEditorHtmlAfterApiLoad(
          String(server?.originalText ?? ''),
          ''
        )
        applyEditorContentRef.current?.(html)
      },
      afterClose: () => {
        dialogOpenRef.current = false
      }
    })
  }, [saveState.conflict])

  const flushAutoSave = useCallback(async (): Promise<StoryScriptFlushResult> => {
    return coordinatorRef.current?.flush() ?? 'clean'
  }, [])

  return { flushAutoSave, saveState }
}
