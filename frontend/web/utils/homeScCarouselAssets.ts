import type { HeroCarouselSlide } from '~/types/heroCarousel'
import { resolveCenterVideoUrl } from '~/utils/homeBanner'
/**
 * 本地测试轮播目录 `assets/img/sc` / `static/media/sc` 已清空。
 * 首页轮播只走 Banner 接口（见 `pages/index.vue` → `userHomeBannerList`）。
 * 保留空导出，避免历史 import 断裂。
 */
export const homeScCarouselSlides: HeroCarouselSlide[] = []

/** @deprecated 请使用 `resolveCenterVideoUrl`（`utils/homeBanner.ts`） */
export function resolveHomeScCenterVideoUrl(
  slides: HeroCarouselSlide[],
  centerIndex = 0
): string {
  return resolveCenterVideoUrl(slides, centerIndex)
}
