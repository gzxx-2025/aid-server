import { scoreStep4PlusLiveGenBlob } from '~/hooks/creationStoreHydrationScore'
import {
liveGenScopeKeyFromIds,
useCreationStore,
type CreationStoreState,
type Step4PlusLiveGenSnapshot,
type StoryboardDubbingGenTaskSnapshot,
type StoryboardImageGenTaskSnapshot,
type StoryboardVideoGenTaskSnapshot
} from '~/stores/creation'
import type { RouteLikeLocation } from '~/types/routeLike'
import { resolveFlowEpisodeIdFromRoute } from '~/utils/createFlowProjectContext'
import {
resolveLiveGenScopeIdsPreferStore
} from '~/utils/liveGenScopeIsolation'
import { resolveProjectIdFromRouteAndStore } from '~/utils/storyScriptSaveContext'
import { normalizeCountProgress } from '~/utils/taskSseProgressText'
export function pickBestStep4PlusLiveGenScopeKey(
  store: ReturnType<typeof useCreationStore>,
  route: RouteLikeLocation
): string | null {
  const candidates = resolveCurrentStep4LiveGenScopeBlobs(store, route)

  let bestKey: string | null = null
  let bestScore = 0
  for (const { key, blob } of candidates) {
    const score = scoreStep4PlusLiveGenBlob(blob)
    if (score > bestScore) {
      bestScore = score
      bestKey = key
    }
  }
  /** 没有任何进行中的桶时返回 null：不要用 idle 桶覆盖运行期扁平状态 */
  return bestKey && bestScore > 0 ? bestKey : null
}

/** 从路由 + store 解析 scope key，用于 persist afterRestore 未跑完时从 step4PlusLiveGenByScope 恢复生成态 */
export function resolveLiveGenScopeKeyFromRoute(
  store: ReturnType<typeof useCreationStore>,
  route: RouteLikeLocation
): string {
  const routeProjectIdRaw = Number(route.query.projectId ?? route.query.id ?? route.query.workId)
  const routeProjectId =
    Number.isFinite(routeProjectIdRaw) && routeProjectIdRaw > 0 ? routeProjectIdRaw : null
  const routeEpisodeId = resolveFlowEpisodeIdFromRoute(route, store.currentProjectType)
  const picked = resolveLiveGenScopeIdsPreferStore({
    storeProjectId: store.currentProjectId,
    routeProjectId,
    storeEpisodeId: store.currentEpisodeId,
    routeEpisodeId
  })
  const projectId = picked.projectId ?? resolveProjectIdFromRouteAndStore(store, route)
  return liveGenScopeKeyFromIds(projectId, picked.episodeId)
}

/** 与 stores/creation scopeKeyLegacyAliases 一致：仅同一作品的 episode null/0 别名 */
export function step4ScopeKeyLegacyAliases(key: string): string[] {
  const keys = [key]
  const m = /^(\d+):(.+)$/.exec(String(key || '').trim())
  if (!m) return keys
  const pid = m[1]
  const ep = m[2]
  if (ep === 'null') keys.push(`${pid}:0`)
  if (ep === '0') keys.push(`${pid}:null`)
  return keys
}

/**
 * 仅当前作品 scope 桶（含 episode null/0 别名），不含其他作品。
 * 用于扁平 store 灌入，避免跨作品串流。
 */
export function resolveCurrentStep4LiveGenScopeBlobs(
  store: ReturnType<typeof useCreationStore>,
  route?: RouteLikeLocation | { query?: Record<string, unknown>; path?: string }
): Array<{ key: string; blob: Step4PlusLiveGenSnapshot }> {
  // 只读：禁止在 computed / 流程条判断里 sync route→store，否则切作品窗口会把旧作品扳回。
  const primaryKey = route
    ? resolveLiveGenScopeKeyFromRoute(store, route as RouteLikeLocation)
    : store.step3GenVisualScopeKey()
  const map = store.step4PlusLiveGenByScope || {}
  const seen = new Set<string>()
  const result: Array<{ key: string; blob: Step4PlusLiveGenSnapshot }> = []
  for (const alias of step4ScopeKeyLegacyAliases(primaryKey)) {
    if (seen.has(alias)) continue
    seen.add(alias)
    const blob = map[alias]
    if (blob) result.push({ key: alias, blob })
  }
  return result
}

/**
 * 刷新后可能因 project/episode 尚未灌入导致 scopeKey 短暂不一致，列出候选 scope。
 *
 * 剧集隔离铁律：候选只含「当前 scope 的各来源写法 + episode null/0 历史别名」。
 * 禁止枚举全部桶——否则第 1 集的弹窗生图/生视频任务快照会被第 2/3 集页面恢复（跨集污染）。
 */
