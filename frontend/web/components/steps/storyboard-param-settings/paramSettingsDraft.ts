import {
collectStoryboardPromptAssets,
mergePromptAssets,
type PromptAssetItem
} from '~/utils/storyboardPromptAssetRef'

export type AssetCategoryKey =
  | 'scene'
  | 'character'
  | 'prop'
  | 'pose'
  | 'expression'
  | 'effect'
  | 'draft'

export type ParamSettingsConfirmPayload = {
  sceneImages: any[]
  characterImages: any[]
  propImages: any[]
  otherImages: any[]
  nineGridEnabled: boolean
  referenceImage: { id?: string; url?: string; thumbnail?: string; title?: string } | null
  referenceImages: any[]
  selectedComposition: { key: string; value: string } | null
  selectedShotSize: { key: string; value: string } | null
  selectedCameraAngle: { key: string; value: string } | null
  selectedFocalLength: { key: string; value: string } | null
  selectedColorTone: { key: string; value: string } | null
  selectedLighting: { key: string; value: string } | null
  selectedTechnique: { key: string; value: string } | null
  compositionDesc: string
  activeSettingKey: string | null
  selectedCameraMovement: { key: string; value: string } | null
  cameraMovementDesc: string
  selectedShootingTechnique: { key: string; value: string } | null
  activeVideoSettingKey: string | null
  imageToVideoNineGridEnabled?: boolean
  imageToVideoSelectedCameraMovement?: { key: string; value: string } | null
  imageToVideoCameraMovementDesc?: string
  imageToVideoSelectedShootingTechnique?: { key: string; value: string } | null
  imageToVideoActiveVideoSettingKey?: string | null
}

export type GridImageSource = 'scene' | 'character' | 'prop' | 'other' | 'reference'

export type GridImageItem = {
  key: string
  img: any
  source: GridImageSource
  index: number
  displayName: string
  removable: boolean
}

export type ParamSettingsMode = 'storyboard' | 'imageToVideo' | 'storyboardVideo'

/** 弹窗内草稿态（原组件里的一组 ref，React 侧合并为单对象 + ref 镜像） */
export interface ParamSettingsDraftState {
  sceneImages: any[]
  characterImages: any[]
  propImages: any[]
  otherImages: any[]
  nineGridEnabled: boolean
  referenceImages: any[]
  referenceImage: { id?: string; url?: string; thumbnail?: string; title?: string } | null
  i2vNineGridEnabled: boolean
  i2vSelectedCameraMovement: { key: string; value: string } | null
  i2vCameraMovementDesc: string
  i2vSelectedShootingTechnique: { key: string; value: string } | null
  i2vActiveVideoSettingKey: string | null
  selectedComposition: { key: string; value: string } | null
  selectedShotSize: { key: string; value: string } | null
  selectedCameraAngle: { key: string; value: string } | null
  selectedFocalLength: { key: string; value: string } | null
  selectedColorTone: { key: string; value: string } | null
  selectedLighting: { key: string; value: string } | null
  selectedTechnique: { key: string; value: string } | null
  compositionDesc: string
  activeSettingKey: string | null
  selectedCameraMovement: { key: string; value: string } | null
  cameraMovementDesc: string
  selectedShootingTechnique: { key: string; value: string } | null
  activeVideoSettingKey: string | null
  selectedGridKeys: Set<string>
  i2vReferenceImages: any[]
}

export const categoryDefaultNames: Record<AssetCategoryKey, string> = {
  scene: '场景',
  character: '角色',
  prop: '道具',
  pose: '姿态图',
  expression: '表情图',
  effect: '特效图',
  draft: '手绘图'
}

export function cloneList(list: any[]) {
  return list.map((item) => ({ ...item }))
}

