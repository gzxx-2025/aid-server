/** 杂项域：官方参数词库、枚举字典、常见问题。 */
import type {
ApiEnvelope,
EnumDictGroup,
EnumDictListRequest,
OfficialPromptCategoryItem,
OfficialPromptItem,
OfficialPromptItemDetailRequest,
OfficialPromptItemListRequest,
PaginatedListResult,
UserFaqDetail,
UserFaqDetailRequest,
UserFaqListItem,
UserFaqListRequest
} from '~/types/business-api'
import { request } from '~/utils/api'
import {
API_DEFAULT_PAGE_SIZE,
extractPaginatedResponse,
runListDedupe,
stableRequestKey,
unwrap,
type ListBurstSlot
} from '~/utils/business/shared'

/** 官方只读参数词库：分类列表 POST /api/user/prompt/official/category/list */
export async function userPromptOfficialCategoryList(): Promise<OfficialPromptCategoryItem[]> {
  const res = await request.post<ApiEnvelope<OfficialPromptCategoryItem[]>>('/api/user/prompt/official/category/list', {})
  const data = unwrap(res)
  return Array.isArray(data) ? data : []
}

/** 官方只读参数词库：词条列表（分页） POST /api/user/prompt/official/item/list */
export async function userPromptOfficialItemListPage(
  body: OfficialPromptItemListRequest
): Promise<PaginatedListResult<OfficialPromptItem>> {
  const pageNum = body.pageNum ?? 1
  const pageSize = body.pageSize ?? API_DEFAULT_PAGE_SIZE
  const res = await request.post('/api/user/prompt/official/item/list', { ...body, pageNum, pageSize })
  return extractPaginatedResponse<OfficialPromptItem>(res, pageNum, pageSize)
}

/** 官方词库词条全量拉取 pageSize（数据量有限，避免初始化时分页循环多次请求） */
const OFFICIAL_PROMPT_ITEM_ALL_PAGE_SIZE = 2000

const officialPromptItemListCache = new Map<string, OfficialPromptItem[]>()
const officialPromptItemListInflight = new Map<string, Promise<OfficialPromptItem[]>>()

function officialPromptItemListCacheKey(
  body: Omit<OfficialPromptItemListRequest, 'pageNum' | 'pageSize'>
): string {
  const codes = body.categoryCodes?.length
    ? [...body.categoryCodes].sort().join(',')
    : (body.categoryCode ?? '')
  return `${codes}|${body.keyword ?? ''}`
}

/** 拉取满足条件的全部官方词条（单次大页请求，避免循环分页） */
export async function fetchAllOfficialPromptItems(
  body: Omit<OfficialPromptItemListRequest, 'pageNum' | 'pageSize'>
): Promise<OfficialPromptItem[]> {
  const { rows } = await userPromptOfficialItemListPage({
    ...body,
    pageNum: 1,
    pageSize: OFFICIAL_PROMPT_ITEM_ALL_PAGE_SIZE
  })
  return rows
}

/** 官方只读参数词库：词条列表 POST /api/user/prompt/official/item/list（未传分页时单次拉取并缓存） */
export async function userPromptOfficialItemList(
  body: OfficialPromptItemListRequest
): Promise<OfficialPromptItem[]> {
  if (body.pageNum != null || body.pageSize != null) {
    const { rows } = await userPromptOfficialItemListPage(body)
    return rows
  }

  const cacheKey = officialPromptItemListCacheKey(body)
  const cached = officialPromptItemListCache.get(cacheKey)
  if (cached) return cached

  let inflight = officialPromptItemListInflight.get(cacheKey)
  if (!inflight) {
    inflight = fetchAllOfficialPromptItems(body).then((rows) => {
      officialPromptItemListCache.set(cacheKey, rows)
      return rows
    }).finally(() => {
      officialPromptItemListInflight.delete(cacheKey)
    })
    officialPromptItemListInflight.set(cacheKey, inflight)
  }
  return inflight
}

/** 官方只读参数词库：词条详情 POST /api/user/prompt/official/item/detail */
export async function userPromptOfficialItemDetail(
  body: OfficialPromptItemDetailRequest
): Promise<OfficialPromptItem> {
  const res = await request.post<ApiEnvelope<OfficialPromptItem>>('/api/user/prompt/official/item/detail', body)
  return unwrap(res)
}

const userDictEnumListInflight = new Map<string, Promise<EnumDictGroup[]>>()
const userDictEnumListBurst: ListBurstSlot<EnumDictGroup[]> = { current: null }

/** 枚举字典：查询枚举 POST /api/user/dict/enum/list */
export async function userDictEnumList(body: EnumDictListRequest): Promise<EnumDictGroup[]> {
  const enumTypes = body.enumTypes ?? []
  // 去重 key 按类型名排序，避免同一批枚举因顺序不同打成两次
  const key = stableRequestKey({ enumTypes: [...enumTypes].map(String).sort() })
  return runListDedupe(key, userDictEnumListInflight, userDictEnumListBurst, async () => {
    const res = await request.post<ApiEnvelope<EnumDictGroup[]>>('/api/user/dict/enum/list', body)
    const data = unwrap(res)
    return Array.isArray(data) ? data : []
  })
}

/** 常见问题列表：POST /api/user/faq/list */
export async function userFaqList(
  body: UserFaqListRequest = {}
): Promise<PaginatedListResult<UserFaqListItem>> {
  const pageNum = body.pageNum ?? 1
  const pageSize = body.pageSize ?? API_DEFAULT_PAGE_SIZE
  const res = await request.post('/api/user/faq/list', { ...body, pageNum, pageSize })
  return extractPaginatedResponse<UserFaqListItem>(res, pageNum, pageSize)
}

/** 常见问题详情：POST /api/user/faq/detail */
export async function userFaqDetail(body: UserFaqDetailRequest): Promise<UserFaqDetail> {
  const res = await request.post<ApiEnvelope<UserFaqDetail>>('/api/user/faq/detail', body)
  return unwrap(res)
}
