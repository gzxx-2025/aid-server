/**
 * 跨作品 liveGen 隔离纯规则：判断扁平 generating 是否相对当前 scope 桶「孤儿」，
 * 以及 sessionStorage legacy 是否允许迁移到当前作品 scoped key。
 */

export function flatStep4LiveGenLooksActive(input: {
  isGeneratingStoryboard?: boolean
  isGeneratingStoryboardImageBatch?: boolean
  isGeneratingStoryboardVideo?: boolean
  storyboardScriptActiveTaskId?: number | null
  storyboardImageBatchActiveTaskId?: number | null
  storyboardImageBatchActiveImageTaskId?: number | null
  storyboardVideoBatchActivePromptTaskId?: number | null
  storyboardVideoBatchActiveVideoTaskId?: number | null
  storyboardPanelImageGenStatusByStoryboardId?: Record<string, string>
  storyboardPanelVideoGenStatusByStoryboardId?: Record<string, string>
  dubbingBatchGeneratingIndices?: number[]
}): boolean {
  if (input.isGeneratingStoryboard) return true
  if (input.isGeneratingStoryboardImageBatch) return true
  if (input.isGeneratingStoryboardVideo) return true
  const ids = [
    input.storyboardScriptActiveTaskId,
    input.storyboardImageBatchActiveTaskId,
    input.storyboardImageBatchActiveImageTaskId,
    input.storyboardVideoBatchActivePromptTaskId,
    input.storyboardVideoBatchActiveVideoTaskId
  ]
  if (ids.some((id) => Number(id) > 0)) return true
  if (
    Object.values(input.storyboardPanelImageGenStatusByStoryboardId || {}).some(
      (s) => s === 'generating'
    )
  ) {
    return true
  }
  if (
    Object.values(input.storyboardPanelVideoGenStatusByStoryboardId || {}).some(
      (s) => s === 'generating'
    )
  ) {
    return true
  }
  if ((input.dubbingBatchGeneratingIndices?.length ?? 0) > 0) return true
  return false
}

/**
 * 当前作品 scope 桶 idle，但扁平字段仍显示 generating → 跨作品/跨集残留，应冲刷为空快照。
 * 同作品运行中会先 sync 进桶，因此「扁平有、桶无」不应出现于正常同 scope 路径。
 */
export function shouldClearOrphanStep4FlatLiveGen(input: {
  scopeHasLiveWork: boolean
  flatLooksActive: boolean
}): boolean {
  return !input.scopeHasLiveWork && input.flatLooksActive
}