export function createEmptyParamSettingsDraft(): ParamSettingsDraftState {
  return {
    sceneImages: [],
    characterImages: [],
    propImages: [],
    otherImages: [],
    nineGridEnabled: false,
    referenceImages: [],
    referenceImage: null,
    i2vNineGridEnabled: false,
    i2vSelectedCameraMovement: null,
    i2vCameraMovementDesc: '',
    i2vSelectedShootingTechnique: null,
    i2vActiveVideoSettingKey: null,
    selectedComposition: null,
    selectedShotSize: null,
    selectedCameraAngle: null,
    selectedFocalLength: null,
    selectedColorTone: null,
    selectedLighting: null,
    selectedTechnique: null,
    compositionDesc: '',
    activeSettingKey: null,
    selectedCameraMovement: null,
    cameraMovementDesc: '',
    selectedShootingTechnique: null,
    activeVideoSettingKey: null,
    selectedGridKeys: new Set<string>(),
    i2vReferenceImages: []
  }
}

/** 原 computed allGridImages：按草稿态推导左侧网格 */
export function buildGridItems(
  mode: ParamSettingsMode,
  draft: ParamSettingsDraftState
): GridImageItem[] {
  if (mode === 'storyboardVideo') {
    return draft.referenceImages
      .filter((img) => img?.url || img?.thumbnail)
      .map((img, index) => ({
        key: `reference-${img.id || index}`,
        img,
        source: 'reference' as GridImageSource,
        index,
        displayName: img.title || img.name || `参考图${index + 1}`,
        removable: true
      }))
  }

  const items: GridImageItem[] = []
  draft.sceneImages.forEach((img, index) => {
    items.push({
      key: `scene-${img.id || index}`,
      img,
      source: 'scene',
      index,
      displayName: img.title || img.name || `场景${index + 1}`,
      removable: false
    })
  })
  draft.characterImages.forEach((img, index) => {
    items.push({
      key: `character-${img.id || index}`,
      img,
      source: 'character',
      index,
      displayName: img.title || img.name || `角色${index + 1}`,
      removable: false
    })
  })
  draft.propImages.forEach((img, index) => {
    items.push({
      key: `prop-${img.id || index}`,
      img,
      source: 'prop',
      index,
      displayName: img.title || img.name || `道具${index + 1}`,
      removable: false
    })
  })
  draft.otherImages.forEach((img, index) => {
    items.push({
      key: `other-${img.id || index}`,
      img,
      source: 'other',
      index,
      displayName: img.title || img.name || `参考${index + 1}`,
      removable: true
    })
  })
  return items
}

/** 原 computed draftPromptAssets：草稿图片 → @图片 引用资产列表 */
export function computeDraftPromptAssets(
  mode: ParamSettingsMode,
  draft: ParamSettingsDraftState,
  extraPromptAssets: PromptAssetItem[]
): PromptAssetItem[] {
  const enable = mode === 'storyboard' || mode === 'imageToVideo' || mode === 'storyboardVideo'
  if (!enable) return []
  const startIndex =
    (extraPromptAssets?.length ?? 0) > 0
      ? Math.max(...extraPromptAssets.map((a) => a.imageIndex)) + 1
      : 1
  if (mode === 'storyboardVideo') {
    const refs = draft.referenceImages.filter((img) => img?.url || img?.thumbnail)
    if (!refs.length) {
      return extraPromptAssets?.length ? [...extraPromptAssets] : []
    }
    const local = collectStoryboardPromptAssets(
      refs.map((ref, idx) => ({
        ...ref,
        id: ref.id || `ref-${idx}-${ref.url || ref.thumbnail}`,
        title: ref.title || ref.name || `参考图${idx + 1}`
      })),
      [],
      [],
      [],
      startIndex
    )
    return extraPromptAssets?.length ? mergePromptAssets(extraPromptAssets, local) : local
  }
  const local = collectStoryboardPromptAssets(
    draft.sceneImages,
    draft.characterImages,
    draft.propImages,
    draft.otherImages,
    startIndex
  )
  return extraPromptAssets?.length ? mergePromptAssets(extraPromptAssets, local) : local
}
