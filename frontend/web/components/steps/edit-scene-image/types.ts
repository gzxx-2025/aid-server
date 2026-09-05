import type { RefObject } from 'react'
import type { HorizontalScrollTabBarHandle } from '~/components/common/HorizontalScrollTabBar'
import type { followFormImageGenerateCardTask } from '~/composables/useFormImageGenerateCardTask'
import type {
CreationStoreState,
SceneModalSseTaskKind,
SceneModalSseTaskSnapshot
} from '~/stores/creation'
import type { AssetExtractType,BillingQuoteRequest,UserTaskDetailData } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import type { createFormImageTaskClaimOwner } from '~/utils/formImageAutoUse'
import type { ModalGenSessionScope } from '~/utils/modalGenSessionScope'
import type {
decideModalTaskOwnerCleanup,
ModalTabSkeletonController
} from '~/utils/modalTabSseMutex'
import type { MultiAngleGeneratePayload } from '~/utils/multiAngleCameraPrompt'
import type { readSceneImageModalGenSession } from '~/utils/sceneImageModalGenSession'
import type { Mirrored } from './useMirrored'
import type { SceneModalImageActionsApi } from './useSceneModalImageActions'
import type { SceneModalImageListApi } from './useSceneModalImageList'
import type { SceneModalModelsApi } from './useSceneModalModels'

export interface EditSceneImageModalScene {
  name: string
  images?: any[]
  setting?: string
}

export interface EditSceneImageModalProps {
  open: boolean
  sceneIndex: number
  initialImageIndex?: number | null
  scenes: EditSceneImageModalScene[]
  imageType?: 'scene' | 'character' | 'prop' | 'form' // scene 为添加场景图，其余为添加形态图
  /** 个人资产主表 id，有则在本弹窗内同步形态图（上传/资产库/AI 生成） */
  rpsAssetId?: number | null
  /** 与当前资产形态顺序对应的 formId 列表（来自服务端） */
  rpsFormIds?: number[]
  /**
   * 形态编辑弹窗：tab 切换到第 N 项时，用于初始化的主表资产 id。
   * - 仅在 imageType = scene/character/prop 时需要
   * - imageType = form 时 tab 对应的是 formId，本字段会被忽略
   */
  rpsAssetIdsByIndex?: Record<number, number | null>
  /**
   * 形态编辑弹窗：tab 切换到第 N 项时，用于初始化的 formId 列表。
   * - 仅在 imageType = scene/character/prop 时需要
   * - imageType = form 时 tab 对应的是 formId，本字段会被忽略
   */
  rpsFormIdsByIndex?: Record<number, number[]>
  /**
   * `imageType === 'form'`（编辑形态 Tab）时：形态所属主资产类型。
   * 角色形态弹窗需与 `imageType=character` 一致展示「生成设定图」；道具形态仍为「对话作图」。
   */
  formParentAssetType?: 'character' | 'prop' | null
  /** 非空时：右侧 PromptScriptFileHeader（名称行）不可点开设定编辑，悬停展示说明 */
  manualSettingEditBlockedTooltip?:
    | string
    | null
    | ((sceneIndex: number) => string | null | undefined)
  /** 弹窗实例作用域（如角色形态 `0-1`），用于隔离不同列表项的生图 loading 与回写 */
  editorScopeKey?: string
  /** 与素材列表底部“重新生成”共用的自动生成资格判断。 */
  canAutoGenerateImage?: (sceneIndex: number) => boolean
  /**
   * 委托给素材列表已有的自动生成任务链路；弹窗内不得为同一任务重复创建 SSE 跟随。
   */
  onAutoRegenerateImage?: (
    sceneIndex: number,
    imageIndex: number,
    image: unknown
  ) => void | Promise<void>
  /** 打开重新生成确认层时，按与正式提交相同的 formId + 当前项目配置构造权威报价体。 */
  resolveAutoRegenerateBillingRequest?: (
    sceneIndex: number,
    imageIndex: number,
    image: unknown
  ) => BillingQuoteRequest | null | Promise<BillingQuoteRequest | null>
  /** 原 emit('update:open') */
  onOpenChange: (value: boolean) => void
  /** 原 emit('update', sceneIndex, data, editorScopeKey?) */
  onUpdate: (sceneIndex: number, data: any, editorScopeKey?: string) => void
}

