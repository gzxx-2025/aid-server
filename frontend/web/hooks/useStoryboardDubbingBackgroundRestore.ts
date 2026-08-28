'use client'

import { useState } from 'react'
import {
  findStoryboardDubbingGenTaskInScopes,
  waitForCreationStoreHydrated
} from '~/composables/useCreationStoreHydration'
import {
  followStoryboardDubbingComposeJob,
  followStoryboardLipSyncOnlyJob,
  type StoryboardDubbingGenerateProgress
} from '~/composables/useStoryboardDubbingGenerate'
import {
  runStoryboardDubbingGenFollowOnce,
  isStoryboardDubbingGenFollowActive
} from '~/composables/useStoryboardDubbingGenFollowLock'
import {
  clearStoryboardDubbingModalGenSession,
  readStoryboardDubbingModalGenSession,
  notifyStoryboardDubbingGenSettled
} from '~/utils/storyboardDubbingModalGenSession'
import {
  resolveComposeJobFromDubbingSnapshots,
  resolveOngoingComposeDubbingJob
} from '~/utils/modalGenTaskRestore'
import type { DubbingPanel, StoryboardPanel, StoryboardVideoPanel } from '~/types'
import { getPanelStoryboardVideoUrl } from '~/utils/storyboardVideoCover'
import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { useCreationStore } from '~/stores/creation'
import { modalGenSessionScopeFromScopeKey } from '~/utils/modalGenSessionScope'

let restoreGeneration = 0

function formatDubTime(d = new Date()) {
  return d.toLocaleString('sv-SE').replace(' ', ' ')
}

function resolveStoryboardIdForIndex(
  index: number,
  scriptPanels: StoryboardPanel[],
  dubbingPanels: DubbingPanel[]
): number | null {
  const raw = scriptPanels[index]?.id ?? dubbingPanels[index]?.id
  const id = Number(raw)
  return Number.isFinite(id) && id > 0 ? id : null
}

function resolveSourceVideoUrl(index: number, videoPanels: StoryboardVideoPanel[]): string {
  return getPanelStoryboardVideoUrl(videoPanels[index] ?? {})
}

/**
 * 原 Vue 版签名为 (store, route)（响应式实例）；React 侧改为调用时取
 * `useCreationStore.getState()` + `getRouteLikeSnapshot()`，避免异步回调闭包持有过期快照。
 */
