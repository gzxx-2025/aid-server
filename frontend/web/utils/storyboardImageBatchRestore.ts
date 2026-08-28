/**
 * 分镜图批量生成：刷新/切集后的任务恢复编排（原
 * composables/useStoryboardImageBatchGenerate.ts restoreOngoingBatchIfNeeded 段拆分）。
 * 状态与 follow 链路经参数注入，主体见 hooks/useStoryboardImageBatchGenerate.ts。
 */

import {
captureCreationLiveGenScope,
matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import { resolveCurrentStep4LiveGenScopeBlobs } from '~/composables/useCreationStoreHydration'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import {
fetchUserTaskDetailOnce,
isTerminalTaskStatus,
isUserTaskTerminal,
normalizeTaskStatus,
resolveUserTaskTerminalOutcome
} from '~/composables/useTaskSseFollow'
import type { StoryboardPanel } from '~/types'
import type { UserTaskRow } from '~/types/business-api'
import type { StoryboardImageBatchCore } from '~/utils/storyboardImageBatchFollowCore'
import type { StoryboardImageBatchPromptFollow } from '~/utils/storyboardImageBatchPromptFollow'
import {
resolveImageBatchLoadingTargetIds,
shouldRestoreImageBatchSse
} from '~/utils/storyboardImageBatchRestoreGate'
import {
isOngoingImageBatchTaskStatus,
parseImageBatchTaskId as parseTaskId,
type StoryboardImageBatchState,
type StoryboardPromptBatchFollowResult
} from '~/utils/storyboardImageBatchShared'
import { hasPersistedStoryboardImageBatchGenWork } from '~/utils/storyboardListBootstrap'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { shouldKeepImageBatchLoadingAfterFollowMessage } from '~/utils/taskSseSilentDisconnect'
import {
beginFlowTaskListQuietWindow,
endFlowTaskListQuietWindow
} from '~/utils/userTaskListFlowOnce'

export function createStoryboardImageBatchRestore(
  state: StoryboardImageBatchState,
  core: StoryboardImageBatchCore,
  follow: StoryboardImageBatchPromptFollow
) {
  const { getStore } = core

  async function restoreOngoingBatchIfNeeded(
    currentPanels: StoryboardPanel[],
    onPanelsUpdate: (panels: StoryboardPanel[]) => void,
    options?: { discoverServerTasks?: boolean }
  ): Promise<void> {
    if (typeof window === 'undefined') return

    if (
      state.batchSseFollowInFlight ||
      state.batchRunInFlight ||
      state.followInFlight != null ||
      state.imageFollowInFlight != null
    ) {
      return
    }
    if (state.restoreSessionInFlight) {
      return state.restoreSessionInFlight
    }

    // restore 的资格判断必须基于已回填的当前 scope，而不是刷新初始时的空扁平字段。
    core.applyImmediatePanelLoadingRestore(currentPanels)
    const hasServerStoryboardIds = currentPanels.some(
      (panel) => parseServerStoryboardId(panel.id) != null
    )
    const hasRestoreIntent = shouldRestoreImageBatchSse({
      isGenerating:
        Boolean(getStore().isGeneratingStoryboardImageBatch) ||
        hasPersistedStoryboardImageBatchGenWork(getStore(), getRouteLikeSnapshot()),
      following: false,
      hasServerStoryboardIds,
      hasActiveTaskId:
        parseTaskId(getStore().storyboardImageBatchActiveTaskId) != null ||
        parseTaskId(getStore().storyboardImageBatchActiveImageTaskId) != null
    })
    // 响应式调用不轮询；只有页面 bootstrap/scope 事件允许从服务端发现一次丢失的本地快照。
    if (!hasRestoreIntent && !options?.discoverServerTasks) return

    if (
      state.batchSseFollowInFlight ||
      state.batchRunInFlight ||
      state.followInFlight != null ||
      state.imageFollowInFlight != null
    ) {
      return
    }

    const scopeAtEntry = captureCreationLiveGenScope()

    const run = async () => {
      const ctx = await resolveStoryScriptSaveContext(getStore(), getRouteLikeSnapshot())
      if (!ctx) return

      const gen = ++state.resumeFollowGeneration
      beginFlowTaskListQuietWindow(ctx.projectId)
      try {
        const storyboardIds = currentPanels
          .map((p) => parseServerStoryboardId(p.id))
          .filter((id): id is number => id != null)

        let tasks: UserTaskRow[] = []
        let taskListOk = true
        try {
          tasks = await core.fetchRecentProjectTasks(ctx.projectId)
        } catch {
          taskListOk = false
        }
        if (gen !== state.resumeFollowGeneration) return
        if (taskListOk) {
          await core.reconcileOngoingImageGenerationTasks(tasks, currentPanels, scopeAtEntry)
          if (gen !== state.resumeFollowGeneration || !matchesCreationLiveGenScope(scopeAtEntry)) {
            return
          }
        }

        const preferredId = getStore().storyboardImageBatchActiveTaskId
        const preferredImageIdEarly = getStore().storyboardImageBatchActiveImageTaskId
        const prefPromptId = parseTaskId(preferredId)
        const prefImageIdEarly = parseTaskId(preferredImageIdEarly)
        const hasActiveBatchTaskId = prefPromptId != null || prefImageIdEarly != null

        // 跨集会清空 panels：有 taskId 时先跟 SSE，勿只亮 loading
        if (
          !storyboardIds.length &&
          !options?.discoverServerTasks &&
          !shouldRestoreImageBatchSse({
            isGenerating: Boolean(getStore().isGeneratingStoryboardImageBatch),
            following: false,
            hasActiveTaskId: hasActiveBatchTaskId
          })
        ) {
          return
        }

        let ongoingId: number | null = null

        if (prefPromptId) {
          let detail = null
          try {
            detail = await fetchUserTaskDetailOnce(prefPromptId)
          } catch {
            /* task/list 仍可用于恢复 */
          }
          if (gen !== state.resumeFollowGeneration) return
          if (
            detail &&
            (isOngoingImageBatchTaskStatus(normalizeTaskStatus(detail.status)) ||
              isTerminalTaskStatus(detail.status))
          ) {
            ongoingId = prefPromptId
          } else if (!detail && getStore().isGeneratingStoryboardImageBatch && prefPromptId) {
            // detail 暂不可用时仍信任 store taskId，避免跨集返回只恢复 loading
            ongoingId = prefPromptId
          }
        }

        if (ongoingId == null) {
          ongoingId = parseTaskId(follow.pickOngoingImagePromptBatchTask(tasks, preferredId)?.id)
        }

        // 仍 generating 且 store 有 prompt taskId 时，即使 list 未命中也跟 SSE
        if (ongoingId == null && prefPromptId && getStore().isGeneratingStoryboardImageBatch) {
          ongoingId = prefPromptId
        }

        if (gen !== state.resumeFollowGeneration) return

        core.applyImmediatePanelLoadingRestore(currentPanels)

        if (ongoingId != null) {
          if (gen !== state.resumeFollowGeneration) return
          getStore().setStoryboardImageBatchGenerating(true)
          const restoreLoadingTargets = resolveImageBatchLoadingTargetIds(
            core.getActiveImageBatchTargetIdsLocal(),
            storyboardIds
          )
          core.ensureImageBatchLoadingUi(restoreLoadingTargets, currentPanels)

          let promptOutcome: StoryboardPromptBatchFollowResult
          const promptAlreadyTerminal = await isUserTaskTerminal(ongoingId)
          if (promptAlreadyTerminal) {
            core.syncActiveTaskIdToStore(null)
            const resolved = await resolveUserTaskTerminalOutcome(ongoingId)
            if (resolved.kind === 'succeeded') {
              promptOutcome = {
                ok: true,
                chainChildTaskIds: await follow.resolveChainChildTaskIdsForPromptTask(ongoingId)
              }
            } else if (resolved.kind === 'partial_failed') {
              promptOutcome = {
                ok: false,
                partial: true,
                message: '部分分镜图提示词生成失败，可续生',
                chainChildTaskIds: await follow.resolveChainChildTaskIdsForPromptTask(ongoingId)
              }
            } else if (resolved.kind === 'cancelled') {
              promptOutcome = { ok: false, message: resolved.message || '任务已取消' }
            } else if (resolved.kind === 'failed') {
              promptOutcome = {
                ok: false,
                message: resolved.errorMessage || '批量生成分镜图失败'
              }
            } else {
              promptOutcome = { ok: false, message: '批量生成分镜图失败' }
            }
          } else {
            promptOutcome = await follow.followPromptTask(ongoingId, storyboardIds)
          }
          if (gen !== state.resumeFollowGeneration) {
            core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, currentPanels, {
              promptTaskId: ongoingId
            })
            return
          }

          let workingPanels = currentPanels
          let backgroundStillRunning = shouldKeepImageBatchLoadingAfterFollowMessage(
            promptOutcome.message
          )
          if (promptOutcome.ok || promptOutcome.partial) {
            try {
              workingPanels = await core.refreshPanelsFromApi()
              onPanelsUpdate(workingPanels)
            } catch {
              /* ignore */
            }
            const batchTargetIds = core.getActiveImageBatchTargetIdsLocal()
            const imageTargets = resolveImageBatchLoadingTargetIds(
              batchTargetIds.length
                ? batchTargetIds
                : core.resolveBatchImageTargets(workingPanels, storyboardIds, false),
              storyboardIds
            )
            const imageOutcome = await follow.followImageGenerateAfterPrompt(
              imageTargets,
              storyboardIds,
              workingPanels,
              promptOutcome.chainChildTaskIds
            )
            if (gen !== state.resumeFollowGeneration) {
              core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, workingPanels, {
                promptTaskId: ongoingId
              })
              return
            }
            if (imageOutcome.ok && imageOutcome.panels) {
              workingPanels = imageOutcome.panels
              onPanelsUpdate(workingPanels)
            }
            backgroundStillRunning = shouldKeepImageBatchLoadingAfterFollowMessage(
              imageOutcome.message
            )
          }

          if (backgroundStillRunning || !matchesCreationLiveGenScope(scopeAtEntry)) {
            core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, workingPanels, {
              promptTaskId: ongoingId
            })
            return
          }

          getStore().setStoryboardImageBatchGenerating(false)
          core.clearPanelGeneratingStatuses(storyboardIds)
          core.clearImageBatchTargetIds()
          return
        }

        const preferredImageId = getStore().storyboardImageBatchActiveImageTaskId
        const prefImageId = parseTaskId(preferredImageId)
        let ongoingImageId: number | null = null

        if (prefImageId) {
          const detail = await fetchUserTaskDetailOnce(prefImageId)
          if (gen !== state.resumeFollowGeneration) return
          if (detail && isOngoingImageBatchTaskStatus(normalizeTaskStatus(detail.status))) {
            ongoingImageId = prefImageId
          } else if (detail && isTerminalTaskStatus(detail.status)) {
            ongoingImageId = prefImageId
          } else if (!detail && getStore().isGeneratingStoryboardImageBatch) {
            ongoingImageId = prefImageId
          }
        }

        if (ongoingImageId == null) {
          if (!tasks.length && taskListOk) {
            try {
              tasks = await core.fetchRecentProjectTasks(ctx.projectId)
            } catch {
              tasks = []
              taskListOk = false
            }
            if (gen !== state.resumeFollowGeneration) return
          }
          ongoingImageId = parseTaskId(
            follow.pickOngoingImageGenerateTask(tasks, preferredImageId)?.id
          )
        }

        if (!ongoingImageId && taskListOk && prefImageId) {
          const detail = await fetchUserTaskDetailOnce(prefImageId)
          if (gen !== state.resumeFollowGeneration) return
          if (detail && isOngoingImageBatchTaskStatus(normalizeTaskStatus(detail.status))) {
            ongoingImageId = prefImageId
          } else if (detail && isTerminalTaskStatus(detail.status)) {
            ongoingImageId = prefImageId
          }
        }

        if (
          ongoingImageId == null &&
          prefImageId &&
          getStore().isGeneratingStoryboardImageBatch
        ) {
          ongoingImageId = prefImageId
        }

        if (ongoingImageId != null) {
          if (gen !== state.resumeFollowGeneration) return
          if (core.isModalOwnedStoryboardImageTaskId(ongoingImageId)) {
            if (getStore().isGeneratingStoryboardImageBatch) {
              getStore().setStoryboardImageBatchGenerating(false)
            }
            return
          }
          getStore().setStoryboardImageBatchGenerating(true)
          core.applyImmediatePanelLoadingRestore(currentPanels)
          const batchTargetIds = core.getActiveImageBatchTargetIdsLocal()
          const imageTargets =
            batchTargetIds.length > 0
              ? batchTargetIds
              : core.resolveBatchImageTargets(currentPanels, storyboardIds, false)

          const imageAlreadyTerminal = await isUserTaskTerminal(ongoingImageId)
          if (imageAlreadyTerminal) {
            core.syncActiveImageTaskIdToStore(null)
            const resolved = await resolveUserTaskTerminalOutcome(ongoingImageId)
            if (resolved.kind === 'succeeded' || resolved.kind === 'partial_failed') {
              const finalizedPanels = await core.finalizeBatchPanelsAfterImageGen(
                ctx,
                imageTargets.length ? imageTargets : storyboardIds
              )
              onPanelsUpdate(finalizedPanels)
              getStore().setStoryboardImageBatchProgress(
                imageTargets.length || storyboardIds.length,
                imageTargets.length || storyboardIds.length
              )
            } else {
              core.clearPanelGeneratingStatuses(storyboardIds)
            }
            getStore().setStoryboardImageBatchGenerating(false)
            core.clearPanelGeneratingStatuses(core.getActiveImageBatchTargetIdsLocal())
            core.clearImageBatchTargetIds()
            return
          }

          const imageFollowOutcome = await follow.followOngoingImageGenerateTask(
            ongoingImageId,
            storyboardIds,
            imageTargets.length ? imageTargets : storyboardIds
          )
          if (gen !== state.resumeFollowGeneration) {
            core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, currentPanels, {
              imageTaskId: ongoingImageId
            })
            return
          }
          if (imageFollowOutcome.panels) {
            onPanelsUpdate(imageFollowOutcome.panels)
          }
          if (
            !imageFollowOutcome.ok &&
            shouldKeepImageBatchLoadingAfterFollowMessage(imageFollowOutcome.message)
          ) {
            core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, currentPanels, {
              imageTaskId: ongoingImageId
            })
            return
          }
          if (!matchesCreationLiveGenScope(scopeAtEntry)) {
            core.keepLoadingAfterFollowInterrupt(scopeAtEntry, storyboardIds, currentPanels, {
              imageTaskId: ongoingImageId
            })
            return
          }
          getStore().setStoryboardImageBatchGenerating(false)
          core.clearPanelGeneratingStatuses(core.getActiveImageBatchTargetIdsLocal())
          core.clearImageBatchTargetIds()
          return
        }

        const persistedGenerating = Object.entries(
          getStore().storyboardPanelImageGenStatusByStoryboardId
        ).filter(([, st]) => st === 'generating')

        const pendingImageTasks: Array<[string, { taskId: number; sceneIdx: number }]> = []
        const seenPendingSids = new Set<string>()
        for (const { blob } of resolveCurrentStep4LiveGenScopeBlobs(
          getStore(),
          getRouteLikeSnapshot()
        )) {
          for (const [sidRaw, snap] of Object.entries(
            blob.storyboardImageGenTasksByStoryboardId || {}
          )) {
            if (seenPendingSids.has(sidRaw)) continue
            seenPendingSids.add(sidRaw)
            pendingImageTasks.push([sidRaw, snap as { taskId: number; sceneIdx: number }])
          }
        }

        if (!persistedGenerating.length && !pendingImageTasks.length) {
          if (getStore().isGeneratingStoryboardImageBatch) {
            const promptTid = parseTaskId(getStore().storyboardImageBatchActiveTaskId)
            const imageTid = parseTaskId(getStore().storyboardImageBatchActiveImageTaskId)
            // 仍有 taskId 时禁止「只刷 loading 不连 SSE」早退（跨集返回常见）
            if (promptTid || imageTid) {
              core.applyImmediatePanelLoadingRestore(currentPanels, { skipScopeHydrate: true })
              if (promptTid) {
                getStore().setStoryboardImageBatchGenerating(true)
                core.ensureImageBatchLoadingUi(
                  core.keepLoadingTargetsForStoryboards(storyboardIds),
                  currentPanels
                )
                if (gen !== state.resumeFollowGeneration) return
                await follow.followPromptTask(promptTid, storyboardIds)
                return
              }
              if (imageTid && !core.isModalOwnedStoryboardImageTaskId(imageTid)) {
                getStore().setStoryboardImageBatchGenerating(true)
                const targets = core.keepLoadingTargetsForStoryboards(storyboardIds)
                core.ensureImageBatchLoadingUi(targets, currentPanels)
                if (gen !== state.resumeFollowGeneration) return
                await follow.followOngoingImageGenerateTask(imageTid, storyboardIds, targets)
                return
              }
            }
            if (!taskListOk) {
              core.ensureBatchPanelLoadingUi(currentPanels)
              return
            }
            const flatHasBatchWork =
              getStore().storyboardImageBatchTargetStoryboardIds.length > 0 ||
              getStore().storyboardImageBatchActiveImageTaskId != null ||
              state.batchRunInFlight
            if (
              hasPersistedStoryboardImageBatchGenWork(getStore(), getRouteLikeSnapshot()) ||
              flatHasBatchWork
            ) {
              core.applyImmediatePanelLoadingRestore(currentPanels, { skipScopeHydrate: true })
              return
            }
            core.stopImageBatchGeneration()
          }
          return
        }

        if (pendingImageTasks.length) {
          // 弹窗持久化任务由弹窗恢复，外层列表不建立 SSE。
          return
        }

        if (persistedGenerating.length) {
          core.applyImmediatePanelLoadingRestore(currentPanels, {
            skipScopeHydrate: state.batchRunInFlight
          })
          return
        }
      } finally {
        endFlowTaskListQuietWindow(ctx.projectId)
      }
    }

    const pending = run()
    const owner = pending.finally(() => {
      state.restoreSessionInFlight = null
    })
    state.restoreSessionInFlight = owner
    return owner
  }

  return { restoreOngoingBatchIfNeeded }
}
