'use client'

import { useRouteLike } from '@/hooks/useRouteLike'
import { useRef } from 'react'
import type { HorizontalScrollTabBarHandle } from '~/components/common/HorizontalScrollTabBar'
import { useCreationStore } from '~/stores/creation'
import type { AssetExtractType } from '~/types/business-api'
import { createFormImageTaskClaimOwner } from '~/utils/formImageAutoUse'
import { resolveFormImageEditPrefill } from '~/utils/formImageEditPrefill'
import { createModalTabSkeletonController } from '~/utils/modalTabSseMutex'
import { openImagePreviewModal } from '~/utils/openImagePreviewModal'
import { isSettingCardBaseImage } from '~/utils/settingCardBaseImage'
import { createSceneModalInteractionHandlers } from './sceneModalInteractionHandlers'
import {
buildCanvasOverlayKeyImpl,
buildEditorScopeKeyForSceneIndexImpl,
captureModalScopeSnapshotImpl,
cloneScenesForTaskImpl,
isSameModalScopeImpl,
resolveFormIdForSceneIndexImpl,
resolveSceneModalAssetTypeImpl,
rpsAssetIdForSceneIndexImpl,
rpsFormIdsForSceneIndexImpl
} from './sceneModalScopeHelpers'
import type {
DialogueSourceImage,
EditSceneImageControllerExtras,
EditSceneImageModalCtx,
EditSceneImageModalProps,
EditSceneImageModalScene,
ResolvedEditSceneImageModalProps,
SceneModalUpscaleContext
} from './types'
import { useEditSceneImageModalEffects } from './useEditSceneImageModalEffects'
import { useMirrored } from './useMirrored'
import { useSceneModalGenerate } from './useSceneModalGenerate'
import { useSceneModalImageActions } from './useSceneModalImageActions'
import { useSceneModalImageList } from './useSceneModalImageList'
import { useSceneModalModels } from './useSceneModalModels'
import { useSceneModalTaskRestore } from './useSceneModalTaskRestore'
import { useSceneModalTaskState } from './useSceneModalTaskState'

// 切换场景/角色/道具 Tab 时，左右两侧分别展示骨架屏
const TAB_SWITCH_SKELETON_MS = 380

const GLOBAL_TASKS_UPDATED_EVENT = 'create-flow-global-tasks-updated'

export type { EditSceneImageControllerExtras }

