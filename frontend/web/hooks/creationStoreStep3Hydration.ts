import {
liveGenScopeKeyFromIds,
useCreationStore,
type SceneModalSseTaskSnapshot,
type Step3GenVisualScopeMaps
} from '~/stores/creation'
import type { RouteLikeLocation } from '~/types/routeLike'
import { resolveFlowEpisodeIdFromRoute } from '~/utils/createFlowProjectContext'
import {
flatStep4LiveGenLooksActive,
isLiveGenContextDiverged,
shouldClearOrphanStep4FlatLiveGen
} from '~/utils/liveGenScopeIsolation'
import { hydrateStoryboardVideoLiveGenFromScopes,pickBestStep4PlusLiveGenScopeKey,resolveLiveGenScopeKeyFromRoute,step4ScopeKeyLegacyAliases } from '~/hooks/creationStoreStep4Hydration'

/** Restore the persisted step-4+ live-generation snapshot for the active route scope. */
export function applyCreationStoreScopeLiveGenFromRoute(
  store: ReturnType<typeof useCreationStore>,
  route: RouteLikeLocation
): void {
  syncProjectContextFromRouteIfNeeded(store, route)
  const live = useCreationStore.getState()
  const primaryKey = resolveLiveGenScopeKeyFromRoute(live, route)
  const bestKey = pickBestStep4PlusLiveGenScopeKey(live, route)
  if (bestKey) {
    live.restoreStep4PlusLiveGenForScopeKey(bestKey)
  } else if (
    shouldClearOrphanStep4FlatLiveGen({
      scopeHasLiveWork: false,
      flatLooksActive: flatStep4LiveGenLooksActive(live)
    })
  ) {
    // 目标作品 idle，但扁平仍残留他作品 generating → 用空快照冲刷
    live.restoreStep4PlusLiveGenForScopeKey(primaryKey)
  }
  hydrateStoryboardVideoLiveGenFromScopes(useCreationStore.getState(), route)
}

/**
 * 刷新后可能因 project/episode 尚未灌入导致 scopeKey 短暂不一致，列出候选 step3 scope。
 *
 * 剧集隔离铁律：候选只含「当前 scope 的各来源写法 + episode null/0 历史别名」。
 * modalSseTasks 的 editorScopeKey 是索引型键（scene-0 / character-0-1 / prop-0-1），不全局唯一，
 * 枚举全部桶会把第 1 集同名键的任务恢复到第 2/3 集（跨集/跨作品污染）。
 */
export function resolveStep3GenVisualScopeCandidates(
  store: ReturnType<typeof useCreationStore>,
  route?: RouteLikeLocation
): Array<{ key: string; blob: Step3GenVisualScopeMaps }> {
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
      const blob = store.step3GenVisualByScope[key]
      return blob ? { key, blob } : null
    })
    .filter((x): x is { key: string; blob: Step3GenVisualScopeMaps } => x != null)
}

/**
 * 仅当前作品 step3 scope 桶（含 episode null/0 别名），不含其他作品。
 */
export function resolveCurrentStep3GenVisualScopeBlobs(
  store: ReturnType<typeof useCreationStore>,
  route?: RouteLikeLocation
): Array<{ key: string; blob: Step3GenVisualScopeMaps }> {
  // 只读：与 resolveCurrentStep4LiveGenScopeBlobs 相同，禁止在读路径 sync route。
  const primaryKey = route
    ? resolveLiveGenScopeKeyFromRoute(store, route)
    : store.step3GenVisualScopeKey()
  const map = store.step3GenVisualByScope || {}
  const seen = new Set<string>()
  const result: Array<{ key: string; blob: Step3GenVisualScopeMaps }> = []
  for (const alias of step4ScopeKeyLegacyAliases(primaryKey)) {
    if (seen.has(alias)) continue
    seen.add(alias)
    const blob = map[alias]
    if (blob) result.push({ key: alias, blob })
  }
  return result
}

function step3ScopeBlobHasLiveWork(blob: Step3GenVisualScopeMaps): boolean {
  const hasGenerating = (m?: Record<string | number, string>) =>
    Object.values(m || {}).some((s) => s === 'generating')
  return (
    hasGenerating(blob.scene) ||
    hasGenerating(blob.character) ||
    hasGenerating(blob.prop) ||
    Object.keys(blob.modalSseTasks || {}).length > 0
  )
}

