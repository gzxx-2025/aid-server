import {
type ModalGenSessionScope,
modalGenSessionScopeFromScopeKey,
readScopedSessionItem,
removeScopedSessionItem,
writeScopedSessionItem
} from '~/utils/modalGenSessionScope'

export const STORYBOARD_DUBBING_MODAL_GEN_SESSION_KEY = 'storyboard-dubbing-modal-gen-session'
export const STORYBOARD_DUBBING_MODAL_DISMISSED_KEY = 'storyboard-dubbing-modal-user-dismissed'

export type StoryboardDubbingModalGenSession = {
  storyboardId: number
  sceneIdx: number
  scopeKey?: string
  /** SSE 任务 taskId（/api/user/task/stream/{taskId}） */
  taskId?: number
  composeBatchId?: string
  audioRecordId?: number
  lipSync?: boolean
}

export function persistStoryboardDubbingModalGenSession(
  storyboardId: number,
  sceneIdx: number,
  scopeKey?: string,
  extra?: {
    taskId?: number
    composeBatchId?: string
    audioRecordId?: number
    lipSync?: boolean
  },
  sessionScope?: ModalGenSessionScope | null
) {
  if (!(typeof window !== 'undefined')) return
  try {
    const resolvedScope = modalGenSessionScopeFromScopeKey(scopeKey ?? '') ?? sessionScope
    const prev = readStoryboardDubbingModalGenSession(resolvedScope)
    const taskIdRaw = extra?.taskId ?? prev?.taskId
    const taskId =
      taskIdRaw != null && Number.isFinite(Number(taskIdRaw)) && Number(taskIdRaw) > 0
        ? Number(taskIdRaw)
        : undefined
    const composeBatchId = String(extra?.composeBatchId ?? prev?.composeBatchId ?? '').trim()
    const audioRecordIdRaw = extra?.audioRecordId ?? prev?.audioRecordId
    const audioRecordId =
      audioRecordIdRaw != null &&
      Number.isFinite(Number(audioRecordIdRaw)) &&
      Number(audioRecordIdRaw) > 0
        ? Number(audioRecordIdRaw)
        : undefined
    const lipSync = extra?.lipSync != null ? Boolean(extra.lipSync) : prev?.lipSync
    writeScopedSessionItem(
      STORYBOARD_DUBBING_MODAL_GEN_SESSION_KEY,
      JSON.stringify({
        storyboardId,
        sceneIdx,
        scopeKey: scopeKey ?? prev?.scopeKey ?? '',
        ...(taskId != null ? { taskId } : {}),
        ...(composeBatchId ? { composeBatchId } : {}),
        ...(audioRecordId != null ? { audioRecordId } : {}),
        ...(lipSync != null ? { lipSync } : {})
      } satisfies StoryboardDubbingModalGenSession),
      resolvedScope
    )
    removeScopedSessionItem(STORYBOARD_DUBBING_MODAL_DISMISSED_KEY, resolvedScope)
  } catch {
    /* ignore */
  }
}

export function clearStoryboardDubbingModalGenSession(sessionScope?: ModalGenSessionScope | null) {
  removeScopedSessionItem(STORYBOARD_DUBBING_MODAL_GEN_SESSION_KEY, sessionScope)
}

export function markStoryboardDubbingModalUserDismissed(
  storyboardId: number,
  sessionScope?: ModalGenSessionScope | null
) {
  if (!(typeof window !== 'undefined')) return
  try {
    writeScopedSessionItem(
      STORYBOARD_DUBBING_MODAL_DISMISSED_KEY,
      String(storyboardId),
      sessionScope
    )
    removeScopedSessionItem(STORYBOARD_DUBBING_MODAL_GEN_SESSION_KEY, sessionScope)
  } catch {
    /* ignore */
  }
}

export function clearStoryboardDubbingModalUserDismissed(
  sessionScope?: ModalGenSessionScope | null
) {
  removeScopedSessionItem(STORYBOARD_DUBBING_MODAL_DISMISSED_KEY, sessionScope)
}

export function isStoryboardDubbingModalUserDismissed(
  storyboardId: number,
  sessionScope?: ModalGenSessionScope | null
): boolean {
  if (!(typeof window !== 'undefined')) return false
  try {
    return (
      readScopedSessionItem(STORYBOARD_DUBBING_MODAL_DISMISSED_KEY, sessionScope) ===
      String(storyboardId)
    )
  } catch {
    return false
  }
}

export function readStoryboardDubbingModalGenSession(
  sessionScope?: ModalGenSessionScope | null
): StoryboardDubbingModalGenSession | null {
  if (!(typeof window !== 'undefined')) return null
  try {
    const raw = readScopedSessionItem(STORYBOARD_DUBBING_MODAL_GEN_SESSION_KEY, sessionScope)
    if (!raw) return null
    const o = JSON.parse(raw) as Partial<StoryboardDubbingModalGenSession>
    const storyboardId = Number(o.storyboardId)
    const sceneIdx = Number(o.sceneIdx)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return null
    if (!Number.isFinite(sceneIdx) || sceneIdx < 0) return null
    const composeBatchId = String(o.composeBatchId || '').trim()
    const audioRecordId =
      o.audioRecordId != null &&
      Number.isFinite(Number(o.audioRecordId)) &&
      Number(o.audioRecordId) > 0
        ? Number(o.audioRecordId)
        : undefined
    const taskId =
      o.taskId != null && Number.isFinite(Number(o.taskId)) && Number(o.taskId) > 0
        ? Number(o.taskId)
        : undefined
    return {
      storyboardId,
      sceneIdx,
      ...(o.scopeKey ? { scopeKey: String(o.scopeKey) } : {}),
      ...(taskId != null ? { taskId } : {}),
      ...(composeBatchId && audioRecordId != null ? { composeBatchId, audioRecordId } : {}),
      ...(o.lipSync != null ? { lipSync: Boolean(o.lipSync) } : {})
    }
  } catch {
    return null
  }
}

export function isStoryboardDubbingModalGenSessionActive(
  storyboardId?: number | null,
  sessionScope?: ModalGenSessionScope | null
): boolean {
  if (!(typeof window !== 'undefined')) return false
  const session = readStoryboardDubbingModalGenSession(sessionScope)
  if (!session) return false
  if (storyboardId != null) {
    const sid = Number(storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) return false
    if (session.storyboardId !== sid) return false
    return !isStoryboardDubbingModalUserDismissed(sid, sessionScope)
  }
  return !isStoryboardDubbingModalUserDismissed(session.storyboardId, sessionScope)
}

/** 配音 compose 任务终态（成功/失败/已结束）后通知弹窗同步清除 loading */
export function notifyStoryboardDubbingGenSettled(storyboardId: number, scopeKey?: string) {
  if (!(typeof window !== 'undefined')) return
  window.dispatchEvent(
    new CustomEvent('storyboard-dubbing-gen-settled', {
      detail: { storyboardId: Number(storyboardId), scopeKey: String(scopeKey || '') }
    })
  )
}