export function useEditSceneImageModalController(
  rawProps: EditSceneImageModalProps
): EditSceneImageModalCtx & EditSceneImageControllerExtras {
  // 原 withDefaults：默认值填充
  const props: ResolvedEditSceneImageModalProps = {
    ...rawProps,
    imageType: rawProps.imageType ?? 'scene',
    rpsAssetId: rawProps.rpsAssetId ?? null,
    rpsFormIds: rawProps.rpsFormIds ?? [],
    formParentAssetType: rawProps.formParentAssetType ?? null,
    manualSettingEditBlockedTooltip: rawProps.manualSettingEditBlockedTooltip ?? null,
    editorScopeKey: rawProps.editorScopeKey ?? ''
  }
  /** 事件回调 / 异步流程内一律读最新 props，避免闭包捕获旧值 */
  const propsRef = useRef(props)
  propsRef.current = props

  const route = useRouteLike()
  const routeRef = useRef(route)
  routeRef.current = route

  // 订阅 store 相关分支：isSceneModalImageGenerating 等渲染期读 store，须随 store 变化重渲
  useCreationStore((s) => s.step3GenVisualByScope)
  useCreationStore((s) => s.sceneGenerationStatus)
  useCreationStore((s) => s.characterFormGenerationStatus)
  useCreationStore((s) => s.propFormGenerationStatus)
  useCreationStore((s) => s.currentProjectId)
  useCreationStore((s) => s.currentEpisodeId)

  const ctxHolder = useRef<EditSceneImageModalCtx | null>(null)
  if (!ctxHolder.current) ctxHolder.current = {} as EditSceneImageModalCtx
  const ctx = ctxHolder.current

  const sceneModalFormImageClaimOwner = useRef(createFormImageTaskClaimOwner()).current

  // —— 顶层可变状态（原 ref）——
  const currentSceneIndex = useMirrored(props.sceneIndex)
  const viewMode = useRef<'list' | 'card'>('list')
  const currentImageIndex = useMirrored(
    props.initialImageIndex !== null && props.initialImageIndex !== undefined ? props.initialImageIndex : 0
  )
  const editingImageTitleIndex = useMirrored<number | null>(null)
  const editingImageTitle = useMirrored('')

  const dialogueSourceImages = useMirrored<DialogueSourceImage[]>([])
  const dialogueInstructionHtml = useMirrored('')
  const showDialogueImportModal = useMirrored(false)

  // 本地场景图片列表（包含待添加的图片）
  const localSceneImages = useMirrored<any[]>([])
  // 锁：在“编辑从 rps 接口回填”的弹窗场景下，禁止 watch(props.scenes) 覆盖 left 列表数据。
  // left 列表必须以 form-image/list 返回为准（支持 isUse=0/1 全量展示）。
  const lockLocalSceneImagesFromRps = useRef(false)

  const showSceneSettingModal = useMirrored(false)
  const sceneSettingContent = useMirrored('')
  const showAssetLibraryModal = useMirrored(false)
  const showMultiAngleModal = useMirrored(false)
  const multiAngleTargetIndex = useRef<number | null>(null)
  const multiAngleImageUrl = useMirrored('')
  const addingSceneImageAtKey = useMirrored('')
  const cancellingAddAtKey = useMirrored('')
  const isSelectingSceneImage = useMirrored(false)
  const selectedSceneImageIndex = useMirrored<number | null>(null)
  const addingAfterIndex = useRef<number | null>(null)
  // 待添加的图片（导入后暂存，需要手动添加）
  const pendingImage = useRef<any | null>(null)
  // 在当前会话中添加的图片ID集合（用于显示"取消添加"按钮；与父级场景图列表同步）
  const addedImageIds = useMirrored<Set<string>>(new Set())

  /** 点选改图入口（暂不开放） */
  const showTouchEditToolbar = false
  const showTouchEditModal = useMirrored(false)
  const touchEditImageUrl = useMirrored('')

  /** 形态图高清（upscale）画布遮罩 */
  const upscaleUiPhase = useMirrored<'idle' | 'running' | 'failed'>('idle')
  const upscaleTargetKey = useMirrored('')
  const upscaleProgressText = useMirrored('高清处理中…')
  const upscaleFailedMessage = useMirrored('')
  const upscaleContext = useRef<SceneModalUpscaleContext | null>(null)
  /** 画布遮罩当前任务类型，用于工具栏按钮 loading 与任务一一对应 */
  const canvasOverlayTaskKind = useMirrored<import('~/stores/creation').SceneModalSseTaskKind | null>(null)

  const isSceneSplitting = useMirrored(false)
  const sceneSplitTargetKey = useMirrored('')
  const sceneSplitProgressText = useMirrored('正在拆分四宫格…')

  // 切换场景/角色/道具 Tab 时，左右两侧分别展示骨架屏
  const leftPanelLoading = useMirrored(false)
  const rightPanelLoading = useMirrored(false)
  const tabSwitchSkeleton = useRef(
    createModalTabSkeletonController((loading) => {
      leftPanelLoading.set(loading)
      rightPanelLoading.set(loading)
    }, TAB_SWITCH_SKELETON_MS)
  ).current
  const resumeSceneModalFollowGen = useRef(0)
  const sceneModalTabActivationGen = useRef(0)

  const initFormImageListSeq = useRef(0)
  const lastInitFormImageListKey = useRef('')

  const sceneTabBarRef = useRef<HorizontalScrollTabBarHandle | null>(null)
  const mainContentRef = useRef<HTMLElement | null>(null)
  const imageRefs = useRef<Array<HTMLElement | null>>([])

  const setImageRef = (el: any, index: number) => {
    if (el && el instanceof HTMLElement) {
      imageRefs.current[index] = el
    }
  }

  function scrollActiveSceneTabIntoView() {
    sceneTabBarRef.current?.scrollItemIntoView('.scene-image-tab.active')
    sceneTabBarRef.current?.refresh()
  }

  // —— 基础 helpers ——
  // 场景图为「添加场景图」，角色/道具/形态编辑均为「添加形态图」
  const addImageButtonLabel = () =>
    propsRef.current.imageType === 'scene' ? '添加场景图' : '添加形态图'

  /** 与外层列表一致：场景图用「场景图N」，形态/角色/道具用「形态图N」 */
  function getImageTitleFallback(index: number): string {
    const n = index + 1
    return propsRef.current.imageType === 'scene' ? `场景图${n}` : `形态图${n}`
  }

  /** 仅编辑场景主资产图（非角色/道具/形态） */
  const isSceneEditMode = () => propsRef.current.imageType === 'scene'

  // 作用域/props 派生 helpers（实现移至 sceneModalScopeHelpers.ts，仅代码搬移不改逻辑）
  const rpsAssetIdForSceneIndex = (sceneIdx: number): number | null =>
    rpsAssetIdForSceneIndexImpl(ctx, sceneIdx)

  const activeRpsAssetId = (): number | null => rpsAssetIdForSceneIndex(currentSceneIndex.get())

  const rpsFormIdsForSceneIndex = (sceneIdx: number): number[] =>
    rpsFormIdsForSceneIndexImpl(ctx, sceneIdx)

  const activeRpsFormIds = (): number[] => rpsFormIdsForSceneIndex(currentSceneIndex.get())

  const resolveFormIdForSceneIndex = (sceneIdx: number): number | null =>
    resolveFormIdForSceneIndexImpl(ctx, sceneIdx)

  /** 画布 loading 遮罩唯一键：含资产/形态/弹窗实例，避免列表 A/B 同为 `0-0` 时串流 */
  const buildCanvasOverlayKey = (sceneIdx: number, imgIdx: number): string =>
    buildCanvasOverlayKeyImpl(ctx, sceneIdx, imgIdx)

  function emitSceneUpdate(sceneIndex: number, data: any, scopeKey?: string) {
    propsRef.current.onUpdate(
      sceneIndex,
      data,
      scopeKey ?? buildEditorScopeKeyForSceneIndex(sceneIndex)
    )
  }

  const buildEditorScopeKeyForSceneIndex = (sceneIdx: number): string =>
    buildEditorScopeKeyForSceneIndexImpl(ctx, sceneIdx)

  const captureModalScopeSnapshot = (sceneIdx = currentSceneIndex.get()) =>
    captureModalScopeSnapshotImpl(ctx, sceneIdx)

  const isSameModalScope = (snapshot: ReturnType<typeof captureModalScopeSnapshot>) =>
    isSameModalScopeImpl(ctx, snapshot)

  const cloneScenesForTask = () => cloneScenesForTaskImpl(ctx)

  const resolveSceneModalAssetType = (): AssetExtractType => resolveSceneModalAssetTypeImpl(ctx)

  const currentScene = (): EditSceneImageModalScene =>
    propsRef.current.scenes[currentSceneIndex.get()] || { name: '', images: [] }

  const currentSceneImages = () => localSceneImages.get()

  const currentImg = () => currentSceneImages()[currentImageIndex.get()] || null

  /** 选图后以该图片保存的业务提示词和历史参考图初始化对话作图。 */
  function applyCurrentFormImageEditPrefill() {
    const prefill = resolveFormImageEditPrefill(currentImg())
    dialogueInstructionHtml.set(prefill.promptText)
    dialogueSourceImages.set(prefill.sourceImages.map((item) => ({ ...item })))
  }

  /** 中间画布标题：与外层列表 `img.title` 同源 */
  const currentImageDisplayTitle = () => {
    const img = currentImg()
    if (!img) return ''
    const title = String(img.title || img.name || '').trim()
    if (title) return title
    return getImageTitleFallback(currentImageIndex.get())
  }

  function handlePreviewCanvasImage() {
    const img = currentImg()
    const url = String(img?.url || '').trim()
    if (!url) return
    openImagePreviewModal({
      url,
      title: img?.title || '预览'
    })
  }

  /** 角色主资产编辑或「角色下的形态」编辑：设定卡接口仅角色 */
  const showToolbarSettingCard = () =>
    propsRef.current.imageType === 'character' ||
    (propsRef.current.imageType === 'form' && propsRef.current.formParentAssetType === 'character')

  const isSettingCardTypeSupported = () => showToolbarSettingCard()

  const whiteBaseImageReadyForSettingCard = () => {
    if (!isSettingCardTypeSupported()) return false
    return isSettingCardBaseImage(currentImg())
  }

  // 切换场景
  const switchScene = (index: number) => {
    if (index === currentSceneIndex.get()) return

    const nextEditorScopeKey = buildEditorScopeKeyForSceneIndex(index)
    ctx.suspendSceneModalFollowsExceptEditorScope(nextEditorScopeKey)
    resumeSceneModalFollowGen.current++
    sceneModalTabActivationGen.current++

    tabSwitchSkeleton.start()
    currentSceneIndex.set(index)
    currentImageIndex.set(0)
    ctx.syncLocalSceneImagesFromSceneIndex(index)

  }

  // 切换图片
  const switchImage = async (index: number) => {
    if (index === currentImageIndex.get()) return
    currentImageIndex.set(index)

    // 滚动到对应图片，确保图片滚动到顶部
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    const targetElement = imageRefs.current[index]
    if (targetElement && mainContentRef.current) {
      const container = mainContentRef.current
      const elementTop = targetElement.offsetTop

      // 计算需要滚动的距离，使目标元素滚动到容器顶部
      const scrollTo = elementTop - container.offsetTop
      container.scrollTo({
        top: scrollTo,
        behavior: 'smooth'
      })
    }
  }

  // —— ctx 组装（base 部分每次渲染刷新，各子 hook API Object.assign 进来） ——
  Object.assign(ctx, {
    props: () => propsRef.current,
    route: () => routeRef.current,
    store: () => useCreationStore.getState(),
    emitOpenChange: (value: boolean) => propsRef.current.onOpenChange(value),
    currentSceneIndex,
    currentImageIndex,
    viewMode,
    editingImageTitleIndex,
    editingImageTitle,
    dialogueSourceImages,
    dialogueInstructionHtml,
    showDialogueImportModal,
    localSceneImages,
    lockLocalSceneImagesFromRps,
    showSceneSettingModal,
    sceneSettingContent,
    showAssetLibraryModal,
    showMultiAngleModal,
    multiAngleTargetIndex,
    multiAngleImageUrl,
    addingSceneImageAtKey,
    cancellingAddAtKey,
    isSelectingSceneImage,
    selectedSceneImageIndex,
    addingAfterIndex,
    pendingImage,
    addedImageIds,
    showTouchEditModal,
    touchEditImageUrl,
    upscaleUiPhase,
    upscaleTargetKey,
    upscaleProgressText,
    upscaleFailedMessage,
    upscaleContext,
    canvasOverlayTaskKind,
    isSceneSplitting,
    sceneSplitTargetKey,
    sceneSplitProgressText,
    leftPanelLoading,
    rightPanelLoading,
    tabSwitchSkeleton,
    resumeSceneModalFollowGen,
    sceneModalTabActivationGen,
    initFormImageListSeq,
    lastInitFormImageListKey,
    sceneTabBarRef,
    mainContentRef,
    imageRefs,
    sceneModalFormImageClaimOwner,
    scrollActiveSceneTabIntoView,
    addImageButtonLabel,
    getImageTitleFallback,
    isSceneEditMode,
    rpsAssetIdForSceneIndex,
    activeRpsAssetId,
    rpsFormIdsForSceneIndex,
    activeRpsFormIds,
    resolveFormIdForSceneIndex,
    buildCanvasOverlayKey,
    emitSceneUpdate,
    buildEditorScopeKeyForSceneIndex,
    captureModalScopeSnapshot,
    isSameModalScope,
    cloneScenesForTask,
    resolveSceneModalAssetType,
    currentScene,
    currentSceneImages,
    currentImg,
    applyCurrentFormImageEditPrefill,
    switchScene,
    switchImage,
    showToolbarSettingCard,
    isSettingCardTypeSupported,
    whiteBaseImageReadyForSettingCard
  })

  Object.assign(ctx, useSceneModalModels(ctx))
  Object.assign(ctx, useSceneModalTaskState(ctx))
  Object.assign(ctx, useSceneModalImageList(ctx))
  Object.assign(ctx, useSceneModalTaskRestore(ctx))
  Object.assign(ctx, useSceneModalImageActions(ctx))
  Object.assign(ctx, useSceneModalGenerate(ctx))
  const interactionHandlers = createSceneModalInteractionHandlers(ctx)
  Object.assign(ctx, interactionHandlers)

  useEditSceneImageModalEffects(ctx, props)
  return Object.assign(ctx, interactionHandlers, {
    currentImageDisplayTitle,
    handlePreviewCanvasImage,
    setImageRef,
    showTouchEditToolbar
  }) as EditSceneImageModalCtx & EditSceneImageControllerExtras
}
