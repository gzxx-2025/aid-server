import { refreshOngoingUserTaskRowsFromDetail, isUserTaskLocallyTerminal } from '~/hooks/useTaskOngoing'
import { userTaskDetailCached } from '~/utils/businessApi'
import { routePathToCreationStep } from '~/utils/createFlowRoutes'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { isFormCardImageTaskType, parseImageIdsFromTaskInputSnapshot } from '~/utils/formImageAutoUse'
import { fetchFlowUserTaskList } from '~/utils/userTaskListFlowOnce'
import type { UserTaskRow } from '~/types/business-api'
import {
  isFormImageUserTaskType,
  isImageUpscaleUserTaskType,
  isOngoingUserTaskStatus,
  isStep3FormGenerateTaskType,
  isStep3FormRelatedTaskType,
  normUserTaskType,
  parseAssetIdsFromInputSnapshotRecord,
  parseFormIdFromInputSnapshotRecord,
  parseFormIdsFromBatchInputSnapshot
} from './scpTaskUtils'
import type { ScpCtx } from './types'

export function createScpTaskListOps(ctx: ScpCtx) {
  const getRecentStep3TaskRow = (taskId: number) =>
    ctx.recentStep3TaskRows.get().find((row) => Number(row.id) === Number(taskId))

  const shouldFetchOngoingUserTaskList = () => {
    if (ctx.store().isExtractingAssets || ctx.store().isGeneratingStep3Visual) return true
    if (ctx.hasAnyPersistedModalSseTasks() || ctx.hasActiveTrackedTasks()) return true
    if (Object.values(ctx.pendingFormGenBusy.get()).some(Boolean)) return true
    if (ctx.isSettingCardBatchBusy()) return true
    if (Object.values(ctx.sceneGenerationStatus.get()).some((status) => status === 'generating')) {
      return true
    }
    if (
      Object.values(ctx.characterFormGenerationStatus.get()).some(
        (status) => status === 'generating'
      )
    ) {
      return true
    }
    return Object.values(ctx.propFormGenerationStatus.get()).some(
      (status) => status === 'generating'
    )
  }

  const reloadOngoingTasks = async (options?: {
    force?: boolean
    mutate?: boolean
  }): Promise<boolean> => {
    if (!options?.force && !shouldFetchOngoingUserTaskList()) {
      ctx.ongoingTasks.set([])
      ctx.ongoingTasksLoading.set(false)
      return true
    }
    if (ctx.reloadOngoingTasksPromise) return ctx.reloadOngoingTasksPromise
    ctx.reloadOngoingTasksPromise = (async (): Promise<boolean> => {
      const saveContext = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      if (!saveContext) {
        ctx.ongoingTasks.set([])
        return false
      }
      ctx.ongoingTasksLoading.set(true)
      try {
        const allRows = await fetchFlowUserTaskList(saveContext.projectId, {
          intent: options?.mutate === true ? 'mutate' : 'read'
        })
        const seen = new Set<number>()
        const typeFiltered: UserTaskRow[] = []
        const currentEpisodeId = Number(saveContext.episodeId)
        for (const row of allRows) {
          if (!row) continue
          const rowEpisodeId = Number(row.episodeId)
          if (
            Number.isFinite(rowEpisodeId) &&
            rowEpisodeId > 0 &&
            Number.isFinite(currentEpisodeId) &&
            currentEpisodeId > 0 &&
            rowEpisodeId !== currentEpisodeId
          ) {
            continue
          }
          const type = normUserTaskType(row.taskType)
          if (
            type !== 'asset_extract' &&
            !isStep3FormGenerateTaskType(type) &&
            !isFormImageUserTaskType(type) &&
            !isFormCardImageTaskType(type) &&
            type !== 'image_upscale' &&
            type !== 'form_edit_chat' &&
            type !== 'form_multi_view'
          ) {
            continue
          }
          const id = Number(row.id)
          if (!Number.isFinite(id) || seen.has(id)) continue
          seen.add(id)
          typeFiltered.push(row)
        }

        const statusRows = await refreshOngoingUserTaskRowsFromDetail(typeFiltered, {
          reconcileWithDetail: false
        })
        const ongoingIds = new Set(
          statusRows
            .filter(
              (row) =>
                isStep3FormRelatedTaskType(row.taskType) && isOngoingUserTaskStatus(row.status)
            )
            .map((row) => Number(row.id))
            .filter((id) => Number.isFinite(id) && id > 0)
        )
        for (const id of ctx.ongoingStep3TaskDetailRows.keys()) {
          if (!ongoingIds.has(id)) ctx.ongoingStep3TaskDetailRows.delete(id)
        }
        const restoredRows = await Promise.all(
          statusRows.map(async (row): Promise<UserTaskRow> => {
            const id = Number(row.id)
            if (!ongoingIds.has(id)) return row
            const cached = ctx.ongoingStep3TaskDetailRows.get(id)
            if (cached) return { ...row, ...cached, id: row.id }
            const detail = await userTaskDetailCached(id)
            if (!detail) return row
            const restored = { ...row, ...detail, id: row.id }
            ctx.ongoingStep3TaskDetailRows.set(id, restored)
            return restored
          })
        )
        ctx.recentStep3TaskRows.set(restoredRows)
        const merged = restoredRows
          .filter((row) => {
            const id = Number(row.id)
            return (
              Number.isFinite(id) &&
              !isUserTaskLocallyTerminal(id) &&
              isOngoingUserTaskStatus(row.status)
            )
          })
          .sort((left, right) => {
            const idDifference = Number(right.id || 0) - Number(left.id || 0)
            return idDifference || String(right.createTime || '').localeCompare(left.createTime || '')
          })
        ctx.ongoingTasks.set(merged)
        return true
      } catch {
        return false
      } finally {
        ctx.ongoingTasksLoading.set(false)
      }
    })().finally(() => {
      ctx.reloadOngoingTasksPromise = null
    })
    return ctx.reloadOngoingTasksPromise
  }

  const reconcileOngoingStep3TasksToUi = async (): Promise<{
    coverFormIds: Set<number>
    coverAssetIds: Set<number>
    coverImageIds: Set<number>
  }> => {
    const coverFormIds = new Set<number>()
    const coverAssetIds = new Set<number>()
    const coverImageIds = new Set<number>()
    if (routePathToCreationStep(ctx.route().path) !== 'scene-character') {
      return { coverFormIds, coverAssetIds, coverImageIds }
    }
    const tasks = ctx.ongoingTasks.get().filter(
      (task) =>
        task && isStep3FormRelatedTaskType(task.taskType) && isOngoingUserTaskStatus(task.status)
    )
    for (const task of tasks) {
      const snapshot = task as { inputSnapshot?: string | null; taskType?: string | null }
      if (isStep3FormGenerateTaskType(snapshot.taskType ?? task.taskType)) {
        for (const assetId of parseAssetIdsFromInputSnapshotRecord(snapshot)) {
          coverAssetIds.add(assetId)
          ctx.applyAssetIdToPendingFormTextGeneratingBusy(assetId)
        }
        continue
      }
      if (isFormCardImageTaskType(snapshot.taskType ?? task.taskType)) {
        const imageIds = parseImageIdsFromTaskInputSnapshot(snapshot.inputSnapshot)
        imageIds.forEach((imageId) => coverImageIds.add(imageId))
        if (imageIds.length) ctx.applySettingCardGeneratingFromImageIds(imageIds)
        continue
      }
      const type = normUserTaskType(snapshot.taskType ?? task.taskType)
      if (type === 'form_edit_chat' || type === 'form_multi_view' || isImageUpscaleUserTaskType(type)) {
        const formId = parseFormIdFromInputSnapshotRecord(snapshot)
        if (formId != null) {
          coverFormIds.add(formId)
          ctx.applyFormIdToStep3GeneratingSlots(formId)
        }
        continue
      }
      if (!isFormImageUserTaskType(snapshot.taskType ?? task.taskType)) continue
      const formIds = parseFormIdsFromBatchInputSnapshot(snapshot)
      if (formIds.length) {
        for (const formId of formIds) {
          coverFormIds.add(formId)
          ctx.applyFormIdToStep3GeneratingSlots(formId)
        }
        continue
      }
      const formId = parseFormIdFromInputSnapshotRecord(snapshot)
      if (formId != null) {
        coverFormIds.add(formId)
        ctx.applyFormIdToStep3GeneratingSlots(formId)
      }
    }
    return { coverFormIds, coverAssetIds, coverImageIds }
  }

  const reconcileOngoingFormImageTasksToStep3Ui = async () => {
    const { coverFormIds } = await reconcileOngoingStep3TasksToUi()
    return coverFormIds
  }

  return {
    getRecentStep3TaskRow,
    shouldFetchOngoingUserTaskList,
    reloadOngoingTasks,
    reconcileOngoingStep3TasksToUi,
    reconcileOngoingFormImageTasksToStep3Ui
  }
}
