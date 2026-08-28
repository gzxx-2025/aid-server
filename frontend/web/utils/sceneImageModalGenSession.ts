import {
  type ModalGenSessionScope,
  readScopedSessionItem,
  removeScopedSessionItem,
  writeScopedSessionItem
} from '~/utils/modalGenSessionScope'

export const SCENE_IMAGE_MODAL_GEN_SESSION_KEY = 'scene-image-modal-gen-session'
export const SCENE_IMAGE_MODAL_DISMISSED_KEY = 'scene-image-modal-user-dismissed'

export type SceneImageModalGenSession = {
  editorScopeKey: string
  sceneIdx: number
  taskKind?: string
  imageIdx?: number
  taskId?: number
}

export function persistSceneImageModalGenSession(
  editorScopeKey: string,
  sceneIdx: number,
  extra?: { taskKind?: string; imageIdx?: number; taskId?: number },
  sessionScope?: ModalGenSessionScope | null
) {
  if (!(typeof window !== 'undefined')) return
  const scope = String(editorScopeKey || '').trim()
  if (!scope) return
  try {
    writeScopedSessionItem(
      SCENE_IMAGE_MODAL_GEN_SESSION_KEY,
      JSON.stringify({
        editorScopeKey: scope,
        sceneIdx,
        ...(extra?.taskKind ? { taskKind: extra.taskKind } : {}),
        ...(extra?.imageIdx != null && Number.isFinite(Number(extra.imageIdx))
          ? { imageIdx: Number(extra.imageIdx) }
          : {}),
        ...(extra?.taskId != null && Number.isFinite(Number(extra.taskId))
          ? { taskId: Number(extra.taskId) }
          : {})
      } satisfies SceneImageModalGenSession),
      sessionScope
    )
    removeScopedSessionItem(SCENE_IMAGE_MODAL_DISMISSED_KEY, sessionScope)
  } catch {
    /* ignore */
  }
}

export function clearSceneImageModalGenSession(sessionScope?: ModalGenSessionScope | null) {
  removeScopedSessionItem(SCENE_IMAGE_MODAL_GEN_SESSION_KEY, sessionScope)
}

export function markSceneImageModalUserDismissed(
  editorScopeKey: string,
  sessionScope?: ModalGenSessionScope | null
) {
  if (!(typeof window !== 'undefined')) return
  const scope = String(editorScopeKey || '').trim()
  if (!scope) return
  try {
    writeScopedSessionItem(SCENE_IMAGE_MODAL_DISMISSED_KEY, scope, sessionScope)
    removeScopedSessionItem(SCENE_IMAGE_MODAL_GEN_SESSION_KEY, sessionScope)
  } catch {
    /* ignore */
  }
}

export function clearSceneImageModalUserDismissed(sessionScope?: ModalGenSessionScope | null) {
  removeScopedSessionItem(SCENE_IMAGE_MODAL_DISMISSED_KEY, sessionScope)
}

export function isSceneImageModalUserDismissed(
  editorScopeKey: string,
  sessionScope?: ModalGenSessionScope | null
): boolean {
  if (!(typeof window !== 'undefined')) return false
  const scope = String(editorScopeKey || '').trim()
  if (!scope) return false
  try {
    return readScopedSessionItem(SCENE_IMAGE_MODAL_DISMISSED_KEY, sessionScope) === scope
  } catch {
    return false
  }
}

export function readSceneImageModalGenSession(
  sessionScope?: ModalGenSessionScope | null
): SceneImageModalGenSession | null {
  if (!(typeof window !== 'undefined')) return null
  try {
    const raw = readScopedSessionItem(SCENE_IMAGE_MODAL_GEN_SESSION_KEY, sessionScope)
    if (!raw) return null
    const o = JSON.parse(raw) as Partial<SceneImageModalGenSession>
    const editorScopeKey = String(o.editorScopeKey || '').trim()
    const sceneIdx = Number(o.sceneIdx)
    if (!editorScopeKey) return null
    if (!Number.isFinite(sceneIdx) || sceneIdx < 0) return null
    const imageIdx =
      o.imageIdx != null && Number.isFinite(Number(o.imageIdx)) ? Number(o.imageIdx) : undefined
    const taskId =
      o.taskId != null && Number.isFinite(Number(o.taskId)) ? Number(o.taskId) : undefined
    return {
      editorScopeKey,
      sceneIdx,
      ...(o.taskKind ? { taskKind: String(o.taskKind) } : {}),
      ...(imageIdx != null ? { imageIdx } : {}),
      ...(taskId != null ? { taskId } : {})
    }
  } catch {
    return null
  }
}
