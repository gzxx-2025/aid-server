'use client'

import type { TaskStreamHandle } from '~/hooks/useTaskStream'
import { userTaskDetailCached } from '~/utils/businessApi'
import { routePathToCreationStep } from '~/utils/createFlowRoutes'
import { isFormCardImageTaskType,parseImageIdsFromTaskInputSnapshot } from '~/utils/formImageAutoUse'
import { formatPartialFailedMessage } from '~/utils/taskPartialFailed'
import {
isFormImageOrCardUserTaskType,
isFormImageUserTaskType,
isImageUpscaleUserTaskType,
isOngoingUserTaskStatus,
isStep3FormGenerateTaskType,
normUserTaskType,
parseAssetIdsFromInputSnapshotRecord,
parseFormIdFromInputSnapshotRecord,
parseFormIdsFromBatchInputSnapshot
} from './scpTaskUtils'
import type { ScpCtx,UserTaskSseOutcome } from './types'

/**
 * 形态图：以 SSE 终态为准。
 *
 * 语义约定（与形态文案任务保持一致，勿再破坏）：
 * - `stream.done` resolve 仅代表服务端明确下发了终态事件（complete/partial_failed/error/cancelled）；
 * - 断连/中断一律是 reject。此处断连时最多补查一次 task/detail：服务端已终态则按终态返回，
 *   否则**原样抛出**，交给 startTrackTask 的 catch 统一分类（切 Tab 暂停 / 断线待重连 / 真失败）。
 *   绝不允许把断连包装成 error/complete 结果——那会让调用方把「连接没了」误判成「任务终态」。
 */
export function raceFormImageSseOrPollTaskDone(
  taskId: number,
  stream: TaskStreamHandle,
  options?: {
    /** 切 Tab 主动断开 / follow 已被新世代接管时返回 true，跳过 detail 兜底避免刷屏 */
    skipDetailFallback?: () => boolean
  }
): Promise<UserTaskSseOutcome> {
  const finishFromDetail = async (): Promise<UserTaskSseOutcome | null> => {
    if (options?.skipDetailFallback?.()) return null
    try {
      const d = await userTaskDetailCached(taskId, { force: true })
      if (!d || !isFormImageOrCardUserTaskType(d.taskType)) return null
      const st = String(d.status || '').toUpperCase()
      if (st === 'SUCCEEDED' || st === 'PARTIAL_FAILED') {
        // 保留 resultData：全失败时仍走 complete，由 finalize 根据 successCount/failCount 清 loading
        return { type: 'complete', data: d.resultData ?? null }
      }
      if (st === 'FAILED') {
        return {
          type: 'error',
          errorMessage: String(d.errorMessage || '形态图生成失败')
        }
      }
    } catch {
      /* ignore */
    }
    return null
  }

  return stream.done.then(
    async (r): Promise<UserTaskSseOutcome> => {
      try {
        stream.close()
      } catch {
        /* ignore */
      }
      if (r.type === 'complete') {
        // 勿在此处把「全失败 complete」改成 error（会丢掉 failedItems，列表 loading 难清）
        return { type: 'complete', data: r.data }
      }
      if (r.type === 'cancelled') {
        return { type: 'error', errorMessage: r.message || '任务已取消' }
      }
      if (r.type === 'partial_failed') {
        return {
          type: 'partial_failed',
          data: r.data,
          errorMessage: formatPartialFailedMessage(r.data, '部分形态图生成失败')
        }
      }
      // 服务端 error 事件可能与实际成功竞态（如任务已落库成功但推送失败），补查一次为准
      const fromDetail = await finishFromDetail()
      if (fromDetail) return fromDetail
      return { type: 'error', errorMessage: r.errorMessage || '形态图生成失败' }
    },
    async (e: unknown): Promise<UserTaskSseOutcome> => {
      try {
        stream.close()
      } catch {
        /* ignore */
      }
      const fromDetail = await finishFromDetail()
      if (fromDetail) return fromDetail
      // 断连且服务端未终态：保持异常语义原样上抛，禁止在此吞掉
      throw e
    }
  )
}

export interface ScpTaskHydrateApi {
  /** 追踪形态图 / 形态文案 SSE 前：形态图恢复主列表 generating；形态文案仅恢复 pendingFormGenBusy */
  hydrateStep3GeneratingFromTaskId: (taskId: number) => Promise<boolean>
  applyStep3GeneratingFromTaskDetail: (detail: {
    status?: string | null
    taskType?: string | null
    inputSnapshot?: string | null
  }) => boolean
  /** @deprecated 兼容旧调用 */
  hydrateFormImageGeneratingFromTaskId: (taskId: number) => Promise<boolean>
}

export function useScpTaskHydrate(ctx: ScpCtx): ScpTaskHydrateApi {
  /** 追踪形态图 / 形态文案 SSE 前：形态图恢复主列表 generating；形态文案仅恢复 pendingFormGenBusy */
  async function hydrateStep3GeneratingFromTaskId(taskId: number): Promise<boolean> {
    if (routePathToCreationStep(ctx.route().path) !== 'scene-character') return false
    const detail = await userTaskDetailCached(taskId)
    if (!detail) return false
    return applyStep3GeneratingFromTaskDetail(detail)
  }

  function applyStep3GeneratingFromTaskDetail(detail: {
    status?: string | null
    taskType?: string | null
    inputSnapshot?: string | null
  }): boolean {
    if (!isOngoingUserTaskStatus(detail.status)) return false
    if (isFormImageUserTaskType(detail.taskType)) {
      const multi = parseFormIdsFromBatchInputSnapshot(detail)
      if (multi.length > 0) {
        let any = false
        for (const fid of multi) {
          if (ctx.applyFormIdToStep3GeneratingSlots(fid)) any = true
        }
        return any
      }
      const formId = parseFormIdFromInputSnapshotRecord(detail)
      if (formId == null) return false
      return ctx.applyFormIdToStep3GeneratingSlots(formId)
    }
    if (isFormCardImageTaskType(detail.taskType)) {
      const imageIds = parseImageIdsFromTaskInputSnapshot(detail.inputSnapshot)
      if (!imageIds.length) return false
      ctx.markSettingCardGenBusy(imageIds)
      let any = false
      for (const imageId of imageIds) {
        if (ctx.applyRpsImageIdToCharacterSettingCardGeneratingSlots(imageId)) any = true
      }
      return any
    }
    if (
      normUserTaskType(detail.taskType) === 'form_edit_chat' ||
      normUserTaskType(detail.taskType) === 'form_multi_view' ||
      isImageUpscaleUserTaskType(detail.taskType)
    ) {
      const formId = parseFormIdFromInputSnapshotRecord(detail)
      if (formId == null) return false
      return ctx.applyFormIdToStep3GeneratingSlots(formId)
    }
    if (isStep3FormGenerateTaskType(detail.taskType)) {
      const assetIds = parseAssetIdsFromInputSnapshotRecord(detail)
      if (!assetIds.length) return false
      let any = false
      for (const aid of assetIds) {
        if (ctx.applyAssetIdToPendingFormTextGeneratingBusy(aid)) any = true
      }
      return any
    }
    return false
  }

  /** @deprecated 兼容旧调用 */
  async function hydrateFormImageGeneratingFromTaskId(taskId: number): Promise<boolean> {
    return hydrateStep3GeneratingFromTaskId(taskId)
  }

  return {
    hydrateStep3GeneratingFromTaskId,
    applyStep3GeneratingFromTaskDetail,
    hydrateFormImageGeneratingFromTaskId
  }
}
