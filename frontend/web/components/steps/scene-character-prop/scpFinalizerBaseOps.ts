'use client'

import { isUserTaskLocallyTerminal,markUserTaskLocallyTerminal } from '~/hooks/useTaskOngoing'
import { userTaskDetailCached } from '~/utils/businessApi'
import {
getStep3FormImageTaskFormIds,
hasOngoingStep3FormImageTasksForTab,
unregisterStep3FormImageTask
} from '~/utils/step3FormImageTaskRegistry'
import { settleStep3FlowLoadingState } from '~/utils/step3LiveGenRestore'
import {
isFormImageOrCardUserTaskType,
isOngoingUserTaskStatus,
isStep3FormGenerateTaskType,
normUserTaskType,
parseFormImageFailedFormIdsFromComplete,
parseFormImageSuccessItemsFromComplete
} from './scpTaskUtils'
import { GLOBAL_TASKS_UPDATED_EVENT,type ScpCtx,type TabKey,type UserTaskSseOutcome } from './types'

export function createScpFinalizerBaseOps(ctx: ScpCtx) {
  function removeTaskIdFromOngoingList(taskId: number) {
    const id = Number(taskId)
    if (!Number.isFinite(id) || id <= 0) return
    const next = ctx.ongoingTasks.get().filter((t) => Number(t.id) !== id)
    if (next.length !== ctx.ongoingTasks.get().length) {
      ctx.ongoingTasks.set(next)
    }
  }

  /** 合并短时间内的多次通知，避免任务角标与 Popover 对 /task/list 风暴式请求 */
  function notifyGlobalGenerateTaskListUpdated(taskId?: number) {
    if (typeof window === 'undefined') return
    if (taskId != null) markUserTaskLocallyTerminal(taskId)
    if (ctx.notifyGlobalTasksDebounceTimer) clearTimeout(ctx.notifyGlobalTasksDebounceTimer)
    ctx.notifyGlobalTasksDebounceTimer = setTimeout(() => {
      ctx.notifyGlobalTasksDebounceTimer = null
      window.dispatchEvent(
        new CustomEvent(GLOBAL_TASKS_UPDATED_EVENT, {
          detail: taskId != null ? { taskId } : undefined
        })
      )
    }, 400)
  }

  /** 形态图/设定卡任务终态：清除 formId 标记、Tab 进度与流程条 loading */
  function settleStep3FormImageTaskTerminalUi(
    tab: TabKey,
    options?: {
      completeData?: unknown
      taskInputSnapshot?: { inputSnapshot?: string | null; taskType?: string | null }
      taskId?: number
    }
  ) {
    if (options?.taskId != null) {
      unregisterStep3FormImageTask(options.taskId)
    }
    if (!hasOngoingStep3FormImageTasksForTab(tab)) {
      ctx.clearStep3TabTaskProgress(tab)
    }
    ctx.reconcileStep3GeneratingWithLoadedImages()
    ctx.forceSettleTabGeneratingSlots(tab)
    ctx.clearStep3ExtractingTaskProgressIfIdle()
    void settleStep3FlowLoadingState(ctx.store(), ctx.route())
  }

  function isStep3TerminalSseOutcome(
    res: UserTaskSseOutcome | undefined
  ): res is UserTaskSseOutcome {
    return res?.type === 'complete' || res?.type === 'partial_failed' || res?.type === 'error'
  }

  /** SSE 终态（complete / partial_failed / error）后统一清除流程条与 Tab loading */
  function settleStep3TaskFlowLoadingOnTerminalSse(
    tab: TabKey,
    taskType: string | null | undefined,
    res: UserTaskSseOutcome
  ) {
    const ty = normUserTaskType(taskType)
    const isFormImageLike =
      isFormImageOrCardUserTaskType(taskType) ||
      ty === 'image_upscale' ||
      ty === 'form_edit_chat' ||
      ty === 'form_multi_view'

    if (isFormImageLike) {
      settleStep3FormImageTaskTerminalUi(tab, {
        completeData:
          res.type === 'complete' || res.type === 'partial_failed'
            ? (res.data ?? undefined)
            : undefined,
        taskId: undefined
      })
      return
    }

    if (isStep3FormGenerateTaskType(taskType)) {
      ctx.clearStep3TabTaskProgress(tab)
      ctx.reconcileStep3GeneratingWithLoadedImages()
      ctx.clearStep3ExtractingTaskProgressIfIdle()
      void settleStep3FlowLoadingState(ctx.store(), ctx.route())
      return
    }

    ctx.clearStep3ExtractingTaskProgressIfIdle()
    void settleStep3FlowLoadingState(ctx.store(), ctx.route())
  }

  async function shouldSkipStep3LoadingSettleForOngoingTask(taskId: number): Promise<boolean> {
    /** 优先用本地缓存判断进行中状态，避免断连风暴里对同一任务反复 force 打 detail */
    if (isUserTaskLocallyTerminal(taskId)) return false
    const row = ctx.getRecentStep3TaskRow(taskId)
    if (row && isOngoingUserTaskStatus(row.status)) return true
    if (
      ctx.ongoingTasks.get().some(
        (t) => Number(t.id) === Number(taskId) && isOngoingUserTaskStatus(t.status)
      )
    ) {
      return true
    }
    try {
      const d = await userTaskDetailCached(taskId)
      return !!d && isOngoingUserTaskStatus(d.status)
    } catch {
      return false
    }
  }

  function collectFormIdsToUnmarkAfterFormImageTask(
    tab: TabKey,
    options?: {
      completeData?: unknown
      taskInputSnapshot?: { inputSnapshot?: string | null; taskType?: string | null }
      taskId?: number
    }
  ): number[] {
    const ids = new Set<number>()
    for (const item of parseFormImageSuccessItemsFromComplete(options?.completeData ?? null)) {
      ids.add(item.formId)
    }
    for (const fid of parseFormImageFailedFormIdsFromComplete(options?.completeData ?? null)) {
      ids.add(fid)
    }
    if (options?.taskInputSnapshot) {
      for (const fid of ctx.collectFormIdsFromFormImageTaskDetail(options.taskInputSnapshot)) {
        ids.add(fid)
      }
    }
    if (options?.taskId != null) {
      for (const fid of getStep3FormImageTaskFormIds(options.taskId)) {
        ids.add(fid)
      }
    }
    return [...ids]
  }

  async function fetchFormImageTaskInputSnapshot(
    taskId: number
  ): Promise<{ inputSnapshot?: string | null; taskType?: string | null } | null> {
    try {
      const detail = await userTaskDetailCached(taskId, { force: true })
      if (!detail || !isFormImageOrCardUserTaskType(detail.taskType)) return null
      return { inputSnapshot: detail.inputSnapshot, taskType: detail.taskType }
    } catch {
      return null
    }
  }

  return {
    collectFormIdsToUnmarkAfterFormImageTask,
    fetchFormImageTaskInputSnapshot,
    isStep3TerminalSseOutcome,
    notifyGlobalGenerateTaskListUpdated,
    removeTaskIdFromOngoingList,
    settleStep3FormImageTaskTerminalUi,
    settleStep3TaskFlowLoadingOnTerminalSse,
    shouldSkipStep3LoadingSettleForOngoingTask,
  }
}