/** withDefaults 之后的 props（默认值已填充） */
export type ResolvedEditSceneImageModalProps = EditSceneImageModalProps & {
  imageType: 'scene' | 'character' | 'prop' | 'form'
  rpsAssetId: number | null
  rpsFormIds: number[]
  formParentAssetType: 'character' | 'prop' | null
  manualSettingEditBlockedTooltip:
    | string
    | null
    | ((sceneIndex: number) => string | null | undefined)
  editorScopeKey: string
}

export type CanvasToolbarKey =
  | 'drawing'
  | 'regenerate'
  | 'chat'
  | 'hd'
  | 'camera'
  | 'add'
  | 'fourGrid'

export type SceneModalTaskOwner = {
  editorScopeKey: string
  taskId?: number | null
  liveGenScopeKey: string
}

export type DialogueSourceImage = { url: string; title?: string }

export type ModalScopeSnapshot = { editorScopeKey: string; assetId: number | null }

export type GenericModalTaskFollowResult =
  | { ok: true; completeData?: unknown; eventType?: 'complete' | 'partial_failed' }
  | { ok: false; errorMessage: string; completeData?: unknown; deferred?: boolean }

export type SceneModalTaskDetail = UserTaskDetailData
export type SceneModalTaskDetailLoader = (taskId: number) => Promise<SceneModalTaskDetail>

export type SceneModalUpscaleContext = {
  sceneIndex: number
  imageIndex: number
  editorScopeKey: string
  assetId: number | null
  taskId: number | null
}

/**
 * 原 Vue setup() 单闭包按 UI 区块 / 逻辑内聚拆分后共享的运行时上下文。
 * controller 创建 base 部分，各子 hook 把自己的 API Object.assign 进来（延迟绑定解环）。
 */
export interface EditSceneImageModalBaseCtx {
  /** 事件回调 / 异步流程内读最新 props（默认值已填充） */
  props: () => ResolvedEditSceneImageModalProps
  route: () => RouteLikeLocation
  store: () => CreationStoreState
  emitOpenChange: (value: boolean) => void

  // —— 顶层可变状态（原 ref；Mirrored=需触发渲染，RefObject 风格 { current }=纯逻辑量）——
  currentSceneIndex: Mirrored<number>
  currentImageIndex: Mirrored<number>
  viewMode: { current: 'list' | 'card' }
  editingImageTitleIndex: Mirrored<number | null>
  editingImageTitle: Mirrored<string>
  dialogueSourceImages: Mirrored<DialogueSourceImage[]>
  dialogueInstructionHtml: Mirrored<string>
  showDialogueImportModal: Mirrored<boolean>
  localSceneImages: Mirrored<any[]>
  /** 锁：在“编辑从 rps 接口回填”的弹窗场景下，禁止 watch(props.scenes) 覆盖 left 列表数据。 */
  lockLocalSceneImagesFromRps: { current: boolean }
  showSceneSettingModal: Mirrored<boolean>
  sceneSettingContent: Mirrored<string>
  showAssetLibraryModal: Mirrored<boolean>
  showMultiAngleModal: Mirrored<boolean>
  multiAngleTargetIndex: { current: number | null }
  multiAngleImageUrl: Mirrored<string>
  addingSceneImageAtKey: Mirrored<string>
  cancellingAddAtKey: Mirrored<string>
  isSelectingSceneImage: Mirrored<boolean>
  selectedSceneImageIndex: Mirrored<number | null>
  addingAfterIndex: { current: number | null }
  /** 待添加的图片（导入后暂存，需要手动添加） */
  pendingImage: { current: any | null }
  /** 在当前会话中添加的图片ID集合（用于显示"取消添加"按钮；与父级场景图列表同步） */
  addedImageIds: Mirrored<Set<string>>
  showTouchEditModal: Mirrored<boolean>
  touchEditImageUrl: Mirrored<string>
  /** 形态图高清（upscale）画布遮罩 */
  upscaleUiPhase: Mirrored<'idle' | 'running' | 'failed'>
  upscaleTargetKey: Mirrored<string>
  upscaleProgressText: Mirrored<string>
  upscaleFailedMessage: Mirrored<string>
  upscaleContext: { current: SceneModalUpscaleContext | null }
  /** 画布遮罩当前任务类型，用于工具栏按钮 loading 与任务一一对应 */
  canvasOverlayTaskKind: Mirrored<SceneModalSseTaskKind | null>
  isSceneSplitting: Mirrored<boolean>
  sceneSplitTargetKey: Mirrored<string>
  sceneSplitProgressText: Mirrored<string>
  leftPanelLoading: Mirrored<boolean>
  rightPanelLoading: Mirrored<boolean>
  tabSwitchSkeleton: ModalTabSkeletonController
  resumeSceneModalFollowGen: { current: number }
  sceneModalTabActivationGen: { current: number }
  initFormImageListSeq: { current: number }
  lastInitFormImageListKey: { current: string }
  sceneTabBarRef: RefObject<HorizontalScrollTabBarHandle | null>
  mainContentRef: RefObject<HTMLElement | null>
  imageRefs: { current: Array<HTMLElement | null> }
  sceneModalFormImageClaimOwner: ReturnType<typeof createFormImageTaskClaimOwner>

