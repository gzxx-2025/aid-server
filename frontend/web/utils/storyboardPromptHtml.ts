import { looksLikeMarkdown,markdownToStoryScriptEditorHtml } from '~/utils/htmlPlain'
import {
collectImageLabeledParamRefRanges,
collectVideoLabeledParamRefRanges,
findParamOptionByTag,
plainHasImageLabeledParamFields,
plainHasVideoLabeledParamFields,
promptParamRefSpanHtml,
readPromptParamRefFromNode,
type ParamRefTextRange,
type PromptParamGroup,
type PromptParamRefValue
} from '~/utils/storyboardPromptParamRef'
import {
findPromptAsset,
formatAssetApiPlaceholder,
formatAudioApiPlaceholder,
promptAssetItemToRefValue,
promptAssetNamesMatch,
type PromptAssetItem,
type PromptAssetRefValue,
type PromptAssetType
} from './storyboardPromptAssetCore'
const API_PLACEHOLDER_RE = /@图片(\d+)\[([^\]]+)\]/g
const API_AUDIO_PLACEHOLDER_RE = /@音频(\d+)\[([^\]]+)\]/g
const LEGACY_TAG_RE = /@([^\s@]+)/g

function stripAt(value: string): string {
  return value.startsWith('@') ? value.slice(1) : value
}

/** 编辑器 HTML → 接口纯文本（@图片N[name]） */
export function storyboardPromptHtmlToPlain(html: string): string {
  if (!html?.trim()) return ''
  if (!(typeof window !== 'undefined')) {
    return html.replace(/<[^>]+>/g, '').trim()
  }
  try {
    const doc = new DOMParser().parseFromString(`<div id="sp-root">${html}</div>`, 'text/html')
    const root = doc.getElementById('sp-root')
    if (!root) return htmlToPlainFallback(html)

    const walk = (node: Node): string => {
      if (node.nodeType === Node.TEXT_NODE) return node.textContent ?? ''
      if (!(node instanceof HTMLElement)) return ''
      if (node.classList.contains('scp-prompt-asset-ref')) {
        const idx = Number(node.dataset.imageIndex)
        const name = node.dataset.name || node.dataset.label || ''
        const assetType = String(node.dataset.assetType || '')
        if (Number.isFinite(idx) && idx > 0 && name) {
          if (assetType === 'audio') return formatAudioApiPlaceholder(idx, stripAt(name))
          return formatAssetApiPlaceholder(idx, stripAt(name))
        }
        const label = (node.querySelector('.scp-prompt-asset-ref__label')?.textContent ?? '').trim()
        if (label) return label.startsWith('@') ? label : `@${label}`
        return ''
      }
      if (node.classList.contains('scp-prompt-param-ref')) {
        const v = readPromptParamRefFromNode(node)
        return v.label || ''
      }
      let s = ''
      node.childNodes.forEach((c) => {
        s += walk(c)
      })
      if (node.tagName === 'P' || node.tagName === 'BR' || node.tagName === 'DIV') {
        if (node.tagName === 'BR') return '\n'
      }
      return s
    }

    let out = ''
    root.childNodes.forEach((c) => {
      out += walk(c)
      if (c instanceof HTMLElement && c.tagName === 'P') out += '\n'
    })
    return out.replace(/\n+$/, '').trim() || htmlToPlainFallback(html)
  } catch {
    return htmlToPlainFallback(html)
  }
}

function htmlToPlainFallback(html: string): string {
  return html
    .replace(/<span[^>]*class="[^"]*scp-prompt-asset-ref[^"]*"[^>]*data-image-index="(\d+)"[^>]*data-name="([^"]*)"[^>]*>[\s\S]*?<\/span>/gi, (_, n, name) =>
      formatAssetApiPlaceholder(Number(n), name)
    )
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .trim()
}

function paramRefFromTag(
  tag: string,
  paramGroups: PromptParamGroup[]
): PromptParamRefValue | undefined {
  const hit = findParamOptionByTag(paramGroups, tag)
  if (!hit) return undefined
  const label = hit.option.value.startsWith('@') ? hit.option.value : `@${hit.option.value}`
  return {
    paramType: hit.group.paramType,
    key: hit.option.key,
    label
  }
}

type PromptPlainSegment =
  | { kind: 'text'; start: number; end: number }
  | { kind: 'param'; start: number; end: number; ref: PromptParamRefValue }
  | { kind: 'asset'; start: number; end: number; item: PromptAssetItem }

