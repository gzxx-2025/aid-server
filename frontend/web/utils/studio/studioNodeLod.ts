import type { StudioCanvasNode, StudioNodeKind } from '@/types/studio'

export type StudioNodeLod = 'ghost' | 'compact' | 'full'

export function studioNodeLod(zoom: number): StudioNodeLod {
  if (zoom < 0.4) return 'ghost'
  if (zoom < 0.75) return 'compact'
  return 'full'
}

export const STUDIO_NODE_DEFAULT_DIMENSIONS: Record<StudioNodeKind, { width: number; height: number }> = {
  image: { width: 280, height: 220 },
  video: { width: 312, height: 232 },
  text: { width: 300, height: 300 },
  storyboard_script: { width: 300, height: 220 },
  voice: { width: 300, height: 180 }
}

const mediaRatioCache = new Map<string, number>()

export function rememberStudioMediaAspectRatio(mediaUrl: string, width: number, height: number): number | null {
  const key = mediaUrl.trim()
  if (!key || width <= 0 || height <= 0) return null
  const ratio = width / height
  if (!Number.isFinite(ratio) || ratio <= 0) return null
  mediaRatioCache.set(key, ratio)
  return ratio
}

export function studioNodeDimensions(
  node: Pick<StudioCanvasNode, 'data'>,
  intrinsicMediaAspectRatio?: number | null
): { width: number; height: number } {
  const base = STUDIO_NODE_DEFAULT_DIMENSIONS[node.data.kind]
  if (node.data.kind === 'text' || node.data.kind === 'storyboard_script') {
    return (node.data.prompt || node.data.resultSummary) ? { width: 300, height: 340 } : base
  }
  if (!node.data.mediaUrl) return base
  if (node.data.kind === 'image' || node.data.kind === 'video') {
    const ratio = intrinsicMediaAspectRatio ?? mediaRatioCache.get(node.data.mediaUrl.trim()) ?? 16 / 9
    const width = Math.min(420, Math.max(240, Math.sqrt(90_000 * ratio)))
    const height = Math.min(440, Math.max(180, width / ratio))
    return { width: Math.round(width), height: Math.round(height) }
  }
  return base
}
