import { emptyStep4PlusLiveGenSnapshot } from './liveGenMigrate'
import type { CreationGet,CreationSet } from './state'
import type {
StoryboardDubbingGenTaskSnapshot,
StoryboardImageGenTaskSnapshot,
StoryboardModalImageGenKind,
StoryboardVideoGenTaskSnapshot,
StoryboardVideoPromptGenTaskKind,
StoryboardVideoPromptGenTaskSnapshot
} from './types'

export interface ModalTaskActions {
  setStoryboardImageGenTask: (
    storyboardId: number,
    payload: {
      taskId: number
      sceneIdx: number
      kind?: StoryboardModalImageGenKind
      imageIdx?: number
      message?: string
      stepTitle?: string
    },
    scopeKey?: string
  ) => void
  clearStoryboardImageGenTask: (storyboardId: number, scopeKey?: string) => void
  getStoryboardImageGenTask: (
    storyboardId: number,
    scopeKey?: string
  ) => StoryboardImageGenTaskSnapshot | null
  setStoryboardImagePromptGenTask: (
    storyboardId: number,
    payload: { taskId: number; sceneIdx: number },
    scopeKey?: string
  ) => void
  clearStoryboardImagePromptGenTask: (storyboardId: number, scopeKey?: string) => void
  getStoryboardImagePromptGenTask: (
    storyboardId: number,
    scopeKey?: string
  ) => StoryboardImageGenTaskSnapshot | null
  setStoryboardVideoPromptGenTask: (
    storyboardId: number,
    payload: {
      taskId: number
      sceneIdx: number
      taskKind: StoryboardVideoPromptGenTaskKind
    },
    scopeKey?: string
  ) => void
  clearStoryboardVideoPromptGenTask: (storyboardId: number, scopeKey?: string) => void
  getStoryboardVideoPromptGenTask: (
    storyboardId: number,
    scopeKey?: string
  ) => StoryboardVideoPromptGenTaskSnapshot | null
  setStoryboardVideoGenTask: (
    storyboardId: number,
    payload: {
      taskId: number
      sceneIdx: number
      taskKind: 'i2v' | 'multi' | 'edge' | 'grid'
      message?: string
      stepTitle?: string
    },
    scopeKey?: string
  ) => void
  clearStoryboardVideoGenTask: (storyboardId: number, scopeKey?: string) => void
  getStoryboardVideoGenTask: (
    storyboardId: number,
    scopeKey?: string
  ) => StoryboardVideoGenTaskSnapshot | null
  setStoryboardDubbingGenTask: (
    storyboardId: number,
    payload: {
      taskId?: number
      composeBatchId?: string
      audioRecordId?: number
      sceneIdx: number
      lipSync?: boolean
      message?: string
      stepTitle?: string
    },
    scopeKey?: string
  ) => void
  clearStoryboardDubbingGenTask: (storyboardId: number, scopeKey?: string) => void
  getStoryboardDubbingGenTask: (
    storyboardId: number,
    scopeKey?: string
  ) => StoryboardDubbingGenTaskSnapshot | null
  setEpisodeExportFollowTask: (
    scopeKey: string,
    payload: { episodeEditorId?: number | null; taskId?: number; active?: boolean }
  ) => void
  clearEpisodeExportFollowTask: (scopeKey?: string) => void
  getEpisodeExportFollowTask: (scopeKey?: string) => {
    episodeEditorId: number | null
  } | null
}

