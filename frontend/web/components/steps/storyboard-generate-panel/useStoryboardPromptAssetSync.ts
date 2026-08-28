'use client'

import { useMemo,useRef,type RefObject } from 'react'
import type { RichTextEditorHandle } from '~/components/common/RichTextEditor'
import { PROMPT_TYPE } from '~/composables/usePromptDictionary'
import {
storyboardPromptHtmlToPlain,
storyboardPromptPlainToHtml,
stripPromptImageAssetPlaceholdersFromPlain,
type PromptAssetItem
} from '~/utils/storyboardPromptAssetRef'
import {
collectLocalStripImageAssets,
diffPromptAssetsByIdentity,
extractPromptAudioRefIdentityKeysFromHtml,
extractPromptAssetRefIdentityKeysFromHtml,
findAudioIndexesLostFromPrompt,
findStripIndexesLostFromPrompt,
promptAssetItemKey
} from '~/utils/storyboardPromptAssetStripSync'
import { type PromptParamGroup,type PromptParamType } from '~/utils/storyboardPromptParamRef'
import type { PanelSelection,ResolvedStoryboardGeneratePanelProps } from './types'
interface StoryboardPromptAssetSyncOptions {
  props: ResolvedStoryboardGeneratePanelProps
  propsRef: RefObject<ResolvedStoryboardGeneratePanelProps>
  promptEditorExpandedRef: RefObject<RichTextEditorHandle | null>
  promptEditorCollapsedRef: RefObject<RichTextEditorHandle | null>
  storyboardPromptAssetsRef: RefObject<PromptAssetItem[]>
  storyboardPromptParamGroupsRef: RefObject<PromptParamGroup[]>
  paramSettingsOpenRef: RefObject<boolean>
  enablePromptAssetRefs: boolean
}

