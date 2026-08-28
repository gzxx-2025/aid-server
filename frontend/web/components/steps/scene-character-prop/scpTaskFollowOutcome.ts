import { message } from 'antd'
import { userTaskDetailCached } from '~/utils/businessApi'
import { isFormImageAutoUseTaskType } from '~/utils/formImageAutoUse'
import { resolveFormImageBatchCompleteOutcome } from '~/utils/formImageTaskOutcome'
import {
isBenignStep3TaskAbortError,
isBenignStep3TaskAbortMessage,
isFormImageOrCardUserTaskType,
isOngoingUserTaskStatus,
isStep3FormGenerateTaskType,
isStep3FormRelatedTaskType
} from './scpTaskUtils'
import type { ScpCtx,TabKey,UserTaskSseOutcome } from './types'
export interface ScpTrackTaskPayload {
  taskId: number
  taskType?: string | null
  tab?: TabKey
  skipPreSseHydrate?: boolean
  assetIds?: number[]
  skipGateAcquire?: boolean
  gateOwner?: import('~/utils/step3SseConcurrencyGate').Step3SseSlotOwner
}

interface FollowOutcomeFlags {
  didFinalizeStep3Task: boolean
  suspendedForReconnect: boolean
}

interface ApplyOutcomeInput {
  ctx: ScpCtx
  payload: ScpTrackTaskPayload
  res: UserTaskSseOutcome
  ty: string
  sessionAtStart: number
}

