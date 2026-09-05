/** 项目与剧集域：用户项目/剧集增删改查、项目级生成配置、创作步骤。 */
import type {
ApiEnvelope,
ApiListEnvelope,
ApiListEnvelopeData,
CreationStepAdvanceRequest,
CreationStepRequest,
CreationStepState,
ProjectGenConfigQueryRequest,
ProjectGenConfigSaveRequest,
ProjectGenConfigSavedItem,
ProjectGenConfigVO,
UserEpisodeCreateRequest,
UserEpisodeDeleteRequest,
UserEpisodeDetailRequest,
UserEpisodeRow,
UserEpisodeUpdateRequest,
UserProjectCreateRequest,
UserProjectListRequest,
UserProjectRow,
UserProjectUpdateRequest
} from '~/types/business-api';
import { request } from '~/utils/api';
import {
runListDedupe,
stableRequestKey,
unwrap,
type ListBurstSlot
} from '~/utils/business/shared';

const userProjectListInflight = new Map<string, Promise<{ total: number; rows: UserProjectRow[] }>>()
const userProjectListBurst: ListBurstSlot<{ total: number; rows: UserProjectRow[] }> = { current: null }
const userProjectUpdateInflight = new Map<string, Promise<UserProjectRow>>()


/** 用户项目：列表查询（/api/user/project/list） */
export async function userProjectList(body?: UserProjectListRequest): Promise<{ total: number; rows: UserProjectRow[] }> {
  const reqBody = body ?? {}
  const key = stableRequestKey(reqBody)
  return runListDedupe(key, userProjectListInflight, userProjectListBurst, async () => {
    const res = (await request.post('/api/user/project/list', reqBody)) as ApiListEnvelope<UserProjectRow> &
      ApiListEnvelopeData<UserProjectRow> & {
        rows?: UserProjectRow[]
        data?: UserProjectRow[]
        total?: number
      }
    // 后端多为 { total, data: [...] }；旧版可能为根级 rows
    const rows = Array.isArray(res.rows) ? res.rows : Array.isArray(res.data) ? res.data : []
    const total = typeof res.total === 'number' ? res.total : rows.length
    return { total, rows }
  })
}

/** 用户项目：获取详情（/api/user/project/detail） */
export async function userProjectDetail(id: number): Promise<UserProjectRow> {
  const res = await request.post<ApiEnvelope<UserProjectRow>>('/api/user/project/detail', { id })
  return unwrap(res)
}

/** 用户项目：删除（/api/user/project/delete） */
export async function userProjectDelete(id: number): Promise<void> {
  await request.post<ApiEnvelope>('/api/user/project/delete', { id })
}

/** 用户项目：创建（/api/user/project/create） */
export async function userProjectCreate(body: UserProjectCreateRequest): Promise<{ data: UserProjectRow; msg: string }> {
  const res = await request.post<ApiEnvelope<UserProjectRow>>('/api/user/project/create', body)
  return {
    data: unwrap(res),
    msg: res.msg || '操作成功'
  }
}

/** 用户项目：修改（/api/user/project/update） */
export async function userProjectUpdate(body: UserProjectUpdateRequest): Promise<UserProjectRow> {
  const key = stableRequestKey(body)
  const current = userProjectUpdateInflight.get(key)
  if (current) return current
  const task = (async () => {
    const res = await request.post<ApiEnvelope<UserProjectRow>>('/api/user/project/update', body)
    const data = unwrap(res)
    const staleListRequests = Array.from(userProjectListInflight.values())
    if (staleListRequests.length > 0) await Promise.allSettled(staleListRequests)
    userProjectListBurst.current = null
    return data
  })().finally(() => userProjectUpdateInflight.delete(key))
  userProjectUpdateInflight.set(key, task)
  return task
}

/** 用户剧集：列表（/api/user/episode/list） */
export async function userEpisodeList(body: { projectId: number }): Promise<UserEpisodeRow[]> {
  const res = (await request.post('/api/user/episode/list', body)) as {
    data?: UserEpisodeRow[]
    rows?: UserEpisodeRow[]
  }
  const list = res.data ?? res.rows ?? []
  return Array.isArray(list) ? list : []
}

/** 用户剧集：创建（/api/user/episode/create） */
export async function userEpisodeCreate(body: UserEpisodeCreateRequest): Promise<UserEpisodeRow> {
  const res = await request.post<ApiEnvelope<UserEpisodeRow>>('/api/user/episode/create', body)
  return unwrap(res)
}

/** 用户剧集：详情（/api/user/episode/detail） */
export async function userEpisodeDetail(body: UserEpisodeDetailRequest): Promise<UserEpisodeRow> {
  const res = await request.post<ApiEnvelope<UserEpisodeRow>>('/api/user/episode/detail', body)
  return unwrap(res)
}

/** 用户剧集：修改（/api/user/episode/update） */
export async function userEpisodeUpdate(body: UserEpisodeUpdateRequest): Promise<UserEpisodeRow> {
  const res = await request.post<ApiEnvelope<UserEpisodeRow>>('/api/user/episode/update', body)
  return unwrap(res)
}

/** 用户剧集：删除（/api/user/episode/delete） */
export async function userEpisodeDelete(body: UserEpisodeDeleteRequest): Promise<void> {
  await request.post<ApiEnvelope>('/api/user/episode/delete', body)
}

/** 创作步骤：查询状态（/api/user/step/status） */
export async function creationStepStatus(body: CreationStepRequest): Promise<CreationStepState> {
  const res = await request.post<ApiEnvelope<CreationStepState>>('/api/user/step/status', body)
  return unwrap(res)
}

/** 创作步骤：手动推进（/api/user/step/advance） */
export async function creationStepAdvance(body: CreationStepAdvanceRequest): Promise<CreationStepState> {
  const res = await request.post<ApiEnvelope<CreationStepState>>('/api/user/step/advance', body)
  return unwrap(res)
}

/** 查询项目级生成配置（懒加载 + aid_config 兜底）：POST /api/user/project/gen-config/get */
export async function userProjectGenConfigGet(
  body: ProjectGenConfigQueryRequest
): Promise<ProjectGenConfigVO[]> {
  const res = await request.post<ApiEnvelope<ProjectGenConfigVO[]>>(
    '/api/user/project/gen-config/get',
    body
  )
  const data = unwrap(res)
  return Array.isArray(data) ? data : []
}

/** 保存项目级生成配置（部分更新）：POST /api/user/project/gen-config/save */
export async function userProjectGenConfigSave(
  body: ProjectGenConfigSaveRequest
): Promise<ProjectGenConfigSavedItem[]> {
  const res = await request.post<ApiEnvelope<ProjectGenConfigSavedItem[]>>(
    '/api/user/project/gen-config/save',
    body
  )
  const data = unwrap(res)
  return Array.isArray(data) ? data : []
}
