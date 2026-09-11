'use client'

import { usePathname } from 'next/navigation'
import { useEffect, useState, useSyncExternalStore } from 'react'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import { isPrivateSeoPath, loadRegisteredSeoMeta, normalizeSeoPath, resolveSiteSeo, type PublicSeoMeta } from '~/utils/seoHead'

const subscribeToOrigin = () => () => {}
const getClientOrigin = () => window.location.origin
const getServerOrigin = () => ''

/** 唯一的站点 SEO/品牌标签所有者；React 19 会将这些声明式标签提升至 head。 */
export function PublicSiteHead() {
  const pathname = usePathname() || '/'
  const route = normalizeSeoPath(pathname)
  const { siteName, siteDescription, siteKeywords, faviconUrl, loadPublicConfig } = useAuthPublicConfig()
  const origin = useSyncExternalStore(subscribeToOrigin, getClientOrigin, getServerOrigin)
  const [registered, setRegistered] = useState<{ route: string; value: PublicSeoMeta | null } | null>(null)

  useEffect(() => {
    void loadPublicConfig()
  }, [loadPublicConfig])

  useEffect(() => {
    if (isPrivateSeoPath(route)) return
    let active = true
    void loadRegisteredSeoMeta(route).then((value) => {
      if (active) setRegistered({ route, value })
    })
    return () => { active = false }
  }, [route])

  // SSR 与首轮水合使用同一默认值，缓存中的站点配置只在挂载后生效。
  const seo = resolveSiteSeo(route, {
    title: origin ? siteName : undefined,
    description: origin ? siteDescription : undefined,
    keywords: origin ? siteKeywords : undefined,
    canonicalUrl: origin ? `${origin}${pathname}` : undefined
  }, registered?.route === route ? registered.value : null)
  const icon = origin ? faviconUrl : ''
  const iconType = /\.svg(?:$|\?)/i.test(icon) ? 'image/svg+xml' : undefined

  return (
    <>
      <title>{seo.title}</title>
      <meta name="description" content={seo.description} />
      {seo.keywords ? <meta name="keywords" content={seo.keywords} /> : null}
      <meta name="robots" content={seo.robots} />
      <meta property="og:title" content={seo.title} />
      <meta property="og:description" content={seo.description} />
      <meta property="og:type" content="website" />
      <meta property="og:locale" content="zh_CN" />
      {seo.canonicalUrl ? <meta property="og:url" content={seo.canonicalUrl} /> : null}
      {seo.imageUrl ? <meta property="og:image" content={seo.imageUrl} /> : null}
      <meta name="twitter:card" content={seo.imageUrl ? 'summary_large_image' : 'summary'} />
      <meta name="twitter:title" content={seo.title} />
      <meta name="twitter:description" content={seo.description} />
      {seo.imageUrl ? <meta name="twitter:image" content={seo.imageUrl} /> : null}
      {seo.canonicalUrl ? <link rel="canonical" href={seo.canonicalUrl} /> : null}
      {icon ? <link rel="icon" href={icon} type={iconType} /> : null}
      {icon ? <link rel="apple-touch-icon" href={icon} type={iconType} /> : null}
    </>
  )
}
