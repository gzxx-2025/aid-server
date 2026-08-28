'use client'

import { useEffect,useMemo,useRef,useState,type RefObject } from 'react'
import type { RichTextEditorHandle } from '~/components/common/RichTextEditor'
import type { ParamSettingsConfirmPayload } from '~/components/steps/StoryboardParamSettingsModal'
import {
PROMPT_TYPE,
usePromptDictionary,
} from '~/composables/usePromptDictionary'
import {
collectPromptAudioAssetsFromMedia,
collectStoryboardPromptAssets,
mergePromptAssets,
storyboardPromptHtmlToPlain,
storyboardPromptPlainToHtml,
type PromptAssetItem
} from '~/utils/storyboardPromptAssetRef'
import {
buildMultiParamVideoPromptParamGroups,
buildStoryboardPromptParamGroups,
buildStoryboardVideoPromptParamGroups,
insertParamLabelIntoMarkdownSection,
type PromptParamType
} from '~/utils/storyboardPromptParamRef'
import type { ResolvedStoryboardGeneratePanelProps } from './types'
import { usePromptSelectionMigration } from './usePromptSelectionMigration'
import { useStoryboardPromptAssetSync } from './useStoryboardPromptAssetSync'

/** 对齐 Vue nextTick */
function nextTick(fn: () => void) {
  setTimeout(fn, 0)
}

interface PromptSyncOptions {
  props: ResolvedStoryboardGeneratePanelProps
  /** 事件回调 / 异步流程内读最新 props */
  propsRef: RefObject<ResolvedStoryboardGeneratePanelProps>
  promptEditorExpandedRef: RefObject<RichTextEditorHandle | null>
  promptEditorCollapsedRef: RefObject<RichTextEditorHandle | null>
}

/**
 * 描述框 ↔ 参考图条 ↔ 参数下拉三方联动（原 StoryboardGeneratePanel.vue 主体脚本逻辑）。
 * 灵感空间开关（paramSettingsOpen）也由本 hook 持有，guard 逻辑统一收口。
 */
