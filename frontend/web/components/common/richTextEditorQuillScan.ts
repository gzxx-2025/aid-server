import {
formatAssetApiPlaceholder,
normalizePromptAssetPlaceholderName
} from '~/utils/storyboardPromptAssetRef'
import {
readPromptParamRefFromNode,
type PromptParamRefValue,
type PromptParamType
} from '~/utils/storyboardPromptParamRef'
import type { PromptAssetType } from '~/utils/storyboardPromptAssetRef'

/** RichTextEditor 文档扫描辅助：在 Quill 内容里定位 @资产 / @参数 Embed（与组件状态无关） */

type QuillInstance = import('quill').default

export function findParamEmbedIndex(
  quill: QuillInstance,
  paramType: PromptParamType
): number | null {
  let index = 0
  for (const op of quill.getContents().ops) {
    const ins = op.insert
    if (ins != null && typeof ins === 'object' && 'promptParamRef' in ins) {
      const v = ins.promptParamRef as PromptParamRefValue
      if (v.paramType === paramType) return index
      index += 1
      continue
    }
    if (typeof ins === 'string') index += ins.length
    else index += 1
  }
  return null
}

/** 在编辑器纯文本中定位 @标签 对应的 Quill 区间（用于将纯文本占位转为 embed） */
export function findPlainTagQuillRange(
  quill: QuillInstance,
  tag: string
): { index: number; length: number } | null {
  if (!tag) return null
  const needles = [tag, tag.startsWith('@') ? tag : `@${tag}`]
  let plain = ''
  const indexMap: number[] = []
  let index = 0

  for (const op of quill.getContents().ops) {
    const ins = op.insert
    if (typeof ins === 'string') {
      for (let i = 0; i < ins.length; i++) {
        indexMap[plain.length] = index + i
        plain += ins[i]
      }
      index += ins.length
      continue
    }
    if (ins != null && typeof ins === 'object') {
      if ('promptParamRef' in ins) {
        const v = ins.promptParamRef as PromptParamRefValue
        const label = v.label || ''
        for (let i = 0; i < label.length; i++) {
          indexMap[plain.length] = index
          plain += label[i]
        }
        index += 1
        continue
      }
      if ('promptAssetRef' in ins) {
        const v = ins.promptAssetRef as { imageIndex?: number; name?: string }
        const label = v.imageIndex && v.name ? formatAssetApiPlaceholder(v.imageIndex, v.name) : ''
        for (let i = 0; i < label.length; i++) {
          indexMap[plain.length] = index
          plain += label[i]
        }
        index += 1
        continue
      }
      index += 1
    }
  }

  for (const needle of needles) {
    const pos = plain.indexOf(needle)
    if (pos >= 0 && indexMap[pos] != null) {
      return { index: indexMap[pos], length: needle.length }
    }
  }
  return null
}

export function getParamEmbedValue(
  quill: QuillInstance,
  paramType: PromptParamType
): PromptParamRefValue | null {
  const idx = findParamEmbedIndex(quill, paramType)
  if (idx == null) return null
  const leaf = quill.getLeaf(idx) as
    | [InstanceType<typeof import('parchment').LeafBlot>, number]
    | undefined
  if (!leaf?.[0]) return null
  const blotName = (leaf[0].constructor as { blotName?: string }).blotName
  if (blotName !== 'promptParamRef') return null
  const dom = leaf[0].domNode as HTMLElement | undefined
  return dom ? readPromptParamRefFromNode(dom) : null
}

export function findAssetEmbedIndex(quill: QuillInstance, assetId: string): number | null {
  if (!assetId) return null
  let index = 0
  for (const op of quill.getContents().ops) {
    const ins = op.insert
    if (ins != null && typeof ins === 'object' && 'promptAssetRef' in ins) {
      const v = ins.promptAssetRef as { assetId?: string }
      if (v.assetId === assetId) return index
      index += 1
      continue
    }
    if (typeof ins === 'string') index += ins.length
    else index += 1
  }
  return null
}

export function findAssetEmbedIndexByMatch(
  quill: QuillInstance,
  hint: {
    assetId?: string
    assetType?: PromptAssetType
    imageIndex?: number
    name?: string
  }
): number | null {
  type AssetEmbed = {
    index: number
    value: {
      assetId?: string
      assetType?: PromptAssetType
      imageIndex?: number
      name?: string
    }
  }
  const embeds: AssetEmbed[] = []
  let index = 0
  for (const op of quill.getContents().ops) {
    const ins = op.insert
    if (ins != null && typeof ins === 'object' && 'promptAssetRef' in ins) {
      const value = ins.promptAssetRef as AssetEmbed['value']
      if (!hint.assetType || !value.assetType || value.assetType === hint.assetType) {
        embeds.push({ index, value })
      }
      index += 1
      continue
    }
    if (typeof ins === 'string') index += ins.length
    else index += 1
  }

  const assetId = String(hint.assetId || '').trim()
  if (assetId) {
    const exact = embeds.find((entry) => String(entry.value.assetId || '').trim() === assetId)
    if (exact) return exact.index
  }

  // 真实 ID 不允许仅凭同名替换另一个真实资产；名称/序号只用于把解析阶段的临时 embed 水合为真实资产。
  const isProvisionalId = (value: unknown) => {
    const id = String(value || '').trim()
    return !id || /^(?:resolved|placeholder|audio-placeholder)-/.test(id)
  }
  const fallbackEmbeds = assetId && !isProvisionalId(assetId)
    ? embeds.filter((entry) => isProvisionalId(entry.value.assetId))
    : embeds
  const uniqueMatch = (matches: AssetEmbed[]) =>
    matches.length === 1 ? matches[0]!.index : null

  // 名称优先于 imageIndex：本地序号可能重排；同名不唯一时宁可不匹配，也不能误删/误替换。
  const hintName = normalizePromptAssetPlaceholderName(hint.name)
  if (hintName) {
    const byName = fallbackEmbeds.filter(
      (entry) => normalizePromptAssetPlaceholderName(entry.value.name) === hintName
    )
    if (byName.length) return uniqueMatch(byName)
  }
  if (hint.imageIndex == null) return null
  return uniqueMatch(
    fallbackEmbeds.filter((entry) => entry.value.imageIndex === hint.imageIndex)
  )
}
