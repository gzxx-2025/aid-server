'use client'

import { routePathToCreationStep } from '~/utils/createFlowRoutes'
import {
  claimFormImagesFromTaskComplete,
  isFormImageAutoUseTaskType
} from '~/utils/formImageAutoUse'
import { resolveStep3FormImageTrackDoneOutcome } from '~/utils/formImageTaskOutcome'
import {
  getStep3FormImageTaskMeta,
  resolveStep3FormImageTaskDone
} from '~/utils/step3FormImageTaskRegistry'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { fetchFlowUserTaskList } from '~/utils/userTaskListFlowOnce'
import type { UserTaskRow } from '~/types/business-api'
import {
  isBenignStep3TaskAbortMessage,
  isFormImageOrCardUserTaskType,
  isFormImageUserTaskType,
  isOngoingUserTaskStatus,
  isStep3FormRelatedTaskType,
  parseFormIdFromInputSnapshotRecord,
  parseFormIdsFromBatchInputSnapshot,
  parseTaskId
} from './scpTaskUtils'
import type { createScpFinalizerBaseOps } from './scpFinalizerBaseOps'
import type { createScpFormFinalizerOps } from './scpFormFinalizerOps'
import type { ScpCtx, TabKey, UserTaskSseOutcome } from './types'

type BaseOps = ReturnType<typeof createScpFinalizerBaseOps>
type FormOps = ReturnType<typeof createScpFormFinalizerOps>

export function createScpFinalizerRecoveryOps(ctx: ScpCtx, base: BaseOps, form: FormOps) {
  function handleFormImageTaskSettledFromModal(event: Event) {
    const detail = (
      event as CustomEvent<{
        taskId?: number
        ok?: boolean
        completeData?: unknown
        errorMessage?: string
        taskType?: string | null
        tab?: TabKey
      }>
    ).detail
    const taskId = Number(detail?.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) return
    const taskMeta = getStep3FormImageTaskMeta(taskId)
    const tab: TabKey =
      detail?.tab === 'scene' || detail?.tab === 'character' || detail?.tab === 'prop'
        ? detail.tab
        : taskMeta?.tab ?? ctx.activeTab.get()
    const taskType = detail?.taskType ?? taskMeta?.taskType ?? null
    if (detail?.ok === false) {
      void form.finalizeStep3FormImageTaskFailure(tab, detail.errorMessage || '生图失败', {
        completeData: detail.completeData,
        taskId
      })
      return
    }
    void form.finalizeStep3FormImageTaskOutcome(tab, {
      completeData: detail?.completeData,
      taskType,
      taskId
    })
  }

  function handleFormCardBatchSettledEvent(event: Event) {
    const detail = (event as CustomEvent<{ sourceImageIds?: number[] }>).detail
    form.settleSettingCardBatchLoadingState(detail?.sourceImageIds)
  }

  async function claimFormImagesFromMatchingTerminalTasks(generatingFormIds: Set<number>) {
    if (!generatingFormIds.size) return
    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    if (!saveCtx) return
    const allRows = await fetchFlowUserTaskList(saveCtx.projectId, { intent: 'read' }).catch(
      () => [] as UserTaskRow[]
    )
    for (const row of allRows) {
      if (!row || !isFormImageAutoUseTaskType(row.taskType)) continue
      const status = String(row.status || '').toUpperCase()
      if (status !== 'SUCCEEDED' && status !== 'PARTIAL_FAILED') continue
      if (parseTaskId(row.id) == null) continue
      try {
        const batchFormIds = parseFormIdsFromBatchInputSnapshot(row)
        const singleFormId = parseFormIdFromInputSnapshotRecord(row)
        const covered =
          batchFormIds.some((formId) => generatingFormIds.has(formId)) ||
          (singleFormId != null && generatingFormIds.has(singleFormId))
        if (!covered) continue
        await claimFormImagesFromTaskComplete(row.taskType, row.resultData, {
          projectId: saveCtx.projectId
        })
      } catch {
        /* ignore */
      }
    }
  }

  async function recoverStaleGeneratingAfterCompletedFormImageTasks() {
    if (routePathToCreationStep(ctx.route().path) !== 'scene-character') return
    const stillGenerating =
      Object.values(ctx.sceneGenerationStatus.get()).some((status) => status === 'generating') ||
      Object.values(ctx.characterFormGenerationStatus.get()).some(
        (status) => status === 'generating'
      ) ||
      Object.values(ctx.propFormGenerationStatus.get()).some((status) => status === 'generating')
    const stillSettingCardBusy = ctx.isSettingCardBatchBusy()
    if (!stillGenerating && !stillSettingCardBusy) return
    const hasOngoingFormImage = ctx.ongoingTasks
      .get()
      .some(
        (task) =>
          task &&
          isStep3FormRelatedTaskType(task.taskType) &&
          isOngoingUserTaskStatus(task.status)
      )
    if (hasOngoingFormImage || ctx.hasActiveTrackedTasks()) return

    await claimFormImagesFromMatchingTerminalTasks(ctx.collectGeneratingFormIdsForStep3())
    if (stillSettingCardBusy) {
      form.settleSettingCardBatchLoadingState()
      await ctx.loadPersonalAssetsForTab('character')
      ctx.sanitizeStep3SceneImagesState()
      ctx.reconcileStep3GeneratingWithLoadedImages()
    }
    const tabsToRefresh = new Set<TabKey>()
    if (Object.values(ctx.sceneGenerationStatus.get()).some((status) => status === 'generating')) {
      tabsToRefresh.add('scene')
    }
    if (
      Object.values(ctx.characterFormGenerationStatus.get()).some(
        (status) => status === 'generating'
      )
    ) {
      tabsToRefresh.add('character')
    }
    if (Object.values(ctx.propFormGenerationStatus.get()).some((status) => status === 'generating')) {
      tabsToRefresh.add('prop')
    }
    for (const tab of tabsToRefresh) await ctx.loadPersonalAssetsForTab(tab)
    ctx.sanitizeStep3SceneImagesState()
    ctx.reconcileStep3GeneratingWithLoadedImages()
  }

  async function shouldDeferStep3TaskFailureForBenignDisconnect(
    taskId: number,
    errorMessage: string
  ): Promise<boolean> {
    if (!isBenignStep3TaskAbortMessage(errorMessage)) return false
    return base.shouldSkipStep3LoadingSettleForOngoingTask(taskId)
  }

  function notifyStep3FormImageTaskDoneFromTrack(payload: {
    taskId: number
    taskType?: string | null
    didFinalizeStep3Task: boolean
    res: UserTaskSseOutcome | undefined
    wasTabSwitchClose: boolean
  }) {
    if (payload.wasTabSwitchClose) return
    if (
      !isFormImageOrCardUserTaskType(payload.taskType) &&
      !isFormImageUserTaskType(payload.taskType)
    ) {
      return
    }
    resolveStep3FormImageTaskDone(
      payload.taskId,
      resolveStep3FormImageTrackDoneOutcome({
        didFinalizeStep3Task: payload.didFinalizeStep3Task,
        res: payload.res
      })
    )
  }

  return {
    handleFormImageTaskSettledFromModal,
    handleFormCardBatchSettledEvent,
    claimFormImagesFromMatchingTerminalTasks,
    recoverStaleGeneratingAfterCompletedFormImageTasks,
    shouldDeferStep3TaskFailureForBenignDisconnect,
    notifyStep3FormImageTaskDoneFromTrack
  }
}
