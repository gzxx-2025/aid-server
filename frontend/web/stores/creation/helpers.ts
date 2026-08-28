import {
liveGenScopeKeyFromIds,
type ExtractModelCodesMap,
type ExtractUiScopeSnapshot,
type OptionalModelCodesScopeSnapshot,
type StoryboardVideoSettingsScopeSnapshot
} from './types'

export function emptyExtractUiScopeSnapshot(): ExtractUiScopeSnapshot {
  return {
    isExtractingAssets: false,
    extractingStage: 'scene',
    extractingStages: { scene: false, character: false, prop: false },
    extractingTaskProgress: {
      percent: 0,
      stepTitle: '',
      message: '',
      stepIndex: null,
      stepTotal: null
    }
  }
}

export function snapshotExtractUiFromStore(store: {
  isExtractingAssets: boolean
  extractingStage: 'scene' | 'character' | 'prop'
  extractingStages: { scene: boolean; character: boolean; prop: boolean }
  extractingTaskProgress: ExtractUiScopeSnapshot['extractingTaskProgress']
}): ExtractUiScopeSnapshot {
  return {
    isExtractingAssets: !!store.isExtractingAssets,
    extractingStage: store.extractingStage,
    extractingStages: { ...store.extractingStages },
    extractingTaskProgress: { ...store.extractingTaskProgress }
  }
}

export function applyExtractUiSnapshotToStore(
  store: {
    isExtractingAssets: boolean
    extractingStage: 'scene' | 'character' | 'prop'
    extractingStages: { scene: boolean; character: boolean; prop: boolean }
    extractingTaskProgress: ExtractUiScopeSnapshot['extractingTaskProgress']
  },
  snap: ExtractUiScopeSnapshot | null | undefined
) {
  const s = snap ?? emptyExtractUiScopeSnapshot()
  store.isExtractingAssets = s.isExtractingAssets
  store.extractingStage = s.extractingStage
  store.extractingStages = { ...s.extractingStages }
  store.extractingTaskProgress = { ...s.extractingTaskProgress }
}

export function emptyStoryboardVideoSettingsScopeSnapshot(): StoryboardVideoSettingsScopeSnapshot {
  return {
    agentId: '',
    agentName: '',
    agentDesc: '',
    agentThumbnail: '',
    videoModel: '',
    videoPromptModelCode: '',
    aspectRatio: '16:9',
    resolution: '720p',
    durationSeconds: undefined,
    soundEffects: 'with-sound'
  }
}

export function normalizeStoryboardVideoSettingsScopeSnapshot(
  raw: Partial<StoryboardVideoSettingsScopeSnapshot> | null | undefined
): StoryboardVideoSettingsScopeSnapshot {
  const empty = emptyStoryboardVideoSettingsScopeSnapshot()
  if (!raw) return empty
  const ar = String(raw.aspectRatio ?? empty.aspectRatio)
  const aspectRatio =
    ar === '16:9' || ar === '9:16' || ar === '4:3' || ar === '1:1' ? ar : empty.aspectRatio
  const resolution =
    String(raw.resolution ?? empty.resolution)
      .trim()
      .toLowerCase() || empty.resolution
  const durationRaw = Number(raw.durationSeconds)
  const durationSeconds =
    Number.isFinite(durationRaw) && durationRaw > 0 ? Math.floor(durationRaw) : undefined
  const se = String(raw.soundEffects ?? empty.soundEffects)
  const soundEffects = se === 'with-sound' ? 'with-sound' : 'none'
  return {
    agentId: String(raw.agentId ?? '').trim(),
    agentName: String(raw.agentName ?? '').trim(),
    agentDesc: String(raw.agentDesc ?? '').trim(),
    agentThumbnail: String(raw.agentThumbnail ?? '').trim(),
    videoModel: String(raw.videoModel ?? '').trim(),
    videoPromptModelCode: String(raw.videoPromptModelCode ?? '').trim(),
    aspectRatio,
    resolution,
    durationSeconds,
    soundEffects
  }
}

export function snapshotStoryboardVideoSettingsFromStore(store: {
  storyboardVideoAgent: { id: string; name: string; desc: string; thumbnail?: string }
  storyboardVideoGenerateSettings: {
    agentId?: string
    videoModel?: string
    videoPromptModelCode?: string
    aspectRatio?: StoryboardVideoSettingsScopeSnapshot['aspectRatio']
    resolution?: StoryboardVideoSettingsScopeSnapshot['resolution']
    durationSeconds?: number
    soundEffects?: StoryboardVideoSettingsScopeSnapshot['soundEffects']
  }
}): StoryboardVideoSettingsScopeSnapshot {
  return normalizeStoryboardVideoSettingsScopeSnapshot({
    agentId: String(
      store.storyboardVideoGenerateSettings?.agentId ?? store.storyboardVideoAgent?.id ?? ''
    ).trim(),
    agentName: String(store.storyboardVideoAgent?.name ?? '').trim(),
    agentDesc: String(store.storyboardVideoAgent?.desc ?? '').trim(),
    agentThumbnail: String(store.storyboardVideoAgent?.thumbnail ?? '').trim(),
    videoModel: String(store.storyboardVideoGenerateSettings?.videoModel ?? '').trim(),
    videoPromptModelCode: String(
      store.storyboardVideoGenerateSettings?.videoPromptModelCode ?? ''
    ).trim(),
    aspectRatio: store.storyboardVideoGenerateSettings?.aspectRatio,
    resolution: store.storyboardVideoGenerateSettings?.resolution,
    durationSeconds: store.storyboardVideoGenerateSettings?.durationSeconds,
    soundEffects: store.storyboardVideoGenerateSettings?.soundEffects
  })
}

