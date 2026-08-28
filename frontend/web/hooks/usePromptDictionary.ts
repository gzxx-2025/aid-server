'use client'

import { useMemo } from 'react'
import { create } from 'zustand'
import type { EnumDictGroup, OfficialPromptItem } from '~/types/business-api'
import { userDictEnumList, userPromptOfficialCategoryList, userPromptOfficialItemList } from '~/utils/businessApi'
import { resolveProjectStyleReference } from '~/utils/buildProjectVideoStyleFields'
import {
  legacyLabelForKey,
  NONE,
  PROMPT_TYPE,
  type SettingSelectOption
} from './promptDictionaryVocabulary'

export * from './promptDictionaryVocabulary'

let loadInflight: Promise<void> | null = null

function mapRowsToOptions(rows: OfficialPromptItem[]): SettingSelectOption[] {
  return [...rows]
    .sort((a, b) => Number(a.sortOrder ?? 0) - Number(b.sortOrder ?? 0))
    .map((row) => ({
      key: String(row.id),
      value: (row.itemName ?? '').trim() || `项${row.id}`,
      image: (row.coverUrl ?? '').trim() || ''
    }))
}

/** 接口有数据时带「无」；无数据时返回空数组（由 UI 展示「暂无数据」） */
function optionsForType(all: OfficialPromptItem[], promptType: string): SettingSelectOption[] {
  const mapped = mapRowsToOptions(all.filter((p) => p.categoryCode === promptType))
  if (mapped.length > 0) return [NONE, ...mapped]
  return []
}

/** 与接口文档「枚举类型」白名单一致（POST /api/user/dict/enum/list，大小写敏感） */
export const DICT_ENUM = {
  ProjectTypeEnum: 'ProjectTypeEnum',
  AspectRatioEnum: 'AspectRatioEnum',
  ScriptTypeEnum: 'ScriptTypeEnum',
  StoryboardShotDensityEnum: 'StoryboardShotDensityEnum',
  CreationModeEnum: 'CreationModeEnum',
  ModelTypeEnum: 'ModelTypeEnum',
  GenModeEnum: 'GenModeEnum',
  AudioSourceEnum: 'AudioSourceEnum'
} as const

/** 接口未返回 StoryboardShotDensityEnum 时的兜底（与接口文档枚举值一致） */
export const FALLBACK_SHOT_DENSITY_ENUM_OPTIONS: { value: string; label: string }[] = [
  { value: '精简模式', label: '密度低/节奏紧凑' },
  { value: '标准模式', label: '默认' },
  { value: '细拆模式', label: '密度高/重点戏份' }
]

/**
 * 创建弹窗左侧（ProjectType…）与风格库/流程页（ModelType…）共用一次拉取，
 * 合并原先两处 enum/list 入参，避免同屏双请求。
 */
export const ENUM_TYPE_CANDIDATES = [
  DICT_ENUM.ProjectTypeEnum,
  DICT_ENUM.ModelTypeEnum,
  DICT_ENUM.AspectRatioEnum,
  DICT_ENUM.ScriptTypeEnum,
  DICT_ENUM.StoryboardShotDensityEnum,
  DICT_ENUM.CreationModeEnum,
  DICT_ENUM.GenModeEnum,
  DICT_ENUM.AudioSourceEnum
] as const

/** 旧版英文 key → 接口 StoryboardShotDensityEnum.value */
const LEGACY_SHOT_DENSITY_TO_MODE: Record<string, string> = {
  concise: '精简模式',
  standard: '标准模式',
  detailed: '细拆模式'
}

/** 将 store/本地缓存中的镜头密度对齐到字典枚举 value（供 generate/script 的 mode 入参） */
export function resolveShotDensityValue(
  current: string | undefined,
  options: { value: string; label: string }[]
): string {
  const values = options.map((o) => o.value)
  const fallback = values.includes('标准模式') ? '标准模式' : (values[0] ?? '标准模式')
  if (!current) return fallback
  if (values.includes(current)) return current
  const migrated = LEGACY_SHOT_DENSITY_TO_MODE[current]
  if (migrated && values.includes(migrated)) return migrated
  return fallback
}

export function buildEnumOptionsList(
  list: EnumDictGroup[],
  enumType: string
): { value: string; label: string }[] {
  const group = list.find((e) => e.enumType === enumType)
  const items = group?.items ?? []
  return items.map((e) => ({ value: e.value, label: e.desc || e.value }))
}