export function findSceneModalSseTaskInScopes(
  store: ReturnType<typeof useCreationStore>,
  editorScopeKey: string,
  route?: RouteLikeLocation
): SceneModalSseTaskSnapshot | null {
  const scope = String(editorScopeKey || '').trim()
  if (!scope) return null
  for (const { blob } of resolveStep3GenVisualScopeCandidates(store, route)) {
    const hit = blob.modalSseTasks?.[scope]
    const tid = Number(hit?.taskId)
    if (hit && Number.isFinite(tid) && tid > 0) {
      return hit
    }
  }
  return store.findSceneModalSseTaskAcrossScopes(scope)
}

/** 将 step3 场景/角色/道具生成态（含 modalSseTasks）灌回扁平 store 字段 */
export function applyStep3GenVisualFromRoute(
  store: ReturnType<typeof useCreationStore>,
  route: RouteLikeLocation
): void {
  syncProjectContextFromRouteIfNeeded(store, route)
  // Zustand 快照不可变：上一步可能已 setState，scope 计算必须基于最新 state
  const live = useCreationStore.getState()
  const fallbackKey = resolveLiveGenScopeKeyFromRoute(live, route)
  const candidates = resolveCurrentStep3GenVisualScopeBlobs(live, route)
  const preferredKey =
    candidates.find(({ blob }) => step3ScopeBlobHasLiveWork(blob))?.key ??
    candidates[0]?.key ??
    fallbackKey
  live.applyStep3GenVisualFromScopeKey(preferredKey)
  live.refreshStep3VisualGeneratingFlag()
}

function syncProjectContextFromRouteIfNeeded(
  store: ReturnType<typeof useCreationStore>,
  route: RouteLikeLocation
): void {
  const routeProjectIdRaw = Number(route.query.projectId ?? route.query.id ?? route.query.workId)
  const routeProjectId =
    Number.isFinite(routeProjectIdRaw) && routeProjectIdRaw > 0 ? routeProjectIdRaw : null
  const pt = store.currentProjectType
  const routeEpisodeId = resolveFlowEpisodeIdFromRoute(route, pt)
  /**
   * 切作品/切集窗口：store 已更新、route 可能仍旧。
   * 禁止用旧 route 扳回（会把上一作品/上一集 loading 灌回扁平字段）。
   * 刷新首屏：CreateFlowShell.syncProjectContextFromRoute 在 apply 前以 route 为准对齐，不受此门闸影响。
   */
  if (
    isLiveGenContextDiverged({
      storeProjectId: store.currentProjectId,
      routeProjectId,
      storeEpisodeId: store.currentEpisodeId,
      routeEpisodeId
    })
  ) {
    return
  }
  const payload: { projectId?: number; episodeId?: number | null } = {}
  if (routeProjectId) payload.projectId = routeProjectId
  if (pt === 'movie') {
    payload.episodeId = 0
  } else if (routeEpisodeId != null && routeEpisodeId > 0) {
    payload.episodeId = routeEpisodeId
  } else if (
    routeProjectId != null &&
    routeProjectId !== store.currentProjectId &&
    pt === 'series'
  ) {
    payload.episodeId = null
  }
  if (payload.projectId !== undefined || payload.episodeId !== undefined) {
    store.setCurrentProjectContext(payload)
  }
}

/**
 * 等待 persist 完成 afterRestore。
 * 刷新时组件 effect 可能早于 afterRestore，导致 isHydrated 一直为 false 而跳过 SSE 恢复。
 */
export async function waitForCreationStoreHydrated(
  store: ReturnType<typeof useCreationStore>,
  route?: RouteLikeLocation
): Promise<void> {
  if (store.isHydrated) return

  await new Promise<void>((resolve) => {
    let settled = false
    let unsubscribe: (() => void) | null = null
    const finish = () => {
      if (settled) return
      settled = true
      unsubscribe?.()
      resolve()
    }

    // 原 watch(flush:'sync', immediate:true) 的 Zustand 替代：先订阅变化，再立即检查当前值
    unsubscribe = useCreationStore.subscribe((state) => {
      if (state.isHydrated) finish()
    })
    if (useCreationStore.getState().isHydrated) {
      finish()
      return
    }

    // afterRestore 未触发时的兜底：persist 的 scope 桶已可读，直接恢复生成态并标记 hydrated
    setTimeout(() => {
      if (settled) return
      const live = useCreationStore.getState()
      if (live.isHydrated) {
        finish()
        return
      }
      if (route) {
        applyCreationStoreScopeLiveGenFromRoute(live, route)
        applyStep3GenVisualFromRoute(live, route)
      }
      useCreationStore.getState().finalizeClientHydration()
      finish()
    }, 0)
  })
}
