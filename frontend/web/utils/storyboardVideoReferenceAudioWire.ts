/**
 * 编辑分镜视频弹窗：参考音频选用结果拆分与出片字段拼装
 */
import {
collectReferenceAudioIds,
type ReferenceMediaItem
} from '~/utils/referenceMediaItem'
import {
collectPromptAudioAssetsFromMedia,
type PromptAssetItem
} from '~/utils/storyboardPromptAssetRef'
import { appendAudioPlaceholderIfMissing,removeAudioPlaceholderByName } from '~/utils/storyboardPromptAudioRef'

export function isAudioMediaItem(item: unknown): item is ReferenceMediaItem {
  const x = item as ReferenceMediaItem
  return x?.kind === 'audio' || x?.audioSource === 'voice_sample' || x?.audioSource === 'upload'
}

export function referenceAudioIdentityKey(a: ReferenceMediaItem): string {
  return String(a.referenceAudioId || a.id || a.url || a.name || '')
}

/** 弹窗 confirm 结果拆成图片项与音频项 */
export function splitReferenceConfirmItems(items: unknown[]): {
  images: any[]
  audios: ReferenceMediaItem[]
} {
  const images: any[] = []
  const audios: ReferenceMediaItem[] = []
  for (const item of items || []) {
    if (isAudioMediaItem(item)) audios.push(item as ReferenceMediaItem)
    else images.push(item)
  }
  return { images, audios }
}

export function mergeReferenceAudioLists(
  prev: ReferenceMediaItem[],
  next: ReferenceMediaItem[]
): ReferenceMediaItem[] {
  const out = [...(prev || [])]
  const seen = new Set(out.map(referenceAudioIdentityKey))
  for (const a of next || []) {
    const k = referenceAudioIdentityKey(a)
    if (!k || seen.has(k)) continue
    seen.add(k)
    out.push(a)
  }
  return out
}

/** 合并后相对 prev 新增的音频，转成可在光标处插入的 PromptAsset（序号按 merged 列表） */
export function collectNewlyAddedPromptAudioAssets(
  prev: ReferenceMediaItem[],
  merged: ReferenceMediaItem[]
): PromptAssetItem[] {
  const prevKeys = new Set(
    (prev || []).map(referenceAudioIdentityKey).filter(Boolean)
  )
  const all = collectPromptAudioAssetsFromMedia(merged || [])
  return all.filter((_, i) => {
    const media = merged[i]
    if (!media) return false
    const k = referenceAudioIdentityKey(media)
    return Boolean(k) && !prevKeys.has(k)
  })
}

/** 把官方/上传音频名同步进提示词占位 */
export function syncAudioPlaceholdersIntoPrompt(
  plain: string,
  audios: ReferenceMediaItem[]
): string {
  let next = String(plain || '')
  for (const a of audios || []) {
    if (a.kind !== 'audio') continue
    next = appendAudioPlaceholderIfMissing(next, a.name)
  }
  return next
}

export function removeAudioFromPromptAndList(
  plain: string,
  audios: ReferenceMediaItem[],
  index: number
): { plain: string; audios: ReferenceMediaItem[] } {
  const target = audios[index]
  if (!target) return { plain, audios }
  const nextAudios = audios.filter((_, i) => i !== index)
  return {
    plain: removeAudioPlaceholderByName(plain, target.name),
    audios: nextAudios
  }
}

/** 单镜头出片：仅上传项进 referenceAudioIds */
export function buildGenerateReferenceAudioFields(audios: ReferenceMediaItem[]): {
  referenceAudioIds?: number[]
} {
  const ids = collectReferenceAudioIds(audios)
  return ids.length ? { referenceAudioIds: ids } : {}
}