export function resolveStep4LiveGenScopeCandidates(
  store: ReturnType<typeof useCreationStore>,
  route?: RouteLikeLocation
): Array<{ key: string; blob: Step4PlusLiveGenSnapshot }> {
  const keys: string[] = []
  const add = (k: string) => {
    for (const alias of step4ScopeKeyLegacyAliases(String(k || '').trim())) {
      if (alias && !keys.includes(alias)) keys.push(alias)
    }
  }
  add(store.step3GenVisualScopeKey())
  if (route) add(resolveLiveGenScopeKeyFromRoute(store, route))
  if (store.currentProjectId != null) {
    add(liveGenScopeKeyFromIds(store.currentProjectId, store.currentEpisodeId))
  }
  return keys
    .map((key) => {
      const blob = store.step4PlusLiveGenByScope[key]
      return blob ? { key, blob } : null
    })
    .filter((x): x is { key: string; blob: Step4PlusLiveGenSnapshot } => x != null)
}

export function findStoryboardImageGenTaskInScopes(
  store: ReturnType<typeof useCreationStore>,
  storyboardId: number,
  route?: RouteLikeLocation
): StoryboardImageGenTaskSnapshot | null {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return null
  for (const { blob } of resolveStep4LiveGenScopeCandidates(store, route)) {
    const hit = blob.storyboardImageGenTasksByStoryboardId?.[String(sid)]
    const tid = Number(hit?.taskId)
    if (Number.isFinite(tid) && tid > 0) {
      return {
        taskId: tid,
        sceneIdx: Number(hit?.sceneIdx) || 0,
        ...(hit?.kind ? { kind: hit.kind } : {}),
        ...(hit?.imageIdx != null && Number.isFinite(Number(hit.imageIdx))
          ? { imageIdx: Number(hit.imageIdx) }
          : {}),
        ...(String(hit?.message ?? '').trim() ? { message: String(hit?.message).trim() } : {}),
        ...(String(hit?.stepTitle ?? '').trim() ? { stepTitle: String(hit?.stepTitle).trim() } : {})
      }
    }
  }
  return store.getStoryboardImageGenTask(storyboardId)
}

/** 清除各 scope 下持久化的单镜生图任务，避免 SSE 失败后残留任务导致 loading 无法清除 */
export function clearStoryboardImageGenTaskInAllScopes(
  store: ReturnType<typeof useCreationStore>,
  storyboardId: number,
  route?: RouteLikeLocation
) {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return
  const keys = new Set<string>()
  keys.add(store.step3GenVisualScopeKey())
  for (const { key } of resolveStep4LiveGenScopeCandidates(store, route)) {
    keys.add(key)
  }
  for (const key of keys) {
    store.clearStoryboardImageGenTask(sid, key)
  }
}

export function normalizeStoryboardVideoGenTaskKind(
  raw: unknown
): StoryboardVideoGenTaskSnapshot['taskKind'] {
  const k = String(raw ?? '')
    .trim()
    .toLowerCase()
  if (k === 'multi') return 'multi'
  if (k === 'edge') return 'edge'
  if (k === 'grid') return 'grid'
  return 'i2v'
}

export type StoryboardVideoGenTaskScopeEntry = StoryboardVideoGenTaskSnapshot & {
  storyboardId: number
}

/** 跨 scope 收集弹窗单条生视频持久化任务（按 storyboardId 去重，优先先命中的 scope） */
export function collectStoryboardVideoGenTaskEntriesInScopes(
  store: ReturnType<typeof useCreationStore>,
  route?: RouteLikeLocation
): StoryboardVideoGenTaskScopeEntry[] {
  const seen = new Set<number>()
  const entries: StoryboardVideoGenTaskScopeEntry[] = []
  for (const { blob } of resolveStep4LiveGenScopeCandidates(store, route)) {
    for (const [sidRaw, hit] of Object.entries(blob.storyboardVideoGenTasksByStoryboardId ?? {})) {
      const storyboardId = Number(sidRaw)
      const taskId = Number(hit?.taskId)
      if (!Number.isFinite(storyboardId) || storyboardId <= 0) continue
      if (!Number.isFinite(taskId) || taskId <= 0) continue
      if (seen.has(storyboardId)) continue
      seen.add(storyboardId)
      entries.push({
        storyboardId,
        taskId,
        sceneIdx: Number(hit?.sceneIdx) || 0,
        taskKind: normalizeStoryboardVideoGenTaskKind(hit?.taskKind),
        ...(String(hit?.message ?? '').trim() ? { message: String(hit?.message).trim() } : {}),
        ...(String(hit?.stepTitle ?? '').trim() ? { stepTitle: String(hit?.stepTitle).trim() } : {})
      })
    }
  }
  return entries
}