export async function applyScpTaskFollowOutcome(
  input: ApplyOutcomeInput
): Promise<FollowOutcomeFlags> {
  const { ctx, payload, res, ty, sessionAtStart } = input
  let didFinalizeStep3Task = false
  let suspendedForReconnect = false
  const finish = (): FollowOutcomeFlags => ({
    didFinalizeStep3Task,
    suspendedForReconnect
  })

if (res.type === 'partial_failed') {
  if (sessionAtStart !== ctx.taskFollowSession) return finish()
  if (ty === 'asset_extract') {
    const tabForLoad = payload.tab ?? ctx.activeTab.get()
    await ctx.loadPersonalAssetsForTab(tabForLoad, { allowWhenExtracting: true })
    message.warning(res.errorMessage || '部分生成失败，可在任务中心点击续生')
    return finish()
  }
  if (
    isFormImageOrCardUserTaskType(payload.taskType) ||
    isFormImageAutoUseTaskType(payload.taskType)
  ) {
    const tabForLoad = payload.tab ?? ctx.activeTab.get()
    const outcome = resolveFormImageBatchCompleteOutcome(res.data)
    if (outcome?.ok === false) {
      await ctx.finalizeStep3FormImageTaskFailure(tabForLoad, outcome.errorMessage, {
        completeData: res.data,
        taskId: payload.taskId
      })
    } else {
      await ctx.finalizeStep3FormImageTaskOutcome(tabForLoad, {
        partialFailMessages: outcome?.ok
          ? outcome.partialFailMessages
          : [res.errorMessage || '部分形态图生成失败'],
        completeData: res.data,
        taskType: payload.taskType,
        taskId: payload.taskId
      })
    }
    didFinalizeStep3Task = true
    return finish()
  }
  message.warning(res.errorMessage || '部分生成失败，可在任务中心点击续生')
  return finish()
}

if (res.type === 'error') {
  if (sessionAtStart !== ctx.taskFollowSession) return finish()
  const tabForLoad = payload.tab ?? ctx.activeTab.get()
  if (isStep3FormGenerateTaskType(payload.taskType)) {
    if (
      await ctx.shouldDeferStep3TaskFailureForBenignDisconnect(
        payload.taskId,
        res.errorMessage || '形态生成失败，请稍后重试'
      )
    ) {
      suspendedForReconnect = true
      return finish()
    }
    await ctx.finalizeStep3FormGenerateTaskFailure(
      tabForLoad,
      payload.taskId,
      res.errorMessage || '形态生成失败，请稍后重试'
    )
    didFinalizeStep3Task = true
    return finish()
  }
  if (isFormImageOrCardUserTaskType(payload.taskType)) {
    try {
      /** raceFormImageSseOrPollTaskDone 刚 force 查过一次，这里复用 3s 突发缓存即可 */
      const d = await userTaskDetailCached(payload.taskId)
      if (!d) throw new Error('task detail missing')
      if (
        isFormImageOrCardUserTaskType(d.taskType) &&
        String(d.status || '').toUpperCase() === 'SUCCEEDED'
      ) {
        const outcome = resolveFormImageBatchCompleteOutcome(d.resultData)
        if (outcome?.ok === false) {
          await ctx.finalizeStep3FormImageTaskFailure(tabForLoad, outcome.errorMessage, {
            completeData: d.resultData,
            taskId: payload.taskId
          })
          didFinalizeStep3Task = true
          return finish()
        }
        await ctx.finalizeStep3FormImageTaskOutcome(tabForLoad, {
          partialFailMessages: outcome?.ok ? outcome.partialFailMessages : undefined,
          completeData: d.resultData ?? null,
          taskType: d.taskType ?? payload.taskType,
          taskId: payload.taskId
        })
        didFinalizeStep3Task = true
        return finish()
      }
    } catch {
      /* ignore */
    }
    if (
      await ctx.shouldDeferStep3TaskFailureForBenignDisconnect(
        payload.taskId,
        res.errorMessage || '任务失败'
      )
    ) {
      suspendedForReconnect = true
      return finish()
    }
    await ctx.finalizeStep3FormImageTaskFailure(tabForLoad, res.errorMessage || '任务失败', {
      taskId: payload.taskId
    })
    didFinalizeStep3Task = true
    return finish()
  }
  if (isStep3FormRelatedTaskType(payload.taskType)) {
    ctx.settleStep3TaskFlowLoadingOnTerminalSse(tabForLoad, payload.taskType, res)
    didFinalizeStep3Task = true
  }
  if (!isBenignStep3TaskAbortMessage(res.errorMessage || '')) {
    message.error(res.errorMessage || '任务失败')
  }
  return finish()
}

if (sessionAtStart !== ctx.taskFollowSession) return finish()
const tabForLoad = payload.tab ?? ctx.activeTab.get()
if (res.type === 'complete') {
  if (isStep3FormGenerateTaskType(payload.taskType)) {
    await ctx.finalizeStep3FormGenerateTaskOutcome(tabForLoad, payload.taskId, res.data)
    didFinalizeStep3Task = true
    return finish()
  }
  if (
    isFormImageOrCardUserTaskType(payload.taskType) ||
    ty === 'image_upscale' ||
    ty === 'form_edit_chat' ||
    ty === 'form_multi_view'
  ) {
    const outcome = resolveFormImageBatchCompleteOutcome(res.data)
    if (outcome?.ok === false) {
      await ctx.finalizeStep3FormImageTaskFailure(tabForLoad, outcome.errorMessage, {
        completeData: res.data,
        taskId: payload.taskId
      })
      didFinalizeStep3Task = true
      return finish()
    }
    await ctx.finalizeStep3FormImageTaskOutcome(tabForLoad, {
      partialFailMessages: outcome?.ok ? outcome.partialFailMessages : undefined,
      completeData: res.data,
      taskType: payload.taskType,
      taskId: payload.taskId
    })
    didFinalizeStep3Task = true
    return finish()
  }
  if (ty === 'asset_extract') {
    await ctx.loadPersonalAssetsForTab(tabForLoad, { allowWhenExtracting: true })
    return finish()
  }
  return finish()
}
if (isFormImageOrCardUserTaskType(payload.taskType)) {
  await ctx.finalizeStep3FormImageTaskOutcome(tabForLoad, { taskId: payload.taskId })
  didFinalizeStep3Task = true
} else if (isStep3FormGenerateTaskType(payload.taskType)) {
  /** complete 分支已 return finish()；此处为非 complete 兜底，按需补查 detail */
  await ctx.finalizeStep3FormGenerateTaskOutcome(tabForLoad, payload.taskId)
  didFinalizeStep3Task = true
}

  return finish()
}

interface HandleFollowErrorInput {
  ctx: ScpCtx
  payload: ScpTrackTaskPayload
  error: unknown
  ty: string
  sessionAtStart: number
  streamConnected: boolean
  didFinalizeStep3Task: boolean
  isStaleStep3Follow: () => boolean
  finishStep3SseTabSwitchClose: (taskId: number) => void
}