  // —— 基础 helpers（controller 内实现）——
  scrollActiveSceneTabIntoView: () => void
  addImageButtonLabel: () => string
  getImageTitleFallback: (index: number) => string
  isSceneEditMode: () => boolean
  rpsAssetIdForSceneIndex: (sceneIdx: number) => number | null
  activeRpsAssetId: () => number | null
  rpsFormIdsForSceneIndex: (sceneIdx: number) => number[]
  activeRpsFormIds: () => number[]
  resolveFormIdForSceneIndex: (sceneIdx: number) => number | null
  /** 画布 loading 遮罩唯一键：含资产/形态/弹窗实例，避免列表 A/B 同为 `0-0` 时串流 */
  buildCanvasOverlayKey: (sceneIdx: number, imgIdx: number) => string
  emitSceneUpdate: (sceneIndex: number, data: any, scopeKey?: string) => void
  buildEditorScopeKeyForSceneIndex: (sceneIdx: number) => string
  captureModalScopeSnapshot: (sceneIdx?: number) => ModalScopeSnapshot
  isSameModalScope: (snapshot: ModalScopeSnapshot) => boolean
  cloneScenesForTask: () => EditSceneImageModalScene[]
  resolveSceneModalAssetType: () => AssetExtractType
  currentScene: () => EditSceneImageModalScene
  currentSceneImages: () => any[]
  currentImg: () => any | null
  /** 选图后以该图片保存的业务提示词和历史参考图初始化对话作图。 */
  applyCurrentFormImageEditPrefill: () => void
  switchScene: (index: number) => void
  switchImage: (index: number) => Promise<void>
  /** 角色主资产编辑或「角色下的形态」编辑：设定卡接口仅角色 */
  showToolbarSettingCard: () => boolean
  isSettingCardTypeSupported: () => boolean
  whiteBaseImageReadyForSettingCard: () => boolean
}

