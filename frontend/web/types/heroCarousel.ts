/** 轮播切换动画时长 */
export const HERO_CAROUSEL_TRANSITION_MS = 720
/** Hero 视频最长播放时长，超时自动暂停并切到下一张 */
export const HERO_VIDEO_MAX_PLAY_MS = 40000
/** 切换完成后先展示静态封面，再开始播放视频 */
export const HERO_POSTER_HOLD_MS = 500
/** 无视频时的轮播停留时长（兜底） */
export const HERO_CAROUSEL_IDLE_INTERVAL_MS = 3000

export interface HeroCarouselSlide {
  id: number | string
  title: string
  cover: string
  videoUrl?: string
  summary?: string
  bannerType?: 'image' | 'video' | 'gif' | string
  linkType?: 'none' | 'external' | 'internal' | string
  linkUrl?: string | null
}

export type HeroPhase = 'idle' | 'playing'

export interface HeroCarouselLayoutMetrics {
  cardW: number
  cardH: number
  viewportW: number
  viewportH: number
  cylinderRadius?: number
}

