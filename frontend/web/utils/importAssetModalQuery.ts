import type { AssetCenterCategoryTreeVO, UserAssetRow } from '~/types/business-api'
import {
  assetCenterItemToUserAssetRow,
  mergedAssetToUserAssetRow,
  userAssetCenterList,
  userAssetMergedPage
} from '~/utils/businessApi'
import {
  CENTER_CATEGORY_TO_ASSET_TYPE,
  DOCUMENT_KEY_TO_API_TYPE,
  DOCUMENT_STRUCTURE,
  MATERIAL_CATEGORY_ROWS
} from './importAssetModalConfig'

export * from './importAssetModalConfig'
export * from './importAssetModalTree'

export function categoryCodeToApiType(categoryCode: string): string | undefined {
  return (
    CENTER_CATEGORY_TO_ASSET_TYPE[categoryCode] ??
    DOCUMENT_KEY_TO_API_TYPE[categoryCode]
  )
}

/** 资产中心分类是否为图片类（角色/场景/道具/分镜图等） */
const IMAGE_CATEGORY_CODE_SET = new Set([
  'role_image',
  'scene_image',
  'prop_image',
  'storyboard_image',
  'character-image',
  'scene-image',
  'prop-image',
  'storyboard-image'
])

export function isImageCategoryCode(code: string | null | undefined): boolean {
  if (!code) return false
  if (IMAGE_CATEGORY_CODE_SET.has(code)) return true
  return code.endsWith('_image')
}

const VIDEO_CATEGORY_CODE_SET = new Set(['storyboard_video', 'storyboard-video'])

export function isVideoCategoryCode(code: string | null | undefined): boolean {
  if (!code) return false
  if (VIDEO_CATEGORY_CODE_SET.has(code)) return true
  return code.endsWith('_video')
}

/** 从导入弹窗选中节点解析 categoryCode */
export function resolveImportModalCategoryCode(
  category: string | null | undefined,
  selectedCategory?: { key?: string } | null
): string | null {
  if (selectedCategory?.key) return String(selectedCategory.key)
  const cat = String(category || '').trim()
  if (!cat || cat === 'material-library') return null
  if (cat.startsWith('material-')) return cat.replace(/^material-/, '')
  if (cat.startsWith('project-') || cat.startsWith('episode-')) return null
  const parts = cat.split('-')
  if (parts.length >= 3 && /^\d+$/.test(parts[0] ?? '') && /^\d+$/.test(parts[1] ?? '')) {
    return parts.slice(2).join('-')
  }
  const dash = cat.indexOf('-')
  if (dash > 0) return cat.slice(dash + 1)
  return null
}

export type ImportAssetDisplayMode = 'folder' | 'image' | 'video' | 'file'

export function resolveImportAssetDisplayMode(
  items: Array<{ type?: string; thumbnail?: string }>,
  categoryCode: string | null
): ImportAssetDisplayMode {
  if (!items.length) return 'folder'
  if (items.every((a) => a.type === 'folder')) return 'folder'
  if (isVideoCategoryCode(categoryCode)) return 'video'
  const mediaItems = items.filter((item) => item.type !== 'folder')
  if (mediaItems.length > 0 && mediaItems.every((item) => item.type === 'video')) return 'video'
  if (isImageCategoryCode(categoryCode)) return 'image'
  if (items.some((a) => a.type === 'image' && a.thumbnail)) return 'image'
  if (items.some((a) => a.type === 'video' && a.thumbnail)) return 'image'
  return 'file'
}

export function materialKeyToApiType(key: string): string {
  return MATERIAL_CATEGORY_ROWS.find((r) => r.key === key)?.apiType ?? 'file'
}

export function materialLabelToKey(label: string): string | null {
  return MATERIAL_CATEGORY_ROWS.find((r) => r.label === label)?.key ?? null
}

export function documentLabelToKey(label: string): string | null {
  return DOCUMENT_STRUCTURE.find((d) => d.label === label)?.key ?? null
}

