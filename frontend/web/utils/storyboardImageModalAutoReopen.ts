/**
 * 分镜图编辑弹窗：用户关闭 vs 刷新自动重开 的纯规则。
 * 生成中关闭必须 dismiss，否则 StoryboardScript.tryReopen* 会再次打开弹窗。
 */

export type StoryboardImageModalCloseDismiss =
  | { type: 'dialogue'; storyboardId: number; sceneIdx: number; imageIdx: number }
  | { type: 'storyboard'; storyboardId: number; sceneIdx: number }
  | { type: 'overlay'; storyboardId: number; sceneIdx: number }

export function shouldAutoReopenStoryboardImageModal(input: {
  isModalOpen: boolean
  fromScopeChange: boolean
  autoReopenAttempted: boolean
  hasSession: boolean
  isUserDismissed: boolean
  sceneIdx: number
  panelsLength: number
}): boolean {
  if (input.isModalOpen) return false
  if (!input.fromScopeChange && input.autoReopenAttempted) return false
  if (!input.hasSession) return false
  if (input.isUserDismissed) return false
  if (input.sceneIdx < 0 || input.sceneIdx >= input.panelsLength) return false
  return true
}

export function resolveStoryboardImageModalCloseDismiss(input: {
  session: {
    storyboardId: number
    sceneIdx: number
    tab?: string
    imageIdx?: number
  } | null
  currentSceneIdx: number
  currentImageIdx: number
  currentStoryboardId: number | null
  hasPendingForCurrent: boolean
}): StoryboardImageModalCloseDismiss | null {
  const session = input.session
  if (session) {
    const storyboardId = Number(session.storyboardId)
    const sceneIdx = Number(session.sceneIdx)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return null
    if (!Number.isFinite(sceneIdx) || sceneIdx < 0) return null

    if (session.tab === 'dialogue') {
      const imageIdx =
        session.imageIdx != null && Number.isFinite(Number(session.imageIdx))
          ? Number(session.imageIdx)
          : input.currentImageIdx
      return { type: 'dialogue', storyboardId, sceneIdx, imageIdx }
    }
    if (
      session.tab === 'upscale' ||
      session.tab === 'multiangle' ||
      session.tab === 'ninegrid'
    ) {
      return { type: 'overlay', storyboardId, sceneIdx }
    }
    return { type: 'storyboard', storyboardId, sceneIdx }
  }

  const sid = Number(input.currentStoryboardId)
  if (
    input.hasPendingForCurrent &&
    Number.isFinite(sid) &&
    sid > 0 &&
    Number.isFinite(input.currentSceneIdx) &&
    input.currentSceneIdx >= 0
  ) {
    return { type: 'storyboard', storyboardId: sid, sceneIdx: input.currentSceneIdx }
  }
  return null
}