function rangesOverlap(aStart: number, aEnd: number, bStart: number, bEnd: number): boolean {
  return aStart < bEnd && bStart < aEnd
}

/** 从纯文本中移除 @图片N[name] 及已知图片名称的 @ 标签（图生视频参考图单独展示，不入描述框） */
export function stripPromptImageAssetPlaceholdersFromPlain(
  plain: string,
  imageNames: string[] = []
): string {
  let text = String(plain || '')
  text = text.replace(API_PLACEHOLDER_RE, '')
  const uniqueNames = [...new Set(imageNames.map((n) => stripAt(n)).filter(Boolean))]
  for (const name of uniqueNames.sort((a, b) => b.length - a.length)) {
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    text = text.replace(new RegExp(`@${escaped}(?=\\s|$|[，,。；;\\n])`, 'g'), '')
  }
  return text
    .replace(/[ \t]{2,}/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function buildPromptPlainSegments(
  text: string,
  assets: PromptAssetItem[],
  paramGroups: PromptParamGroup[],
  labeledRanges: ParamRefTextRange[],
  enableAssetRefs = true
): PromptPlainSegment[] {
  const segments: PromptPlainSegment[] = []

  for (const range of labeledRanges) {
    segments.push({
      kind: 'param',
      start: range.start,
      end: range.end,
      ref: range.ref
    })
  }

  if (!enableAssetRefs) {
    segments.sort((a, b) => a.start - b.start || a.end - b.end)
    return segments
  }

  const re = new RegExp(
    `${API_PLACEHOLDER_RE.source}|${API_AUDIO_PLACEHOLDER_RE.source}|${LEGACY_TAG_RE.source}`,
    'g'
  )
  let m: RegExpExecArray | null
  while ((m = re.exec(text)) !== null) {
    const start = m.index
    const end = start + m[0].length
    if (segments.some((s) => rangesOverlap(start, end, s.start, s.end))) continue

    // @图片N[name]
    if (m[1] != null && m[2] != null) {
      const imageIndex = Number(m[1])
      const name = m[2]
      const imageAssets = assets.filter((a) => a.assetType !== 'audio')
      const item =
        findPromptAsset(imageAssets, { imageIndex, name }) ||
        ({
          assetId: `placeholder-${imageIndex}-${name}`,
          assetType: 'other' as PromptAssetType,
          name,
          imageIndex,
          url: '',
          label: `@${name}`
        } satisfies PromptAssetItem)
      segments.push({ kind: 'asset', start, end, item })
      continue
    }

    // @音频N[name]
    if (m[3] != null && m[4] != null) {
      const audioIndex = Number(m[3])
      const name = m[4]
      const item =
        assets.find(
          (a) =>
            a.assetType === 'audio' &&
            (a.imageIndex === audioIndex || promptAssetNamesMatch(a, { name }))
        ) ||
        ({
          assetId: `audio-placeholder-${audioIndex}-${name}`,
          assetType: 'audio' as PromptAssetType,
          name,
          imageIndex: audioIndex,
          url: '',
          label: `@${name}`
        } satisfies PromptAssetItem)
      segments.push({ kind: 'asset', start, end, item })
      continue
    }

    if (m[5]) {
      const tag = m[5]
      const paramRef = paramRefFromTag(tag, paramGroups)
      if (paramRef) {
        segments.push({ kind: 'param', start, end, ref: paramRef })
        continue
      }
      const item =
        findPromptAsset(assets, { label: tag, name: tag }) ||
        findPromptAsset(assets, { assetId: tag })
      if (item) {
        segments.push({ kind: 'asset', start, end, item })
      }
    }
  }

  segments.sort((a, b) => a.start - b.start || a.end - b.end)
  return segments
}

function renderPromptPlainSegments(text: string, segments: PromptPlainSegment[]): string {
  const parts: string[] = []
  let last = 0
  for (const seg of segments) {
    if (seg.start > last) parts.push(escapeHtml(text.slice(last, seg.start)))
    if (seg.kind === 'param') parts.push(promptParamRefSpanHtml(seg.ref))
    else if (seg.kind === 'asset') parts.push(promptAssetRefSpanHtml(promptAssetItemToRefValue(seg.item)))
    last = seg.end
  }
  if (last < text.length) parts.push(escapeHtml(text.slice(last)))
  return parts.join('')
}

/** 接口纯文本 → 编辑器 HTML（含资产块与参数块） */
export function storyboardPromptPlainToHtml(
  plain: string,
  assets: PromptAssetItem[],
  paramGroups: PromptParamGroup[] = [],
  options?: {
    enableVideoLabeledParams?: boolean
    enableImageLabeledParams?: boolean
    /** 为 false 时不渲染 @图片 引用块（图生视频参考图在描述框外单独展示） */
    enableAssetRefs?: boolean
  }
): string {
  const enableAssetRefs = options?.enableAssetRefs !== false
  const text = enableAssetRefs
    ? (plain || '').trim()
    : stripPromptImageAssetPlaceholdersFromPlain(
        plain,
        assets.map((a) => a.name || a.label)
      )
  if (!text) return ''

  const labeledRanges = [
    ...(options?.enableImageLabeledParams
      ? collectImageLabeledParamRefRanges(text, paramGroups)
      : []),
    ...(options?.enableVideoLabeledParams
      ? collectVideoLabeledParamRefRanges(text, paramGroups)
      : [])
  ]

  if (!text.includes('@') && labeledRanges.length === 0) {
    return `<p>${escapeHtml(text)}</p>`
  }

  const segments = buildPromptPlainSegments(
    text,
    enableAssetRefs ? assets : [],
    paramGroups,
    labeledRanges,
    enableAssetRefs
  )
  const body = renderPromptPlainSegments(text, segments)
  return body ? `<p>${body}</p>` : ''
}

export function promptAssetRefSpanHtml(v: PromptAssetRefValue): string {
  const label = v.label || `@${v.name}`
  const url = escapeAttr(v.url || '')
  const attrs = [
    `class="scp-prompt-asset-ref scp-prompt-asset-ref--${v.assetType}"`,
    'contenteditable="false"',
    `data-asset-id="${escapeAttr(v.assetId)}"`,
    `data-asset-type="${escapeAttr(v.assetType)}"`,
    `data-name="${escapeAttr(v.name)}"`,
    `data-image-index="${v.imageIndex}"`,
    `data-url="${url}"`,
    `data-label="${escapeAttr(label)}"`
  ].join(' ')
  return `<span ${attrs}><span class="scp-prompt-asset-ref__label">${escapeHtml(label)}</span></span>`
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function escapeAttr(s: string): string {
  return escapeHtml(s).replace(/'/g, '&#39;')
}

/** 从 HTML 提取已引用的 assetId */
export function extractReferencedAssetIdsFromHtml(html: string): Set<string> {
  const ids = new Set<string>()
  if (!html || !(typeof window !== 'undefined')) return ids
  try {
    const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html')
    doc.querySelectorAll('.scp-prompt-asset-ref').forEach((el) => {
      const id = (el as HTMLElement).dataset.assetId
      if (id) ids.add(id)
    })
  } catch {
    /* ignore */
  }
  return ids
}

/** 从 HTML 提取已引用的 @图片N 序号 */
export function extractReferencedImageIndexesFromHtml(html: string): Set<number> {
  const indexes = new Set<number>()
  if (!html || !(typeof window !== 'undefined')) return indexes
  try {
    const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html')
    doc.querySelectorAll('.scp-prompt-asset-ref').forEach((el) => {
      const idx = Number((el as HTMLElement).dataset.imageIndex)
      if (Number.isFinite(idx) && idx > 0) indexes.add(idx)
    })
  } catch {
    /* ignore */
  }
  return indexes
}

export function plainTextLengthForPrompt(htmlOrPlain: string): number {
  return storyboardPromptHtmlToPlain(htmlOrPlain).length
}

function stripPromptParagraphWrapper(html: string): string {
  const m = html.match(/^<p>([\s\S]*)<\/p>$/i)
  return m ? m[1]! : html
}

function inlinePromptChunkToHtml(
  chunk: string,
  assets: PromptAssetItem[],
  paramGroups: PromptParamGroup[],
  options?: {
    enableVideoLabeledParams?: boolean
    enableImageLabeledParams?: boolean
    enableAssetRefs?: boolean
  }
): string {
  const t = String(chunk || '')
  if (!t.trim()) return ''
  const hasRefs =
    t.includes('@') ||
    (options?.enableVideoLabeledParams && plainHasVideoLabeledParamFields(t)) ||
    (options?.enableImageLabeledParams && plainHasImageLabeledParamFields(t))
  if (!hasRefs) {
    const md = markdownToStoryScriptEditorHtml(t)
    if (md) return md.replace(/^<p>([\s\S]*)<\/p>$/i, '$1') || escapeHtml(t)
    return escapeHtml(t)
  }
  return stripPromptParagraphWrapper(storyboardPromptPlainToHtml(t, assets, paramGroups, options))
}

function renderMarkdownBlocksWithPromptRefs(
  plain: string,
  assets: PromptAssetItem[],
  paramGroups: PromptParamGroup[],
  options?: {
    enableVideoLabeledParams?: boolean
    enableImageLabeledParams?: boolean
    enableAssetRefs?: boolean
  }
): string {
  const lines = plain.split('\n')
  const blocks: string[] = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]
    if (!line.trim()) {
      i += 1
      continue
    }

    const headerMatch = line.match(/^(#{1,6})\s+(.+)$/)
    if (headerMatch) {
      const level = headerMatch[1].length
      const titleHtml = inlinePromptChunkToHtml(headerMatch[2], assets, paramGroups, options)
      blocks.push(`<h${level}>${titleHtml}</h${level}>`)
      i += 1
      continue
    }

    if (/^>\s?/.test(line)) {
      const quoteLines: string[] = []
      while (i < lines.length && /^>\s?/.test(lines[i])) {
        quoteLines.push(lines[i].replace(/^>\s?/, ''))
        i += 1
      }
      blocks.push(
        `<blockquote><p>${quoteLines
          .map((q) => inlinePromptChunkToHtml(q, assets, paramGroups, options))
          .join('<br/>')}</p></blockquote>`
      )
      continue
    }

    if (/^[-*+]\s/.test(line) || /^\d+\.\s/.test(line)) {
      const items: string[] = []
      const ordered = /^\d+\.\s/.test(line)
      while (i < lines.length && (/^[-*+]\s/.test(lines[i]) || /^\d+\.\s/.test(lines[i]))) {
        items.push(lines[i].replace(/^([-*+]|\d+\.)\s+/, ''))
        i += 1
      }
      const tag = ordered ? 'ol' : 'ul'
      blocks.push(
        `<${tag}>${items
          .map((it) => `<li>${inlinePromptChunkToHtml(it, assets, paramGroups, options)}</li>`)
          .join('')}</${tag}>`
      )
      continue
    }

    const paraLines: string[] = []
    while (
      i < lines.length &&
      lines[i].trim() &&
      !/^(#{1,6})\s/.test(lines[i]) &&
      !/^[-*+]\s/.test(lines[i]) &&
      !/^\d+\.\s/.test(lines[i]) &&
      !/^>\s?/.test(lines[i])
    ) {
      paraLines.push(lines[i])
      i += 1
    }
    blocks.push(
      `<p>${inlinePromptChunkToHtml(paraLines.join('\n'), assets, paramGroups, options)}</p>`
    )
  }

  return blocks.join('')
}

/** 多参生视频等：Markdown 结构（# 标题 / 列表）+ @图片 / 结构化参数字段 */
export function storyboardPromptMarkdownPlainToHtml(
  plain: string,
  assets: PromptAssetItem[],
  paramGroups: PromptParamGroup[] = [],
  options?: {
    enableVideoLabeledParams?: boolean
    enableImageLabeledParams?: boolean
    enableAssetRefs?: boolean
  }
): string {
  const enableAssetRefs = options?.enableAssetRefs !== false
  const text = enableAssetRefs
    ? (plain || '').trim()
    : stripPromptImageAssetPlaceholdersFromPlain(
        plain,
        assets.map((a) => a.name || a.label)
      )
  if (!text) return ''

  const needsInlineRefs =
    text.includes('@') ||
    (options?.enableVideoLabeledParams && plainHasVideoLabeledParamFields(text)) ||
    (options?.enableImageLabeledParams && plainHasImageLabeledParamFields(text))

  if (looksLikeMarkdown(text)) {
    if (needsInlineRefs) return renderMarkdownBlocksWithPromptRefs(text, assets, paramGroups, options)
    return markdownToStoryScriptEditorHtml(text)
  }

  return storyboardPromptPlainToHtml(text, assets, paramGroups, options)
}
