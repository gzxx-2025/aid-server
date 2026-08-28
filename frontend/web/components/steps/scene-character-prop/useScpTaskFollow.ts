'use client'

import { hasLiveTaskSseFollow,isUserTaskTerminal } from '~/hooks/useTaskSseFollow'
import { createTaskStream } from '~/hooks/useTaskStream'
import type { AssetExtractType } from '~/types/business-api'
import { userTaskDetailCached } from '~/utils/businessApi'
import { inferExtractAssetTabFromSse } from '~/utils/inferExtractAssetTabFromSse'
import { settleStep3FlowLoadingState } from '~/utils/step3LiveGenRestore'
import {
drainStep3SseQueue,
hasStep3SseSlot,
releaseStep3SseSlot,
requeueStep3SseItemToEnd,
tryAcquireStep3SseSlot,
type Step3SseSlotOwner
} from '~/utils/step3SseConcurrencyGate'
import { resolveStepIndexTotalFromSse } from '~/utils/taskSseProgressText'
import { scheduleFlowUserTaskListRefresh } from '~/utils/userTaskListFlowOnce'
import {
applyScpTaskFollowOutcome,
handleScpTaskFollowError,
type ScpTrackTaskPayload
} from './scpTaskFollowOutcome'
import {
isFormImageOrCardUserTaskType,
isFormImageUserTaskType,
isStep3FormGenerateTaskType,
isStep3FormRelatedTaskType,
normUserTaskType,
normalizeTaskStreamToUserOutcome,
parseExtractTypesFromInputSnapshotRecord
} from './scpTaskUtils'
import type { ScpCtx,TabKey,UserTaskSseOutcome } from './types'
import { raceFormImageSseOrPollTaskDone } from './useScpTaskHydrate'

export interface ScpTaskFollowApi {
  /** 恢复智能提取全屏 loading（与任务 extractTypes 对齐，避免三 Tab 误亮） */
  applyAssetExtractUiForTask: (taskId: number) => Promise<void>
  /**
   * 新任务提交成功后调用（write-through invalidation）：
   * 任务列表会话缓存可能早于本次提交，若不失效，切 Tab 恢复链路会在缓存里查不到该任务。
   */
  noteStep3TaskSubmitted: () => void
  /** 释放本 task 占用的并发槽（若仍持有），并尝试拉起队列中等待的 outer follow */
  releaseStep3SseGateSlotAndDrain: (taskId: number) => void
  /** 被 modal preempt 时静默挂起外层 SSE，任务进队列等待空位 */
  suspendStep3OuterSseForPreempt: (taskId: number) => void
  finishStep3SseTabSwitchClose: (taskId: number) => void
  /** Tab 切换断 SSE 后：清理遗留 closer，允许同 taskId 重连 */
  prepareStep3TaskStreamForResume: (taskId: number) => boolean
  /** 切换 Tab：断开非当前 Tab 的 SSE，保留任务登记与已缓存文案 */
  pauseStep3SseForInactiveTabs: (tab: TabKey) => void
  startTrackTask: (payload: ScpTrackTaskPayload) => Promise<void>
}

