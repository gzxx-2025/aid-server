'use client'

import { message } from 'antd'
import { applyStep3GenVisualFromRoute,waitForCreationStoreHydrated } from '~/hooks/useCreationStoreHydration'
import {
ackCreateFlowTaskCommand,
consumePendingCreateFlowTaskCommand
} from '~/utils/createFlowTaskCommand'
import { clearStep3FormImageTaskRegistry,rejectAllStep3FormImageTaskWaiters } from '~/utils/step3FormImageTaskRegistry'
import { clearStep3SseConcurrencyGate } from '~/utils/step3SseConcurrencyGate'
import { resumeUserTask } from '~/utils/taskPartialFailed'
import {
acceptsStep3TrackCommand,
isStoryboardScriptBatchTaskType,
normUserTaskType
} from './scpTaskUtils'
import { SCP_ACTIVE_TAB_SESSION_PREFIX,type ScpCtx,type TabKey } from './types'

export interface ScpBootstrapApi {
  /** 切换作品/剧集时断开上一条任务的 SSE，避免旧任务回调继续改 store 或与新作品恢复逻辑打架 */
  stopOngoingTaskStreamForRouteContextChange: () => void
  runProjectAssetBootstrap: (epoch: number) => Promise<void>
  scheduleProjectAssetBootstrap: () => void
  handleTrackTaskFromGlobal: (payload?: { taskId?: number; taskType?: string | null }) => void
  handleStopTaskFromGlobal: (payload?: { taskId?: number; silent?: boolean }) => void
  handleTrackTaskEvent: (event: Event) => void
  handleResumeTaskFromGlobal: (payload?: { taskId?: number; taskType?: string | null }) => Promise<void>
  handleResumeTaskEvent: (event: Event) => void
  /**
   * 全局任务面板先跳步骤再派发指令；跳步瞬间本页尚未挂载、事件已错过。
   * 挂载完成后补投属于本页的 pending 指令（续生仅智能提取，跟进为第三步相关任务）。
   */
  deliverPendingCreateFlowTaskCommands: () => void
  handleStopTaskEvent: (event: Event) => void
}

