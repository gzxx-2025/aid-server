import type { TaskPartialFailedData } from '~/utils/taskPartialFailed'
import {
EMPTY_COUNT_PROGRESS,
normalizeCountProgress,
type CountProgressSnapshot
} from '~/utils/taskSseProgressText'
import {
liveGenScopeKeyFromIds,
scopeKeyLegacyAliases,
type SceneGenerationStatus,
type Step3GenVisualScopeMaps,
type Step4PlusLiveGenSnapshot
} from './types'

/** 将旧版 `projectId:null` scope 桶合并进 `projectId:0`，避免刷新后读不到持久化状态 */
export function migrateLegacyLiveGenScopeKeys(store: {
  step4PlusLiveGenByScope?: Record<string, Step4PlusLiveGenSnapshot>
}) {
  const map = store.step4PlusLiveGenByScope
  if (!map || typeof map !== 'object') return
  for (const key of Object.keys(map)) {
    const m = /^(\d+):null$/.exec(key)
    if (!m) continue
    const canonical = `${m[1]}:0`
    const legacy = map[key]
    if (!legacy) {
      delete map[key]
      continue
    }
    const existing = map[canonical]
    if (!existing) {
      map[canonical] = legacy
    } else {
      map[canonical] = {
        ...existing,
        ...legacy,
        storyboardPanelImageGenStatusByStoryboardId: {
          ...(existing.storyboardPanelImageGenStatusByStoryboardId || {}),
          ...(legacy.storyboardPanelImageGenStatusByStoryboardId || {})
        },
        storyboardImageBatchTargetStoryboardIds: legacy.storyboardImageBatchTargetStoryboardIds
          ?.length
          ? [...legacy.storyboardImageBatchTargetStoryboardIds]
          : existing.storyboardImageBatchTargetStoryboardIds || [],
        storyboardPanelVideoGenStatusByStoryboardId: {
          ...(existing.storyboardPanelVideoGenStatusByStoryboardId || {}),
          ...(legacy.storyboardPanelVideoGenStatusByStoryboardId || {})
        },
        storyboardPanelVideoGenErrorByStoryboardId: {
          ...(existing.storyboardPanelVideoGenErrorByStoryboardId || {}),
          ...(legacy.storyboardPanelVideoGenErrorByStoryboardId || {})
        },
        storyboardVideoBatchTargetStoryboardIds: legacy.storyboardVideoBatchTargetStoryboardIds
          ?.length
          ? [...legacy.storyboardVideoBatchTargetStoryboardIds]
          : existing.storyboardVideoBatchTargetStoryboardIds || [],
        isGeneratingStoryboardVideo:
          Boolean(existing.isGeneratingStoryboardVideo) ||
          Boolean(legacy.isGeneratingStoryboardVideo),
        storyboardVideoBatchActivePromptTaskId:
          legacy.storyboardVideoBatchActivePromptTaskId ??
          existing.storyboardVideoBatchActivePromptTaskId,
        storyboardVideoBatchActiveVideoTaskId:
          legacy.storyboardVideoBatchActiveVideoTaskId ??
          existing.storyboardVideoBatchActiveVideoTaskId,
        storyboardVideoBatchProgress:
          Number(legacy.storyboardVideoBatchProgress?.total) >
          Number(existing.storyboardVideoBatchProgress?.total)
            ? { ...legacy.storyboardVideoBatchProgress }
            : existing.storyboardVideoBatchProgress
      }
    }
    delete map[key]
  }
}

export function emptyStep4PlusLiveGenSnapshot(): Step4PlusLiveGenSnapshot {
  return {
    isGeneratingStoryboard: false,
    storyboardGenerationProgress: { ...EMPTY_COUNT_PROGRESS },
    storyboardGenerationError: null,
    isGeneratingStoryboardImageBatch: false,
    storyboardImageBatchProgress: { ...EMPTY_COUNT_PROGRESS },
    storyboardImageBatchError: null,
    storyboardImageBatchActiveTaskId: null,
    storyboardImageBatchActiveImageTaskId: null,
    storyboardPanelImageGenStatusByStoryboardId: {},
    storyboardImageBatchTargetStoryboardIds: [],
    isGeneratingStoryboardVideo: false,
    storyboardVideoBatchProgress: { ...EMPTY_COUNT_PROGRESS },
    storyboardVideoBatchError: null,
    storyboardVideoBatchActivePromptTaskId: null,
    storyboardVideoBatchActiveVideoTaskId: null,
    storyboardPanelVideoGenStatusByStoryboardId: {},
    storyboardPanelVideoGenErrorByStoryboardId: {},
    storyboardVideoBatchTargetStoryboardIds: [],
    dubbingBatchGeneratingIndices: [],
    storyboardScriptActiveTaskId: null,
    storyboardScriptPartialFailedData: null,
    storyboardImageGenTasksByStoryboardId: {},
    storyboardImagePromptGenTasksByStoryboardId: {},
    storyboardVideoGenTasksByStoryboardId: {},
    storyboardVideoPromptGenTasksByStoryboardId: {},
    storyboardDubbingGenTasksByStoryboardId: {},
    episodeExportTaskId: null,
    episodeExportEditorId: null
  }
}

