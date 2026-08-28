'use client'

import { useEffect,useRef } from 'react'
import type { HorizontalScrollTabBarHandle } from '~/components/common/HorizontalScrollTabBar'
import { useCreateFlowScopeChangedResume } from '~/composables/useCreateFlowLiveGenResume'
import { useRouteLike } from '~/composables/useRouteLike'
import { useStoryboardModalHeaderTabs } from '~/composables/useStoryboardModalHeaderTabs'
import { useCreationStore } from '~/stores/creation'
import type { UserModelListItem } from '~/types/business-api'
import { pickStoryboardCoverImage } from '~/utils/storyboardImageCover'
import { clearModalImageGenUserDismissed } from '~/utils/storyboardImageModalGenSession'
import type { StoryboardModalHeaderTab } from '~/utils/storyboardModalHeaderTabs'
import type { PromptAssetItem } from '~/utils/storyboardPromptAssetRef'
import type { StoryboardGeneratePanelHandle } from '../StoryboardGeneratePanel'
import type {
CanvasToolbarKey,
DialogueSourceImage,
EditStoryboardImageModalCtx,
EditStoryboardImageModalProps,
GenerationSettingsValue,
LeftActiveTab,
ResolvedEditStoryboardImageModalProps,
SelectAssetModalType,
SelectedParamValue,
SettingKey,
StoryboardCanvasOverlayTaskKind
} from './types'
import { nextTick,useMirrored } from './useMirrored'
import { useStoryboardModalCanvasOverlay } from './useStoryboardModalCanvasOverlay'
import { useStoryboardModalDialogue } from './useStoryboardModalDialogue'
import { useStoryboardModalGenerate } from './useStoryboardModalGenerate'
import { useStoryboardModalImageActions } from './useStoryboardModalImageActions'
import { useStoryboardModalModels } from './useStoryboardModalModels'
import { useStoryboardModalPrompt } from './useStoryboardModalPrompt'
import { useStoryboardModalRecords } from './useStoryboardModalRecords'
import { useStoryboardModalSessionState } from './useStoryboardModalSessionState'

