/** 素材资产域：官方/自定义资产、合并资产分页、资产中心（分类树/列表/明细）、旧行结构映射、OSS 上传。 */
import type {
ApiEnvelope,
AssetCenterCategoryTreeVO,
AssetCenterDetailRequest,
AssetCenterDetailVO,
AssetCenterItemVO,
AssetCenterListRequest,
MergedAssetPageRequest,
MergedAssetVO,
OssRemoteUploadResult,
UserAssetCustomCreateData,
UserAssetCustomCreateRequest,
UserAssetCustomDeleteRequest,
UserAssetCustomDetailRequest,
UserAssetCustomListRequest,
UserAssetCustomRow,
UserAssetCustomTypeItem,
UserAssetCustomUpdateRequest,
UserAssetListRequest,
UserAssetOfficialQueryRequest,
UserAssetOfficialRow,
UserAssetRow,
UserAssetStyleCategoryItem
} from '~/types/business-api'
import { request } from '~/utils/api'
import { rpsRowToUserAssetRow,userAssetRpsList } from '~/utils/business/rps'
import {
extractDataArray,
extractPageRows,
OSS_UPLOAD_TIMEOUT_MS,
runListDedupe,
stableRequestKey,
unwrap,
type ListBurstSlot
} from '~/utils/business/shared'
import { isMergedAssetOfficial } from '~/utils/mergedAssetSource'

const userAssetCustomTypeListInflight = new Map<string, Promise<UserAssetCustomTypeItem[]>>()
const userAssetCustomTypeListBurst: ListBurstSlot<UserAssetCustomTypeItem[]> = { current: null }

const userAssetStyleCategoryListInflight = new Map<string, Promise<UserAssetStyleCategoryItem[]>>()
const userAssetStyleCategoryListBurst: ListBurstSlot<UserAssetStyleCategoryItem[]> = { current: null }

const userAssetMergedPageInflight = new Map<string, Promise<{ total: number; list: MergedAssetVO[] }>>()
const userAssetMergedPageBurst: ListBurstSlot<{ total: number; list: MergedAssetVO[] }> = {
  current: null
}

/** 写操作成功后清空合并资产列表缓存，避免紧接着刷新仍命中旧数据。 */
export function invalidateUserAssetMergedPageCache(): void {
  userAssetMergedPageInflight.clear()
  userAssetMergedPageBurst.current = null
}

/** 官方资产行 → 旧版列表行（兼容导入弹窗等） */
export function officialRowToUserAssetRow(r: UserAssetOfficialRow): UserAssetRow {
  return {
    id: r.id,
    assetType: r.assetType,
    assetName: r.assetName,
    personalityDesc: r.promptText ?? undefined,
    refImageUrl: r.imageUrl ?? null,
    extraImages: null,
    sourceType: 0
  }
}

/** 官方资产：POST /api/user/asset/official/query（全量，不分页） */
export async function userAssetOfficialQuery(
  body?: UserAssetOfficialQueryRequest
): Promise<UserAssetOfficialRow[]> {
  const res = await request.post('/api/user/asset/official/query', body ?? {})
  return extractDataArray<UserAssetOfficialRow>(res)
}

/** 用户自定义资产类型：POST /api/user/asset/custom/type/list */
export async function userAssetCustomTypeList(): Promise<UserAssetCustomTypeItem[]> {
  return runListDedupe('asset-custom-type-list', userAssetCustomTypeListInflight, userAssetCustomTypeListBurst, async () => {
    const res = await request.post<ApiEnvelope<UserAssetCustomTypeItem[]>>(
      '/api/user/asset/custom/type/list',
      {}
    )
    const data = unwrap(res)
    return Array.isArray(data) ? data : []
  })
}

/** 精选风格分类：POST /api/user/asset/style/category/list */
export async function userAssetStyleCategoryList(): Promise<UserAssetStyleCategoryItem[]> {
  return runListDedupe(
    'asset-style-category-list',
    userAssetStyleCategoryListInflight,
    userAssetStyleCategoryListBurst,
    async () => {
      const res = await request.post<ApiEnvelope<UserAssetStyleCategoryItem[]>>(
        '/api/user/asset/style/category/list',
        {}
      )
      const data = unwrap(res)
      return Array.isArray(data) ? data : []
    }
  )
}

/** 用户自定义资产分页：POST /api/user/asset/custom/list */
export async function userAssetCustomList(
  body: UserAssetCustomListRequest = {}
): Promise<{ total: number; list: UserAssetCustomRow[] }> {
  const res = await request.post<ApiEnvelope<{ total?: number; list?: UserAssetCustomRow[] }>>(
    '/api/user/asset/custom/list',
    body
  )
  const data = unwrap(res) ?? {}
  const list = Array.isArray(data.list) ? data.list : []
  const total = typeof data.total === 'number' ? data.total : list.length
  return { total, list }
}

