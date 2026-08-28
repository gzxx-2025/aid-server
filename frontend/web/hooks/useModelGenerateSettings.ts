'use client'

import { useEffect,useRef } from 'react'
import type { ModelOption } from '~/components/steps/ModelSelectDropdown'
import type { UserModelListItem } from '~/types/business-api'
import {
buildAspectRatioSelectOptions,
buildCountSelectOptions,
buildQualitySelectOptions,
coerceGenerationSettings,
parseModelCapability,
type ModelCapabilitySnapshot
} from '~/utils/modelCapability'

export interface GenerationSettingsState {
  aspectRatio: string
  count: number
  quality: string
}

interface UseModelGenerateSettingsOptions {
  /**
   * 原 Vue 版接收 Ref；React 版接收 getter/setter：
   * getter 读 ref 镜像最新值（原 `.value` 同步读），渲染期调用即为当前渲染值。
   */
  getSelectedModel: () => ModelOption
  getRawModelList: () => UserModelListItem[]
  getGenerationSettings: () => GenerationSettingsState
  setGenerationSettings: (v: GenerationSettingsState) => void
  include3k?: boolean
}

/**
 * 根据 listByFunc 返回的模型 capability 驱动比例 / 张数 / 画质下拉
 */
export function useModelGenerateSettings(options: UseModelGenerateSettingsOptions) {
  const { getSelectedModel, getRawModelList, getGenerationSettings, setGenerationSettings, include3k = true } = options

  /** 事件回调内读最新 getter，避免闭包捕获旧值 */
  const optionsRef = useRef({ getSelectedModel, getRawModelList, getGenerationSettings, setGenerationSettings, include3k })
  optionsRef.current = { getSelectedModel, getRawModelList, getGenerationSettings, setGenerationSettings, include3k }

  function resolveSelectedRawModel(): UserModelListItem | null {
    const o = optionsRef.current
    const selected = o.getSelectedModel()
    const code = String(selected?.id || '').trim()
    const sid = selected?.serverModelId
    return (
      o.getRawModelList().find(
        (m) =>
          (sid != null && Number(m.id) === sid) ||
          String(m.modelCode || '').trim() === code
      ) ?? null
    )
  }

  const selectedRawModel = resolveSelectedRawModel()

  const capabilitySnapshot: ModelCapabilitySnapshot = parseModelCapability(selectedRawModel)

  const aspectRatioSelectOptions = buildAspectRatioSelectOptions(capabilitySnapshot)
  const countSelectOptions = buildCountSelectOptions(capabilitySnapshot)
  const qualitySelectOptions = buildQualitySelectOptions(capabilitySnapshot, { include3k })

  function syncSettingsToModel() {
    const o = optionsRef.current
    const snapshot = parseModelCapability(resolveSelectedRawModel())
    o.setGenerationSettings(
      coerceGenerationSettings(o.getGenerationSettings(), snapshot, { include3k: o.include3k })
    )
  }

  /** 原 watch(selectedRawModel)：模型切换后按新 capability 收敛设置（非 immediate，首帧跳过） */
  const syncRef = useRef(syncSettingsToModel)
  syncRef.current = syncSettingsToModel
  const prevRawModelRef = useRef<UserModelListItem | null | undefined>(undefined)
  useEffect(() => {
    if (prevRawModelRef.current === undefined) {
      prevRawModelRef.current = selectedRawModel
      return
    }
    if (prevRawModelRef.current === selectedRawModel) return
    prevRawModelRef.current = selectedRawModel
    syncRef.current()
     
  }, [selectedRawModel])

  return {
    capabilitySnapshot,
    aspectRatioSelectOptions,
    countSelectOptions,
    qualitySelectOptions,
    syncSettingsToModel
  }
}