function formatApiDate(s?: string | null): string {
  if (!s) return ''
  const d = new Date(s.replace(/-/g, '/'))
  if (Number.isNaN(d.getTime())) return s
  return d.toLocaleDateString('zh-CN')
}

/** 将接口行转为导入弹窗卡片数据 */
export function mapUserAssetRowToImportItem(row: UserAssetRow) {
  const url = (row.refImageUrl || '').trim()
  const extras = row.extraImages
    ? String(row.extraImages)
        .split(/[;,\s]+/)
        .map((x) => x.trim())
        .filter(Boolean)
    : []
  const name = row.assetName || '未命名'
  const categoryCode = String(row.assetType || '').trim()
  const video =
    isVideoCategoryCode(categoryCode) || /\.(mp4|webm|mov|avi|mkv|m4v)(\?|$)/i.test(url)
  const thumb = url || extras[0] || ''
  let type: 'image' | 'video' | 'script' = 'script'
  if (video) type = 'video'
  else if (thumb || isImageCategoryCode(categoryCode)) type = 'image'

  return {
    id: String(row.id),
    name,
    type,
    updatedAt: formatApiDate(row.updateTime || row.createTime),
    size: 0,
    thumbnail: type === 'video' ? '' : thumb,
    url: url || thumb,
    content: row.personalityDesc || '',
    raw: row
  }
}

export async function fetchOfficialTypeTotal(assetType: string): Promise<number> {
  const rows = await fetchOfficialMaterialAllRows()
  return countRowsByAssetType(rows).get(assetType) || 0
}

export async function fetchPersonalTypeTotal(
  projectId: number,
  episodeId: number,
  categoryCode?: string
): Promise<number> {
  const { total } = await userAssetCenterList({
    projectId,
    episodeId,
    ...(categoryCode ? { categoryCode } : {}),
    pageNum: 1,
    pageSize: 1
  })
  return total
}

const officialMaterialAllCache = { rows: null as UserAssetRow[] | null }
let officialMaterialAllInflight: Promise<UserAssetRow[]> | null = null

export function clearOfficialMaterialAllCache() {
  officialMaterialAllCache.rows = null
  officialMaterialAllInflight = null
}

/**
 * 素材库：不传 assetType / keyword，一次（必要时翻页）拉齐官方素材，按 scope 缓存。
 * 与原先多次按 assetType 请求 OFFICIAL 的展示范围一致。
 */
export async function fetchOfficialMaterialAllRows(
  options?: { force?: boolean }
): Promise<UserAssetRow[]> {
  if (!options?.force && officialMaterialAllCache.rows) {
    return officialMaterialAllCache.rows
  }
  if (!options?.force && officialMaterialAllInflight) {
    return officialMaterialAllInflight
  }

  // 异步体首个 await 之后才回读 task 做在途校验，此时 const 已完成赋值
  const task: Promise<UserAssetRow[]> = (async () => {
    const pageSize = 100
    let pageNum = 1
    let total = Infinity
    const all: UserAssetRow[] = []

    while (all.length < total) {
      const { list, total: t } = await userAssetMergedPage({
        sourceFlag: 'OFFICIAL',
        pageNum,
        pageSize
      })
      total = Number.isFinite(t) ? t : list.length
      all.push(...list.map(mergedAssetToUserAssetRow))
      if (!list.length || list.length < pageSize) break
      pageNum += 1
      if (pageNum > 50) break
    }

    return all
  })()

  officialMaterialAllInflight = task
  try {
    const all = await task
    // 仍是当前在途任务才写缓存（force 重取可能已替换在途槽位）
    if (officialMaterialAllInflight === task) {
      officialMaterialAllCache.rows = all
    }
    return all
  } finally {
    if (officialMaterialAllInflight === task) {
      officialMaterialAllInflight = null
    }
  }
}

