import { message } from 'antd'
import {
captureCreationLiveGenScope,
matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import { findStoryboardDubbingGenTaskInScopes } from '~/composables/useCreationStoreHydration'
import {
runStoryboardDubbingGenerateTask,
type StoryboardDubbingComposeJob,
type StoryboardDubbingGenerateParams,
type StoryboardDubbingGenerateProgress
} from '~/composables/useStoryboardDubbingGenerate'
import {
isStoryboardDubbingGenFollowActive,
listActiveStoryboardDubbingGenFollowIds,
releaseStoryboardDubbingGenFollow,
runStoryboardDubbingGenFollowOnce
} from '~/composables/useStoryboardDubbingGenFollowLock'
import { suspendTaskSseFollow } from '~/composables/useTaskSseFollow'
import { useCreationStore } from '~/stores/creation'
import {
modalGenSessionScopeFromScopeKey,
modalGenSessionScopeFromStore
} from '~/utils/modalGenSessionScope'
import {
resolveComposeJobFromDubbingSnapshots,
resolveOngoingComposeDubbingJob
} from '~/utils/modalGenTaskRestore'
import { listModalTabFollowsToSuspend } from '~/utils/modalTabSseMutex'
import {
clearStoryboardDubbingModalGenSession,
notifyStoryboardDubbingGenSettled,
persistStoryboardDubbingModalGenSession,
readStoryboardDubbingModalGenSession
} from '~/utils/storyboardDubbingModalGenSession'
import {
clearProjectStoryboardRecordCache
} from '~/utils/storyboardRecordBatch'
import { getPanelStoryboardVideoUrl } from '~/utils/storyboardVideoCover'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
getVideoUrl,
resolveDubbingPanelKey,
resolveStoryboardIdForIndex
} from './derived'
import { formatDubTime } from './helpers'
import { refreshServerVideoRecords } from './recordsOps'
import type { DubbingGenItem,DubbingModalCtx } from './types'
import { navKeyLoading,navKeySource } from './types'

export function storyboardDubbingModalSessionScope() {
  return modalGenSessionScopeFromStore(useCreationStore.getState())
}

/** 提交响应晚于项目切换时，挂起迟到建立的 SSE，切回原 scope 后再恢复。 */
export function suspendLateModalDubbingFollowIfScopeChanged(
  taskId: number,
  taskScope: ReturnType<typeof captureCreationLiveGenScope>
) {
  if (typeof window === 'undefined' || !Number.isFinite(taskId) || taskId <= 0) return
  queueMicrotask(() => {
    if (!matchesCreationLiveGenScope(taskScope)) suspendTaskSseFollow(taskId)
  })
}

export function primeDubbingLoadingFromStore(ctx: DubbingModalCtx) {
  const S = ctx.state
  ctx.props().dubbingPanels.forEach((_, idx) => {
    const sid = resolveStoryboardIdForIndex(ctx, idx)
    if (!sid) return
    const task = findStoryboardDubbingGenTaskInScopes(useCreationStore.getState(), sid, ctx.route())
    if (!task) return
    const panelKey = resolveDubbingPanelKey(ctx, idx)
    S.genLoadingByPanelKey.set({ ...S.genLoadingByPanelKey.get(), [panelKey]: true })
    if (idx === S.currentSceneIndex.get()) {
      S.selectedNavKeyByIndex.set({ ...S.selectedNavKeyByIndex.get(), [idx]: navKeyLoading })
    }
  })
}

/** Pinia 任务终态 / 后台 follow 结束后，同步清弹窗内 loading 并切到最新生成结果 */
export function clearDubbingLoadingUiForScene(ctx: DubbingModalCtx, sceneIdx: number) {
  const S = ctx.state
  const panelKey = resolveDubbingPanelKey(ctx, sceneIdx)
  const hadLoading = !!S.genLoadingByPanelKey.get()[panelKey]
  const gl = { ...S.genLoadingByPanelKey.get() }
  delete gl[panelKey]
  S.genLoadingByPanelKey.set(gl)

  const gm = { ...S.generatingMetaByIndex.get() }
  delete gm[sceneIdx]
  S.generatingMetaByIndex.set(gm)

  if (!hadLoading || S.selectedNavKeyByIndex.get()[sceneIdx] !== navKeyLoading) return

  const hist = S.genHistoryByIndex.get()[sceneIdx] || []
  const latest = hist[hist.length - 1]
  const panel = ctx.props().dubbingPanels[sceneIdx]
  const lipKey = panel?.dubbingLipSyncKey
  const nextKey =
    latest?.id || (lipKey != null && String(lipKey).trim() !== '' ? lipKey : null) || navKeySource
  S.selectedNavKeyByIndex.set({
    ...S.selectedNavKeyByIndex.get(),
    [sceneIdx]: nextKey
  })
}

