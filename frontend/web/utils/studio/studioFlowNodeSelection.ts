import type { StudioCanvasNode, StudioFlowStep } from '@/types/studio'
import { useStudioUiStore } from '@/stores/studioUi'
import { isStudioFlowCanvasVisibleNode } from './studioFlowCanvasProjection'

export function findStudioFlowStepNode(
  step: StudioFlowStep,
  nodes: StudioCanvasNode[]
): StudioCanvasNode | undefined {
  const visibleItem = nodes.find((node) =>
    node.data.flowBinding?.role === 'item' &&
    node.data.flowBinding.step === step &&
    isStudioFlowCanvasVisibleNode(node)
  )
  if (visibleItem) return visibleItem
  const item = nodes.find((node) =>
    node.data.flowBinding?.role === 'item' && node.data.flowBinding.step === step
  )
  if (item) return item
  return nodes.find((node) =>
    node.data.flowBinding?.role === 'step' && node.data.flowBinding.step === step
  )
}

export function findStudioFlowNodeByBindingKey(
  bindingKey: string,
  nodes: StudioCanvasNode[]
): StudioCanvasNode | undefined {
  return nodes.find((node) => node.data.flowBinding?.bindingKey === bindingKey)
}

export function selectStudioFlowStepNode(step: StudioFlowStep): boolean {
  const node = findStudioFlowStepNode(step, useStudioUiStore.getState().nodes)
  if (!node) return false
  useStudioUiStore.getState().selectNodes([node.id])
  return true
}

export function selectStudioFlowNodeByBindingKey(bindingKey: string): boolean {
  const node = findStudioFlowNodeByBindingKey(bindingKey, useStudioUiStore.getState().nodes)
  if (!node) return false
  useStudioUiStore.getState().selectNodes([node.id])
  return true
}
