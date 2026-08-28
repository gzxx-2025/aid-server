'use client'

import { useRef, useState, type MutableRefObject } from 'react'
import { useCreationStore } from '~/stores/creation'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { type useStoryboardImageBatchGenerate } from '~/composables/useStoryboardImageBatchGenerate'
import { type useStoryboardWorkbenchMutations } from '~/composables/useStoryboardWorkbenchMutations'
import { waitForCreationStoreHydrated } from '~/composables/useCreationStoreHydration'
import { hasPersistedStoryboardImageBatchGenWork } from '~/utils/storyboardListBootstrap'
import {
  shouldDropImageBatchRestoreBecauseFollowing,
  shouldRestoreImageBatchSse
} from '~/utils/storyboardImageBatchRestoreGate'
import { createCoalescedAsyncRunner } from '~/utils/coalescedAsyncRunner'
import type { StoryboardPanel } from './storyboardScriptShared'

type ImageBatchGenerate = ReturnType<typeof useStoryboardImageBatchGenerate>
type Workbench = ReturnType<typeof useStoryboardWorkbenchMutations>

export function useStoryboardScriptImageBatchRestore(opts: {
  panelsRef: MutableRefObject<StoryboardPanel[]>
  onChangeRef: MutableRefObject<(panels: StoryboardPanel[]) => void>
  pageMountedRef: MutableRefObject<boolean>
  pageDisposedRef: MutableRefObject<boolean>
  imageBatchGenerate: ImageBatchGenerate
  workbench: Workbench
}) {
  const { panelsRef, onChangeRef, pageMountedRef, pageDisposedRef, imageBatchGenerate, workbench } =
    opts
  const generationRef = useRef(0)
  const discoverServerTasksRef = useRef(false)
  const waitForFollowHandoffRef = useRef(false)

  const runRestoreOnce = async () => {
    if (typeof window === 'undefined' || !pageMountedRef.current || pageDisposedRef.current) return
    const getStore = () => useCreationStore.getState()
    await waitForCreationStoreHydrated(getStore(), getRouteLikeSnapshot())
    if (!pageMountedRef.current || pageDisposedRef.current) return
    const discoverServerTasks = discoverServerTasksRef.current
    const waitForFollowHandoff = waitForFollowHandoffRef.current
    discoverServerTasksRef.current = false
    waitForFollowHandoffRef.current = false

    imageBatchGenerate.applyImmediatePanelLoadingRestore(panelsRef.current)
    if (shouldDropImageBatchRestoreBecauseFollowing(imageBatchGenerate.isFollowInFlight())) {
      if (!waitForFollowHandoff) return
      await imageBatchGenerate.waitForFollowIdle()
      if (!pageMountedRef.current || pageDisposedRef.current) return
      imageBatchGenerate.applyImmediatePanelLoadingRestore(panelsRef.current)
    }

    const store = getStore()
    const shouldRestore = shouldRestoreImageBatchSse({
      isGenerating:
        Boolean(store.isGeneratingStoryboardImageBatch) ||
        hasPersistedStoryboardImageBatchGenWork(store, getRouteLikeSnapshot()),
      following: false,
      hasServerStoryboardIds: panelsRef.current.some(
        (panel) => workbench.parseServerStoryboardId(panel.id) != null
      ),
      hasActiveTaskId:
        Number(store.storyboardImageBatchActiveTaskId) > 0 ||
        Number(store.storyboardImageBatchActiveImageTaskId) > 0
    })
    if (!shouldRestore && !discoverServerTasks) return

    imageBatchGenerate.cancelResumeFollow()
    const generation = ++generationRef.current
    await imageBatchGenerate.restoreOngoingBatchIfNeeded(
      panelsRef.current,
      (next) => {
        if (
          pageMountedRef.current &&
          !pageDisposedRef.current &&
          generation === generationRef.current
        ) {
          onChangeRef.current(next)
        }
      },
      { discoverServerTasks }
    )
  }

  const runRestoreRef = useRef(runRestoreOnce)
  runRestoreRef.current = runRestoreOnce
  const [runner] = useState(() => createCoalescedAsyncRunner(() => runRestoreRef.current()))

  const restoreStoryboardImageBatchIfNeeded = (options?: {
    discoverServerTasks?: boolean
    waitForFollowHandoff?: boolean
  }) => {
    if (typeof window === 'undefined' || !pageMountedRef.current || pageDisposedRef.current) {
      return Promise.resolve()
    }
    if (options?.discoverServerTasks) discoverServerTasksRef.current = true
    if (options?.waitForFollowHandoff) waitForFollowHandoffRef.current = true
    if (
      shouldDropImageBatchRestoreBecauseFollowing(imageBatchGenerate.isFollowInFlight()) &&
      !waitForFollowHandoffRef.current
    ) {
      return Promise.resolve()
    }
    return runner.request()
  }

  const disposeStoryboardImageBatchRestore = () => {
    generationRef.current += 1
    runner.dispose()
  }

  return { restoreStoryboardImageBatchIfNeeded, disposeStoryboardImageBatchRestore }
}