export function syncDubbingLoadingUiFromStore(ctx: DubbingModalCtx) {
  const S = ctx.state
  if (!ctx.props().open) return
  ctx.props().dubbingPanels.forEach((_, idx) => {
    const sid = resolveStoryboardIdForIndex(ctx, idx)
    if (!sid) return
    const panelKey = resolveDubbingPanelKey(ctx, idx)
    if (!S.genLoadingByPanelKey.get()[panelKey]) return

    const store = useCreationStore.getState()
    const persisted = findStoryboardDubbingGenTaskInScopes(store, sid, ctx.route())
    const stillFollowing = isStoryboardDubbingGenFollowActive(sid, store.step3GenVisualScopeKey())
    if (!persisted && !stillFollowing) {
      clearDubbingLoadingUiForScene(ctx, idx)
    }
  })
}

export function buildDubbingGenerateParams(
  ctx: DubbingModalCtx,
  sceneIdx: number
): StoryboardDubbingGenerateParams | null {
  const S = ctx.state
  const storyboardId = resolveStoryboardIdForIndex(ctx, sceneIdx)
  const vp = ctx.props().storyboardVideoPanels
  const vPanel = vp[sceneIdx]
  const src = getPanelStoryboardVideoUrl(vPanel) || getVideoUrl(ctx, sceneIdx)
  if (!storyboardId || !src) return null

  const draft = S.draftByIndex.get()[sceneIdx]
  const isCurrent = sceneIdx === S.currentSceneIndex.get()
  const dialogue = isCurrent ? S.draftDialogue.get().trim() : String(draft?.dialogue ?? '').trim()
  const voiceName = isCurrent ? S.draftVoiceName.get() : String(draft?.voiceName ?? '')
  const emotion = isCurrent ? S.draftEmotion.get() : String(draft?.emotion ?? '中性')
  const emotionCode = String(S.emotionNameToCode.get().get(emotion) || '').trim()
  const lipSync = isCurrent ? S.draftLipSync.get() : Boolean(draft?.lipSync)
  const voiceLibraryId = isCurrent
    ? S.draftVoiceLibraryId.get() > 0
      ? S.draftVoiceLibraryId.get()
      : undefined
    : draft?.voiceLibraryId
  const voiceModelId = isCurrent
    ? S.draftVoiceModelId.get() > 0
      ? S.draftVoiceModelId.get()
      : undefined
    : draft?.voiceModelId
  const timbreCode = isCurrent ? S.draftTimbreCode.get().trim() || undefined : draft?.timbreCode

  return {
    storyboardId,
    dialogue,
    voiceName,
    voiceLibraryId,
    voiceModelId,
    timbreCode,
    emotion,
    emotionCode,
    lipSync,
    sourceVideoUrl: src
  }
}

