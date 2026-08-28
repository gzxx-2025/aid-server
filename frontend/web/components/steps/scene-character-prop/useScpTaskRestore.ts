'use client'

import { hasLiveTaskSseFollow, isTerminalTaskStatus } from '~/hooks/useTaskSseFollow'
import { isUserTaskLocallyTerminal } from '~/hooks/useTaskOngoing'
import { routePathToCreationStep } from '~/utils/createFlowRoutes'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { purgeTerminalStep3ModalSseTasks } from '~/utils/step3LiveGenRestore'
import {
  isFormImageAutoUseTaskType,
} from '~/utils/formImageAutoUse'
import { hasOngoingStep3FormImageTasks } from '~/utils/step3FormImageTaskRegistry'
import {
  parseBareFormSlotKey,
  parseFormEditorScopeKey
} from '~/utils/step3FormEditorScopeKey'
import { shouldOuterStartStep3TaskFollow } from '~/utils/sceneModalOuterFollowHandoff'
import {
  beginFlowTaskListQuietWindow,
  endFlowTaskListQuietWindow
} from '~/utils/userTaskListFlowOnce'
import type { UserTaskRow } from '~/types/business-api'
import type { SceneGenerationStatus } from '~/stores/creation'
import {
  isFormImageUserTaskType,
  isOngoingUserTaskStatus,
  isStep3FormGenerateTaskType,
  isStep3FormRelatedTaskType,
  normUserTaskType,
  parseTaskId
} from './scpTaskUtils'
import type { ScpCtx, TabKey } from './types'
import { createScpTaskListOps } from './scpTaskListOps'
import { createScpTaskTerminalOps } from './scpTaskTerminalOps'

export interface ScpTaskRestoreApi {
  getRecentStep3TaskRow: (taskId: number) => UserTaskRow | undefined
  /**
   * Tab 切换或懒加载列表后：补登记此前因 formId 映射未就绪而跳过的进行中任务。
   * 仅依赖 list 行（含 inputSnapshot），不再对每个 taskId 打 /task/detail。
   */
  registerUnresolvedStep3OngoingTasksForTab: (
    tab: TabKey,
    options?: { skipReload?: boolean }
  ) => Promise<void>
  /** 切换 Tab：为当前 Tab 上登记但未连接 SSE 的进行中任务重连（已终态则用 list 行 finalize） */
  resumeStep3SseForActiveTab: (tab: TabKey, options?: { skipReload?: boolean }) => Promise<void>
  /** SSE 断开期间任务已在服务端终态：补拉详情 finalize，避免 loading 卡住或重复调 set main */
  finalizeTerminalStep3TaskFromDetail: (
    taskId: number,
    tab: TabKey,
    detail: {
      status?: string | null
      taskType?: string | null
      resultData?: unknown
      errorMessage?: string | null
    }
  ) => Promise<boolean>
  /** 用最近一次 list 行清理已终态任务，禁止逐条 /task/detail */
  finalizeTerminalStep3TasksForTab: (tab: TabKey) => Promise<void>
  /** 当前 Tab 仍有 generating 但无 SSE 时，用已缓存 list 再重连（不再打 list/detail） */
  ensureStep3TabGeneratingTasksFollowed: (tab: TabKey) => Promise<void>
  /** 释放当前 Tab 上已无进行中任务/SSE 的残留 follow（切走期间任务已终态时防流程条卡住） */
  releaseStaleStep3FormImageTaskFollowsForTab: (tab: TabKey) => void
  syncStep3SseFollowForActiveTab: (tab: TabKey) => Promise<void>
  /**
   * 无进行中的第三步生成/提取且本地无 generating 卡片时，跳过后端 /task/list（省流量）。
   * 注意：仅进行中的「智能提取」asset_extract 在刷新后不会把 isExtractingAssets 写回内存，
   * 故「恢复跟进」路径必须传 force，否则会误判空闲而不拉列表，导致无法重连 SSE。
   */
  shouldFetchOngoingUserTaskList: () => boolean
  /**
   * @param options.force 必须尝试拉 list（绕过「无进行中则跳过」门闸）；网络默认 read 复用权威缓存
   * @param options.mutate 仅提交后写穿等场景才清 burst 重拉
   * @returns 是否成功拉取任务列表（失败时不应据此清除 scope 恢复的提取 loading）
   */
  reloadOngoingTasks: (options?: { force?: boolean; mutate?: boolean }) => Promise<boolean>
  /**
   * 刷新后：根据仍在排队/处理中的形态图/形态文案任务恢复 UI。
   * - 形态图（form_image*）：恢复主列表图片 generating
   * - 形态文案（form_generate*）：仅恢复待生成形态小卡片 pendingFormGenBusy
   * 返回值：任务覆盖到的 formId / assetId 集合，用于清除 Pinia 持久化遗留的「假 generating」。
   */
  reconcileOngoingStep3TasksToUi: () => Promise<{
    coverFormIds: Set<number>
    coverAssetIds: Set<number>
    coverImageIds: Set<number>
  }>
  /** @deprecated 兼容旧调用 */
  reconcileOngoingFormImageTasksToStep3Ui: () => Promise<Set<number>>
  /** 刷新恢复 generating 后，供 SSE 结束拉列表：优先有生成中卡片的 Tab，避免仍用默认 scene */
  resolveTabKeyForStep3OngoingRestore: () => TabKey
  /** 刷新后需后台预加载的 Tab（排除用户当前停留 Tab），用于恢复其它 Tab 的 generating 卡片 */
  resolveBackgroundTabsWithStep3OngoingWork: (displayTab: TabKey) => TabKey[]
  restoreAndTrackOngoingTasks: () => Promise<void>
}

