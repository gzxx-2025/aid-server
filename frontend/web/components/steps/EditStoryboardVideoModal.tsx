'use client'

/**
 * 编辑分镜视频弹窗（原 components/steps/EditStoryboardVideoModal.vue，5632 行）。
 *
 * 对外 props 契约（对照原 defineProps / defineEmits，本组件无 defineExpose）：
 * - open: boolean                      —— 原 v-model:open 读侧
 * - sceneIndex: number                 —— 初始激活分镜
 * - scenes: EditStoryboardVideoModalScene[]（name / videos / scriptContent / scriptPanelTitle / storyboardId / storyboardImages）
 * - editorScopeKey?: string            —— 弹窗实例作用域（默认 'storyboard-video'）
 * - onOpenChange(value)                —— 原 emit('update:open', value)
 * - onJumpToStoryboardScript(sceneIndex) —— 原 emit('jump-to-storyboard-script', sceneIndex)
 * - onUpdate(sceneIndex, { name?, videos?, scriptContent?, scriptTitle?, title? }) —— 原 emit('update', ...)
 *
 * 原 Vue 调用点（后续批次接线）：
 * - aid-pc/components/steps/StoryboardVideo.vue（分镜视频步骤页）
 * - aid-pc/components/steps/VideoPreview.vue（成片预览）
 *
 * 拆分：会话/loading 恢复、生成记录、出片任务、提示词、模型池、参考图、播放控制、watch 平移
 * 均在 edit-storyboard-video/ 子目录（ctx 延迟绑定，见 types.ts）。
 */

import { useMemo, useRef } from 'react'
import { Button, Modal, message } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import {
  HorizontalScrollTabBar,
  type HorizontalScrollTabBarHandle
} from '~/components/common/HorizontalScrollTabBar'
import {
  resolveStoryboardModalTabMedia,
  StoryboardModalTabThumbnail
} from '~/components/common/StoryboardModalTabThumbnail'
import { cancelPendingVideoPosters } from '~/utils/ensureVideoPoster'
import { useStoryboardModalHeaderTabs } from '~/composables/useStoryboardModalHeaderTabs'
import { useRouteLike, getRouteLikeSnapshot } from '~/composables/useRouteLike'
import {
  activeStoryboardVideoModalOwnedFollowIds
} from '~/composables/useStoryboardVideoBatchGenerate'
import { findStoryboardVideoGenTaskInScopes } from '~/composables/useCreationStoreHydration'
import { useCreationStore } from '~/stores/creation'
import {
  isStoryboardVideoTabVisible,
  resolvePrimaryStoryboardVideoTab,
  type StoryboardVideoModalTabKey
} from '~/utils/creationModeUiRules'
import { markStoryboardVideoModalUserDismissed } from '~/utils/storyboardVideoModalGenSession'
import type { StoryboardGeneratePanelHandle } from './StoryboardGeneratePanel'
import type { StoryboardPanel } from '~/types'
import { useMirrored } from './edit-storyboard-video/useMirrored'
import type {
  EditStoryboardVideoModalProps,
  ResolvedEditStoryboardVideoModalProps,
  SelectAssetModalType,
  VideoModalCtx
} from './edit-storyboard-video/types'
import { useVideoModalSession } from './edit-storyboard-video/useVideoModalSession'
import { useVideoPreviewPlayback } from './edit-storyboard-video/useVideoPreviewPlayback'
import { useVideoModalRecords } from './edit-storyboard-video/useVideoModalRecords'
import { useVideoModalPrompt } from './edit-storyboard-video/useVideoModalPrompt'
import { useVideoModalPromptFlows } from './edit-storyboard-video/useVideoModalPromptFlows'
import { useVideoModalModels } from './edit-storyboard-video/useVideoModalModels'
import { useVideoModalReferences } from './edit-storyboard-video/useVideoModalReferences'
import { useVideoModalGenerate } from './edit-storyboard-video/useVideoModalGenerate'
import { useVideoModalGenerateActions } from './edit-storyboard-video/useVideoModalGenerateActions'
import { useVideoModalEffects } from './edit-storyboard-video/useVideoModalEffects'
import {
  VideoHistoryPanel,
  VideoCanvasSection,
  VideoStageSkeleton
} from './edit-storyboard-video/VideoStagePanels'
import { VideoConfigPanel } from './edit-storyboard-video/VideoConfigPanel'
import { VideoModalSubModals } from './edit-storyboard-video/VideoModalSubModals'
import '~/assets/css/history-record-card.css'
import './edit-storyboard-video/edit-storyboard-video.css'
import './edit-storyboard-video/edit-storyboard-video-canvas.css'

