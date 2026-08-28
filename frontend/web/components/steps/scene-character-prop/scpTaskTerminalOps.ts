import { message } from 'antd'
import { isTerminalTaskStatus } from '~/hooks/useTaskSseFollow'
import { isUserTaskLocallyTerminal } from '~/hooks/useTaskOngoing'
import { routePathToCreationStep } from '~/utils/createFlowRoutes'
import { isFormImageAutoUseTaskType } from '~/utils/formImageAutoUse'
import { resolveFormImageBatchCompleteOutcome } from '~/utils/formImageTaskOutcome'
import type { UserTaskRow } from '~/types/business-api'
import {
  isFormImageOrCardUserTaskType,
  isImageUpscaleUserTaskType,
  isOngoingUserTaskStatus,
  isStep3FormGenerateTaskType,
  isStep3FormRelatedTaskType,
  normUserTaskType
} from './scpTaskUtils'
import type { ScpCtx, TabKey } from './types'

type TerminalTaskDetail = {
  status?: string | null
  taskType?: string | null
  resultData?: unknown
  errorMessage?: string | null
}

export function createScpTaskTerminalOps(
  ctx: ScpCtx,
  getRecentTaskRow: (taskId: number) => UserTaskRow | undefined
) {
  const finalizeTerminalStep3TaskFromDetail = async (
    taskId: number,
    tab: TabKey,
    detail: TerminalTaskDetail
  ): Promise<boolean> => {
    if (isOngoingUserTaskStatus(detail.status)) return false
    const taskType = detail.taskType
    const status = String(detail.status || '').toUpperCase()
    const releaseFollow = () => {
      if (isStep3FormRelatedTaskType(taskType)) {
        ctx.store().endStep3FormImageTaskFollow(taskId)
      }
    }

    if (status === 'FAILED') {
      if (isStep3FormGenerateTaskType(taskType)) {
        await ctx.finalizeStep3FormGenerateTaskFailure(
          tab,
          taskId,
          detail.errorMessage || '形态生成失败，请稍后重试'
        )
      } else if (isFormImageOrCardUserTaskType(taskType)) {
        await ctx.finalizeStep3FormImageTaskFailure(tab, detail.errorMessage || '形态图生成失败', {
          completeData: detail.resultData,
          taskId
        })
      } else {
        message.error(detail.errorMessage || '任务失败')
      }
      finishTrackedTask(ctx, taskId, releaseFollow)
      return true
    }

    if (status === 'PARTIAL_FAILED') {
      if (isFormImageOrCardUserTaskType(taskType) || isFormImageAutoUseTaskType(taskType)) {
        const outcome = resolveFormImageBatchCompleteOutcome(detail.resultData)
        if (outcome?.ok === false) {
          await ctx.finalizeStep3FormImageTaskFailure(tab, outcome.errorMessage, {
            completeData: detail.resultData,
            taskId
          })
        } else {
          await ctx.finalizeStep3FormImageTaskOutcome(tab, {
            partialFailMessages: outcome?.ok
              ? outcome.partialFailMessages
              : [detail.errorMessage || '部分形态图生成失败'],
            completeData: detail.resultData,
            taskType,
            taskId
          })
        }
      } else {
        message.warning(detail.errorMessage || '部分生成失败，可在任务中心点击续生')
      }
      finishTrackedTask(ctx, taskId, releaseFollow)
      return true
    }

    if (status !== 'SUCCEEDED') return false
    if (isStep3FormGenerateTaskType(taskType)) {
      await ctx.finalizeStep3FormGenerateTaskOutcome(tab, taskId, detail.resultData)
    } else if (
      isFormImageOrCardUserTaskType(taskType) ||
      isImageUpscaleUserTaskType(taskType) ||
      normUserTaskType(taskType) === 'form_edit_chat' ||
      normUserTaskType(taskType) === 'form_multi_view'
    ) {
      const outcome = resolveFormImageBatchCompleteOutcome(detail.resultData)
      if (outcome?.ok === false) {
        await ctx.finalizeStep3FormImageTaskFailure(tab, outcome.errorMessage, {
          completeData: detail.resultData,
          taskId
        })
      } else {
        await ctx.finalizeStep3FormImageTaskOutcome(tab, {
          partialFailMessages: outcome?.ok ? outcome.partialFailMessages : undefined,
          completeData: detail.resultData,
          taskType,
          taskId
        })
      }
    }
    finishTrackedTask(ctx, taskId, releaseFollow)
    return true
  }

  const finalizeTerminalStep3TasksForTab = async (tab: TabKey) => {
    if (routePathToCreationStep(ctx.route().path) !== 'scene-character') return
    const taskIds = Object.entries(ctx.step3TaskIdToTab.get())
      .filter(([, taskTab]) => taskTab === tab)
      .map(([id]) => Number(id))
      .filter((id) => Number.isFinite(id) && id > 0)
    for (const taskId of taskIds) {
      if (ctx.step3SseTabSwitchClosing.has(taskId) || ctx.activeTaskStreamClosers.has(taskId)) {
        continue
      }
      const row = getRecentTaskRow(taskId)
      if (row && isOngoingUserTaskStatus(row.status) && !isUserTaskLocallyTerminal(taskId)) {
        continue
      }
      try {
        if (row && !isOngoingUserTaskStatus(row.status)) {
          await finalizeTerminalStep3TaskFromDetail(taskId, tab, row)
        } else if (isUserTaskLocallyTerminal(taskId)) {
          ctx.unregisterStep3TrackedTaskTab(taskId)
          ctx.removeTaskIdFromOngoingList(taskId)
          ctx.store().endStep3FormImageTaskFollow(taskId)
        }
      } catch {
        // 后续 list/SSE 同步会再次尝试终态收敛。
      }
    }
  }

  return { finalizeTerminalStep3TaskFromDetail, finalizeTerminalStep3TasksForTab }
}

function finishTrackedTask(ctx: ScpCtx, taskId: number, releaseFollow: () => void) {
  ctx.unregisterStep3TrackedTaskTab(taskId)
  releaseFollow()
}