/** 用户自定义资产创建：POST /api/user/asset/custom/create */
export async function userAssetCustomCreate(
  body: UserAssetCustomCreateRequest
): Promise<UserAssetCustomCreateData> {
  const res = await request.post<ApiEnvelope<UserAssetCustomCreateData>>('/api/user/asset/custom/create', body)
  const data = unwrap(res)
  invalidateUserAssetMergedPageCache()
  return data
}

/** 用户自定义资产详情：POST /api/user/asset/custom/detail */
export async function userAssetCustomDetail(
  body: UserAssetCustomDetailRequest
): Promise<UserAssetCustomRow> {
  const res = await request.post<ApiEnvelope<UserAssetCustomRow>>('/api/user/asset/custom/detail', body)
  return unwrap(res)
}

/** 用户自定义资产修改：POST /api/user/asset/custom/update */
export async function userAssetCustomUpdate(body: UserAssetCustomUpdateRequest): Promise<boolean> {
  const res = await request.post<ApiEnvelope<boolean>>('/api/user/asset/custom/update', body)
  const success = Boolean(unwrap(res))
  if (success) invalidateUserAssetMergedPageCache()
  return success
}

/** 用户自定义资产删除：POST /api/user/asset/custom/delete */
export async function userAssetCustomDelete(body: UserAssetCustomDeleteRequest): Promise<boolean> {
  const res = await request.post<ApiEnvelope<boolean>>('/api/user/asset/custom/delete', body)
  const success = Boolean(unwrap(res))
  if (success) invalidateUserAssetMergedPageCache()
  return success
}

/** 合并个人+官方参考资产分页：POST /api/user/asset/custom/page */
export async function userAssetMergedPage(
  body: MergedAssetPageRequest = {}
): Promise<{ total: number; list: MergedAssetVO[] }> {
  const reqBody = body ?? {}
  const key = stableRequestKey(reqBody)
  return runListDedupe(key, userAssetMergedPageInflight, userAssetMergedPageBurst, async () => {
    const res = await request.post<ApiEnvelope<{ total?: number; list?: MergedAssetVO[] }>>(
      '/api/user/asset/custom/page',
      reqBody
    )
    const data = unwrap(res) ?? {}
    const list = Array.isArray(data.list) ? data.list : []
    const total = typeof data.total === 'number' ? data.total : list.length
    return { total, list }
  })
}

/** 从分类树接口响应中取出树数组（兼容 data / rows / data.tree 等结构） */
function extractAssetCenterCategoryTree(res: unknown): unknown[] {
  const { rows } = extractPageRows<unknown>(res)
  if (rows.length) return rows
  const r = res as Record<string, unknown> | null
  const data = r?.data
  if (data && typeof data === 'object' && !Array.isArray(data)) {
    const nested = data as Record<string, unknown>
    for (const key of ['tree', 'list', 'children']) {
      if (Array.isArray(nested[key])) return nested[key] as unknown[]
    }
  }
  return []
}