/** 生视频弹窗用：仅保留常见比例 */
export function filterAspectRatiosForVideoModal(
  rows: { value: string; label: string }[]
): { value: string; label: string }[] {
  const allow = new Set(['16:9', '9:16', '4:3', '1:1'])
  return rows.filter((r) => allow.has(r.value))
}

/**
 * 字典项使用数字 id 为 key 后，将旧版英文 key 映射到当前 options 中的项（按展示名匹配）。
 */
export function resolvePromptSelection(
  current: { key: string; value: string } | null | undefined,
  options: SettingSelectOption[],
  promptType: string
): { key: string; value: string } | null {
  if (!current) return null
  if (!options.length) return current
  if (current.key === 'none') return current
  if (options.some((o) => o.key === current.key)) return current
  const label = legacyLabelForKey(promptType, current.key) ?? current.value
  const byLabel = options.find((o) => o.value === label)
  if (byLabel) return { key: byLabel.key, value: byLabel.value }
  const byValueOnly = options.find((o) => o.value === current.value)
  if (byValueOnly) return { key: byValueOnly.key, value: byValueOnly.value }
  return current
}

export type StyleLibraryCard = {
  id: string
  name: string
  thumbnail: string
  featured: boolean
  /** 合并资产接口中的原始主键 ID */
  assetId?: number
  /** 合并资产接口来源 */
  sourceFlag?: 'official' | 'custom'
  /** official/query 原始 assetName（与 name 通常一致） */
  assetName?: string
  /** official/query 的 promptText，提交作品时映射为 videoStyleValue */
  promptText?: string | null
}

/** 合并资产分页：个人与官方 id 可能重复，用 source + id 作为稳定唯一键 */
export function buildStyleLibraryCardId(
  sourceFlag: string | null | undefined,
  rowId: number | string
): string {
  const source = String(sourceFlag || 'USER').toUpperCase()
  return `${source}-${rowId}`
}

/** 按 id 去重，避免 Vue :key 冲突导致多卡片同时呈现选中态 */
export function dedupeStyleLibraryCards(list: StyleLibraryCard[]): StyleLibraryCard[] {
  const seen = new Set<string>()
  const out: StyleLibraryCard[] = []
  for (const item of list) {
    const key = item.id.trim() || `${item.name}::${String(item.promptText ?? '').trim()}`
    if (seen.has(key)) continue
    seen.add(key)
    out.push(item)
  }
  return out
}

/** 兼容旧调用名称；仅按稳定复合 id 去重，禁止跨来源合并同名风格。 */
export function dedupeStyleLibraryCardsPreferOfficial(list: StyleLibraryCard[]): StyleLibraryCard[] {
  return dedupeStyleLibraryCards(list)
}

/** 将当前选中项移到列表首位（仅按 id 精确匹配，避免同名误排导致底部残留选中视觉） */
export function reorderStyleListSelectedFirst(
  list: StyleLibraryCard[],
  selectedId: string | null | undefined
): StyleLibraryCard[] {
  if (!selectedId || !list.length) return list
  const idx = list.findIndex((s) => s.id === selectedId)
  if (idx <= 0) return list
  const next = list.slice()
  const [item] = next.splice(idx, 1)
  if (!item) return list
  next.unshift(item)
  return next
}

function findStyleCardByStoredId(
  currentId: string,
  list: StyleLibraryCard[]
): StyleLibraryCard | undefined {
  const exact = list.find((s) => s.id === currentId)
  if (exact) return exact
  const currentReference = resolveProjectStyleReference({ id: currentId })
  if (currentReference) {
    return list.find((item) => {
      const itemReference = resolveProjectStyleReference(item)
      return itemReference?.styleSource === currentReference.styleSource
        && itemReference.styleAssetId === currentReference.styleAssetId
    })
  }
  if (/^\d+$/.test(currentId)) {
    const legacyMatches = list.filter(
      (s) =>
        s.id === `OFFICIAL-${currentId}` ||
        s.id === `USER-${currentId}` ||
        s.id.endsWith(`-${currentId}`)
    )
    return legacyMatches.length === 1 ? legacyMatches[0] : undefined
  }
  return undefined
}

export function buildStyleCardsFromPromptLib(list: OfficialPromptItem[]): StyleLibraryCard[] {
  const rows = mapRowsToOptions(list.filter((p) => p.categoryCode === PROMPT_TYPE.style))
  return rows.map((r, i) => ({
    id: r.key,
    name: r.value,
    thumbnail: r.image || '',
    featured: i < 3
  }))
}

