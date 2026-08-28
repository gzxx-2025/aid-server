'use client'

import type {
  ScriptDetailByProjectRequest,
  ScriptDetailRow,
  ScriptSaveRequest
} from '~/types/business-api'
import { useCreationStore } from '~/stores/creation'
import { userScriptAutoSave, userScriptDetailByProject } from '~/utils/businessApi'

export const STORY_SCRIPT_AUTOSAVE_IDLE_MS = 5_000
export const STORY_SCRIPT_AUTOSAVE_MAX_WAIT_MS = 20_000
export const STORY_SCRIPT_AUTOSAVE_RETRY_MS = [10_000, 20_000, 40_000, 60_000] as const
export const EMPTY_STORY_SCRIPT_CONTENT_HASH =
  'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'

export type StoryScriptSaveStatus = 'saved' | 'dirty' | 'saving' | 'error' | 'conflict'
export type StoryScriptFlushResult = 'clean' | 'saved' | 'error' | 'conflict'

export interface StoryScriptBaseline {
  scriptId: number
  contentHash: string
  originalText: string
  comicVersion: number
}

export interface StoryScriptConflict {
  localText: string
  server: ScriptDetailRow | null
}

export interface StoryScriptPersistenceState {
  status: StoryScriptSaveStatus
  lastSavedAt: number | null
  errorMessage: string | null
  conflict: StoryScriptConflict | null
}

export interface StoryScriptLocalSnapshot {
  content: string
  savedAt: number
}

export interface CoordinatorDependencies {
  autoSave: (body: ScriptSaveRequest) => Promise<ScriptDetailRow>
  loadLatest: (ctx: ScriptDetailByProjectRequest) => Promise<ScriptDetailRow | null>
  now: () => number
}

const LOCAL_SNAPSHOT_PREFIX = 'aid:story-script:unsynced:'
const baselines = new Map<string, StoryScriptBaseline>()
const coordinators = new Map<string, StoryScriptAutoSaveCoordinator>()

export function storyScriptScopeKey(ctx: ScriptDetailByProjectRequest): string {
  return `${Number(ctx.projectId)}:${Number(ctx.episodeId)}`
}

function baselineFromRow(row: ScriptDetailRow | null): StoryScriptBaseline {
  if (!row) {
    return {
      scriptId: 0,
      contentHash: EMPTY_STORY_SCRIPT_CONTENT_HASH,
      originalText: '',
      comicVersion: 0
    }
  }
  return {
    scriptId: Number(row.id) || 0,
    contentHash: String(row.contentHash || ''),
    originalText: String(row.originalText ?? ''),
    comicVersion: Number(row.comicVersion) || 0
  }
}

function syncCurrentCreationStore(
  ctx: ScriptDetailByProjectRequest,
  baseline: StoryScriptBaseline
): void {
  const store = useCreationStore.getState()
  const projectId = Number(store.currentProjectId)
  const episodeId = Number(store.currentEpisodeId ?? (store.currentProjectType === 'movie' ? 0 : NaN))
  if (projectId !== Number(ctx.projectId) || episodeId !== Number(ctx.episodeId)) return
  store.setScriptServerHtmlBaseline(baseline.originalText)
  store.setScriptComicVersion(baseline.comicVersion)
}

function writeBaseline(
  ctx: ScriptDetailByProjectRequest,
  row: ScriptDetailRow | null
): StoryScriptBaseline {
  const baseline = baselineFromRow(row)
  baselines.set(storyScriptScopeKey(ctx), baseline)
  syncCurrentCreationStore(ctx, baseline)
  return baseline
}

export function setStoryScriptServerBaseline(
  ctx: ScriptDetailByProjectRequest,
  row: ScriptDetailRow | null
): StoryScriptBaseline {
  const baseline = writeBaseline(ctx, row)
  coordinators.get(storyScriptScopeKey(ctx))?.acceptServerBaseline(row)
  return baseline
}

