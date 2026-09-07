import type { StudioFlowStep } from '@/types/studio'
import { selectStudioFlowStepNode } from '@/utils/studio/studioFlowNodeSelection'
import { requestStudioFlowAdvancedModal } from '@/utils/studio/studioFlowAdvancedModal'

/** @deprecated 抽屉嵌页已移除；保留 API 以兼容旧调用，行为改为选中步骤控制节点。 */
export const STUDIO_FLOW_OPEN_EDITOR_EVENT = 'studio-flow-open-editor'

export interface StudioFlowOpenEditorDetail {
  step: StudioFlowStep
  remount?: boolean
}

export function nextStudioFlowEditorMountRevision(current: number, remount?: boolean): number {
  return remount ? current + 1 : current
}

/** 打开步骤编辑器：项目配置走高级 Modal；其它步骤选中可见节点并尝试打开编辑器。 */
export function requestStudioFlowEditor(step: StudioFlowStep, options?: { remount?: boolean }): void {
  if (typeof window === 'undefined') return
  if (step === 'global-setting') {
    requestStudioFlowAdvancedModal({ kind: 'global-setting' })
    return
  }
  selectStudioFlowStepNode(step)
  window.dispatchEvent(new CustomEvent<StudioFlowOpenEditorDetail>(STUDIO_FLOW_OPEN_EDITOR_EVENT, {
    detail: { step, remount: options?.remount }
  }))
}
