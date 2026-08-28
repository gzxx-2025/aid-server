'use client'

import { useEffect,useRef,useState,type MutableRefObject } from 'react'
import { useCreateFlowScopeChangedResume } from '~/composables/useCreateFlowLiveGenResume'
import { useStoryboardAudioBatchGenerate } from '~/composables/useStoryboardAudioBatchGenerate'
import { useStoryboardDubbingBackgroundRestore } from '~/composables/useStoryboardDubbingBackgroundRestore'
import { useCreationStore } from '~/stores/creation'
import type { DubbingPanel,StoryboardPanel,StoryboardVideoPanel } from '~/types'
import { createCoalescedAsyncRunner } from '~/utils/coalescedAsyncRunner'

/**
 * Dubbing 步骤的后台任务恢复编排（原 Dubbing.vue script 内
 * runStoryboardDubbingRestoreOnce + 生命周期 / scope watch 部分原样搬迁）：
 * 弹窗单镜配音恢复 + 批量配音恢复；有 taskId 先跟 SSE 再亮 loading，回调过 scope guard。
 */
export function useDubbingStepRestore(opts: {
  panelsRef: MutableRefObject<DubbingPanel[]>
  scriptPanelsRef: MutableRefObject<StoryboardPanel[]>
  videoPanelsRef: MutableRefObject<StoryboardVideoPanel[]>
  onChangeRef: MutableRefObject<(next: DubbingPanel[]) => void>
  onGeneratingRef: MutableRefObject<(v: boolean) => void>
  isHydrated: boolean
  currentProjectId: number | null
  currentEpisodeId: number | null
  routeProjectId: unknown
  routeEpisodeId: unknown
  panelsLength: number
}) {
  const {
    panelsRef,
    scriptPanelsRef,
    videoPanelsRef,
    onChangeRef,
    onGeneratingRef,
    isHydrated,
    currentProjectId,
    currentEpisodeId,
    routeProjectId,
    routeEpisodeId,
    panelsLength
  } = opts

  const dubbingBackgroundRestore = useStoryboardDubbingBackgroundRestore()
  const storyboardAudioBatchGen = useStoryboardAudioBatchGenerate()
  const pageDisposedRef = useRef(false)
  const pageMountedRef = useRef(false)
  const dubbingRestoreGenerationRef = useRef(0)
  const storyboardAudioFollowHandoffRequestedRef = useRef(false)

  async function runStoryboardDubbingRestoreOnce() {
    if (
      typeof window === 'undefined' ||
      !pageMountedRef.current ||
      pageDisposedRef.current ||
      !useCreationStore.getState().isHydrated
    ) {
      return
    }
    const gen = ++dubbingRestoreGenerationRef.current
    const restoreIsActive = () =>
      pageMountedRef.current && !pageDisposedRef.current && gen === dubbingRestoreGenerationRef.current
    const waitForFollowHandoff = storyboardAudioFollowHandoffRequestedRef.current
    storyboardAudioFollowHandoffRequestedRef.current = false
    if (waitForFollowHandoff) {
      await storyboardAudioBatchGen.waitForFollowIdle()
      if (!restoreIsActive()) return
    }
    await dubbingBackgroundRestore.restoreOngoingDubbingTasks(
      panelsRef.current,
      scriptPanelsRef.current,
      videoPanelsRef.current,
      (next) => {
        if (!restoreIsActive()) return
        onChangeRef.current(next)
      }
    )
    if (!restoreIsActive()) return
    await storyboardAudioBatchGen.restoreOngoingBatchIfNeeded({
      panels: panelsRef.current,
      scriptPanels: scriptPanelsRef.current,
      onPanelsUpdate: (next) => {
        if (!restoreIsActive()) return
        onChangeRef.current(next)
      },
      onGenerating: (v) => {
        if (!restoreIsActive()) return
        onGeneratingRef.current(v)
      }
    })
  }

  const restoreOnceRef = useRef(runStoryboardDubbingRestoreOnce)
  restoreOnceRef.current = runStoryboardDubbingRestoreOnce
  const [storyboardDubbingRestoreRunner] = useState(() =>
    createCoalescedAsyncRunner(() => restoreOnceRef.current())
  )

  function restoreStoryboardDubbingTasksIfNeeded() {
    if (
      typeof window === 'undefined' ||
      !pageMountedRef.current ||
      pageDisposedRef.current ||
      !useCreationStore.getState().isHydrated
    ) {
      return Promise.resolve()
    }
    return storyboardDubbingRestoreRunner.request()
  }

  // 原 onMounted（恢复部分）+ onBeforeUnmount + onUnmounted
  useEffect(() => {
    pageMountedRef.current = true
    pageDisposedRef.current = false
    // 原 createPreloadableAsyncComponent + preloadComponentWhenIdle 的空闲预加载不再需要：
    // EditStoryboardDubbingModal 已在 Dubbing.tsx 静态引入，随步骤 chunk 一并加载
    void restoreStoryboardDubbingTasksIfNeeded()
    return () => {
      pageMountedRef.current = false
      pageDisposedRef.current = true
      storyboardDubbingRestoreRunner.dispose()
      dubbingRestoreGenerationRef.current += 1
      dubbingBackgroundRestore.cancelPendingRestore()
      void storyboardAudioBatchGen.cancelResumeFollow()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 原 watch(scope, { flush: 'sync' })：项目/剧集切换时交接 SSE 并按新 scope 恢复
  const prevScopeRef = useRef<readonly [unknown, unknown, unknown, unknown] | null>(null)
  useEffect(() => {
    const scope = [currentProjectId, currentEpisodeId, routeProjectId, routeEpisodeId] as const
    const previousScope = prevScopeRef.current
    prevScopeRef.current = scope
    if (!previousScope || scope.every((value, index) => value === previousScope[index])) return
    dubbingRestoreGenerationRef.current += 1
    storyboardAudioFollowHandoffRequestedRef.current = true
    dubbingBackgroundRestore.cancelPendingRestore()
    void storyboardAudioBatchGen.cancelResumeFollow()
    void restoreStoryboardDubbingTasksIfNeeded()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentProjectId, currentEpisodeId, routeProjectId, routeEpisodeId])

  /** 挂载后由 watch 补跑最新 scope；未水合时先短路，isHydrated 翻转后自动恢复。 */
  useEffect(() => {
    if (!isHydrated) return
    void restoreStoryboardDubbingTasksIfNeeded()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isHydrated, currentProjectId, currentEpisodeId, routeProjectId, routeEpisodeId, panelsLength])

  useCreateFlowScopeChangedResume(() => {
    // 项目/剧集切换由同步 watch 完成交接；流程 Tab 返回只请求当前 scope 恢复。
    return restoreStoryboardDubbingTasksIfNeeded()
  })

  return {
    storyboardAudioBatchGen,
    restoreStoryboardDubbingTasksIfNeeded,
    pageDisposedRef
  }
}