export function getStoryScriptServerBaseline(
  ctx: ScriptDetailByProjectRequest
): StoryScriptBaseline {
  return baselines.get(storyScriptScopeKey(ctx)) ?? baselineFromRow(null)
}

export function getStoryScriptWriteBaseline(
  ctx: ScriptDetailByProjectRequest
): Pick<ScriptSaveRequest, 'baseScriptId' | 'baseContentHash'> {
  const baseline = getStoryScriptServerBaseline(ctx)
  if (baseline.scriptId > 0 && !baseline.contentHash) {
    // 兼容尚未返回 contentHash 的旧服务端；新服务端始终携带完整基线。
    return {}
  }
  return {
    baseScriptId: baseline.scriptId,
    baseContentHash: baseline.contentHash || EMPTY_STORY_SCRIPT_CONTENT_HASH
  }
}

function snapshotStorageKey(ctx: ScriptDetailByProjectRequest): string {
  return `${LOCAL_SNAPSHOT_PREFIX}${storyScriptScopeKey(ctx)}`
}

export function readStoryScriptLocalSnapshot(
  ctx: ScriptDetailByProjectRequest
): StoryScriptLocalSnapshot | null {
  if (typeof window === 'undefined') return null
  try {
    const raw = localStorage.getItem(snapshotStorageKey(ctx))
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<StoryScriptLocalSnapshot>
    if (typeof parsed.content !== 'string') return null
    return {
      content: parsed.content,
      savedAt: Number(parsed.savedAt) || 0
    }
  } catch {
    return null
  }
}

export function saveStoryScriptLocalSnapshot(
  ctx: ScriptDetailByProjectRequest,
  content: string,
  savedAt = Date.now()
): void {
  if (typeof window === 'undefined') return
  try {
    localStorage.setItem(snapshotStorageKey(ctx), JSON.stringify({ content, savedAt }))
  } catch {
    // 浏览器禁用或存储空间不足时仍保留内存脏状态。
  }
}

export function clearStoryScriptLocalSnapshot(ctx: ScriptDetailByProjectRequest): void {
  if (typeof window === 'undefined') return
  try {
    localStorage.removeItem(snapshotStorageKey(ctx))
  } catch {
    // ignore
  }
}

export function isStoryScriptConflictError(error: unknown): boolean {
  const err = error as {
    code?: number | string
    response?: { data?: { code?: number | string } }
  }
  return Number(err?.code ?? err?.response?.data?.code) === 409
}

function errorMessage(error: unknown): string {
  const err = error as {
    msg?: string
    message?: string
    response?: { data?: { msg?: string; message?: string } }
  }
  return String(
    err?.msg || err?.response?.data?.msg || err?.message || err?.response?.data?.message || '自动保存失败'
  )
}

export class StoryScriptAutoSaveCoordinator {
  private baseline: StoryScriptBaseline
  private currentText: string
  private dirtySince: number | null = null
  private idleTimer: ReturnType<typeof setTimeout> | null = null
  private maxTimer: ReturnType<typeof setTimeout> | null = null
  private retryTimer: ReturnType<typeof setTimeout> | null = null
  private retryIndex = 0
  private inFlight: Promise<StoryScriptFlushResult> | null = null
  private listeners = new Set<() => void>()
  private state: StoryScriptPersistenceState = {
    status: 'saved',
    lastSavedAt: null,
    errorMessage: null,
    conflict: null
  }

  constructor(
    readonly context: ScriptDetailByProjectRequest,
    private readonly dependencies: CoordinatorDependencies = {
      autoSave: userScriptAutoSave,
      loadLatest: (ctx) => userScriptDetailByProject(ctx, { force: true }),
      now: Date.now
    }
  ) {
    this.baseline = getStoryScriptServerBaseline(context)
    this.currentText = this.baseline.originalText
  }

  getState = (): StoryScriptPersistenceState => this.state

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  private emit(next: Partial<StoryScriptPersistenceState>): void {
    this.state = { ...this.state, ...next }
    this.listeners.forEach((listener) => listener())
  }

