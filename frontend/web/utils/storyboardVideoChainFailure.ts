import type { StoryboardVideoPanel } from '~/types'

export interface StoryboardVideoChainFailureTarget {
  index: number
  storyboardId: number
}

interface StoryboardVideoChainFailureStore {
  clearStoryboardPanelVideoGenStatus(storyboardId: number): void
  setStoryboardPanelVideoGenStatus(storyboardId: number, status: 'failed'): void
  setStoryboardPanelVideoGenError(storyboardId: number, message: string): void
  finalizeStoryboardVideoBatchGeneration(): void
}

/** 链式提交只应标记从未进入任何显式子任务的分镜。 */
export function excludeCoveredStoryboardIds(
  targetStoryboardIds: number[],
  coveredStoryboardIds?: ReadonlySet<number>
): number[] {
  if (!coveredStoryboardIds?.size) return [...targetStoryboardIds]
  return targetStoryboardIds.filter((id) => !coveredStoryboardIds.has(id))
}

/** 链式出片失败的唯一 UI 终态迁移：落卡片错误后原子清除批量运行凭证。 */
export function finalizeStoryboardVideoChainFailure(input: {
  store: StoryboardVideoChainFailureStore
  videoPanels: StoryboardVideoPanel[]
  targets: StoryboardVideoChainFailureTarget[]
  targetStoryboardIds: number[]
  message: string
}): StoryboardVideoPanel[] {
  const message = String(input.message || '').trim() || '视频提交失败'
  const targetIds = new Set(
    input.targetStoryboardIds.map((id) => Number(id)).filter((id) => Number.isFinite(id) && id > 0)
  )
  const targetByIndex = new Map(
    input.targets
      .filter((target) => targetIds.has(target.storyboardId))
      .map((target) => [target.index, target.storyboardId])
  )

  for (const storyboardId of targetIds) {
    input.store.clearStoryboardPanelVideoGenStatus(storyboardId)
    input.store.setStoryboardPanelVideoGenStatus(storyboardId, 'failed')
    input.store.setStoryboardPanelVideoGenError(storyboardId, message)
  }

  const panels = input.videoPanels.map((panel, index) => {
    if (!targetByIndex.has(index)) return panel
    return {
      ...panel,
      generating: false,
      generateError: message,
      videos: []
    }
  })

  input.store.finalizeStoryboardVideoBatchGeneration()
  return panels
}
