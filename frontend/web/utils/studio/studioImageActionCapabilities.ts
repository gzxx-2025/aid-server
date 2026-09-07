import type { StoryboardPanel } from '@/types'
import type { StudioCanvasNode } from '@/types/studio'
import { isStoryboardImageSelected } from '@/utils/storyboardImageCover'

export interface StudioStoryboardImageBinding {
  storyboardId: number | null
  genRecordId: number | null
}

export function resolveStudioStoryboardImageBinding(
  node: StudioCanvasNode,
  panels: StoryboardPanel[]
): StudioStoryboardImageBinding {
  const storyboardId = positiveNumber(node.data.storyboardId)
  const explicitRecordId = positiveNumber(
    node.data.genRecordId ?? node.data.recordId ?? node.data.imageId
  )
  if (!storyboardId || explicitRecordId) {
    return { storyboardId, genRecordId: explicitRecordId }
  }

  const panel = panels.find((item) => Number(item.id) === storyboardId)
  const images = Array.isArray(panel?.images) ? panel.images : []
  const matching = images.find((item) => String(item?.url || item?.thumbnail || '').trim() === node.data.mediaUrl)
  const selected = images.find((item) => isStoryboardImageSelected(item))
  return {
    storyboardId,
    genRecordId: serverRecordId(matching ?? selected)
  }
}

export function studioImageActionDisabledReason(input: {
  node: StudioCanvasNode
  binding: StudioStoryboardImageBinding
  requiresRecord?: boolean
  modelAvailable?: boolean
}): string | null {
  if (!input.node.data.mediaUrl) return '请先上传或生成源图片'
  if (!input.binding.storyboardId) {
    return '仅绑定到真实分镜的数据节点可提交该任务'
  }
  if (input.requiresRecord && !input.binding.genRecordId) {
    return '当前图片缺少有效生成记录，无法提交高清任务'
  }
  if (input.modelAvailable === false) return '对应功能模型池暂无可用模型'
  return null
}

function serverRecordId(image: unknown): number | null {
  if (!image || typeof image !== 'object') return null
  const candidate = image as {
    id?: unknown
    _fromServer?: unknown
    _serverRow?: { id?: unknown }
  }
  const fromRow = positiveNumber(candidate._serverRow?.id)
  if (fromRow) return fromRow
  if (candidate._fromServer) return positiveNumber(candidate.id)
  return null
}

function positiveNumber(value: unknown): number | null {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : null
}
