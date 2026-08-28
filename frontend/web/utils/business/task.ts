/** 通用任务域：任务列表/详情查询（含去重缓存）、统一续生、取消与批量取消。 */
import type {
ApiEnvelope,
PaginatedListResult,
UserTaskCancelBatchData,
UserTaskCancelBatchRequest,
UserTaskCancelRequest,
UserTaskDetailData,
UserTaskDetailRequest,
UserTaskListRequest,
UserTaskResumeData,
UserTaskResumeRequest,
UserTaskRow
} from '~/types/business-api';
import { request } from '~/utils/api';
import {
API_DEFAULT_PAGE_SIZE,
extractPaginatedResponse,
runListDedupe,
unwrap,
type ListBurstSlot
} from '~/utils/business/shared';

async function fetchAllPaginatedRows<T, B extends { pageNum?: number; pageSize?: number }>(
  fetchPage: (body: B) => Promise<PaginatedListResult<T>>,
  baseBody: Omit<B, 'pageNum' | 'pageSize'>,
  pageSize = API_DEFAULT_PAGE_SIZE
): Promise<T[]> {
  let pageNum = 1
  const all: T[] = []
  let total = Infinity
  while (all.length < total) {
    const page = await fetchPage({ ...baseBody, pageNum, pageSize } as B)
    total = page.total
    if (!page.rows.length) break
    all.push(...page.rows)
    if (!page.hasMore) break
    pageNum += 1
  }
  return all
}

/** 统一续生：POST /api/user/task/resume（按 taskId 识别任务类型） */
export async function userTaskResume(body: UserTaskResumeRequest): Promise<UserTaskResumeData> {
  const res = await request.post<ApiEnvelope<UserTaskResumeData>>('/api/user/task/resume', body)
  return unwrap(res)
}

/** 任务 list 恢复场景跨 bootstrap / scope-resume / 角标预拉，间隔常 >450ms，单独延长缓存 */
const USER_TASK_LIST_BURST_CACHE_MS = 3000

const userTaskListPageInflight = new Map<string, Promise<PaginatedListResult<UserTaskRow>>>()
const userTaskListPageBurst: ListBurstSlot<PaginatedListResult<UserTaskRow>> = {
  current: null
}

const userTaskListInflight = new Map<string, Promise<UserTaskRow[]>>()
const userTaskListBurst: ListBurstSlot<UserTaskRow[]> = { current: null }

function userTaskListDedupeKey(body: UserTaskListRequest): string {
  const pid =
    body.projectId != null && Number.isFinite(Number(body.projectId)) ? Number(body.projectId) : null
  return JSON.stringify({
    p: pid,
    t: body.taskType ?? null,
    s: body.status ?? null,
    pn: body.pageNum ?? null,
    ps: body.pageSize ?? null
  })
}

const userTaskListRecentInflight = new Map<string, Promise<UserTaskRow[]>>()
const userTaskListRecentBurst: ListBurstSlot<UserTaskRow[]> = { current: null }

/** 任务恢复/续跟只拉最近一页，避免 userTaskList 全量分页在 Network 里连打多次 list */
export const USER_TASK_LIST_RESTORE_PAGE_SIZE = 50

/** 任务终态刷新时清掉 list 短时缓存，避免角标仍读到旧的「进行中」行 */
export function invalidateUserTaskListCache(): void {
  userTaskListPageInflight.clear()
  userTaskListInflight.clear()
  userTaskListRecentInflight.clear()
  userTaskListPageBurst.current = null
  userTaskListBurst.current = null
  userTaskListRecentBurst.current = null
}

/** 通用任务列表（分页）：POST /api/user/task/list */
export async function userTaskListPage(
  body: UserTaskListRequest = {}
): Promise<PaginatedListResult<UserTaskRow>> {
  const pageNum = body.pageNum ?? 1
  const pageSize = body.pageSize ?? API_DEFAULT_PAGE_SIZE
  const key = userTaskListDedupeKey({ ...body, pageNum, pageSize })
  return runListDedupe(
    key,
    userTaskListPageInflight,
    userTaskListPageBurst,
    async () => {
      const res = await request.post('/api/user/task/list', { ...body, pageNum, pageSize })
      return extractPaginatedResponse<UserTaskRow>(res, pageNum, pageSize)
    },
    USER_TASK_LIST_BURST_CACHE_MS
  )
}

