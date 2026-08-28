'use client'

import type { UserTaskRow } from '~/types/business-api'
import { isFormCardImageTaskType } from '~/utils/formImageAutoUse'
import type { TaskSseProgressInput } from '~/utils/taskSseProgressText'
import { resolveStepIndexTotalFromSse } from '~/utils/taskSseProgressText'
import {
emptyStep3TabTaskProgress,
isFormImageUserTaskType,
isOngoingUserTaskStatus,
isStep3FormGenerateTaskType,
isStep3FormRelatedTaskType,
parseAssetIdsFromInputSnapshotRecord,
parseFormIdFromInputSnapshotRecord,
parseFormIdsFromBatchInputSnapshot
} from './scpTaskUtils'
import type { ScpCtx,Step3TabTaskProgress,TabKey } from './types'

export interface ScpTaskProgressApi {
  hasAnyStep3TabTaskProgress: () => boolean
  buildStep3TabProgressFromSse: (tab: TabKey, p: TaskSseProgressInput) => Partial<Step3TabTaskProgress>
  setStep3TabTaskProgress: (tab: TabKey, payload: Partial<Step3TabTaskProgress>) => void
  clearStep3TabTaskProgress: (tab?: TabKey) => void
  registerStep3TrackedTaskTab: (
    taskId: number,
    tab: TabKey,
    taskType?: string | null,
    assetIds?: number[]
  ) => void
  unregisterStep3TrackedTaskTab: (taskId: number) => void
  resolveTabKeyForFormId: (formId: number) => TabKey | null
  resolveTabKeyForAssetId: (assetId: number) => TabKey | null
  resolveTabFromPendingExtractAssetIds: (assetIds: number[]) => TabKey | null
  resolveTabKeyForStep3TaskSnapshot: (rec: {
    inputSnapshot?: string | null
    taskType?: string | null
  }) => TabKey | null
  /** 优先用 list 行自身 snapshot，避免切 Tab 时对每个任务打 detail */
  resolveTabKeyForStep3OngoingTask: (t: UserTaskRow) => TabKey | null
  registerStep3OngoingTaskMeta: (taskId: number, tab: TabKey, taskType?: string | null) => void
  hasActiveTrackedTasks: () => boolean
  clearActiveTaskStream: (taskId?: number) => void
  tabHasStep3GeneratingSlots: (tab: TabKey) => boolean
  tabHasLiveStep3TaskSse: (tab: TabKey) => boolean
  hasUnresolvedOngoingStep3Tasks: () => boolean
}