function createStoryboardDubbingBackgroundRestore() {
  const getStore = () => useCreationStore.getState()

  async function restoreOngoingDubbingTasks(
    dubbingPanels: DubbingPanel[],
    scriptPanels: StoryboardPanel[],
    videoPanels: StoryboardVideoPanel[],
    onPanelsUpdate: (next: DubbingPanel[]) => void
  ) {
    if (typeof window === 'undefined') return
    await waitForCreationStoreHydrated(getStore(), getRouteLikeSnapshot())
    const gen = ++restoreGeneration
    const scopeKey = getStore().step3GenVisualScopeKey()
    const sessionScope = modalGenSessionScopeFromScopeKey(scopeKey)

    for (let i = 0; i < dubbingPanels.length; i++) {
      if (gen !== restoreGeneration) return

      const storyboardId = resolveStoryboardIdForIndex(i, scriptPanels, dubbingPanels)
      if (!storyboardId) continue
      if (isStoryboardDubbingGenFollowActive(storyboardId, scopeKey)) continue

      const session = readStoryboardDubbingModalGenSession(sessionScope)
      const persisted = findStoryboardDubbingGenTaskInScopes(
        getStore(),
        storyboardId,
        getRouteLikeSnapshot()
      )
      const composeJob = resolveComposeJobFromDubbingSnapshots(persisted, session, storyboardId)
      if (!composeJob) {
        if (persisted) getStore().clearStoryboardDubbingGenTask(storyboardId)
        continue
      }

      const sourceVideoUrl = resolveSourceVideoUrl(i, videoPanels)

      const ongoing = await resolveOngoingComposeDubbingJob(composeJob)
      if (gen !== restoreGeneration) return

      if (!ongoing) {
        getStore().clearStoryboardDubbingGenTask(storyboardId, scopeKey)
        clearStoryboardDubbingModalGenSession(sessionScope)
        notifyStoryboardDubbingGenSettled(storyboardId, scopeKey)
        continue
      }

      if (!sourceVideoUrl || isStoryboardDubbingGenFollowActive(storyboardId, scopeKey)) continue

      void runStoryboardDubbingGenFollowOnce(
        storyboardId,
        async () => {
          const lipSync = composeJob.lipSync ?? false
          const panel = dubbingPanels[i]
          const voiceName = panel?.dubbingVoiceName || '无音色'
          const emotion = panel?.dubbingEmotion || '中性'
          const dialogue = scriptPanels[i]?.scriptContent?.trim() || panel?.dialogue?.trim() || ''

          const onProgress = (p: StoryboardDubbingGenerateProgress) => {
            const composeBatchId = String(p.composeBatchId || ongoing.composeBatchId || '').trim()
            const audioRecordId = Number(p.audioRecordId ?? ongoing.audioRecordId)
            const taskId = Number(p.taskId ?? ongoing.taskId)
            const hasTask = Number.isFinite(taskId) && taskId > 0
            const hasAudio = Number.isFinite(audioRecordId) && audioRecordId > 0
            if (lipSync) {
              if (!hasTask) return
            } else if (!composeBatchId || !hasAudio) {
              return
            }
            getStore().setStoryboardDubbingGenTask(
              storyboardId,
              {
                composeBatchId,
                ...(hasAudio ? { audioRecordId } : {}),
                ...(hasTask ? { taskId } : {}),
                sceneIdx: i,
                lipSync,
                message: p.message,
                stepTitle: p.stepTitle
              },
              scopeKey
            )
          }

          try {
            const result =
              lipSync && !ongoing.composeBatchId
                ? await followStoryboardLipSyncOnlyJob({
                    params: {
                      storyboardId,
                      dialogue,
                      voiceName,
                      emotion,
                      lipSync: true,
                      sourceVideoUrl
                    },
                    taskId: Number(ongoing.taskId),
                    audioRecordId: ongoing.audioRecordId,
                    onProgress
                  })
                : await followStoryboardDubbingComposeJob({
                    composeBatchId: ongoing.composeBatchId,
                    audioRecordId: ongoing.audioRecordId,
                    lipSync: false,
                    sourceVideoUrl,
                    storyboardId,
                    onProgress
                  })

            if (result.ok === false) {
              if (!result.deferred) {
                getStore().clearStoryboardDubbingGenTask(storyboardId, scopeKey)
                clearStoryboardDubbingModalGenSession(sessionScope)
              }
              notifyStoryboardDubbingGenSettled(storyboardId, scopeKey)
              return
            }

            const recordId = Number(result.lipSyncVideoRecordId)
            const itemId =
              Number.isFinite(recordId) && recordId > 0
                ? `compose-${recordId}`
                : `dub-restore-${Date.now()}-${i}`
            const newItem = {
              id: itemId,
              url: result.videoUrl,
              title: `文本朗读 | 配音 ${voiceName} ${emotion} ${formatDubTime()}`,
              dialogue,
              voiceName,
              emotion
            }
            const prevPanel = dubbingPanels[i]
            const prevHistory = prevPanel?.dubbingGenHistory || []
            const next = dubbingPanels.map((p, idx) =>
              idx !== i
                ? p
                : {
                    ...p,
                    dialogue: dialogue || p.dialogue,
                    dubbingVoiceName: voiceName,
                    dubbingEmotion: emotion,
                    dubbingLipSyncVideoUrl: result.videoUrl,
                    dubbingLipSyncKey: itemId,
                    dubbingGenHistory: [...prevHistory, newItem],
                    status: 'done' as const,
                    storyboardDubbingConfirmed: true as const
                  }
            )
            onPanelsUpdate(next)
            getStore().clearStoryboardDubbingGenTask(storyboardId, scopeKey)
            clearStoryboardDubbingModalGenSession(sessionScope)
            notifyStoryboardDubbingGenSettled(storyboardId, scopeKey)
          } catch {
            getStore().clearStoryboardDubbingGenTask(storyboardId, scopeKey)
            clearStoryboardDubbingModalGenSession(sessionScope)
            notifyStoryboardDubbingGenSettled(storyboardId, scopeKey)
          }
        },
        scopeKey
      )
    }
  }

  function cancelPendingRestore() {
    restoreGeneration += 1
  }

  return {
    restoreOngoingDubbingTasks,
    cancelPendingRestore,
    isStoryboardDubbingGenFollowActive
  }
}

export function useStoryboardDubbingBackgroundRestore() {
  const [instance] = useState(() => createStoryboardDubbingBackgroundRestore())
  return instance
}
