import type { StudioCanvasNode } from '@/types/studio'
import { skipsStoryboardImageGeneration } from '@/utils/creationModeUiRules'
import { isStudioFlowStoryboardStillNode } from '@/utils/studio/studioFlowStoryboardMedia'
import {
  studioFlowAutoPipelineStartIndex,
  studioFlowAutoPipelineStages,
  type StudioFlowAutoPipelineStage
} from '@/utils/studio/studioFlowAutoPipelineRunner'

export interface StudioFlowAutoPipelineProgressInput {
  nodes: StudioCanvasNode[]
  creationMode?: string | null
}

function boundNodes(nodes: StudioCanvasNode[]): StudioCanvasNode[] {
  return nodes.filter((node) => Boolean(node.data.flowBinding))
}

function rpsAssetNodes(nodes: StudioCanvasNode[]): StudioCanvasNode[] {
  return boundNodes(nodes).filter((node) => node.data.flowBinding?.entityType === 'rps_asset')
}

function storyboardScriptNodes(nodes: StudioCanvasNode[]): StudioCanvasNode[] {
  return boundNodes(nodes).filter((node) =>
    node.data.kind === 'storyboard_script'
    && node.data.flowBinding?.entityType === 'storyboard'
    && !isStudioFlowStoryboardStillNode(node.data)
  )
}

function storyboardVideoNodes(nodes: StudioCanvasNode[]): StudioCanvasNode[] {
  return boundNodes(nodes).filter((node) =>
    node.data.kind === 'video'
    && node.data.flowBinding?.step === 'storyboard-video'
  )
}

function dubbingNodes(nodes: StudioCanvasNode[]): StudioCanvasNode[] {
  return boundNodes(nodes).filter((node) => node.data.flowBinding?.step === 'dubbing')
}

function hasText(value: unknown): boolean {
  return Boolean(String(value ?? '').trim())
}

function hasMedia(node: StudioCanvasNode): boolean {
  return hasText(node.data.mediaUrl)
}

/** 各流水线阶段是否已在画布业务节点上完成（含手动生成）。 */
export function isStudioFlowAutoPipelineStageComplete(
  stage: StudioFlowAutoPipelineStage,
  input: StudioFlowAutoPipelineProgressInput
): boolean {
  if (stage === 'done') return true
  const { nodes, creationMode } = input

  if (stage === 'extract') {
    return rpsAssetNodes(nodes).length > 0
  }

  if (stage === 'form-text') {
    const assets = rpsAssetNodes(nodes)
    if (!assets.length) return false
    return assets.every((node) =>
      (node.data.flowAssetForms?.length ?? 0) > 0 || hasMedia(node)
    )
  }

  if (stage === 'form-image') {
    const assets = rpsAssetNodes(nodes)
    if (!assets.length) return false
    return assets.every((node) => hasMedia(node))
  }

  if (stage === 'storyboard-script') {
    const scripts = storyboardScriptNodes(nodes)
    if (!scripts.length) return false
    return scripts.every((node) =>
      hasText(node.data.prompt) || hasText(node.data.resultSummary)
    )
  }

  if (stage === 'storyboard-image') {
    if (skipsStoryboardImageGeneration(creationMode)) return true
    const stills = boundNodes(nodes).filter((node) => isStudioFlowStoryboardStillNode(node.data))
    if (!stills.length) return false
    return stills.every((node) => hasMedia(node))
  }

  if (stage === 'storyboard-video') {
    const videos = storyboardVideoNodes(nodes)
    if (!videos.length) return false
    return videos.every((node) => hasMedia(node))
  }

  if (stage === 'dubbing') {
    const dubs = dubbingNodes(nodes)
    if (!dubs.length) return false
    return dubs.every((node) => node.data.status === 'success' || hasMedia(node))
  }

  return false
}

/**
 * 按画布节点推导「下一个未完成阶段」。
 * 全部完成返回 null（对应 done / 可清除续跑点）。
 */
export function resolveStudioFlowAutoPipelineNextStage(
  input: StudioFlowAutoPipelineProgressInput
): StudioFlowAutoPipelineStage | null {
  const stages = studioFlowAutoPipelineStages(input.creationMode)
  for (const stage of stages) {
    if (!isStudioFlowAutoPipelineStageComplete(stage, input)) return stage
  }
  return null
}

/**
 * 将 localStorage 中的中断续跑点与画布真实进度对齐。
 * - 无历史中断记录时不凭空制造续跑态
 * - 中断阶段已在画布完成时推进到下一未完成阶段
 * - 全部完成时返回 null（清除续跑）
 */
export function reconcileStudioFlowAutoPipelineResume(
  storedResume: StudioFlowAutoPipelineStage | null,
  input: StudioFlowAutoPipelineProgressInput
): StudioFlowAutoPipelineStage | null {
  if (!storedResume || storedResume === 'done') return null
  const stages = studioFlowAutoPipelineStages(input.creationMode)
  const startIndex = studioFlowAutoPipelineStartIndex(stages, storedResume)
  for (let index = startIndex; index < stages.length; index += 1) {
    const stage = stages[index]
    if (!isStudioFlowAutoPipelineStageComplete(stage, input)) return stage
  }
  return null
}