export function useStoryboardPromptAssetSync(
  options: StoryboardPromptAssetSyncOptions
) {
  const {
    props,
    propsRef,
    promptEditorExpandedRef,
    promptEditorCollapsedRef,
    storyboardPromptAssetsRef,
    storyboardPromptParamGroupsRef,
    paramSettingsOpenRef,
    enablePromptAssetRefs
  } = options

/** 各参数类型与「当前选中值 / 回写回调」的绑定（读写均走最新 props） */
function paramSelectionBindings(): Array<{
  paramType: PromptParamType
  get: () => PanelSelection | undefined
  emit: (v: PanelSelection) => void
}> {
  const p = propsRef.current!
  if (p.mode === 'storyboard') {
    return [
      {
        paramType: PROMPT_TYPE.composition as PromptParamType,
        get: () => propsRef.current!.selectedComposition,
        emit: (v) => propsRef.current!.onSelectedCompositionChange?.(v)
      },
      {
        paramType: PROMPT_TYPE.shot_size as PromptParamType,
        get: () => propsRef.current!.selectedShotSize,
        emit: (v) => propsRef.current!.onSelectedShotSizeChange?.(v)
      },
      {
        paramType: PROMPT_TYPE.camera_angle as PromptParamType,
        get: () => propsRef.current!.selectedCameraAngle,
        emit: (v) => propsRef.current!.onSelectedCameraAngleChange?.(v)
      },
      {
        paramType: PROMPT_TYPE.focal_length as PromptParamType,
        get: () => propsRef.current!.selectedFocalLength,
        emit: (v) => propsRef.current!.onSelectedFocalLengthChange?.(v)
      },
      {
        paramType: PROMPT_TYPE.color_tone as PromptParamType,
        get: () => propsRef.current!.selectedColorTone,
        emit: (v) => propsRef.current!.onSelectedColorToneChange?.(v)
      },
      {
        paramType: PROMPT_TYPE.lighting as PromptParamType,
        get: () => propsRef.current!.selectedLighting,
        emit: (v) => propsRef.current!.onSelectedLightingChange?.(v)
      },
      {
        paramType: PROMPT_TYPE.exposure_blur as PromptParamType,
        get: () => propsRef.current!.selectedTechnique,
        emit: (v) => propsRef.current!.onSelectedTechniqueChange?.(v)
      }
    ]
  }
  if (p.mode === 'storyboardVideo') {
    return [
      {
        paramType: PROMPT_TYPE.camera_movement as PromptParamType,
        get: () => propsRef.current!.selectedCameraMovement,
        emit: (v) => propsRef.current!.onSelectedCameraMovementChange?.(v)
      },
      {
        paramType: PROMPT_TYPE.shooting_technique as PromptParamType,
        get: () => propsRef.current!.selectedShootingTechnique,
        emit: (v) => propsRef.current!.onSelectedShootingTechniqueChange?.(v)
      }
    ]
  }
  if (p.mode === 'imageToVideo') {
    return [
      {
        paramType: PROMPT_TYPE.shooting_technique as PromptParamType,
        get: () =>
          propsRef.current!.imageToVideoSelectedShootingTechnique ??
          propsRef.current!.selectedShootingTechnique ??
          null,
        emit: (v) => propsRef.current!.onSelectedShootingTechniqueChange?.(v)
      },
      {
        paramType: PROMPT_TYPE.camera_movement as PromptParamType,
        get: () => propsRef.current!.imageToVideoSelectedCameraMovement ?? null,
        emit: () => {
          /* 多参灵感空间镜头运动由父级 applyMultiParamSettingsConfirm 维护 */
        }
      }
    ]
  }
  return []
}

function enablePromptAssetRefsOf(p: ResolvedStoryboardGeneratePanelProps): boolean {
  return (
    p.mode === 'storyboard' ||
    p.mode === 'imageToVideo' ||
    p.mode === 'storyboardVideo' ||
    p.mode === 'edgeVideo'
  )
}

function getActivePromptEditor(): RichTextEditorHandle | null {
  const p = propsRef.current!
  if (p.useParamSettingsModal) {
    return promptEditorCollapsedRef.current
  }
  return p.isSettingExpanded ? promptEditorExpandedRef.current : promptEditorCollapsedRef.current
}

function getMainPromptEditor() {
  return promptEditorCollapsedRef.current
}

/** 本地参考图条快照：仅对「新增」写入描述框，避免清空文本后因旧图仍在列表而批量回填 */
const prevLocalStripImageAssetsRef = useRef<PromptAssetItem[]>([])
const prevPromptAssetRefIdentityKeysRef = useRef<Set<string>>(new Set())
const prevPromptAudioRefIdentityKeysRef = useRef<Set<string>>(new Set())
const promptStripSyncGuardRef = useRef(false)
/** 首次同步只种子快照，避免 remount/冷启动把条上旧图整批 upsert 回描述框 */
const stripAssetSyncSeededRef = useRef(false)

function snapshotLocalStripImageAssets(): PromptAssetItem[] {
  const p = propsRef.current!
  return collectLocalStripImageAssets(p.sceneImages, p.characterImages, p.propImages, p.otherImages)
}

function resolveCanonicalPromptAsset(local: PromptAssetItem): PromptAssetItem {
  const key = promptAssetItemKey(local)
  if (!key) return local
  return (
    storyboardPromptAssetsRef.current.find(
      (a) => promptAssetItemKey(a) === key && a.assetType !== 'audio'
    ) || local
  )
}

function removePromptAssetRefsByAssets(assets: PromptAssetItem[]) {
  const editor = getActivePromptEditor()
  if (!editor || !assets.length) return
  for (const asset of assets) {
    // 不传 imageIndex，避免本地序号重排后误删其它标签
    editor.removePromptAssetRefByMatch({ assetId: asset.assetId, name: asset.name })
  }
}

function insertPromptAssetRefsByAssets(assets: PromptAssetItem[]) {
  const editor = getActivePromptEditor()
  if (!editor || !assets.length) return
  for (const asset of assets) {
    editor.upsertPromptAssetRef(resolveCanonicalPromptAsset(asset))
  }
}

function refreshPromptAssetRefKeySnapshot(html?: string) {
  const raw = html ?? getActivePromptEditor()?.getHtml?.() ?? propsRef.current!.prompt ?? ''
  prevPromptAssetRefIdentityKeysRef.current = extractPromptAssetRefIdentityKeysFromHtml(raw || '')
  prevPromptAudioRefIdentityKeysRef.current = extractPromptAudioRefIdentityKeysFromHtml(raw || '')
}

/** 参考图条 → 描述框：删除联动 + 仅同步新导入项（修复「清空后导入一张却回填多张」） */
function syncStoryboardPromptAssetRefsInEditor() {
  if (!enablePromptAssetRefsOf(propsRef.current!)) return
  const nextLocal = snapshotLocalStripImageAssets()
  // suppress / 灵感空间打开 / 双向同步中：只推进快照，避免关闭后把累积变化打成大批 add
  if (
    propsRef.current!.suppressPromptReactiveSync ||
    promptStripSyncGuardRef.current ||
    paramSettingsOpenRef.current
  ) {
    prevLocalStripImageAssetsRef.current = nextLocal
    return
  }
  // 编辑器未挂载时不推进快照，避免首次插入被跳过且之后不再补写
  if (!getActivePromptEditor()) return
  // 仅多参做条↔描述联动；其它模式只维护快照，避免分镜图模式误伤
  if (propsRef.current!.mode !== 'imageToVideo') {
    prevLocalStripImageAssetsRef.current = nextLocal
    stripAssetSyncSeededRef.current = true
    return
  }
  if (!stripAssetSyncSeededRef.current) {
    prevLocalStripImageAssetsRef.current = nextLocal
    stripAssetSyncSeededRef.current = true
    return
  }
  const { added, removed } = diffPromptAssetsByIdentity(
    prevLocalStripImageAssetsRef.current,
    nextLocal
  )
  prevLocalStripImageAssetsRef.current = nextLocal
  if (!added.length && !removed.length) return
  promptStripSyncGuardRef.current = true
  try {
    removePromptAssetRefsByAssets(removed)
    insertPromptAssetRefsByAssets(added)
  } finally {
    promptStripSyncGuardRef.current = false
    refreshPromptAssetRefKeySnapshot()
  }
}

/** 描述框 → 参考图条：删除单个 @ 标签时同步移除对应参考图（整段清空不删条） */
function syncStripImagesFromPromptRefDiff(html: string) {
  const p = propsRef.current!
  if (p.mode !== 'imageToVideo' && p.mode !== 'storyboard') return
  if (!enablePromptAssetRefsOf(p) || paramSettingsOpenRef.current) return
  const nextKeys = extractPromptAssetRefIdentityKeysFromHtml(html || '')
  if (p.suppressPromptReactiveSync || promptStripSyncGuardRef.current) {
    prevPromptAssetRefIdentityKeysRef.current = nextKeys
    return
  }

  const stripImages = [
    ...p.sceneImages,
    ...p.characterImages,
    ...p.propImages,
    ...p.otherImages
  ].filter((img) => img?.url || img?.thumbnail)
  const indexes = findStripIndexesLostFromPrompt({
    images: stripImages,
    prevKeys: prevPromptAssetRefIdentityKeysRef.current,
    nextKeys,
    promptIsEmpty: !storyboardPromptHtmlToPlain(html || '').trim()
  })
  prevPromptAssetRefIdentityKeysRef.current = nextKeys
  if (!indexes.length) return

  promptStripSyncGuardRef.current = true
  try {
    for (const idx of indexes) {
      p.onRemoveMultiParamAssetReference?.(idx)
    }
  } finally {
    promptStripSyncGuardRef.current = false
    prevLocalStripImageAssetsRef.current = snapshotLocalStripImageAssets()
  }
}

/** 描述框 → 参考音频条：删除 @音频 后按稳定身份同步移除上方音频。 */
function syncStripAudiosFromPromptRefDiff(html: string) {
  const p = propsRef.current!
  if (!enablePromptAssetRefsOf(p) || paramSettingsOpenRef.current) return
  const nextKeys = extractPromptAudioRefIdentityKeysFromHtml(html || '')
  if (p.suppressPromptReactiveSync || promptStripSyncGuardRef.current) {
    prevPromptAudioRefIdentityKeysRef.current = nextKeys
    return
  }

  const indexes = findAudioIndexesLostFromPrompt({
    audios: p.referenceAudios ?? [],
    prevKeys: prevPromptAudioRefIdentityKeysRef.current,
    nextKeys,
    promptIsEmpty: !storyboardPromptHtmlToPlain(html || '').trim()
  })
  prevPromptAudioRefIdentityKeysRef.current = nextKeys
  if (!indexes.length || !p.onRemoveReferenceAudio) return

  promptStripSyncGuardRef.current = true
  void (async () => {
    try {
      for (const index of indexes) {
        await p.onRemoveReferenceAudio?.(index)
      }
    } finally {
      promptStripSyncGuardRef.current = false
      refreshPromptAssetRefKeySnapshot()
    }
  })()
}

function plainHasPromptImageAssetPlaceholder(plain: string): boolean {
  return /@图片\d*(?:\[[^\]]+\])?/.test(plain)
}

