import { useCreationStore } from '~/stores/creation'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import { userStoryboardList, userStoryboardSetFinalImage } from '~/utils/businessApi'
import {
  fetchFlowUserTaskList,
  filterUserTaskRowsForEpisode
} from '~/utils/userTaskListFlowOnce'
import { fetchUserTaskDetailOnce } from '~/composables/useTaskSseFollow'
import {
  captureCreationLiveGenScope,
  matchesCreationLiveGenScope,
  type CreationLiveGenScopeCtx
} from '~/composables/useCreationLiveGenScopeGuard'
import {
  clearStoryboardImageGenTaskInAllScopes,
  resolveCurrentStep4LiveGenScopeBlobs
} from '~/composables/useCreationStoreHydration'
import { mapStoryboardListRowToPanel } from '~/utils/storyboardPanelMap'
import {
  fetchProjectStoryboardRecords,
  groupStoryboardRecordsByStoryboardId,
  clearProjectStoryboardRecordCache,
  type ProjectEpisodeContext
} from '~/utils/storyboardRecordBatch'
import {
  resolveImageBatchLoadingTargetIds,
  buildImageBatchScopePreserveOnContextSwitch
} from '~/utils/storyboardImageBatchRestoreGate'
import {
  applyStoryboardImageImmediatePanelLoadingRestore,
  getActiveImageBatchTargetIds,
  clearImageBatchTargetIdsSession,
  writeImageBatchTargetIdsSession,
  panelHasCoverImage,
  parseImageBatchTaskId as parseTaskId,
  type StoryboardImageBatchState
} from '~/utils/storyboardImageBatchShared'
import type { StoryboardPanel } from '~/types'
import type { StoryboardRecordRow, UserTaskRow } from '~/types/business-api'
import { createStoryboardImageBatchModalRestore } from '~/utils/storyboardImageBatchModalRestore'

const RECENT_TASK_LIST_CACHE_MS = 4000