/** 按 assetType 统计数量 */
export function countRowsByAssetType(rows: UserAssetRow[]): Map<string, number> {
  const map = new Map<string, number>()
  for (const row of rows) {
    const type = String(row.assetType || '').trim()
    if (!type) continue
    map.set(type, (map.get(type) || 0) + 1)
  }
  return map
}

/** 从全量官方素材中筛某一 assetType */
export function filterRowsByAssetType(
  rows: UserAssetRow[],
  assetType: string
): UserAssetRow[] {
  const type = String(assetType || '').trim()
  if (!type) return rows
  return rows.filter((r) => String(r.assetType || '').trim() === type)
}

/** 素材库分类文件夹：用全量 list 按 apiType 聚合计数（draft/misc 与 file 共用同一计数，与原先一致） */
export function buildMaterialFolderItems(
  countByType: Map<string, number>,
  updatedAt: string
): Array<{
  id: string
  name: string
  type: 'folder'
  updatedAt: string
  itemCount: number
}> {
  return MATERIAL_CATEGORY_ROWS.map((c) => ({
    id: `folder-m-${c.key}`,
    name: c.label,
    type: 'folder' as const,
    updatedAt,
    itemCount: countByType.get(c.apiType) || 0
  }))
}

/** 官方素材列表：有全量缓存则本地过滤，否则按 assetType 单次请求 */
export async function fetchOfficialAssetsAsRows(assetType: string): Promise<UserAssetRow[]> {
  if (officialMaterialAllCache.rows) {
    return filterRowsByAssetType(officialMaterialAllCache.rows, assetType)
  }
  const { list } = await userAssetMergedPage({
    assetType,
    sourceFlag: 'OFFICIAL',
    pageNum: 1,
    pageSize: 100
  })
  return list.map(mergedAssetToUserAssetRow)
}

/** 本作品/历史个人资产列表（center/list）；不传 categoryCode 时拉该范围内全部分类混合 */
export async function fetchPersonalCenterRows(
  projectId: number,
  episodeId: number,
  categoryCode?: string,
  assetType?: string
): Promise<UserAssetRow[]> {
  const { list } = await userAssetCenterList({
    projectId,
    episodeId,
    ...(categoryCode ? { categoryCode } : {}),
    ...(assetType ? { assetType } : {}),
    pageNum: 1,
    pageSize: 100
  })
  return list.map(assetCenterItemToUserAssetRow)
}

const personalCenterAllCache = new Map<string, UserAssetRow[]>()
const personalCenterAllInflight = new Map<string, Promise<UserAssetRow[]>>()

function personalCenterCacheKey(projectId: number, episodeId: number): string {
  return `${projectId}:${episodeId}`
}

export function clearPersonalCenterAllCache(projectId?: number, episodeId?: number) {
  if (projectId == null) {
    personalCenterAllCache.clear()
    personalCenterAllInflight.clear()
    return
  }
  const key = personalCenterCacheKey(projectId, episodeId ?? 0)
  personalCenterAllCache.delete(key)
  personalCenterAllInflight.delete(key)
}

/**
 * 不传 categoryCode，一次（必要时翻页）拉齐该项目/剧集下全部分类资产，并按 scope 缓存。
 * 供导入弹窗文件夹统计与进入分类后过滤复用，避免按分类多次请求 list。
 */
