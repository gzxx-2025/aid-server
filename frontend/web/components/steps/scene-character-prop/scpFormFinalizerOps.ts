'use client'

import { message } from 'antd'
import { markUserTaskLocallyTerminal } from '~/hooks/useTaskOngoing'
import { invalidateUserTaskDetailCache,userTaskDetailCached } from '~/utils/businessApi'
import { routePathToCreationStep } from '~/utils/createFlowRoutes'
import {
claimFormImagesFromTaskComplete,
extractSourceImageIdsFromSettingCardComplete,
isFormCardImageTaskType
} from '~/utils/formImageAutoUse'
import {
resolveFormImageBatchCompleteOutcome,
uniqueFailMessages,
uniqueJoinFailMessages
} from '~/utils/formImageTaskOutcome'
import {
hasOngoingStep3FormImageTasksForTab,
hasStep3FormImageTaskDoneWaiter,
isFormIdUnderActiveStep3FormImageTask,
unregisterStep3FormImageTask
} from '~/utils/step3FormImageTaskRegistry'
import { settleStep3FlowLoadingState } from '~/utils/step3LiveGenRestore'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
delayMs
} from './scpRowUtils'
import {
isBenignStep3TaskAbortMessage,
parseAssetIdsFromInputSnapshotRecord,
parseFormGenerateBatchCompleteOutcome,
parseFormImageSuccessItemsFromComplete
} from './scpTaskUtils'
import { type FormGenStatus,type ScpCtx,type TabKey } from './types'

import type { createScpFinalizerBaseOps } from './scpFinalizerBaseOps'

