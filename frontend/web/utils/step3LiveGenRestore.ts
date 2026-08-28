import type { RouteLikeLocation } from '~/types/routeLike'
import {
  fetchUserTaskDetailOnce,
  isTerminalTaskStatus
} from '~/composables/useTaskSseFollow'
import { resolveCurrentStep3GenVisualScopeBlobs } from '~/composables/useCreationStoreHydration'
import type { Step3GenVisualScopeMaps, useCreationStore } from '~/stores/creation'

type CreationStore = ReturnType<typeof useCreationStore>

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

/** 头部流程条「场景/角色/道具」步骤是否展示 loading（跨步骤页刷新后仍有效） */
export function isStep3FlowStepGenerating(
  store: CreationStore,
  route?: RouteLikeLocation
): boolean {
  if (store.isExtractingAssets) return true
  if (store.isGeneratingStep3Visual) return true
  for (const { blob } of resolveCurrentStep3GenVisualScopeBlobs(store, route)) {
    if (step3ScopeBlobHasLiveWork(blob)) return true
  }
  return false
}

function clearGeneratingFromScopeMap<T extends Record<string | number, string>>(
  m?: T
): T | null {
  if (!m || typeof m !== 'object') return null
  const next = { ...m } as T
  let changed = false
  for (const [k, s] of Object.entries(next)) {
    if (s !== 'generating') continue
    next[k as keyof T] = 'idle' as T[keyof T]
    changed = true
  }
  return changed ? next : null
}

/**
 * flat store 已无 generating 时，清除 scope 别名桶内残留的 generating，
 * 避免 isStep3FlowStepGenerating 读到旧桶数据导致流程条 loading 不消失。
 */
export function reconcileStep3ScopeBlobsWithFlatStore(
  store: CreationStore,
  route?: RouteLikeLocation
): void {
  const flatHasGenerating =
    Object.values(store.sceneGenerationStatus).some((s) => s === 'generating') ||
    Object.values(store.characterFormGenerationStatus).some((s) => s === 'generating') ||
    Object.values(store.propFormGenerationStatus).some((s) => s === 'generating')

  store.syncStep3GenVisualToCurrentScope()
  if (flatHasGenerating) return

  for (const { key, blob } of resolveCurrentStep3GenVisualScopeBlobs(store, route)) {
    const scene = clearGeneratingFromScopeMap(blob.scene)
    const character = clearGeneratingFromScopeMap(blob.character)
    const prop = clearGeneratingFromScopeMap(blob.prop)
    if (!scene && !character && !prop) continue
    store.writeStep3GenVisualScopeKey(key, {
      scene: scene ?? blob.scene ?? {},
      character: character ?? blob.character ?? {},
      prop: prop ?? blob.prop ?? {},
      modalSseTasks: blob.modalSseTasks
    })
  }
}

/** SSE / 任务终态后刷新第三步流程条 loading（modal 快照、follow 计数与 generating 态对齐） */
export async function settleStep3FlowLoadingState(
  store: CreationStore,
  route?: RouteLikeLocation
): Promise<void> {
  await purgeTerminalStep3ModalSseTasks(store, route)
  reconcileStep3ScopeBlobsWithFlatStore(store, route)
  store.refreshStep3VisualGeneratingFlag()
}

/**
 * 刷新后 Pinia 中 modalSseTasks 可能仍残留已终态任务，导致流程条 loading 无法结束。
 * 校验 task/detail 后清除终态快照。
 */
export async function purgeTerminalStep3ModalSseTasks(
  store: CreationStore,
  route?: RouteLikeLocation
): Promise<void> {
  const candidates = resolveCurrentStep3GenVisualScopeBlobs(store, route)
  for (const { key, blob } of candidates) {
    const tasks = blob.modalSseTasks || {}
    const scopes = Object.keys(tasks)
    if (!scopes.length) continue

    for (const scope of scopes) {
      const snap = tasks[scope]
      const tid = Number(snap?.taskId)
      if (!Number.isFinite(tid) || tid <= 0) {
        store.clearSceneModalSseTask(scope, key)
        continue
      }
      try {
        const detail = await fetchUserTaskDetailOnce(tid)
        if (!detail || isTerminalTaskStatus(detail.status)) {
          store.clearSceneModalSseTask(scope, key)
        }
      } catch {
        /* 网络失败时保留快照，避免误清进行中的 loading */
      }
    }
  }
  store.refreshStep3VisualGeneratingFlag()
}
