'use client'

import { useEffect,useRef } from 'react'
import {
applyCreationStoreScopeLiveGenFromRoute,
applyStep3GenVisualFromRoute,
waitForCreationStoreHydrated
} from '~/composables/useCreationStoreHydration'
import { useCreationStore } from '~/stores/creation'
import type { RouteLikeLocation } from '~/types/routeLike'
import {
CREATE_FLOW_SCOPE_CHANGED_EVENT,
dispatchCreateFlowScopeChanged
} from '~/utils/createFlowLiveGenEvents'

/**
 * 壳层统一调度：切作品时同步 Pinia scope 并通知当前步骤页恢复 SSE。
 */
export function useCreateFlowLiveGenResume(options: { route: RouteLikeLocation }) {
  const routeRef = useRef(options.route)
  routeRef.current = options.route

  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)

  const stateRef = useRef({
    resumeGeneration: 0,
    resumeTimer: null as ReturnType<typeof setTimeout> | null,
    resumeInFlight: null as Promise<void> | null
  })

  async function runLiveGenResume(
    gen: number,
    reason: 'scope-or-route' | 'panel-return' | 'manual'
  ) {
    const state = stateRef.current
    if (gen !== state.resumeGeneration) return
    if (typeof window === 'undefined') return

    if (state.resumeInFlight) {
      await state.resumeInFlight
      if (gen !== state.resumeGeneration) return
    }

    const pending = (async () => {
      await waitForCreationStoreHydrated(useCreationStore.getState(), routeRef.current)
      if (gen !== state.resumeGeneration) return

      applyCreationStoreScopeLiveGenFromRoute(useCreationStore.getState(), routeRef.current)
      applyStep3GenVisualFromRoute(useCreationStore.getState(), routeRef.current)
      if (gen !== state.resumeGeneration) return

      dispatchCreateFlowScopeChanged({ reason })
    })()

    state.resumeInFlight = pending
    try {
      await pending
    } finally {
      if (state.resumeInFlight === pending) state.resumeInFlight = null
    }
  }

  function scheduleLiveGenResume(reason: 'scope-or-route' | 'panel-return' | 'manual') {
    if (typeof window === 'undefined') return
    const state = stateRef.current
    state.resumeGeneration++
    const gen = state.resumeGeneration
    if (state.resumeTimer) clearTimeout(state.resumeTimer)
    state.resumeTimer = setTimeout(() => {
      state.resumeTimer = null
      void runLiveGenResume(gen, reason)
    }, 64)
  }

  const scheduleLiveGenResumeRef = useRef(scheduleLiveGenResume)
  scheduleLiveGenResumeRef.current = scheduleLiveGenResume

  // 原 watch([currentProjectId, currentEpisodeId])（无 immediate）：跳过首次渲染
  const prevScopeRef = useRef<readonly [number | null, number | null] | null>(null)
  useEffect(() => {
    const prev = prevScopeRef.current
    prevScopeRef.current = [currentProjectId, currentEpisodeId] as const
    if (!prev) return
    if (prev[0] === currentProjectId && prev[1] === currentEpisodeId) return
    scheduleLiveGenResumeRef.current('scope-or-route')
  }, [currentProjectId, currentEpisodeId])

  return { scheduleLiveGenResume }
}

/** 步骤页挂载：监听 scope 变化事件，触发一次轻量 restore（带 generation 防重入） */
export function useCreateFlowScopeChangedResume(onResume: () => void | Promise<void>) {
  const onResumeRef = useRef(onResume)
  onResumeRef.current = onResume

  useEffect(() => {
    let scopeResumeGeneration = 0
    let scopeResumeInFlight: Promise<void> | null = null
    let disposed = false

    const run = async (gen: number) => {
      if (disposed || gen !== scopeResumeGeneration) return
      if (scopeResumeInFlight) {
        try {
          await scopeResumeInFlight
        } catch {
          /* The owning step handles restore errors. A newer request must still be allowed to run. */
        }
        if (disposed || gen !== scopeResumeGeneration) return
      }

      const pending = Promise.resolve().then(() => onResumeRef.current())
      scopeResumeInFlight = pending as Promise<void>
      try {
        await pending
      } finally {
        if (scopeResumeInFlight === pending) scopeResumeInFlight = null
      }
    }

    const handler = () => {
      const gen = ++scopeResumeGeneration
      void run(gen)
    }

    window.addEventListener(CREATE_FLOW_SCOPE_CHANGED_EVENT, handler)
    return () => {
      disposed = true
      scopeResumeGeneration += 1
      window.removeEventListener(CREATE_FLOW_SCOPE_CHANGED_EVENT, handler)
    }
  }, [])
}