export async function runDubbingGenerateForScene(
  ctx: DubbingModalCtx,
  sceneIdx: number,
  opts?: { resumeComposeJob?: StoryboardDubbingComposeJob; silentComplete?: boolean }
) {
  const S = ctx.state
  const storyboardId = resolveStoryboardIdForIndex(ctx, sceneIdx)
  if (!storyboardId) return

  const followScope = captureCreationLiveGenScope()
  return runStoryboardDubbingGenFollowOnce(
    storyboardId,
    async () => {
      const params = buildDubbingGenerateParams(ctx, sceneIdx)
      const lipSync = opts?.resumeComposeJob?.lipSync ?? params?.lipSync ?? false
      if (!opts?.resumeComposeJob && !params) return

      const effectiveParams: StoryboardDubbingGenerateParams =
        params ??
        ({
          storyboardId,
          dialogue: '',
          voiceName: '',
          emotion: '中性',
          lipSync,
          sourceVideoUrl: opts?.resumeComposeJob?.sourceVideoUrl || getVideoUrl(ctx, sceneIdx) || ''
        } satisfies StoryboardDubbingGenerateParams)

      const panelKey = resolveDubbingPanelKey(ctx, sceneIdx)
      /** 剧集隔离：任务归属启动时 scope；快照读写用 scopeKey，终态 UI/toast 用 liveScope 校验 */
      const liveScope = followScope
      const scopeKey = liveScope.scopeKey
      const taskSessionScope = modalGenSessionScopeFromScopeKey(scopeKey)

      persistStoryboardDubbingModalGenSession(
        storyboardId,
        sceneIdx,
        scopeKey,
        opts?.resumeComposeJob
          ? {
              composeBatchId: opts.resumeComposeJob.composeBatchId,
              audioRecordId: opts.resumeComposeJob.audioRecordId,
              taskId: opts.resumeComposeJob.taskId,
              lipSync: opts.resumeComposeJob.lipSync
            }
          : { lipSync: effectiveParams.lipSync },
        taskSessionScope
      )

      if (!opts?.resumeComposeJob) {
        S.generatingMetaByIndex.set({
          ...S.generatingMetaByIndex.get(),
          [sceneIdx]: {
            voice: effectiveParams.voiceName,
            emotion: effectiveParams.emotion,
            timeLabel: formatDubTime()
          }
        })
      }

      S.genLoadingByPanelKey.set({ ...S.genLoadingByPanelKey.get(), [panelKey]: true })
      if (sceneIdx === S.currentSceneIndex.get()) {
        S.selectedNavKeyByIndex.set({ ...S.selectedNavKeyByIndex.get(), [sceneIdx]: navKeyLoading })
      }

      const onProgress = (p: StoryboardDubbingGenerateProgress) => {
        const composeBatchId = String(
          p.composeBatchId || opts?.resumeComposeJob?.composeBatchId || ''
        ).trim()
        const audioRecordId = Number(p.audioRecordId ?? opts?.resumeComposeJob?.audioRecordId)
        const taskId = Number(p.taskId ?? opts?.resumeComposeJob?.taskId)
        const lipSync = effectiveParams.lipSync
        const hasTask = Number.isFinite(taskId) && taskId > 0
        const hasAudio = Number.isFinite(audioRecordId) && audioRecordId > 0
        if (lipSync) {
          if (!hasTask) return
        } else if (!composeBatchId || !hasAudio) {
          return
        }
        const hint = String(p.message || p.stepTitle || '').trim()
        if (hint) {
          S.lipSyncProgressHintByIndex.set({
            ...S.lipSyncProgressHintByIndex.get(),
            [sceneIdx]: hint
          })
        }
        useCreationStore.getState().setStoryboardDubbingGenTask(
          storyboardId,
          {
            composeBatchId,
            ...(hasAudio ? { audioRecordId } : {}),
            ...(hasTask ? { taskId } : {}),
            sceneIdx,
            lipSync,
            message: p.message,
            stepTitle: p.stepTitle
          },
          scopeKey
        )
        persistStoryboardDubbingModalGenSession(
          storyboardId,
          sceneIdx,
          scopeKey,
          {
            composeBatchId,
            ...(hasAudio ? { audioRecordId } : {}),
            ...(hasTask ? { taskId } : {}),
            lipSync
          },
          taskSessionScope
        )
      }

      let preserveTaskOnExit = false
      try {
        const result = await runStoryboardDubbingGenerateTask({
          params: effectiveParams,
          resumeComposeJob: opts?.resumeComposeJob,
          onProgress,
          onSubmitted: ({ composeBatchId, audioRecordId, taskId }) => {
            const tid = Number(taskId)
            const aid = Number(audioRecordId)
            useCreationStore.getState().setStoryboardDubbingGenTask(
              storyboardId,
              {
                composeBatchId,
                ...(Number.isFinite(aid) && aid > 0 ? { audioRecordId: aid } : {}),
                ...(Number.isFinite(tid) && tid > 0 ? { taskId: tid } : {}),
                sceneIdx,
                lipSync: effectiveParams.lipSync
              },
              scopeKey
            )
            persistStoryboardDubbingModalGenSession(
              storyboardId,
              sceneIdx,
              scopeKey,
              {
                composeBatchId,
                ...(Number.isFinite(aid) && aid > 0 ? { audioRecordId: aid } : {}),
                ...(Number.isFinite(tid) && tid > 0 ? { taskId: tid } : {}),
                lipSync: effectiveParams.lipSync
              },
              taskSessionScope
            )
            suspendLateModalDubbingFollowIfScopeChanged(tid, liveScope)
          }
        })

        /** 剧集隔离：已切集则不 toast、不回写当前集数据；finally 仍按 scopeKey 清理任务所属桶 */
        if (!matchesCreationLiveGenScope(liveScope)) {
          preserveTaskOnExit = true
          return
        }

        if (result.ok === false) {
          if (result.deferred) {
            preserveTaskOnExit = true
            return
          }
          if (!opts?.silentComplete) {
            message.error(result.errorMessage || '配音生成失败，请重试')
          }
          if (sceneIdx === S.currentSceneIndex.get()) {
            S.selectedNavKeyByIndex.set({
              ...S.selectedNavKeyByIndex.get(),
              [sceneIdx]: navKeySource
            })
          }
          return
        }

        const recordId = Number(result.lipSyncVideoRecordId)
        const item: DubbingGenItem = {
          id:
            Number.isFinite(recordId) && recordId > 0
              ? `compose-${recordId}`
              : `dub-gen-${Date.now()}-${sceneIdx}`,
          url: result.videoUrl,
          title: `文本朗读 | 配音 ${effectiveParams.voiceName} ${effectiveParams.emotion} ${formatDubTime()}`,
          dialogue: effectiveParams.dialogue,
          voiceName: effectiveParams.voiceName,
          emotion: effectiveParams.emotion
        }
        S.genHistoryByIndex.set({
          ...S.genHistoryByIndex.get(),
          [sceneIdx]: [...(S.genHistoryByIndex.get()[sceneIdx] || []), item]
        })
        const saveCtx = await resolveStoryScriptSaveContext(useCreationStore.getState(), ctx.route())
        if (saveCtx) {
          clearProjectStoryboardRecordCache(saveCtx)
          void ctx.refreshHeaderTabs(true)
          await refreshServerVideoRecords(ctx, sceneIdx, { force: true })
        }
        if (sceneIdx === S.currentSceneIndex.get()) {
          const hist = S.genHistoryByIndex.get()[sceneIdx] || []
          const synced =
            hist.find((h) => h.url === item.url) || hist.find((h) => h.id === item.id) || item
          S.selectedNavKeyByIndex.set({
            ...S.selectedNavKeyByIndex.get(),
            [sceneIdx]: synced.id
          })
          if (!opts?.silentComplete) {
            message.success(
              effectiveParams.lipSync
                ? '对口型视频已生成，请点击「设置为音画同步结果」确认使用'
                : '配音视频已生成，请点击「设置为音画同步结果」确认使用'
            )
          }
        }
      } finally {
        if (!preserveTaskOnExit) {
          const nextHint = { ...S.lipSyncProgressHintByIndex.get() }
          delete nextHint[sceneIdx]
          S.lipSyncProgressHintByIndex.set(nextHint)
          useCreationStore.getState().clearStoryboardDubbingGenTask(storyboardId, scopeKey)
          clearStoryboardDubbingModalGenSession(taskSessionScope)
          clearDubbingLoadingUiForScene(ctx, sceneIdx)
        }
        notifyStoryboardDubbingGenSettled(storyboardId, scopeKey)
      }
    },
    followScope.scopeKey
  )
}

