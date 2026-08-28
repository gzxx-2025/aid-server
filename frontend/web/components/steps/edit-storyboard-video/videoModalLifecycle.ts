import { invalidateGenerationToken } from '~/utils/generationToken'
import { clearStoryboardVideoModalUserDismissed } from '~/utils/storyboardVideoModalGenSession'
import type { VideoModalCtx } from './types'

interface VideoModalLifecycleOptions {
  open: boolean
  sceneIndex: number
  onGlobalTasksUpdated: EventListener
  onVideoGenSettled: EventListener
}

/** 将一次弹窗开关的可变命令收敛在 React effect 边界之外。 */
export function syncVideoModalOpenLifecycle(
  ctx: VideoModalCtx,
  options: VideoModalLifecycleOptions
): number | null {
  const { open, sceneIndex, onGlobalTasksUpdated, onVideoGenSettled } = options

  if (open) {
    ctx.imageToVideoModel.set('')
    ctx.multiParamVideoModel.set('')
    ctx.edgeVideoModel.set('')
    ctx.gridVideoModel.set('')
    ctx.edgeVideoPromptByStoryboardId.set({})
    ctx.imageToVideoModelDropdownExpanded.set(false)
    ctx.multiParamVideoModelDropdownExpanded.set(false)
    ctx.edgeVideoModelDropdownExpanded.set(false)
    void ctx.initVideoModelOptions()
    ctx.currentSceneIndex.set(sceneIndex)
    ctx.showStoryboardScriptModal.set(false)

    const openStoryboardId = ctx.sceneStoryboardIdNum(sceneIndex)
    if (openStoryboardId != null) {
      clearStoryboardVideoModalUserDismissed(ctx.storyboardVideoModalSessionScope())
    }
    ctx.ensurePendingStoryboardVideoLoadingPlaceholders(sceneIndex)

    const refreshTimer = window.setTimeout(() => {
      ctx.scrollActiveSceneTabIntoView()
      ctx.sceneTabBarRef.current?.refresh()
    }, 0)

    window.addEventListener('create-flow-global-tasks-updated', onGlobalTasksUpdated)
    window.addEventListener('create-flow-storyboard-video-gen-settled', onVideoGenSettled)
    ctx.resetStoryboardReferenceState()
    ctx.applyDefaultStoryboardReferenceImages(sceneIndex)
    void ctx.syncSceneDetailAndRestore(sceneIndex)
    return refreshTimer
  }

  invalidateGenerationToken(ctx.initVideoModelGen)
  invalidateGenerationToken(ctx.resumeStoryboardVideoFollowGen)
  invalidateGenerationToken(ctx.resumeStoryboardVideoPromptFollowGen)
  ctx.showStoryboardScriptModal.set(false)
  ctx.saveEdgeVideoPromptToCache(ctx.currentStoryboardId())
  window.removeEventListener('create-flow-global-tasks-updated', onGlobalTasksUpdated)
  window.removeEventListener('create-flow-storyboard-video-gen-settled', onVideoGenSettled)
  return null
}

export function removeVideoModalGlobalListeners(
  onGlobalTasksUpdated: EventListener,
  onVideoGenSettled: EventListener
): void {
  window.removeEventListener('create-flow-global-tasks-updated', onGlobalTasksUpdated)
  window.removeEventListener('create-flow-storyboard-video-gen-settled', onVideoGenSettled)
}
