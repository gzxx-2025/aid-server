import {
  collectReferenceVideoRecordIds,
  type ReferenceMediaItem
} from '~/utils/referenceMediaItem'
import {
  collectPromptVideoAssetsFromMedia,
  formatVideoApiPlaceholder,
  type PromptAssetItem
} from '~/utils/storyboardPromptAssetRef'

export function referenceVideoIdentityKey(video: ReferenceMediaItem): string {
  return String(video.referenceVideoRecordId || video.id || video.url || video.name || '')
}

export function mergeReferenceVideoLists(
  existing: ReferenceMediaItem[],
  incoming: ReferenceMediaItem[]
): ReferenceMediaItem[] {
  const merged = [...(existing || [])]
  const seen = new Set(merged.map(referenceVideoIdentityKey))
  for (const video of incoming || []) {
    const key = referenceVideoIdentityKey(video)
    if (!key || seen.has(key)) continue
    seen.add(key)
    merged.push(video)
  }
  return merged
}

export function collectNewlyAddedPromptVideoAssets(
  previous: ReferenceMediaItem[],
  merged: ReferenceMediaItem[]
): PromptAssetItem[] {
  const previousKeys = new Set((previous || []).map(referenceVideoIdentityKey).filter(Boolean))
  return collectPromptVideoAssetsFromMedia(merged).filter((_, index) => {
    const media = merged[index]
    return !!media && !previousKeys.has(referenceVideoIdentityKey(media))
  })
}

export function syncVideoPlaceholdersIntoPrompt(
  plain: string,
  videos: ReferenceMediaItem[]
): string {
  let next = String(plain || '').trim()
  const all = collectPromptVideoAssetsFromMedia(videos)
  for (const asset of all) {
    const placeholder = formatVideoApiPlaceholder(asset.imageIndex, asset.name)
    if (next.includes(placeholder)) continue
    next = `${next}${next ? '\n' : ''}${placeholder}`
  }
  return next
}

export function removeVideoFromPromptAndList(
  plain: string,
  videos: ReferenceMediaItem[],
  index: number
): { plain: string; videos: ReferenceMediaItem[] } {
  const target = videos[index]
  if (!target) return { plain, videos }
  const assets = collectPromptVideoAssetsFromMedia(videos)
  const targetPlaceholder = assets[index]
    ? formatVideoApiPlaceholder(assets[index]!.imageIndex, assets[index]!.name)
    : ''
  const nextVideos = videos.filter((_, itemIndex) => itemIndex !== index)
  let nextPlain = targetPlaceholder
    ? String(plain || '').replace(targetPlaceholder, '')
    : String(plain || '')
  const nextAssets = collectPromptVideoAssetsFromMedia(nextVideos)
  assets.forEach((asset, oldIndex) => {
    if (oldIndex === index) return
    const nextIndex = oldIndex < index ? oldIndex : oldIndex - 1
    const nextAsset = nextAssets[nextIndex]
    if (!nextAsset) return
    nextPlain = nextPlain.replace(
      formatVideoApiPlaceholder(asset.imageIndex, asset.name),
      formatVideoApiPlaceholder(nextAsset.imageIndex, nextAsset.name)
    )
  })
  return {
    plain: nextPlain.replace(/\n{3,}/g, '\n\n').trim(),
    videos: nextVideos
  }
}

export function buildGenerateReferenceVideoFields(videos: ReferenceMediaItem[]): {
  referenceVideoRecordIds?: number[]
} {
  const ids = collectReferenceVideoRecordIds(videos)
  return ids.length ? { referenceVideoRecordIds: ids } : {}
}