function normalizePositiveId(raw: unknown): number | null {
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

function normalizeEpisodeId(raw: unknown): number | null {
  if (raw === null || raw === undefined || raw === '') return null
  const n = Number(raw)
  return Number.isFinite(n) && n >= 0 ? n : null
}

/**
 * 解析 liveGen 用的 projectId/episodeId。
 *
 * 铁律（与切作品/切集时序一致）：
 * - store 已切、route 未齐 → 优先 store（避免旧 route 灌错桶）
 * - store 尚未有值 → 回退 route（刷新首屏在 shell sync 前的兜底）
 * 刷新正常路径：CreateFlowShell.syncProjectContextFromRoute 会先把 route 写入 store，此后二者一致。
 */
export function resolveLiveGenScopeIdsPreferStore(input: {
  storeProjectId?: number | null
  routeProjectId?: number | null
  storeEpisodeId?: number | null
  routeEpisodeId?: number | null
}): { projectId: number | null; episodeId: number | null } {
  const storePid = normalizePositiveId(input.storeProjectId)
  const routePid = normalizePositiveId(input.routeProjectId)
  const projectId = storePid ?? routePid

  const storeEp = normalizeEpisodeId(input.storeEpisodeId)
  const routeEp = normalizeEpisodeId(input.routeEpisodeId)

  if (storePid != null && routePid != null && storePid !== routePid) {
    // 跨作品分歧：整份上下文以 store 为准
    return { projectId: storePid, episodeId: storeEp }
  }

  if (
    storePid != null &&
    storeEp != null &&
    routeEp != null &&
    Number(storeEp) !== Number(routeEp)
  ) {
    // 同作品切集分歧：集数以 store 为准
    return { projectId, episodeId: storeEp }
  }

  return {
    projectId,
    episodeId: storeEp ?? routeEp
  }
}

/** store/route 作品或集数是否处于「已切未齐」分歧（此时禁止用旧 route 扳回 store） */
export function isLiveGenContextDiverged(input: {
  storeProjectId?: number | null
  routeProjectId?: number | null
  storeEpisodeId?: number | null
  routeEpisodeId?: number | null
}): boolean {
  const storePid = normalizePositiveId(input.storeProjectId)
  const routePid = normalizePositiveId(input.routeProjectId)
  if (storePid != null && routePid != null && storePid !== routePid) return true
  const storeEp = normalizeEpisodeId(input.storeEpisodeId)
  const routeEp = normalizeEpisodeId(input.routeEpisodeId)
  if (
    storePid != null &&
    (routePid == null || routePid === storePid) &&
    storeEp != null &&
    routeEp != null &&
    Number(storeEp) !== Number(routeEp)
  ) {
    return true
  }
  return false
}

/** 解析 liveGen scopeKey / session 内 scopeKey 为 projectId */
export function projectIdFromLiveGenScopeKey(scopeKey: unknown): number | null {
  const raw = String(scopeKey ?? '').trim()
  if (!raw) return null
  const idx = raw.indexOf(':')
  const pid = Number(idx > 0 ? raw.slice(0, idx) : raw)
  return Number.isFinite(pid) && pid > 0 ? pid : null
}

/**
 * legacy（无 project 分桶）session 仅当正文声明的 scope/project 与目标作品（及剧集）一致时，
 * 才允许迁移到 `${base}:${projectId}:${episodeId}`，避免作品 A 的 session 灌进作品 B。
 */
export function canMigrateLegacyModalGenSession(input: {
  legacyRaw: string
  targetProjectId: number
  targetEpisodeId?: number | null
}): boolean {
  const targetPid = Number(input.targetProjectId)
  if (!Number.isFinite(targetPid) || targetPid <= 0) return false
  const raw = String(input.legacyRaw ?? '').trim()
  if (!raw) return false
  try {
    const parsed = JSON.parse(raw) as { scopeKey?: unknown; projectId?: unknown }
    const scopeKey = String(parsed?.scopeKey ?? '').trim()
    if (scopeKey) {
      const fromScope = projectIdFromLiveGenScopeKey(scopeKey)
      if (fromScope == null || fromScope !== targetPid) return false
      if (input.targetEpisodeId !== undefined) {
        const targetKey = `${targetPid}:${
          input.targetEpisodeId == null ? 0 : Number(input.targetEpisodeId) || 0
        }`
        return modalGenSessionMatchesLiveGenScope(scopeKey, targetKey)
      }
      return true
    }
    const fromField = Number(parsed?.projectId)
    if (Number.isFinite(fromField) && fromField > 0) return fromField === targetPid
    // 无作品标识的极旧数据：禁止自动迁移到任意新作品 scoped key
    return false
  } catch {
    return false
  }
}

/** session.scopeKey 与当前 store scope 是否同一作品（允许 episode null/0 别名） */
export function modalGenSessionMatchesLiveGenScope(
  sessionScopeKey: unknown,
  currentScopeKey: string
): boolean {
  const sessionPid = projectIdFromLiveGenScopeKey(sessionScopeKey)
  const currentPid = projectIdFromLiveGenScopeKey(currentScopeKey)
  if (sessionPid == null || currentPid == null) return false
  if (sessionPid !== currentPid) return false
  const normalize = (k: string) => String(k || '').trim().replace(/:null$/, ':0')
  return normalize(String(sessionScopeKey)) === normalize(currentScopeKey)
}

function normalizeLiveGenScopeKey(scopeKey: unknown): string {
  return String(scopeKey || '')
    .trim()
    .replace(/:null$/, ':0')
}

/**
 * 弹窗 SSE 跟随锁键：`${liveGenScopeKey}::${editorScopeKey}`。
 * editorScopeKey 仅为索引型（character-0），必须带作品/集桶，否则跨项目同名键会互挡 restore。
 */
export function buildModalFollowLockKey(
  liveGenScopeKey: string,
  editorScopeKey: string
): string {
  const live = normalizeLiveGenScopeKey(liveGenScopeKey)
  const editor = String(editorScopeKey || '').trim()
  if (!live || !editor) return ''
  return `${live}::${editor}`
}

/**
 * 切作品/切集后：清掉不属于当前 liveGen scope 的跟随锁（含未分桶旧键）。
 * 返回清除条数。
 */
export function clearModalFollowLocksNotMatchingLiveGenScope(
  locks: Set<string>,
  liveGenScopeKey: string
): number {
  const liveN = normalizeLiveGenScopeKey(liveGenScopeKey)
  if (!liveN) return 0
  let cleared = 0
  for (const key of [...locks]) {
    const idx = key.indexOf('::')
    if (idx <= 0) {
      locks.delete(key)
      cleared++
      continue
    }
    const lockLive = normalizeLiveGenScopeKey(key.slice(0, idx))
    if (lockLive !== liveN) {
      locks.delete(key)
      cleared++
    }
  }
  return cleared
}

/**
 * 后台 follow 的 onProgress 是否允许写弹窗画布 loading 文案。
 * Pinia 快照仍应按任务归属 scope 更新；此处只约束可见 UI，避免多任务/跨作品串字。
 */
export function shouldApplyModalTaskProgressToCanvas(input: {
  taskLiveGenScopeKey: string
  currentLiveGenScopeKey: string
  taskEditorScopeKey: string
  currentEditorScopeKey: string
  modalOpen?: boolean
}): boolean {
  if (input.modalOpen === false) return false
  const taskEditor = String(input.taskEditorScopeKey || '').trim()
  const currentEditor = String(input.currentEditorScopeKey || '').trim()
  if (!taskEditor || taskEditor !== currentEditor) return false
  return modalGenSessionMatchesLiveGenScope(
    input.taskLiveGenScopeKey,
    input.currentLiveGenScopeKey
  )
}
