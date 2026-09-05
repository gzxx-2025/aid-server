import type { GlobalSettingData } from '~/types'
import type {
  UserSkillDefinition,
  UserSkillInputResponseRequest,
  UserSkillRuntimeInvokeRequest,
  UserSkillRuntimeResponseMode
} from '~/types/user-skill'
import type { EditorTextSelection } from '~/utils/quill/editorTextSelection'

const STORAGE_PREFIX = 'aid-story-script-agent:v3:'
const LEGACY_STORAGE_PREFIX = 'aid-story-script-agent:v2:'
const MAX_STATE_AGE_MS = 7 * 24 * 60 * 60 * 1000

export interface StoryScriptAgentRuntimeCheckpoint {
  idempotencyKey: string
  invokeRequest: UserSkillRuntimeInvokeRequest
  prompt: string
  references: EditorTextSelection[]
  responseMode: UserSkillRuntimeResponseMode
  runId?: number | null
  generation?: number | null
  afterSeq: number
  waitingInput?: boolean
  /** False once any transient delta may have been missed; canceled output must then stay read-only. */
  partialOutputTrusted?: boolean
}

export interface StoryScriptAgentProjectState {
  version: 3
  projectId: number
  episodeId: number
  ownerId?: string
  skill: UserSkillDefinition
  autoOpen: boolean
  pendingPrompt?: string
  lastRunId?: number | null
  activeRun?: StoryScriptAgentRuntimeCheckpoint | null
  pendingInputResponse?: UserSkillInputResponseRequest | null
  paused?: boolean
  updatedAt: number
}

function storageKey(prefix: string, projectId: number, episodeId: number) {
  return `${prefix}${projectId}:${episodeId}`
}

function currentOwnerId(): string {
  if (typeof window === 'undefined') return ''
  try {
    const raw = localStorage.getItem('user-info')
    if (!raw) return ''
    const user = JSON.parse(raw) as { id?: string | number }
    return String(user.id ?? '').trim()
  } catch {
    return ''
  }
}

function clearStorageKey(key: string) {
  sessionStorage.removeItem(key)
  localStorage.removeItem(key)
}

function clearLegacyState(projectId: number, episodeId: number) {
  if (typeof window === 'undefined') return
  clearStorageKey(storageKey(LEGACY_STORAGE_PREFIX, projectId, episodeId))
  for (const storage of [sessionStorage, localStorage]) {
    for (let index = storage.length - 1; index >= 0; index -= 1) {
      const key = storage.key(index)
      if (key?.startsWith(LEGACY_STORAGE_PREFIX)) storage.removeItem(key)
    }
  }
}

function isValidState(
  value: unknown,
  projectId: number,
  episodeId: number
): value is StoryScriptAgentProjectState {
  if (!value || typeof value !== 'object') return false
  const state = value as Partial<StoryScriptAgentProjectState>
  return (
    state.version === 3 &&
    Number(state.projectId) === projectId &&
    Number(state.episodeId) === episodeId &&
    Boolean(state.skill?.skillCode) &&
    typeof state.updatedAt === 'number'
  )
}

export function readStoryScriptAgentState(
  projectId: number,
  episodeId: number
): StoryScriptAgentProjectState | null {
  if (
    typeof window === 'undefined' ||
    !Number.isFinite(projectId) ||
    projectId <= 0 ||
    !Number.isFinite(episodeId) ||
    episodeId < 0
  ) return null
  try {
    clearLegacyState(projectId, episodeId)
    const key = storageKey(STORAGE_PREFIX, projectId, episodeId)
    const raw = sessionStorage.getItem(key) || localStorage.getItem(key)
    if (!raw) return null
    const parsed = JSON.parse(raw) as unknown
    if (!isValidState(parsed, projectId, episodeId) || Date.now() - parsed.updatedAt > MAX_STATE_AGE_MS) {
      clearStorageKey(key)
      return null
    }
    const ownerId = currentOwnerId()
    if (ownerId && parsed.ownerId && parsed.ownerId !== ownerId) {
      clearStorageKey(key)
      return null
    }
    if (!sessionStorage.getItem(key)) sessionStorage.setItem(key, raw)
    return parsed
  } catch {
    return null
  }
}

export function writeStoryScriptAgentState(state: StoryScriptAgentProjectState): boolean {
  if (typeof window === 'undefined') return false
  try {
    clearLegacyState(state.projectId, state.episodeId)
    const raw = JSON.stringify({
      ...state,
      ownerId: currentOwnerId() || state.ownerId,
      updatedAt: Date.now()
    })
    const key = storageKey(STORAGE_PREFIX, state.projectId, state.episodeId)
    sessionStorage.setItem(key, raw)
    localStorage.setItem(key, raw)
    return true
  } catch {
    return false
  }
}

export function patchStoryScriptAgentState(
  projectId: number,
  episodeId: number,
  patch: Partial<Omit<StoryScriptAgentProjectState, 'version' | 'projectId' | 'episodeId'>>
): StoryScriptAgentProjectState | null {
  const current = readStoryScriptAgentState(projectId, episodeId)
  if (!current) return null
  const next: StoryScriptAgentProjectState = {
    ...current,
    ...patch,
    version: 3,
    projectId,
    episodeId,
    updatedAt: Date.now()
  }
  return writeStoryScriptAgentState(next) ? next : null
}

export function createStoryScriptAgentHandoff(input: {
  projectId: number
  episodeId?: number
  title: string
  prompt: string
  skill: UserSkillDefinition
  globalSetting: GlobalSettingData
}): boolean {
  // title/globalSetting are already persisted with the project. The handoff only carries Runtime intent.
  void input.title
  void input.globalSetting
  return writeStoryScriptAgentState({
    version: 3,
    projectId: input.projectId,
    episodeId: input.episodeId ?? 0,
    skill: input.skill,
    autoOpen: true,
    pendingPrompt: input.prompt,
    activeRun: null,
    pendingInputResponse: null,
    paused: false,
    updatedAt: Date.now()
  })
}

export function createUserSkillClientMessageId(): string {
  const suffix =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `web-${suffix}`.slice(0, 64)
}
