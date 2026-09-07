import type { StoryboardPanel } from '@/types'
import type { StudioCanvasNode } from '@/types/studio'
import { parseServerStoryboardId } from '@/hooks/useStoryboardWorkbenchMutations'

export function collectStoryboardPanelsForFlowNodes(
  nodes: StudioCanvasNode[],
  panels: StoryboardPanel[]
): StoryboardPanel[] {
  const serverIds = new Set(
    nodes
      .map((node) => node.data.flowBinding?.serverId)
      .filter((id): id is number => typeof id === 'number' && id > 0)
  )
  return panels.filter((panel) => {
    const storyboardId = parseServerStoryboardId(panel.id)
    return storyboardId != null && serverIds.has(storyboardId)
  })
}

export function collectFormIdsForRpsFlowNodes(
  nodes: StudioCanvasNode[],
  options?: { missingImageOnly?: boolean }
): number[] {
  const formIds = new Set<number>()
  for (const node of nodes) {
    for (const form of node.data.flowAssetForms ?? []) {
      if (!Number.isFinite(form.formId) || form.formId <= 0) continue
      if (options?.missingImageOnly && String(form.selectedImageUrl ?? '').trim()) continue
      formIds.add(form.formId)
    }
  }
  return [...formIds]
}

export function collectStoryboardIdsForFlowNodes(nodes: StudioCanvasNode[]): number[] {
  return nodes
    .map((node) => node.data.flowBinding?.serverId)
    .filter((id): id is number => typeof id === 'number' && id > 0)
}