export function createStoryboardImageBatchCore(state: StoryboardImageBatchState) {
  /** 事件回调 / 异步流程一律取最新 store 状态（原 Pinia 实例为响应式，Zustand 需调用时取） */
  const getStore = () => useCreationStore.getState()

  async function fetchRecentProjectTasks(projectId: number): Promise<UserTaskRow[]> {
    const pid = Number(projectId)
    if (!Number.isFinite(pid) || pid <= 0) return []
    const now = Date.now()
    if (
      state.cachedRecentProjectTasks &&
      state.cachedRecentProjectTasks.projectId === pid &&
      now - state.cachedRecentProjectTasks.at < RECENT_TASK_LIST_CACHE_MS
    ) {
      return filterUserTaskRowsForEpisode(
        state.cachedRecentProjectTasks.rows,
        getStore().currentEpisodeId
      )
    }
    const rows = await fetchFlowUserTaskList(pid, { intent: 'read' })
    state.cachedRecentProjectTasks = { projectId: pid, at: now, rows }
    /** 剧集隔离：禁止把其它集的分镜图任务恢复到本集 */
    return filterUserTaskRowsForEpisode(rows, getStore().currentEpisodeId)
  }

  function invalidateRecentProjectTasksCache() {
    state.cachedRecentProjectTasks = null
  }

  function beginBatchSseFollow() {
    state.batchSseFollowDepth += 1
    state.batchSseFollowInFlight = true
  }

  function endBatchSseFollow() {
    state.batchSseFollowDepth = Math.max(0, state.batchSseFollowDepth - 1)
    state.batchSseFollowInFlight = state.batchSseFollowDepth > 0
    state.followIdleBarrier.notifyStateChange()
  }

  function closeStream() {
    const close = state.streamCloser
    state.streamCloser = null
    if (close) {
      try {
        close()
      } catch {
        /* ignore */
      }
    }
  }

  function syncActiveTaskIdToStore(taskId: number | null) {
    state.activeTaskId.value = taskId
    getStore().setStoryboardImageBatchActiveTaskId(taskId)
  }

  function syncActiveImageTaskIdToStore(taskId: number | null) {
    state.activeImageTaskId.value = taskId
    getStore().setStoryboardImageBatchActiveImageTaskId(taskId)
  }

  function setImageBatchTargetIds(storyboardIds: number[]) {
    getStore().setStoryboardImageBatchTargetStoryboardIds(storyboardIds)
    writeImageBatchTargetIdsSession(getStore(), storyboardIds)
  }

  function clearImageBatchTargetIds() {
    clearImageBatchTargetIdsSession(getStore())
    getStore().clearStoryboardImageBatchTargetStoryboardIds()
  }

  function stopImageBatchGeneration() {
    clearImageBatchTargetIdsSession(getStore())
    getStore().stopStoryboardImageBatchGeneration()
  }

  const { isModalOwnedStoryboardImageTaskId, reconcileOngoingImageGenerationTasks } =
    createStoryboardImageBatchModalRestore({
      getStore,
      syncActiveImageTaskIdToStore,
      setImageBatchTargetIds,
      clearImageBatchTargetIds
    })

  function pickLatestImageRecord(rows: StoryboardRecordRow[]): StoryboardRecordRow | null {
    const withUrl = rows.filter((r) => String(r?.fileUrl ?? '').trim())
    if (!withUrl.length) return null
    return (
      [...withUrl].sort((a, b) => {
        const ta = String(a.createTime ?? '')
        const tb = String(b.createTime ?? '')
        return tb.localeCompare(ta) || Number(b.id) - Number(a.id)
      })[0] ?? null
    )
  }

  function markRecordSelectedInMap(
    imageByStoryboardId: Map<number, StoryboardRecordRow[]>,
    storyboardId: number,
    recordId: number
  ) {
    const sid = Number(storyboardId)
    const rid = Number(recordId)
    const rows = imageByStoryboardId.get(sid)
    if (!rows?.length) return
    imageByStoryboardId.set(
      sid,
      rows.map((r) => {
        const id = Number(r.id)
        if (id === rid) return { ...r, isSelected: 1 }
        if (r.isSelected === 1) return { ...r, isSelected: 0 }
        return r
      })
    )
  }

  /** 批量设主图：list-by-storyboard 只请求一次，再单次 setFinalImage（items 批量） */
  async function setFinalImagesForStoryboards(
    ctx: ProjectEpisodeContext,
    storyboardIds: number[]
  ): Promise<{
    results: Map<number, boolean>
    imageByStoryboardId: Map<number, StoryboardRecordRow[]>
  }> {
    const results = new Map<number, boolean>()
    const emptyMap = new Map<number, StoryboardRecordRow[]>()
    if (!storyboardIds.length) return { results, imageByStoryboardId: emptyMap }

    let imageRows: StoryboardRecordRow[] = []
    try {
      imageRows = await fetchProjectStoryboardRecords(ctx, 'image')
    } catch {
      for (const sid of storyboardIds) results.set(sid, false)
      return { results, imageByStoryboardId: emptyMap }
    }

    const imageByStoryboardId = groupStoryboardRecordsByStoryboardId(imageRows)
    const sidToRecordId = new Map<number, number>()
    const items: Array<{ storyboardId: number; recordId: number }> = []

    for (const storyboardId of storyboardIds) {
      const sid = Number(storyboardId)
      const latest = pickLatestImageRecord(imageByStoryboardId.get(sid) ?? [])
      const rid = Number(latest?.id)
      if (!Number.isFinite(rid) || rid <= 0) {
        results.set(sid, false)
        continue
      }
      sidToRecordId.set(sid, rid)
      items.push({ storyboardId: sid, recordId: rid })
    }

    if (!items.length) return { results, imageByStoryboardId }

    const SET_FINAL_BATCH_MAX = 50
    const successRecordIds = new Set<number>()

    for (let i = 0; i < items.length; i += SET_FINAL_BATCH_MAX) {
      const chunk = items.slice(i, i + SET_FINAL_BATCH_MAX)
      try {
        const data = await userStoryboardSetFinalImage({
          projectId: ctx.projectId,
          episodeId: ctx.episodeId,
          items: chunk
        })
        for (const rid of (data?.successIds ?? []).map(Number)) {
          if (Number.isFinite(rid) && rid > 0) successRecordIds.add(rid)
        }
      } catch {
        /* 本批失败，对应分镜记为 false */
      }
    }

    for (const [sid, rid] of sidToRecordId) {
      if (successRecordIds.has(rid)) {
        markRecordSelectedInMap(imageByStoryboardId, sid, rid)
        results.set(sid, true)
      } else {
        results.set(sid, false)
      }
    }

    if (successRecordIds.size > 0) {
      clearProjectStoryboardRecordCache(ctx)
    }

    return { results, imageByStoryboardId }
  }

  async function refreshPanelsFromApi(): Promise<StoryboardPanel[]> {
    const ctx = await resolveStoryScriptSaveContext(getStore(), getRouteLikeSnapshot())
    if (!ctx) return []
    const list = await userStoryboardList({
      projectId: ctx.projectId,
      episodeId: ctx.episodeId
    })
    const sorted = [...list].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
    const panels = sorted.map((row, index) => mapStoryboardListRowToPanel(row, index))
    getStore().updateFormData({ storyboardScript: { panels } })
    return panels
  }

  /** 设主图后刷新分镜列表（仅 /storyboard/list） */
  async function finalizeBatchPanelsAfterImageGen(
    ctx: ProjectEpisodeContext,
    targets: number[]
  ): Promise<StoryboardPanel[]> {
    const { results } = await setFinalImagesForStoryboards(ctx, targets)
    for (const [sid, ok] of results) {
      getStore().setStoryboardPanelImageGenStatus(sid, ok ? 'success' : 'failed')
    }
    return refreshPanelsFromApi()
  }

  function applySseProgress(p: {
    progress?: number
    stepIndex?: number
    stepTotal?: number
    message?: string
    stepTitle?: string
  }) {
    getStore().applyStoryboardImageBatchSseProgress(p)
  }

  function resolveBatchImageTargets(
    panels: StoryboardPanel[],
    storyboardIds: number[],
    overwrite: boolean
  ): number[] {
    return storyboardIds.filter((sid) => {
      const panel = panels.find((p) => parseServerStoryboardId(p.id) === sid)
      if (!panel) return false
      if (!overwrite && panelHasCoverImage(panel)) return false
      return true
    })
  }

  function markPanelsGenerating(storyboardIds: number[]) {
    for (const sid of storyboardIds) {
      getStore().setStoryboardPanelImageGenStatus(sid, 'generating')
    }
  }

  /** 提示词阶段结束 → 出图 SSE 接续前，显式保持顶部/卡片 loading（对齐分镜视频批量） */
  function ensureImageBatchLoadingUi(targets: number[], panels?: StoryboardPanel[]) {
    if (!targets.length) return
    getStore().setStoryboardImageBatchGenerating(true)
    getStore().setStoryboardImageBatchError(null)
    if (!getStore().storyboardImageBatchTargetStoryboardIds.length) {
      getStore().setStoryboardImageBatchTargetStoryboardIds(targets)
    }
    writeImageBatchTargetIdsSession(getStore(), targets)
    markPanelsGenerating(targets)
    if (panels?.length) {
      applyImmediatePanelLoadingRestore(panels, { skipScopeHydrate: true })
    }
  }

  /** 仅当前 scope 才写扁平 loading；已切集则只保活原 scope 桶 */
  function ensureImageBatchLoadingUiForScope(
    scopeCtx: CreationLiveGenScopeCtx,
    targets: number[],
    panels: StoryboardPanel[] | undefined,
    taskIds?: { promptTaskId?: number | null; imageTaskId?: number | null }
  ) {
    if (matchesCreationLiveGenScope(scopeCtx)) {
      if (taskIds?.promptTaskId != null) syncActiveTaskIdToStore(taskIds.promptTaskId)
      if (taskIds?.imageTaskId != null) syncActiveImageTaskIdToStore(taskIds.imageTaskId)
      ensureImageBatchLoadingUi(targets, panels)
      return
    }
    getStore().mergeStep4PlusLiveGenForScopeKey(
      scopeCtx.scopeKey,
      buildImageBatchScopePreserveOnContextSwitch({
        promptTaskId: taskIds?.promptTaskId,
        imageTaskId: taskIds?.imageTaskId
      })
    )
  }

  function getActiveImageBatchTargetIdsLocal(): number[] {
    return getActiveImageBatchTargetIds(getStore(), getRouteLikeSnapshot())
  }

  function keepLoadingTargetsForStoryboards(storyboardIds: number[]): number[] {
    return resolveImageBatchLoadingTargetIds(getActiveImageBatchTargetIdsLocal(), storyboardIds)
  }

  function keepLoadingAfterFollowInterrupt(
    scopeCtx: CreationLiveGenScopeCtx,
    storyboardIds: number[],
    panels: StoryboardPanel[] | undefined,
    taskIds?: { promptTaskId?: number | null; imageTaskId?: number | null }
  ) {
    ensureImageBatchLoadingUiForScope(
      scopeCtx,
      keepLoadingTargetsForStoryboards(storyboardIds),
      panels,
      taskIds
    )
  }

  function isBatchOperationInterrupted(
    scopeCtx: CreationLiveGenScopeCtx,
    generation: number
  ): boolean {
    return generation !== state.resumeFollowGeneration || !matchesCreationLiveGenScope(scopeCtx)
  }

  function applyImmediatePanelLoadingRestore(
    panels: StoryboardPanel[],
    options?: { skipScopeHydrate?: boolean }
  ) {
    applyStoryboardImageImmediatePanelLoadingRestore(
      getStore(),
      getRouteLikeSnapshot(),
      panels,
      options
    )
  }

  /** @deprecated 请改用 applyImmediatePanelLoadingRestore */
  function ensureBatchPanelLoadingUi(panels: StoryboardPanel[]) {
    applyImmediatePanelLoadingRestore(panels)
  }

  function clearPanelGeneratingStatusIfIdle(storyboardId: number) {
    const sid = Number(storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) return
    for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(getStore(), getRouteLikeSnapshot())) {
      if (blob.storyboardImageGenTasksByStoryboardId?.[String(sid)]) return
    }
    getStore().clearStoryboardPanelImageGenStatus(sid)
  }

  function clearPanelGeneratingStatuses(storyboardIds: number[]) {
    for (const sid of storyboardIds) {
      clearStoryboardImageGenTaskInAllScopes(getStore(), sid, getRouteLikeSnapshot())
      clearPanelGeneratingStatusIfIdle(sid)
    }
  }

  function onStoryboardImageGenSseTerminal(event: Event) {
    const detail = (
      event as CustomEvent<{
        taskId?: number
        storyboardId?: number
        ok?: boolean
        errorMessage?: string
      }>
    ).detail
    const taskId = Number(detail?.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) return

    const matchesBatchTask =
      state.activeTaskId.value === taskId ||
      state.activeImageTaskId.value === taskId ||
      getStore().storyboardImageBatchActiveTaskId === taskId ||
      getStore().storyboardImageBatchActiveImageTaskId === taskId

    if (detail?.ok) {
      if (matchesBatchTask) {
        syncActiveTaskIdToStore(null)
        syncActiveImageTaskIdToStore(null)
      }
      return
    }

    const sids = new Set<number>()
    const hintedSid = Number(detail?.storyboardId)
    if (Number.isFinite(hintedSid) && hintedSid > 0) sids.add(hintedSid)

    for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(getStore(), getRouteLikeSnapshot())) {
      for (const [sidRaw, snap] of Object.entries(
        blob.storyboardImageGenTasksByStoryboardId || {}
      )) {
        if (Number((snap as { taskId?: number }).taskId) === taskId) {
          const sid = Number(sidRaw)
          if (Number.isFinite(sid) && sid > 0) sids.add(sid)
        }
      }
    }

    for (const sid of sids) {
      clearStoryboardImageGenTaskInAllScopes(getStore(), sid, getRouteLikeSnapshot())
      getStore().clearStoryboardPanelImageGenStatus(sid)
    }

    if (matchesBatchTask) {
      syncActiveTaskIdToStore(null)
      syncActiveImageTaskIdToStore(null)
      getStore().setStoryboardImageBatchGenerating(false)
      clearImageBatchTargetIds()
    }
  }

  async function seedProgressFromTaskDetail(taskId: number, fallbackTotal: number) {
    try {
      const detail = await fetchUserTaskDetailOnce(taskId)
      if (detail?.eta) {
        getStore().applyStoryboardImageBatchSseProgress({ eta: detail.eta })
      }
      const totalShots = Number((detail as { totalShots?: number }).totalShots)
      const total = Number.isFinite(totalShots) && totalShots > 0 ? totalShots : fallbackTotal
      if (total > 0) {
        const cur = getStore().storyboardImageBatchProgress
        if (!cur.total || cur.total < total) {
          getStore().setStoryboardImageBatchProgress(Math.min(cur.completed, total), total)
        }
      }
    } catch {
      /* ignore */
    }
  }

  return {
    getStore,
    fetchRecentProjectTasks,
    invalidateRecentProjectTasksCache,
    beginBatchSseFollow,
    endBatchSseFollow,
    closeStream,
    syncActiveTaskIdToStore,
    syncActiveImageTaskIdToStore,
    setImageBatchTargetIds,
    clearImageBatchTargetIds,
    stopImageBatchGeneration,
    setFinalImagesForStoryboards,
    refreshPanelsFromApi,
    finalizeBatchPanelsAfterImageGen,
    applySseProgress,
    resolveBatchImageTargets,
    markPanelsGenerating,
    ensureImageBatchLoadingUi,
    ensureImageBatchLoadingUiForScope,
    getActiveImageBatchTargetIdsLocal,
    keepLoadingTargetsForStoryboards,
    keepLoadingAfterFollowInterrupt,
    isBatchOperationInterrupted,
    isModalOwnedStoryboardImageTaskId,
    reconcileOngoingImageGenerationTasks,
    applyImmediatePanelLoadingRestore,
    ensureBatchPanelLoadingUi,
    clearPanelGeneratingStatuses,
    onStoryboardImageGenSseTerminal,
    seedProgressFromTaskDetail
  }
}

export type StoryboardImageBatchCore = ReturnType<typeof createStoryboardImageBatchCore>

/** 供 restore/follow 复用：captureCreationLiveGenScope 的返回类型别名 */
export type { CreationLiveGenScopeCtx }
export { captureCreationLiveGenScope }
