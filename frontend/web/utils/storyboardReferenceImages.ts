import type { StoryboardReferenceImageSnapshot } from '~/types/business-api'

/** 将列表接口 referenceImages 规范为前端可用结构（兼容历史 string[]） */
export function normalizeStoryboardReferenceImages(
  raw: unknown
): StoryboardReferenceImageSnapshot[] {
  if (!Array.isArray(raw)) return []
  return raw
    .map((item) => {
      if (typeof item === 'string') {
        const url = item.trim()
        return url ? ({ url, type: 'REFERENCE' } as StoryboardReferenceImageSnapshot) : null
      }
      if (!item || typeof item !== 'object') return null
      const row = item as StoryboardReferenceImageSnapshot
      const url = String(row.url ?? '').trim()
      return {
        n: row.n,
        name: row.name,
        assetKind: row.assetKind ?? null,
        assetName: row.assetName ?? null,
        url: url || null,
        type: row.type ?? null
      } satisfies StoryboardReferenceImageSnapshot
    })
    .filter((item): item is StoryboardReferenceImageSnapshot => item != null)
}

/** 可预览的参考图（有完整 URL） */
export function getPreviewableStoryboardReferenceImages(
  images: StoryboardReferenceImageSnapshot[] | null | undefined
): StoryboardReferenceImageSnapshot[] {
  return (images ?? []).filter((img) => String(img.url ?? '').trim())
}

export function resolveStoryboardReferenceImageTitle(
  img: StoryboardReferenceImageSnapshot
): string {
  return String(img.name ?? '').trim() || String(img.assetName ?? '').trim() || '参考图'
}
