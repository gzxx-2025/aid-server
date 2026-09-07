import type { StudioCanvasNode } from '@/types/studio'

export interface StudioFlowDeliveryExportState {
  exportStatus: number | null | undefined
  finalVideoUrl: string | null | undefined
  pendingVideoUrl: string | null | undefined
}

/** 画布是否具备交付入口（成品 URL 或 preview 节点就绪）。 */
export function isStudioFlowDeliveryReady(
  nodes: StudioCanvasNode[],
  exportState: StudioFlowDeliveryExportState
): boolean {
  if (
    exportState.exportStatus === 2 ||
    Boolean(String(exportState.finalVideoUrl || '').trim()) ||
    Boolean(String(exportState.pendingVideoUrl || '').trim())
  ) {
    return true
  }
  return nodes.some(
    (node) =>
      node.data.flowBinding?.step === 'preview' && Boolean(node.data.mediaUrl)
  )
}

export function hasStudioFlowPreviewStepSelected(
  nodes: StudioCanvasNode[],
  selectedNodeIds: string[]
): boolean {
  if (!selectedNodeIds.length) return false
  const selected = new Set(selectedNodeIds)
  return nodes.some(
    (node) =>
      selected.has(node.id) && node.data.flowBinding?.step === 'preview'
  )
}
