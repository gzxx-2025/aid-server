import type { HomeBannerVO } from '~/types/business-api'
import type { HeroCarouselSlide } from '~/types/heroCarousel'
/** 取轮播中心位 slide 的视频地址（用于 Hero 背景视频兜底） */
export function resolveCenterVideoUrl(
  slides: HeroCarouselSlide[],
  centerIndex = 0
): string {
  if (!slides.length) return ''
  const idx = Math.min(centerIndex, slides.length - 1)
  return slides[idx]?.videoUrl?.trim() || slides[0]?.videoUrl?.trim() || ''
}

/** 将首页 Banner 接口数据转为轮播 slide */
export function mapHomeBannerToSlide(banner: HomeBannerVO): HeroCarouselSlide {
  const bannerType = String(banner.bannerType || 'image').toLowerCase()
  const resourceUrl = String(banner.resourceUrl || '').trim()
  const coverUrl = String(banner.coverUrl || '').trim()
  return {
    id: banner.id,
    title: banner.title || '',
    summary: banner.summary || undefined,
    cover: coverUrl || (bannerType === 'image' ? resourceUrl : ''),
    videoUrl: bannerType === 'video' ? resourceUrl : undefined,
    bannerType,
    linkType: banner.linkType,
    linkUrl: banner.linkUrl
  }
}

