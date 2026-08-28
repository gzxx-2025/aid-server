/** 项目与剧集域：用户项目/剧集增删改查、公开作品、审核发布、项目级生成配置、创作步骤。 */
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
ProjectOrEpisodeIdRequest,
PublicProjectDetailRow,
PublicProjectVideoListRequest,
PublicProjectVideoRow,
UserEpisodeCreateRequest,
UserEpisodeDeleteRequest,
UserEpisodeDetailRequest,
UserEpisodeRow,
UserEpisodeUpdateRequest,
UserProjectCreateRequest,
UserProjectListRequest,
UserProjectPublishRequest,
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

const publicProjectVideoListInflight = new Map<
  string,
  Promise<{ total: number; rows: PublicProjectVideoRow[] }>
>()
const publicProjectVideoListBurst: ListBurstSlot<{ total: number; rows: PublicProjectVideoRow[] }> = {
  current: null
}

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

/** 公开项目视频：分页列表（/api/public/project/video/list） */
export async function publicProjectVideoList(
  body?: PublicProjectVideoListRequest
): Promise<{ total: number; rows: PublicProjectVideoRow[] }> {
  const reqBody = body ?? {}
  const key = stableRequestKey(reqBody)
  return runListDedupe(key, publicProjectVideoListInflight, publicProjectVideoListBurst, async () => {
    const res = (await request.post(
      '/api/public/project/video',
      reqBody
    )) as ApiListEnvelope<PublicProjectVideoRow> &
      ApiListEnvelopeData<PublicProjectVideoRow> & {
        rows?: PublicProjectVideoRow[]
        data?: PublicProjectVideoRow[]
        total?: number
      }
    const rows = Array.isArray(res.rows) ? res.rows : Array.isArray(res.data) ? res.data : []
    const total = typeof res.total === 'number' ? res.total : rows.length
    return { total, rows }
  })
}

const publicProjectDetailInflight = new Map<string, Promise<PublicProjectDetailRow>>()
const publicProjectDetailBurst: { current: { key: string; data: PublicProjectDetailRow; at: number } | null } =
  { current: null }

/** 公开项目：详情（/api/public/project/detail） */
export async function publicProjectDetail(id: number): Promise<PublicProjectDetailRow> {
  const key = String(id)
  const now = Date.now()
  const burst = publicProjectDetailBurst.current
  if (burst && burst.key === key && now - burst.at < 450) {
    return burst.data
  }
  const existing = publicProjectDetailInflight.get(key)
  if (existing) return existing
  const p = (async () => {
    const res = await request.post<ApiEnvelope<PublicProjectDetailRow>>(
      '/api/public/project/detail',
      { id }
    )
    const data = unwrap(res)
    publicProjectDetailBurst.current = { key, data, at: Date.now() }
    return data
  })().finally(() => publicProjectDetailInflight.delete(key))
  publicProjectDetailInflight.set(key, p)
  return p
}

/** 用户项目：删除（/api/user/project/delete） */
export async function userProjectDelete(id: number): Promise<void> {
  await request.post<ApiEnvelope>('/api/user/project/delete', { id })
}

/** 提交项目审核 POST /api/user/project/submit-audit */
export async function userProjectSubmitAudit(body: ProjectOrEpisodeIdRequest): Promise<void> {
  await request.post<ApiEnvelope>('/api/user/project/submit-audit', body)
}

/** 提交剧集审核 POST /api/user/episode/submit-audit（仅剧集） */
export async function userEpisodeSubmitAudit(body: ProjectOrEpisodeIdRequest): Promise<void> {
  await request.post<ApiEnvelope>('/api/user/episode/submit-audit', body)
}

/** 公开项目 POST /api/user/project/publish（须审核通过 status=4；须传描述与封面） */
export async function userProjectPublish(body: UserProjectPublishRequest): Promise<UserProjectRow> {
  const res = await request.post<ApiEnvelope<UserProjectRow>>('/api/user/project/publish', body)
  return unwrap(res)
}

/** 关闭项目公开 POST /api/user/project/unpublish */
export async function userProjectUnpublish(body: ProjectOrEpisodeIdRequest): Promise<UserProjectRow> {
  const res = await request.post<ApiEnvelope<UserProjectRow>>('/api/user/project/unpublish', body)
  return unwrap(res)
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
  const res = await request.post<ApiEnvelope<UserProjectRow>>('/api/user/project/update', body)
  return unwrap(res)
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
