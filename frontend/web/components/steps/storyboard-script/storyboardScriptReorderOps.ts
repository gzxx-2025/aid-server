import type { MutableRefObject } from 'react'
import { message } from 'antd'
import { useCreationStore } from '~/stores/creation'
import type { useStoryboardWorkbenchMutations } from '~/composables/useStoryboardWorkbenchMutations'
import { moveItemBeforeIndex } from '~/utils/moveItemBeforeIndex'
import type { DubbingPanel, StoryboardVideoPanel } from '~/types'
import { storyboardApiErr, type StoryboardPanel } from './storyboardScriptShared'

type StoryboardWorkbench = ReturnType<typeof useStoryboardWorkbenchMutations>

export function createStoryboardScriptReorderOps(
  panelsRef: MutableRefObject<StoryboardPanel[]>,
  onChangeRef: MutableRefObject<(panels: StoryboardPanel[]) => void>,
  workbench: StoryboardWorkbench
) {
  function setStoryboardFormPanels(next: {
    script?: StoryboardPanel[]
    video?: StoryboardVideoPanel[]
    dubbing?: DubbingPanel[]
  }) {
    useCreationStore.setState((state) => ({
      formData: {
        ...state.formData,
        ...(next.script !== undefined
          ? { storyboardScript: { ...state.formData.storyboardScript, panels: next.script } }
          : {}),
        ...(next.video !== undefined
          ? { storyboardVideo: { ...state.formData.storyboardVideo, panels: next.video } }
          : {}),
        ...(next.dubbing !== undefined
          ? { dubbing: { ...state.formData.dubbing, panels: next.dubbing } }
          : {})
      }
    }))
  }

  async function applyShotReorder(from: number, insertBefore: number) {
    const store = useCreationStore.getState()
    const scriptPanels = [...(store.formData.storyboardScript.panels as StoryboardPanel[])]
    if (scriptPanels.length <= 1) return
    const nextScriptPanels = moveItemBeforeIndex(scriptPanels, from, insertBefore)
    const videoPanels = [...(store.formData.storyboardVideo.panels as StoryboardVideoPanel[])]
    const dubbingPanels = [...(store.formData.dubbing.panels as DubbingPanel[])]
    const nextVideoPanels =
      videoPanels.length === nextScriptPanels.length
        ? moveItemBeforeIndex(videoPanels, from, insertBefore)
        : videoPanels
    const nextDubbingPanels =
      dubbingPanels.length === nextScriptPanels.length
        ? moveItemBeforeIndex(dubbingPanels, from, insertBefore)
        : dubbingPanels
    setStoryboardFormPanels({
      script: nextScriptPanels,
      video: nextVideoPanels,
      dubbing: nextDubbingPanels
    })
    onChangeRef.current(nextScriptPanels)
    if (
      nextScriptPanels.length > 0 &&
      nextScriptPanels.every((panel) => workbench.parseServerStoryboardId(panel.id) != null)
    ) {
      try {
        await workbench.sortRemoteToMatchPanels(nextScriptPanels)
      } catch (error: unknown) {
        message.warning(`排序同步失败：${storyboardApiErr(error)}`)
      }
    }
  }

  async function onShotListDragChange(from: number, to: number) {
    await applyShotReorder(from, from < to ? to + 1 : to)
  }

  return { setStoryboardFormPanels, applyShotReorder, onShotListDragChange }
}
