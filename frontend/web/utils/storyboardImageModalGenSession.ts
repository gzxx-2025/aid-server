import {
  type ModalGenSessionScope,
  modalGenSessionScopeFromScopeKey,
  readScopedSessionItem,
  removeScopedSessionItem,
  writeScopedSessionItem
} from '~/utils/modalGenSessionScope'
import { modalGenSessionMatchesLiveGenScope } from '~/utils/liveGenScopeIsolation'

export const STORYBOARD_IMAGE_MODAL_GEN_SESSION_KEY = 'storyboard-image-modal-gen-session'
export const STORYBOARD_IMAGE_MODAL_DISMISSED_KEY = 'storyboard-image-modal-user-dismissed'

export type ModalImageGenSessionTab =
  | 'generate'
  | 'dialogue'
  | 'upscale'
  | 'multiangle'
  | 'ninegrid'

export type ModalImageGenSession = {
  storyboardId: number
  sceneIdx: number
  scopeKey?: string
  tab?: ModalImageGenSessionTab
  imageIdx?: number
  taskId?: number
}

function resolveSessionScope(
  scopeKey?: string,
  sessionScope?: ModalGenSessionScope | null
): ModalGenSessionScope | null | undefined {
  if (sessionScope?.projectId != null && Number(sessionScope.projectId) > 0) {
    return sessionScope
  }
  return modalGenSessionScopeFromScopeKey(scopeKey ?? '') ?? sessionScope
}

export function persistModalImageGenSession(
  storyboardId: number,
  sceneIdx: number,
  scopeKey?: string,
  extra?: {
    tab?: ModalImageGenSessionTab
    imageIdx?: number
    taskId?: number
  },
  sessionScope?: ModalGenSessionScope | null
) {
  if (!(typeof window !== 'undefined')) return
  const resolvedScope = resolveSessionScope(scopeKey, sessionScope)
  try {
    const prev = readModalImageGenSession(resolvedScope)
    const taskIdRaw = extra?.taskId ?? prev?.taskId
    const taskId =
      taskIdRaw != null && Number.isFinite(Number(taskIdRaw)) && Number(taskIdRaw) > 0
        ? Number(taskIdRaw)
        : undefined
    writeScopedSessionItem(
      STORYBOARD_IMAGE_MODAL_GEN_SESSION_KEY,
      JSON.stringify({
        storyboardId,
        sceneIdx,
        scopeKey: scopeKey ?? prev?.scopeKey ?? '',
        ...(extra?.tab ? { tab: extra.tab } : prev?.tab ? { tab: prev.tab } : {}),
        ...(extra?.imageIdx != null && Number.isFinite(extra.imageIdx)
          ? { imageIdx: extra.imageIdx }
          : prev?.imageIdx != null
            ? { imageIdx: prev.imageIdx }
            : {}),
        ...(taskId != null ? { taskId } : {})
      } satisfies ModalImageGenSession),
      resolvedScope
    )
    removeScopedSessionItem(STORYBOARD_IMAGE_MODAL_DISMISSED_KEY, resolvedScope)
  } catch {
    /* ignore */
  }
}

export function clearModalImageGenSession(sessionScope?: ModalGenSessionScope | null) {
  removeScopedSessionItem(STORYBOARD_IMAGE_MODAL_GEN_SESSION_KEY, sessionScope)
}

export function markModalImageGenUserDismissed(
  storyboardId: number,
  sessionScope?: ModalGenSessionScope | null
) {
  if (!(typeof window !== 'undefined')) return
  try {
    writeScopedSessionItem(
      STORYBOARD_IMAGE_MODAL_DISMISSED_KEY,
      String(storyboardId),
      sessionScope
    )
    removeScopedSessionItem(STORYBOARD_IMAGE_MODAL_GEN_SESSION_KEY, sessionScope)
  } catch {
    /* ignore */
  }
}

export function clearModalImageGenUserDismissed(sessionScope?: ModalGenSessionScope | null) {
  removeScopedSessionItem(STORYBOARD_IMAGE_MODAL_DISMISSED_KEY, sessionScope)
}

