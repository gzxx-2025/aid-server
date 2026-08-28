import { useCreationStore } from '~/stores/creation'
import type { DubbingPanel, StoryboardPanel, StoryboardVideoPanel } from '~/types'

export interface StoryboardStepPanelsSnapshot {
  script: StoryboardPanel[]
  video: StoryboardVideoPanel[]
  dubbing: DubbingPanel[]
}

function serverStoryboardId(panelId: string): number | null {
  if (!/^\d+$/.test(panelId)) return null
  const id = Number(panelId)
  return Number.isFinite(id) && id > 0 ? id : null
}

/**
 * 分镜三步骤按脚本索引同源：删除任意服务端分镜时原子过滤脚本、视频、配音，
 * 防止只更新当前页面导致跨步骤索引错位。
 */
export function removeStoryboardStepPanelsByIds(
  storyboardIds: number[]
): StoryboardStepPanelsSnapshot {
  const deleting = new Set(storyboardIds.filter((id) => Number.isFinite(id) && id > 0))
  const state = useCreationStore.getState()
  const script = state.formData.storyboardScript.panels as StoryboardPanel[]
  const video = state.formData.storyboardVideo.panels as StoryboardVideoPanel[]
  const dubbing = state.formData.dubbing.panels as DubbingPanel[]
  const keepIndexes = script.flatMap((panel, index) => {
    const id = serverStoryboardId(panel.id)
    return id != null && deleting.has(id) ? [] : [index]
  })
  const next = {
    script: keepIndexes.map((index) => script[index]).filter(Boolean),
    video: keepIndexes.map((index) => video[index]).filter(Boolean),
    dubbing: keepIndexes.map((index) => dubbing[index]).filter(Boolean)
  }
  useCreationStore.setState((current) => ({
    formData: {
      ...current.formData,
      storyboardScript: { ...current.formData.storyboardScript, panels: next.script },
      storyboardVideo: { ...current.formData.storyboardVideo, panels: next.video },
      dubbing: { ...current.formData.dubbing, panels: next.dubbing }
    },
    manualStoryboardIds: current.manualStoryboardIds.filter((id) => !deleting.has(id))
  }))
  return next
}

/** 删除全部分镜后，原子清空脚本、视频、配音三个步骤的本地面板状态。 */
export function clearAllStoryboardStepPanels(): void {
  useCreationStore.setState((state) => ({
    formData: {
      ...state.formData,
      storyboardScript: { ...state.formData.storyboardScript, panels: [] },
      storyboardVideo: { ...state.formData.storyboardVideo, panels: [] },
      dubbing: { ...state.formData.dubbing, panels: [] }
    },
    manualStoryboardIds: []
  }))
}