/** SSE 任务状态模块 API（实现见 useSceneModalTaskState.ts） */
export interface SceneModalTaskStateApi {
  /** 值形如 `${projectId:episodeId}::${editorScopeKey}`，禁止裸 character-0 */
  activeSceneModalFollowScopeKeys: Set<string>
  sceneModalSessionScope: () => ModalGenSessionScope | null
  /** 任务终态只删除自己的持久化状态；同 editorScope 的新任务已经接管时，不得清它的 loading/UI。 */
  sceneModalTaskCleanupDecision: (owner: SceneModalTaskOwner) => {
    decision: ReturnType<typeof decideModalTaskOwnerCleanup>
    sessionScope: ModalGenSessionScope | null
  }
  canClearSceneModalTaskUi: (owner: SceneModalTaskOwner) => boolean
  clearSceneModalTaskStateIfOwned: (
    owner: SceneModalTaskOwner,
    options?: { sceneIdx?: number }
  ) => boolean
  claimFormImagesForModal: (
    taskId: number,
    taskType: unknown,
    completeData: unknown
  ) => Promise<unknown>
  /** 顶部 Tab 互斥：挂起非目标 editorScope 的浏览器 SSE，释放并发槽 */
  suspendSceneModalFollowsExceptEditorScope: (keepEditorScopeKey: string) => void
  showUpscaleFailedOverlay: () => boolean
  /** 画布 / 左侧生成记录共用的任务 loading（生图、高清、设定卡等） */
  isCanvasTaskOverlayActive: (sceneIdx: number, imgIdx: number) => boolean
  showCanvasTaskRunningOverlay: () => boolean
  sceneGenerateOverlayText: () => string
  showSettingCardToolbarLoading: () => boolean
  showUpscaleToolbarLoading: () => boolean
  showMultiViewToolbarLoading: () => boolean
  showCurrentGeneratingPlaceholder: () => boolean
  resolveActiveSceneModalTaskKind: (sceneIdx: number) => SceneModalSseTaskKind | null
  showGenerateFooterButtonLoading: () => boolean
  clearUpscaleOverlay: () => void
  resolvePersistedSceneModalSseTask: (editorScopeKey: string) => SceneModalSseTaskSnapshot | null
  rebuildPersistedFromSession: (sceneIdx: number) => SceneModalSseTaskSnapshot | null
  /** 弹窗内发起任务时同步列表卡片 generating，便于刷新后列表/流程条恢复 loading */
  syncExternalGeneratingForModalScope: (sceneIdx: number) => void
  slotHasLoadedImagesForModal: (sceneIdx: number) => boolean
  /** 形态图已就绪时，将外层 Pinia generating 回落为 success，避免弹窗 Tab/记录卡误显 loading */
  markExternalGeneratingCompleteForModalScope: (sceneIdx: number) => void
  /** 刷新后：任务已终态但 session / Pinia generating 仍残留时，打开弹窗前先对齐 */
  clearStaleSceneModalGeneratingState: (
    sceneIdx: number,
    isCurrent?: () => boolean
  ) => Promise<void>
  /** 与 syncExternalGeneratingForModalScope 成对：SSE 失败/取消后清除外层 Tab/列表 generating */
  clearExternalGeneratingForModalScope: (sceneIdx: number) => void
  collectModalFormIdsForSceneIndex: (sceneIdx: number) => number[]
  isEditorScopeGeneratingExternally: (sceneIdx: number) => boolean
  resolveImageIdxByRpsImageId: (imageId: number) => number
  currentModalLiveGenScopeKey: () => string
  addModalFollowLock: (editorScopeKey: string, liveGenScopeKey?: string) => void
  hasModalFollowLock: (editorScopeKey: string, liveGenScopeKey?: string) => boolean
  deleteModalFollowLock: (editorScopeKey: string, liveGenScopeKey?: string) => void
  resetSceneModalDeferredRestoreState: () => void
  /**
   * deferred 统一收口，杜绝「删锁 + 立刻 restore」与新跟随互抢打爆 `/task/stream`。
   * @returns true = 已被继任跟随接管，调用方 finally 不得 endFollow / 删锁 / 清 overlay
   */
  handleDeferredSceneModalFollow: (opts: {
    sceneIdx: number
    editorScopeKey: string
    liveGenScopeKey?: string
    errorMessage?: unknown
  }) => boolean
  /** 仅当前作品 + 当前 editor 可见时写画布进度文案；Pinia 仍按任务归属 scope 更新 */
  applyCanvasProgressIfCurrent: (opts: {
    liveGenScopeKey: string
    editorScopeKey: string
    taskId?: number | null
    text: string
  }) => void
  persistSceneModalSseTask: (
    sceneIdx: number,
    imageIdx: number,
    taskKind: SceneModalSseTaskKind,
    taskId: number,
    extra?: {
      formId?: number | null
      imageId?: number | null
      message?: string
      stepTitle?: string
    }
  ) => void
  beginCanvasTaskOverlay: (
    sceneIdx: number,
    imgIdx: number,
    progressText: string,
    taskKind?: SceneModalSseTaskKind | null
  ) => void
  endCanvasTaskOverlay: (sceneIdx: number, imgIdx: number) => void
  readSessionForScene: (sceneIdx: number) => ReturnType<typeof readSceneImageModalGenSession>
  clearLocalGeneratingPlaceholders: () => void
  /** SSE 终态（含 error）后统一清除 Tab / 生成记录 / 外层列表 loading */
  clearSceneModalGeneratingUi: (sceneIdx: number) => void
  ensureGeneratingPlaceholderImage: (sceneIdx: number) => void
  finalizeLocalImagesWhileGenerating: (mapped: any[]) => any[]
  isSceneModalImageGenerating: (sceneIdx: number) => boolean
  isHistoryItemGenerating: (imageIndex: number) => boolean
  syncSceneModalSseProgress: (
    snap: SceneModalSseTaskSnapshot,
    p: { message?: string; stepTitle?: string },
    liveGenScopeKey?: string
  ) => void
  primeSceneModalLoadingUi: (sceneIdx: number) => void
  ensureModalLoadingRestored: (sceneIdx: number, isCurrent?: () => boolean) => Promise<void>
}