export function resolveStoryboardVideoGenEntriesByTaskId(
  store: ReturnType<typeof useCreationStore>,
  taskId: number,
  route?: RouteLikeLocation
): StoryboardVideoGenTaskScopeEntry[] {
  const tid = Number(taskId)
  if (!Number.isFinite(tid) || tid <= 0) return []
  return collectStoryboardVideoGenTaskEntriesInScopes(store, route).filter((e) => e.taskId === tid)
}

export function findStoryboardVideoGenTaskInScopes(
  store: ReturnType<typeof useCreationStore>,
  storyboardId: number,
  route?: RouteLikeLocation
): StoryboardVideoGenTaskSnapshot | null {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return null
  for (const { blob } of resolveStep4LiveGenScopeCandidates(store, route)) {
    const hit = blob.storyboardVideoGenTasksByStoryboardId?.[String(sid)]
    const tid = Number(hit?.taskId)
    if (Number.isFinite(tid) && tid > 0) {
      return {
        taskId: tid,
        sceneIdx: Number(hit?.sceneIdx) || 0,
        taskKind: normalizeStoryboardVideoGenTaskKind(hit?.taskKind),
        ...(String(hit?.message ?? '').trim() ? { message: String(hit?.message).trim() } : {}),
        ...(String(hit?.stepTitle ?? '').trim() ? { stepTitle: String(hit?.stepTitle).trim() } : {})
      }
    }
  }
  return store.getStoryboardVideoGenTask(storyboardId)
}

export function findStoryboardDubbingGenTaskInScopes(
  store: ReturnType<typeof useCreationStore>,
  storyboardId: number,
  route?: RouteLikeLocation
): StoryboardDubbingGenTaskSnapshot | null {
  const sid = Number(storyboardId)
  if (!Number.isFinite(sid) || sid <= 0) return null
  for (const { blob } of resolveStep4LiveGenScopeCandidates(store, route)) {
    const hit = blob.storyboardDubbingGenTasksByStoryboardId?.[String(sid)]
    if (!hit) continue
    const composeBatchId = String(hit.composeBatchId || '').trim()
    const audioRecordId = Number(hit.audioRecordId)
    const tid = Number(hit.taskId)
    const hasTask = Number.isFinite(tid) && tid > 0
    const hasCompose = !!composeBatchId && Number.isFinite(audioRecordId) && audioRecordId > 0
    if (!hasTask && !hasCompose) continue
    return {
      sceneIdx: Number(hit?.sceneIdx) || 0,
      ...(hasCompose ? { composeBatchId, audioRecordId } : {}),
      ...(hasTask ? { taskId: tid } : {}),
      ...(hit.lipSync != null ? { lipSync: Boolean(hit.lipSync) } : {}),
      ...(String(hit?.message ?? '').trim() ? { message: String(hit.message).trim() } : {}),
      ...(String(hit?.stepTitle ?? '').trim() ? { stepTitle: String(hit.stepTitle).trim() } : {})
    }
  }
  return store.getStoryboardDubbingGenTask(storyboardId)
}

function step4ScopeBlobHasStoryboardVideoPanelWork(blob: Step4PlusLiveGenSnapshot): boolean {
  const promptTid = Number(blob.storyboardVideoBatchActivePromptTaskId)
  const videoTid = Number(blob.storyboardVideoBatchActiveVideoTaskId)
  const modalStoryboardIds = new Set(Object.keys(blob.storyboardVideoGenTasksByStoryboardId || {}))
  const modalTaskIds = new Set(
    Object.values(blob.storyboardVideoGenTasksByStoryboardId || {})
      .map((task) => Number(task?.taskId))
      .filter((taskId) => Number.isFinite(taskId) && taskId > 0)
  )
  const hasBatchPanelGenerating = Object.entries(
    blob.storyboardPanelVideoGenStatusByStoryboardId || {}
  ).some(
    ([storyboardId, status]) => status === 'generating' && !modalStoryboardIds.has(storyboardId)
  )
  const ongoingBatch =
    (Number.isFinite(promptTid) && promptTid > 0) ||
    (Number.isFinite(videoTid) && videoTid > 0 && !modalTaskIds.has(videoTid)) ||
    hasBatchPanelGenerating
  if (ongoingBatch) return true
  if ((blob.storyboardVideoBatchTargetStoryboardIds?.length ?? 0) > 0) return true
  if (Object.keys(blob.storyboardPanelVideoGenErrorByStoryboardId || {}).length > 0) return true
  return Object.values(blob.storyboardPanelVideoGenStatusByStoryboardId || {}).some(
    (s) => s === 'failed'
  )
}