const TAB_SWITCH_SKELETON_MS = 380

export type {
  EditStoryboardVideoModalProps,
  EditStoryboardVideoModalScene
} from './edit-storyboard-video/types'

export function EditStoryboardVideoModal(rawProps: EditStoryboardVideoModalProps) {
  // 原 withDefaults
  const props: ResolvedEditStoryboardVideoModalProps = {
    ...rawProps,
    editorScopeKey: rawProps.editorScopeKey ?? 'storyboard-video'
  }
  /** 事件回调 / 异步流程内读最新 props */
  const propsRef = useRef(props)
  propsRef.current = props

  const routeLike = useRouteLike()

  // 渲染期订阅 store（selector 防全量重渲）
  const scriptPanelsValue = useCreationStore(
    (s) => (s.formData.storyboardScript.panels || []) as StoryboardPanel[]
  )
  const creationModeValue = useCreationStore(
    (s) => s.formData.globalSetting?.creationMode || 'i2v'
  )
  const currentProjectIdValue = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeIdValue = useCreationStore((s) => s.currentEpisodeId)
  const scriptPanelsRef = useRef(scriptPanelsValue)
  scriptPanelsRef.current = scriptPanelsValue
  const creationModeRef = useRef(creationModeValue)
  creationModeRef.current = creationModeValue

  // —— 顶层可变状态（原 ref → Mirrored）——
  const currentSceneIndex = useMirrored(props.sceneIndex)
  const viewMode = useMirrored<'list' | 'card'>('list')
  const leftActiveTab = useMirrored<StoryboardVideoModalTabKey>('imageToVideo')
  const leftPanelLoading = useMirrored(false)
  const rightPanelLoading = useMirrored(false)
  const videoGenerateTargetKey = useMirrored('')
  const videoGenerateProgressText = useMirrored('分镜视频提交中…')
  const selectedVideoIdx = useMirrored(0)
  const showStoryboardScriptModal = useMirrored(false)
  const scriptEditorKey = useMirrored(0)
  const stepPanelImagesCache = useMirrored<Record<number, any[]>>({})
  const selectReferenceModalOpen = useMirrored(false)

  const imageToVideoPrompt = useMirrored('')
  const resolvedVideoPromptAssets = useMirrored<any[]>([])
  const videoPromptProgrammaticSyncDepth = useMirrored(0)
  const isGeneratingVideoPrompt = useMirrored(false)
  const isSavingVideoPrompt = useMirrored(false)
  const videoPromptGenerateTargetKey = useMirrored('')
  const multiParamPrompt = useMirrored('')
  const resolvedMultiParamPromptAssets = useMirrored<any[]>([])
  const isGeneratingMultiParamPrompt = useMirrored(false)
  const multiParamPromptGenerateTargetKey = useMirrored('')
  const edgeVideoPrompt = useMirrored('')
  const edgeVideoPromptByStoryboardId = useMirrored<Record<string, string>>({})

  const referenceImages = useMirrored<any[]>([])
  const nineGridEnabled = useMirrored(false)
  const isImageToVideoSettingExpanded = useMirrored(true)
  const activeImageToVideoSettingKey = useMirrored<'cameraMovement' | 'shootingTechnique' | null>(null)
  const selectedCameraMovement = useMirrored<{ key: string; value: string } | null>(null)
  const cameraMovementDesc = useMirrored('')
  const selectedImageToVideoShootingTechnique = useMirrored<{ key: string; value: string } | null>(null)

  const isMultiParamSettingExpanded = useMirrored(true)
  const activeMultiParamSettingKey = useMirrored<string | null>(null)
  const multiParamShootingTechnique = useMirrored<{ key: string; value: string } | null>(null)

  const isEdgeVideoSettingExpanded = useMirrored(true)
  const firstFrameImage = useMirrored<any>(null)
  const lastFrameImage = useMirrored<any>(null)
  const edgeFrameImagesByStoryboardId = useMirrored<Record<string, { first: any; last: any }>>({})
  const selectEdgeFrameModalOpen = useMirrored(false)
  const edgeFramePickTarget = useMirrored<'first' | 'last'>('first')

  const sceneImages = useMirrored<any[]>([])
  const characterImages = useMirrored<any[]>([])
  const propImages = useMirrored<any[]>([])
  const otherImages = useMirrored<any[]>([])

  const selectAssetModalOpen = useMirrored(false)
  const selectMultiParamReferenceModalOpen = useMirrored(false)
  const selectAssetModalType = useMirrored<SelectAssetModalType>('scene')
  const showMaterialFromLibraryModal = useMirrored(false)
  const materialLibraryCategoryKey = useMirrored<string>('pose')
  const showVideoLibraryModal = useMirrored(false)

  const imageToVideoModel = useMirrored('')
  const multiParamVideoModel = useMirrored('')
  const edgeVideoModel = useMirrored('')
  const gridVideoModel = useMirrored('')
  const imageToVideoModelDropdownExpanded = useMirrored(false)
  const multiParamVideoModelDropdownExpanded = useMirrored(false)
  const edgeVideoModelDropdownExpanded = useMirrored(false)
  const gridVideoModelDropdownExpanded = useMirrored(false)

  const referenceAudios = useMirrored<any[]>([])
  const videoAspectRatio = useMirrored('16:9')
  const videoDuration = useMirrored('5')
  const videoCount = useMirrored(1)
  const videoQuality = useMirrored('1080p')
  const videoAudio = useMirrored('with_audio')
  const recommendedDurationSecondsRaw = useMirrored<number | null>(null)

  const isSettingFinalVideo = useMirrored(false)
  const isDeletingRecord = useMirrored(false)
  const playingVideoIdx = useMirrored(-1)
  const videoPreviewMediaReady = useMirrored<Record<number, boolean>>({})

  // —— 非渲染可变量 ——
  const resumeStoryboardVideoFollowGen = useRef(0)
  const resumeStoryboardVideoPromptFollowGen = useRef(0)
  const initVideoModelGen = useRef(0)
  const cachedImageToVideoAgentModelCodes = useRef<string[]>([])
  const cachedMultiParamAgentModelCodes = useRef<string[]>([])
  const cachedGridVideoAgentModelCodes = useRef<string[]>([])
  const setsRef = useRef<{
    promptTaskIds: Set<number>
    promptStoryboardIds: Set<number>
    refreshedIds: Set<number>
  } | null>(null)
  if (!setsRef.current) {
    setsRef.current = {
      promptTaskIds: new Set<number>(),
      promptStoryboardIds: new Set<number>(),
      refreshedIds: new Set<number>()
    }
  }

  const videoCanvasBodyRef = useRef<HTMLDivElement | null>(null)
  const sceneTabBarRef = useRef<HorizontalScrollTabBarHandle | null>(null)
  const imageToVideoPanelRef = useRef<StoryboardGeneratePanelHandle | null>(null)
  const multiParamPanelRef = useRef<StoryboardGeneratePanelHandle | null>(null)
  const edgeVideoPanelRef = useRef<StoryboardGeneratePanelHandle | null>(null)

  // —— ctx（原 setup 单闭包）：稳定引用 + 每渲染回填最新 Mirrored ——
  const ctxRef = useRef<VideoModalCtx | null>(null)
  if (!ctxRef.current) ctxRef.current = {} as VideoModalCtx
  const ctx = ctxRef.current

  const { headerTabs, refreshHeaderTabs } = useStoryboardModalHeaderTabs({
    open: props.open,
    recordType: 'video',
    // 打开/切 Tab 由 syncSceneDetailAndRestore 统一 force 一次，避免与画布刷新双打
    autoRefreshOnOpen: false,
    scenes: () =>
      propsRef.current.scenes.map((scene) => ({
        name: scene.name,
        storyboardId: scene.storyboardId
      })),
    route: routeLike,
    headerOptions: () => ({
      // 顶部 Tab 优先走分镜图封面；不要回落 mp4，否则会并发拉视频导致弹窗卡顿
      resolveFallbackThumbnailUrl: (sceneIndex) => ctx.resolveSceneCoverImageUrl(sceneIndex)
    })
  })

  // —— base helpers ——
  function resolveScriptPanelForSceneIndex(sceneIdx: number): StoryboardPanel | undefined {
    const scene = propsRef.current.scenes[sceneIdx]
    const sid = Number(scene?.storyboardId)
    if (Number.isFinite(sid) && sid > 0) {
      const hit = scriptPanelsRef.current.find((p) => Number(p.id) === sid)
      if (hit) return hit
    }
    return scriptPanelsRef.current[sceneIdx]
  }

  function currentStoryboardId(): number | null {
    const raw = propsRef.current.scenes[currentSceneIndex.get()]?.storyboardId
    const id = Number(raw)
    return Number.isFinite(id) && id > 0 ? id : null
  }

  function scriptRowLabel(): string {
    const s = propsRef.current.scenes[currentSceneIndex.get()] as
      | { scriptPanelTitle?: string; name?: string }
      | undefined
    return s?.scriptPanelTitle || s?.name || '分镜脚本'
  }

  function currentSceneVideos(): any[] {
    const list = propsRef.current.scenes[currentSceneIndex.get()]?.videos || []
    return [...list]
  }

  function getActiveStoryboardPanel(): StoryboardGeneratePanelHandle | null {
    const tab = leftActiveTab.get()
    if (tab === 'imageToVideo' || tab === 'gridVideo') {
      return imageToVideoPanelRef.current
    }
    if (tab === 'multiParam') return multiParamPanelRef.current
    if (tab === 'startEndFrame') return edgeVideoPanelRef.current
    return null
  }

  function syncLeftActiveTabForCreationMode(preferPrimary = false) {
    const current = leftActiveTab.get()
    if (!preferPrimary && isStoryboardVideoTabVisible(current, creationModeRef.current)) {
      return
    }
    leftActiveTab.set(resolvePrimaryStoryboardVideoTab(creationModeRef.current))
  }

  function openStoryboardScriptEditor() {
    // 分镜视频内点击脚本标题：关闭当前弹窗并跳转第 4 步分镜脚本列表
    showStoryboardScriptModal.set(false)
    propsRef.current.onOpenChange(false)
    propsRef.current.onJumpToStoryboardScript?.(currentSceneIndex.get())
  }

  function handleSaveScriptFromVideoModal(payload: { title: string; content: string }) {
    const content = payload?.content ?? ''
    const title = payload?.title ?? ''
    propsRef.current.onUpdate(currentSceneIndex.get(), {
      scriptContent: content,
      ...(title.trim() ? { title } : {})
    })
    showStoryboardScriptModal.set(false)
    message.success('分镜脚本已同步到分镜设计步骤')
  }

  /** 原 @update:title 回调，经 StoryboardScriptModal 的 onTitleChange 触发 */
  function handleScriptTitleFromVideoModal(title: string) {
    const t = title?.trim()
    if (!t) return
    propsRef.current.onUpdate(currentSceneIndex.get(), { scriptTitle: t })
  }

  function scrollActiveSceneTabIntoView() {
    sceneTabBarRef.current?.scrollItemIntoView('.scene-image-tab.active')
    sceneTabBarRef.current?.refresh()
  }

  async function syncSceneDetailAndRestore(sceneIdx: number) {
    await ctx.ensureModalVideoLoadingRestored(sceneIdx)
    // 打开/切 Tab：顶部 Tab 与画布共用一次 force list-by-storyboard（外层 list 只带主视频）
    await refreshHeaderTabs(true)
    await ctx.refreshVideoRecords(sceneIdx)
    void ctx.restoreStoryboardVideoPromptGenerateIfNeeded(sceneIdx)
    void ctx.restoreStoryboardVideoGenerateIfNeeded(sceneIdx)
    void ctx.loadRecommendedDurationForScene()
    if (!ctx.isStoryboardVideoPromptGeneratingForScene(sceneIdx)) {
      void ctx.loadStoryboardVideoPromptForScene()
      void ctx.loadStoryboardMultiVideoPromptForScene()
      ctx.loadStoryboardEdgeVideoPromptForScene()
    }
  }

  // 每渲染回填 base ctx（Mirrored 的 value 每帧更新，get/set 稳定）
  Object.assign(ctx, {
    props: () => propsRef.current,
    route: () => getRouteLikeSnapshot(),
    store: () => useCreationStore.getState(),
    emitOpenChange: (v: boolean) => propsRef.current.onOpenChange(v),
    emitUpdate: (sceneIdx: number, data: any) => propsRef.current.onUpdate(sceneIdx, data),
    emitJumpToStoryboardScript: (sceneIdx: number) =>
      propsRef.current.onJumpToStoryboardScript?.(sceneIdx),
    currentSceneIndex,
    viewMode,
    leftActiveTab,
    leftPanelLoading,
    rightPanelLoading,
    videoGenerateTargetKey,
    videoGenerateProgressText,
    selectedVideoIdx,
    showStoryboardScriptModal,
    scriptEditorKey,
    stepPanelImagesCache,
    selectReferenceModalOpen,
    imageToVideoPrompt,
    resolvedVideoPromptAssets,
    videoPromptProgrammaticSyncDepth,
    isGeneratingVideoPrompt,
    isSavingVideoPrompt,
    videoPromptGenerateTargetKey,
    multiParamPrompt,
    resolvedMultiParamPromptAssets,
    isGeneratingMultiParamPrompt,
    multiParamPromptGenerateTargetKey,
    edgeVideoPrompt,
    edgeVideoPromptByStoryboardId,
    referenceImages,
    nineGridEnabled,
    isImageToVideoSettingExpanded,
    activeImageToVideoSettingKey,
    selectedCameraMovement,
    cameraMovementDesc,
    selectedImageToVideoShootingTechnique,
    isMultiParamSettingExpanded,
    activeMultiParamSettingKey,
    multiParamShootingTechnique,
    isEdgeVideoSettingExpanded,
    firstFrameImage,
    lastFrameImage,
    edgeFrameImagesByStoryboardId,
    selectEdgeFrameModalOpen,
    edgeFramePickTarget,
    sceneImages,
    characterImages,
    propImages,
    otherImages,
    selectAssetModalOpen,
    selectMultiParamReferenceModalOpen,
    selectAssetModalType,
    showMaterialFromLibraryModal,
    materialLibraryCategoryKey,
    showVideoLibraryModal,
    imageToVideoModel,
    multiParamVideoModel,
    edgeVideoModel,
    gridVideoModel,
    imageToVideoModelDropdownExpanded,
    multiParamVideoModelDropdownExpanded,
    edgeVideoModelDropdownExpanded,
    gridVideoModelDropdownExpanded,
    cachedImageToVideoAgentModelCodes,
    cachedMultiParamAgentModelCodes,
    cachedGridVideoAgentModelCodes,
    initVideoModelGen,
    referenceAudios,
    videoAspectRatio,
    videoDuration,
    videoCount,
    videoQuality,
    videoAudio,
    recommendedDurationSecondsRaw,
    isSettingFinalVideo,
    isDeletingRecord,
    playingVideoIdx,
    videoPreviewMediaReady,
    resumeStoryboardVideoFollowGen,
    resumeStoryboardVideoPromptFollowGen,
    activeStoryboardPromptFollowTaskIds: setsRef.current.promptTaskIds,
    activeStoryboardPromptFollowStoryboardIds: setsRef.current.promptStoryboardIds,
    modalOwnedVideoRecordsRefreshedIds: setsRef.current.refreshedIds,
    videoCanvasBodyRef,
    sceneTabBarRef,
    imageToVideoPanelRef,
    multiParamPanelRef,
    edgeVideoPanelRef,
    refreshHeaderTabs,
    scrollActiveSceneTabIntoView,
    syncSceneDetailAndRestore,
    scriptPanels: () => scriptPanelsRef.current,
    resolveScriptPanelForSceneIndex,
    currentStoryboardId,
    scriptRowLabel,
    currentSceneVideos,
    projectCreationMode: () => creationModeRef.current,
    getActiveStoryboardPanel,
    openStoryboardScriptEditor
  } satisfies Partial<VideoModalCtx>)

  // 子 hook 装配（prompt 需先于 models：models 渲染期读 ctx.aspectRatioEnumOptions）
  useVideoModalSession(ctx)
  useVideoPreviewPlayback(ctx)
  useVideoModalRecords(ctx)
  useVideoModalPrompt(ctx)
  useVideoModalPromptFlows(ctx)
  useVideoModalModels(ctx)
  useVideoModalReferences(ctx)
  useVideoModalGenerate(ctx)
  useVideoModalGenerateActions(ctx)

  // ---------- 头部 Tab 派生数据 ----------

  function getFirstVideo(index: number) {
    const list = propsRef.current.scenes[index]?.videos || []
    return list.find((v: any) => v.isStoryboardVideo) || null
  }

  /** 与编辑分镜配音弹窗 Tab 一致：已设置分镜视频则不显示「分镜生成中」，未设置则显示「未设置分镜」 */
  function formatStoryboardVideoTabLabel(
    title: string,
    hasStoryboardVideo: boolean,
    index: number
  ): string {
    const raw = (title || '').trim()
    if (hasStoryboardVideo) {
      return (
        raw
          .replace(/[:：]\s*分镜生成中\s*$/u, '')
          .replace(/\s*分镜生成中\s*$/u, '')
          .trim() ||
        raw ||
        `分镜视频${index + 1}`
      )
    }
    if (/分镜生成中/.test(raw)) {
      return raw.replace(/分镜生成中/g, '未设置分镜')
    }
    const base =
      raw
        .replace(/[:：]\s*分镜生成中\s*$/u, '')
        .replace(/[:：]\s*$/, '')
        .trim() || raw
    if (!base) return `分镜视频${index + 1}：未设置分镜`
    return base.includes('未设置分镜') ? base : `${base}：未设置分镜`
  }

  function resolveSceneTabVideoUrl(sceneIdx: number, tabThumbnailUrl?: string): string {
    const fromTab = String(tabThumbnailUrl || '').trim()
    // headerTabs 里可能仍是视频记录 fileUrl；无分镜图封面时用于 Tab 首帧
    if (fromTab && !/\.(png|jpe?g|webp|gif|bmp|svg)(\b|$)/i.test(fromTab.split('?')[0]!)) {
      return fromTab
    }
    const main = String(getFirstVideo(sceneIdx)?.url ?? '').trim()
    if (main) return main
    // 尚未标 isStoryboardVideo 时，仍用场景内任意视频 URL，避免 Tab 空白
    const any = propsRef.current.scenes[sceneIdx]?.videos?.find((v: { url?: string }) =>
      String(v?.url || '').trim()
    )
    return String(any?.url ?? '').trim()
  }

  const headerTabsForDisplay = headerTabs.length
    ? headerTabs
    : props.scenes.map((scene, sceneIndex) => ({
        sceneIndex,
        storyboardId: Number.isFinite(Number(scene.storyboardId))
          ? Number(scene.storyboardId)
          : undefined,
        name: scene.name,
        thumbnailUrl: ctx.resolveSceneCoverImageUrl(sceneIndex),
        hasFinalAsset: !!getFirstVideo(sceneIndex)?.url
      }))

  const sceneTabsForHeader = headerTabsForDisplay.map((tab: any, i: number) => {
    const sceneIdx = Number.isFinite(Number(tab.sceneIndex)) ? Number(tab.sceneIndex) : i
    const mainFromScene = String(getFirstVideo(sceneIdx)?.url || '').trim()
    const fromTabOrScene = resolveSceneTabVideoUrl(sceneIdx, tab.thumbnailUrl)
    const videoUrl = mainFromScene || fromTabOrScene
    const hasMainVideo = tab.hasFinalAsset || !!videoUrl
    const tabThumb = String(tab.thumbnailUrl || '').trim()
    const coverFromScene = String(ctx.resolveSceneCoverImageUrl(sceneIdx) || '').trim()
    const { coverImageUrl, videoUrl: resolvedVideo } = resolveStoryboardModalTabMedia({
      tabThumbnailUrl: tabThumb,
      coverImageUrl: coverFromScene,
      videoUrl
    })
    return {
      storyboardId: tab.storyboardId,
      tabLabel: formatStoryboardVideoTabLabel(
        tab.name || props.scenes[sceneIdx]?.name || '',
        hasMainVideo || !!coverImageUrl,
        sceneIdx
      ),
      coverImageUrl,
      videoUrl: resolvedVideo || videoUrl,
      thumbnailUrl: resolvedVideo || videoUrl
    }
  })
  const sceneTabsForHeaderRef = useRef(sceneTabsForHeader)
  sceneTabsForHeaderRef.current = sceneTabsForHeader

  function switchScene(index: number) {
    if (index === currentSceneIndex.get()) return
    const keepSid = ctx.sceneStoryboardIdNum(index)
    ctx.suspendOtherStoryboardVideoModalFollows(keepSid)
    ctx.pauseAllVideoPreviews()
    playingVideoIdx.set(-1)
    ctx.clearVideoPreviewRefs()
    videoPreviewMediaReady.set({})
    showStoryboardScriptModal.set(false)
    leftPanelLoading.set(true)
    rightPanelLoading.set(true)
    currentSceneIndex.set(index)
    selectedVideoIdx.set(0)
    void syncSceneDetailAndRestore(index)
    setTimeout(() => {
      scrollActiveSceneTabIntoView()
      setTimeout(() => {
        leftPanelLoading.set(false)
        rightPanelLoading.set(false)
      }, TAB_SWITCH_SKELETON_MS)
    }, 0)
  }

  function handleCancel() {
    ctx.pauseAllVideoPreviews()
    playingVideoIdx.set(-1)
    ctx.clearVideoPreviewRefs()
    const posterUrls = [
      ...sceneTabsForHeaderRef.current.map((t) => String(t.videoUrl || '').trim()),
      ...currentSceneVideos().map((v: { url?: string }) => String(v?.url || '').trim())
    ].filter(Boolean)
    if (posterUrls.length) cancelPendingVideoPosters(posterUrls)
    const sid = Number(propsRef.current.scenes[currentSceneIndex.get()]?.storyboardId)
    if (Number.isFinite(sid) && sid > 0) {
      // 关窗释放 live follow，保留持久化 taskId 供下次打开弹窗恢复；dismiss 防止自动重开。
      if (
        findStoryboardVideoGenTaskInScopes(ctx.store(), sid, ctx.route()) ||
        activeStoryboardVideoModalOwnedFollowIds.has(sid)
      ) {
        markStoryboardVideoModalUserDismissed(sid, ctx.storyboardVideoModalSessionScope())
      }
      activeStoryboardVideoModalOwnedFollowIds.delete(sid)
    }
    propsRef.current.onOpenChange(false)
  }

  // ---------- 原 watch / 生命周期（全部平移至 useVideoModalEffects） ----------

  const activeVideoModelValue = (() => {
    const tab = leftActiveTab.value
    if (tab === 'multiParam') return multiParamVideoModel.value
    if (tab === 'startEndFrame') return edgeVideoModel.value
    if (tab === 'gridVideo') return gridVideoModel.value
    return imageToVideoModel.value
  })()

  const cameraMovementOptionsValue = ctx.cameraMovementOptions()
  const shootingTechniqueOptionsValue = ctx.shootingTechniqueOptions()
  const multiParamGroupsValue = useMemo(
    () => ctx.multiParamPromptParamGroups(),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [cameraMovementOptionsValue, shootingTechniqueOptionsValue]
  )
  const videoGroupsValue = useMemo(
    () => ctx.videoPromptParamGroups(),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [cameraMovementOptionsValue, shootingTechniqueOptionsValue]
  )

  useVideoModalEffects(ctx, {
    open: props.open,
    sceneIndex: props.sceneIndex,
    scenesLength: props.scenes.length,
    scriptPanelsValue,
    creationModeValue,
    currentVideosValue: props.scenes[currentSceneIndex.value]?.videos,
    currentStoryboardIdValue: currentStoryboardId(),
    activeVideoModelValue,
    showAudioValue: ctx.videoConfigShowAudio(),
    i2vOptionsValue: ctx.imageToVideoModelOptions(),
    multiOptionsValue: ctx.multiParamVideoModelOptions(),
    edgeOptionsValue: ctx.edgeVideoModelOptions(),
    gridOptionsValue: ctx.gridVideoModelOptions(),
    multiParamGroupsValue,
    videoGroupsValue,
    syncLeftActiveTabForCreationMode
  })

  // ---------- JSX ----------

  const panelLoading = leftPanelLoading.value || rightPanelLoading.value

  return (
    <Modal
      open={props.open}
      width="100vw"
      style={{ top: 0, paddingBottom: 0, maxWidth: '100vw' }}
      footer={null}
      closable={false}
      mask={{ closable: false }}
      wrapClassName="create-flow-modal edit-scene-image-modal edit-storyboard-video-modal"
      className="edit-scene-image-modal edit-storyboard-video-modal"
      onCancel={handleCancel}
    >
      <div className="edit-scene-image-container">
        <div className="modal-header">
          <Button type="text" className="back-btn" onClick={handleCancel} icon={<ArrowLeftOutlined />}>
            <span>返回</span>
          </Button>
          <HorizontalScrollTabBar
            ref={sceneTabBarRef}
            rootClass="scene-switcher"
            trackClass="scene-switcher-track"
          >
            {sceneTabsForHeader.map((tab, index) => (
              <div
                key={tab.storyboardId ?? `scene-${index}`}
                className={`scene-image-tab${currentSceneIndex.value === index ? ' active' : ''}`}
                onClick={() => switchScene(index)}
              >
                <div className="scene-image-thumbnail">
                  <StoryboardModalTabThumbnail
                    generating={ctx.isSceneVideoGenerating(index)}
                    coverImageUrl={tab.coverImageUrl}
                    videoUrl={tab.videoUrl}
                    isActive={currentSceneIndex.value === index}
                  />
                </div>
                <span className="scene-label">{tab.tabLabel}</span>
              </div>
            ))}
          </HorizontalScrollTabBar>
        </div>

        <div className="main-content-wrapper">
          {panelLoading ? (
            <VideoStageSkeleton />
          ) : (
            <div className="figma-stage-layout video-stage-layout">
              <VideoHistoryPanel ctx={ctx} />
              <VideoCanvasSection ctx={ctx} />
              <VideoConfigPanel ctx={ctx} />
            </div>
          )}
        </div>
      </div>

      <VideoModalSubModals
        ctx={ctx}
        currentProjectId={currentProjectIdValue}
        currentEpisodeId={currentEpisodeIdValue}
        onSaveScript={handleSaveScriptFromVideoModal}
        onScriptTitleChange={handleScriptTitleFromVideoModal}
      />
    </Modal>
  )
}

export default EditStoryboardVideoModal
