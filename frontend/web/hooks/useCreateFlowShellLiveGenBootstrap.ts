'use client'

import { useEffect,useRef } from 'react'
import { applyStoryboardScriptPanelsFromApi } from '~/composables/useCreateFlowStoryboardSync'
import {
applyCreationStoreScopeLiveGenFromRoute,
applyStep3GenVisualFromRoute,
waitForCreationStoreHydrated
} from '~/composables/useCreationStoreHydration'
import { useStoryboardScriptBatchGenerate } from '~/composables/useStoryboardScriptBatchGenerate'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel } from '~/types'
import type { RouteLikeLocation } from '~/types/routeLike'
import { dispatchCreateFlowScopeChanged } from '~/utils/createFlowLiveGenEvents'
import { routePathToCreationStep } from '~/utils/createFlowRoutes'
import { purgeTerminalStep3ModalSseTasks } from '~/utils/step3LiveGenRestore'
import { purgeTerminalStep4LiveGenTasks } from '~/utils/step4LiveGenRestore'

/**
 * 创作流程壳层（原 composables/useCreateFlowShellLiveGenBootstrap.ts）：
 * 刷新 / 首屏进入时统一恢复各步骤的生成态与 SSE 跟进，
 * 使头部流程条 loading 不依赖当前停留在哪一步。
 */
export function useCreateFlowShellLiveGenBootstrap(options: {
  route: RouteLikeLocation
  syncProjectContextFromRoute: () => void
}) {
  const { route, syncProjectContextFromRoute } = options
  const storyboardScriptGen = useStoryboardScriptBatchGenerate()

  const routeRef = useRef(route)
  routeRef.current = route
  const syncProjectContextRef = useRef(syncProjectContextFromRoute)
  syncProjectContextRef.current = syncProjectContextFromRoute
  const storyboardScriptGenRef = useRef(storyboardScriptGen)
  storyboardScriptGenRef.current = storyboardScriptGen

  const stateRef = useRef({
    bootstrapGeneration: 0,
    bootstrapTimer: null as ReturnType<typeof setTimeout> | null,
    bootstrapInFlight: null as Promise<void> | null
  })

  async function runShellLiveGenBootstrap(gen: number) {
    const state = stateRef.current
    if (gen !== state.bootstrapGeneration) return
    if (typeof window === 'undefined') return

    if (state.bootstrapInFlight) {
      await state.bootstrapInFlight
      if (gen !== state.bootstrapGeneration) return
    }

    const pending = (async () => {
      syncProjectContextRef.current()
      const creationStore = useCreationStore.getState()
      await waitForCreationStoreHydrated(creationStore, routeRef.current)
      if (gen !== state.bootstrapGeneration) return

      applyCreationStoreScopeLiveGenFromRoute(useCreationStore.getState(), routeRef.current)
      applyStep3GenVisualFromRoute(useCreationStore.getState(), routeRef.current)
      await purgeTerminalStep3ModalSseTasks(useCreationStore.getState(), routeRef.current)
      if (gen !== state.bootstrapGeneration) return
      await purgeTerminalStep4LiveGenTasks(useCreationStore.getState(), routeRef.current)
      if (gen !== state.bootstrapGeneration) return

      useCreationStore.getState().refreshStep3VisualGeneratingFlag()

      const storeNow = useCreationStore.getState()
      const onStoryboardScriptPage =
        routePathToCreationStep(routeRef.current.path) === 'storyboard-script'
      const hasPersistedStoryboardScriptWork =
        storeNow.isGeneratingStoryboard ||
        (storeNow.storyboardScriptActiveTaskId != null &&
          Number(storeNow.storyboardScriptActiveTaskId) > 0)

      // 仅当存在进行中的分镜脚本任务且当前不在第四步时，才由壳层恢复（避免进任意步骤都预拉 task/list）
      if (!onStoryboardScriptPage && hasPersistedStoryboardScriptWork) {
        const panels =
          (storeNow.formData.storyboardScript?.panels as StoryboardPanel[]) ?? []
        await storyboardScriptGenRef.current.restoreOngoingGenerationIfNeeded(
          panels,
          (next) => {
            if (gen !== state.bootstrapGeneration) return
            applyStoryboardScriptPanelsFromApi(next)
          },
          () => {
            if (gen !== state.bootstrapGeneration) return
            if (!useCreationStore.getState().isGeneratingStoryboard) {
              useCreationStore.getState().setStoryboardGenerating(true)
            }
          }
        )
        if (gen !== state.bootstrapGeneration) return
      }

      dispatchCreateFlowScopeChanged({ reason: 'scope-or-route' })
    })()

    state.bootstrapInFlight = pending
    try {
      await pending
    } finally {
      if (state.bootstrapInFlight === pending) state.bootstrapInFlight = null
    }
  }

  function scheduleShellLiveGenBootstrap() {
    if (typeof window === 'undefined') return
    const state = stateRef.current
    state.bootstrapGeneration++
    const gen = state.bootstrapGeneration
    if (state.bootstrapTimer) clearTimeout(state.bootstrapTimer)
    state.bootstrapTimer = setTimeout(() => {
      state.bootstrapTimer = null
      void runShellLiveGenBootstrap(gen)
    }, 48)
  }

  const scheduleRef = useRef(scheduleShellLiveGenBootstrap)
  scheduleRef.current = scheduleShellLiveGenBootstrap

  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)
  const routeProjectId = route.query.projectId
  const routeEpisodeId = route.query.episodeId

  // 原 onMounted + watch([store ids, route.query ids], flush: 'post')
  useEffect(() => {
    scheduleRef.current()
  }, [currentProjectId, currentEpisodeId, routeProjectId, routeEpisodeId])

  return { scheduleShellLiveGenBootstrap }
}