export function applyStoryboardVideoSettingsToStore(
  store: {
    storyboardVideoAgent: { id: string; name: string; desc: string; thumbnail: string }
    storyboardVideoGenerateSettings: {
      agentId: string
      videoModel: string
      videoPromptModelCode: string
      aspectRatio: StoryboardVideoSettingsScopeSnapshot['aspectRatio']
      resolution: StoryboardVideoSettingsScopeSnapshot['resolution']
      durationSeconds?: number
      soundEffects: StoryboardVideoSettingsScopeSnapshot['soundEffects']
    }
  },
  snap: StoryboardVideoSettingsScopeSnapshot
) {
  const normalized = normalizeStoryboardVideoSettingsScopeSnapshot(snap)
  store.storyboardVideoAgent = {
    id: normalized.agentId,
    name: normalized.agentName,
    desc: normalized.agentDesc,
    thumbnail: normalized.agentThumbnail
  }
  store.storyboardVideoGenerateSettings = {
    agentId: normalized.agentId,
    videoModel: normalized.videoModel,
    videoPromptModelCode: normalized.videoPromptModelCode,
    aspectRatio: normalized.aspectRatio,
    resolution: normalized.resolution,
    durationSeconds: normalized.durationSeconds,
    soundEffects: normalized.soundEffects
  }
}

export function migrateStoryboardVideoSettingsFromPersist(store: {
  currentProjectId: number | null
  currentEpisodeId: number | null
  storyboardVideoSettingsByScope?: Record<string, StoryboardVideoSettingsScopeSnapshot>
  storyboardVideoAgent?: { id: string; name: string; desc: string; thumbnail?: string }
  /** zustand 状态里的生成设置不含 agentName 等展示字段，故用 Partial 声明（原实现即整体覆写） */
  storyboardVideoGenerateSettings?: Partial<StoryboardVideoSettingsScopeSnapshot>
}) {
  if (
    !store.storyboardVideoSettingsByScope ||
    typeof store.storyboardVideoSettingsByScope !== 'object'
  ) {
    store.storyboardVideoSettingsByScope = {}
  }
  const key = liveGenScopeKeyFromIds(store.currentProjectId, store.currentEpisodeId)
  applyStoryboardVideoSettingsToStore(
    store as Parameters<typeof applyStoryboardVideoSettingsToStore>[0],
    store.storyboardVideoSettingsByScope[key]
      ? normalizeStoryboardVideoSettingsScopeSnapshot(store.storyboardVideoSettingsByScope[key])
      : emptyStoryboardVideoSettingsScopeSnapshot()
  )
}

export function emptyExtractModelCodes(): ExtractModelCodesMap {
  return { scene: '', character: '', prop: '' }
}

export function emptyOptionalModelCodesScopeSnapshot(): OptionalModelCodesScopeSnapshot {
  return {
    extractText: emptyExtractModelCodes(),
    extractImage: emptyExtractModelCodes(),
    storyboardScriptModelCode: '',
    storyboardStylistModelCode: '',
    storyboardScriptAgentId: '',
    storyboardScriptAgentName: '',
    storyboardScriptAgentDesc: '',
    storyboardScriptAgentThumbnail: ''
  }
}

export function normalizeOptionalModelCodesScopeSnapshot(
  raw: Partial<OptionalModelCodesScopeSnapshot> | null | undefined
): OptionalModelCodesScopeSnapshot {
  const empty = emptyOptionalModelCodesScopeSnapshot()
  if (!raw) return empty
  const legacyExtract = raw.extract ?? emptyExtractModelCodes()
  return {
    extractText: {
      scene: String(raw.extractText?.scene ?? legacyExtract.scene ?? '').trim(),
      character: String(raw.extractText?.character ?? legacyExtract.character ?? '').trim(),
      prop: String(raw.extractText?.prop ?? legacyExtract.prop ?? '').trim()
    },
    extractImage: {
      scene: String(raw.extractImage?.scene ?? '').trim(),
      character: String(raw.extractImage?.character ?? '').trim(),
      prop: String(raw.extractImage?.prop ?? '').trim()
    },
    storyboardScriptModelCode: String(raw.storyboardScriptModelCode ?? '').trim(),
    storyboardStylistModelCode: String(raw.storyboardStylistModelCode ?? '').trim(),
    storyboardScriptAgentId: String(raw.storyboardScriptAgentId ?? '').trim(),
    storyboardScriptAgentName: String(raw.storyboardScriptAgentName ?? '').trim(),
    storyboardScriptAgentDesc: String(raw.storyboardScriptAgentDesc ?? '').trim(),
    storyboardScriptAgentThumbnail: String(raw.storyboardScriptAgentThumbnail ?? '').trim()
  }
}

