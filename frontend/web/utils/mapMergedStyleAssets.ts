import {
buildStyleLibraryCardId,
type StyleLibraryCard
} from '~/composables/usePromptDictionary'
import type { MergedAssetVO } from '~/types/business-api'
import { isMergedAssetOfficial } from '~/utils/mergedAssetSource'

/** 创建/配置入口首屏仅读取 30 个推荐优先的常用风格。 */
export const STYLE_LIBRARY_PAGE_SIZE = 30
/** 精选角标：官方列表前 N 条 */
export const STYLE_LIBRARY_FEATURED_BADGE_COUNT = 3

export function isOfficialStyleLibraryCard(card: StyleLibraryCard): boolean {
  return card.id.toUpperCase().startsWith('OFFICIAL-')
}

/**
 * 将合并资产分页行映射为风格卡片。
 * 个人风格无图跳过；官方前若干条（按已累计数量）打精选角标。
 */
export function mapMergedAssetsToStyleCards(
  list: MergedAssetVO[],
  options: { officialFeaturedStart?: number; skipIds?: Set<string> } = {}
): StyleLibraryCard[] {
  const officialFeaturedStart = options.officialFeaturedStart ?? 0
  const skipIds = options.skipIds
  const out: StyleLibraryCard[] = []
  let officialIndex = officialFeaturedStart

  list.forEach((row, index) => {
    const isOfficial = isMergedAssetOfficial(row.sourceFlag)
    const sourceFlag = isOfficial ? 'official' : 'custom'
    const id = buildStyleLibraryCardId(sourceFlag, row.id)
    if (skipIds?.has(id)) return

    const imageUrl = String(row.imageUrl || '').trim()
    // 个人风格无图多为历史项目封面复用同一 OSS 后被删导致的残留，跳过空白卡片
    if (!isOfficial && !imageUrl) return

    const card: StyleLibraryCard = {
      id,
      name: row.assetName || `风格${index + 1}`,
      assetId: row.id,
      sourceFlag,
      assetName: row.assetName || '',
      promptText: row.promptText ?? '',
      thumbnail: imageUrl,
      featured: false
    }

    if (isOfficial) {
      card.featured = officialIndex < STYLE_LIBRARY_FEATURED_BADGE_COUNT
      officialIndex += 1
    }

    out.push(card)
  })

  return out
}

/** 展示顺序：个人在前，官方在后（与历史 GlobalSetting 一致） */
export function orderStyleLibraryCardsCustomFirst(list: StyleLibraryCard[]): StyleLibraryCard[] {
  const custom: StyleLibraryCard[] = []
  const official: StyleLibraryCard[] = []
  for (const card of list) {
    if (isOfficialStyleLibraryCard(card)) official.push(card)
    else custom.push(card)
  }
  return [...custom, ...official]
}