const storyboardVideoReferenceList = useMemo(() => {
  const fromList = (props.referenceImages ?? []).filter((img) => img?.url || img?.thumbnail)
  if (fromList.length) return fromList
  const single = props.referenceImage
  if (single?.url || single?.thumbnail) return [single]
  return []
}, [props.referenceImages, props.referenceImage])
const storyboardVideoReferenceListRef = useRef(storyboardVideoReferenceList)
storyboardVideoReferenceListRef.current = storyboardVideoReferenceList

/** 图生视频：参考图在描述框外展示，移除文本域内残留的 @图片 引用 */
function syncStoryboardVideoPromptWithoutImageRefs() {
  const p = propsRef.current!
  if (p.mode !== 'storyboardVideo' || paramSettingsOpenRef.current) return
  const editor = getActivePromptEditor()
  const currentHtml = editor?.getHtml?.() || p.prompt
  const plain = editor?.getPlainPrompt?.() || storyboardPromptHtmlToPlain(p.prompt)
  const hasAssetEmbed = currentHtml.includes('scp-prompt-asset-ref')
  if (!hasAssetEmbed && !plain.includes('@') && !plainHasPromptImageAssetPlaceholder(plain)) return

  const imageNames = storyboardVideoReferenceListRef.current.map((img: any) =>
    String(img.title || img.name || '').trim()
  )
  const cleaned = stripPromptImageAssetPlaceholdersFromPlain(plain, imageNames)
  if (cleaned === plain && !hasAssetEmbed) return

  const html = storyboardPromptPlainToHtml(cleaned, [], storyboardPromptParamGroupsRef.current, {
    enableVideoLabeledParams: true,
    enableAssetRefs: false
  })
  const prevPlain = storyboardPromptHtmlToPlain(p.prompt)
  const nextPlain = storyboardPromptHtmlToPlain(html)
  if (nextPlain === prevPlain && !hasAssetEmbed) return
  p.onPromptChange?.(html)
}


  return {
    paramSelectionBindings,
    getActivePromptEditor,
    getMainPromptEditor,
    prevLocalStripImageAssetsRef,
    prevPromptAssetRefIdentityKeysRef,
    prevPromptAudioRefIdentityKeysRef,
    promptStripSyncGuardRef,
    stripAssetSyncSeededRef,
    snapshotLocalStripImageAssets,
    resolveCanonicalPromptAsset,
    removePromptAssetRefsByAssets,
    insertPromptAssetRefsByAssets,
    refreshPromptAssetRefKeySnapshot,
    syncStoryboardPromptAssetRefsInEditor,
    syncStripImagesFromPromptRefDiff,
    syncStripAudiosFromPromptRefDiff,
    plainHasPromptImageAssetPlaceholder,
    storyboardVideoReferenceList,
    storyboardVideoReferenceListRef,
    syncStoryboardVideoPromptWithoutImageRefs
  }
}
