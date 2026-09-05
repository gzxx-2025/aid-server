/** 多参/分镜：参考图条与描述框 @图片 引用的双向同步纯函数 */

import {
collectStoryboardPromptAssets,
normalizePromptAssetPlaceholderName,
promptAssetNamesMatch,
type PromptAssetItem
} from '~/utils/storyboardPromptAssetRef'
import { ensureAudioNamePrefix } from '~/utils/storyboardPromptAudioRef'

export type StripReferenceImage = {
  id?: string | number
  title?: string
  name?: string
  url?: string
  thumbnail?: string
}

export type StripReferenceAudio = StripReferenceImage & {
  referenceAudioId?: string | number
}

function stripAt(s: string): string {
  const t = String(s || '').trim()
  return t.startsWith('@') ? t.slice(1) : t
}

/** 参考图 / prompt 资产的稳定身份键（id 优先，否则 name） */
export function referenceImageIdentityKeys(img: StripReferenceImage): string[] {
  const keys: string[] = []
  const id = String(img?.id ?? '').trim()
  if (id) keys.push(`id:${id}`)
  const name = normalizePromptAssetPlaceholderName(img?.title || img?.name)
  if (name) keys.push(`name:${name}`)
  return keys
}

export function promptAssetIdentityKeys(asset: Pick<PromptAssetItem, 'assetId' | 'name'>): string[] {
  return referenceImageIdentityKeys({ id: asset.assetId, name: asset.name })
}

/** 从纯文本 `@图片N[name]` 占位提取身份键（无 DOM embed 时的兜底） */
export function extractPromptAssetRefIdentityKeysFromPlain(plain: string): Set<string> {
  const keys = new Set<string>()
  const text = String(plain || '')
  if (!text) return keys
  const re = /@图片\d+\[([^\]]+)\]/g
  let m: RegExpExecArray | null
  while ((m = re.exec(text))) {
    const name = stripAt(m[1] || '')
    if (name) keys.add(`name:${name}`)
  }
  return keys
}

/** 从描述框 HTML 提取已引用资产的身份键（embed + 纯文本占位） */
export function extractPromptAssetRefIdentityKeysFromHtml(html: string): Set<string> {
  const keys = new Set<string>()
  if (!html) return keys
  if (typeof DOMParser !== 'undefined') {
    try {
      const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html')
      doc.querySelectorAll('.scp-prompt-asset-ref').forEach((el) => {
        const node = el as HTMLElement
        const assetType = String(node.dataset.assetType || '').trim()
        if (assetType === 'audio') return
        for (const k of referenceImageIdentityKeys({
          id: node.dataset.assetId,
          name: node.dataset.name
        })) {
          keys.add(k)
        }
      })
    } catch {
      /* ignore */
    }
  }
  for (const k of extractPromptAssetRefIdentityKeysFromPlain(html)) {
    keys.add(k)
  }
  return keys
}

function normalizeAudioName(value: string): string {
  const raw = stripAt(String(value || '').trim())
  return raw ? ensureAudioNamePrefix(raw) : ''
}

/** 参考音频 / prompt 音频引用的稳定身份键（记录 ID 优先，同时保留名称兜底）。 */
export function referenceAudioIdentityKeys(audio: StripReferenceAudio): string[] {
  const keys: string[] = []
  const ids = [audio?.id, audio?.referenceAudioId]
    .map((value) => String(value ?? '').trim())
    .filter(Boolean)
  for (const id of new Set(ids)) keys.push(`audio-id:${id}`)
  const name = normalizeAudioName(String(audio?.title || audio?.name || ''))
  if (name) keys.push(`audio-name:${name}`)
  return keys
}

