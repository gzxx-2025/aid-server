/** AI 模型与智能体域：模型列表、按功能编码查询可用模型、智能体分组列表。 */
import { request } from '~/utils/api'
import { normalizeListByFuncGroups } from '~/utils/modelListByFuncBatch'
import {
  unwrap,
  stableRequestKey,
  runListDedupe,
  type ListBurstSlot
} from '~/utils/business/shared'
import type {
  ApiEnvelope,
  UserModelListByFuncRequest,
  AidAgentListRequest,
  AgentListGroupVO,
  UserModelListItem,
  UserModelListByFuncGroupVO,
  UserModelListRequest
} from '~/types/business-api'

const modelListByFuncInflight = new Map<string, Promise<UserModelListByFuncGroupVO[]>>()
const modelListByFuncBurst: ListBurstSlot<UserModelListByFuncGroupVO[]> = { current: null }

const aidAgentListInflight = new Map<string, Promise<AgentListGroupVO[]>>()
const aidAgentListBurst: ListBurstSlot<AgentListGroupVO[]> = { current: null }

/** AI 模型列表：POST /api/user/model/list */
export async function userModelList(body: UserModelListRequest = {}): Promise<UserModelListItem[]> {
  const res = await request.post<ApiEnvelope<UserModelListItem[]>>('/api/user/model/list', body)
  const data = unwrap(res)
  return Array.isArray(data) ? data : []
}

/** 按多个功能编码批量查询模型列表：POST /api/user/model/listByFunc */
export async function userModelListByFuncCodes(
  funcCodes: readonly string[],
  scope?: Pick<UserModelListByFuncRequest, 'projectId' | 'episodeId'>
): Promise<UserModelListByFuncGroupVO[]> {
  const codes = [...new Set(funcCodes.map((c) => String(c || '').trim()).filter(Boolean))]
  if (!codes.length) return []
  const body: UserModelListByFuncRequest = { funcCodes: codes }
  const projectId = Number(scope?.projectId)
  if (Number.isFinite(projectId) && projectId > 0) {
    body.projectId = projectId
    if (scope?.episodeId != null && Number.isFinite(Number(scope.episodeId))) {
      body.episodeId = Number(scope.episodeId)
    }
  }
  const key = stableRequestKey(body)
  return runListDedupe(key, modelListByFuncInflight, modelListByFuncBurst, async () => {
    const res = await request.post<ApiEnvelope<UserModelListByFuncGroupVO[] | UserModelListItem[]>>(
      '/api/user/model/listByFunc',
      body
    )
    return normalizeListByFuncGroups(unwrap(res), codes)
  })
}

/** 按功能编码查询可用模型列表：POST /api/user/model/listByFunc（v2.34.0） */
export async function userModelListByFunc(
  funcCode: string,
  scope?: Pick<UserModelListByFuncRequest, 'projectId' | 'episodeId'>
): Promise<UserModelListItem[]> {
  const normalized = String(funcCode || '').trim()
  if (!normalized) return []
  const groups = await userModelListByFuncCodes([normalized], scope)
  // 专业版可能把 main_storyboard_video 重映射为 multi_pro，按首个非空分组取模型
  const hit =
    groups.find((g) => String(g.funcCode || '').trim() === normalized) ||
    groups.find((g) => Array.isArray(g.models) && g.models.length > 0)
  return Array.isArray(hit?.models) ? hit!.models! : []
}

/** C 端：按业务分类分组查询启用智能体列表 POST /aid/agent/list */
export async function aidAgentList(body?: AidAgentListRequest): Promise<AgentListGroupVO[]> {
  const payload = body ?? {}
  const key = stableRequestKey(payload)
  return runListDedupe(key, aidAgentListInflight, aidAgentListBurst, async () => {
    const res = await request.post<ApiEnvelope<AgentListGroupVO[]>>('/aid/agent/list', payload)
    const data = unwrap(res)
    return Array.isArray(data) ? data : []
  })
}
