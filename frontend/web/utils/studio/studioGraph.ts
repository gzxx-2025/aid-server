import { MarkerType, type XYPosition } from '@xyflow/react'
import { STUDIO_NODE_META } from '@/config/studioMeta'
import type {
  StudioCanvasNode,
  StudioEdgeKind,
  StudioFlowEdge,
  StudioMediaKind,
  StudioNodeData,
  StudioNodeKind
} from '@/types/studio'

let fallbackId = 0

export function studioId(prefix: string): string {
  const randomId = globalThis.crypto?.randomUUID?.()
  if (randomId) return `${prefix}-${randomId}`
  fallbackId += 1
  return `${prefix}-${Date.now()}-${fallbackId}`
}

export function createStudioNode(
  kind: StudioNodeKind,
  position: XYPosition,
  options: Partial<StudioNodeData> = {}
): StudioCanvasNode {
  const now = new Date().toISOString()
  const meta = STUDIO_NODE_META[kind]
  return {
    id: studioId(kind),
    type: 'studioNode',
    position,
    zIndex: 2,
    data: {
      kind,
      title: options.title ?? meta.label,
      subtitle: options.subtitle,
      zoneId: options.zoneId,
      status: options.status ?? 'empty',
      prompt: options.prompt ?? meta.defaultPrompt,
      imagePurpose: options.imagePurpose ?? (kind === 'image' ? 'generic' : undefined),
      composerAgentOpen: options.composerAgentOpen,
      composerSkillId: options.composerSkillId,
      composerRefs: options.composerRefs,
      negativePrompt: options.negativePrompt,
      model: options.model,
      modelName: options.modelName,
      modelCostCredits: options.modelCostCredits,
      modelIsFree: options.modelIsFree,
      recommendedDurationSeconds: options.recommendedDurationSeconds,
      recommendedDurationSource: options.recommendedDurationSource,
      recommendedDurationDescription: options.recommendedDurationDescription,
      composerAgentCode: options.composerAgentCode,
      composerAgentName: options.composerAgentName,
      mediaKind: options.mediaKind ?? defaultMediaKindForKind(kind),
      generationOptions: options.generationOptions ?? {
        aspectRatio: '16:9',
        duration: 5,
        quality: kind === 'video' ? '1080p' : '2k',
        count: 1,
        videoMode: 'imageToVideo',
        endFrameEnabled: false
      },
      mediaUrl: options.mediaUrl,
      resultSummary: options.resultSummary,
      storyboardRows: options.storyboardRows,
      storyboardAssets: options.storyboardAssets,
      storyboardWorkflowStep: options.storyboardWorkflowStep,
      storyboardPromptMode: options.storyboardPromptMode,
      storyboardPromptTaskId: options.storyboardPromptTaskId,
      storyboardPromptTaskStatus: options.storyboardPromptTaskStatus,
      storyboardPromptTaskRowIds: options.storyboardPromptTaskRowIds,
      errorMessage: options.errorMessage,
      storyboardId: options.storyboardId,
      assetId: options.assetId,
      bindingKey: options.bindingKey,
      flowBinding: options.flowBinding,
      flowAssetForms: options.flowAssetForms,
      flowLayoutOnly: options.flowLayoutOnly,
      createdAt: options.createdAt ?? now,
      updatedAt: options.updatedAt ?? now
    }
  }
}

export function createStudioEdge(
  source: string,
  target: string,
  kind: StudioEdgeKind = 'flow'
): StudioFlowEdge {
  const color = edgeColor(kind)
  return {
    id: studioId('edge'),
    source,
    target,
    type: 'studioEnergy',
    markerEnd: { type: MarkerType.ArrowClosed, color },
    style: { stroke: color, strokeWidth: 1.35, opacity: 0.78 },
    data: { kind, label: edgeLabel(kind) }
  }
}

export function edgeColor(kind: StudioEdgeKind): string {
  if (kind === 'reference') return '#85809f'
  if (kind === 'sequence') return '#9a8d73'
  return '#718a9c'
}

export function edgeLabel(kind: StudioEdgeKind): string {
  if (kind === 'reference') return '引用'
  if (kind === 'sequence') return '顺序'
  return '流程'
}

export function defaultMediaKindForKind(kind: StudioNodeKind): StudioMediaKind {
  if (kind === 'video') return 'video'
  if (kind === 'voice') return 'audio'
  if (kind === 'image') return 'image'
  return 'text'
}