function shallowStringRecordEqual(a: Record<string, string>, b: Record<string, string>): boolean {
  const keysA = Object.keys(a)
  const keysB = Object.keys(b)
  if (keysA.length !== keysB.length) return false
  return keysA.every((k) => a[k] === b[k])
}

function countProgressEqual(
  a: { completed?: number; total?: number; eta?: { displayProgress?: number; estimatedFinishAtP50?: number; estimatedFinishAtP90?: number } },
  b: { completed?: number; total?: number; eta?: { displayProgress?: number; estimatedFinishAtP50?: number; estimatedFinishAtP90?: number } }
): boolean {
  return (
    Number(a?.completed ?? 0) === Number(b?.completed ?? 0) &&
    Number(a?.total ?? 0) === Number(b?.total ?? 0) &&
    Number(a?.eta?.displayProgress ?? 0) === Number(b?.eta?.displayProgress ?? 0) &&
    Number(a?.eta?.estimatedFinishAtP50 ?? 0) === Number(b?.eta?.estimatedFinishAtP50 ?? 0) &&
    Number(a?.eta?.estimatedFinishAtP90 ?? 0) === Number(b?.eta?.estimatedFinishAtP90 ?? 0)
  )
}

function numberArrayEqual(a: number[], b: number[]): boolean {
  if (a.length !== b.length) return false
  return a.every((v, i) => v === b[i])
}