export function createScpFormFinalizerOps(
  ctx: ScpCtx,
  base: ReturnType<typeof createScpFinalizerBaseOps>
) {
  const { collectFormIdsToUnmarkAfterFormImageTask, fetchFormImageTaskInputSnapshot, notifyGlobalGenerateTaskListUpdated, removeTaskIdFromOngoingList, settleStep3FormImageTaskTerminalUi } = base
  function finalizeSceneGenerateSuccessOffPage(
    index: number,
    sceneName: string,
    routeCtx: { scopeKey: string; projectId: number | null; episodeId: number | null }
  ) {
    ctx.store().resolveAllStep3GeneratingStatuses('success')
    ctx.patchSceneGenStatus(index, 'success', routeCtx)
    message.success(`「${sceneName}」场景图生成成功`)
  }

  async function finalizeStep3FormGenerateTaskOutcome(
    tab: TabKey,
    taskId: number,
    sseCompleteData?: unknown
  ) {
    markUserTaskLocallyTerminal(taskId)
    const onStep3Page = routePathToCreationStep(ctx.route().path) === 'scene-character'
    let assetIds = [...(ctx.step3TaskMetaById.get()[taskId]?.assetIds ?? [])]
    let batchOutcome = parseFormGenerateBatchCompleteOutcome(sseCompleteData)
    /** SSE 已带结果且本地已知 assetIds 时不再 force 打 detail */
    if (!assetIds.length || !batchOutcome) {
      try {
        invalidateUserTaskDetailCache(taskId)
        const detail = await userTaskDetailCached(taskId, { force: true })
        if (detail) {
          if (!assetIds.length) assetIds = parseAssetIdsFromInputSnapshotRecord(detail)
          if (!batchOutcome) batchOutcome = parseFormGenerateBatchCompleteOutcome(detail.resultData)
        }
      } catch {
        /* ignore */
      }
    }
    if (onStep3Page) {
      await ctx.loadPersonalAssetsForTab(tab)
      ctx.clearPendingFormGenBusyForAssetIds(assetIds)
      for (const aid of assetIds) {
        ctx.clearStep3ImageGeneratingSlotsForFormTextAssetId(aid)
      }
      ctx.reconcileStep3GeneratingWithLoadedImages()
      const alreadyFinalizedUi =
        assetIds.length > 0 && assetIds.every((aid) => !ctx.pendingFormGenBusy.get()[aid])
      if (!alreadyFinalizedUi) {
        if (batchOutcome) {
          const { successCount, failCount, failedMessages } = batchOutcome
          if (successCount > 0 && failCount > 0) {
            message.warning(
              `形态生成完成：成功 ${successCount} 个，失败 ${failCount} 个${
                failedMessages.length
                  ? `（${uniqueJoinFailMessages(failedMessages.slice(0, 3))}）`
                  : ''
              }`
            )
          } else if (successCount > 0) {
            message.success(`已成功生成 ${successCount} 个形态，可点击「自动生成」生成配图`)
          } else if (failCount > 0) {
            message.error(uniqueFailMessages(failedMessages)[0] || '批量形态生成失败，请重试')
          }
        } else if (assetIds.length > 1) {
          message.success('批量形态生成完成，可点击「自动生成」生成配图')
        } else if (assetIds.length === 1) {
          message.success('形态已生成，可点击「自动生成」生成配图')
        }
      }
    } else {
      ctx.store().resolveAllStep3GeneratingStatuses('success')
    }
    ctx.clearStep3TabTaskProgress(tab)
    ctx.unregisterStep3TrackedTaskTab(taskId)
    removeTaskIdFromOngoingList(taskId)
    void settleStep3FlowLoadingState(ctx.store(), ctx.route())
  }

  async function finalizeStep3FormGenerateTaskFailure(
    tab: TabKey,
    taskId: number,
    errorMessage: string
  ) {
    markUserTaskLocallyTerminal(taskId)
    const onStep3Page = routePathToCreationStep(ctx.route().path) === 'scene-character'
    let assetIds = [...(ctx.step3TaskMetaById.get()[taskId]?.assetIds ?? [])]
    if (!assetIds.length) {
      try {
        invalidateUserTaskDetailCache(taskId)
        const detail = await userTaskDetailCached(taskId, { force: true })
        if (detail) assetIds = parseAssetIdsFromInputSnapshotRecord(detail)
      } catch {
        /* ignore */
      }
    }
    ctx.clearPendingFormGenBusyForAssetIds(assetIds)
    if (onStep3Page) {
      for (const aid of assetIds) {
        ctx.clearStep3ImageGeneratingSlotsForFormTextAssetId(aid)
      }
      await ctx.loadPersonalAssetsForTab(tab)
    } else {
      ctx.store().resolveAllStep3GeneratingStatuses('failed')
    }
    ctx.clearStep3TabTaskProgress(tab)
    ctx.unregisterStep3TrackedTaskTab(taskId)
    removeTaskIdFromOngoingList(taskId)
    if (!isBenignStep3TaskAbortMessage(errorMessage)) {
      message.error(errorMessage)
    }
    void settleStep3FlowLoadingState(ctx.store(), ctx.route())
  }

  async function finalizeStep3FormImageTaskOutcome(
    tab: TabKey,
    options?: {
      partialFailMessages?: string[]
      completeData?: unknown
      taskType?: string | null
      taskId?: number
    }
  ) {
    if (options?.taskId != null) markUserTaskLocallyTerminal(options.taskId)
    const onStep3Page = routePathToCreationStep(ctx.route().path) === 'scene-character'
    /** SSE complete 已带结果时不再 force 打 detail 取 snapshot */
    const taskInputSnapshot =
      options?.completeData != null
        ? options?.taskType != null
          ? { taskType: options.taskType, inputSnapshot: null as string | null }
          : undefined
        : options?.taskId != null
          ? ((await fetchFormImageTaskInputSnapshot(options.taskId)) ?? undefined)
          : undefined
    const unmarkOpts = {
      completeData: options?.completeData,
      taskInputSnapshot,
      taskId: options?.taskId
    }

    if (options?.completeData != null && options?.taskType != null) {
      const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      await claimFormImagesFromTaskComplete(options.taskType, options.completeData, {
        projectId: saveCtx?.projectId
      })
    }
    if (isFormCardImageTaskType(options?.taskType)) {
      const outcome =
        options?.completeData != null
          ? resolveFormImageBatchCompleteOutcome(options.completeData)
          : null
      if (outcome?.ok === false) {
        message.error(uniqueJoinFailMessages([outcome.errorMessage]) || outcome.errorMessage)
      } else if (outcome?.ok && outcome.failCount > 0) {
        const detail = uniqueJoinFailMessages(outcome.partialFailMessages?.slice(0, 3) ?? [])
        message.warning(
          detail
            ? `设定卡已生成 ${outcome.successCount} 张，${outcome.failCount} 张失败：${detail}`
            : `设定卡已生成 ${outcome.successCount} 张，${outcome.failCount} 张失败`
        )
      } else if (outcome?.ok) {
        message.success(`角色设定卡批量生成完成，共 ${outcome.successCount} 张`)
      }
      settleSettingCardBatchLoadingState(
        extractSourceImageIdsFromSettingCardComplete(options?.completeData)
      )
    }
    if (onStep3Page) {
      const completeItems =
        options?.completeData != null
          ? parseFormImageSuccessItemsFromComplete(options.completeData)
          : []
      const targetFormIds = completeItems.map((item) => item.formId)

      // 先注销本任务，避免 loadPersonalAssets 末尾 reapply 把已完成 form 又打回 generating
      if (options?.taskId != null) {
        unregisterStep3FormImageTask(options.taskId)
      }

      // 刷新恢复：仅以 rps/list 为准并多次重试，不再合并 SSE completeData（避免「主图」空占位卡）
      for (let attempt = 0; attempt < 3; attempt++) {
        if (attempt > 0) await delayMs(attempt === 1 ? 450 : 1200)
        await ctx.loadPersonalAssetsForTab(tab)
        ctx.sanitizeStep3SceneImagesState()
        if (
          targetFormIds.length === 0 ||
          targetFormIds.every((fid) => ctx.step3SlotHasImageForFormId(fid))
        ) {
          break
        }
      }

      if (options?.completeData != null) {
        ctx.applyFormImageCompleteDataToStep3Ui(options.completeData)
        ctx.markStep3SlotsSuccessFromCompleteData(options.completeData)
      }
      // 全失败 / 无 successItems：按 complete 中的 failed formId 清 loading
      for (const fid of collectFormIdsToUnmarkAfterFormImageTask(tab, {
        completeData: options?.completeData,
        taskInputSnapshot,
        taskId: undefined
      })) {
        if (isFormIdUnderActiveStep3FormImageTask(fid)) continue
        ctx.resolveFormIdGeneratingSlotAfterCancel(fid)
      }
      ctx.reconcileStep3GeneratingWithLoadedImages()
    } else {
      ctx.store().resolveAllStep3GeneratingStatuses('success')
    }
    ctx.clearStep3ExtractingTaskProgressIfIdle()
    const partial = uniqueFailMessages(options?.partialFailMessages ?? [])
    if (partial.length) {
      message.warning(`部分形态图生成失败：${uniqueJoinFailMessages(partial)}`)
    }
    settleStep3FormImageTaskTerminalUi(tab, { ...unmarkOpts, taskId: undefined })
  }

  async function finalizeStep3FormImageTaskFailure(
    tab: TabKey,
    errorMessage: string,
    options?: { completeData?: unknown; taskId?: number; taskType?: string | null }
  ) {
    if (options?.taskId != null) markUserTaskLocallyTerminal(options.taskId)
    const onStep3Page = routePathToCreationStep(ctx.route().path) === 'scene-character'
    const taskInputSnapshot =
      options?.completeData != null
        ? options?.taskType != null
          ? { taskType: options.taskType, inputSnapshot: null as string | null }
          : undefined
        : options?.taskId != null
          ? ((await fetchFormImageTaskInputSnapshot(options.taskId)) ?? undefined)
          : undefined
    const unmarkOpts = {
      completeData: options?.completeData,
      taskInputSnapshot,
      taskId: options?.taskId
    }
    const formIdsToSettle = collectFormIdsToUnmarkAfterFormImageTask(tab, unmarkOpts)
    // 先注销任务，再清槽位：否则 isFormIdUnderActiveStep3FormImageTask 仍为 true 会跳过清除
    if (options?.taskId != null) {
      unregisterStep3FormImageTask(options.taskId)
    }
    if (isFormCardImageTaskType(taskInputSnapshot?.taskType ?? options?.taskType)) {
      settleSettingCardBatchLoadingState(
        extractSourceImageIdsFromSettingCardComplete(options?.completeData)
      )
    } else {
      settleSettingCardBatchLoadingState()
    }
    if (onStep3Page) {
      await ctx.loadPersonalAssetsForTab(tab)
    } else {
      ctx.store().resolveAllStep3GeneratingStatuses('failed')
    }
    for (const fid of formIdsToSettle) {
      ctx.resolveFormIdGeneratingSlotAfterCancel(fid, 'failed')
    }
    ctx.reconcileStep3GeneratingWithLoadedImages()
    ctx.clearStep3ExtractingTaskProgressIfIdle()
    // 有 waitForStep3FormImageTaskDone 时由 runFormImageGenerate 抛错并 toast，避免与 finalize 重复
    const waiterWillToast =
      options?.taskId != null && hasStep3FormImageTaskDoneWaiter(options.taskId)
    if (!waiterWillToast && !isBenignStep3TaskAbortMessage(errorMessage)) {
      message.error(uniqueJoinFailMessages([errorMessage]) || errorMessage)
    }
    settleStep3FormImageTaskTerminalUi(tab, { ...unmarkOpts, taskId: undefined })
  }

  /**
   * 弹窗 `create-flow-stop-task` 接管形态图 SSE 后，外层 startTrackTask 被静默中断，
   * 终态由弹窗派发本事件，走与列表 SSE complete 相同的 finalize，清除全部相关卡片 loading。
   */
  function settleSettingCardBatchLoadingState(sourceImageIds?: number[]) {
    const busyFromMap = Object.keys(ctx.settingCardGenBusyByImageId.get())
      .map(Number)
      .filter((n) => Number.isFinite(n) && n > 0)
    const ids = sourceImageIds?.length
      ? sourceImageIds.filter((n) => Number.isFinite(n) && n > 0)
      : busyFromMap

    ctx.settingCardGenBusyByImageId.set({})

    const affectedSlotKeys = new Set<string>()
    for (const imageId of ids) {
      const slotKey = ctx.findCharacterSlotKeyByRpsImageId(imageId)
      if (slotKey) affectedSlotKeys.add(slotKey)
    }

    if (ids.length) {
      ctx.resolveCharacterSettingCardGeneratingToIdle(ids)
    }

    ctx.reconcileStep3GeneratingWithLoadedImages()

    // 批量设定卡：按 sourceImageId 映射 slot；映射失败时回落到所有仍 generating 的角色形态
    if (!affectedSlotKeys.size) {
      for (const [key, st] of Object.entries(ctx.store().characterFormGenerationStatus)) {
        if (st === 'generating') affectedSlotKeys.add(key)
      }
      for (const [key, st] of Object.entries(ctx.characterFormGenerationStatus.get())) {
        if (st === 'generating') affectedSlotKeys.add(key)
      }
    }

    for (const key of affectedSlotKeys) {
      if (ctx.store().characterFormGenerationStatus[key] !== 'generating') continue
      const nextStatus: FormGenStatus = ctx.characterFormSlotHasLoadedImages(key) ? 'success' : 'idle'
      ctx.store().setCharacterFormGenerationStatus(key, nextStatus)
      ctx.characterFormGenerationStatus.set({
        ...ctx.characterFormGenerationStatus.get(),
        [key]: nextStatus
      })
    }

    if (!ctx.isSettingCardBatchBusy() && !hasOngoingStep3FormImageTasksForTab('character')) {
      ctx.clearStep3TabTaskProgress('character')
    }
    ctx.store().syncStep3GenVisualToCurrentScope()
    ctx.store().refreshStep3VisualGeneratingFlag()
    notifyGlobalGenerateTaskListUpdated()
  }

  return {
    finalizeSceneGenerateSuccessOffPage,
    finalizeStep3FormGenerateTaskFailure,
    finalizeStep3FormGenerateTaskOutcome,
    finalizeStep3FormImageTaskFailure,
    finalizeStep3FormImageTaskOutcome,
    settleSettingCardBatchLoadingState,
  }
}