/** 精选风格 id 从资产库切到提示词库 id 时，按名称对齐 */
export function resolveSelectedStyle(
  current: { id: string; name: string; thumbnail: string; assetId?: number; sourceFlag?: 'official' | 'custom'; assetName?: string; promptText?: string | null } | null,
  list: StyleLibraryCard[]
): { id: string; name: string; thumbnail: string; assetId?: number; sourceFlag?: 'official' | 'custom'; assetName?: string; promptText?: string | null } | null {
  if (!current) return null
  if (!list.length) return current
  const byId = findStyleCardByStoredId(current.id, list)
  if (byId) {
    return {
      id: byId.id,
      name: byId.name,
      thumbnail: byId.thumbnail,
      ...(byId.assetId != null ? { assetId: byId.assetId } : {}),
      ...(byId.sourceFlag ? { sourceFlag: byId.sourceFlag } : {}),
      ...(byId.assetName != null && byId.assetName !== '' ? { assetName: byId.assetName } : {}),
      ...(byId.promptText != null ? { promptText: byId.promptText } : {})
    }
  }
  // 分页列表尚未加载到目标时，稳定来源+资产 ID 不允许退化成同名匹配到另一条记录。
  if (resolveProjectStyleReference(current)) return current
  const promptKey = String(current.promptText ?? '').trim()
  const nameMatches = list.filter((s) => {
    if (s.name !== current.name) return false
    return !promptKey || String(s.promptText ?? '').trim() === promptKey
  })
  const byName = nameMatches.length === 1 ? nameMatches[0] : null
  if (byName) {
    return {
      id: byName.id,
      name: byName.name,
      thumbnail: byName.thumbnail,
      ...(byName.assetId != null ? { assetId: byName.assetId } : {}),
      ...(byName.sourceFlag ? { sourceFlag: byName.sourceFlag } : {}),
      ...(byName.assetName != null && byName.assetName !== '' ? { assetName: byName.assetName } : {}),
      ...(byName.promptText != null ? { promptText: byName.promptText } : {})
    }
  }
  const assetKey = String(current.assetName ?? current.name ?? '').trim()
  if (assetKey) {
    const assetMatches = list.filter((s) => {
      const nameMatch = s.assetName === assetKey || s.name === assetKey
      if (!nameMatch) return false
      if (!promptKey) return true
      return String(s.promptText ?? '').trim() === promptKey
    })
    const byAsset = assetMatches.length === 1 ? assetMatches[0] : null
    if (byAsset) {
      return {
        id: byAsset.id,
        name: byAsset.name,
        thumbnail: byAsset.thumbnail,
        ...(byAsset.assetId != null ? { assetId: byAsset.assetId } : {}),
        ...(byAsset.sourceFlag ? { sourceFlag: byAsset.sourceFlag } : {}),
        ...(byAsset.assetName != null && byAsset.assetName !== '' ? { assetName: byAsset.assetName } : {}),
        ...(byAsset.promptText != null ? { promptText: byAsset.promptText } : {})
      }
    }
  }
  return current
}

interface PromptDictionaryState {
  promptLibList: OfficialPromptItem[]
  enumList: EnumDictGroup[]
  loaded: boolean
  loadError: boolean
}

/** 对应原 Nuxt useState('prompt-dictionary-*') 的应用级共享状态：模块级 Zustand 单例，跨组件共享一份 */
const usePromptDictionaryStore = create<PromptDictionaryState>(() => ({
  promptLibList: [],
  enumList: [],
  loaded: false,
  loadError: false
}))

async function ensureLoaded() {
  const { loaded, loadError } = usePromptDictionaryStore.getState()
  if (loaded && !loadError) return
  if (loadInflight) {
    await loadInflight
    return
  }
  loadInflight = (async () => {
    let enumOk = false
    let promptOk = false
    try {
      const [enumResult, promptResult] = await Promise.allSettled([
        userDictEnumList({ enumTypes: [...ENUM_TYPE_CANDIDATES] }),
        (async () => {
          const categories = await userPromptOfficialCategoryList()
          const categoryCodes = categories.map((item) => item.categoryCode).filter(Boolean)
          if (!categoryCodes.length) return [] as OfficialPromptItem[]
          return userPromptOfficialItemList({ categoryCodes })
        })()
      ])

      if (enumResult.status === 'fulfilled') {
        usePromptDictionaryStore.setState({ enumList: enumResult.value })
        enumOk = true
      } else {
        usePromptDictionaryStore.setState({ enumList: [] })
      }

      if (promptResult.status === 'fulfilled') {
        usePromptDictionaryStore.setState({ promptLibList: promptResult.value })
        promptOk = true
      } else {
        usePromptDictionaryStore.setState({ promptLibList: [] })
      }

      usePromptDictionaryStore.setState({ loadError: !enumOk && !promptOk })
    } catch {
      usePromptDictionaryStore.setState({ loadError: true, promptLibList: [], enumList: [] })
    } finally {
      usePromptDictionaryStore.setState({ loaded: true })
      loadInflight = null
    }
  })()
  await loadInflight
}

