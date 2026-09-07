import type { StudioCanvasNode } from '@/types/studio'

/**
 * 流程画布可见节点：隐藏步骤控制占位、空剧本与空成片，仅展示有内容的业务节点。
 */
export function isStudioFlowCanvasVisibleNode(node: StudioCanvasNode): boolean {
  const binding = node.data.flowBinding
  if (!binding) return true
  if (binding.role === 'step') return false
  if (binding.step === 'preview' && !String(node.data.mediaUrl || '').trim()) return false
  if (binding.entityType === 'story_script') {
    const text = String(node.data.prompt || node.data.resultSummary || '').trim()
    if (!text) return false
  }
  return true
}

export function filterStudioFlowCanvasVisibleNodes(nodes: StudioCanvasNode[]): StudioCanvasNode[] {
  return nodes.filter(isStudioFlowCanvasVisibleNode)
}