export function useScpTaskProgress(ctx: ScpCtx): ScpTaskProgressApi {
  function hasAnyStep3TabTaskProgress(): boolean {
    return (['scene', 'character', 'prop'] as TabKey[]).some((tab) => {
      const p = ctx.step3TabTaskProgress.get()[tab]
      return (
        String(p?.stepTitle || '').trim() ||
        String(p?.message || '').trim() ||
        (typeof p?.percent === 'number' && p.percent > 0)
      )
    })
  }

  function buildStep3TabProgressFromSse(
    tab: TabKey,
    p: TaskSseProgressInput
  ): Partial<Step3TabTaskProgress> {
    const msgText = String(p.message || '').trim()
    const titleText = String(p.stepTitle || '').trim()
    const prev = ctx.step3TabTaskProgress.get()[tab]
    const { stepIndex, stepTotal } = resolveStepIndexTotalFromSse(p)
    return {
      percent: typeof p.progress === 'number' ? p.progress : prev.percent,
      stepTitle: titleText || msgText || prev.stepTitle || '任务进行中',
      message: msgText || titleText,
      stepIndex,
      stepTotal
    }
  }

  function setStep3TabTaskProgress(tab: TabKey, payload: Partial<Step3TabTaskProgress>) {
    ctx.step3TabTaskProgress.set({
      ...ctx.step3TabTaskProgress.get(),
      [tab]: { ...ctx.step3TabTaskProgress.get()[tab], ...payload }
    })
  }

  function clearStep3TabTaskProgress(tab?: TabKey) {
    if (tab) {
      ctx.step3TabTaskProgress.set({
        ...ctx.step3TabTaskProgress.get(),
        [tab]: emptyStep3TabTaskProgress()
      })
      return
    }
    ctx.step3TabTaskProgress.set({
      scene: emptyStep3TabTaskProgress(),
      character: emptyStep3TabTaskProgress(),
      prop: emptyStep3TabTaskProgress()
    })
  }

  function registerStep3TrackedTaskTab(
    taskId: number,
    tab: TabKey,
    taskType?: string | null,
    assetIds?: number[]
  ) {
    ctx.step3TaskIdToTab.set({ ...ctx.step3TaskIdToTab.get(), [taskId]: tab })
    const prev = ctx.step3TaskMetaById.get()[taskId]
    const ids =
      assetIds?.filter((n) => Number.isFinite(n) && n > 0) ??
      prev?.assetIds
    ctx.step3TaskMetaById.set({
      ...ctx.step3TaskMetaById.get(),
      [taskId]: {
        tab,
        taskType: taskType ?? null,
        ...(ids?.length ? { assetIds: ids } : {})
      }
    })
  }

  function unregisterStep3TrackedTaskTab(taskId: number) {
    const tab = ctx.step3TaskIdToTab.get()[taskId]
    const next = { ...ctx.step3TaskIdToTab.get() }
    delete next[taskId]
    ctx.step3TaskIdToTab.set(next)
    const nextMeta = { ...ctx.step3TaskMetaById.get() }
    delete nextMeta[taskId]
    ctx.step3TaskMetaById.set(nextMeta)
    if (tab && !Object.values(next).includes(tab)) {
      clearStep3TabTaskProgress(tab)
    }
  }

  function resolveTabKeyForFormId(formId: number): TabKey | null {
    for (const ids of Object.values(ctx.sceneFormIdsByIndex.get())) {
      if ((ids ?? []).some((id) => Number(id) === formId)) return 'scene'
    }
    for (const ids of Object.values(ctx.characterFormIdsByIndex.get())) {
      if ((ids ?? []).some((id) => Number(id) === formId)) return 'character'
    }
    for (const ids of Object.values(ctx.propFormIdsByIndex.get())) {
      if ((ids ?? []).some((id) => Number(id) === formId)) return 'prop'
    }
    return null
  }

  function resolveTabKeyForAssetId(assetId: number): TabKey | null {
    if (ctx.findSceneIndexByAssetId(assetId) >= 0) return 'scene'
    if (ctx.findCharacterIndexByAssetId(assetId) >= 0) return 'character'
    if (ctx.findPropIndexByAssetId(assetId) >= 0) return 'prop'
    const pending = ctx.store().pendingExtractFormAssets.find(
      (x) => Number(x.assetId) === Number(assetId)
    )
    if (pending) return pending.assetType
    return null
  }

  function resolveTabFromPendingExtractAssetIds(assetIds: number[]): TabKey | null {
    for (const aid of assetIds) {
      const tab = resolveTabKeyForAssetId(aid)
      if (tab) return tab
    }
    return null
  }

  function resolveTabKeyForStep3TaskSnapshot(rec: {
    inputSnapshot?: string | null
    taskType?: string | null
  }): TabKey | null {
    if (isFormCardImageTaskType(rec.taskType)) return 'character'
    if (isStep3FormGenerateTaskType(rec.taskType)) {
      const assetIds = parseAssetIdsFromInputSnapshotRecord(rec)
      const tab = resolveTabFromPendingExtractAssetIds(assetIds)
      if (tab) return tab
    }
    if (isFormImageUserTaskType(rec.taskType)) {
      const multi = parseFormIdsFromBatchInputSnapshot(rec)
      const formIds =
        multi.length > 0
          ? multi
          : (() => {
              const single = parseFormIdFromInputSnapshotRecord(rec)
              return single != null ? [single] : []
            })()
      for (const fid of formIds) {
        const tab = resolveTabKeyForFormId(fid)
        if (tab) return tab
      }
    }
    const formId = parseFormIdFromInputSnapshotRecord(rec)
    if (formId != null) {
      const tab = resolveTabKeyForFormId(formId)
      if (tab) return tab
    }
    return null
  }

  /** 优先用 list 行自身 snapshot，避免切 Tab 时对每个任务打 detail */
  function resolveTabKeyForStep3OngoingTask(t: UserTaskRow): TabKey | null {
    return resolveTabKeyForStep3TaskSnapshot(t)
  }

  function registerStep3OngoingTaskMeta(
    taskId: number,
    tab: TabKey,
    taskType?: string | null
  ) {
    registerStep3TrackedTaskTab(taskId, tab, taskType)
    if (!ctx.activeTrackedTaskIds.get().includes(taskId)) {
      ctx.activeTrackedTaskIds.set([...ctx.activeTrackedTaskIds.get(), taskId])
    }
  }

  function hasActiveTrackedTasks(): boolean {
    return (
      ctx.activeTaskStreamClosers.size > 0 ||
      ctx.activeTrackedTaskIds.get().length > 0 ||
      Object.keys(ctx.step3TaskIdToTab.get()).length > 0
    )
  }

  function clearActiveTaskStream(taskId?: number) {
    if (taskId != null && Number.isFinite(taskId)) {
      const close = ctx.activeTaskStreamClosers.get(taskId)
      if (close) {
        try {
          close()
        } catch {
          /* ignore */
        }
      }
      ctx.activeTaskStreamClosers.delete(taskId)
      ctx.activeTrackedTaskIds.set(ctx.activeTrackedTaskIds.get().filter((id) => id !== taskId))
      return
    }
    for (const close of ctx.activeTaskStreamClosers.values()) {
      try {
        close()
      } catch {
        /* ignore */
      }
    }
    ctx.activeTaskStreamClosers.clear()
    ctx.activeTrackedTaskIds.set([])
  }

  function tabHasStep3GeneratingSlots(tab: TabKey): boolean {
    if (tab === 'scene') return Object.values(ctx.sceneGenerationStatus.get()).some((s) => s === 'generating')
    if (tab === 'character') {
      return Object.values(ctx.characterFormGenerationStatus.get()).some((s) => s === 'generating')
    }
    return Object.values(ctx.propFormGenerationStatus.get()).some((s) => s === 'generating')
  }

  function tabHasLiveStep3TaskSse(tab: TabKey): boolean {
    return Object.entries(ctx.step3TaskIdToTab.get()).some(
      ([id, t]) => t === tab && ctx.activeTaskStreamClosers.has(Number(id))
    )
  }

  function hasUnresolvedOngoingStep3Tasks(): boolean {
    return ctx.ongoingTasks.get().some((task) => {
      const taskId = Number(task.id)
      return (
        Number.isFinite(taskId) &&
        taskId > 0 &&
        isStep3FormRelatedTaskType(task.taskType) &&
        isOngoingUserTaskStatus(task.status) &&
        ctx.step3TaskIdToTab.get()[taskId] == null
      )
    })
  }

  return {
    hasAnyStep3TabTaskProgress,
    buildStep3TabProgressFromSse,
    setStep3TabTaskProgress,
    clearStep3TabTaskProgress,
    registerStep3TrackedTaskTab,
    unregisterStep3TrackedTaskTab,
    resolveTabKeyForFormId,
    resolveTabKeyForAssetId,
    resolveTabFromPendingExtractAssetIds,
    resolveTabKeyForStep3TaskSnapshot,
    resolveTabKeyForStep3OngoingTask,
    registerStep3OngoingTaskMeta,
    hasActiveTrackedTasks,
    clearActiveTaskStream,
    tabHasStep3GeneratingSlots,
    tabHasLiveStep3TaskSse,
    hasUnresolvedOngoingStep3Tasks
  }
}