  private clearTimer(ref: 'idleTimer' | 'maxTimer' | 'retryTimer'): void {
    const timer = this[ref]
    if (timer) clearTimeout(timer)
    this[ref] = null
  }

  private clearSaveTimers(): void {
    this.clearTimer('idleTimer')
    this.clearTimer('maxTimer')
  }

  private clearAllTimers(): void {
    this.clearSaveTimers()
    this.clearTimer('retryTimer')
  }

  private scheduleSaveTimers(): void {
    if (this.state.status === 'conflict' || this.currentText === this.baseline.originalText) return
    this.clearTimer('idleTimer')
    this.idleTimer = setTimeout(() => {
      this.idleTimer = null
      void this.saveCurrentSnapshot()
    }, STORY_SCRIPT_AUTOSAVE_IDLE_MS)

    if (!this.maxTimer) {
      const elapsed = this.dirtySince == null ? 0 : this.dependencies.now() - this.dirtySince
      const remaining = Math.max(0, STORY_SCRIPT_AUTOSAVE_MAX_WAIT_MS - elapsed)
      this.maxTimer = setTimeout(() => {
        this.maxTimer = null
        void this.saveCurrentSnapshot()
      }, remaining)
    }
  }

  updateContent(content: string, schedule = true): void {
    this.currentText = String(content ?? '')
    if (this.currentText === this.baseline.originalText) {
      this.dirtySince = null
      this.retryIndex = 0
      this.clearAllTimers()
      clearStoryScriptLocalSnapshot(this.context)
      this.emit({ status: 'saved', errorMessage: null, conflict: null })
      return
    }

    if (this.dirtySince == null) this.dirtySince = this.dependencies.now()
    saveStoryScriptLocalSnapshot(this.context, this.currentText, this.dependencies.now())
    if (this.state.status === 'conflict') {
      this.emit({ conflict: { ...this.state.conflict!, localText: this.currentText } })
      return
    }
    if (this.state.status !== 'saving') {
      this.emit({ status: 'dirty', errorMessage: null })
    }
    if (schedule) {
      this.clearTimer('retryTimer')
      this.scheduleSaveTimers()
    }
  }

  acceptServerBaseline(row: ScriptDetailRow | null): void {
    this.baseline = writeBaseline(this.context, row)
    if (this.currentText === this.baseline.originalText) {
      this.dirtySince = null
      this.retryIndex = 0
      this.clearAllTimers()
      clearStoryScriptLocalSnapshot(this.context)
      this.emit({ status: 'saved', errorMessage: null, conflict: null })
    }
  }

  private markSaveSuccess(row: ScriptDetailRow | null, sentText: string): StoryScriptFlushResult {
    this.baseline = writeBaseline(this.context, row)
    this.retryIndex = 0
    this.emit({ lastSavedAt: this.dependencies.now(), errorMessage: null, conflict: null })
    if (this.currentText === sentText && this.currentText === this.baseline.originalText) {
      this.dirtySince = null
      clearStoryScriptLocalSnapshot(this.context)
      this.emit({ status: 'saved' })
      return 'saved'
    }
    if (this.dirtySince == null) this.dirtySince = this.dependencies.now()
    saveStoryScriptLocalSnapshot(this.context, this.currentText, this.dependencies.now())
    this.emit({ status: 'dirty' })
    this.scheduleSaveTimers()
    return 'saved'
  }

