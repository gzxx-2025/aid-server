/**
 * 按 projectId+episodeId 隔离的提取剧本基线 / 忽略指纹。
 * @see docs/superpowers/specs/2026-07-29-script-change-continue-extract-design.md
 */
import {
buildScriptChangeKey,
isMeaningfulScriptChange,
normalizeScriptContent
} from './scriptContentFingerprint'

export type ScriptExtractBaseline = {
  projectId: number
  episodeId: number
  comicVersion: number
  normalizedHash: string
  normalizedLen: number
  /** 规范化全文，供精确 diff（仅本地） */
  normalizedText: string
  savedAt: number
}

export type StorageLike = {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

const BASELINE_KEY = 'aid.scriptExtractBaseline.v1'
const IGNORED_KEY = 'aid.scriptExtractIgnored.v1'

function scopeKey(projectId: number, episodeId: number): string {
  return `${projectId}:${episodeId}`
}

function readMap<T>(storage: StorageLike, key: string): Record<string, T> {
  try {
    const raw = storage.getItem(key)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as unknown
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    return parsed as Record<string, T>
  } catch {
    return {}
  }
}

function writeMap<T>(storage: StorageLike, key: string, map: Record<string, T>): void {
  storage.setItem(key, JSON.stringify(map))
}

export type ScriptExtractBaselineStore = {
  getScriptExtractBaseline: (projectId: number, episodeId: number) => ScriptExtractBaseline | null
  setScriptExtractBaseline: (baseline: ScriptExtractBaseline) => void
  clearScriptExtractBaseline: (projectId: number, episodeId: number) => void
  getIgnoredScriptChangeKey: (projectId: number, episodeId: number) => string | null
  setIgnoredScriptChangeKey: (projectId: number, episodeId: number, changeKey: string) => void
  clearIgnoredScriptChangeKey: (projectId: number, episodeId: number) => void
  buildChangeKey: (comicVersion: number, normalizedHash: string) => string
  recordExtractSuccessBaseline: (input: {
    projectId: number
    episodeId: number
    comicVersion: number
    scriptHtml: string
  }) => void
  ensureColdStartBaselineIfNeeded: (input: {
    projectId: number
    episodeId: number
    comicVersion: number
    scriptHtml: string
    hasExtractedAssets: boolean
  }) => boolean
  shouldPromptScriptChangeExtract: (input: {
    projectId: number
    episodeId: number
    comicVersion: number
    scriptHtml: string
    hasExtractedAssets: boolean
    isExtracting: boolean
  }) => boolean
}

export function createScriptExtractBaselineStore(storage: StorageLike): ScriptExtractBaselineStore {
  function getScriptExtractBaseline(projectId: number, episodeId: number): ScriptExtractBaseline | null {
    const row = readMap<ScriptExtractBaseline>(storage, BASELINE_KEY)[scopeKey(projectId, episodeId)]
    if (!row || typeof row !== 'object') return null
    if (!Number.isFinite(row.comicVersion) || !row.normalizedHash) return null
    return row
  }

  function setScriptExtractBaseline(baseline: ScriptExtractBaseline): void {
    const map = readMap<ScriptExtractBaseline>(storage, BASELINE_KEY)
    map[scopeKey(baseline.projectId, baseline.episodeId)] = baseline
    writeMap(storage, BASELINE_KEY, map)
  }

  function clearScriptExtractBaseline(projectId: number, episodeId: number): void {
    const map = readMap<ScriptExtractBaseline>(storage, BASELINE_KEY)
    delete map[scopeKey(projectId, episodeId)]
    writeMap(storage, BASELINE_KEY, map)
  }

  function getIgnoredScriptChangeKey(projectId: number, episodeId: number): string | null {
    const row = readMap<{ changeKey: string }>(storage, IGNORED_KEY)[scopeKey(projectId, episodeId)]
    return row?.changeKey ? String(row.changeKey) : null
  }

  function setIgnoredScriptChangeKey(projectId: number, episodeId: number, changeKey: string): void {
    const map = readMap<{ changeKey: string; projectId: number; episodeId: number }>(
      storage,
      IGNORED_KEY
    )
    map[scopeKey(projectId, episodeId)] = { projectId, episodeId, changeKey }
    writeMap(storage, IGNORED_KEY, map)
  }

  function clearIgnoredScriptChangeKey(projectId: number, episodeId: number): void {
    const map = readMap<{ changeKey: string }>(storage, IGNORED_KEY)
    delete map[scopeKey(projectId, episodeId)]
    writeMap(storage, IGNORED_KEY, map)
  }

  function recordExtractSuccessBaseline(input: {
    projectId: number
    episodeId: number
    comicVersion: number
    scriptHtml: string
  }): void {
    const n = normalizeScriptContent(input.scriptHtml)
    setScriptExtractBaseline({
      projectId: input.projectId,
      episodeId: input.episodeId,
      comicVersion: Number(input.comicVersion) || 0,
      normalizedHash: n.hash,
      normalizedLen: n.len,
      normalizedText: n.text,
      savedAt: Date.now()
    })
    clearIgnoredScriptChangeKey(input.projectId, input.episodeId)
  }

  function ensureColdStartBaselineIfNeeded(input: {
    projectId: number
    episodeId: number
    comicVersion: number
    scriptHtml: string
    hasExtractedAssets: boolean
  }): boolean {
    if (!input.hasExtractedAssets) return false
    if (getScriptExtractBaseline(input.projectId, input.episodeId)) return false
    recordExtractSuccessBaseline(input)
    return true
  }

  function shouldPromptScriptChangeExtract(input: {
    projectId: number
    episodeId: number
    comicVersion: number
    scriptHtml: string
    hasExtractedAssets: boolean
    isExtracting: boolean
  }): boolean {
    if (input.isExtracting) return false
    if (!input.hasExtractedAssets) return false

    if (
      ensureColdStartBaselineIfNeeded({
        projectId: input.projectId,
        episodeId: input.episodeId,
        comicVersion: input.comicVersion,
        scriptHtml: input.scriptHtml,
        hasExtractedAssets: true
      })
    ) {
      return false
    }

    const baseline = getScriptExtractBaseline(input.projectId, input.episodeId)
    if (!baseline) return false

    const current = normalizeScriptContent(input.scriptHtml)
    const meaningful = isMeaningfulScriptChange(
      {
        comicVersion: baseline.comicVersion,
        normalizedHash: baseline.normalizedHash,
        normalizedLen: baseline.normalizedLen,
        text: baseline.normalizedText
      },
      {
        comicVersion: input.comicVersion,
        normalizedHash: current.hash,
        normalizedLen: current.len,
        text: current.text
      }
    )
    if (!meaningful) return false

    const changeKey = buildScriptChangeKey(input.comicVersion, current.hash)
    if (getIgnoredScriptChangeKey(input.projectId, input.episodeId) === changeKey) return false
    return true
  }

  return {
    getScriptExtractBaseline,
    setScriptExtractBaseline,
    clearScriptExtractBaseline,
    getIgnoredScriptChangeKey,
    setIgnoredScriptChangeKey,
    clearIgnoredScriptChangeKey,
    buildChangeKey: buildScriptChangeKey,
    recordExtractSuccessBaseline,
    ensureColdStartBaselineIfNeeded,
    shouldPromptScriptChangeExtract
  }
}

function browserStorage(): StorageLike | null {
  if (typeof globalThis === 'undefined') return null
  try {
    const ls = (globalThis as { localStorage?: StorageLike }).localStorage
    if (!ls) return null
    return ls
  } catch {
    return null
  }
}

const noopStorage: StorageLike = {
  getItem: () => null,
  setItem: () => {},
  removeItem: () => {}
}

/** 浏览器默认单例；SSR 用 noop */
export const scriptExtractBaselineStore = createScriptExtractBaselineStore(
  browserStorage() ?? noopStorage
)