/** 从描述框 HTML/纯文本提取 @音频 引用身份键。 */
export function extractPromptAudioRefIdentityKeysFromHtml(html: string): Set<string> {
  const keys = new Set<string>()
  const source = String(html || '')
  if (!source) return keys

  if (typeof DOMParser !== 'undefined') {
    try {
      const doc = new DOMParser().parseFromString(`<div>${source}</div>`, 'text/html')
      doc.querySelectorAll('.scp-prompt-asset-ref').forEach((el) => {
        const node = el as HTMLElement
        if (String(node.dataset.assetType || '').trim() !== 'audio') return
        for (const key of referenceAudioIdentityKeys({
          id: node.dataset.assetId,
          name: node.dataset.name
        })) {
          keys.add(key)
        }
      })
    } catch {
      /* ignore */
    }
  }

  const placeholderRe = /@音频\d+\[([^\]]+)\]/g
  let match: RegExpExecArray | null
  while ((match = placeholderRe.exec(source))) {
    const name = normalizeAudioName(match[1] || '')
    if (name) keys.add(`audio-name:${name}`)
  }
  return keys
}

export function diffIdentityKeySets(
  prev: Iterable<string>,
  next: Iterable<string>
): { added: string[]; removed: string[] } {
  const prevSet = prev instanceof Set ? prev : new Set(prev)
  const nextSet = next instanceof Set ? next : new Set(next)
  const added: string[] = []
  const removed: string[] = []
  for (const k of nextSet) {
    if (!prevSet.has(k)) added.push(k)
  }
  for (const k of prevSet) {
    if (!nextSet.has(k)) removed.push(k)
  }
  return { added, removed }
}

export function promptAssetItemKey(
  asset: Pick<PromptAssetItem, 'assetId' | 'name'> & Partial<Pick<PromptAssetItem, 'assetType'>>
): string {
  const mediaKind = asset.assetType === 'audio' ? 'audio' : 'image'
  const id = String(asset.assetId || '').trim()
  if (id) return `${mediaKind}:id:${id}`
  const name = normalizePromptAssetPlaceholderName(asset.name)
  return name ? `${mediaKind}:name:${name}` : ''
}

/** 按资产身份对比本地参考图列表变化（用于只插入「新导入」的 @ 标签） */
export function diffPromptAssetsByIdentity(
  prev: PromptAssetItem[],
  next: PromptAssetItem[]
): { added: PromptAssetItem[]; removed: PromptAssetItem[] } {
  const prevMap = new Map<string, PromptAssetItem>()
  for (const a of prev) {
    const k = promptAssetItemKey(a)
    if (k) prevMap.set(k, a)
  }
  const nextMap = new Map<string, PromptAssetItem>()
  for (const a of next) {
    const k = promptAssetItemKey(a)
    if (k) nextMap.set(k, a)
  }
  const added: PromptAssetItem[] = []
  const removed: PromptAssetItem[] = []
  for (const [k, a] of nextMap) {
    if (!prevMap.has(k)) added.push(a)
  }
  for (const [k, a] of prevMap) {
    if (!nextMap.has(k)) removed.push(a)
  }
  return { added, removed }
}

export function collectLocalStripImageAssets(
  sceneImages: unknown[],
  characterImages: unknown[],
  propImages: unknown[],
  otherImages: unknown[]
): PromptAssetItem[] {
  return collectStoryboardPromptAssets(
    sceneImages as any[],
    characterImages as any[],
    propImages as any[],
    otherImages as any[],
    1
  )
}

/** 在扁平参考图条中找出匹配给定身份键的下标（降序，便于逐个删除） */
export function findStripIndexesMatchingIdentityKeys(
  images: StripReferenceImage[],
  identityKeys: Iterable<string>
): number[] {
  const keySet = identityKeys instanceof Set ? identityKeys : new Set(identityKeys)
  if (!keySet.size || !images.length) return []
  const hasStableIds = [...keySet].some((key) => key.startsWith('id:'))
  const indexes: number[] = []
  for (let i = 0; i < images.length; i++) {
    const imgKeys = referenceImageIdentityKeys(images[i]!)
    const comparableKeys = hasStableIds
      ? imgKeys.filter((key) => key.startsWith('id:'))
      : imgKeys
    if (comparableKeys.some((k) => keySet.has(k))) indexes.push(i)
  }
  return indexes.sort((a, b) => b - a)
}