  private scheduleRetry(): void {
    this.clearTimer('retryTimer')
    const delay = STORY_SCRIPT_AUTOSAVE_RETRY_MS[
      Math.min(this.retryIndex, STORY_SCRIPT_AUTOSAVE_RETRY_MS.length - 1)
    ]
    this.retryIndex = Math.min(this.retryIndex + 1, STORY_SCRIPT_AUTOSAVE_RETRY_MS.length - 1)
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null
      void this.saveCurrentSnapshot()
    }, delay)
  }

  private async performSave(sentText: string): Promise<StoryScriptFlushResult> {
    try {
      const row = await this.dependencies.autoSave({
        ...this.context,
        originalText: sentText,
        ...getStoryScriptWriteBaseline(this.context)
      })
      return this.markSaveSuccess(row, sentText)
    } catch (error: unknown) {
      if (isStoryScriptConflictError(error)) {
        try {
          const latest = await this.dependencies.loadLatest(this.context)
          const latestText = String(latest?.originalText ?? '')
          if (latestText === sentText) {
            return this.markSaveSuccess(latest, sentText)
          }
          saveStoryScriptLocalSnapshot(this.context, this.currentText, this.dependencies.now())
          this.emit({
            status: 'conflict',
            errorMessage: '内容冲突',
            conflict: { localText: this.currentText, server: latest }
          })
          return 'conflict'
        } catch (loadError: unknown) {
          this.emit({ status: 'error', errorMessage: errorMessage(loadError) })
          this.scheduleRetry()
          return 'error'
        }
      }
      this.emit({ status: 'error', errorMessage: errorMessage(error) })
      saveStoryScriptLocalSnapshot(this.context, this.currentText, this.dependencies.now())
      this.scheduleRetry()
      return 'error'
    }
  }

  private async saveCurrentSnapshot(): Promise<StoryScriptFlushResult> {
    if (this.inFlight) return this.inFlight
    if (this.state.status === 'conflict') return 'conflict'
    if (this.currentText === this.baseline.originalText) return 'clean'

    this.clearAllTimers()
    const sentText = this.currentText
    this.emit({ status: 'saving', errorMessage: null })
    this.inFlight = this.performSave(sentText)
    try {
      return await this.inFlight
    } finally {
      this.inFlight = null
    }
  }

  async flush(): Promise<StoryScriptFlushResult> {
    this.clearSaveTimers()
    if (this.inFlight) await this.inFlight
    if (this.state.status === 'conflict') return 'conflict'
    if (this.currentText === this.baseline.originalText) return 'clean'
    return this.saveCurrentSnapshot()
  }

  async retryNow(): Promise<StoryScriptFlushResult> {
    if (this.state.status === 'conflict') return 'conflict'
    this.clearTimer('retryTimer')
    return this.flush()
  }

  async keepLocalAfterConflict(): Promise<StoryScriptFlushResult> {
    const conflict = this.state.conflict
    if (!conflict) return this.flush()
    this.baseline = writeBaseline(this.context, conflict.server)
    this.currentText = conflict.localText
    this.dirtySince = this.dependencies.now()
    this.emit({ status: 'dirty', errorMessage: null, conflict: null })
    return this.flush()
  }

  useServerAfterConflict(): ScriptDetailRow | null {
    const server = this.state.conflict?.server ?? null
    this.baseline = writeBaseline(this.context, server)
    this.currentText = this.baseline.originalText
    this.dirtySince = null
    this.retryIndex = 0
    this.clearAllTimers()
    clearStoryScriptLocalSnapshot(this.context)
    this.emit({ status: 'saved', errorMessage: null, conflict: null })
    return server
  }
}

export function getStoryScriptAutoSaveCoordinator(
  ctx: ScriptDetailByProjectRequest
): StoryScriptAutoSaveCoordinator {
  const key = storyScriptScopeKey(ctx)
  let coordinator = coordinators.get(key)
  if (!coordinator) {
    coordinator = new StoryScriptAutoSaveCoordinator(ctx)
    coordinators.set(key, coordinator)
  }
  return coordinator
}

export async function flushStoryScriptAutoSave(
  ctx: ScriptDetailByProjectRequest
): Promise<StoryScriptFlushResult> {
  const coordinator = coordinators.get(storyScriptScopeKey(ctx))
  return coordinator ? coordinator.flush() : 'clean'
}

export function queueStoryScriptAutoSave(
  ctx: ScriptDetailByProjectRequest,
  content: string
): void {
  getStoryScriptAutoSaveCoordinator(ctx).updateContent(content, true)
}