export function isModalImageGenUserDismissed(
  storyboardId: number,
  sessionScope?: ModalGenSessionScope | null
): boolean {
  if (!(typeof window !== 'undefined')) return false
  try {
    return (
      readScopedSessionItem(STORYBOARD_IMAGE_MODAL_DISMISSED_KEY, sessionScope) ===
      String(storyboardId)
    )
  } catch {
    return false
  }
}

export function isModalImageGenSessionActive(
  storyboardId?: number | null,
  sessionScope?: ModalGenSessionScope | null
): boolean {
  if (!(typeof window !== 'undefined')) return false
  const session = readModalImageGenSession(sessionScope)
  if (!session) return false
  // session 正文含 scopeKey 时，必须与当前作品 scope 一致，防止跨作品误亮 loading
  if (session.scopeKey && sessionScope?.projectId != null) {
    const currentKey = `${Number(sessionScope.projectId)}:${
      sessionScope.episodeId == null ? 0 : Number(sessionScope.episodeId) || 0
    }`
    if (!modalGenSessionMatchesLiveGenScope(session.scopeKey, currentKey)) {
      return false
    }
  }
  if (storyboardId != null) {
    const sid = Number(storyboardId)
    if (!Number.isFinite(sid) || sid <= 0) return false
    if (session.storyboardId !== sid) return false
    return !isModalImageGenUserDismissed(sid, sessionScope)
  }
  return !isModalImageGenUserDismissed(session.storyboardId, sessionScope)
}

/** @deprecated 弹窗生图已与分镜列表 loading 联动，请改用 syncModalPanelLoadingForActiveSession */
export function clearModalPanelLoadingForActiveSession(
  clearStatus: (storyboardId: number) => void,
  sessionScope?: ModalGenSessionScope | null
) {
  if (!(typeof window !== 'undefined')) return
  const session = readModalImageGenSession(sessionScope)
  if (!session || isModalImageGenUserDismissed(session.storyboardId, sessionScope)) return
  clearStatus(session.storyboardId)
}

/** 刷新后恢复分镜列表卡片 loading（与编辑分镜图弹窗 session 同步） */
export function syncModalPanelLoadingForActiveSession(
  syncStatus: (storyboardId: number) => void,
  sessionScope?: ModalGenSessionScope | null
) {
  if (!(typeof window !== 'undefined')) return
  const session = readModalImageGenSession(sessionScope)
  if (!session || isModalImageGenUserDismissed(session.storyboardId, sessionScope)) return
  syncStatus(session.storyboardId)
}

export function readModalImageGenSession(
  sessionScope?: ModalGenSessionScope | null
): ModalImageGenSession | null {
  if (!(typeof window !== 'undefined')) return null
  try {
    const raw = readScopedSessionItem(STORYBOARD_IMAGE_MODAL_GEN_SESSION_KEY, sessionScope)
    if (!raw) return null
    const o = JSON.parse(raw) as Partial<ModalImageGenSession>
    const storyboardId = Number(o.storyboardId)
    const sceneIdx = Number(o.sceneIdx)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return null
    if (!Number.isFinite(sceneIdx) || sceneIdx < 0) return null
    const tab =
      o.tab === 'dialogue'
        ? 'dialogue'
        : o.tab === 'generate'
          ? 'generate'
          : o.tab === 'upscale'
            ? 'upscale'
            : o.tab === 'multiangle'
              ? 'multiangle'
              : o.tab === 'ninegrid'
                ? 'ninegrid'
                : undefined
    const imageIdx =
      o.imageIdx != null && Number.isFinite(Number(o.imageIdx)) ? Number(o.imageIdx) : undefined
    const taskId =
      o.taskId != null && Number.isFinite(Number(o.taskId)) && Number(o.taskId) > 0
        ? Number(o.taskId)
        : undefined
    return {
      storyboardId,
      sceneIdx,
      ...(o.scopeKey ? { scopeKey: String(o.scopeKey) } : {}),
      ...(tab ? { tab } : {}),
      ...(imageIdx != null ? { imageIdx } : {}),
      ...(taskId != null ? { taskId } : {})
    }
  } catch {
    return null
  }
}