/** 参考图是否仍被描述框引用（任一身份键命中即视为仍引用） */
export function stripImageIsReferencedInPrompt(
  img: StripReferenceImage,
  promptRefKeys: Set<string>
): boolean {
  const keys = referenceImageIdentityKeys(img)
  const idKeys = keys.filter((key) => key.startsWith('id:'))
  const promptHasStableIds = [...promptRefKeys].some((key) => key.startsWith('id:'))
  if (idKeys.length && promptHasStableIds) {
    return idKeys.some((key) => promptRefKeys.has(key))
  }
  return keys.some((key) => promptRefKeys.has(key))
}

/**
 * 描述框 → 参考图条：找出应删除的下标（降序）。
 * - id/name 不对称（embed→纯文本仅剩 name）：只要仍有任一键命中即保留
 * - 仅删除「曾经被引用、且当前已不被引用」的图
 * - 整段清空时也删除已联动引用，保证文本和上方素材条一致
 */
export function findStripIndexesLostFromPrompt(opts: {
  images: StripReferenceImage[]
  prevKeys: Iterable<string>
  nextKeys: Iterable<string>
  /** 兼容旧调用参数；清空状态与逐个删除使用相同的引用收口规则。 */
  promptIsEmpty?: boolean
}): number[] {
  const prevKeys = opts.prevKeys instanceof Set ? opts.prevKeys : new Set(opts.prevKeys)
  const nextKeys = opts.nextKeys instanceof Set ? opts.nextKeys : new Set(opts.nextKeys)
  if (!opts.images.length) return []
  const indexes: number[] = []
  for (let i = 0; i < opts.images.length; i++) {
    const img = opts.images[i]!
    const wasReferenced = stripImageIsReferencedInPrompt(img, prevKeys)
    if (!wasReferenced) continue
    if (stripImageIsReferencedInPrompt(img, nextKeys)) continue
    indexes.push(i)
  }
  return indexes.sort((a, b) => b - a)
}

/** 描述框 → 参考音频条：仅删除曾被引用、随后从文本中移除的音频。 */
export function findAudioIndexesLostFromPrompt(opts: {
  audios: StripReferenceAudio[]
  prevKeys: Iterable<string>
  nextKeys: Iterable<string>
  promptIsEmpty?: boolean
}): number[] {
  const prevKeys = opts.prevKeys instanceof Set ? opts.prevKeys : new Set(opts.prevKeys)
  const nextKeys = opts.nextKeys instanceof Set ? opts.nextKeys : new Set(opts.nextKeys)
  if (!opts.audios.length) return []

  const audioIsReferenced = (keys: string[], refKeys: Set<string>) => {
    const idKeys = keys.filter((key) => key.startsWith('audio-id:'))
    const promptHasStableIds = [...refKeys].some((key) => key.startsWith('audio-id:'))
    if (idKeys.length && promptHasStableIds) {
      return idKeys.some((key) => refKeys.has(key))
    }
    return keys.some((key) => refKeys.has(key))
  }

  const indexes: number[] = []
  for (let index = 0; index < opts.audios.length; index += 1) {
    const keys = referenceAudioIdentityKeys(opts.audios[index]!)
    if (!audioIsReferenced(keys, prevKeys)) continue
    if (audioIsReferenced(keys, nextKeys)) continue
    indexes.push(index)
  }
  return indexes.sort((a, b) => b - a)
}

/** 从 resolved 资产表中剔除与已删参考图同 id/同名的项 */
export function pruneResolvedPromptAssetsForRemovedImage(
  assets: PromptAssetItem[],
  removed: StripReferenceImage
): PromptAssetItem[] {
  if (!assets.length) return assets
  const id = String(removed?.id ?? '').trim()
  const name = stripAt(String(removed?.title || removed?.name || ''))
  const hasExactId = id && assets.some((asset) => String(asset.assetId) === id)
  const provisionalNameMatches = name
    ? assets.filter(
        (asset) =>
          /^(?:resolved|placeholder)-/.test(String(asset.assetId || '')) &&
          promptAssetNamesMatch(asset, { name })
      )
    : []
  return assets.filter((a) => {
    if (hasExactId) return String(a.assetId) !== id
    if (id) {
      return provisionalNameMatches.length !== 1 || a !== provisionalNameMatches[0]
    }
    if (name && promptAssetNamesMatch(a, { name })) return false
    return true
  })
}
