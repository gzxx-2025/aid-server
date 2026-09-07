import type { StudioCanvasNode } from '@/types/studio'

export type StudioFlowBatchIntent =
  | 'batch-rps-image'
  | 'batch-storyboard-image'
  | 'batch-storyboard-video'
  | 'batch-delete-storyboard'
  | 'batch-dubbing'

export const STUDIO_FLOW_BATCH_INTENT_LABELS: Record<StudioFlowBatchIntent, string> = {
  'batch-rps-image': '批量形态图',
  'batch-storyboard-image': '批量分镜图',
  'batch-storyboard-video': '批量分镜视频',
  'batch-delete-storyboard': '批量删除分镜',
  'batch-dubbing': '批量配音'
}

const BATCH_INTENT_ORDER: StudioFlowBatchIntent[] = [
  'batch-rps-image',
  'batch-storyboard-image',
  'batch-storyboard-video',
  'batch-delete-storyboard',
  'batch-dubbing'
]

function isFlowItemNode(node: StudioCanvasNode): boolean {
  return node.data.flowBinding?.role === 'item'
}

function nodeMatchesBatchIntent(node: StudioCanvasNode, intent: StudioFlowBatchIntent): boolean {
  const binding = node.data.flowBinding
  if (!binding || binding.role !== 'item') return false
  switch (intent) {
    case 'batch-rps-image':
      return binding.entityType === 'rps_asset' && binding.step === 'scene-character'
    case 'batch-storyboard-image':
      return binding.entityType === 'storyboard' && (
        node.data.kind === 'image' || String(binding.bindingKey).startsWith('flow:storyboard-image:')
      )
    case 'batch-delete-storyboard':
      return binding.entityType === 'storyboard' && node.data.kind === 'storyboard_script'
    case 'batch-storyboard-video':
      return binding.entityType === 'storyboard_video' && binding.step === 'storyboard-video'
    case 'batch-dubbing':
      return binding.entityType === 'dubbing' && binding.step === 'dubbing'
    default:
      return false
  }
}

export type StudioFlowBatchSelectionResult =
  | { ok: true; nodes: StudioCanvasNode[]; intent: StudioFlowBatchIntent }
  | { ok: false; reason: string; invalidIds: string[] }

export function validateFlowBatchSelection(
  nodes: StudioCanvasNode[],
  intent: StudioFlowBatchIntent
): StudioFlowBatchSelectionResult {
  if (nodes.length < 2) {
    return { ok: false, reason: '批量任务至少需要选择 2 个节点', invalidIds: [] }
  }
  const invalidIds = nodes.filter((node) => !nodeMatchesBatchIntent(node, intent)).map((node) => node.id)
  if (invalidIds.length) {
    return {
      ok: false,
      reason: `选区与「${STUDIO_FLOW_BATCH_INTENT_LABELS[intent]}」不匹配，请只选择同类流程节点`,
      invalidIds
    }
  }
  return { ok: true, nodes, intent }
}

export function inferStudioFlowBatchIntent(nodes: StudioCanvasNode[]): StudioFlowBatchIntent | null {
  if (nodes.length < 2) return null
  for (const intent of BATCH_INTENT_ORDER) {
    const result = validateFlowBatchSelection(nodes, intent)
    if (result.ok) return intent
  }
  return null
}

export function resolveStudioFlowBatchSelection(nodes: StudioCanvasNode[]): StudioFlowBatchSelectionResult | null {
  const intent = inferStudioFlowBatchIntent(nodes)
  if (!intent) {
    if (nodes.length < 2) return null
    return {
      ok: false,
      reason: '当前多选包含不同类型的流程节点，无法批量处理',
      invalidIds: nodes.filter((node) => !isFlowItemNode(node)).map((node) => node.id)
    }
  }
  return validateFlowBatchSelection(nodes, intent)
}

export function studioFlowBatchIntentHint(intent: StudioFlowBatchIntent): string {
  switch (intent) {
    case 'batch-rps-image':
      return '将为所选形态批量生成参考图'
    case 'batch-storyboard-image':
      return '将为所选分镜批量生成分镜图'
    case 'batch-storyboard-video':
      return '将为所选分镜视频节点批量生成视频'
    case 'batch-delete-storyboard':
      return '将批量删除所选分镜（需二次确认）'
    case 'batch-dubbing':
      return '将为所选配音节点批量提交生成'
    default:
      return ''
  }
}