export function snapshotOptionalModelCodesFromStore(store: {
  extractModelCodes: ExtractModelCodesMap
  extractImageModelCodes: ExtractModelCodesMap
  storyboardAgent: { id: string; name: string; desc: string; thumbnail?: string }
  storyboardGenerateSettings: { agentId?: string; modelCode?: string }
  storyboardStylistGenerateSettings: { modelCode?: string }
}): OptionalModelCodesScopeSnapshot {
  return {
    extractText: {
      scene: String(store.extractModelCodes?.scene ?? '').trim(),
      character: String(store.extractModelCodes?.character ?? '').trim(),
      prop: String(store.extractModelCodes?.prop ?? '').trim()
    },
    extractImage: {
      scene: String(store.extractImageModelCodes?.scene ?? '').trim(),
      character: String(store.extractImageModelCodes?.character ?? '').trim(),
      prop: String(store.extractImageModelCodes?.prop ?? '').trim()
    },
    storyboardScriptModelCode: String(store.storyboardGenerateSettings?.modelCode ?? '').trim(),
    storyboardStylistModelCode: String(
      store.storyboardStylistGenerateSettings?.modelCode ?? ''
    ).trim(),
    storyboardScriptAgentId: String(
      store.storyboardGenerateSettings?.agentId ?? store.storyboardAgent?.id ?? ''
    ).trim(),
    storyboardScriptAgentName: String(store.storyboardAgent?.name ?? '').trim(),
    storyboardScriptAgentDesc: String(store.storyboardAgent?.desc ?? '').trim(),
    storyboardScriptAgentThumbnail: String(store.storyboardAgent?.thumbnail ?? '').trim()
  }
}

export type OptionalModelCodesStoreSlice = {
  extractModelCodes: ExtractModelCodesMap
  extractImageModelCodes: ExtractModelCodesMap
  storyboardAgent: { id: string; name: string; desc: string; thumbnail: string }
  storyboardGenerateSettings: { agentId: string; modelCode: string; shotDensity?: string }
  storyboardStylistGenerateSettings: { modelCode: string; agentId?: string }
}

export function applyOptionalModelCodesToStore(
  store: OptionalModelCodesStoreSlice,
  snap: OptionalModelCodesScopeSnapshot
) {
  const normalized = normalizeOptionalModelCodesScopeSnapshot(snap)
  store.extractModelCodes = { ...normalized.extractText }
  store.extractImageModelCodes = { ...normalized.extractImage }
  store.storyboardGenerateSettings.modelCode = normalized.storyboardScriptModelCode
  store.storyboardStylistGenerateSettings.modelCode = normalized.storyboardStylistModelCode
  store.storyboardGenerateSettings.agentId = normalized.storyboardScriptAgentId
  store.storyboardAgent = {
    id: normalized.storyboardScriptAgentId,
    name: normalized.storyboardScriptAgentName,
    desc: normalized.storyboardScriptAgentDesc,
    thumbnail: normalized.storyboardScriptAgentThumbnail
  }
}

export function migrateOptionalModelCodesFromPersist(
  store: OptionalModelCodesStoreSlice & {
    currentProjectId: number | null
    currentEpisodeId: number | null
    optionalModelCodesByScope?: Record<string, OptionalModelCodesScopeSnapshot>
  }
) {
  if (!store.optionalModelCodesByScope || typeof store.optionalModelCodesByScope !== 'object') {
    store.optionalModelCodesByScope = {}
  }
  const key = liveGenScopeKeyFromIds(store.currentProjectId, store.currentEpisodeId)

  /** 丢弃 localStorage 里未分桶的扁平字段，避免刷新后写入当前作品（此前 bug 根因） */
  store.extractModelCodes = emptyExtractModelCodes()
  store.extractImageModelCodes = emptyExtractModelCodes()
  store.storyboardGenerateSettings.modelCode = ''
  store.storyboardGenerateSettings.agentId = ''
  store.storyboardStylistGenerateSettings.modelCode = ''
  store.storyboardAgent.id = ''
  store.storyboardAgent.name = ''
  store.storyboardAgent.desc = ''
  store.storyboardAgent.thumbnail = ''

  const scoped = store.optionalModelCodesByScope[key]
  applyOptionalModelCodesToStore(
    store,
    scoped
      ? normalizeOptionalModelCodesScopeSnapshot(scoped)
      : emptyOptionalModelCodesScopeSnapshot()
  )
}

export function pausedTasksFollowSessionKey(projectId: number): string {
  return `cf-paused-task-follow:${projectId}`
}