export async function fetchPersonalCenterAllRows(
  projectId: number,
  episodeId: number,
  options?: { force?: boolean }
): Promise<UserAssetRow[]> {
  const key = personalCenterCacheKey(projectId, episodeId)
  if (!options?.force && personalCenterAllCache.has(key)) {
    return personalCenterAllCache.get(key)!
  }
  if (!options?.force) {
    const inflight = personalCenterAllInflight.get(key)
    if (inflight) return inflight
  }

  const task: Promise<UserAssetRow[]> = (async () => {
    const pageSize = 100
    let pageNum = 1
    let total = Infinity
    const all: UserAssetRow[] = []

    while (all.length < total) {
      const { list, total: t } = await userAssetCenterList({
        projectId,
        episodeId,
        pageNum,
        pageSize
      })
      total = Number.isFinite(t) ? t : list.length
      all.push(...list.map(assetCenterItemToUserAssetRow))
      if (!list.length || list.length < pageSize) break
      pageNum += 1
      if (pageNum > 50) break
    }

    return all
  })()

  personalCenterAllInflight.set(key, task)
  try {
    const all = await task
    // 仍是当前在途任务才写缓存（force 重取可能已替换在途槽位）
    if (personalCenterAllInflight.get(key) === task) {
      personalCenterAllCache.set(key, all)
    }
    return all
  } finally {
    if (personalCenterAllInflight.get(key) === task) {
      personalCenterAllInflight.delete(key)
    }
  }
}

/** 按 categoryCode（映射到 UserAssetRow.assetType）统计数量 */
export function countRowsByCategoryCode(rows: UserAssetRow[]): Map<string, number> {
  const map = new Map<string, number>()
  for (const row of rows) {
    const code = String(row.assetType || '').trim()
    if (!code) continue
    map.set(code, (map.get(code) || 0) + 1)
  }
  return map
}

/** 从全量 list 中筛出某一分类 */
export function filterRowsByCategoryCode(
  rows: UserAssetRow[],
  categoryCode: string
): UserAssetRow[] {
  const code = String(categoryCode || '').trim()
  if (!code) return rows
  return rows.filter((r) => String(r.assetType || '').trim() === code)
}

/** 进入分类：有全量缓存则本地过滤，否则按 categoryCode 单次请求 */
export async function fetchPersonalCenterRowsByCategory(
  projectId: number,
  episodeId: number,
  categoryCode: string
): Promise<UserAssetRow[]> {
  const key = personalCenterCacheKey(projectId, episodeId)
  const cached = personalCenterAllCache.get(key)
  if (cached) return filterRowsByCategoryCode(cached, categoryCode)
  return fetchPersonalCenterRows(projectId, episodeId, categoryCode)
}

/** 分类文件夹卡片：优先树 assetCount，否则用全量 list 聚合计数 */
export function buildCategoryFolderItems(
  categories: AssetCenterCategoryTreeVO[],
  countByCode: Map<string, number>,
  idPrefix: string,
  updatedAt: string
): Array<{
  id: string
  name: string
  type: 'folder'
  updatedAt: string
  itemCount: number
}> {
  return categories.map((cat) => {
    const code = cat.categoryCode || ''
    const fromTree = typeof cat.assetCount === 'number' ? cat.assetCount : null
    const total = fromTree != null ? fromTree : countByCode.get(code) || 0
    return {
      id: `${idPrefix}${code}`,
      name: cat.categoryName || code,
      type: 'folder' as const,
      updatedAt,
      itemCount: total
    }
  })
}

/** @deprecated 请用 fetchPersonalCenterRows */
export async function fetchPersonalRpsAsRows(
  projectId: number,
  episodeId: number,
  assetType: string
): Promise<UserAssetRow[]> {
  const doc = DOCUMENT_STRUCTURE.find((d) => d.apiType === assetType)
  const categoryCode = doc?.key ?? assetType
  return fetchPersonalCenterRows(projectId, episodeId, categoryCode, assetType)
}

/** 分类文件夹数量：优先接口 assetCount，否则一次拉全量后按分类聚合（带缓存） */
export async function resolveCategoryFolderCount(
  projectId: number,
  episodeId: number,
  cat: AssetCenterCategoryTreeVO
): Promise<number> {
  if (typeof cat.assetCount === 'number') return cat.assetCount
  const code = cat.categoryCode
  if (!code) return 0
  try {
    const rows = await fetchPersonalCenterAllRows(projectId, episodeId)
    return countRowsByCategoryCode(rows).get(code) || 0
  } catch {
    return 0
  }
}
