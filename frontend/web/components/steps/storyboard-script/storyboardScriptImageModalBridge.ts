'use client'

/**
 * StoryboardScript 编辑分镜图弹窗会话桥接（原 StoryboardScript.vue script 内
 * tryReopenStoryboardImageModalAfterRefresh + watch(isImageModalOpen) 关闭分支原样搬迁）。
 * 依赖经 deps 显式注入，由 useStoryboardScriptGeneration 装配。
 */

import type { MutableRefObject } from 'react'
import { findStoryboardImageGenTaskInScopes } from '~/composables/useCreationStoreHydration'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import { suspendTaskSseFollow } from '~/composables/useTaskSseFollow'
import { useCreationStore } from '~/stores/creation'
import { modalGenSessionScopeFromStore } from '~/utils/modalGenSessionScope'
import { shouldAutoReopenStoryboardImageModal } from '~/utils/storyboardImageModalAutoReopen'
import {
isModalImageGenUserDismissed,
markModalImageGenUserDismissed,
readModalImageGenSession
} from '~/utils/storyboardImageModalGenSession'
import { releaseStoryboardImageModalLiveOwned } from '~/utils/storyboardImageModalOwnedFollow'
import type { StoryboardPanel } from './storyboardScriptShared'

export interface StoryboardScriptImageModalBridgeDeps {
  panelsRef: MutableRefObject<StoryboardPanel[]>
  isImageModalOpenRef: MutableRefObject<boolean>
  currentPanelIndexRef: MutableRefObject<number>
  modalAutoReopenAttemptedRef: MutableRefObject<boolean>
  setCurrentPanelIndex: (v: number) => void
  setIsImageModalOpen: (v: boolean) => void
  refreshPanelsFromStoryboardListApi: () => Promise<void>
  restoreStoryboardImageBatchIfNeeded: () => Promise<void>
}

export function createStoryboardScriptImageModalBridge(
  deps: StoryboardScriptImageModalBridgeDeps
) {
  const {
    panelsRef,
    isImageModalOpenRef,
    currentPanelIndexRef,
    modalAutoReopenAttemptedRef,
    setCurrentPanelIndex,
    setIsImageModalOpen,
    refreshPanelsFromStoryboardListApi,
    restoreStoryboardImageBatchIfNeeded
  } = deps
  const getStore = () => useCreationStore.getState()

  /** 刷新或切回原作品后尝试一次自动重开弹窗；生成过程中进度更新不应反复打开 */
  function tryReopenStoryboardImageModalAfterRefresh(fromScopeChange = false) {
    if (typeof window === 'undefined') return

    const sessionScope = modalGenSessionScopeFromStore(getStore())
    const session = readModalImageGenSession(sessionScope)
    const storyboardId = session?.storyboardId
    const sceneIdx = session?.sceneIdx ?? -1
    if (
      !shouldAutoReopenStoryboardImageModal({
        isModalOpen: isImageModalOpenRef.current,
        fromScopeChange,
        autoReopenAttempted: modalAutoReopenAttemptedRef.current,
        hasSession: !!session,
        isUserDismissed:
          storyboardId != null
            ? isModalImageGenUserDismissed(storyboardId, sessionScope)
            : false,
        sceneIdx,
        panelsLength: panelsRef.current.length
      })
    ) {
      return
    }

    modalAutoReopenAttemptedRef.current = true
    setCurrentPanelIndex(sceneIdx)
    setIsImageModalOpen(true)
  }

  /** 原 watch(isImageModalOpen) 关闭分支 */
  function handleImageModalClosed() {
    // 防御：弹窗关闭路径若未 mark dismissed，仍阻止 refresh/panels watch 再次自动打开
    const sessionScope = modalGenSessionScopeFromStore(getStore())
    const session = readModalImageGenSession(sessionScope)
    const sid =
      session?.storyboardId ??
      parseServerStoryboardId(panelsRef.current[currentPanelIndexRef.current]?.id ?? '')
    if (session) {
      markModalImageGenUserDismissed(session.storyboardId, sessionScope)
    }
    if (sid != null) {
      releaseStoryboardImageModalLiveOwned(sid)
      const snap = findStoryboardImageGenTaskInScopes(getStore(), sid, getRouteLikeSnapshot())
      const tid = Number(snap?.taskId)
      if (Number.isFinite(tid) && tid > 0) suspendTaskSseFollow(tid)
    }
    void refreshPanelsFromStoryboardListApi()
    // 关窗后仅恢复列表自身的批量任务；弹窗 taskId 保留快照，重新打开弹窗时再续跟。
    setTimeout(() => {
      void restoreStoryboardImageBatchIfNeeded()
    }, 0)
  }

  return { tryReopenStoryboardImageModalAfterRefresh, handleImageModalClosed }
}
