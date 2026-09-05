'use client'

/**
 * 顶栏全局任务面板（GlobalGenerateTaskPopover）的停止/重新开始/续生指令编排
 * （原 CreateFlowShell.vue 全局任务段拆分）。
 */

import { message } from 'antd'
import { useCallback } from 'react'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { fetchUserTaskDetailOnce } from '~/composables/useTaskSseFollow'
import { useCreationStore } from '~/stores/creation'
import type { BillingQuoteRequest, UserTaskRow } from '~/types/business-api'
import { dispatchCreateFlowTaskCommand } from '~/utils/createFlowTaskCommand'
import { buildTaskResumeBillingRequest, normUserTaskType } from '~/utils/taskPartialFailed'
import {
  extractSceneIdsFromPartialFailed,
  parseTaskPartialFailedData
} from '~/utils/taskPartialFailed'
import { buildStoryboardScriptSubmitBody } from '~/utils/storyboardScriptSubmitBody'
import { normUserTaskCancelType,requestCancelUserTask } from '~/utils/userTaskCancelFlow'

function parseTaskId(raw: unknown): number | null {
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

function bizErrMsg(e: unknown): string {
  const x = e as { msg?: string; message?: string }
  return String(x?.msg ?? x?.message ?? (e as Error)?.message ?? '')
}

async function resolveFailedStoryboardScriptSceneIds(task: UserTaskRow): Promise<number[]> {
  const fromRow = extractSceneIdsFromPartialFailed(parseTaskPartialFailedData(task.resultData))
  if (fromRow.length) return fromRow
  try {
    const detail = await fetchUserTaskDetailOnce(Number(task.id))
    const fromDetail = extractSceneIdsFromPartialFailed(
      parseTaskPartialFailedData(detail?.resultData)
    )
    if (fromDetail.length) return fromDetail
  } catch {
    // 任务详情不可用时回落当前步骤已保存的失败明细。
  }
  return extractSceneIdsFromPartialFailed(
    useCreationStore.getState().storyboardScriptPartialFailedData
  )
}

/**
 * 任务类型 → 所属流程步骤（CREATION_FLOW_STEPS 下标）。
 * 续生/重新开始/继续跟进都必须先落到任务所属步骤页，再由该页受理指令；
 * 素材准备（2）仅承接智能提取与形态/形态图等第三步任务。
 */
function resolveTaskOwnerStepIndex(taskType: unknown): number {
  const ty = normUserTaskType(taskType)
  if (ty === 'storyboard_audio_generate') {
    return 5
  }
  if (ty === 'storyboard_video_prompt_batch' || ty === 'storyboard_video_generate') {
    return 4
  }
  if (
    ty === 'storyboard_script_batch' ||
    ty === 'storyboard_image_prompt_batch' ||
    ty === 'storyboard_image_generate' ||
    ty === 'storyboard_image_batch'
  ) {
    return 3
  }
  return 2
}

export function useCreateFlowGlobalTasks(options: {
  goToCreateStep: (stepIndex: number) => Promise<void>
}) {
  const { goToCreateStep } = options

  const handleGlobalTaskRestart = useCallback(
    async (task: UserTaskRow) => {
      const taskId = parseTaskId(task.id)
      if (!taskId) {
        message.warning('任务ID无效，无法重新开始')
        return
      }
      const ty = normUserTaskCancelType(task.taskType)
      const status = String(task?.status ?? '').toUpperCase()
      await goToCreateStep(resolveTaskOwnerStepIndex(task.taskType))
      if (status === 'FAILED' && ty === 'storyboard_script_batch') {
        const sceneIds = await resolveFailedStoryboardScriptSceneIds(task)
        dispatchCreateFlowTaskCommand('restart', {
          taskId,
          taskType: task.taskType ?? null,
          ...(sceneIds.length ? { sceneIds } : {}),
          manualAgentModelPick: false
        })
      } else {
        useCreationStore.getState().removePausedTaskFollow(taskId)
        dispatchCreateFlowTaskCommand('track', { taskId, taskType: task.taskType ?? null })
      }
      window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
    },
    [goToCreateStep]
  )

  const resolveGlobalTaskRestartBillingRequest = useCallback(
    async (task: UserTaskRow): Promise<BillingQuoteRequest | null> => {
      if (normUserTaskType(task.taskType) !== 'storyboard_script_batch') return null
      const sceneIds = await resolveFailedStoryboardScriptSceneIds(task)
      const store = useCreationStore.getState()
      const submitBody = await buildStoryboardScriptSubmitBody({
        store,
        route: getRouteLikeSnapshot(),
        currentPanels: store.formData.storyboardScript.panels,
        ...(sceneIds.length ? { sceneIds } : {}),
        manualAgentModelPick: false
      })
      return submitBody
        ? { quoteType: 'STORYBOARD_SCRIPT', payload: { ...submitBody } }
        : null
    },
    []
  )

  const handleGlobalTaskResume = useCallback(
    async (task: UserTaskRow) => {
      const taskId = parseTaskId(task.id)
      if (!taskId) {
        message.warning('任务ID无效，无法续生')
        return
      }
      const ty = normUserTaskType(task.taskType)
      const hide = message.loading('正在重新生成...', 0)
      try {
        useCreationStore.getState().removePausedTaskFollow(taskId)
        await goToCreateStep(resolveTaskOwnerStepIndex(task.taskType))
        dispatchCreateFlowTaskCommand('resume', { taskId, taskType: task.taskType ?? null })
        if (ty === 'storyboard_script_batch') {
          message.success('分镜续生已提交')
        } else if (ty === 'asset_extract') {
          message.success('智能提取续跑已提交')
        } else if (ty === 'storyboard_image_prompt_batch') {
          message.success('分镜图提示词续生已提交')
        } else if (ty === 'storyboard_video_prompt_batch') {
          message.success('分镜视频提示词续生已提交')
        } else if (ty === 'storyboard_video_generate') {
          message.success('分镜视频续生已提交')
        } else {
          message.success('续生已提交')
        }
      } catch (e: unknown) {
        message.error(bizErrMsg(e) || '续生失败')
      } finally {
        hide()
        /** 任务列表刷新由各步骤 resume 处理器在续生接口完成后统一派发，避免与 finally 重复触发 */
      }
    },
    [goToCreateStep]
  )

  const resolveGlobalTaskResumeBillingRequest = useCallback(
    (task: UserTaskRow): BillingQuoteRequest | null => buildTaskResumeBillingRequest(task.id),
    []
  )

  const handleGlobalTaskStop = useCallback(async (task: UserTaskRow) => {
    const taskId = parseTaskId(task.id)
    if (!taskId) {
      message.warning('任务ID无效，无法停止')
      return
    }
    let remoteCancelRequested = false
    try {
      await requestCancelUserTask(task)
      remoteCancelRequested = true
      message.success('已请求停止生成')
    } catch (e: unknown) {
      const ax = e as { response?: { status?: number }; msg?: string; message?: string }
      const st = ax?.response?.status
      if (st === 404) {
        message.warning('停止接口未就绪（404），已仅停止本页进度展示')
      } else {
        message.warning(bizErrMsg(e) || '停止任务请求失败，已仅停止本页进度展示')
      }
    }
    useCreationStore.getState().addPausedTaskFollow(taskId)
    window.dispatchEvent(
      new CustomEvent('create-flow-stop-task', {
        detail: { taskId, taskType: task.taskType ?? null, remoteCancelRequested }
      })
    )
    window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
  }, [])

  return {
    handleGlobalTaskRestart,
    resolveGlobalTaskRestartBillingRequest,
    handleGlobalTaskResume,
    resolveGlobalTaskResumeBillingRequest,
    handleGlobalTaskStop
  }
}