/** SSE 跟随 / 恢复模块 API（实现见 useSceneModalTaskRestore.ts） */
export interface SceneModalTaskRestoreApi {
  restoreSceneModalSseIfNeeded: (
    sceneIdx: number,
    options?: { loadingStateReady?: boolean; isCurrent?: () => boolean }
  ) => Promise<void>
  activateSceneModalTab: (
    sceneIdx: number,
    options?: { forceImageRefresh?: boolean }
  ) => Promise<void>
  runSceneModalSseFollow: (
    snap: SceneModalSseTaskSnapshot,
    opts?: { silentComplete?: boolean }
  ) => Promise<void>
  applySettingCardGenerateSuccess: (
    result: { imageUrl: string; imageId: number | null },
    opts?: {
      silentComplete?: boolean
      skipClaim?: boolean
      taskId?: number | null
      isCurrent?: () => boolean
    }
  ) => Promise<void>
  resolveSettingCardFollowResult: (
    taskId: number,
    result: Awaited<ReturnType<typeof followFormImageGenerateCardTask>>
  ) => Promise<Awaited<ReturnType<typeof followFormImageGenerateCardTask>>>
}

/** 生成 / 任务触发模块 API（实现见 useSceneModalGenerate.ts） */
export interface SceneModalGenerateApi {
  /** 「对话作图」Tab：genMode=chat，参考图 0~N 张（0 张为纯文生图） */
  handleStartDialogueGenerate: () => Promise<void>
  /** 仅当前画布条目展示拆分 loading，避免切 Tab/换图后按钮仍转圈挡住其它入口 */
  showSceneSplitOverlay: () => boolean
  showSceneSplitToolbarLoading: () => boolean
  /** 场景图：中间工具栏「拆分四宫格」— 后端切图、上传 OSS 并入库 */
  handleSceneSplitFourGrid: (index: number) => Promise<void>
  /** 场景 / 道具 / 形态(form) 等：保存参考图优先，否则沿用当前图单图兜底。 */
  handleDialogueImage: (index: number) => void
  handleSettingCardSelect: (payload: {
    agentCode?: string
    modelCode?: string
    imageIndex: number
  }) => Promise<void>
  handleUpscaleModelSelect: (payload: {
    modelCode: string
    resolution: string
    imageIndex: number
  }) => Promise<void>
  handleMultiAngle: (index: number) => void
  handleMultiAngleGenerate: (payload: MultiAngleGeneratePayload) => Promise<void>
}

/** controller 内实现的杂项 handler（实现见 useEditSceneImageModalController.ts） */
export interface EditSceneImageControllerExtras {
  /** 中间画布标题：与外层列表 `img.title` 同源 */
  currentImageDisplayTitle: () => string
  handlePreviewCanvasImage: () => void
  handleOpenSceneSetting: () => void
  handleSettingModalSyncSceneTitle: (fullDisplayName: string) => void
  handleSaveSceneSetting: (content: string) => void
  handleSaveAndUpdateSceneSetting: (content: string) => void
  getFirstSceneImage: (sceneIndex: number) => any | null
  handleAddSceneImageAfter: (index: number) => void
  selectSceneImageFromTab: (sceneIndex: number) => void
  cancelSelectSceneImage: () => void
  handleCancel: () => void
  setImageRef: (el: any, index: number) => void
  /** 点选改图入口（暂不开放） */
  showTouchEditToolbar: boolean
}

export type EditSceneImageModalCtx = EditSceneImageModalBaseCtx &
  SceneModalModelsApi &
  SceneModalImageListApi &
  SceneModalImageActionsApi &
  SceneModalTaskStateApi &
  SceneModalTaskRestoreApi &
  SceneModalGenerateApi