export function useScpTaskRestore(ctx: ScpCtx): ScpTaskRestoreApi {
  const taskListOps = createScpTaskListOps(ctx)
  const {
    getRecentStep3TaskRow,
    shouldFetchOngoingUserTaskList,
    reloadOngoingTasks,
    reconcileOngoingStep3TasksToUi,
    reconcileOngoingFormImageTasksToStep3Ui
  } = taskListOps
  const { finalizeTerminalStep3TaskFromDetail, finalizeTerminalStep3TasksForTab } =
    createScpTaskTerminalOps(ctx, getRecentStep3TaskRow)

  /**
   * Tab 切换或懒加载列表后：补登记此前因 formId 映射未就绪而跳过的进行中任务。
   * 仅依赖 list 行（含 inputSnapshot），不再对每个 taskId 打 /task/detail。
   */
  async function registerUnresolvedStep3OngoingTasksForTab(
    tab: TabKey,
    options?: { skipReload?: boolean }
  ) {
    if (typeof window === 'undefined') return
    if (routePathToCreationStep(ctx.route().path) !== 'scene-character') return
    if (!options?.skipReload) {
      /** 复用壳层 bootstrap 权威 list；force 仅表示必须拉（绕过 idle 门闸），不 mutate 清缓存 */
      await reloadOngoingTasks({ force: true })
    }
    for (const t of ctx.ongoingTasks.get()) {
      if (!t || !isStep3FormRelatedTaskType(t.taskType) || !isOngoingUserTaskStatus(t.status)) continue
      const tid = parseTaskId(t.id)
      if (tid == null) continue
      if (ctx.store().taskIdsWithLocalFollowPaused.includes(tid)) continue
      if (isUserTaskLocallyTerminal(tid)) {
        ctx.removeTaskIdFromOngoingList(tid)
        continue
      }
      if (ctx.step3TaskIdToTab.get()[tid]) continue
      /** list 行已含 inputSnapshot，勿再拉 detail */
      const resolved = ctx.resolveTabKeyForStep3TaskSnapshot(t)
      if (resolved == null || resolved !== tab) continue
      ctx.registerStep3OngoingTaskMeta(tid, resolved, t.taskType ?? null)
    }
  }

  /** 切换 Tab：为当前 Tab 上登记但未连接 SSE 的进行中任务重连（已终态则用 list 行 finalize） */
  async function resumeStep3SseForActiveTab(tab: TabKey, options?: { skipReload?: boolean }) {
    await registerUnresolvedStep3OngoingTasksForTab(tab, options)
    for (const [idStr, taskTab] of Object.entries(ctx.step3TaskIdToTab.get())) {
      const taskId = Number(idStr)
      if (!Number.isFinite(taskId) || taskTab !== tab) continue
      if (!ctx.prepareStep3TaskStreamForResume(taskId)) continue

      /**
       * 终态判定只认正面证据（list 行终态 / 本地已标终态）。
       * 缓存 list 里查不到（新任务尚未入缓存）视为进行中，交给 SSE 重连自证。
       */
      const row = getRecentStep3TaskRow(taskId)
      const stillOngoing =
        !isUserTaskLocallyTerminal(taskId) && (!row || isOngoingUserTaskStatus(row.status))

      if (!stillOngoing) {
        if (row && (isTerminalTaskStatus(row.status) || !isOngoingUserTaskStatus(row.status))) {
          await finalizeTerminalStep3TaskFromDetail(taskId, tab, row)
        } else {
          ctx.unregisterStep3TrackedTaskTab(taskId)
          ctx.removeTaskIdFromOngoingList(taskId)
          ctx.store().endStep3FormImageTaskFollow(taskId)
        }
        continue
      }

      const meta = ctx.step3TaskMetaById.get()[taskId]
      if (isFormImageUserTaskType(meta?.taskType) && row && isOngoingUserTaskStatus(row.status)) {
        ctx.applyStep3GeneratingFromTaskDetail(row)
      }
      void ctx.startTrackTask({
        taskId,
        taskType: meta?.taskType ?? row?.taskType ?? null,
        tab,
        skipPreSseHydrate: true
      })
    }
  }

  /** 当前 Tab 仍有 generating 但无 SSE 时，用已缓存 list 再重连（不再打 list/detail） */
  async function ensureStep3TabGeneratingTasksFollowed(tab: TabKey) {
    ctx.reapplyFormImageGeneratingSlotsFromActiveIds(tab)
    if (ctx.tabHasLiveStep3TaskSse(tab)) return
    const needsFollow =
      ctx.tabHasStep3GeneratingSlots(tab) ||
      ctx.tabHasStep3FormImageGenerating(tab) ||
      Object.values(ctx.step3TaskIdToTab.get()).includes(tab) ||
      ctx.hasUnresolvedOngoingStep3Tasks()
    if (!needsFollow) return
    await resumeStep3SseForActiveTab(tab, { skipReload: true })
  }

  /** 释放当前 Tab 上已无进行中任务/SSE 的残留 follow（切走期间任务已终态时防流程条卡住） */
  function releaseStaleStep3FormImageTaskFollowsForTab(tab: TabKey) {
    for (const tid of [...ctx.store().step3FormImageTaskFollowTaskIds]) {
      const taskTab = ctx.step3TaskIdToTab.get()[tid]
      if (taskTab != null && taskTab !== tab) continue
      if (ctx.activeTaskStreamClosers.has(tid)) continue
      const row = getRecentStep3TaskRow(tid)
      if (row && isOngoingUserTaskStatus(row.status) && !isUserTaskLocallyTerminal(tid)) continue
      if (
        !row &&
        ctx.ongoingTasks.get().some(
          (t) => Number(t.id) === tid && isOngoingUserTaskStatus(t.status) && !isUserTaskLocallyTerminal(tid)
        )
      ) {
        continue
      }
      ctx.store().endStep3FormImageTaskFollow(tid)
    }
  }

  async function syncStep3SseFollowForActiveTab(tab: TabKey) {
    if (ctx.props().isExtracting) return
    ctx.pauseStep3SseForInactiveTabs(tab)
    ctx.reapplyFormImageGeneratingSlotsFromActiveIds(tab)

    const needsFollow =
      ctx.tabHasStep3GeneratingSlots(tab) ||
      ctx.tabHasStep3FormImageGenerating(tab) ||
      Object.values(ctx.step3TaskIdToTab.get()).includes(tab) ||
      ctx.hasUnresolvedOngoingStep3Tasks()

    /**
     * 切到已完成的场景/道具等：只断开其它 Tab SSE，不打 /task/list、/task/detail。
     * 仅当前 Tab 仍有进行中任务/generating 槽位时才用缓存 list 重连。
     */
    if (!needsFollow) {
      releaseStaleStep3FormImageTaskFollowsForTab(tab)
      ctx.store().refreshStep3VisualGeneratingFlag()
      return
    }

    const hasListCache =
      ctx.recentStep3TaskRows.get().length > 0 || ctx.ongoingTasks.get().length > 0
    /** 有会话缓存则不 force，避免每次切 Tab 刷屏 list */
    await reloadOngoingTasks({ force: !hasListCache })
    await finalizeTerminalStep3TasksForTab(tab)
    await resumeStep3SseForActiveTab(tab, { skipReload: true })
    await ensureStep3TabGeneratingTasksFollowed(tab)
    ctx.reapplyFormImageGeneratingSlotsFromActiveIds(tab)
    releaseStaleStep3FormImageTaskFollowsForTab(tab)
    ctx.store().refreshStep3VisualGeneratingFlag()
  }

  /** 刷新恢复 generating 后，供 SSE 结束拉列表：优先有生成中卡片的 Tab，避免仍用默认 scene */
  function resolveTabKeyForStep3OngoingRestore(): TabKey {
    if (Object.values(ctx.propFormGenerationStatus.get()).some((s) => s === 'generating')) return 'prop'
    if (Object.values(ctx.characterFormGenerationStatus.get()).some((s) => s === 'generating'))
      return 'character'
    if (Object.values(ctx.sceneGenerationStatus.get()).some((s) => s === 'generating')) return 'scene'
    return ctx.activeTab.get()
  }

  /** 刷新后需后台预加载的 Tab（排除用户当前停留 Tab），用于恢复其它 Tab 的 generating 卡片 */
  function resolveBackgroundTabsWithStep3OngoingWork(displayTab: TabKey): TabKey[] {
    const tabs = new Set<TabKey>()
    const addGeneratingTabs = (
      scene: Record<string | number, SceneGenerationStatus>,
      character: Record<string, SceneGenerationStatus>,
      prop: Record<string, SceneGenerationStatus>
    ) => {
      if (Object.values(scene).some((s) => s === 'generating')) tabs.add('scene')
      if (Object.values(character).some((s) => s === 'generating')) tabs.add('character')
      if (Object.values(prop).some((s) => s === 'generating')) tabs.add('prop')
    }
    addGeneratingTabs(
      ctx.sceneGenerationStatus.get(),
      ctx.characterFormGenerationStatus.get(),
      ctx.propFormGenerationStatus.get()
    )
    addGeneratingTabs(
      ctx.store().sceneGenerationStatus,
      ctx.store().characterFormGenerationStatus,
      ctx.store().propFormGenerationStatus
    )
    for (const blob of Object.values(ctx.store().step3GenVisualByScope || {})) {
      addGeneratingTabs(blob?.scene || {}, blob?.character || {}, blob?.prop || {})
      for (const snap of Object.values(blob?.modalSseTasks || {})) {
        const scopeKey = String(snap?.editorScopeKey || '').trim()
        if (/^scene-\d+$/i.test(scopeKey)) tabs.add('scene')
        else if (parseFormEditorScopeKey(scopeKey)?.tab === 'character') tabs.add('character')
        else if (parseFormEditorScopeKey(scopeKey)?.tab === 'prop') tabs.add('prop')
        else if (/^character-\d+$/i.test(scopeKey)) tabs.add('character')
        else if (/^prop-\d+$/i.test(scopeKey)) tabs.add('prop')
        else if (/^\d+-\d+$/.test(scopeKey)) {
          const bare = parseBareFormSlotKey(scopeKey)
          const ci = bare?.assetIndex
          const propOnly =
            ci != null &&
            ctx.propFormIdsByIndex.get()[ci]?.length &&
            !ctx.characterFormIdsByIndex.get()[ci]?.length
          if (propOnly) tabs.add('prop')
          else if (
            ci != null &&
            ctx.characterFormIdsByIndex.get()[ci]?.length &&
            !ctx.propFormIdsByIndex.get()[ci]?.length
          ) {
            tabs.add('character')
          }
          // 两侧都有：不猜 Tab，避免错误角标
        }
      }
    }
    tabs.delete(displayTab)
    return [...tabs]
  }

  async function restoreAndTrackOngoingTasks() {
    if (typeof window === 'undefined' || !ctx.assetPageMounted) return
    const req = ++ctx.restoreAndTrackRequestId
    if (ctx.restoreAndTrackOngoingTasksInFlight) {
      await ctx.restoreAndTrackOngoingTasksInFlight
    }
    if (!ctx.assetPageMounted || req !== ctx.restoreAndTrackRequestId) return

    const pending = (async () => {
    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    if (!ctx.assetPageMounted || !saveCtx) return

    const gen = ++ctx.restoreTasksGeneration
    beginFlowTaskListQuietWindow(saveCtx.projectId)
    try {
      /** force=绕过 idle 门闸；网络走 read，复用壳层 bootstrap 权威 list，不 mutate 清缓存 */
      const taskListOk = await reloadOngoingTasks({ force: true })
      if (gen !== ctx.restoreTasksGeneration) return
      await purgeTerminalStep3ModalSseTasks(ctx.store(), ctx.route())
      if (gen !== ctx.restoreTasksGeneration) return
      ctx.purgeNonOngoingModalSseTasksFromStore()
      ctx.syncGeneratingFromPersistedModalSseTasks()
      const cover = await reconcileOngoingStep3TasksToUi()
      if (gen !== ctx.restoreTasksGeneration) return
      ctx.clearStalePersistedGeneratingWithoutOngoingStep3Cover(cover)
      if (gen !== ctx.restoreTasksGeneration) return
      ctx.purgeStaleStep3FormImageGeneratingMarks(cover.coverFormIds)
      ctx.reconcileStep3GeneratingWithLoadedImages()
      if (gen !== ctx.restoreTasksGeneration) return
      await ctx.reconcileSettingCardGeneratingUiForOngoingTasks()

      const step3Tasks = ctx.ongoingTasks.get().filter(
        (t) => t && isStep3FormRelatedTaskType(t.taskType) && isOngoingUserTaskStatus(t.status)
      )
      const extractTask = ctx.ongoingTasks.get().find(
        (t) =>
          t && normUserTaskType(t.taskType) === 'asset_extract' && isOngoingUserTaskStatus(t.status)
      )

      for (const t of step3Tasks) {
        const tid = parseTaskId(t.id)
        if (tid == null) continue
        if (ctx.store().taskIdsWithLocalFollowPaused.includes(tid)) continue
        if (
          !shouldOuterStartStep3TaskFollow({
            taskId: tid,
            modalFollowSlotLive: hasLiveTaskSseFollow(tid),
            pageStreamAlreadyOpen: ctx.activeTaskStreamClosers.has(tid)
          })
        ) {
          continue
        }
        const tab = await ctx.resolveTabKeyForStep3OngoingTask(t)
        if (gen !== ctx.restoreTasksGeneration) return
        if (tab == null) continue
        ctx.registerStep3OngoingTaskMeta(tid, tab, t.taskType ?? null)
        if (tab !== ctx.activeTab.get()) continue
        // await 后占槽可能已变（弹窗仍活 / 并发 restore），再闸一次
        if (
          !shouldOuterStartStep3TaskFollow({
            taskId: tid,
            modalFollowSlotLive: hasLiveTaskSseFollow(tid),
            pageStreamAlreadyOpen: ctx.activeTaskStreamClosers.has(tid)
          })
        ) {
          continue
        }
        void ctx.startTrackTask({
          taskId: tid,
          taskType: t.taskType ?? null,
          tab,
          skipPreSseHydrate: true
        })
      }

      if (extractTask?.id) {
        const extractId = Number(extractTask.id)
        const userPausedFollow =
          Number.isFinite(extractId) &&
          extractId > 0 &&
          ctx.store().taskIdsWithLocalFollowPaused.includes(extractId)
        if (
          !userPausedFollow &&
          !ctx.activeTaskStreamClosers.has(extractId) &&
          !ctx.activeTrackedTaskIds.get().includes(extractId)
        ) {
          void ctx.startTrackTask({
            taskId: extractId,
            taskType: extractTask.taskType ?? null,
            tab: ctx.activeTab.get()
          })
        }
      } else if (taskListOk && ctx.store().isExtractingAssets) {
        const scopeKey = ctx.store().step3GenVisualScopeKey()
        const shellLive = ctx.store().getAssetExtractShellLiveTaskId()
        const followId = ctx.store().getAssetExtractFollowTask(scopeKey)
        if (shellLive || followId) {
          /* 壳层或 follow 登记仍在，由 extract composable / startTrackTask 重连 SSE */
        } else {
          ctx.store().finishAssetExtractUiForCurrentScope()
        }
      }

      if (gen !== ctx.restoreTasksGeneration) return
      ctx.purgeStaleStep3FormImageGeneratingMarks(cover.coverFormIds)
      if (hasOngoingStep3FormImageTasks()) {
        for (const tab of ['scene', 'character', 'prop'] as TabKey[]) {
          ctx.reapplyFormImageGeneratingSlotsFromActiveIds(tab)
        }
      }
      ctx.reconcileStep3GeneratingWithLoadedImages()
      ctx.store().refreshStep3VisualGeneratingFlag()
      await ctx.recoverStaleGeneratingAfterCompletedFormImageTasks()
      for (const tab of ['scene', 'character', 'prop'] as TabKey[]) {
        await finalizeTerminalStep3TasksForTab(tab)
      }
      ctx.purgeStaleStep3FormImageGeneratingMarks(new Set())
      ctx.reconcileStep3GeneratingWithLoadedImages()
      ctx.store().refreshStep3VisualGeneratingFlag()
    } catch {
      // ignore restore errors
    } finally {
      endFlowTaskListQuietWindow(saveCtx.projectId)
    }
    })()

    ctx.restoreAndTrackOngoingTasksInFlight = pending
    try {
      await pending
    } finally {
      if (ctx.restoreAndTrackOngoingTasksInFlight === pending) {
        ctx.restoreAndTrackOngoingTasksInFlight = null
      }
    }
  }

  return {
    getRecentStep3TaskRow,
    registerUnresolvedStep3OngoingTasksForTab,
    resumeStep3SseForActiveTab,
    finalizeTerminalStep3TaskFromDetail,
    finalizeTerminalStep3TasksForTab,
    ensureStep3TabGeneratingTasksFollowed,
    releaseStaleStep3FormImageTaskFollowsForTab,
    syncStep3SseFollowForActiveTab,
    shouldFetchOngoingUserTaskList,
    reloadOngoingTasks,
    reconcileOngoingStep3TasksToUi,
    reconcileOngoingFormImageTasksToStep3Ui,
    resolveTabKeyForStep3OngoingRestore,
    resolveBackgroundTabsWithStep3OngoingWork,
    restoreAndTrackOngoingTasks
  }
}