/** 拉取满足条件的全部任务（内部分页循环，供任务恢复等场景） */
export async function fetchAllUserTaskRows(
  body: Omit<UserTaskListRequest, 'pageNum' | 'pageSize'> = {}
): Promise<UserTaskRow[]> {
  return fetchAllPaginatedRows(userTaskListPage, body)
}

/** 只拉第一页最近任务（恢复/续跟：避免全量分页连续请求 /task/list） */
export async function userTaskListRecentPage(
  body: Omit<UserTaskListRequest, 'pageNum' | 'pageSize'> = {},
  pageSize = USER_TASK_LIST_RESTORE_PAGE_SIZE
): Promise<UserTaskRow[]> {
  const key = userTaskListDedupeKey({ ...body, pageNum: 1, pageSize })
  return runListDedupe(
    key,
    userTaskListRecentInflight,
    userTaskListRecentBurst,
    async () => {
      const { rows } = await userTaskListPage({ ...body, pageNum: 1, pageSize })
      return rows
    },
    USER_TASK_LIST_BURST_CACHE_MS
  )
}

/** 通用任务列表：POST /api/user/task/list（未传 pageNum/pageSize 时自动拉取全部页） */
export async function userTaskList(body: UserTaskListRequest = {}): Promise<UserTaskRow[]> {
  if (body.pageNum != null || body.pageSize != null) {
    const { rows } = await userTaskListPage(body)
    return rows
  }
  const key = userTaskListDedupeKey(body)
  return runListDedupe(key, userTaskListInflight, userTaskListBurst, () => fetchAllUserTaskRows(body))
}

/** 通用任务详情：POST /api/user/task/detail */
export async function userTaskDetail(body: UserTaskDetailRequest): Promise<UserTaskDetailData> {
  const res = await request.post<ApiEnvelope<UserTaskDetailData>>('/api/user/task/detail', body)
  return unwrap(res)
}

const userTaskDetailInflight = new Map<number, Promise<UserTaskDetailData>>()
const userTaskDetailBurst = new Map<number, { data: UserTaskDetailData; at: number }>()
const USER_TASK_DETAIL_BURST_MS = 3000

/** 刷新/恢复阶段合并同 taskId 的 detail 请求；SSE 终态后请 {@link invalidateUserTaskDetailCache} 再 force 拉取 */
export async function userTaskDetailCached(
  taskId: number,
  options?: { force?: boolean }
): Promise<UserTaskDetailData | null> {
  const id = Number(taskId)
  if (!Number.isFinite(id) || id <= 0) return null
  if (!options?.force) {
    const burst = userTaskDetailBurst.get(id)
    if (burst && Date.now() - burst.at < USER_TASK_DETAIL_BURST_MS) {
      return burst.data
    }
    const inflight = userTaskDetailInflight.get(id)
    if (inflight) return inflight
  } else {
    userTaskDetailBurst.delete(id)
  }
  const p = userTaskDetail({ taskId: id })
    .then((data) => {
      if (userTaskDetailInflight.get(id) === p) {
        userTaskDetailBurst.set(id, { data, at: Date.now() })
      }
      return data
    })
    .finally(() => {
      if (userTaskDetailInflight.get(id) === p) {
        userTaskDetailInflight.delete(id)
      }
    })
  userTaskDetailInflight.set(id, p)
  return p.catch(() => null)
}

export function invalidateUserTaskDetailCache(taskId?: number): void {
  if (taskId != null && Number.isFinite(Number(taskId)) && Number(taskId) > 0) {
    userTaskDetailBurst.delete(Number(taskId))
    return
  }
  userTaskDetailBurst.clear()
}

/** 停止生成/取消单个任务：POST /api/user/task/cancel */
export async function userTaskCancel(body: UserTaskCancelRequest): Promise<unknown> {
  const res = await request.post<ApiEnvelope<unknown>>('/api/user/task/cancel', body)
  return unwrap(res)
}

/** 批量取消 PENDING 独立任务（停止剩余）：POST /api/user/task/cancel-batch */
export async function userTaskCancelBatch(
  body: UserTaskCancelBatchRequest
): Promise<UserTaskCancelBatchData> {
  const res = await request.post<ApiEnvelope<UserTaskCancelBatchData>>(
    '/api/user/task/cancel-batch',
    body
  )
  const data = unwrap(res)
  return {
    cancelCount: Number(data?.cancelCount ?? 0)
  }
}
