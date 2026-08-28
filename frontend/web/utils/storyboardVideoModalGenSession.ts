import {
  type ModalGenSessionScope,
  readScopedSessionItem,
  removeScopedSessionItem,
  writeScopedSessionItem
} from '~/utils/modalGenSessionScope'

export const STORYBOARD_VIDEO_MODAL_GEN_SESSION_KEY = 'storyboard-video-modal-gen-session'
export const STORYBOARD_VIDEO_MODAL_DISMISSED_KEY = 'storyboard-video-modal-user-dismissed'

export type StoryboardVideoModalGenTaskKind =
  | 'i2v'
  | 'multi'
  | 'edge'
  | 'grid'
  | 'video-prompt-gen'
  | 'multi-video-prompt-gen'

export type StoryboardVideoModalGenSession = {
  storyboardId: number
  sceneIdx: number
  taskKind?: StoryboardVideoModalGenTaskKind
  taskId?: number
}

export function persistStoryboardVideoModalGenSession(
  storyboardId: number,
  sceneIdx: number,
  extra?: { taskKind?: StoryboardVideoModalGenTaskKind; taskId?: number },
  sessionScope?: ModalGenSessionScope | null
) {
  if (!(typeof window !== 'undefined')) return
  try {
    const prev = readStoryboardVideoModalGenSession(sessionScope)
    const taskIdRaw = extra?.taskId ?? prev?.taskId
    const taskId =
      taskIdRaw != null && Number.isFinite(Number(taskIdRaw)) && Number(taskIdRaw) > 0
        ? Number(taskIdRaw)
        : undefined
    writeScopedSessionItem(
      STORYBOARD_VIDEO_MODAL_GEN_SESSION_KEY,
      JSON.stringify({
        storyboardId,
        sceneIdx,
        ...(extra?.taskKind
          ? { taskKind: extra.taskKind }
          : prev?.taskKind
            ? { taskKind: prev.taskKind }
            : {}),
        ...(taskId != null ? { taskId } : {})
      } satisfies StoryboardVideoModalGenSession),
      sessionScope
    )
    removeScopedSessionItem(STORYBOARD_VIDEO_MODAL_DISMISSED_KEY, sessionScope)
  } catch {
    /* ignore */
  }
}

export function clearStoryboardVideoModalGenSession(sessionScope?: ModalGenSessionScope | null) {
  removeScopedSessionItem(STORYBOARD_VIDEO_MODAL_GEN_SESSION_KEY, sessionScope)
}

export function markStoryboardVideoModalUserDismissed(
  storyboardId: number,
  sessionScope?: ModalGenSessionScope | null
) {
  if (!(typeof window !== 'undefined')) return
  try {
    writeScopedSessionItem(
      STORYBOARD_VIDEO_MODAL_DISMISSED_KEY,
      String(storyboardId),
      sessionScope
    )
    removeScopedSessionItem(STORYBOARD_VIDEO_MODAL_GEN_SESSION_KEY, sessionScope)
  } catch {
    /* ignore */
  }
}

export function clearStoryboardVideoModalUserDismissed(sessionScope?: ModalGenSessionScope | null) {
  removeScopedSessionItem(STORYBOARD_VIDEO_MODAL_DISMISSED_KEY, sessionScope)
}

export function isStoryboardVideoModalUserDismissed(
  storyboardId: number,
  sessionScope?: ModalGenSessionScope | null
): boolean {
  if (!(typeof window !== 'undefined')) return false
  try {
    return (
      readScopedSessionItem(STORYBOARD_VIDEO_MODAL_DISMISSED_KEY, sessionScope) ===
      String(storyboardId)
    )
  } catch {
    return false
  }
}

export function readStoryboardVideoModalGenSession(
  sessionScope?: ModalGenSessionScope | null
): StoryboardVideoModalGenSession | null {
  if (!(typeof window !== 'undefined')) return null
  try {
    const raw = readScopedSessionItem(STORYBOARD_VIDEO_MODAL_GEN_SESSION_KEY, sessionScope)
    if (!raw) return null
    const o = JSON.parse(raw) as Partial<StoryboardVideoModalGenSession>
    const storyboardId = Number(o.storyboardId)
    const sceneIdx = Number(o.sceneIdx)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return null
    if (!Number.isFinite(sceneIdx) || sceneIdx < 0) return null
    const taskKind =
      o.taskKind === 'multi'
        ? 'multi'
        : o.taskKind === 'edge'
          ? 'edge'
          : o.taskKind === 'grid'
            ? 'grid'
            : o.taskKind === 'video-prompt-gen'
              ? 'video-prompt-gen'
              : o.taskKind === 'multi-video-prompt-gen'
                ? 'multi-video-prompt-gen'
                : o.taskKind === 'i2v'
                  ? 'i2v'
                  : undefined
    const taskId =
      o.taskId != null && Number.isFinite(Number(o.taskId)) && Number(o.taskId) > 0
        ? Number(o.taskId)
        : undefined
    return {
      storyboardId,
      sceneIdx,
      ...(taskKind ? { taskKind } : {}),
      ...(taskId != null ? { taskId } : {})
    }
  } catch {
    return null
  }
}