function treeNodeNum(v: unknown): number | null {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** 归一化分类树节点（接口实际结构：list[].episodes[].categories[]） */
function normalizeAssetCenterTreeNode(
  raw: unknown,
  ctx: { projectId?: number; projectName?: string; episodeId?: number | null } = {}
): AssetCenterCategoryTreeVO {
  const n = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  const projectId = treeNodeNum(n.projectId ?? n.id) ?? ctx.projectId ?? 0
  const projectName =
    String(n.projectName ?? ctx.projectName ?? '').trim() || `项目${projectId}`

  // 分类叶子：categories[] 项
  const categoryCodeRaw = n.categoryCode ?? n.code ?? n.key
  const hasCategoryCode =
    categoryCodeRaw != null && String(categoryCodeRaw).trim().length > 0
  const episodesArr = n.episodes
  const categoriesArr = n.categories
  if (
    hasCategoryCode &&
    !Array.isArray(episodesArr) &&
    !Array.isArray(categoriesArr)
  ) {
    const code = String(categoryCodeRaw).trim()
    const categoryNameRaw = n.categoryName ?? n.label ?? n.name
    return {
      projectId,
      projectName,
      episodeId: ctx.episodeId ?? treeNodeNum(n.episodeId),
      categoryCode: code,
      categoryName:
        categoryNameRaw != null && String(categoryNameRaw).trim()
          ? String(categoryNameRaw).trim()
          : code,
      assetCount: typeof n.assetCount === 'number' ? n.assetCount : null
    }
  }

  // 剧集层：episodes[] 项（含 categories[]）
  if (Array.isArray(categoriesArr)) {
    const episodeId = treeNodeNum(n.episodeId) ?? ctx.episodeId ?? 0
    const episodeTitle =
      n.episodeTitle != null && String(n.episodeTitle).trim()
        ? String(n.episodeTitle).trim()
        : n.episodeName != null && String(n.episodeName).trim()
          ? String(n.episodeName).trim()
          : null
    return {
      projectId,
      projectName,
      projectType: (n.projectType as string) ?? null,
      episodeId,
      episodeNo: treeNodeNum(n.episodeNo),
      episodeTitle,
      assetCount: typeof n.assetCount === 'number' ? n.assetCount : null,
      children: categoriesArr.map((c) =>
        normalizeAssetCenterTreeNode(c, { projectId, projectName, episodeId })
      )
    }
  }

  // 项目层：list[] 项（含 episodes[]）
  const childSource = Array.isArray(episodesArr)
    ? episodesArr
    : Array.isArray(n.children)
      ? n.children
      : null
  if (childSource) {
    return {
      projectId,
      projectName,
      projectType: (n.projectType as string) ?? null,
      assetCount: typeof n.assetCount === 'number' ? n.assetCount : null,
      children: childSource.map((c) =>
        normalizeAssetCenterTreeNode(c, { projectId, projectName })
      )
    }
  }

  return {
    projectId,
    projectName,
    projectType: (n.projectType as string) ?? null,
    episodeId: treeNodeNum(n.episodeId),
    episodeNo: treeNodeNum(n.episodeNo),
    episodeTitle:
      n.episodeTitle != null && String(n.episodeTitle).trim()
        ? String(n.episodeTitle).trim()
        : n.episodeName != null && String(n.episodeName).trim()
          ? String(n.episodeName).trim()
          : null,
    categoryCode: hasCategoryCode ? String(categoryCodeRaw).trim() : null,
    categoryName: hasCategoryCode ? String(n.categoryName ?? categoryCodeRaw).trim() : null,
    assetCount: typeof n.assetCount === 'number' ? n.assetCount : null
  }
}

function normalizeAssetCenterCategoryTree(raw: unknown): AssetCenterCategoryTreeVO[] {
  if (!raw) return []
  if (Array.isArray(raw)) return raw.map((item) => normalizeAssetCenterTreeNode(item))
  if (typeof raw === 'object') {
    const o = raw as Record<string, unknown>
    for (const key of ['list', 'tree', 'children', 'rows', 'data']) {
      const v = o[key]
      if (Array.isArray(v)) return v.map((item) => normalizeAssetCenterTreeNode(item))
    }
  }
  return []
}

/** 个人资产中心分类树：POST /api/user/asset/center/category/tree */
export async function userAssetCenterCategoryTree(
  body: Record<string, never> = {}
): Promise<AssetCenterCategoryTreeVO[]> {
  const res = await request.post('/api/user/asset/center/category/tree', body)
  return normalizeAssetCenterCategoryTree(extractAssetCenterCategoryTree(res))
}

/** 个人资产中心列表：POST /api/user/asset/center/list */
export async function userAssetCenterList(
  body: AssetCenterListRequest
): Promise<{ total: number; list: AssetCenterItemVO[] }> {
  const res = await request.post('/api/user/asset/center/list', body)
  const { rows, total } = extractPageRows<AssetCenterItemVO>(res)
  return { total, list: rows }
}

/** 个人资产中心明细：POST /api/user/asset/center/detail */
export async function userAssetCenterDetail(
  body: AssetCenterDetailRequest
): Promise<AssetCenterDetailVO> {
  const res = await request.post<ApiEnvelope<AssetCenterDetailVO>>(
    '/api/user/asset/center/detail',
    body
  )
  return unwrap(res)
}

/** 合并参考资产行 → 旧版列表行（导入弹窗等） */
export function mergedAssetToUserAssetRow(r: MergedAssetVO): UserAssetRow {
  return {
    id: r.id,
    assetType: r.assetType,
    assetName: r.assetName,
    personalityDesc: r.personalityDesc ?? r.promptText ?? undefined,
    refImageUrl: r.imageUrl ?? null,
    extraImages: null,
    sourceType: isMergedAssetOfficial(r.sourceFlag) ? 0 : 1,
    createTime: r.createTime ?? undefined
  }
}

/** 资产中心列表项 → 旧版列表行 */
export function assetCenterItemToUserAssetRow(r: AssetCenterItemVO): UserAssetRow {
  const mediaUrl = String(r.mediaUrl ?? r.coverUrl ?? '').trim()
  const name = String(r.name ?? r.assetName ?? '').trim()
  const categoryCode = String(r.categoryCode ?? r.assetType ?? '').trim()
  return {
    id: r.id,
    projectId: r.projectId,
    episodeId: r.episodeId,
    assetType: categoryCode || r.assetType || '',
    assetName: name || r.assetName || '',
    refImageUrl: mediaUrl || null,
    extraImages: null,
    createTime: r.createTime ?? undefined,
    updateTime: r.updateTime ?? undefined
  }
}

/** 资产中心明细 → 旧版列表行（content 摘要写入 personalityDesc） */
export function assetCenterDetailToUserAssetRow(r: AssetCenterDetailVO): UserAssetRow {
  const c = r.content ?? {}
  const intro =
    (typeof c.introduction === 'string' && c.introduction.trim()) ||
    (typeof c.summary === 'string' && c.summary.trim()) ||
    (typeof c.storyScript === 'string' && c.storyScript.trim()) ||
    (typeof c.originalText === 'string' && c.originalText.trim()) ||
    undefined
  const mediaUrl = r.imageUrl ?? r.coverUrl ?? r.videoUrl ?? null
  return {
    id: r.id,
    projectId: r.projectId,
    episodeId: r.episodeId,
    assetType: r.assetType ?? r.categoryCode,
    assetName: r.name ?? r.assetName ?? undefined,
    personalityDesc: intro,
    refImageUrl: mediaUrl,
    extraImages: null,
    createTime: r.createTime ?? undefined,
    updateTime: r.updateTime ?? undefined
  }
}

/**
 * @deprecated v2.0 改用 userAssetRpsList
 * 个人资产列表（走 rps/list 并映射为旧行结构）
 */
export async function userAssetList(body?: UserAssetListRequest): Promise<{ total: number; rows: UserAssetRow[] }> {
  const { rows } = await userAssetRpsList({
    projectId: body?.projectId,
    episodeId: body?.episodeId,
    assetType: body?.assetType
  })
  const mapped = rows.map(rpsRowToUserAssetRow)
  return { total: mapped.length, rows: mapped }
}

/**
 * @deprecated v2.0 改用 userAssetOfficialQuery
 * 官方资产（走 official/query 并映射为旧行结构）
 */
export async function userAssetOfficialList(body?: UserAssetListRequest): Promise<{ total: number; rows: UserAssetRow[] }> {
  let list = await userAssetOfficialQuery({
    assetType: body?.assetType,
    assetName: body?.assetName
  })
  if (body?.assetName?.trim()) {
    const q = body.assetName.trim().toLowerCase()
    list = list.filter((r) => (r.assetName || '').toLowerCase().includes(q))
  }
  const rows = list.map(officialRowToUserAssetRow)
  return { total: rows.length, rows }
}

/** OSS 单文件上传（multipart）/api/user/oss/upload */
export async function ossRemoteUploadSingle(
  file: File,
  customDir?: string
): Promise<OssRemoteUploadResult> {
  const fd = new FormData()
  fd.append('files', file)
  if (customDir != null && customDir !== '') fd.append('customDir', customDir)
  const res = await request.post<ApiEnvelope<OssRemoteUploadResult[]>>(
    '/api/user/oss/upload',
    fd,
    {
      timeout: OSS_UPLOAD_TIMEOUT_MS,
      headers: { 'Content-Type': false } as unknown as Record<string, string>
    }
  )
  const data = unwrap(res)
  if (Array.isArray(data)) {
    if (data.length === 0) throw Object.assign(new Error('上传失败：未返回文件地址'), { code: 500 })
    return data[0]!
  }
  if (data && typeof data === 'object') return data as OssRemoteUploadResult
  throw Object.assign(new Error('上传失败：返回数据格式异常'), { code: 500 })
}

/** OSS 多文件上传（/api/user/oss/upload） */
export async function ossRemoteUploadMultiple(
  files: File[],
  customDir?: string
): Promise<OssRemoteUploadResult[]> {
  const fd = new FormData()
  for (const f of files) {
    fd.append('files', f)
  }
  if (customDir != null && customDir !== '') fd.append('customDir', customDir)
  const res = await request.post<ApiEnvelope<OssRemoteUploadResult[]>>(
    '/api/user/oss/upload',
    fd,
    {
      timeout: OSS_UPLOAD_TIMEOUT_MS,
      headers: { 'Content-Type': false } as unknown as Record<string, string>
    }
  )
  return unwrap(res)
}

/** 任意数量图片：按 3 个一批调用统一上传接口 */
export async function ossRemoteUploadImages(files: File[], customDir = 'images'): Promise<string[]> {
  const urls: string[] = []
  let i = 0
  while (i < files.length) {
    const chunk = files.slice(i, i + 3)
    if (chunk.length === 1) {
      const one = await ossRemoteUploadSingle(chunk[0]!, customDir)
      urls.push(one.url)
    } else {
      const list = await ossRemoteUploadMultiple(chunk, customDir)
      urls.push(...list.map((x) => x.url))
    }
    i += 3
  }
  return urls
}