export function useStoryboardPanelPromptSync(options: PromptSyncOptions) {
  const { props, propsRef, promptEditorExpandedRef, promptEditorCollapsedRef } = options

  const [paramSettingsOpen, setParamSettingsOpenState] = useState(false)
  const paramSettingsOpenRef = useRef(false)
  const setParamSettingsOpen = (v: boolean) => {
    paramSettingsOpenRef.current = v
    setParamSettingsOpenState(v)
  }

  const {
    ensureLoaded,
    cameraMovementOptions,
    shootingTechniqueOptions,
    compositionOptions,
    shotSizeOptions,
    cameraAngleOptions,
    focalLengthOptions,
    colorToneOptions,
    lightingOptions,
    techniqueOptions
  } = usePromptDictionary()

  // 原 onMounted：加载字典
  useEffect(() => {
    void ensureLoaded()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const enablePromptAssetRefs =
    props.mode === 'storyboard' ||
    props.mode === 'imageToVideo' ||
    props.mode === 'storyboardVideo' ||
    props.mode === 'edgeVideo'

  const enablePromptParamRefs =
    props.mode === 'storyboard' ||
    props.mode === 'storyboardVideo' ||
    props.mode === 'imageToVideo' ||
    props.mode === 'edgeVideo'

  const storyboardPromptAssets = useMemo(() => {
    const audioAssets = collectPromptAudioAssetsFromMedia(props.referenceAudios ?? [])
    const startIndex =
      (props.extraPromptAssets?.length ?? 0) > 0
        ? Math.max(...props.extraPromptAssets!.map((a) => a.imageIndex)) + 1
        : 1

    if (props.mode === 'storyboard' || props.mode === 'imageToVideo') {
      const local = collectStoryboardPromptAssets(
        props.sceneImages,
        props.characterImages,
        props.propImages,
        props.otherImages,
        startIndex
      )
      const base = props.extraPromptAssets?.length
        ? mergePromptAssets(props.extraPromptAssets, local)
        : local
      return audioAssets.length ? mergePromptAssets(base, audioAssets) : base
    }
    if (props.mode === 'storyboardVideo' || props.mode === 'edgeVideo') {
      const base = props.extraPromptAssets?.length ? [...props.extraPromptAssets] : []
      return audioAssets.length ? mergePromptAssets(base, audioAssets) : base
    }
    return props.extraPromptAssets?.length ? [...props.extraPromptAssets] : []
  }, [
    props.mode,
    props.referenceAudios,
    props.extraPromptAssets,
    props.sceneImages,
    props.characterImages,
    props.propImages,
    props.otherImages
  ])
  const storyboardPromptAssetsRef = useRef(storyboardPromptAssets)
  storyboardPromptAssetsRef.current = storyboardPromptAssets

  const storyboardPromptParamGroups = useMemo(() => {
    if (props.mode === 'storyboard') {
      return buildStoryboardPromptParamGroups({
        composition: compositionOptions,
        shotSize: shotSizeOptions,
        cameraAngle: cameraAngleOptions,
        focalLength: focalLengthOptions,
        colorTone: colorToneOptions,
        lighting: lightingOptions,
        technique: techniqueOptions
      })
    }
    if (props.mode === 'storyboardVideo') {
      return buildStoryboardVideoPromptParamGroups({
        cameraMovement: cameraMovementOptions,
        shootingTechnique: shootingTechniqueOptions
      })
    }
    if (props.mode === 'imageToVideo' || props.mode === 'edgeVideo') {
      return buildMultiParamVideoPromptParamGroups({
        cameraMovement: cameraMovementOptions,
        shootingTechnique: shootingTechniqueOptions
      })
    }
    return []
  }, [
    props.mode,
    compositionOptions,
    shotSizeOptions,
    cameraAngleOptions,
    focalLengthOptions,
    colorToneOptions,
    lightingOptions,
    techniqueOptions,
    cameraMovementOptions,
    shootingTechniqueOptions
  ])
  const storyboardPromptParamGroupsRef = useRef(storyboardPromptParamGroups)
  storyboardPromptParamGroupsRef.current = storyboardPromptParamGroups

  const promptAssetSync = useStoryboardPromptAssetSync({
    props,
    propsRef,
    promptEditorExpandedRef,
    promptEditorCollapsedRef,
    storyboardPromptAssetsRef,
    storyboardPromptParamGroupsRef,
    paramSettingsOpenRef,
    enablePromptAssetRefs
  })
  const {
    paramSelectionBindings,
    getActivePromptEditor,
    getMainPromptEditor,
    prevLocalStripImageAssetsRef,
    promptStripSyncGuardRef,
    stripAssetSyncSeededRef,
    snapshotLocalStripImageAssets,
    removePromptAssetRefsByAssets,
    refreshPromptAssetRefKeySnapshot,
    syncStoryboardPromptAssetRefsInEditor,
    syncStripImagesFromPromptRefDiff,
    syncStripAudiosFromPromptRefDiff,
    storyboardVideoReferenceList,
    syncStoryboardVideoPromptWithoutImageRefs
  } = promptAssetSync
  function syncStoryboardPromptParamRefsInEditor() {
    if (!enablePromptParamRefsOf(propsRef.current!) || paramSettingsOpenRef.current) return
    const editor = getActivePromptEditor()
    if (!editor) return
    for (const binding of paramSelectionBindings()) {
      editor.syncPromptParamRef(binding.paramType, binding.get())
    }
  }

  function onDraftToggleAssetRef(payload: { asset: PromptAssetItem; selected: boolean }) {
    getMainPromptEditor()?.togglePromptAssetRef(payload.asset, payload.selected)
  }

  function onDraftSyncAssetRefs(assets: PromptAssetItem[]) {
    const editor = getMainPromptEditor()
    if (!editor) return
    for (const asset of assets) {
      editor.upsertPromptAssetRef(asset)
    }
  }

  function syncParamRefsFromPayload(payload: ParamSettingsConfirmPayload) {
    const editor = getMainPromptEditor()
    if (!editor || !enablePromptParamRefsOf(propsRef.current!)) return

    const p = propsRef.current!
    if (p.mode === 'storyboard') {
      editor.syncPromptParamRef(PROMPT_TYPE.composition as PromptParamType, payload.selectedComposition)
      editor.syncPromptParamRef(PROMPT_TYPE.shot_size as PromptParamType, payload.selectedShotSize)
      editor.syncPromptParamRef(PROMPT_TYPE.camera_angle as PromptParamType, payload.selectedCameraAngle)
      editor.syncPromptParamRef(PROMPT_TYPE.focal_length as PromptParamType, payload.selectedFocalLength)
      editor.syncPromptParamRef(PROMPT_TYPE.color_tone as PromptParamType, payload.selectedColorTone)
      editor.syncPromptParamRef(PROMPT_TYPE.lighting as PromptParamType, payload.selectedLighting)
      editor.syncPromptParamRef(PROMPT_TYPE.exposure_blur as PromptParamType, payload.selectedTechnique)
    } else if (p.mode === 'storyboardVideo') {
      editor.syncPromptParamRef(
        PROMPT_TYPE.camera_movement as PromptParamType,
        payload.selectedCameraMovement
      )
      editor.syncPromptParamRef(
        PROMPT_TYPE.shooting_technique as PromptParamType,
        payload.selectedShootingTechnique
      )
    } else if (p.mode === 'imageToVideo') {
      editor.syncPromptParamRef(
        PROMPT_TYPE.shooting_technique as PromptParamType,
        payload.imageToVideoSelectedShootingTechnique ?? payload.selectedShootingTechnique
      )
      if (payload.imageToVideoSelectedCameraMovement !== undefined) {
        editor.syncPromptParamRef(
          PROMPT_TYPE.camera_movement as PromptParamType,
          payload.imageToVideoSelectedCameraMovement ?? null
        )
      }
    }
  }

  function onParamSettingsConfirm(payload: ParamSettingsConfirmPayload) {
    propsRef.current!.onParamSettingsConfirm?.(payload)
    // 不再 syncMissing 全量回填：灵感空间内已通过 toggle/syncAssetRefs 维护描述框；
    // 确认后条变化由差分同步处理，避免清空描述后再确认把旧资产整批写回
    nextTick(() => {
      prevLocalStripImageAssetsRef.current = snapshotLocalStripImageAssets()
      stripAssetSyncSeededRef.current = true
      refreshPromptAssetRefKeySnapshot()

      let promptPatched = false
      const p = propsRef.current!
      if (p.mode === 'imageToVideo') {
        const cameraMovement = payload.imageToVideoSelectedCameraMovement ?? null
        if (cameraMovement?.value && cameraMovement.key !== 'none') {
          const editor = getMainPromptEditor()
          const plain = editor?.getPlainPrompt?.() || storyboardPromptHtmlToPlain(p.prompt)
          const nextPlain = insertParamLabelIntoMarkdownSection(plain, '运镜', cameraMovement.value)
          if (nextPlain !== plain) {
            const html = storyboardPromptPlainToHtml(
              nextPlain,
              storyboardPromptAssetsRef.current,
              storyboardPromptParamGroupsRef.current
            )
            p.onPromptChange?.(html)
            promptPatched = true
          }
        }
      }

      const runParamSync = () => {
        syncParamRefsFromPayload(payload)
        if (enablePromptAssetRefsOf(propsRef.current!) || enablePromptParamRefsOf(propsRef.current!)) {
          getMainPromptEditor()?.hydratePromptRefEmbeds()
        }
        refreshPromptAssetRefKeySnapshot()
      }

      if (promptPatched) nextTick(runParamSync)
      else runParamSync()
    })
  }

  /** 在当前文本域光标处插入 @ 资产引用（始终插在光标处，不挪到已有同资产标签） */
  function insertPromptAssetRefsAtCaret(assets: PromptAssetItem[]): boolean {
    const editor = getActivePromptEditor()
    if (!editor || !assets.length) return false
    for (const asset of assets) {
      editor.insertPromptAssetRef(asset)
    }
    return true
  }

  function onPromptParamChange(payload: {
    paramType: PromptParamType
    selection: { key: string; value: string } | null
  }) {
    const binding = paramSelectionBindings().find((b) => b.paramType === payload.paramType)
    binding?.emit(payload.selection)
  }

  const multiParamAssetReferenceList = useMemo(() => {
    if (props.mode !== 'imageToVideo') return []
    return [...props.sceneImages, ...props.characterImages, ...props.propImages, ...props.otherImages].filter(
      (img) => img?.url || img?.thumbnail
    )
  }, [props.mode, props.sceneImages, props.characterImages, props.propImages, props.otherImages])
  const multiParamAssetReferenceListRef = useRef(multiParamAssetReferenceList)
  multiParamAssetReferenceListRef.current = multiParamAssetReferenceList

  function onMultiParamStripRemove(index: number) {
    const list = multiParamAssetReferenceListRef.current
    const imgCount = list.length
    if (index < imgCount) {
      const img = list[index]
      const localAssets = snapshotLocalStripImageAssets()
      const matched =
        localAssets.find((a) => String(a.assetId) === String(img?.id ?? '')) ||
        localAssets.find(
          (a) =>
            String(a.name || '').trim() ===
            String(img?.title || img?.name || '')
              .replace(/^@/, '')
              .trim()
        )
      promptStripSyncGuardRef.current = true
      try {
        if (matched) {
          removePromptAssetRefsByAssets([matched])
        } else if (img) {
          getActivePromptEditor()?.removePromptAssetRefByMatch({
            assetId: String(img.id || ''),
            name: String(img.title || img.name || '')
          })
        }
        propsRef.current!.onRemoveMultiParamAssetReference?.(index)
      } finally {
        promptStripSyncGuardRef.current = false
        prevLocalStripImageAssetsRef.current = snapshotLocalStripImageAssets()
        refreshPromptAssetRefKeySnapshot()
      }
      return
    }
    propsRef.current!.onRemoveReferenceAudio?.(index - imgCount)
  }

  // ---- 原 watch 平移 ----

  // watch([assets, referenceImage(s), isSettingExpanded, mode], deep, immediate)
  useEffect(() => {
    nextTick(() => {
      const p = propsRef.current!
      if (p.mode === 'storyboardVideo') {
        if (paramSettingsOpenRef.current || p.suppressPromptReactiveSync) return
        syncStoryboardVideoPromptWithoutImageRefs()
      } else {
        // paramSettingsOpen / suppress 时函数内只推进快照，不写描述框
        syncStoryboardPromptAssetRefsInEditor()
        if (
          !paramSettingsOpenRef.current &&
          !p.suppressPromptReactiveSync &&
          !getActivePromptEditor()
        ) {
          nextTick(() => syncStoryboardPromptAssetRefsInEditor())
        }
      }
      if (paramSettingsOpenRef.current || propsRef.current!.suppressPromptReactiveSync) return
      getActivePromptEditor()?.hydratePromptRefEmbeds()
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    props.sceneImages,
    props.characterImages,
    props.propImages,
    props.otherImages,
    props.referenceImage,
    props.referenceImages,
    props.isSettingExpanded,
    props.mode
  ])

  // watch(prompt, immediate)
  useEffect(() => {
    const html = props.prompt
    nextTick(() => {
      syncStripImagesFromPromptRefDiff(html || '')
      syncStripAudiosFromPromptRefDiff(html || '')
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.prompt])

  // watch(paramSettingsOpen)：关闭后重新种子快照并补一次条→描述联动
  const prevParamSettingsOpenRef = useRef(paramSettingsOpen)
  useEffect(() => {
    const wasOpen = prevParamSettingsOpenRef.current
    prevParamSettingsOpenRef.current = paramSettingsOpen
    if (wasOpen && !paramSettingsOpen) {
      nextTick(() => {
        prevLocalStripImageAssetsRef.current = snapshotLocalStripImageAssets()
        stripAssetSyncSeededRef.current = true
        refreshPromptAssetRefKeySnapshot()
        syncStoryboardPromptAssetRefsInEditor()
      })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [paramSettingsOpen])

  // watch([各参数选中值, isSettingExpanded, mode, paramGroups], deep)
  useEffect(() => {
    nextTick(() => {
      if (propsRef.current!.suppressPromptReactiveSync) return
      if (paramSettingsOpenRef.current) return
      syncStoryboardPromptParamRefsInEditor()
      getActivePromptEditor()?.hydratePromptRefEmbeds()
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    props.selectedComposition,
    props.selectedShotSize,
    props.selectedCameraAngle,
    props.selectedFocalLength,
    props.selectedColorTone,
    props.selectedLighting,
    props.selectedTechnique,
    props.selectedCameraMovement,
    props.selectedShootingTechnique,
    props.imageToVideoSelectedCameraMovement,
    props.imageToVideoSelectedShootingTechnique,
    props.isSettingExpanded,
    props.mode,
    storyboardPromptParamGroups
  ])

  usePromptSelectionMigration({
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
  })
  return {
    paramSettingsOpen,
    setParamSettingsOpen,
    paramSettingsOpenRef,
    enablePromptAssetRefs,
    enablePromptParamRefs,
    storyboardPromptAssets,
    storyboardPromptParamGroups,
    storyboardVideoReferenceList,
    multiParamAssetReferenceList,
    onPromptParamChange,
    onDraftToggleAssetRef,
    onDraftSyncAssetRefs,
    onParamSettingsConfirm,
    insertPromptAssetRefsAtCaret,
    onMultiParamStripRemove,
    // 词库选项透出给设置面板下拉
    dictionary: {
      cameraMovementOptions,
      shootingTechniqueOptions,
      compositionOptions,
      shotSizeOptions,
      cameraAngleOptions,
      focalLengthOptions,
      colorToneOptions,
      lightingOptions,
      techniqueOptions
    }
  }
}

function enablePromptAssetRefsOf(p: ResolvedStoryboardGeneratePanelProps): boolean {
  return (
    p.mode === 'storyboard' ||
    p.mode === 'imageToVideo' ||
    p.mode === 'storyboardVideo' ||
    p.mode === 'edgeVideo'
  )
}

function enablePromptParamRefsOf(p: ResolvedStoryboardGeneratePanelProps): boolean {
  return (
    p.mode === 'storyboard' ||
    p.mode === 'storyboardVideo' ||
    p.mode === 'imageToVideo' ||
    p.mode === 'edgeVideo'
  )
}
