import { request } from '~/utils/api'

export interface PublicSeoMeta {
  title?: string
  description?: string
  keywords?: string
  canonicalUrl?: string
  imageUrl?: string
  robots?: string
}

export const DEFAULT_SITE_TITLE = '视觉·AID'
export const DEFAULT_SITE_DESCRIPTION = '从剧本到成片的全流程创作工具'

const PAGE_TITLES: Record<string, string> = {
  '/works': '我的作品',
  '/invite': '邀请管理',
  '/faq': '常见问题',
  '/about': '关于我们',
  '/mobile': '移动端提示'
}

export function normalizeSeoPath(pathname: string): string {
  return pathname.replace(/\/+$/, '') || '/'
}

export function isPrivateSeoPath(pathname: string): boolean {
  return /^\/(?:admin|assets|billing|create|forgot-password|login|user|works|invite|mobile|index-legacy|case)(?:\/|$)/.test(pathname)
}

/** 路由和动态配置在同一处合成；标签生命周期由 PublicSiteHead 声明式管理。 */
export function resolveSiteSeo(pathname: string, base: PublicSeoMeta, registered?: PublicSeoMeta | null): PublicSeoMeta {
  const route = normalizeSeoPath(pathname)
  const siteTitle = base.title || DEFAULT_SITE_TITLE
  const defaults: PublicSeoMeta = {
    title: PAGE_TITLES[route] ? `${PAGE_TITLES[route]} - ${siteTitle}` : siteTitle,
    description: route === '/faq'
      ? '产品使用说明、常见问题与帮助中心'
      : route === '/about' ? '了解产品、服务与创作平台' : base.description || DEFAULT_SITE_DESCRIPTION,
    keywords: base.keywords,
    canonicalUrl: base.canonicalUrl,
    robots: 'index,follow'
  }
  if (isPrivateSeoPath(route)) return { ...defaults, robots: 'noindex,nofollow' }
  return {
    title: registered?.title || defaults.title,
    description: registered?.description || defaults.description,
    keywords: registered?.keywords || defaults.keywords,
    canonicalUrl: registered?.canonicalUrl || defaults.canonicalUrl,
    imageUrl: registered?.imageUrl,
    robots: registered?.robots || defaults.robots
  }
}

const metaInflight = new Map<string, Promise<PublicSeoMeta | null>>()
const metaCache = new Map<string, { value: PublicSeoMeta | null; at: number }>()
const META_CACHE_MS = 60_000

/** 未登记路径返回 404，表示无覆盖配置；按路径合并请求并做一分钟短时缓存。 */
export function loadRegisteredSeoMeta(path: string, force = false): Promise<PublicSeoMeta | null> {
  const normalized = normalizeSeoPath(path.startsWith('/') ? path : `/${path}`)
  const running = metaInflight.get(normalized)
  if (running) return running
  const cached = metaCache.get(normalized)
  if (!force && cached && Date.now() - cached.at < META_CACHE_MS) return Promise.resolve(cached.value)
  const promise = request
    .get<PublicSeoMeta>('/seo/public/meta', { path: normalized }, {
      validateStatus: (status) => (status >= 200 && status < 300) || status === 404
    })
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
