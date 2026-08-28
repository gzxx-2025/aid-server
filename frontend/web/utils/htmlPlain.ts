/** 将简单 HTML 转为纯文本（用于字数统计、空内容判断、预览，不依赖 DOM） */
const TAG_RE = /<[^>]+>/g

export function htmlToPlainText(html: string): string {
  if (!html) return ''
  return html
    .replace(TAG_RE, '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/\s+/g, ' ')
    .trim()
}

export function htmlPlainTextLength(html: string): number {
  return htmlToPlainText(html).length
}

/** 剧本创作：电影作品单集字数上限（纯文字） */
export const STORY_SCRIPT_MAX_CHARS_MOVIE = 10_000

/** 剧本创作：剧集作品单集字数上限（纯文字） */
export const STORY_SCRIPT_MAX_CHARS_SERIES = 100_000

/**
 * 统计纯文字字数：汉字、字母、数字；不含空格、换行与标点符号。
 * 用于剧本创作等场景的字数限制展示与校验。
 */
export function plainPureTextCharCount(text: string): number {
  if (!text) return 0
  const matches = text.match(/[\p{L}\p{N}]/gu)
  return matches?.length ?? 0
}

/** HTML 内容转纯文字后统计纯文字字数 */
export function htmlPureTextCharCount(html: string): number {
  return plainPureTextCharCount(htmlToPlainPreserveLineBreaks(html))
}

export function isHtmlContentEmpty(html: string): boolean {
  return htmlPlainTextLength(html) === 0
}

/** 内容是否像 HTML 片段（含常见开闭标签），用于区分「纯文本」与「可直接进编辑器的 HTML」 */
export function looksLikeHtmlFragment(s: string): boolean {
  return /<\/?[a-z][\w-]*\b/i.test(s)
}

/** 内容是否像 Markdown（标题、列表、引用、加粗等） */
export function looksLikeMarkdown(s: string): boolean {
  const t = (s || '').trim()
  if (!t) return false
  return /^(#{1,6}\s|[-*+]\s|\d+\.\s|>\s)|(\n|^)(#{1,6}\s|[-*+]\s|\d+\.\s|>\s)|\*\*[^*\n]+\*\*|__[^_\n]+__/.test(
    t
  )
}

/** 分镜脚本等结构化纯文本的小节标题（用于修复历史存盘时丢失的换行） */
const STORY_SCRIPT_SECTION_LABELS = [
  '剧本内容',
  '画面描述',
  '叙事功能',
  '时间坐标',
  '引用信息',
  '拍摄角度',
  '音效'
] as const

function decodeHtmlEntities(s: string): string {
  return s
    .replace(/&nbsp;/gi, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/**
 * 富文本 → 纯文本：块级标签与 br 转为换行（存 storyScript 时保留段落，避免切换步骤后列表挤成一行）
 */
export function htmlToPlainPreserveLineBreaks(html: string): string {
  if (!html) return ''
  let s = html
    .replace(/<\/p>\s*<p[^>]*>/gi, '\n')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/div>\s*<div[^>]*>/gi, '\n')
    .replace(/<\/li>\s*<li[^>]*>/gi, '\n')
  s = s
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
  return s
    .split('\n')
    .map((line) => line.replace(/\s+/g, ' ').trim())
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function htmlToMarkdownViaRegex(html: string): string {
  const s = html
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/p>\s*<p[^>]*>/gi, '\n\n')
    .replace(/<\/div>\s*<div[^>]*>/gi, '\n\n')
    .replace(/<h([1-6])[^>]*>/gi, (_, n) => `${'#'.repeat(Number(n))} `)
    .replace(/<\/h[1-6]>/gi, '\n\n')
    .replace(/<\/li>\s*<li[^>]*>/gi, '\n')
    .replace(/<li[^>]*>/gi, '- ')
    .replace(/<\/(ul|ol)>/gi, '\n\n')
    .replace(/<(ul|ol)[^>]*>/gi, '')
    .replace(/<blockquote[^>]*>/gi, '> ')
    .replace(/<\/blockquote>/gi, '\n\n')
    .replace(/<(strong|b)[^>]*>/gi, '**')
    .replace(/<\/(strong|b)>/gi, '**')
    .replace(/<(em|i)[^>]*>/gi, '*')
    .replace(/<\/(em|i)>/gi, '*')
    .replace(/<(s|del)[^>]*>/gi, '~~')
    .replace(/<\/(s|del)>/gi, '~~')
    .replace(/<[^>]+>/g, '')
  return decodeHtmlEntities(s)
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function htmlToMarkdownViaDom(html: string): string {
  const root = document.createElement('div')
  root.innerHTML = html

  function inline(node: Node): string {
    if (node.nodeType === Node.TEXT_NODE) {
      return decodeHtmlEntities(node.textContent || '')
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return ''
    const el = node as HTMLElement
    const tag = el.tagName.toLowerCase()
    const inner = Array.from(el.childNodes).map(inline).join('')
    switch (tag) {
      case 'br':
        return '\n'
      case 'strong':
      case 'b':
        return inner ? `**${inner}**` : ''
      case 'em':
      case 'i':
        return inner ? `*${inner}*` : ''
      case 'u':
        return inner ? `<u>${inner}</u>` : ''
      case 's':
      case 'del':
        return inner ? `~~${inner}~~` : ''
      default:
        return inner
    }
  }

  function block(node: Node): string {
    if (node.nodeType === Node.TEXT_NODE) {
      const t = decodeHtmlEntities(node.textContent || '').trim()
      return t ? `${t}\n\n` : ''
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return ''
    const el = node as HTMLElement
    const tag = el.tagName.toLowerCase()
    switch (tag) {
      case 'p': {
        const text = Array.from(el.childNodes)
          .map(inline)
          .join('')
          .replace(/\n+$/, '')
        if (!text.trim()) return '\n'
        return `${text}\n\n`
      }
      case 'h1':
      case 'h2':
      case 'h3':
      case 'h4':
      case 'h5':
      case 'h6': {
        const level = Number(tag[1])
        const text = Array.from(el.childNodes).map(inline).join('').trim()
        return text ? `${'#'.repeat(level)} ${text}\n\n` : ''
      }
      case 'blockquote': {
        const text = Array.from(el.childNodes)
          .map(block)
          .join('')
          .trim()
        if (!text) return ''
        return `${text
          .split('\n')
          .map((line) => `> ${line}`)
          .join('\n')}\n\n`
      }
      case 'ul':
      case 'ol': {
        let idx = 0
        return (
          Array.from(el.children)
            .filter((c) => c.tagName.toLowerCase() === 'li')
            .map((li) => {
              idx += 1
              const prefix = tag === 'ol' ? `${idx}. ` : '- '
              const text = Array.from(li.childNodes).map(inline).join('').trim()
              return `${prefix}${text}`
            })
            .join('\n') + '\n\n'
        )
      }
      case 'div':
        return Array.from(el.childNodes).map(block).join('')
      default:
        return Array.from(el.childNodes).map(block).join('')
    }
  }

  return Array.from(root.childNodes)
    .map(block)
    .join('')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

/** 剧本创作编辑器 HTML（Quill semantic HTML）→ Markdown，供 save / autoSave 的 originalText */
export function htmlToMarkdownForStoryScript(html: string): string {
  const raw = (html || '').trim()
  if (!raw) return ''
  if (!looksLikeHtmlFragment(raw)) return raw
  if (typeof document !== 'undefined') {
    try {
      return htmlToMarkdownViaDom(raw)
    } catch {
      return htmlToMarkdownViaRegex(raw)
    }
  }
  return htmlToMarkdownViaRegex(raw)
}

function applyInlineMarkdown(line: string): string {
  let s = escapeHtml(line)
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  s = s.replace(/\*([^*]+)\*/g, '<em>$1</em>')
  s = s.replace(/~~([^~]+)~~/g, '<s>$1</s>')
  s = s.replace(/&lt;u&gt;([^&]*)&lt;\/u&gt;/g, '<u>$1</u>')
  return s
}

/** Markdown → 剧本创作编辑器 HTML */
export function markdownToStoryScriptEditorHtml(md: string): string {
  const t = (md || '').trim()
  if (!t) return ''

  const lines = t.split('\n')
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
      blocks.push(`<h${level}>${applyInlineMarkdown(headerMatch[2])}</h${level}>`)
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
        `<blockquote><p>${quoteLines.map(applyInlineMarkdown).join('<br/>')}</p></blockquote>`
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
        `<${tag}>${items.map((it) => `<li>${applyInlineMarkdown(it)}</li>`).join('')}</${tag}>`
      )
      continue
    }

    const paraLines: string[] = []
    while (i < lines.length && lines[i].trim()) {
      paraLines.push(lines[i])
      i += 1
    }
    blocks.push(`<p>${paraLines.map(applyInlineMarkdown).join('<br/>')}</p>`)
  }

  return blocks.join('')
}

/** 历史数据：接口纯文本无换行时，在小节标题前补换行以便列表/编辑器恢复分段 */
export function normalizeLegacyStoryScriptPlain(text: string): string {
  const t = (text || '').trim()
  if (!t || /\n/.test(t)) return t
  let s = t
  for (const label of STORY_SCRIPT_SECTION_LABELS) {
    s = s.replace(new RegExp(label, 'g'), `\n${label}`)
  }
  return s.replace(/^\n+/, '').trim()
}

/**
 * 接口返回的剧本内容交给富文本编辑器：
 * - 已含 HTML 标签则原样使用；
 * - Markdown 则转为编辑器 HTML；
 * - 否则按纯文本转义后包一层 `<p>`，换行变 `<br/>`。
 */
export function scriptApiTextToEditorHtml(text: string): string {
  const t = normalizeLegacyStoryScriptPlain((text || '').trim())
  if (!t) return ''
  if (looksLikeHtmlFragment(t)) return t
  if (looksLikeMarkdown(t)) return markdownToStoryScriptEditorHtml(t)
  const esc = t
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  return `<p>${esc.replace(/\n/g, '<br/>')}</p>`
}

/**
 * 第二步剧本 save / autoSave 的 originalText：编辑器 HTML → Markdown。
 */
export function storyScriptOriginalTextForApi(html: string): string {
  return htmlToMarkdownForStoryScript(html)
}

/**
 * 接口返回 Markdown 后写入编辑器：本地已有富文本且与接口 Markdown 一致时，保留 HTML 排版展示。
 */
export function resolveStoryScriptEditorHtmlAfterApiLoad(
  apiText: string,
  previousEditorHtml: string
): string {
  const text = (apiText || '').trim()
  if (!text) return ''
  const prev = (previousEditorHtml || '').trim()
  if (
    prev &&
    looksLikeHtmlFragment(prev) &&
    storyScriptOriginalTextForApi(prev) === storyScriptOriginalTextForApi(text)
  ) {
    return prev
  }
  return scriptApiTextToEditorHtml(text)
}