/** 剧集隔离：仅合并当前 scope（含 null/0 别名）的失败态，禁止跨 episode 桶合并 */
export function mergeStoryboardVideoFailuresFromCurrentScope(
  store: { step4PlusLiveGenByScope?: Record<string, Step4PlusLiveGenSnapshot> },
  scopeKey: string,
  status: Record<string, SceneGenerationStatus>,
  errors: Record<string, string>
) {
  for (const alias of scopeKeyLegacyAliases(scopeKey)) {
    const scopeBlob = store.step4PlusLiveGenByScope?.[alias]
    if (!scopeBlob) continue
    for (const [k, v] of Object.entries(
      scopeBlob.storyboardPanelVideoGenStatusByStoryboardId || {}
    )) {
      if (v === 'failed') status[k] = v
    }
    for (const [k, v] of Object.entries(
      scopeBlob.storyboardPanelVideoGenErrorByStoryboardId || {}
    )) {
      const text = String(v ?? '').trim()
      if (text) errors[k] = text
    }
  }
}

export function scoreStep4PlusLiveGenBlobForMigrate(blob: Step4PlusLiveGenSnapshot): number {
  let score = 0
  if (blob.isGeneratingStoryboard) score += 100
  if (blob.isGeneratingStoryboardImageBatch) score += 100
  if (blob.isGeneratingStoryboardVideo) score += 100
  const imageBatchTid = Number(blob.storyboardImageBatchActiveTaskId)
  if (Number.isFinite(imageBatchTid) && imageBatchTid > 0) score += 40
  const imageGenBatchTid = Number(blob.storyboardImageBatchActiveImageTaskId)
  if (Number.isFinite(imageGenBatchTid) && imageGenBatchTid > 0) score += 40
  score += Object.values(blob.storyboardPanelImageGenStatusByStoryboardId || {}).filter(
    (s) => s === 'generating'
  ).length
  score += blob.storyboardImageBatchTargetStoryboardIds?.length ?? 0
  score += Object.values(blob.storyboardPanelVideoGenStatusByStoryboardId || {}).filter(
    (s) => s === 'generating'
  ).length
  score += blob.storyboardVideoBatchTargetStoryboardIds?.length ?? 0
  score += Object.keys(blob.storyboardImageGenTasksByStoryboardId || {}).length * 20
  score += Object.keys(blob.storyboardImagePromptGenTasksByStoryboardId || {}).length * 20
  score += Object.keys(blob.storyboardVideoGenTasksByStoryboardId || {}).length * 20
  score += Object.keys(blob.storyboardVideoPromptGenTasksByStoryboardId || {}).length * 20
  score += Object.keys(blob.storyboardDubbingGenTasksByStoryboardId || {}).length * 20
  return score
}

export function collectModalOwnedTaskIds(
  tasks?: Record<string, { taskId?: number } | undefined>
): Set<number> {
  return new Set(
    Object.values(tasks || {})
      .map((task) => Number(task?.taskId))
      .filter((taskId) => Number.isFinite(taskId) && taskId > 0)
  )
}

export function sanitizeLegacyModalPanelGenerating(
  statuses: Record<string, SceneGenerationStatus> | undefined,
  modalTasks: Record<string, { taskId?: number } | undefined> | undefined
): Record<string, SceneGenerationStatus> {
  const modalStoryboardIds = new Set(Object.keys(modalTasks || {}))
  return Object.fromEntries(
    Object.entries(statuses || {}).filter(
      ([storyboardId, status]) => status !== 'generating' || !modalStoryboardIds.has(storyboardId)
    )
  )
}

