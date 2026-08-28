/**
 * 新版首页 / 我的作品 / 资产库 小分辨率档位（与 home-new-compact-viewport.css 媒体查询一致）
 */

export const HOME_NEW_COMPACT_MEDIA = '(max-width: 1600px) and (max-height: 900px)'
export const HOME_NEW_COMPACT_SHORT_MEDIA = '(max-width: 1600px) and (max-height: 768px)'

export type HomeNewCompactTier = 'normal' | 'compact' | 'compact-short'

export function getHomeNewCompactTier(): HomeNewCompactTier {
  if (typeof window === 'undefined') return 'normal'
  if (window.matchMedia(HOME_NEW_COMPACT_SHORT_MEDIA).matches) return 'compact-short'
  if (window.matchMedia(HOME_NEW_COMPACT_MEDIA).matches) return 'compact'
  return 'normal'
}

/** 按小分辨率档位缩放 Hero 轮播卡片尺寸（大屏返回原值） */
export function scaleForHomeNewCompact(size: number): number {
  const tier = getHomeNewCompactTier()
  if (tier === 'compact-short') return Math.round(size * 0.84)
  if (tier === 'compact') return Math.round(size * 0.88)
  return size
}
