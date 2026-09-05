/** Unified Skill Runtime catalog and invocation APIs. */
import type { ApiEnvelope } from '~/types/business-api'
import type {
  UserSkillDefinition,
  UserSkillInputResponseRequest,
  UserSkillRuntimeEventsPage,
  UserSkillRuntimeHistoryPage,
  UserSkillRuntimeHistoryRequest,
  UserSkillRuntimeInvokeRequest,
  UserSkillRuntimeRunHandle
} from '~/types/user-skill'
import { request } from '~/utils/api'
import { runListDedupe, stableRequestKey, type ListBurstSlot } from '~/utils/business/shared'

const catalogInflight = new Map<string, Promise<UserSkillDefinition[]>>()
const catalogBurst: ListBurstSlot<UserSkillDefinition[]> = { current: null }
type MutationSlot<T> = { fingerprint: string; promise: Promise<T> }
const runtimeInvokeInflight = new Map<string, MutationSlot<UserSkillRuntimeRunHandle>>()
const runtimeInputInflight = new Map<string, MutationSlot<UserSkillRuntimeRunHandle>>()
const runtimeDetailInflight = new Map<number, Promise<UserSkillRuntimeRunHandle>>()
const runtimeHistoryInflight = new Map<string, Promise<UserSkillRuntimeHistoryPage>>()
const runtimeHistoryBurst: ListBurstSlot<UserSkillRuntimeHistoryPage> = { current: null }
const runtimeEventsInflight = new Map<string, Promise<UserSkillRuntimeEventsPage>>()
const runtimeCancelInflight = new Map<number, Promise<void>>()

function runMutationOnce<T>(
  map: Map<string, MutationSlot<T>>,
  key: string,
  fingerprint: string,
  task: () => Promise<T>
): Promise<T> {
  const existing = map.get(key)
  if (existing) {
    return existing.fingerprint === fingerprint
      ? existing.promise
      : Promise.reject(new Error('同一请求标识不能用于不同参数'))
  }
  const holder: { promise?: Promise<T> } = {}
  const pending = task().finally(() => {
    if (map.get(key)?.promise === holder.promise) map.delete(key)
  })
  holder.promise = pending
  map.set(key, { fingerprint, promise: pending })
  return pending
}

/** Authenticated catalog containing only active Runtime entrypoints. */
export function userSkillRuntimeCatalog(): Promise<UserSkillDefinition[]> {
  const body = {}
  const key = stableRequestKey(body)
  return runListDedupe(key, catalogInflight, catalogBurst, async () => {
    const response = await request.post<ApiEnvelope<UserSkillDefinition[]>>(
      '/api/user/skill/execution/catalog',
      body
    )
    return Array.isArray(response.data) ? response.data : []
  })
}

/** Invoke a Runtime entrypoint; an in-flight idempotency key is never bypassed. */
export function userSkillRuntimeInvoke(
  body: UserSkillRuntimeInvokeRequest
): Promise<UserSkillRuntimeRunHandle> {
  const requestIdentity = { ...body, force: undefined }
  const scopeKey = stableRequestKey({
    skillCode: body.skillCode,
    idempotencyKey: body.idempotencyKey
  })
  return runMutationOnce(runtimeInvokeInflight, scopeKey, stableRequestKey(requestIdentity), async () => {
    const response = await request.post<ApiEnvelope<UserSkillRuntimeRunHandle>>(
      '/api/user/skill/execution/invoke',
      body
    )
    if (!response.data?.runId) throw new Error(response.msg || '创建 Skill 运行失败')
    return response.data
  })
}

/** Submit all answers for one dynamic input request and resume that same Run. */
export function userSkillRuntimeRespondInput(
  body: UserSkillInputResponseRequest
): Promise<UserSkillRuntimeRunHandle> {
  const key = `${body.runId}:${body.requestId}:${body.responseKey}`
  return runMutationOnce(runtimeInputInflight, key, stableRequestKey(body), async () => {
    const response = await request.post<ApiEnvelope<UserSkillRuntimeRunHandle>>(
      '/api/user/skill/execution/input/respond',
      body
    )
    if (!response.data?.runId) throw new Error(response.msg || '提交创作信息失败')
    return response.data
  })
}

/** Read a Runtime Run snapshot; concurrent reads for the same Run are merged. */
export function userSkillRuntimeRunDetail(runId: number): Promise<UserSkillRuntimeRunHandle> {
  const existing = runtimeDetailInflight.get(runId)
  if (existing) return existing
  const pending = request.post<ApiEnvelope<UserSkillRuntimeRunHandle>>(
    '/api/user/skill/execution/run/detail',
    { runId }
  ).then((response) => {
    if (!response.data?.runId) throw new Error(response.msg || 'Skill 运行不存在')
    return response.data
  }).finally(() => {
    if (runtimeDetailInflight.get(runId) === pending) runtimeDetailInflight.delete(runId)
  })
  runtimeDetailInflight.set(runId, pending)
  return pending
}

/** Restore one project/episode conversation page; identical concurrent reads share one request. */
export function userSkillRuntimeRunHistory(
  body: UserSkillRuntimeHistoryRequest
): Promise<UserSkillRuntimeHistoryPage> {
  const key = stableRequestKey(body)
  return runListDedupe(key, runtimeHistoryInflight, runtimeHistoryBurst, async () => {
    const response = await request.post<ApiEnvelope<UserSkillRuntimeHistoryPage>>(
      '/api/user/skill/execution/run/history',
      body
    )
    return {
      data: Array.isArray(response.data?.data) ? response.data.data : [],
      hasMore: response.data?.hasMore === true
    }
  })
}

/** Incrementally recover persisted milestones and the current Run snapshot. */
export function userSkillRuntimeRunEvents(
  runId: number,
  afterSeq = 0
): Promise<UserSkillRuntimeEventsPage> {
  const key = stableRequestKey({ runId, afterSeq })
  const existing = runtimeEventsInflight.get(key)
  if (existing) return existing
  const pending = request.post<ApiEnvelope<UserSkillRuntimeEventsPage>>(
    '/api/user/skill/execution/run/events',
    { runId, afterSeq }
  ).then((response) => {
    if (!response.data?.run?.runId) throw new Error(response.msg || '读取 Skill 事件失败')
    return {
      data: Array.isArray(response.data.data) ? response.data.data : [],
      run: response.data.run
    }
  }).finally(() => {
    if (runtimeEventsInflight.get(key) === pending) runtimeEventsInflight.delete(key)
  })
  runtimeEventsInflight.set(key, pending)
  return pending
}

/** Cancel a Runtime Run; concurrent cancel actions are merged. */
export function userSkillRuntimeRunCancel(runId: number): Promise<void> {
  const existing = runtimeCancelInflight.get(runId)
  if (existing) return existing
  const pending = request.post<ApiEnvelope<unknown>>(
    '/api/user/skill/execution/run/cancel',
    { runId }
  ).then(() => undefined).finally(() => {
    if (runtimeCancelInflight.get(runId) === pending) runtimeCancelInflight.delete(runId)
  })
  runtimeCancelInflight.set(runId, pending)
  return pending
}