export function useEditStoryboardImageModalController(rawProps: EditStoryboardImageModalProps): {
  ctx: EditStoryboardImageModalCtx
  headerTabsForDisplay: StoryboardModalHeaderTab[]
} {
  /** 原 withDefaults：editorScopeKey 默认 'storyboard-image' */
  const props: ResolvedEditStoryboardImageModalProps = {
    ...rawProps,
    editorScopeKey: rawProps.editorScopeKey ?? 'storyboard-image'
  }
  const propsRef = useRef(props)
  propsRef.current = props

  const route = useRouteLike()
  const routeRef = useRef(route)
  routeRef.current = route

  /** 渲染期订阅整棵 creation store：任务快照 / 批量进度 / panel loading 均驱动本弹窗渲染 */
  useCreationStore()

  // —— ctx：稳定引用，每渲染帧 Object.assign 刷新（原 Vue setup 单闭包的 React 适配）——
  const ctx = useRef({} as EditStoryboardImageModalCtx).current

  const { headerTabs, refreshHeaderTabs } = useStoryboardModalHeaderTabs({
    open: props.open,
    recordType: 'image',
    // 打开/切 Tab 由 syncSceneDetailAndRestore 统一 force 一次，避免与画布刷新双打
    autoRefreshOnOpen: false,
    scenes: () =>
      propsRef.current.scenes.map((scene) => ({
        name: scene.name,
        storyboardId: scene.storyboardId
      })),
    route,
    headerOptions: () => ({
      resolveFallbackThumbnailUrl: (sceneIndex: number) => {
        const cover = pickStoryboardCoverImage(propsRef.current.scenes[sceneIndex]?.images)
        return String(cover?.url || cover?.thumbnail || '').trim()
      }
    })
  })

  const headerTabsForDisplay: StoryboardModalHeaderTab[] = headerTabs.length
    ? headerTabs
    : props.scenes.map((scene, sceneIndex) => ({
        sceneIndex,
        storyboardId: Number.isFinite(Number(scene.storyboardId))
          ? Number(scene.storyboardId)
          : undefined,
        name: scene.name,
        thumbnailUrl: String(
          pickStoryboardCoverImage(scene.images)?.url ||
            pickStoryboardCoverImage(scene.images)?.thumbnail ||
            ''
        ).trim(),
        hasFinalAsset: false
      }))

  // —— 顶层可变状态（原 ref）——
  const currentSceneIndex = useMirrored<number>(props.sceneIndex)
  const currentImageIndex = useMirrored<number>(
    props.initialImageIndex !== null && props.initialImageIndex !== undefined
      ? props.initialImageIndex
      : 0
  )
  const leftActiveTab = useMirrored<LeftActiveTab>('generate')
  const showStoryboardScriptModal = useMirrored(false)
  const scriptEditorKey = useMirrored(0)
  const editingImageTitleIndex = useMirrored<number | null>(null)
  const editingImageTitle = useMirrored('')
  const localSceneImages = useMirrored<any[]>([])
  const canvasToolbarHoverKey = useMirrored<CanvasToolbarKey | null>(null)

  const modelDropdownExpanded = useMirrored(false)
  const initImageModelGen = useRef(0)
  const cachedStoryboardImageAgentModelCodes = useRef<string[]>([])
  const dialogueSourceImages = useMirrored<DialogueSourceImage[]>([])
  const dialogueInstructionHtml = useMirrored('')
  const showDialogueImportModal = useMirrored(false)
  const dialogueModelDropdownExpanded = useMirrored(false)
  const dialogueSettings = useMirrored<GenerationSettingsValue>({
    model: '',
    aspectRatio: '16:9',
    count: 1,
    quality: '2k'
  })
  const generationSettings = useMirrored<GenerationSettingsValue>({
    model: '',
    aspectRatio: '16:9',
    count: 1,
    quality: '3k'
  })
  const multiViewModelDropdownExpanded = useMirrored(false)
  const upscaleModelPool = useMirrored<UserModelListItem[]>([])
  const multiViewSettings = useMirrored<{ model: string }>({ model: '' })
  const nineGridSettings = useMirrored<{ model: string }>({ model: '' })
  const nineGridAspectRatio = useMirrored('1:1')

  const sceneImages = useMirrored<any[]>([])
  const characterImages = useMirrored<any[]>([])
  const propImages = useMirrored<any[]>([])
  const otherImages = useMirrored<any[]>([])
  const storyboardGeneratePanelRef = useRef<StoryboardGeneratePanelHandle | null>(null)

  const selectAssetModalOpen = useMirrored(false)
  const selectAssetModalType = useMirrored<SelectAssetModalType>('scene')
  const showOtherListDropdown = useMirrored(false)

  const activeSettingKey = useMirrored<SettingKey | null>(null)

  const isSettingExpanded = useMirrored(false)
  const compositionDesc = useMirrored('')
  const selectedComposition = useMirrored<SelectedParamValue>(null)
  const selectedShotSize = useMirrored<SelectedParamValue>(null)
  const selectedCameraAngle = useMirrored<SelectedParamValue>(null)
  const selectedFocalLength = useMirrored<SelectedParamValue>(null)
  const selectedColorTone = useMirrored<SelectedParamValue>(null)
  const selectedLighting = useMirrored<SelectedParamValue>(null)
  const selectedTechnique = useMirrored<SelectedParamValue>(null)

  const storyboardPrompt = useMirrored('')
  const resolvedPromptAssets = useMirrored<PromptAssetItem[]>([])
  const storyboardPromptProgrammaticSyncDepth = useMirrored(0)
  const isGeneratingPrompt = useMirrored(false)
  const isGeneratingStoryboardImage = useMirrored(false)
  const storyboardGenerateProgressText = useMirrored('分镜图生成中…')
  const storyboardGenerateTargetKey = useMirrored('')
  const promptGenerateTargetKey = useMirrored('')

  const resumeStoryboardImageFollowGen = useRef(0)
  const resumeStoryboardPromptFollowGen = useRef(0)
  const resumeDialogueFollowGen = useRef(0)
  const activePromptFollowStoryboardIdsRef = useRef(new Set<number>())
  const resumeCanvasOverlayFollowGen = useRef(0)

  const showAssetLibraryModal = useMirrored(false)
  const showMaterialFromLibraryModal = useMirrored(false)
  const showMultiAngleModal = useMirrored(false)
  const multiAngleTargetIndex = useRef<number | null>(null)
  const multiAngleImageUrl = useMirrored('')
  const materialLibraryCategoryKey = useMirrored<string>('pose')
  const materialImportAppendToStoryPrompt = useMirrored(false)
  const isSelectingSceneImage = useMirrored(false)
  const selectedSceneImageIndex = useMirrored<number | null>(null)
  const addingAfterIndex = useRef<number | null>(null)
  const pendingImage = useRef<any | null>(null)
  const addedImageIds = useMirrored<Set<string>>(() => new Set())
  const isSettingFinalImage = useMirrored(false)
  const uploadingLocalImageAtKey = useMirrored('')
  const assetLibraryImportInFlight = useRef(false)
  const isDeletingRecord = useMirrored(false)

  const mainContentRef = useRef<HTMLDivElement | null>(null)
  const sceneTabBarRef = useRef<HorizontalScrollTabBarHandle | null>(null)

  const leftPanelLoading = useMirrored(false)
  const rightPanelLoading = useMirrored(false)

  const showTouchEditModal = useMirrored(false)
  const touchEditImageUrl = useMirrored('')

  const upscaleUiPhase = useMirrored<'idle' | 'running' | 'failed'>('idle')
  const upscaleTargetKey = useMirrored('')
  const upscaleProgressText = useMirrored('高清处理中…')
  const upscaleFailedMessage = useMirrored('')
  const upscaleContext = useRef<{ sceneIndex: number; imageIndex: number } | null>(null)
  const canvasOverlayTaskKind = useMirrored<StoryboardCanvasOverlayTaskKind | null>(null)

  function scrollActiveSceneTabIntoView() {
    sceneTabBarRef.current?.scrollItemIntoView('.scene-image-tab.active')
    sceneTabBarRef.current?.refresh()
  }

  async function syncSceneDetailAndRestore(sceneIdx: number) {
    void ctx.loadCurrentStoryboardPrompt()
    await ctx.ensureModalLoadingRestored(sceneIdx)
    // 打开/切 Tab：顶部 Tab 与画布共用一次 force list-by-storyboard（外层 list 只带主图）
    await refreshHeaderTabs(true)
    await ctx.refreshSceneRecords(sceneIdx)
    void ctx.restoreStoryboardImageGenerateIfNeeded(sceneIdx)
    void ctx.restoreStoryboardDialogueGenerateIfNeeded(sceneIdx)
    void ctx.restoreStoryboardPromptGenerateIfNeeded(sceneIdx)
    void ctx.restoreStoryboardCanvasOverlayGenerateIfNeeded(sceneIdx)
  }

  Object.assign(ctx, {
    props: () => propsRef.current,
    route: () => routeRef.current,
    store: () => useCreationStore.getState(),
    emitOpenChange: (value: boolean) => propsRef.current.onOpenChange(value),
    emitUpdate: (sceneIndex: number, data: any) => propsRef.current.onUpdate(sceneIndex, data),

    currentSceneIndex,
    currentImageIndex,
    leftActiveTab,
    showStoryboardScriptModal,
    scriptEditorKey,
    editingImageTitleIndex,
    editingImageTitle,
    localSceneImages,
    canvasToolbarHoverKey,

    modelDropdownExpanded,
    initImageModelGen,
    cachedStoryboardImageAgentModelCodes,
    dialogueSourceImages,
    dialogueInstructionHtml,
    showDialogueImportModal,
    dialogueModelDropdownExpanded,
    dialogueSettings,
    generationSettings,
    multiViewModelDropdownExpanded,
    upscaleModelPool,
    multiViewSettings,
    nineGridSettings,
    nineGridAspectRatio,

    sceneImages,
    characterImages,
    propImages,
    otherImages,
    storyboardGeneratePanelRef,

    selectAssetModalOpen,
    selectAssetModalType,
    showOtherListDropdown,

    activeSettingKey,

    isSettingExpanded,
    compositionDesc,
    selectedComposition,
    selectedShotSize,
    selectedCameraAngle,
    selectedFocalLength,
    selectedColorTone,
    selectedLighting,
    selectedTechnique,

    storyboardPrompt,
    resolvedPromptAssets,
    storyboardPromptProgrammaticSyncDepth,
    isGeneratingPrompt,
    isGeneratingStoryboardImage,
    storyboardGenerateProgressText,
    storyboardGenerateTargetKey,
    promptGenerateTargetKey,

    resumeStoryboardImageFollowGen,
    resumeStoryboardPromptFollowGen,
    resumeDialogueFollowGen,
    activePromptFollowStoryboardIds: activePromptFollowStoryboardIdsRef.current,
    resumeCanvasOverlayFollowGen,

    showAssetLibraryModal,
    showMaterialFromLibraryModal,
    showMultiAngleModal,
    multiAngleTargetIndex,
    multiAngleImageUrl,
    materialLibraryCategoryKey,
    materialImportAppendToStoryPrompt,
    isSelectingSceneImage,
    selectedSceneImageIndex,
    addingAfterIndex,
    pendingImage,
    addedImageIds,
    isSettingFinalImage,
    uploadingLocalImageAtKey,
    assetLibraryImportInFlight,
    isDeletingRecord,

    mainContentRef,
    sceneTabBarRef,

    leftPanelLoading,
    rightPanelLoading,

    showTouchEditToolbar: false,
    showTouchEditModal,
    touchEditImageUrl,

    upscaleUiPhase,
    upscaleTargetKey,
    upscaleProgressText,
    upscaleFailedMessage,
    upscaleContext,
    canvasOverlayTaskKind,

    refreshHeaderTabs,
    scrollActiveSceneTabIntoView,
    syncSceneDetailAndRestore
  })

  Object.assign(ctx, useStoryboardModalSessionState(ctx))
  Object.assign(ctx, useStoryboardModalRecords(ctx))
  Object.assign(ctx, useStoryboardModalModels(ctx))
  Object.assign(ctx, useStoryboardModalCanvasOverlay(ctx))
  Object.assign(ctx, useStoryboardModalGenerate(ctx))
  Object.assign(ctx, useStoryboardModalPrompt(ctx))
  Object.assign(ctx, useStoryboardModalDialogue(ctx))
  Object.assign(ctx, useStoryboardModalImageActions(ctx))

  /**
   * 原 watch(() => [props.scenes, currentSceneIndex.value], sync, { immediate, deep })：
   * React 下父级更新 scenes 必然换引用（onUpdate 回写），以引用 + 当前分镜下标为依赖。
   */
  useEffect(() => {
    ctx.syncLocalSceneImagesFromSceneIndex(currentSceneIndex.get(), { preservePending: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.scenes, currentSceneIndex.value])

  /** 原 watch(() => props.sceneIndex)：外部切分镜时联动内部 Tab */
  const prevSceneIndexPropRef = useRef(props.sceneIndex)
  useEffect(() => {
    if (prevSceneIndexPropRef.current === props.sceneIndex) return
    prevSceneIndexPropRef.current = props.sceneIndex
    if (props.sceneIndex !== currentSceneIndex.get()) {
      void ctx.switchScene(props.sceneIndex)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.sceneIndex])

  /** 原 watch(() => [props.open, props.initialImageIndex], immediate) */
  useEffect(() => {
    const isOpen = props.open
    const imageIndex = props.initialImageIndex
    if (isOpen && typeof imageIndex === 'number' && imageIndex >= 0) {
      void nextTick(() => {
        if (ctx.currentSceneImages().length > imageIndex) {
          currentImageIndex.set(imageIndex)
        }
      })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.open, props.initialImageIndex])

  /** 原 watch(() => props.open, immediate)：打开初始化 / 关闭收尾 */
  useEffect(() => {
    const isOpen = props.open
    if (isOpen) {
      const si = propsRef.current.sceneIndex
      const sid = ctx.sceneStoryboardIdNum(si)
      if (sid != null) {
        clearModalImageGenUserDismissed(ctx.storyboardImageModalSessionScope())
      }
      ctx.ensureModalSessionFromStoreTask(si)
      void ctx.ensurePromptDictLoaded()
      void ctx.initImageModelOptions()
      currentSceneIndex.set(propsRef.current.sceneIndex)
      showStoryboardScriptModal.set(false)
      const sceneImagesList = propsRef.current.scenes[si]?.images || []
      ctx.syncAddedImageIdsFromList(sceneImagesList)
      ctx.primeStoryboardImageLoadingUi(si)
      ctx.primeDialogueLoadingUi(si)
      ctx.primeCanvasOverlayFromSession(si)
      void syncSceneDetailAndRestore(si)
      void nextTick(() => scrollActiveSceneTabIntoView())
    } else {
      initImageModelGen.current++
      const si = currentSceneIndex.get()
      if (!ctx.isAnyModalGenerationPendingForScene(si)) {
        resumeStoryboardImageFollowGen.current++
        resumeDialogueFollowGen.current++
      }
      resumeStoryboardPromptFollowGen.current++
      showStoryboardScriptModal.set(false)
      addedImageIds.get().clear()
      pendingImage.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.open])

  /** 原 watch(() => currentStoryboardId.value, (id, prevId))：分镜数据晚到时补一次详情同步 */
  const currentStoryboardIdValue = ctx.currentStoryboardId()
  const prevStoryboardIdRef = useRef<number | null | undefined>(undefined)
  useEffect(() => {
    const prevId = prevStoryboardIdRef.current
    prevStoryboardIdRef.current = currentStoryboardIdValue
    if (prevId === undefined) return
    const id = currentStoryboardIdValue
    if (!propsRef.current.open) return
    if (!id || id === prevId) return
    if (prevId == null) return
    void syncSceneDetailAndRestore(currentSceneIndex.get())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentStoryboardIdValue])

  /** 原 watch(() => props.scenes.length)：Tab 数量变化后刷新横向滚动条 */
  const prevScenesLenRef = useRef(props.scenes.length)
  useEffect(() => {
    if (prevScenesLenRef.current === props.scenes.length) return
    prevScenesLenRef.current = props.scenes.length
    if (!propsRef.current.open) return
    void nextTick(() => sceneTabBarRef.current?.refresh())
     
  }, [props.scenes.length])

  /** 原 watch(showMaterialFromLibraryModal)：关闭素材库时复位「导入其他」标记 */
  const prevMaterialModalOpenRef = useRef(showMaterialFromLibraryModal.value)
  useEffect(() => {
    if (prevMaterialModalOpenRef.current === showMaterialFromLibraryModal.value) return
    prevMaterialModalOpenRef.current = showMaterialFromLibraryModal.value
    if (!showMaterialFromLibraryModal.value) materialImportAppendToStoryPrompt.set(false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [showMaterialFromLibraryModal.value])

  useCreateFlowScopeChangedResume(() => {
    if (!propsRef.current.open) return
    const si = currentSceneIndex.get()
    void (async () => {
      await ctx.ensureModalLoadingRestored(si)
      void ctx.restoreStoryboardImageGenerateIfNeeded(si)
      void ctx.restoreStoryboardDialogueGenerateIfNeeded(si)
      void ctx.restoreStoryboardPromptGenerateIfNeeded(si)
      void ctx.restoreStoryboardCanvasOverlayGenerateIfNeeded(si)
    })()
  })

  return { ctx, headerTabsForDisplay }
}
