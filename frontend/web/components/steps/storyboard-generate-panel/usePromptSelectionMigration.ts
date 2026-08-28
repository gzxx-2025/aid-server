'use client'

import { useEffect,type RefObject } from 'react'
import {
PROMPT_TYPE,
resolvePromptSelection,
type SettingSelectOption
} from '~/composables/usePromptDictionary'
import type { PanelSelection,ResolvedStoryboardGeneratePanelProps } from './types'
interface PromptSelectionMigrationOptions {
  props: ResolvedStoryboardGeneratePanelProps
  propsRef: RefObject<ResolvedStoryboardGeneratePanelProps>
  compositionOptions: SettingSelectOption[]
  shotSizeOptions: SettingSelectOption[]
  cameraAngleOptions: SettingSelectOption[]
  focalLengthOptions: SettingSelectOption[]
  colorToneOptions: SettingSelectOption[]
  lightingOptions: SettingSelectOption[]
  techniqueOptions: SettingSelectOption[]
  cameraMovementOptions: SettingSelectOption[]
  shootingTechniqueOptions: SettingSelectOption[]
}

export function usePromptSelectionMigration(
  options: PromptSelectionMigrationOptions
) {
  const {
    props,
    propsRef,
    compositionOptions,
    shotSizeOptions,
    cameraAngleOptions,
    focalLengthOptions,
    colorToneOptions,
    lightingOptions,
    techniqueOptions,
    cameraMovementOptions,
    shootingTechniqueOptions
  } = options

function migrateSelectionIfNeeded(
  cur: PanelSelection | undefined,
  opts: SettingSelectOption[],
  promptType: string,
  updater: (v: { key: string; value: string }) => void
) {
  if (!cur) return
  const n = resolvePromptSelection(cur, opts, promptType)
  if (n && (n.key !== cur.key || n.value !== cur.value)) updater(n)
}

// watch([各选中值, 各词库 options], flush post)：词库更新后迁移旧选中值
useEffect(() => {
  const p = propsRef.current!
  if (p.suppressPromptReactiveSync) return
  migrateSelectionIfNeeded(p.selectedComposition, compositionOptions, PROMPT_TYPE.composition, (v) =>
    p.onSelectedCompositionChange?.(v)
  )
  migrateSelectionIfNeeded(p.selectedShotSize, shotSizeOptions, PROMPT_TYPE.shot_size, (v) =>
    p.onSelectedShotSizeChange?.(v)
  )
  migrateSelectionIfNeeded(p.selectedCameraAngle, cameraAngleOptions, PROMPT_TYPE.camera_angle, (v) =>
    p.onSelectedCameraAngleChange?.(v)
  )
  migrateSelectionIfNeeded(p.selectedFocalLength, focalLengthOptions, PROMPT_TYPE.focal_length, (v) =>
    p.onSelectedFocalLengthChange?.(v)
  )
  migrateSelectionIfNeeded(p.selectedColorTone, colorToneOptions, PROMPT_TYPE.color_tone, (v) =>
    p.onSelectedColorToneChange?.(v)
  )
  migrateSelectionIfNeeded(p.selectedLighting, lightingOptions, PROMPT_TYPE.lighting, (v) =>
    p.onSelectedLightingChange?.(v)
  )
  migrateSelectionIfNeeded(p.selectedTechnique, techniqueOptions, PROMPT_TYPE.exposure_blur, (v) =>
    p.onSelectedTechniqueChange?.(v)
  )
  migrateSelectionIfNeeded(
    p.selectedCameraMovement,
    cameraMovementOptions,
    PROMPT_TYPE.camera_movement,
    (v) => p.onSelectedCameraMovementChange?.(v)
  )
  migrateSelectionIfNeeded(
    p.selectedShootingTechnique,
    shootingTechniqueOptions,
    PROMPT_TYPE.shooting_technique,
    (v) => p.onSelectedShootingTechniqueChange?.(v)
  )
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [
  props.selectedComposition,
  compositionOptions,
  props.selectedShotSize,
  shotSizeOptions,
  props.selectedCameraAngle,
  cameraAngleOptions,
  props.selectedFocalLength,
  focalLengthOptions,
  props.selectedColorTone,
  colorToneOptions,
  props.selectedLighting,
  lightingOptions,
  props.selectedTechnique,
  techniqueOptions,
  props.selectedCameraMovement,
  cameraMovementOptions,
  props.selectedShootingTechnique,
  shootingTechniqueOptions
])

}