export async function restoreStoryboardDubbingGenerateIfNeeded(
  ctx: DubbingModalCtx,
  sceneIdx: number
) {
  const S = ctx.state
  if (!ctx.props().open) return
  const storyboardId = resolveStoryboardIdForIndex(ctx, sceneIdx)
  if (!storyboardId) return

  const store = useCreationStore.getState()
  const persisted = findStoryboardDubbingGenTaskInScopes(store, storyboardId, ctx.route())
  const sessionScope = storyboardDubbingModalSessionScope()
  const session = readStoryboardDubbingModalGenSession(sessionScope)
  const composeJob = resolveComposeJobFromDubbingSnapshots(persisted, session, storyboardId)
  if (!composeJob) {
    store.clearStoryboardDubbingGenTask(storyboardId)
    return
  }
  if (isStoryboardDubbingGenFollowActive(storyboardId, store.step3GenVisualScopeKey())) {
    return
  }

  const panelKey = resolveDubbingPanelKey(ctx, sceneIdx)
  S.genLoadingByPanelKey.set({ ...S.genLoadingByPanelKey.get(), [panelKey]: true })
  if (sceneIdx === S.currentSceneIndex.get()) {
    S.selectedNavKeyByIndex.set({ ...S.selectedNavKeyByIndex.get(), [sceneIdx]: navKeyLoading })
  }

  const gen = ++ctx.resumeDubbingFollowGen.current
  const ongoingJob = await resolveOngoingComposeDubbingJob(composeJob)
  if (gen !== ctx.resumeDubbingFollowGen.current) return

  if (!ongoingJob) {
    const latestStore = useCreationStore.getState()
    latestStore.clearStoryboardDubbingGenTask(storyboardId)
    clearStoryboardDubbingModalGenSession(sessionScope)
    clearDubbingLoadingUiForScene(ctx, sceneIdx)
    notifyStoryboardDubbingGenSettled(storyboardId, latestStore.step3GenVisualScopeKey())
    return
  }

  const params = buildDubbingGenerateParams(ctx, sceneIdx)
  await runDubbingGenerateForScene(ctx, sceneIdx, {
    resumeComposeJob: {
      composeBatchId: ongoingJob.composeBatchId,
      audioRecordId: ongoingJob.audioRecordId,
      taskId: ongoingJob.taskId,
      lipSync: composeJob.lipSync ?? params?.lipSync ?? false,
      sourceVideoUrl: params?.sourceVideoUrl || getVideoUrl(ctx, sceneIdx) || ''
    },
    silentComplete: true
  })
}

