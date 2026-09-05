import { request } from '~/utils/api'

export interface PublicSeoMeta {
  title?: string
  description?: string
  keywords?: string
  canonicalUrl?: string
  imageUrl?: string
  robots?: string
}

type SeoOwner = { owner: string; value: PublicSeoMeta }

let baseSeo: PublicSeoMeta = {}
let pageSeo: SeoOwner | null = null
const metaInflight = new Map<string, Promise<PublicSeoMeta | null>>()
const metaCache = new Map<string, { value: PublicSeoMeta | null; at: number }>()
const META_CACHE_MS = 60_000

function setMeta(attr: 'name' | 'property', key: string, content?: string) {
  let element = document.head.querySelector<HTMLMetaElement>(`meta[${attr}="${key}"]`)
  if (!content) {
    element?.remove()
    return
  }
  if (!element) {
    element = document.createElement('meta')
    element.setAttribute(attr, key)
    document.head.appendChild(element)
  }
  element.setAttribute('content', content)
}

function setCanonical(href?: string) {
  const links = [...document.head.querySelectorAll<HTMLLinkElement>('link[rel="canonical"]')]
  if (!href) {
    links.forEach((link) => link.remove())
    return
  }
  const canonical = links.shift() || document.createElement('link')
  canonical.setAttribute('rel', 'canonical')
  canonical.setAttribute('href', href)
  if (!canonical.parentNode) document.head.appendChild(canonical)
  links.forEach((link) => link.remove())
}

function currentCanonical() {
  if (typeof window === 'undefined') return undefined
  return `${window.location.origin}${window.location.pathname}`
}

function renderSeo() {
  if (typeof document === 'undefined') return
  const value = pageSeo?.value || baseSeo
  if (value.title) document.title = value.title
  setMeta('name', 'description', value.description)
  setMeta('name', 'keywords', value.keywords)
  setMeta('name', 'robots', value.robots || 'index,follow')
  setMeta('property', 'og:title', value.title)
  setMeta('property', 'og:description', value.description)
  setMeta('property', 'og:url', value.canonicalUrl || currentCanonical())
  setMeta('property', 'og:type', 'website')
  setMeta('property', 'og:image', value.imageUrl)
  setMeta('name', 'twitter:card', value.imageUrl ? 'summary_large_image' : 'summary')
  setMeta('name', 'twitter:title', value.title)
  setMeta('name', 'twitter:description', value.description)
  setMeta('name', 'twitter:image', value.imageUrl)
  setCanonical(value.canonicalUrl || currentCanonical())
}

/** 写入路由默认 SEO；页面级覆盖存在时不会被异步公共配置覆盖。 */
export function setBaseSeo(value: PublicSeoMeta) {
  baseSeo = value
  renderSeo()
}

/** 页面详情加载后设置更高优先级的 SEO，并返回清理函数。 */
export function setPageSeo(owner: string, value: PublicSeoMeta) {
  pageSeo = { owner, value }
  renderSeo()
  return () => {
    if (pageSeo?.owner === owner) {
      pageSeo = null
      renderSeo()
    }
  }
}

/** 按业务键合并同一路径的进行中请求，并做一分钟短时缓存。 */
export function loadRegisteredSeoMeta(path: string, force = false): Promise<PublicSeoMeta | null> {
  const normalized = path.startsWith('/') ? path : `/${path}`
  const running = metaInflight.get(normalized)
  if (running) return running
  const cached = metaCache.get(normalized)
  if (!force && cached && Date.now() - cached.at < META_CACHE_MS) return Promise.resolve(cached.value)
  const promise = request
    .get<PublicSeoMeta>('/seo/public/meta', { path: normalized })
    .then((response) => response || null)
    .catch(() => null)
    .then((value) => {
      metaCache.set(normalized, { value, at: Date.now() })
      return value
    })
    .finally(() => metaInflight.delete(normalized))
  metaInflight.set(normalized, promise)
  return promise
}