export function migrateStep4PlusLiveGenAfterRestore(store: {
  step4PlusLiveGenByScope?: Record<string, Step4PlusLiveGenSnapshot>
  dubbingBatchGeneratingIndices?: number[]
  step3GenVisualScopeKey?: () => string
  currentProjectId: number | null
  currentEpisodeId: number | null
  isGeneratingStoryboard: boolean
  storyboardGenerationProgress: CountProgressSnapshot
  storyboardGenerationError: string | null
  isGeneratingStoryboardImageBatch: boolean
  storyboardImageBatchProgress: CountProgressSnapshot
  storyboardImageBatchError: string | null
  storyboardImageBatchActiveTaskId: number | null
  storyboardImageBatchActiveImageTaskId: number | null
  storyboardPanelImageGenStatusByStoryboardId: Record<string, SceneGenerationStatus>
  storyboardImageBatchTargetStoryboardIds: number[]
  isGeneratingStoryboardVideo: boolean
  storyboardVideoBatchProgress: CountProgressSnapshot
  storyboardVideoBatchError: string | null
  storyboardVideoBatchActivePromptTaskId: number | null
  storyboardVideoBatchActiveVideoTaskId: number | null
  storyboardPanelVideoGenStatusByStoryboardId: Record<string, SceneGenerationStatus>
  storyboardPanelVideoGenErrorByStoryboardId?: Record<string, string>
  storyboardVideoBatchTargetStoryboardIds: number[]
  storyboardScriptActiveTaskId: number | null
  storyboardScriptPartialFailedData?: TaskPartialFailedData | null
}) {
  if (!store.step4PlusLiveGenByScope || typeof store.step4PlusLiveGenByScope !== 'object') {
     
    store.step4PlusLiveGenByScope = {}
  }
  if (!Array.isArray(store.dubbingBatchGeneratingIndices)) {
     
    store.dubbingBatchGeneratingIndices = []
  }
  const key =
    typeof store.step3GenVisualScopeKey === 'function'
      ? store.step3GenVisualScopeKey()
      : liveGenScopeKeyFromIds(store.currentProjectId, store.currentEpisodeId)
  let blob: Step4PlusLiveGenSnapshot | undefined
  let bestScore = 0
  const consider = (candidate?: Step4PlusLiveGenSnapshot) => {
    if (!candidate) return
    const score = scoreStep4PlusLiveGenBlobForMigrate(candidate)
    if (score > bestScore) {
      bestScore = score
      blob = candidate
    }
  }
  /** 剧集隔离：刷新回灌只允许当前 scope（含 null/0 别名），禁止跨 episode 桶挑「最活跃」的灌回 */
  for (const alias of scopeKeyLegacyAliases(key)) {
    consider(store.step4PlusLiveGenByScope[alias])
  }
  if (!blob || bestScore <= 0) {
    for (const alias of scopeKeyLegacyAliases(key)) {
      const hit = store.step4PlusLiveGenByScope[alias]
      if (hit) {
        blob = hit
        break
      }
    }
  }
  if (blob) {
     
    store.isGeneratingStoryboard = Boolean(blob.isGeneratingStoryboard)
     
    store.storyboardGenerationProgress = normalizeCountProgress(blob.storyboardGenerationProgress)
     
    store.storyboardGenerationError = blob.storyboardGenerationError ?? null
    const modalImageTaskIds = collectModalOwnedTaskIds(blob.storyboardImageGenTasksByStoryboardId)
    const imageGenBatchTid = Number(blob.storyboardImageBatchActiveImageTaskId)
    const restoredImageGenBatchTid =
      Number.isFinite(imageGenBatchTid) &&
      imageGenBatchTid > 0 &&
      !modalImageTaskIds.has(imageGenBatchTid)
        ? imageGenBatchTid
        : null
    const restoredImagePanelStatus = sanitizeLegacyModalPanelGenerating(
      blob.storyboardPanelImageGenStatusByStoryboardId,
      blob.storyboardImageGenTasksByStoryboardId
    )
     
    store.isGeneratingStoryboardImageBatch = Boolean(
      blob.isGeneratingStoryboardImageBatch &&
        (Number(blob.storyboardImageBatchActiveTaskId) > 0 ||
          restoredImageGenBatchTid != null ||
          (blob.storyboardImageBatchTargetStoryboardIds?.length ?? 0) > 0 ||
          Object.values(restoredImagePanelStatus).some((status) => status === 'generating'))
    )
     
    store.storyboardImageBatchProgress = normalizeCountProgress(blob.storyboardImageBatchProgress)
     
    store.storyboardImageBatchError = blob.storyboardImageBatchError ?? null
    const imageBatchTid = Number(blob.storyboardImageBatchActiveTaskId)
     
    store.storyboardImageBatchActiveTaskId =
      Number.isFinite(imageBatchTid) && imageBatchTid > 0 ? imageBatchTid : null
     
    store.storyboardImageBatchActiveImageTaskId = restoredImageGenBatchTid
     
    store.storyboardPanelImageGenStatusByStoryboardId =
      blob.storyboardPanelImageGenStatusByStoryboardId &&
      typeof blob.storyboardPanelImageGenStatusByStoryboardId === 'object'
        ? restoredImagePanelStatus
        : {}
     
    store.storyboardImageBatchTargetStoryboardIds = Array.isArray(
      blob.storyboardImageBatchTargetStoryboardIds
    )
      ? blob.storyboardImageBatchTargetStoryboardIds
          .map((id) => Number(id))
          .filter((id) => Number.isFinite(id) && id > 0)
      : []
    const modalVideoStoryboardIds = new Set(
      Object.keys(blob.storyboardVideoGenTasksByStoryboardId || {})
    )
    const modalVideoTaskIds = collectModalOwnedTaskIds(blob.storyboardVideoGenTasksByStoryboardId)
    const videoBatchTid = Number(blob.storyboardVideoBatchActiveVideoTaskId)
    const restoredVideoBatchTid =
      Number.isFinite(videoBatchTid) && videoBatchTid > 0 && !modalVideoTaskIds.has(videoBatchTid)
        ? videoBatchTid
        : null
    const restoredVideoPanelStatus = sanitizeLegacyModalPanelGenerating(
      blob.storyboardPanelVideoGenStatusByStoryboardId,
      blob.storyboardVideoGenTasksByStoryboardId
    )
     
    store.isGeneratingStoryboardVideo = Boolean(
      blob.isGeneratingStoryboardVideo &&
      ((blob.storyboardVideoBatchTargetStoryboardIds?.length ?? 0) > 0 ||
        Number(blob.storyboardVideoBatchActivePromptTaskId) > 0 ||
        restoredVideoBatchTid != null ||
        Object.entries(restoredVideoPanelStatus).some(
          ([storyboardId, status]) =>
            status === 'generating' && !modalVideoStoryboardIds.has(storyboardId)
        ))
    )
     
    store.storyboardVideoBatchProgress = normalizeCountProgress(blob.storyboardVideoBatchProgress)
     
    store.storyboardVideoBatchError = blob.storyboardVideoBatchError ?? null
    const videoPromptBatchTid = Number(blob.storyboardVideoBatchActivePromptTaskId)
     
    store.storyboardVideoBatchActivePromptTaskId =
      Number.isFinite(videoPromptBatchTid) && videoPromptBatchTid > 0 ? videoPromptBatchTid : null
     
    store.storyboardVideoBatchActiveVideoTaskId = restoredVideoBatchTid
     
    store.storyboardPanelVideoGenStatusByStoryboardId =
      blob.storyboardPanelVideoGenStatusByStoryboardId &&
      typeof blob.storyboardPanelVideoGenStatusByStoryboardId === 'object'
        ? restoredVideoPanelStatus
        : {}
     
    store.storyboardPanelVideoGenErrorByStoryboardId =
      blob.storyboardPanelVideoGenErrorByStoryboardId &&
      typeof blob.storyboardPanelVideoGenErrorByStoryboardId === 'object'
        ? { ...blob.storyboardPanelVideoGenErrorByStoryboardId }
        : {}
    mergeStoryboardVideoFailuresFromCurrentScope(
      store,
      key,
      store.storyboardPanelVideoGenStatusByStoryboardId,
      store.storyboardPanelVideoGenErrorByStoryboardId
    )
     
    store.storyboardVideoBatchTargetStoryboardIds = Array.isArray(
      blob.storyboardVideoBatchTargetStoryboardIds
    )
      ? blob.storyboardVideoBatchTargetStoryboardIds
          .map((id) => Number(id))
          .filter((id) => Number.isFinite(id) && id > 0)
      : []
     
    store.dubbingBatchGeneratingIndices = Array.isArray(blob.dubbingBatchGeneratingIndices)
      ? [...blob.dubbingBatchGeneratingIndices]
      : []
    const tid = Number(blob.storyboardScriptActiveTaskId)
     
    store.storyboardScriptActiveTaskId = Number.isFinite(tid) && tid > 0 ? tid : null
     
    store.storyboardScriptPartialFailedData = blob.storyboardScriptPartialFailedData ?? null
    const patch: Partial<Step4PlusLiveGenSnapshot> = {}
    if (
      !blob.storyboardPanelImageGenStatusByStoryboardId ||
      typeof blob.storyboardPanelImageGenStatusByStoryboardId !== 'object'
    ) {
      patch.storyboardPanelImageGenStatusByStoryboardId = {}
    }
    if (
      !blob.storyboardImageGenTasksByStoryboardId ||
      typeof blob.storyboardImageGenTasksByStoryboardId !== 'object'
    ) {
      patch.storyboardImageGenTasksByStoryboardId = {}
    }
    if (
      !blob.storyboardImagePromptGenTasksByStoryboardId ||
      typeof blob.storyboardImagePromptGenTasksByStoryboardId !== 'object'
    ) {
      patch.storyboardImagePromptGenTasksByStoryboardId = {}
    }
    if (
      !blob.storyboardVideoGenTasksByStoryboardId ||
      typeof blob.storyboardVideoGenTasksByStoryboardId !== 'object'
    ) {
      patch.storyboardVideoGenTasksByStoryboardId = {}
    }
    if (
      !blob.storyboardVideoPromptGenTasksByStoryboardId ||
      typeof blob.storyboardVideoPromptGenTasksByStoryboardId !== 'object'
    ) {
      patch.storyboardVideoPromptGenTasksByStoryboardId = {}
    }
    if (
      !blob.storyboardDubbingGenTasksByStoryboardId ||
      typeof blob.storyboardDubbingGenTasksByStoryboardId !== 'object'
    ) {
      patch.storyboardDubbingGenTasksByStoryboardId = {}
    }
    if (
      !blob.storyboardPanelVideoGenStatusByStoryboardId ||
      typeof blob.storyboardPanelVideoGenStatusByStoryboardId !== 'object'
    ) {
      patch.storyboardPanelVideoGenStatusByStoryboardId = {}
    }
    if (
      !blob.storyboardPanelVideoGenErrorByStoryboardId ||
      typeof blob.storyboardPanelVideoGenErrorByStoryboardId !== 'object'
    ) {
      patch.storyboardPanelVideoGenErrorByStoryboardId = {}
    }
    if (Object.keys(patch).length) {
       
      store.step4PlusLiveGenByScope[key] = { ...blob, ...patch }
    }
  } else {
    const empty = emptyStep4PlusLiveGenSnapshot()
     
    store.isGeneratingStoryboard = empty.isGeneratingStoryboard
     
    store.storyboardGenerationProgress = { ...empty.storyboardGenerationProgress }
     
    store.storyboardGenerationError = empty.storyboardGenerationError
     
    store.isGeneratingStoryboardImageBatch = empty.isGeneratingStoryboardImageBatch
     
    store.storyboardImageBatchProgress = { ...empty.storyboardImageBatchProgress }
     
    store.storyboardImageBatchError = empty.storyboardImageBatchError
     
    store.storyboardImageBatchActiveTaskId = empty.storyboardImageBatchActiveTaskId
     
    store.storyboardImageBatchActiveImageTaskId = empty.storyboardImageBatchActiveImageTaskId
     
    store.storyboardPanelImageGenStatusByStoryboardId = {
      ...empty.storyboardPanelImageGenStatusByStoryboardId
    }
     
    store.storyboardImageBatchTargetStoryboardIds = [
      ...empty.storyboardImageBatchTargetStoryboardIds
    ]
     
    store.isGeneratingStoryboardVideo = empty.isGeneratingStoryboardVideo
     
    store.storyboardVideoBatchProgress = { ...empty.storyboardVideoBatchProgress }
     
    store.storyboardVideoBatchError = empty.storyboardVideoBatchError
     
    store.storyboardVideoBatchActivePromptTaskId = empty.storyboardVideoBatchActivePromptTaskId
     
    store.storyboardVideoBatchActiveVideoTaskId = empty.storyboardVideoBatchActiveVideoTaskId
    const preservedVideoStatus: Record<string, SceneGenerationStatus> = {}
    const preservedVideoErrors: Record<string, string> = {}
    for (const [k, v] of Object.entries(store.storyboardPanelVideoGenStatusByStoryboardId || {})) {
      if (v === 'failed') preservedVideoStatus[k] = v
    }
    for (const [k, v] of Object.entries(store.storyboardPanelVideoGenErrorByStoryboardId || {})) {
      const text = String(v ?? '').trim()
      if (text) preservedVideoErrors[k] = text
    }
    for (const scopeBlob of Object.values(store.step4PlusLiveGenByScope || {})) {
      for (const [k, v] of Object.entries(
        scopeBlob.storyboardPanelVideoGenStatusByStoryboardId || {}
      )) {
        if (v === 'failed') preservedVideoStatus[k] = v
      }
      for (const [k, v] of Object.entries(
        scopeBlob.storyboardPanelVideoGenErrorByStoryboardId || {}
      )) {
        const text = String(v ?? '').trim()
        if (text) preservedVideoErrors[k] = text
      }
    }
     
    store.storyboardPanelVideoGenStatusByStoryboardId = preservedVideoStatus
     
    store.storyboardPanelVideoGenErrorByStoryboardId = preservedVideoErrors
     
    store.storyboardVideoBatchTargetStoryboardIds = [
      ...empty.storyboardVideoBatchTargetStoryboardIds
    ]
     
    store.dubbingBatchGeneratingIndices = [...empty.dubbingBatchGeneratingIndices]
     
    store.storyboardScriptActiveTaskId = empty.storyboardScriptActiveTaskId
     
    store.storyboardScriptPartialFailedData = empty.storyboardScriptPartialFailedData
  }
}

