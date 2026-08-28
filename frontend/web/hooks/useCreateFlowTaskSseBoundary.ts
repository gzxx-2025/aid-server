'use client'

import { useEffect,useLayoutEffect,useRef } from 'react'
import { suspendAllTaskSseFollows } from '~/hooks/useTaskSseFollow'
import { useCreationStore } from '~/stores/creation'
/**
 * 创作壳 SSE 生命周期边界：切路由/作品/剧集先断浏览器跟随，保留持久化任务快照供目标页恢复。
 */
export function useCreateFlowTaskSseBoundary(
  routePath: string,
  syncTitleInputWidth: () => void
): void {
  const previousPathRef = useRef<string | undefined>(undefined)
  useLayoutEffect(() => {
    const previousPath = previousPathRef.current
    previousPathRef.current = routePath
    if (previousPath !== undefined && routePath !== previousPath) {
      useCreationStore.getState().setStep3AssetListSyncReady(false)
      suspendAllTaskSseFollows()
    }
    window.setTimeout(syncTitleInputWidth, 0)
  }, [routePath, syncTitleInputWidth])

  const step3ScopeKey = useCreationStore((state) => state.step3GenVisualScopeKey())
  const previousScopeKeyRef = useRef<string | undefined>(undefined)
  useLayoutEffect(() => {
    const previousScopeKey = previousScopeKeyRef.current
    previousScopeKeyRef.current = step3ScopeKey
    if (previousScopeKey !== undefined && previousScopeKey && step3ScopeKey !== previousScopeKey) {
      suspendAllTaskSseFollows()
    }
  }, [step3ScopeKey])

  useEffect(
    () => () => {
      suspendAllTaskSseFollows()
      const store = useCreationStore.getState()
      store.syncStep3GenVisualToCurrentScope()
      store.syncStep4PlusLiveGenToCurrentScope()
    },
    []
  )
}
