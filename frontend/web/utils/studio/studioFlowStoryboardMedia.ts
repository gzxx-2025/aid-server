import type { StudioCanvasNode, StudioNodeData } from '@/types/studio'

export const STUDIO_FLOW_GENERATE_VIDEO_EVENT = 'studio:generate-flow-video'
export const STUDIO_FLOW_GENERATE_STORYBOARD_IMAGE_EVENT = 'studio:generate-flow-storyboard-image'

export function isStudioFlowStoryboardStillNode(data: Pick<StudioNodeData, 'kind' | 'imagePurpose' | 'flowBinding'>): boolean {
  const key = data.flowBinding?.bindingKey ?? ''
  if (key.startsWith('flow:storyboard-image:')) return true
  return data.kind === 'image' && data.imagePurpose === 'storyboard' && data.flowBinding?.entityType === 'storyboard'
}

function sameStoryboardId(node: Pick<StudioCanvasNode, 'data'>, storyboardId: string): boolean {
  return String(node.data.storyboardId || node.data.flowBinding?.serverId || '').trim() === storyboardId
}

export function resolvePairedStoryboardImageUrl(
  videoNode: Pick<StudioCanvasNode, 'data'>,
  nodes: StudioCanvasNode[]
): string | undefined {
  const sid = String(videoNode.data.storyboardId || videoNode.data.flowBinding?.serverId || '').trim()
  if (!sid) return undefined
  const still = nodes.find((node) => isStudioFlowStoryboardStillNode(node.data) && sameStoryboardId(node, sid))
  if (still?.data.mediaUrl?.trim()) return still.data.mediaUrl.trim()
  const fused = nodes.find((node) =>
    node.data.flowBinding?.entityType === 'storyboard'
    && sameStoryboardId(node, sid)
    && Boolean(node.data.mediaUrl?.trim())
  )
  return fused?.data.mediaUrl?.trim() || undefined
}

export function dispatchStudioFlowGenerateVideo(nodeId: string): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent(STUDIO_FLOW_GENERATE_VIDEO_EVENT, { detail: { nodeId } }))
}

export function dispatchStudioFlowGenerateStoryboardImage(nodeId: string): void {
  if (typeof window === 'undefined') return
  pendingStoryboardImageNodeId = nodeId
  window.dispatchEvent(new CustomEvent(STUDIO_FLOW_GENERATE_STORYBOARD_IMAGE_EVENT, { detail: { nodeId } }))
}

let pendingStoryboardImageNodeId = ''

export function consumePendingStudioFlowStoryboardImageGenerate(nodeId: string): boolean {
  if (pendingStoryboardImageNodeId !== nodeId) return false
  pendingStoryboardImageNodeId = ''
  return true
}