/** 顶部 Tab 互斥：挂起非当前分镜的对口型 SSE / follow 占坑 */
export function suspendOtherStoryboardDubbingModalFollows(
  ctx: DubbingModalCtx,
  keepStoryboardId: number | null
) {
  const store = useCreationStore.getState()
  const scopeKey = store.step3GenVisualScopeKey()
  const keepKey = keepStoryboardId != null && keepStoryboardId > 0 ? String(keepStoryboardId) : ''
  const activeFollows: Array<{ tabKey: string; taskId: number }> = []
  for (const sid of listActiveStoryboardDubbingGenFollowIds(scopeKey)) {
    const task = findStoryboardDubbingGenTaskInScopes(store, sid, ctx.route())
    const tid = Number(task?.taskId)
    if (!Number.isFinite(tid) || tid <= 0) continue
    activeFollows.push({ tabKey: String(sid), taskId: tid })
  }
  const toSuspend = listModalTabFollowsToSuspend({
    currentTabKey: keepKey,
    activeFollows
  })
  for (const tid of toSuspend) {
    suspendTaskSseFollow(tid)
  }
  for (const sid of listActiveStoryboardDubbingGenFollowIds(scopeKey)) {
    if (keepStoryboardId != null && sid === keepStoryboardId) continue
    releaseStoryboardDubbingGenFollow(sid, scopeKey)
  }
}

/** window 'storyboard-dubbing-gen-settled' 事件：同集终态后清对应分镜 loading */
export function handleStoryboardDubbingGenSettledEvent(ctx: DubbingModalCtx, event: Event) {
  if (!ctx.props().open) return
  const detail = (event as CustomEvent<{ storyboardId?: number; scopeKey?: string }>).detail
  const settledScopeKey = String(detail?.scopeKey || '').trim()
  if (
    settledScopeKey &&
    settledScopeKey !== useCreationStore.getState().step3GenVisualScopeKey()
  ) {
    return
  }
  const settledSid = Number(detail?.storyboardId)
  if (Number.isFinite(settledSid) && settledSid > 0) {
    ctx.props().dubbingPanels.forEach((_, idx) => {
      if (resolveStoryboardIdForIndex(ctx, idx) === settledSid) {
        clearDubbingLoadingUiForScene(ctx, idx)
      }
    })
    return
  }
  syncDubbingLoadingUiFromStore(ctx)
}
