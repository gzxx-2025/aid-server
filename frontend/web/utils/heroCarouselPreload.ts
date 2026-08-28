import type { HeroCarouselSlide } from '~/types/heroCarousel'

/** 归一化轮播下标（环形） */
export function normalizeHeroIndex(index: number, count: number): number {
  if (!count) return 0
  return ((index % count) + count) % count
}

/**
 * 收集中心卡与左右邻卡封面 URL（去重），用于首屏预加载门闩。
 * @param neighborRadius 邻卡半径，默认 1（中心 ±1）
 */
export function collectNeighborCoverUrls(
  slides: Array<Pick<HeroCarouselSlide, 'cover'>>,
  centerIndex: number,
  neighborRadius = 1
): string[] {
  const count = slides.length
  if (!count) return []

  const radius = Math.max(0, Math.floor(neighborRadius))
  const urls: string[] = []
  const seen = new Set<string>()

  for (let delta = -radius; delta <= radius; delta += 1) {
    const idx = normalizeHeroIndex(centerIndex + delta, count)
    const cover = String(slides[idx]?.cover || '').trim()
    if (!cover || seen.has(cover)) continue
    seen.add(cover)
    urls.push(cover)
  }

  return urls
}

/** 取指定下标封面；无数据时返回空串（不再回退空白 Logo） */
export function resolveSlideCoverOrEmpty(
  slides: Array<Pick<HeroCarouselSlide, 'cover'>>,
  index: number
): string {
  if (!slides.length) return ''
  const idx = Math.min(Math.max(0, index), slides.length - 1)
  return String(slides[idx]?.cover || '').trim()
}