export function createModalTaskSlice(_set: CreationSet, get: CreationGet): ModalTaskActions {
  return {
    setStoryboardImageGenTask(
      storyboardId: number,
      payload: {
        taskId: number
        sceneIdx: number
        kind?: StoryboardModalImageGenKind
        imageIdx?: number
        message?: string
        stepTitle?: string
      },
      scopeKey?: string
    ) {
      const sid = Number(storyboardId)
      const tid = Number(payload.taskId)
      const sceneIdx = Number(payload.sceneIdx)
      if (!Number.isFinite(sid) || sid <= 0 || !Number.isFinite(tid) || tid <= 0) return
      const key = scopeKey || get().step3GenVisualScopeKey()
      const base = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      const prev = base.storyboardImageGenTasksByStoryboardId?.[String(sid)]
      const msg = String(payload.message ?? prev?.message ?? '').trim()
      const step = String(payload.stepTitle ?? prev?.stepTitle ?? '').trim()
      const kind = payload.kind ?? prev?.kind
      const imageIdxRaw = payload.imageIdx ?? prev?.imageIdx
      const imageIdx =
        imageIdxRaw != null && Number.isFinite(Number(imageIdxRaw))
          ? Number(imageIdxRaw)
          : undefined
      get().mergeStep4PlusLiveGenForScopeKey(key, {
        storyboardImageGenTasksByStoryboardId: {
          ...(base.storyboardImageGenTasksByStoryboardId || {}),
          [String(sid)]: {
            taskId: tid,
            sceneIdx: Number.isFinite(sceneIdx) ? sceneIdx : 0,
            ...(kind ? { kind } : {}),
            ...(imageIdx != null ? { imageIdx } : {}),
            ...(msg ? { message: msg } : {}),
            ...(step ? { stepTitle: step } : {})
          }
        }
      })
    },

    clearStoryboardImageGenTask(storyboardId: number, scopeKey?: string) {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return
      const key = scopeKey || get().step3GenVisualScopeKey()
      const base = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      const next = { ...(base.storyboardImageGenTasksByStoryboardId || {}) }
      delete next[String(sid)]
      get().mergeStep4PlusLiveGenForScopeKey(key, {
        storyboardImageGenTasksByStoryboardId: next
      })
    },

    getStoryboardImageGenTask(
      storyboardId: number,
      scopeKey?: string
    ): StoryboardImageGenTaskSnapshot | null {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return null
      const key = scopeKey || get().step3GenVisualScopeKey()
      const blob = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      const hit = blob.storyboardImageGenTasksByStoryboardId?.[String(sid)]
      if (!hit) return null
      const tid = Number(hit.taskId)
      if (!Number.isFinite(tid) || tid <= 0) return null
      return {
        taskId: tid,
        sceneIdx: Number(hit.sceneIdx) || 0,
        ...(hit.kind ? { kind: hit.kind } : {}),
        ...(hit.imageIdx != null && Number.isFinite(Number(hit.imageIdx))
          ? { imageIdx: Number(hit.imageIdx) }
          : {}),
        ...(String(hit.message ?? '').trim() ? { message: String(hit.message).trim() } : {}),
        ...(String(hit.stepTitle ?? '').trim() ? { stepTitle: String(hit.stepTitle).trim() } : {})
      }
    },

    setStoryboardImagePromptGenTask(
      storyboardId: number,
      payload: { taskId: number; sceneIdx: number },
      scopeKey?: string
    ) {
      const sid = Number(storyboardId)
      const tid = Number(payload.taskId)
      const sceneIdx = Number(payload.sceneIdx)
      if (!Number.isFinite(sid) || sid <= 0 || !Number.isFinite(tid) || tid <= 0) return
      const key = scopeKey || get().step3GenVisualScopeKey()
      const base = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      get().mergeStep4PlusLiveGenForScopeKey(key, {
        storyboardImagePromptGenTasksByStoryboardId: {
          ...(base.storyboardImagePromptGenTasksByStoryboardId || {}),
          [String(sid)]: { taskId: tid, sceneIdx: Number.isFinite(sceneIdx) ? sceneIdx : 0 }
        }
      })
    },

    clearStoryboardImagePromptGenTask(storyboardId: number, scopeKey?: string) {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return
      const key = scopeKey || get().step3GenVisualScopeKey()
      const base = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      const next = { ...(base.storyboardImagePromptGenTasksByStoryboardId || {}) }
      delete next[String(sid)]
      get().mergeStep4PlusLiveGenForScopeKey(key, {
        storyboardImagePromptGenTasksByStoryboardId: next
      })
    },

    getStoryboardImagePromptGenTask(
      storyboardId: number,
      scopeKey?: string
    ): StoryboardImageGenTaskSnapshot | null {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return null
      const key = scopeKey || get().step3GenVisualScopeKey()
      const blob = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      const hit = blob.storyboardImagePromptGenTasksByStoryboardId?.[String(sid)]
      if (!hit) return null
      const tid = Number(hit.taskId)
      if (!Number.isFinite(tid) || tid <= 0) return null
      return { taskId: tid, sceneIdx: Number(hit.sceneIdx) || 0 }
    },

    setStoryboardVideoPromptGenTask(
      storyboardId: number,
      payload: {
        taskId: number
        sceneIdx: number
        taskKind: StoryboardVideoPromptGenTaskKind
      },
      scopeKey?: string
    ) {
      const sid = Number(storyboardId)
      const tid = Number(payload.taskId)
      const sceneIdx = Number(payload.sceneIdx)
      if (!Number.isFinite(sid) || sid <= 0 || !Number.isFinite(tid) || tid <= 0) return
      const key = scopeKey || get().step3GenVisualScopeKey()
      const base = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      get().mergeStep4PlusLiveGenForScopeKey(key, {
        storyboardVideoPromptGenTasksByStoryboardId: {
          ...(base.storyboardVideoPromptGenTasksByStoryboardId || {}),
          [String(sid)]: {
            taskId: tid,
            sceneIdx: Number.isFinite(sceneIdx) ? sceneIdx : 0,
            taskKind: payload.taskKind
          }
        }
      })
    },

    clearStoryboardVideoPromptGenTask(storyboardId: number, scopeKey?: string) {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return
      const key = scopeKey || get().step3GenVisualScopeKey()
      const base = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      const next = { ...(base.storyboardVideoPromptGenTasksByStoryboardId || {}) }
      delete next[String(sid)]
      get().mergeStep4PlusLiveGenForScopeKey(key, {
        storyboardVideoPromptGenTasksByStoryboardId: next
      })
    },

    getStoryboardVideoPromptGenTask(
      storyboardId: number,
      scopeKey?: string
    ): StoryboardVideoPromptGenTaskSnapshot | null {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return null
      const key = scopeKey || get().step3GenVisualScopeKey()
      const blob = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      const hit = blob.storyboardVideoPromptGenTasksByStoryboardId?.[String(sid)]
      if (!hit) return null
      const tid = Number(hit.taskId)
      if (!Number.isFinite(tid) || tid <= 0) return null
      const taskKind =
        hit.taskKind === 'multi-video-prompt-gen' ? 'multi-video-prompt-gen' : 'video-prompt-gen'
      return {
        taskId: tid,
        sceneIdx: Number(hit.sceneIdx) || 0,
        taskKind
      }
    },

    setStoryboardVideoGenTask(
      storyboardId: number,
      payload: {
        taskId: number
        sceneIdx: number
        taskKind: 'i2v' | 'multi' | 'edge' | 'grid'
        message?: string
        stepTitle?: string
      },
      scopeKey?: string
    ) {
      const sid = Number(storyboardId)
      const tid = Number(payload.taskId)
      const sceneIdx = Number(payload.sceneIdx)
      if (!Number.isFinite(sid) || sid <= 0 || !Number.isFinite(tid) || tid <= 0) return
      const key = scopeKey || get().step3GenVisualScopeKey()
      const base = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      const prev = base.storyboardVideoGenTasksByStoryboardId?.[String(sid)]
      const msg = String(payload.message ?? prev?.message ?? '').trim()
      const step = String(payload.stepTitle ?? prev?.stepTitle ?? '').trim()
      const taskKind = payload.taskKind ?? prev?.taskKind ?? 'i2v'
      get().mergeStep4PlusLiveGenForScopeKey(key, {
        storyboardVideoGenTasksByStoryboardId: {
          ...(base.storyboardVideoGenTasksByStoryboardId || {}),
          [String(sid)]: {
            taskId: tid,
            sceneIdx: Number.isFinite(sceneIdx) ? sceneIdx : 0,
            taskKind,
            ...(msg ? { message: msg } : {}),
            ...(step ? { stepTitle: step } : {})
          }
        }
      })
    },

    clearStoryboardVideoGenTask(storyboardId: number, scopeKey?: string) {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return
      const key = scopeKey || get().step3GenVisualScopeKey()
      const base = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      const next = { ...(base.storyboardVideoGenTasksByStoryboardId || {}) }
      delete next[String(sid)]
      get().mergeStep4PlusLiveGenForScopeKey(key, {
        storyboardVideoGenTasksByStoryboardId: next
      })
    },

    getStoryboardVideoGenTask(
      storyboardId: number,
      scopeKey?: string
    ): StoryboardVideoGenTaskSnapshot | null {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return null
      const key = scopeKey || get().step3GenVisualScopeKey()
      const blob = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      const hit = blob.storyboardVideoGenTasksByStoryboardId?.[String(sid)]
      if (!hit) return null
      const tid = Number(hit.taskId)
      if (!Number.isFinite(tid) || tid <= 0) return null
      return {
        taskId: tid,
        sceneIdx: Number(hit.sceneIdx) || 0,
        taskKind:
          hit.taskKind === 'multi'
            ? 'multi'
            : hit.taskKind === 'edge'
              ? 'edge'
              : hit.taskKind === 'grid'
                ? 'grid'
                : 'i2v',
        ...(String(hit.message ?? '').trim() ? { message: String(hit.message).trim() } : {}),
        ...(String(hit.stepTitle ?? '').trim() ? { stepTitle: String(hit.stepTitle).trim() } : {})
      }
    },

    setStoryboardDubbingGenTask(
      storyboardId: number,
      payload: {
        taskId?: number
        composeBatchId?: string
        audioRecordId?: number
        sceneIdx: number
        lipSync?: boolean
        message?: string
        stepTitle?: string
      },
      scopeKey?: string
    ) {
      const sid = Number(storyboardId)
      const sceneIdx = Number(payload.sceneIdx)
      const composeBatchId = String(payload.composeBatchId || '').trim()
      const audioRecordId = Number(payload.audioRecordId)
      const tid = Number(payload.taskId)
      const hasTask = Number.isFinite(tid) && tid > 0
      const hasAudio = Number.isFinite(audioRecordId) && audioRecordId > 0
      const hasCompose = !!composeBatchId && hasAudio
      if (!Number.isFinite(sid) || sid <= 0 || (!hasTask && !hasCompose)) return
      const key = scopeKey || get().step3GenVisualScopeKey()
      const base = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      const prev = base.storyboardDubbingGenTasksByStoryboardId?.[String(sid)]
      const msg = String(payload.message ?? prev?.message ?? '').trim()
      const step = String(payload.stepTitle ?? prev?.stepTitle ?? '').trim()
      const lipSync = payload.lipSync != null ? Boolean(payload.lipSync) : prev?.lipSync
      const prevAudio = Number(prev?.audioRecordId)
      const mergedAudio = hasAudio
        ? audioRecordId
        : Number.isFinite(prevAudio) && prevAudio > 0
          ? prevAudio
          : undefined
      get().mergeStep4PlusLiveGenForScopeKey(key, {
        storyboardDubbingGenTasksByStoryboardId: {
          ...(base.storyboardDubbingGenTasksByStoryboardId || {}),
          [String(sid)]: {
            sceneIdx: Number.isFinite(sceneIdx) ? sceneIdx : 0,
            ...(hasCompose ? { composeBatchId, audioRecordId } : {}),
            ...(hasTask ? { taskId: tid } : {}),
            ...(!hasCompose && mergedAudio != null ? { audioRecordId: mergedAudio } : {}),
            ...(lipSync != null ? { lipSync } : {}),
            ...(msg ? { message: msg } : {}),
            ...(step ? { stepTitle: step } : {})
          }
        }
      })
    },

    clearStoryboardDubbingGenTask(storyboardId: number, scopeKey?: string) {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return
      const key = scopeKey || get().step3GenVisualScopeKey()
      const base = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      const next = { ...(base.storyboardDubbingGenTasksByStoryboardId || {}) }
      delete next[String(sid)]
      get().mergeStep4PlusLiveGenForScopeKey(key, {
        storyboardDubbingGenTasksByStoryboardId: next
      })
    },

    getStoryboardDubbingGenTask(
      storyboardId: number,
      scopeKey?: string
    ): StoryboardDubbingGenTaskSnapshot | null {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return null
      const key = scopeKey || get().step3GenVisualScopeKey()
      const blob = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      const hit = blob.storyboardDubbingGenTasksByStoryboardId?.[String(sid)]
      if (!hit) return null
      const composeBatchId = String(hit.composeBatchId || '').trim()
      const audioRecordId = Number(hit.audioRecordId)
      const tid = Number(hit.taskId)
      const hasTask = Number.isFinite(tid) && tid > 0
      const hasAudio = Number.isFinite(audioRecordId) && audioRecordId > 0
      const hasCompose = !!composeBatchId && hasAudio
      if (!hasTask && !hasCompose) return null
      return {
        sceneIdx: Number(hit.sceneIdx) || 0,
        ...(hasCompose ? { composeBatchId, audioRecordId } : {}),
        ...(hasTask ? { taskId: tid } : {}),
        ...(!hasCompose && hasAudio ? { audioRecordId } : {}),
        ...(hit.lipSync != null ? { lipSync: Boolean(hit.lipSync) } : {}),
        ...(String(hit.message ?? '').trim() ? { message: String(hit.message).trim() } : {}),
        ...(String(hit.stepTitle ?? '').trim() ? { stepTitle: String(hit.stepTitle).trim() } : {})
      }
    },

    setEpisodeExportFollowTask(
      scopeKey: string,
      payload: { episodeEditorId?: number | null; taskId?: number; active?: boolean }
    ) {
      const key = String(scopeKey || '').trim()
      if (!key || key.startsWith('0:')) return
      const editorId = Number(payload.episodeEditorId)
      const hasEditor = Number.isFinite(editorId) && editorId > 0
      // 兼容旧调用：曾误存 media taskId；现仅作「导出跟进中」标记（固定 1）
      const marker = payload.active === true || hasEditor || Number(payload.taskId) > 0 ? 1 : 0
      if (!marker) return
      get().mergeStep4PlusLiveGenForScopeKey(key, {
        episodeExportTaskId: marker,
        episodeExportEditorId: hasEditor ? editorId : null
      })
    },

    clearEpisodeExportFollowTask(scopeKey?: string) {
      const key = String(scopeKey || get().step3GenVisualScopeKey() || '').trim()
      if (!key) return
      get().mergeStep4PlusLiveGenForScopeKey(key, {
        episodeExportTaskId: null,
        episodeExportEditorId: null
      })
    },

    getEpisodeExportFollowTask(scopeKey?: string): {
      episodeEditorId: number | null
    } | null {
      const key = String(scopeKey || get().step3GenVisualScopeKey() || '').trim()
      if (!key) return null
      const blob = get().step4PlusLiveGenByScope[key]
      if (!blob) return null
      const marker = Number(blob.episodeExportTaskId)
      const editorId = Number(blob.episodeExportEditorId)
      const hasEditor = Number.isFinite(editorId) && editorId > 0
      // marker>0 或已有 editorId，均视为导出跟进中（兼容历史误存的超大 media taskId）
      if (!(marker > 0 || hasEditor)) return null
      return {
        episodeEditorId: hasEditor ? editorId : null
      }
    }
  }
}