/** 从当前作品 scope 桶合并分镜视频卡片 loading / 失败态到扁平 store（episode null/0 别名兜底） */
export function hydrateStoryboardVideoLiveGenFromScopes(
  store: ReturnType<typeof useCreationStore>,
  route?: RouteLikeLocation
): void {
  const candidates = resolveCurrentStep4LiveGenScopeBlobs(store, route).filter(({ blob }) =>
    step4ScopeBlobHasStoryboardVideoPanelWork(blob)
  )
  if (!candidates.length) {
    // 当前作品无视频生成态时，冲刷可能残留的他作品扁平 loading
    if (
      store.isGeneratingStoryboardVideo ||
      Number(store.storyboardVideoBatchActivePromptTaskId) > 0 ||
      Number(store.storyboardVideoBatchActiveVideoTaskId) > 0 ||
      Object.values(store.storyboardPanelVideoGenStatusByStoryboardId || {}).some(
        (s) => s === 'generating'
      )
    ) {
      const nextStatus: Record<string, import('~/stores/creation').SceneGenerationStatus> = {}
      const nextErrors: Record<string, string> = {}
      for (const [k, v] of Object.entries(
        store.storyboardPanelVideoGenStatusByStoryboardId || {}
      )) {
        if (v === 'failed') nextStatus[k] = v
      }
      for (const [k, v] of Object.entries(store.storyboardPanelVideoGenErrorByStoryboardId || {})) {
        const text = String(v ?? '').trim()
        if (text) nextErrors[k] = text
      }
      // Zustand 适配：原 Pinia 逐条直接赋值合并为一次 setState（最终状态等价）
      useCreationStore.setState({
        isGeneratingStoryboardVideo: false,
        storyboardVideoBatchActivePromptTaskId: null,
        storyboardVideoBatchActiveVideoTaskId: null,
        storyboardVideoBatchTargetStoryboardIds: [],
        storyboardVideoBatchProgress: normalizeCountProgress({
          completed: 0,
          total: 0,
          message: '',
          stepTitle: ''
        }),
        storyboardPanelVideoGenStatusByStoryboardId: nextStatus,
        storyboardPanelVideoGenErrorByStoryboardId: nextErrors
      })
    }
    return
  }

  // 仅从当前作品 scope 桶重建，禁止以扁平残留为底（避免跨作品串流）
  const genStatus: Record<string, import('~/stores/creation').SceneGenerationStatus> = {}
  const genErrors: Record<string, string> = {}
  let batchTargets: number[] = []
  let isGenerating = false
  let promptTaskId: number | null = null
  let videoTaskId: number | null = null
  let progress = normalizeCountProgress({
    completed: 0,
    total: 0,
    message: '',
    stepTitle: ''
  })

  for (const { blob } of candidates) {
    const modalStoryboardIds = new Set(
      Object.keys(blob.storyboardVideoGenTasksByStoryboardId || {})
    )
    const modalTaskIds = new Set(
      Object.values(blob.storyboardVideoGenTasksByStoryboardId || {})
        .map((task) => Number(task?.taskId))
        .filter((taskId) => Number.isFinite(taskId) && taskId > 0)
    )
    for (const [storyboardId, status] of Object.entries(
      blob.storyboardPanelVideoGenStatusByStoryboardId || {}
    )) {
      if (status === 'generating' && modalStoryboardIds.has(storyboardId)) continue
      genStatus[storyboardId] = status
    }
    Object.assign(genErrors, blob.storyboardPanelVideoGenErrorByStoryboardId || {})
    if (blob.storyboardVideoBatchTargetStoryboardIds?.length) {
      batchTargets = blob.storyboardVideoBatchTargetStoryboardIds
        .map((id) => Number(id))
        .filter((id) => Number.isFinite(id) && id > 0)
    }
    if (
      blob.isGeneratingStoryboardVideo &&
      ((blob.storyboardVideoBatchTargetStoryboardIds?.length ?? 0) > 0 ||
        (Number.isFinite(Number(blob.storyboardVideoBatchActivePromptTaskId)) &&
          Number(blob.storyboardVideoBatchActivePromptTaskId) > 0) ||
        (Number.isFinite(Number(blob.storyboardVideoBatchActiveVideoTaskId)) &&
          Number(blob.storyboardVideoBatchActiveVideoTaskId) > 0 &&
          !modalTaskIds.has(Number(blob.storyboardVideoBatchActiveVideoTaskId))) ||
        Object.entries(blob.storyboardPanelVideoGenStatusByStoryboardId || {}).some(
          ([storyboardId, status]) =>
            status === 'generating' && !modalStoryboardIds.has(storyboardId)
        ))
    ) {
      isGenerating = true
    }
    const pt = Number(blob.storyboardVideoBatchActivePromptTaskId)
    if (Number.isFinite(pt) && pt > 0) promptTaskId = pt
    const vt = Number(blob.storyboardVideoBatchActiveVideoTaskId)
    if (Number.isFinite(vt) && vt > 0 && !modalTaskIds.has(vt)) videoTaskId = vt
    const total = Number(blob.storyboardVideoBatchProgress?.total || 0)
    if (total > Number(progress.total || 0)) {
      progress = normalizeCountProgress({
        completed: Number(blob.storyboardVideoBatchProgress?.completed || 0),
        total,
        message: String(blob.storyboardVideoBatchProgress?.message ?? ''),
        stepTitle: String(blob.storyboardVideoBatchProgress?.stepTitle ?? ''),
        successCount: blob.storyboardVideoBatchProgress?.successCount,
        failCount: blob.storyboardVideoBatchProgress?.failCount,
        progressText: blob.storyboardVideoBatchProgress?.progressText,
        eta: blob.storyboardVideoBatchProgress?.eta
      })
    }
  }

  /** 内容未变时不替换引用，避免 StoryboardVideo 内 watcher ↔ hydrate 同 tick 死循环 */
  const patch: Partial<CreationStoreState> = {}
  if (store.isGeneratingStoryboardVideo !== isGenerating) {
    patch.isGeneratingStoryboardVideo = isGenerating
  }
  if (
    !shallowStringRecordEqual(
      store.storyboardPanelVideoGenStatusByStoryboardId as Record<string, string>,
      genStatus as Record<string, string>
    )
  ) {
    patch.storyboardPanelVideoGenStatusByStoryboardId = genStatus
  }
  if (!shallowStringRecordEqual(store.storyboardPanelVideoGenErrorByStoryboardId, genErrors)) {
    patch.storyboardPanelVideoGenErrorByStoryboardId = genErrors
  }
  if (!numberArrayEqual(store.storyboardVideoBatchTargetStoryboardIds, batchTargets)) {
    patch.storyboardVideoBatchTargetStoryboardIds = batchTargets
  }
  if (store.storyboardVideoBatchActivePromptTaskId !== promptTaskId) {
    patch.storyboardVideoBatchActivePromptTaskId = promptTaskId
  }
  if (store.storyboardVideoBatchActiveVideoTaskId !== videoTaskId) {
    patch.storyboardVideoBatchActiveVideoTaskId = videoTaskId
  }
  if (!countProgressEqual(store.storyboardVideoBatchProgress, progress)) {
    patch.storyboardVideoBatchProgress = progress
  }
  // Zustand 适配：仅把有变化的字段合并为一次 setState，未变化字段不进 patch（保持原「不替换引用」语义）
  if (Object.keys(patch).length > 0) {
    useCreationStore.setState(patch)
  }
}

/** 将按作品隔离的 step4+ 生成态（含 taskId / isGenerating）灌回扁平 store 字段 */
