import { useCreationStore } from '~/stores/creation'
import type { DubbingPanel,StoryboardPanel,StoryboardVideoPanel } from '~/types'

/** 列表 / 卡片两种视图共享的回调与派生数据（由 Dubbing.tsx 统一下发） */
export interface DubbingViewSharedProps {
  panels: DubbingPanel[]
  hoverIndex: number | null
  onHoverIndexChange: (v: number | null) => void
  displayPanelTitle: (panel: DubbingPanel, index: number) => string
  isDubbingBatchGenerating: (index: number) => boolean
  hasVideoForIndex: (index: number) => boolean
  getVideoUrlForIndex: (index: number) => string
  getRenderedDialogue: (index: number) => string
  onGoStep: (stepIndex: number) => void
  onEditDubbing: (index: number) => void
  onCopyPanel: (index: number) => void
  onRemovePanel: (index: number) => void
  onPreviewDubbingVideo: (index: number) => void
  goToStoryboardVideo: () => void
}

/**
 * Zustand 适配（对齐 useCreateFlowStoryboardSync 内同名私有工具）：
 * 原 Vue 直接对 creationStore.formData.*.panels 赋值；formData 为嵌套对象，
 * 写 panels 必须整分支不可变替换（一次 setState 覆盖多个面板字段）。
 */
export function setDubbingStepFormPanels(next: {
  script?: StoryboardPanel[]
  video?: StoryboardVideoPanel[]
  dubbing?: DubbingPanel[]
}): void {
  useCreationStore.setState((s) => ({
    formData: {
      ...s.formData,
      ...(next.script !== undefined
        ? { storyboardScript: { ...s.formData.storyboardScript, panels: next.script } }
        : {}),
      ...(next.video !== undefined
        ? { storyboardVideo: { ...s.formData.storyboardVideo, panels: next.video } }
        : {}),
      ...(next.dubbing !== undefined
        ? { dubbing: { ...s.formData.dubbing, panels: next.dubbing } }
        : {})
    }
  }))
}
