const ALLOWED_TAGS = new Set([
  'a',
  'audio',
  'b',
  'blockquote',
  'br',
  'code',
  'div',
  'em',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
  'hr',
  'i',
  'img',
  'li',
  'ol',
  'p',
  'pre',
  's',
  'source',
  'span',
  'strike',
  'strong',
  'sub',
  'sup',
  'table',
  'tbody',
  'td',
  'th',
  'thead',
  'tr',
  'u',
  'ul',
  'video'
])

const DROP_CONTENT_TAGS = new Set([
  'base',
  'embed',
  'form',
  'iframe',
  'link',
  'math',
  'meta',
  'noscript',
  'object',
  'script',
  'style',
  'svg',
  'template'
])

const ALLOWED_ATTRIBUTES = new Set([
  'alt',
  'class',
  'colspan',
  'controls',
  'dir',
  'height',
  'href',
  'lang',
  'loop',
  'muted',
  'playsinline',
  'poster',
  'preload',
  'rel',
  'role',
  'rowspan',
  'src',
  'target',
  'title',
  'type',
  'width'
])

const URL_ATTRIBUTES = new Set(['href', 'poster', 'src'])

function escapeHtmlText(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function isAllowedUrl(raw: string, attribute: string): boolean {
  const value = raw.trim()
  if (!value) return false
  if (attribute === 'href' && value.startsWith('#')) return true

  try {
    const parsed = new URL(value, 'https://sanitizer.invalid/')
    if (parsed.protocol === 'http:' || parsed.protocol === 'https:') return true
    return attribute === 'href' && (parsed.protocol === 'mailto:' || parsed.protocol === 'tel:')
  } catch {
    return false
  }
}

function isAllowedAttribute(name: string): boolean {
  return (
    ALLOWED_ATTRIBUTES.has(name) ||
    name.startsWith('aria-') ||
    name.startsWith('data-')
  )
}

/**
 * 对后端富文本做严格白名单清洗后再交给 v-html。
 * 服务端渲染时退化为纯文本；当前使用方的数据均在客户端请求完成后写入。
 */
export function sanitizeDisplayHtml(input: unknown): string {
  const raw = String(input ?? '')
  if (!raw) return ''
  if (typeof document === 'undefined') return escapeHtmlText(raw)

  const template = document.createElement('template')
  template.innerHTML = raw
  const elements = [...template.content.querySelectorAll('*')]

  for (const element of elements) {
    const tag = element.tagName.toLowerCase()
    if (!ALLOWED_TAGS.has(tag)) {
      if (DROP_CONTENT_TAGS.has(tag)) element.remove()
      else element.replaceWith(...element.childNodes)
      continue
    }

    for (const attribute of [...element.attributes]) {
      const name = attribute.name.toLowerCase()
      if (name.startsWith('on') || !isAllowedAttribute(name)) {
        element.removeAttribute(attribute.name)
        continue
      }
      if (URL_ATTRIBUTES.has(name) && !isAllowedUrl(attribute.value, name)) {
        element.removeAttribute(attribute.name)
      }
    }

    if (tag === 'a' && element.getAttribute('target') === '_blank') {
      element.setAttribute('rel', 'noopener noreferrer')
    }
  }

  return template.innerHTML
}