export function useScpTaskFollow(ctx: ScpCtx): ScpTaskFollowApi {
  /** 恢复智能提取全屏 loading（与任务 extractTypes 对齐，避免三 Tab 误亮） */
  async function applyAssetExtractUiForTask(taskId: number) {
    ctx.store().setExtractingAssets(true)
    let extractTypesForUi: AssetExtractType[] = ['scene', 'character', 'prop']
    try {
      const detail = await userTaskDetailCached(taskId)
      if (detail) {
        const parsed = parseExtractTypesFromInputSnapshotRecord(detail)
        if (parsed?.length) extractTypesForUi = parsed
      }
    } catch {
      /* ignore */
    }
    const first = extractTypesForUi[0] ?? 'scene'
    ctx.store().setExtractingStage(first)
    ctx.store().setExtractingStages({
      scene: extractTypesForUi.includes('scene'),
      character: extractTypesForUi.includes('character'),
      prop: extractTypesForUi.includes('prop')
    })
    ctx.store().syncExtractUiToCurrentScope()
  }

  /**
   * 新任务提交成功后调用（write-through invalidation）：
   * 任务列表会话缓存可能早于本次提交，若不失效，切 Tab 恢复链路会在缓存里查不到该任务。
   */
  function noteStep3TaskSubmitted() {
    const pid = Number(ctx.store().currentProjectId)
    if (Number.isFinite(pid) && pid > 0) {
      scheduleFlowUserTaskListRefresh(pid, { force: true })
    }
  }

  /** 释放本 task 占用的并发槽（若仍持有），并尝试拉起队列中等待的 outer follow */
  function releaseStep3SseGateSlotAndDrain(taskId: number) {
    if (hasStep3SseSlot(taskId)) {
      releaseStep3SseSlot(taskId)
    }
    drainStep3SseQueue((item) => {
      const meta = ctx.step3TaskMetaById.get()[item.taskId]
      const tab = (meta?.tab || ctx.step3TaskIdToTab.get()[item.taskId] || ctx.activeTab.get()) as TabKey
      if (item.owner === 'outer' && tab !== ctx.activeTab.get()) {
        requeueStep3SseItemToEnd(item)
        return { kind: 'skipped' as const }
      }
      const acq = tryAcquireStep3SseSlot({ taskId: item.taskId, owner: item.owner })
      if (acq.kind !== 'acquired' && acq.kind !== 'already-active') return acq
      void startTrackTask({
        taskId: item.taskId,
        taskType: meta?.taskType ?? null,
        tab,
        assetIds: meta?.assetIds,
        skipGateAcquire: true,
        gateOwner: item.owner
      })
      return acq
    })
  }

  /** 被 modal preempt 时静默挂起外层 SSE，任务进队列等待空位 */
  function suspendStep3OuterSseForPreempt(taskId: number) {
    const tid = Number(taskId)
    if (!Number.isFinite(tid) || tid <= 0) return
    if (!ctx.activeTaskStreamClosers.has(tid)) return
    ctx.step3SseTabSwitchClosing.add(tid)
    try {
      ctx.activeTaskStreamClosers.get(tid)?.()
    } catch {
      /* ignore */
    }
  }

  function finishStep3SseTabSwitchClose(taskId: number) {
    ctx.step3SseTabSwitchClosing.delete(taskId)
    ctx.clearActiveTaskStream(taskId)
  }

  /** Tab 切换断 SSE 后：清理遗留 closer，允许同 taskId 重连 */
  function prepareStep3TaskStreamForResume(taskId: number): boolean {
    if (!ctx.activeTaskStreamClosers.has(taskId)) return true
    if (ctx.step3SseTabSwitchClosing.has(taskId)) {
      /**
       * 先递增世代把旧 follow 置为过期：清掉 closing 标记后旧 follow 的 await 才 settle 时，
       * 不会再把切 Tab 断开误判为失败（否则会 force 打 task/detail 并误清新连接）。
       */
      ctx.step3TaskFollowGeneration.set(
        taskId,
        (ctx.step3TaskFollowGeneration.get(taskId) ?? 0) + 1
      )
      finishStep3SseTabSwitchClose(taskId)
      return true
    }
    return false
  }

  /** 切换 Tab：断开非当前 Tab 的 SSE，保留任务登记与已缓存文案 */
  function pauseStep3SseForInactiveTabs(tab: TabKey) {
    for (const [idStr, taskTab] of Object.entries(ctx.step3TaskIdToTab.get())) {
      const taskId = Number(idStr)
      if (!Number.isFinite(taskId) || taskTab === tab || !ctx.activeTaskStreamClosers.has(taskId)) continue
      ctx.step3SseTabSwitchClosing.add(taskId)
      try {
        ctx.activeTaskStreamClosers.get(taskId)?.()
      } catch {
        /* ignore */
      }
    }
  }

  async function startTrackTask(payload: ScpTrackTaskPayload) {
    if (typeof window === 'undefined') return
    if (ctx.activeTaskStreamClosers.has(payload.taskId)) {
      if (ctx.step3SseTabSwitchClosing.has(payload.taskId)) {
        finishStep3SseTabSwitchClose(payload.taskId)
      } else {
        return
      }
    }
    if (hasLiveTaskSseFollow(payload.taskId)) {
      /** 弹窗 waitUserTaskSseTerminal 仍占槽：禁止外层再开 stream，避免双连 */
      return
    }

    /**
     * 在任何 await 之前同步占坑，杜绝并发 startTrackTask 双开 `/task/stream`。
     * 真正连上 SSE 后会替换为 stream.close；中途提前 return 必须 clear。
     */
    ctx.activeTaskStreamClosers.set(payload.taskId, () => {})

    /** 本次 follow 的世代号；旧 follow 收尾时若已被新世代接管，禁止清理/兜底 */
    const myFollowGeneration = (ctx.step3TaskFollowGeneration.get(payload.taskId) ?? 0) + 1
    ctx.step3TaskFollowGeneration.set(payload.taskId, myFollowGeneration)
    const isStaleStep3Follow = () =>
      ctx.step3TaskFollowGeneration.get(payload.taskId) !== myFollowGeneration

    const ty = normUserTaskType(payload.taskType)
    const scopeKey = ctx.store().step3GenVisualScopeKey()
    const gateOwner: Step3SseSlotOwner = payload.gateOwner === 'modal' ? 'modal' : 'outer'

    if (ty === 'asset_extract') {
      const existingFollowId = ctx.store().getAssetExtractFollowTask(scopeKey)
      const shellStillLive =
        existingFollowId === payload.taskId &&
        ctx.store().isAssetExtractSseLiveForTask(payload.taskId)
      /** follow 仅表示「SSE 仍连接」；本页或壳层 composable 仍有连接时才跳过 */
      if (
        existingFollowId === payload.taskId &&
        (ctx.activeTaskStreamClosers.has(payload.taskId) || shellStillLive)
      ) {
        ctx.clearActiveTaskStream(payload.taskId)
        await applyAssetExtractUiForTask(payload.taskId)
        return
      }
      if (existingFollowId === payload.taskId) {
        ctx.store().setAssetExtractFollowTask(scopeKey, null)
      }
    }

    if (!ctx.activeTrackedTaskIds.get().includes(payload.taskId)) {
      ctx.activeTrackedTaskIds.set([...ctx.activeTrackedTaskIds.get(), payload.taskId])
    }
    const sessionAtStart = ctx.taskFollowSession
    const trackTab = payload.tab ?? ctx.activeTab.get()
    if (ty !== 'asset_extract') {
      ctx.registerStep3TrackedTaskTab(payload.taskId, trackTab, payload.taskType, payload.assetIds)
      if (isFormImageOrCardUserTaskType(payload.taskType) || isFormImageUserTaskType(payload.taskType)) {
        await ctx.ensureStep3FormImageTaskRegistered({
          taskId: payload.taskId,
          tab: trackTab,
          taskType: payload.taskType ?? null
        })
        // registry 异步补齐后立刻回写卡片 loading（restore 里 reapply 可能早于本 await）
        ctx.reapplyFormImageGeneratingSlotsFromActiveIds(trackTab)
      }
      if (trackTab !== ctx.activeTab.get()) {
        ctx.clearActiveTaskStream(payload.taskId)
        if (payload.skipGateAcquire && hasStep3SseSlot(payload.taskId)) {
          releaseStep3SseGateSlotAndDrain(payload.taskId)
        }
        return
      }
    }

    // 并发闸门：满 6 则入队，保留 loading/登记，不连 SSE
    if (!payload.skipGateAcquire) {
      const gate = tryAcquireStep3SseSlot({
        taskId: payload.taskId,
        owner: gateOwner,
        allowPreemptOuter: gateOwner === 'modal'
      })
      if (gate.kind === 'enqueued' || gate.kind === 'rejected') {
        ctx.clearActiveTaskStream(payload.taskId)
        return
      }
      if (gate.kind === 'preempt') {
        suspendStep3OuterSseForPreempt(gate.releaseTaskId)
      }
    }

    // 根据任务类型做基础 UI 状态恢复
    if (ty === 'asset_extract') {
      ctx.store().setAssetExtractFollowTask(scopeKey, payload.taskId)
      await applyAssetExtractUiForTask(payload.taskId)
    } else if (isStep3FormRelatedTaskType(payload.taskType)) {
      ctx.store().beginStep3FormImageTaskFollow(payload.taskId)
    }

    let streamConnected = false
    let res: UserTaskSseOutcome | undefined
    let didFinalizeStep3Task = false
    /** 切 Tab 主动断开：保留登记，切回时由 resume 重连 */
    let endedByTabSwitch = false
    /**
     * 断连但任务仍在服务端进行（良性断线）：与切 Tab 同样按「挂起」收尾——
     * 保留登记与 follow、不 markUserTaskLocallyTerminal，否则恢复链路会被永久掐死。
     */
    let suspendedForReconnect = false
    try {
      const stream = createTaskStream(payload.taskId)
      ctx.activeTaskStreamClosers.set(payload.taskId, () => stream.close())
      if (isStep3FormRelatedTaskType(payload.taskType) && !payload.skipPreSseHydrate) {
        if (isStep3FormGenerateTaskType(payload.taskType) && payload.assetIds?.length) {
          for (const aid of payload.assetIds) {
            ctx.applyAssetIdToPendingFormTextGeneratingBusy(aid)
          }
        } else {
          await ctx.hydrateStep3GeneratingFromTaskId(payload.taskId)
        }
      }
      const stopWatch = stream.subscribeProgress((p) => {
        if (sessionAtStart !== ctx.taskFollowSession) return
        if (isStaleStep3Follow()) return
        if (!p) return
        if (ty === 'asset_extract') {
          const msgText = String(p.message || '').trim()
          const titleText = String(p.stepTitle || '').trim()
          const { stepIndex, stepTotal } = resolveStepIndexTotalFromSse(p)
          ctx.store().setExtractingTaskProgress({
            percent:
              typeof p.progress === 'number'
                ? p.progress
                : ctx.store().extractingTaskProgress.percent,
            stepTitle:
              titleText ||
              msgText ||
              ctx.store().extractingTaskProgress.stepTitle ||
              '任务进行中',
            message: msgText || titleText,
            stepIndex,
            stepTotal
          })
          ctx.store().setExtractingAssets(true)
          ctx.store().syncExtractUiToCurrentScope()
          const stage = inferExtractAssetTabFromSse({
            stage: p.stage,
            stepTitle: p.stepTitle || '',
            message: p.message || ''
          })
          if (stage) {
            ctx.store().setExtractingStage(stage)
            if (ctx.shouldFollowExtractStageForActiveTab()) {
              ctx.activeTab.set(stage)
            } else if (ctx.singleExtractTabLock) {
              ctx.activeTab.set(ctx.singleExtractTabLock)
            }
          }
          return
        }
        ctx.setStep3TabTaskProgress(trackTab, ctx.buildStep3TabProgressFromSse(trackTab, p))
      })
      let streamRes: UserTaskSseOutcome
      try {
        if (isFormImageOrCardUserTaskType(payload.taskType)) {
          streamRes = await raceFormImageSseOrPollTaskDone(payload.taskId, stream, {
            skipDetailFallback: () =>
              isStaleStep3Follow() || ctx.step3SseTabSwitchClosing.has(payload.taskId)
          })
        } else {
          streamRes = normalizeTaskStreamToUserOutcome(await stream.done)
        }
      } finally {
        streamConnected = stream.isConnected()
        stopWatch()
        if (!isFormImageOrCardUserTaskType(payload.taskType)) {
          try {
            stream.close()
          } catch {
            /* ignore */
          }
        }
      }
      if (isStaleStep3Follow()) {
        /** 新世代 follow 已接管本任务：旧 follow 直接退出，收尾交给新 follow */
        return
      }
      endedByTabSwitch = ctx.step3SseTabSwitchClosing.has(payload.taskId)
      if (endedByTabSwitch) {
        /** 切 Tab 主动断 SSE：不得走 error/detail 兜底，交给 finally 保留任务登记以便回来重连 */
        finishStep3SseTabSwitchClose(payload.taskId)
        return
      }
      res = streamRes
      if (
        res.type === 'error' &&
        (await ctx.shouldDeferStep3TaskFailureForBenignDisconnect(payload.taskId, res.errorMessage || ''))
      ) {
        suspendedForReconnect = true
        return
      }
      const appliedOutcome = await applyScpTaskFollowOutcome({
        ctx,
        payload,
        res,
        ty,
        sessionAtStart
      })
      didFinalizeStep3Task = appliedOutcome.didFinalizeStep3Task
      suspendedForReconnect = appliedOutcome.suspendedForReconnect
      return
    } catch (e: unknown) {
      const caughtOutcome = await handleScpTaskFollowError({
        ctx,
        payload,
        error: e,
        ty,
        sessionAtStart,
        streamConnected,
        didFinalizeStep3Task,
        isStaleStep3Follow,
        finishStep3SseTabSwitchClose
      })
      didFinalizeStep3Task = caughtOutcome.didFinalizeStep3Task
      suspendedForReconnect = caughtOutcome.suspendedForReconnect
      endedByTabSwitch = caughtOutcome.endedByTabSwitch
      return
    } finally {
      if (isStaleStep3Follow()) {
        /** 已被新世代 follow 接管：不 end follow、不注销登记、不关流（closer 属于新 SSE） */
      } else if ((endedByTabSwitch || suspendedForReconnect) && !didFinalizeStep3Task) {
        /**
         * 挂起收尾（切 Tab 主动断开 / 良性断线待重连）：
         * 仅暂停跟进度，不 end follow：形态文案无 generating map，靠计数保流程条 loading；
         * 切回重连时 begin(taskId) 幂等，不会叠高计数。
         * 必须清掉本次已死的 closer，否则恢复逻辑会误判「SSE 仍活着」而跳过重连。
         *
         * 若断连窗口内任务已终态：必须 endFollow + 清 modal 快照，否则流程条假死。
         */
        ctx.clearActiveTaskStream(payload.taskId)
        /** 让出并发槽给当前 Tab / 队列任务；本任务登记保留，切回再 acquire */
        releaseStep3SseGateSlotAndDrain(payload.taskId)
        void (async () => {
          await settleStep3FlowLoadingState(ctx.store(), ctx.route())
          try {
            if (await isUserTaskTerminal(payload.taskId)) {
              if (isStep3FormRelatedTaskType(payload.taskType)) {
                ctx.store().endStep3FormImageTaskFollow(payload.taskId)
              }
              ctx.unregisterStep3TrackedTaskTab(payload.taskId)
              ctx.store().refreshStep3VisualGeneratingFlag()
            }
          } catch {
            /* ignore */
          }
        })()
        ctx.notifyStep3FormImageTaskDoneFromTrack({
          taskId: payload.taskId,
          taskType: payload.taskType,
          didFinalizeStep3Task,
          res,
          wasTabSwitchClose: true
        })
      } else {
        const wasTabSwitchClose = endedByTabSwitch || suspendedForReconnect
        if (isStep3FormRelatedTaskType(payload.taskType)) {
          ctx.store().endStep3FormImageTaskFollow(payload.taskId)
        }

        if (
          !didFinalizeStep3Task &&
          isStep3FormRelatedTaskType(payload.taskType) &&
          ctx.isStep3TerminalSseOutcome(res)
        ) {
          ctx.settleStep3TaskFlowLoadingOnTerminalSse(trackTab, payload.taskType, res)
        }

        if (sessionAtStart !== ctx.taskFollowSession) {
          if (ty === 'asset_extract') {
            ctx.store().setAssetExtractFollowTask(scopeKey, null)
          } else {
            ctx.unregisterStep3TrackedTaskTab(payload.taskId)
          }
          ctx.removeTaskIdFromOngoingList(payload.taskId)
          ctx.clearActiveTaskStream(payload.taskId)
          releaseStep3SseGateSlotAndDrain(payload.taskId)
          ctx.notifyGlobalGenerateTaskListUpdated()
          void settleStep3FlowLoadingState(ctx.store(), ctx.route())
          ctx.notifyStep3FormImageTaskDoneFromTrack({
            taskId: payload.taskId,
            taskType: payload.taskType,
            didFinalizeStep3Task,
            res,
            wasTabSwitchClose: true
          })
        } else {
          if (ty === 'asset_extract') {
            ctx.store().setAssetExtractFollowTask(scopeKey, null)
            ctx.store().finishAssetExtractUiForCurrentScope()
          } else {
            ctx.unregisterStep3TrackedTaskTab(payload.taskId)
          }
          ctx.removeTaskIdFromOngoingList(payload.taskId)
          ctx.clearActiveTaskStream(payload.taskId)
          releaseStep3SseGateSlotAndDrain(payload.taskId)
          ctx.notifyGlobalGenerateTaskListUpdated(
            didFinalizeStep3Task || ctx.isStep3TerminalSseOutcome(res) ? payload.taskId : undefined
          )
          void settleStep3FlowLoadingState(ctx.store(), ctx.route())
          ctx.notifyStep3FormImageTaskDoneFromTrack({
            taskId: payload.taskId,
            taskType: payload.taskType,
            didFinalizeStep3Task,
            res,
            wasTabSwitchClose
          })
        }
      }
    }
  }

  return {
    applyAssetExtractUiForTask,
    noteStep3TaskSubmitted,
    releaseStep3SseGateSlotAndDrain,
    suspendStep3OuterSseForPreempt,
    finishStep3SseTabSwitchClose,
    prepareStep3TaskStreamForResume,
    pauseStep3SseForInactiveTabs,
    startTrackTask
  }
}