/**
 * 拉取官方只读参数词库与枚举字典；无数据时不回退静态选项，由页面展示「暂无数据」。
 */
export function usePromptDictionary() {
  const promptLibList = usePromptDictionaryStore((s) => s.promptLibList)
  const enumList = usePromptDictionaryStore((s) => s.enumList)
  const loaded = usePromptDictionaryStore((s) => s.loaded)
  const loadError = usePromptDictionaryStore((s) => s.loadError)

  // 原 computed → useMemo：保持派生列表在数据未变时引用稳定，避免下游 effect 依赖抖动
  const cameraMovementOptions = useMemo(
    () => optionsForType(promptLibList, PROMPT_TYPE.camera_movement),
    [promptLibList]
  )
  const shootingTechniqueOptions = useMemo(
    () => optionsForType(promptLibList, PROMPT_TYPE.shooting_technique),
    [promptLibList]
  )
  const compositionOptions = useMemo(
    () => optionsForType(promptLibList, PROMPT_TYPE.composition),
    [promptLibList]
  )
  const shotSizeOptions = useMemo(
    () => optionsForType(promptLibList, PROMPT_TYPE.shot_size),
    [promptLibList]
  )
  const cameraAngleOptions = useMemo(
    () => optionsForType(promptLibList, PROMPT_TYPE.camera_angle),
    [promptLibList]
  )
  const focalLengthOptions = useMemo(
    () => optionsForType(promptLibList, PROMPT_TYPE.focal_length),
    [promptLibList]
  )
  const colorToneOptions = useMemo(
    () => optionsForType(promptLibList, PROMPT_TYPE.color_tone),
    [promptLibList]
  )
  const lightingOptions = useMemo(
    () => optionsForType(promptLibList, PROMPT_TYPE.lighting),
    [promptLibList]
  )
  const techniqueOptions = useMemo(
    () => optionsForType(promptLibList, PROMPT_TYPE.exposure_blur),
    [promptLibList]
  )

  const aspectRatioEnumOptions = useMemo(
    () => buildEnumOptionsList(enumList, DICT_ENUM.AspectRatioEnum),
    [enumList]
  )

  const scriptTypeEnumOptions = useMemo(
    () => buildEnumOptionsList(enumList, DICT_ENUM.ScriptTypeEnum),
    [enumList]
  )

  const creationModeEnumOptions = useMemo(
    () => buildEnumOptionsList(enumList, DICT_ENUM.CreationModeEnum),
    [enumList]
  )

  const storyboardShotDensityEnumOptions = useMemo(() => {
    const fromApi = buildEnumOptionsList(enumList, DICT_ENUM.StoryboardShotDensityEnum)
    return fromApi.length ? fromApi : FALLBACK_SHOT_DENSITY_ENUM_OPTIONS
  }, [enumList])

  const modelTypeEnumOptions = useMemo(
    () => buildEnumOptionsList(enumList, DICT_ENUM.ModelTypeEnum),
    [enumList]
  )

  const genModeEnumOptions = useMemo(
    () => buildEnumOptionsList(enumList, DICT_ENUM.GenModeEnum),
    [enumList]
  )

  const audioSourceEnumOptions = useMemo(
    () => buildEnumOptionsList(enumList, DICT_ENUM.AudioSourceEnum),
    [enumList]
  )

  const styleLibraryCards = useMemo(() => buildStyleCardsFromPromptLib(promptLibList), [promptLibList])

  // 原返回 computed ref，React 侧直接返回当前数组（调用处去掉 .value）
  function enumsByType(enumType: string) {
    return enumList.find((e) => e.enumType === enumType)?.items ?? []
  }

  return {
    ensureLoaded,
    loaded,
    loadError,
    promptLibList,
    enumList,
    cameraMovementOptions,
    shootingTechniqueOptions,
    compositionOptions,
    shotSizeOptions,
    cameraAngleOptions,
    focalLengthOptions,
    colorToneOptions,
    lightingOptions,
    techniqueOptions,
    aspectRatioEnumOptions,
    scriptTypeEnumOptions,
    creationModeEnumOptions,
    storyboardShotDensityEnumOptions,
    modelTypeEnumOptions,
    genModeEnumOptions,
    audioSourceEnumOptions,
    styleLibraryCards,
    enumsByType
  }
}
