import { canMigrateLegacyModalGenSession } from '~/utils/liveGenScopeIsolation'

export type ModalGenSessionScope = {
  projectId?: number | null
  episodeId?: number | null
}

/** sessionStorage key：`${baseKey}:${projectId}:${episodeId|0}` */
export function modalGenSessionStorageKey(
  baseKey: string,
  scope?: ModalGenSessionScope | null
): string {
  const pid = scope?.projectId != null ? Number(scope.projectId) : 0
  if (!Number.isFinite(pid) || pid <= 0) return baseKey
  const epRaw = scope?.episodeId
  const ep =
    epRaw === null || epRaw === undefined
      ? 0
      : Number.isFinite(Number(epRaw)) && Number(epRaw) >= 0
        ? Number(epRaw)
        : 0
  return `${baseKey}:${pid}:${ep}`
}

export function modalGenSessionScopeFromStore(store: {
  currentProjectId?: number | null
  currentEpisodeId?: number | null
}): ModalGenSessionScope {
  return {
    projectId: store.currentProjectId,
    episodeId: store.currentEpisodeId
  }
}

/** 从 Pinia liveGen scopeKey（`projectId:episodeId`）解析 session 分桶 */
export function modalGenSessionScopeFromScopeKey(scopeKey: string): ModalGenSessionScope | null {
  const trimmed = String(scopeKey || '').trim()
  if (!trimmed) return null
  const idx = trimmed.indexOf(':')
  if (idx <= 0) return null
  const pid = Number(trimmed.slice(0, idx))
  const ep = Number(trimmed.slice(idx + 1))
  if (!Number.isFinite(pid) || pid <= 0) return null
  if (!Number.isFinite(ep) || ep < 0) return null
  return { projectId: pid, episodeId: ep === 0 ? null : ep }
}

function readSessionRaw(
  scopedKey: string,
  legacyKey: string,
  scope?: ModalGenSessionScope | null
): string | null {
  if (!(typeof window !== 'undefined')) return null
  try {
    const scoped = sessionStorage.getItem(scopedKey)
    if (scoped) return scoped
    if (scopedKey === legacyKey) return null
    const legacy = sessionStorage.getItem(legacyKey)
    if (!legacy) return null
    const targetPid = scope?.projectId != null ? Number(scope.projectId) : 0
    // 禁止把无分桶 legacy 迁到「另一作品」的 scoped key（跨作品 loading 串流）
    if (
      Number.isFinite(targetPid) &&
      targetPid > 0 &&
      !canMigrateLegacyModalGenSession({
        legacyRaw: legacy,
        targetProjectId: targetPid,
        targetEpisodeId: scope?.episodeId
      })
    ) {
      return null
    }
    sessionStorage.setItem(scopedKey, legacy)
    sessionStorage.removeItem(legacyKey)
    return legacy
  } catch {
    return null
  }
}

export function readScopedSessionItem(
  baseKey: string,
  scope?: ModalGenSessionScope | null
): string | null {
  const scopedKey = modalGenSessionStorageKey(baseKey, scope)
  return readSessionRaw(scopedKey, baseKey, scope)
}

export function writeScopedSessionItem(
  baseKey: string,
  value: string,
  scope?: ModalGenSessionScope | null
): void {
  if (!(typeof window !== 'undefined')) return
  try {
    sessionStorage.setItem(modalGenSessionStorageKey(baseKey, scope), value)
    if (scope?.projectId != null && Number(scope.projectId) > 0) {
      sessionStorage.removeItem(baseKey)
    }
  } catch {
    /* ignore */
  }
}

export function removeScopedSessionItem(
  baseKey: string,
  scope?: ModalGenSessionScope | null
): void {
  if (!(typeof window !== 'undefined')) return
  try {
    sessionStorage.removeItem(modalGenSessionStorageKey(baseKey, scope))
    if (!scope?.projectId) {
      sessionStorage.removeItem(baseKey)
    }
  } catch {
    /* ignore */
  }
}
