/**
 * 纯文本视频提示词 + 默认分镜参考图：文首注入 @图片1[name]（不回写后端）
 */

import {
  formatAssetApiPlaceholder,
  normalizePromptAssetPlaceholderName
} from './storyboardPromptAssetCore'

export interface DefaultReferenceImageLike {
  id?: string | number
  url?: string
  thumbnail?: string
  name?: string
  title?: string
}

/** 与 PromptAssetItem 结构兼容，供文本域渲染 */
export interface InjectedDefaultRefAsset {
  assetId: string
  assetType: 'scene'
  name: string
  imageIndex: number
  url: string
  label: string
}

export function promptPlainHasAssetPlaceholders(plain: string): boolean {
  const text = String(plain || '')
  return text.includes('@图片') || text.includes('@音频')
}

/** 从导入参考图项构建可点击的 prompt 资产 */
export function referenceImageToDefaultPromptAsset(
  ref: DefaultReferenceImageLike,
  imageIndex = 1
): InjectedDefaultRefAsset | null {
  const url = String(ref.url || ref.thumbnail || '').trim()
  if (!url) return null
  const name = normalizePromptAssetPlaceholderName(ref.title || ref.name, '分镜图')
  const idx = Math.max(1, Math.floor(Number(imageIndex) || 1))
  const id = String(ref.id ?? '').trim()
  return {
    assetId: id || `default-ref-${idx}-${name}`,
    assetType: 'scene',
    name,
    imageIndex: idx,
    url,
    label: `@${name}`
  }
}

/**
 * 纯文本且有默认参考图时，在文首插入 @图片1[name]。
 * 已有 @图片/@音频、无有效图、空文本时不改动。
 */
export function prependDefaultReferenceImageToPlainPrompt(
  plain: string,
  ref: DefaultReferenceImageLike | null | undefined
): { plain: string; asset: InjectedDefaultRefAsset | null; injected: boolean } {
  const text = String(plain || '').trim()
  if (!text || promptPlainHasAssetPlaceholders(text) || !ref) {
    return { plain: text, asset: null, injected: false }
  }
  const asset = referenceImageToDefaultPromptAsset(ref, 1)
  if (!asset) {
    return { plain: text, asset: null, injected: false }
  }
  return {
    plain: `${formatAssetApiPlaceholder(asset.imageIndex, asset.name)}\n${text}`,
    asset,
    injected: true
  }
}