export function useScpBootstrap(ctx: ScpCtx): ScpBootstrapApi {
  /** 切换作品/剧集时断开上一条任务的 SSE，避免旧任务回调继续改 store 或与新作品恢复逻辑打架 */
  function stopOngoingTaskStreamForRouteContextChange() {
    ctx.taskFollowSession++
    ctx.restoreTasksGeneration += 1
    ctx.restoreAndTrackRequestId += 1
    rejectAllStep3FormImageTaskWaiters()
    clearStep3FormImageTaskRegistry()
    ctx.clearActiveTaskStream()
    clearStep3SseConcurrencyGate()
    ctx.pendingFormGenBusy.set({})
    ctx.settingCardGenBusyByImageId.set({})
    ctx.clearStep3ActiveFormImageGeneratingIds()
    ctx.step3TaskIdToTab.set({})
    ctx.step3TaskMetaById.set({})
    ctx.step3SseTabSwitchClosing.clear()
    ctx.clearStep3TabTaskProgress()
  }

  async function runProjectAssetBootstrap(epoch: number) {
    ctx.step3AssetBootstrapReady.set(false)
    ctx.store().setStep3AssetListSyncReady(false)
    try {
      await waitForCreationStoreHydrated(ctx.store(), ctx.route())
      if (epoch !== ctx.projectAssetBootstrapEpoch) return

      const nextScopeKey = ctx.store().step3GenVisualScopeKey()
      const cachedExtractUi = ctx.store().extractUiByScope[nextScopeKey]
      if (cachedExtractUi?.isExtractingAssets && !ctx.store().isExtractingAssets) {
        ctx.store().applyExtractUiFromScopeKey(nextScopeKey)
      }
      /**
       * 切集时 setCurrentProjectContext 已把旧集 generating 同步进旧 scope。
       * 此处若再 write 当前（已是新集/空）flats 到 lastScope，会冲掉旧集卡片 loading。
       * optional model codes 同样已在 context switch 时 persist，无需重复写空 gen maps。
       */

      const scopeChanged =
        ctx.assetRouteWatchBootstrapped &&
        ctx.lastStep3VisualScopeKey.get() &&
        ctx.lastStep3VisualScopeKey.get() !== nextScopeKey

      // 同作品刷新时 Pinia 仍可能带着旧列表名，先清空展示层，避免 reconcile 前闪完整卡片
      ctx.clearPersonalAssetPayload()
      if (scopeChanged) {
        stopOngoingTaskStreamForRouteContextChange()
        ctx.defaultTextModelCode.set('')
      }
      ctx.assetRouteWatchBootstrapped = true

      ctx.store().applyOptionalModelCodesFromScopeKey(nextScopeKey)
      applyStep3GenVisualFromRoute(ctx.store(), ctx.route())
      ctx.store().refreshStep3VisualGeneratingFlag()
      ctx.sceneGenerationStatus.set({ ...ctx.store().sceneGenerationStatus })
      ctx.characterFormGenerationStatus.set({ ...ctx.store().characterFormGenerationStatus })
      ctx.propFormGenerationStatus.set({ ...ctx.store().propFormGenerationStatus })

      if (epoch !== ctx.projectAssetBootstrapEpoch) return

      const pid = ctx.store().currentProjectId
      let savedTab: TabKey | null = null
      if (pid != null && Number.isFinite(Number(pid)) && typeof window !== 'undefined') {
        try {
          const raw = window.sessionStorage.getItem(`${SCP_ACTIVE_TAB_SESSION_PREFIX}${Number(pid)}`)
          if (raw === 'scene' || raw === 'character' || raw === 'prop') savedTab = raw
        } catch {
          /* ignore */
        }
      }

      // 刷新后优先恢复用户停留的 Tab，且首屏请求必须与当前 Tab 一致
      const displayTab: TabKey = savedTab ?? ctx.activeTab.get()
      if (displayTab !== ctx.activeTab.get()) {
        ctx.suppressActiveTabAssetLoadOnce = true
        ctx.activeTab.set(displayTab)
      }

      const hasOngoingVisualWork = ctx.hasOngoingStep3VisualWork()
      const backgroundTabs = hasOngoingVisualWork
        ? ctx.resolveBackgroundTabsWithStep3OngoingWork(displayTab)
        : []

      await ctx.loadPersonalAssetsForTab(displayTab)
      if (epoch !== ctx.projectAssetBootstrapEpoch) return

      for (const tab of backgroundTabs) {
        await ctx.loadPersonalAssetsForTab(tab, { background: true })
        if (epoch !== ctx.projectAssetBootstrapEpoch) return
      }
      await ctx.restoreAndTrackOngoingTasks()

      if (epoch !== ctx.projectAssetBootstrapEpoch) return
      ctx.lastStep3VisualScopeKey.set(nextScopeKey)
    } finally {
      if (epoch === ctx.projectAssetBootstrapEpoch) {
        ctx.step3AssetBootstrapReady.set(true)
        ctx.store().setStep3AssetListSyncReady(true)
      }
    }
  }

  function scheduleProjectAssetBootstrap() {
    if (!ctx.assetPageMounted) return
    ctx.projectAssetBootstrapEpoch++
    const epoch = ctx.projectAssetBootstrapEpoch
    if (ctx.projectAssetBootstrapDebounceTimer) clearTimeout(ctx.projectAssetBootstrapDebounceTimer)
    ctx.projectAssetBootstrapDebounceTimer = setTimeout(() => {
      ctx.projectAssetBootstrapDebounceTimer = null
      if (!ctx.assetPageMounted || epoch !== ctx.projectAssetBootstrapEpoch) return
      void runProjectAssetBootstrap(epoch)
    }, 48)
  }

  function handleTrackTaskFromGlobal(payload?: { taskId?: number; taskType?: string | null }) {
    if (isStoryboardScriptBatchTaskType(payload?.taskType)) return
    const taskId = Number(payload?.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) return
    if (acceptsStep3TrackCommand(payload)) {
      ackCreateFlowTaskCommand('track', taskId)
    }
    void ctx.startTrackTask({ taskId, taskType: payload?.taskType ?? null, tab: ctx.activeTab.get() })
  }

  function handleStopTaskFromGlobal(payload?: { taskId?: number; silent?: boolean }) {
    const taskId = Number(payload?.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) return
    const tracked =
      ctx.activeTaskStreamClosers.has(taskId) ||
      ctx.activeTrackedTaskIds.get().includes(taskId) ||
      ctx.step3TaskIdToTab.get()[taskId] != null
    if (!tracked) return
    ctx.taskFollowSession++
    const scopeKey = ctx.store().step3GenVisualScopeKey()
    if (ctx.store().getAssetExtractFollowTask(scopeKey) === taskId) {
      ctx.store().finishAssetExtractUiForCurrentScope()
    } else {
      ctx.unregisterStep3TrackedTaskTab(taskId)
    }
    if (
      ctx.activeTaskStreamClosers.has(taskId) ||
      ctx.store().step3FormImageTaskFollowTaskIds.includes(taskId)
    ) {
      ctx.store().endStep3FormImageTaskFollow(taskId)
    }
    ctx.clearActiveTaskStream(taskId)
    ctx.activeTrackedTaskIds.set(ctx.activeTrackedTaskIds.get().filter((id) => id !== taskId))
    if (!payload?.silent) {
      message.info('已停止本页任务进度展示')
    }
  }

  function handleTrackTaskEvent(event: Event) {
    const customEvent = event as CustomEvent<{ taskId?: number; taskType?: string | null }>
    handleTrackTaskFromGlobal(customEvent.detail)
  }

  async function handleResumeTaskFromGlobal(payload?: { taskId?: number; taskType?: string | null }) {
    if (isStoryboardScriptBatchTaskType(payload?.taskType)) return
    const taskId = Number(payload?.taskId)
    if (!Number.isFinite(taskId) || taskId <= 0) return
    const ty = normUserTaskType(payload?.taskType)
    if (ty !== 'asset_extract') return
    ackCreateFlowTaskCommand('resume', taskId)
    try {
      await resumeUserTask(taskId, ty)
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
      }
      await ctx.startTrackTask({ taskId, taskType: payload?.taskType ?? null, tab: ctx.activeTab.get() })
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '续生失败')
    }
  }

  function handleResumeTaskEvent(event: Event) {
    const customEvent = event as CustomEvent<{ taskId?: number; taskType?: string | null }>
    void handleResumeTaskFromGlobal(customEvent.detail)
  }

  /**
   * 全局任务面板先跳步骤再派发指令；跳步瞬间本页尚未挂载、事件已错过。
   * 挂载完成后补投属于本页的 pending 指令（续生仅智能提取，跟进为第三步相关任务）。
   */
  function deliverPendingCreateFlowTaskCommands() {
    const resume = consumePendingCreateFlowTaskCommand(
      'resume',
      (d) => normUserTaskType(d.taskType) === 'asset_extract'
    )
    if (resume) {
      void handleResumeTaskFromGlobal(resume)
    }
    const track = consumePendingCreateFlowTaskCommand('track', (d) => acceptsStep3TrackCommand(d))
    if (track) {
      handleTrackTaskFromGlobal(track)
    }
  }

  function handleStopTaskEvent(event: Event) {
    const customEvent = event as CustomEvent<{ taskId?: number }>
    handleStopTaskFromGlobal(customEvent.detail)
  }

  return {
    stopOngoingTaskStreamForRouteContextChange,
    runProjectAssetBootstrap,
    scheduleProjectAssetBootstrap,
    handleTrackTaskFromGlobal,
    handleStopTaskFromGlobal,
    handleTrackTaskEvent,
    handleResumeTaskFromGlobal,
    handleResumeTaskEvent,
    deliverPendingCreateFlowTaskCommands,
    handleStopTaskEvent
  }
}
