'use client'

import { useEffect,useMemo,useRef } from 'react'
import type { UserModelListItem } from '~/types/business-api'
import {
buildAspectRatioSelectOptions,
buildAudioSelectOptions,
buildDurationSelectOptions,
buildVideoCountSelectOptions,
buildVideoQualitySelectOptions,
coerceVideoGenerationSettings,
mergeAspectRatioLabels,
parseModelCapability,
type ModelCapabilitySnapshot,
type VideoGenerationSettingsState
} from '~/utils/modelCapability'

interface UseVideoModelGenerateSettingsOptions {
  selectedModelCode: string
  rawModelList: UserModelListItem[]
  generationSettings: VideoGenerationSettingsState
  /** 原 Ref 写回：coerce 后的设置回写调用方状态 */
  onGenerationSettingsChange: (value: VideoGenerationSettingsState) => void
  /** AspectRatioEnum 字典项，用于比例下拉展示名 */
  aspectRatioEnumLabels?: { value: string; label: string }[]
}

function findRawModelByCode(
  code: string,
  rawModelList: UserModelListItem[]
): UserModelListItem | null {
  const trimmed = String(code || '').trim()
  if (!trimmed) return null
  const normalized = trimmed.toLowerCase()
  return (
    rawModelList.find((m) => {
      const mc = String(m.modelCode || '').trim()
      if (mc && (mc === trimmed || mc.toLowerCase() === normalized)) return true
      const sid = Number(m.id)
      if (Number.isFinite(sid) && sid > 0 && String(sid) === trimmed) return true
      const rawId = String(m.id ?? '').trim()
      if (rawId && (rawId === trimmed || rawId.toLowerCase() === normalized)) return true
      return false
    }) ?? null
  )
}

/**
 * 图生视频模型配置：listByFunc(main_storyboard_video) capability + 枚举字典比例文案
 */
export function useVideoModelGenerateSettings(options: UseVideoModelGenerateSettingsOptions) {
  const {
    selectedModelCode,
    rawModelList,
    generationSettings,
    onGenerationSettingsChange,
    aspectRatioEnumLabels
  } = options

  const selectedRawModel = useMemo(
    () => findRawModelByCode(selectedModelCode, rawModelList),
    [selectedModelCode, rawModelList]
  )

  const capabilitySnapshot = useMemo<ModelCapabilitySnapshot>(
    () => parseModelCapability(selectedRawModel),
    [selectedRawModel]
  )

  const aspectRatioSelectOptions = useMemo(() => {
    const base = buildAspectRatioSelectOptions(capabilitySnapshot)
    const labels = aspectRatioEnumLabels ?? []
    if (!labels.length) return base
    return mergeAspectRatioLabels(
      base.map((o) => o.value),
      labels
    )
  }, [capabilitySnapshot, aspectRatioEnumLabels])

  const countSelectOptions = useMemo(
    () => buildVideoCountSelectOptions(capabilitySnapshot),
    [capabilitySnapshot]
  )
  const qualitySelectOptions = useMemo(
    () => buildVideoQualitySelectOptions(capabilitySnapshot),
    [capabilitySnapshot]
  )
  const durationSelectOptions = useMemo(
    () => buildDurationSelectOptions(capabilitySnapshot),
    [capabilitySnapshot]
  )
  const supportsDuration = capabilitySnapshot.supportsDuration
  const supportsAudio = capabilitySnapshot.supportsAudio
  const audioSelectOptions = useMemo(
    () => buildAudioSelectOptions(capabilitySnapshot.supportsAudio),
    [capabilitySnapshot]
  )

  /** 回调 / effect 内读最新值（避免闭包旧值） */
  const generationSettingsRef = useRef(generationSettings)
  generationSettingsRef.current = generationSettings
  const capabilitySnapshotRef = useRef(capabilitySnapshot)
  capabilitySnapshotRef.current = capabilitySnapshot
  const rawModelListRef = useRef(rawModelList)
  rawModelListRef.current = rawModelList
  const onGenerationSettingsChangeRef = useRef(onGenerationSettingsChange)
  onGenerationSettingsChangeRef.current = onGenerationSettingsChange

  /**
   * 原 Vue 侧 setState 同步可见（ref.value 赋值后立即 sync 即取新值）；
   * React setState 到下一渲染才可见，调用方刚 set 的模型/设置可通过 next 显式传入。
   */
  function syncSettingsToModel(next?: {
    selectedModelCode?: string
    settings?: VideoGenerationSettingsState
    rawModelList?: UserModelListItem[]
  }) {
    const settings = next?.settings ?? generationSettingsRef.current
    const snapshot =
      next?.selectedModelCode !== undefined
        ? parseModelCapability(
            findRawModelByCode(next.selectedModelCode, next?.rawModelList ?? rawModelListRef.current)
          )
        : capabilitySnapshotRef.current
    onGenerationSettingsChangeRef.current(coerceVideoGenerationSettings(settings, snapshot))
  }

  // 原 watch(selectedRawModel, ...)：非 immediate，首帧跳过
  const firstRunRef = useRef(true)
  useEffect(() => {
    if (firstRunRef.current) {
      firstRunRef.current = false
      return
    }
    syncSettingsToModel()
  }, [selectedRawModel])

  return {
    capabilitySnapshot,
    aspectRatioSelectOptions,
    countSelectOptions,
    qualitySelectOptions,
    durationSelectOptions,
    audioSelectOptions,
    supportsDuration,
    supportsAudio,
    syncSettingsToModel
  }
}