export function migrateStep3GenVisualMapsFromPersist(store: {
  currentProjectId: number | null
  currentEpisodeId: number | null
  step3GenVisualByScope?: Record<string, Step3GenVisualScopeMaps>
  sceneGenerationStatus: Record<number, SceneGenerationStatus>
  characterFormGenerationStatus: Record<string, SceneGenerationStatus>
  propFormGenerationStatus: Record<string, SceneGenerationStatus>
}) {
  const pid =
    store.currentProjectId != null && Number.isFinite(Number(store.currentProjectId))
      ? Number(store.currentProjectId)
      : 0
  const e =
    store.currentEpisodeId === null || store.currentEpisodeId === undefined
      ? 'null'
      : String(Number(store.currentEpisodeId))
  const sk = `${pid}:${e}`

  if (!store.step3GenVisualByScope || typeof store.step3GenVisualByScope !== 'object') {
    store.step3GenVisualByScope = {}
  }

  const legacyScene =
    store.sceneGenerationStatus && typeof store.sceneGenerationStatus === 'object'
      ? store.sceneGenerationStatus
      : {}
  const legacyChar =
    store.characterFormGenerationStatus && typeof store.characterFormGenerationStatus === 'object'
      ? store.characterFormGenerationStatus
      : {}
  const legacyProp =
    store.propFormGenerationStatus && typeof store.propFormGenerationStatus === 'object'
      ? store.propFormGenerationStatus
      : {}

  const hasLegacy =
    Object.keys(legacyScene).length > 0 ||
    Object.keys(legacyChar).length > 0 ||
    Object.keys(legacyProp).length > 0

  let scoped = store.step3GenVisualByScope[sk]
  const scopedEmpty =
    !scoped ||
    (!Object.keys(scoped.scene || {}).length &&
      !Object.keys(scoped.character || {}).length &&
      !Object.keys(scoped.prop || {}).length)

  if (hasLegacy && scopedEmpty) {
    store.step3GenVisualByScope[sk] = {
      scene: { ...legacyScene },
      character: { ...legacyChar },
      prop: { ...legacyProp },
      modalSseTasks: { ...(scoped?.modalSseTasks || {}) }
    }
    scoped = store.step3GenVisualByScope[sk]
  }

  if (scoped && !scoped.modalSseTasks) {
    store.step3GenVisualByScope[sk] = {
      scene: { ...(scoped.scene || {}) },
      character: { ...(scoped.character || {}) },
      prop: { ...(scoped.prop || {}) },
      modalSseTasks: {}
    }
    scoped = store.step3GenVisualByScope[sk]
  }

  store.sceneGenerationStatus = scoped?.scene ? { ...scoped.scene } : {}
  store.characterFormGenerationStatus = scoped?.character ? { ...scoped.character } : {}
  store.propFormGenerationStatus = scoped?.prop ? { ...scoped.prop } : {}
}
