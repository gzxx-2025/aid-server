'use client'

import { usePathname } from 'next/navigation'
import { useEffect } from 'react'
import { loadPublicConfig,useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { loadRegisteredSeoMeta, setBaseSeo } from '~/utils/seoHead'
const DEFAULT_TITLE = '视觉·AID'
const DEFAULT_DESCRIPTION = '从剧本到成片的全流程创作工具'

/** 按 name / property 查找或创建 meta 标签并写入 content（对应原 useHead meta 数组的 upsert 语义） */
/**
 * 更新（或创建）rel 对应的 link 标签 href。
 * 原 useHead 通过 key: 'brand-favicon' / 'brand-apple-touch-icon' 去重复用同一节点；
 * Next 侧 metadata 已渲染同 rel 的默认 link，直接改写现有节点即等价于 key 去重。
 */
function upsertLink(rel: string, href: string, isSvgIcon: boolean) {
  const links = document.head.querySelectorAll<HTMLLinkElement>(`link[rel="${rel}"]`)
  if (links.length === 0) {
    const el = document.createElement('link')
    el.setAttribute('rel', rel)
    if (isSvgIcon) el.setAttribute('type', 'image/svg+xml')
    el.setAttribute('href', href)
    document.head.appendChild(el)
    return
  }
  links.forEach((el) => {
    if (isSvgIcon) {
      el.setAttribute('type', 'image/svg+xml')
    } else {
      el.removeAttribute('type')
    }
    el.setAttribute('href', href)
  })
}

function removeLinks(rel: string) {
  document.head.querySelectorAll<HTMLLinkElement>(`link[rel="${rel}"]`).forEach((el) => {
    el.remove()
  })
}

/**
 * 根据 POST /auth/public-config 的 basic（SEO）与 brand（Favicon）动态写入文档 head。
 * 优先使用接口返回的 site_name / site_description / site_keywords / faviconUrl；
 * SEO 文案缺失时使用中性默认值；品牌图标缺失时不注入任何内置图标。
 *
 * 原 Nuxt useHead(() => ...) 为响应式声明；React 侧改为 useEffect 副作用直写 document：
 * 配置值变化即重写 title / meta / link。依赖中额外带上 pathname——App Router 软导航
 * 会按目标路由 metadata 重写 head（本项目仅根 layout 声明了默认 metadata），
 * 路由切换后需要重申动态站点头，否则标题会被打回默认值。
 */
export function usePublicSiteHead() {
  const { siteName, siteDescription, siteKeywords, faviconUrl } = useAuthPublicConfig()
  const pathname = usePathname()

  useEffect(() => {
    const route = pathname.replace(/\/+$/, '') || '/'
    const pageDefaults = route === '/faq'
      ? { title: `常见问题 - ${siteName || DEFAULT_TITLE}`, description: '产品使用说明、常见问题与帮助中心' }
      : route === '/about'
        ? { title: `关于我们 - ${siteName || DEFAULT_TITLE}`, description: '了解产品、服务与创作平台' }
        : null
    const title = pageDefaults?.title || siteName || DEFAULT_TITLE
    const description = pageDefaults?.description || siteDescription || DEFAULT_DESCRIPTION
    const keywords = siteKeywords
    const isSvgIcon = /\.svg(?:$|\?)/i.test(faviconUrl)
    const privateRoute = /^\/(?:admin|assets|billing|create|forgot-password|login|user|works|invite|mobile|index-legacy)(?:\/|$)/.test(pathname) || route === '/case'
    const robots = privateRoute ? 'noindex,nofollow' : 'index,follow'
    let active = true

    setBaseSeo({ title, description, keywords, robots })
    if (!privateRoute) void loadRegisteredSeoMeta(route).then((registered) => {
      if (!active || !registered) return
      setBaseSeo({
        title: registered.title || title,
        description: registered.description || description,
        keywords: registered.keywords || keywords,
        canonicalUrl: registered.canonicalUrl,
        imageUrl: registered.imageUrl,
        robots: privateRoute ? 'noindex,nofollow' : (registered.robots || 'index,follow')
      })
    })
    if (faviconUrl) {
      upsertLink('icon', faviconUrl, isSvgIcon)
      upsertLink('apple-touch-icon', faviconUrl, isSvgIcon)
    } else {
      removeLinks('icon')
      removeLinks('apple-touch-icon')
    }
    return () => { active = false }
  }, [siteName, siteDescription, siteKeywords, faviconUrl, pathname])

  // 原实现在 setup 内 import.meta.client 时 void loadPublicConfig()；React 侧 effect 天然仅客户端执行
  useEffect(() => {
    void loadPublicConfig()
  }, [])
}
