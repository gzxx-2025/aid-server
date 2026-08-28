/**
 * 分镜图编辑弹窗「正在跟 SSE」的 storyboardId 占坑。
 * 仅表示弹窗仍 live follow；关窗后应 release，Pinia taskId 仍归弹窗恢复。
 */

export const activeStoryboardImageModalGenFollowIds = new Set<number>()
export const activeStoryboardImageModalDialogueFollowIds = new Set<number>()
export const activeStoryboardImageModalOverlayFollowIds = new Set<number>()

/** @deprecated 弹窗任务不再允许外层静默续跟；保留导出兼容旧调用。 */
export const activeStoryboardImageModalRestoreFollowIds = new Set<number>()

function normSid(storyboardId: number): number | null {
  const sid = Number(storyboardId)
  return Number.isFinite(sid) && sid > 0 ? sid : null
}

export function isStoryboardImageModalLiveOwned(storyboardId: number): boolean {
  const sid = normSid(storyboardId)
  if (sid == null) return false
  return (
    activeStoryboardImageModalGenFollowIds.has(sid) ||
    activeStoryboardImageModalDialogueFollowIds.has(sid) ||
    activeStoryboardImageModalOverlayFollowIds.has(sid)
  )
}

export function releaseStoryboardImageModalLiveOwned(storyboardId: number): void {
  const sid = normSid(storyboardId)
  if (sid == null) return
  activeStoryboardImageModalGenFollowIds.delete(sid)
  activeStoryboardImageModalDialogueFollowIds.delete(sid)
  activeStoryboardImageModalOverlayFollowIds.delete(sid)
}

/** 弹窗持久化任务始终由弹窗接管，外层列表不得订阅同一 SSE。 */
export function shouldOuterFollowStoryboardImageModalTask(input: {
  taskId: number
  storyboardId: number
  modalLiveOwned: boolean
}): boolean {
  const tid = Number(input.taskId)
  const sid = Number(input.storyboardId)
  if (!Number.isFinite(tid) || tid <= 0) return false
  if (!Number.isFinite(sid) || sid <= 0) return false
  return false
}
