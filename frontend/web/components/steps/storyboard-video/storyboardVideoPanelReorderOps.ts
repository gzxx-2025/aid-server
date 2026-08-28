import { message } from 'antd'
import type { DubbingPanel, StoryboardPanel, StoryboardVideoPanel } from '~/types'
import { useCreationStore } from '~/stores/creation'
import { moveItemBeforeIndex } from '~/utils/moveItemBeforeIndex'
import { type useStoryboardWorkbenchMutations } from '~/composables/useStoryboardWorkbenchMutations'

type StoryboardWorkbench = ReturnType<typeof useStoryboardWorkbenchMutations>

function getErrorMessage(error: unknown): string {
  const value = error as { msg?: string; message?: string }
  return value?.msg || value?.message || '操作失败'
}

export async function reorderStoryboardVideoPanels(params: {
  from: number
  to: number
  onChange: (next: StoryboardVideoPanel[]) => void
  workbench: StoryboardWorkbench
}) {
  const { from, to, onChange, workbench } = params
  const insertBefore = from < to ? to + 1 : to
  const state = useCreationStore.getState()
  const scripts = [...(state.formData.storyboardScript.panels as StoryboardPanel[])]
  const videos = [...(state.formData.storyboardVideo.panels as StoryboardVideoPanel[])]
  const dubbing = [...(state.formData.dubbing.panels as DubbingPanel[])]
  if (scripts.length <= 1) return

  const nextScripts = moveItemBeforeIndex(scripts, from, insertBefore)
  const nextVideos =
    videos.length === nextScripts.length ? moveItemBeforeIndex(videos, from, insertBefore) : videos
  const nextDubbing =
    dubbing.length === nextScripts.length ? moveItemBeforeIndex(dubbing, from, insertBefore) : dubbing

  useCreationStore.setState((previous) => ({
    formData: {
      ...previous.formData,
      storyboardScript: { ...previous.formData.storyboardScript, panels: nextScripts },
      storyboardVideo: { ...previous.formData.storyboardVideo, panels: nextVideos },
      dubbing: { ...previous.formData.dubbing, panels: nextDubbing }
    }
  }))
  onChange(nextVideos)

  if (nextScripts.every((panel) => workbench.parseServerStoryboardId(panel.id) != null)) {
    try {
      await workbench.sortRemoteToMatchPanels(nextScripts)
    } catch (error: unknown) {
      message.warning(`排序同步失败：${getErrorMessage(error)}`)
    }
  }
}