export async function handleScpTaskFollowError(
  input: HandleFollowErrorInput
): Promise<FollowOutcomeFlags & { endedByTabSwitch: boolean }> {
  const {
    ctx,
    payload,
    error: e,
    ty,
    sessionAtStart,
    streamConnected,
    isStaleStep3Follow,
    finishStep3SseTabSwitchClose
  } = input
  let didFinalizeStep3Task = input.didFinalizeStep3Task
  let suspendedForReconnect = false
  let endedByTabSwitch = false
  const finish = () => ({
    didFinalizeStep3Task,
    suspendedForReconnect,
    endedByTabSwitch
  })

if (isStaleStep3Follow()) return finish()
if (ctx.step3SseTabSwitchClosing.has(payload.taskId)) {
  endedByTabSwitch = true
  finishStep3SseTabSwitchClose(payload.taskId)
  /** 切 Tab 断开属预期，禁止再打 task/detail */
  return finish()
}
if (sessionAtStart !== ctx.taskFollowSession) return finish()
const tabForLoad = payload.tab ?? ctx.activeTab.get()
if (isFormImageOrCardUserTaskType(payload.taskType)) {
  try {
    /** 断连抛错前 race 兜底已 force 查过一次 detail，这里复用突发缓存避免重复请求 */
    const d = await userTaskDetailCached(payload.taskId)
    if (!d) throw new Error('task detail missing')
    if (
      isFormImageOrCardUserTaskType(d.taskType) &&
      String(d.status || '').toUpperCase() === 'SUCCEEDED'
    ) {
      const outcome = resolveFormImageBatchCompleteOutcome(d.resultData)
      if (outcome?.ok === false) {
        await ctx.finalizeStep3FormImageTaskFailure(tabForLoad, outcome.errorMessage, {
          completeData: d.resultData,
          taskId: payload.taskId
        })
        didFinalizeStep3Task = true
        return finish()
      }
      await ctx.finalizeStep3FormImageTaskOutcome(tabForLoad, {
        partialFailMessages: outcome?.ok ? outcome.partialFailMessages : undefined,
        completeData: d.resultData ?? null,
        taskType: d.taskType ?? payload.taskType,
        taskId: payload.taskId
      })
      didFinalizeStep3Task = true
      return finish()
    }
    if (isOngoingUserTaskStatus(d.status)) {
      suspendedForReconnect = true
      return finish()
    }
  } catch {
    /* ignore */
  }
  if (!didFinalizeStep3Task) {
    if (await ctx.shouldSkipStep3LoadingSettleForOngoingTask(payload.taskId)) {
      suspendedForReconnect = true
      return finish()
    }
    await ctx.finalizeStep3FormImageTaskFailure(
      tabForLoad,
      String((e as { message?: string })?.message || '任务失败'),
      { taskId: payload.taskId }
    )
    didFinalizeStep3Task = true
  }
}
if (ty === 'form_edit_chat' || ty === 'form_multi_view' || ty === 'image_upscale') {
  try {
    const d = await userTaskDetailCached(payload.taskId, { force: true })
    if (!d) throw new Error('task detail missing')
    const st = String(d.status || '').toUpperCase()
    if (st === 'SUCCEEDED' || st === 'PARTIAL_FAILED') {
      const outcome = resolveFormImageBatchCompleteOutcome(d.resultData)
      await ctx.finalizeStep3FormImageTaskOutcome(tabForLoad, {
        partialFailMessages: outcome?.ok ? outcome.partialFailMessages : undefined,
        completeData: d.resultData,
        taskType: d.taskType ?? payload.taskType,
        taskId: payload.taskId
      })
      didFinalizeStep3Task = true
      return finish()
    }
    if (st === 'FAILED') {
      await ctx.finalizeStep3FormImageTaskFailure(tabForLoad, String(d.errorMessage || '任务失败'), {
        taskId: payload.taskId
      })
      didFinalizeStep3Task = true
      return finish()
    }
    if (isOngoingUserTaskStatus(d.status)) {
      suspendedForReconnect = true
      return finish()
    }
  } catch {
    /* ignore */
  }
  if (!didFinalizeStep3Task) {
    if (await ctx.shouldSkipStep3LoadingSettleForOngoingTask(payload.taskId)) {
      suspendedForReconnect = true
      return finish()
    }
    await ctx.finalizeStep3FormImageTaskFailure(
      tabForLoad,
      String((e as { message?: string })?.message || '任务失败'),
      { taskId: payload.taskId }
    )
    didFinalizeStep3Task = true
  }
}
if (isBenignStep3TaskAbortError(e)) {
  /** 良性中断（Abort/断线）且上面未证实终态：第三步任务按挂起收尾，保留登记待重连 */
  if (ty !== 'asset_extract') {
    suspendedForReconnect = true
  }
  return finish()
}
if (!streamConnected) {
  const msg = String((e as { message?: string })?.message || '')
  if (msg && !isBenignStep3TaskAbortMessage(msg)) message.error(msg)
  return finish()
}
const msg = String((e as { message?: string })?.message || '任务连接异常')
if (!isBenignStep3TaskAbortMessage(msg)) {
  message.error(msg)
}

  return finish()
}
