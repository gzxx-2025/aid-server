import type { RefObject } from 'react'
import type { CreationStoreState } from '~/stores/creation'
import type { SceneCharacterData } from '~/types'
import type { UserTaskRow } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import type { CreateFlowShellContext } from '~/utils/createFlowInjection'
import type { Mirrored } from './mirrored'
import type { ScpAssetLoadApi } from './useScpAssetLoad'
import type { ScpBootstrapApi } from './useScpBootstrap'
import type { ScpCharacterCrudApi } from './useScpCharacterCrud'
import type { ScpCharacterImagesApi } from './useScpCharacterImages'
import type { ScpDerivedApi } from './useScpDerived'
import type { ScpFinalizersApi } from './useScpFinalizers'
import type { ScpGenerateActionsApi } from './useScpGenerateActions'
import type { ScpGenStatusApi } from './useScpGenStatus'
import type { ScpGenStatusSyncApi } from './useScpGenStatusSync'
import type { ScpModalSseSyncApi } from './useScpModalSseSync'
import type { ScpPropCrudApi } from './useScpPropCrud'
import type { ScpPropImagesApi } from './useScpPropImages'
import type { ScpRpsOpsApi } from './useScpRpsOps'
import type { ScpSceneCrudApi } from './useScpSceneCrud'
import type { ScpSettingCardApi } from './useScpSettingCard'
import type { ScpSubmitFieldsApi } from './useScpSubmitFields'
import type { ScpTaskFollowApi } from './useScpTaskFollow'
import type { ScpTaskHydrateApi } from './useScpTaskHydrate'
import type { ScpTaskProgressApi } from './useScpTaskProgress'
import type { ScpTaskRestoreApi } from './useScpTaskRestore'
import type { RpsSettingEditorState } from './scpSettingPromptUtils'

export type TabKey = 'scene' | 'character' | 'prop'

export type CharacterFormItem = {
  name: string
  voiceover?: string
  voiceoverId?: string
  voiceoverAvatarUrl?: string
  voiceoverPreviewUrl?: string
  /** false=手动新增形态，仅支持图片导入 */
  canAutoGenerateImage?: boolean
  createSource?: string
  /** The prompt belongs to this form, not to the parent character asset. */
  setting?: RpsSettingEditorState
}

export type PropFormItem = {
  name: string
  /** false=手动新增形态，仅支持图片导入 */
  canAutoGenerateImage?: boolean
  createSource?: string
  /** The prompt belongs to this form, not to the parent prop asset. */
  setting?: RpsSettingEditorState
}

export interface SceneCharacterPropProps {
  modelValue: SceneCharacterData
  storyScriptContent: string
  isExtracting?: boolean
  extractingStage?: 'scene' | 'character' | 'prop'
  extractingStages?: {
    scene: boolean
    character: boolean
    prop: boolean
  }
  /** 原 emit('update:modelValue') */
  onModelValueChange: (value: SceneCharacterData) => void
  /** 原 emit('stop-extract') */
  onStopExtract: () => void
  /** 原 emit('open-extract-modal')：打开智能体弹窗，仅展示对应一列，开始提取后只跑该类型 */
  onOpenExtractModal: (scope: 'scene' | 'character' | 'prop') => void
}

/** withDefaults 之后的 props（isExtracting / extractingStage 默认值已填充） */
export type ResolvedSceneCharacterPropProps = SceneCharacterPropProps & {
  isExtracting: boolean
  extractingStage: 'scene' | 'character' | 'prop'
}

/** 须在 `projectContextDeps` 的 immediate watch 之前初始化，避免 TDZ（Cannot access before initialization） */
export type FormGenStatus = 'idle' | 'generating' | 'success' | 'failed'

/** 第三步形态/生图 SSE 进度文案按 Tab 隔离，避免多 Tab 并行时互相覆盖 */
export type Step3TabTaskProgress = {
  percent: number
  stepTitle: string
  message: string
  stepIndex: number | null
  stepTotal: number | null
}

export type UserTaskSseOutcome =
  | { type: 'complete'; data: unknown }
  | { type: 'partial_failed'; data: import('~/utils/taskPartialFailed').TaskPartialFailedData | null; errorMessage: string }
  | { type: 'error'; errorMessage: string }

export type FormImageSuccessItem = {
  formId: number
  imageId?: number
  imageUrl?: string
}

/** 形态图任务结束：在第三步则刷新列表；否则仅更新 Pinia，避免切走后卡片一直 generating */
export type FormGenerateBatchOutcome = {
  successCount: number
  failCount: number
  failedMessages: string[]
}

export type PendingFormCardItem = {
  assetId: number
  assetType: TabKey
  title: string
}

export const FORM_CARD_BATCH_SETTLED_EVENT = 'create-flow-form-card-batch-settled'
export const FORM_IMAGE_TASK_SETTLED_EVENT = 'create-flow-form-image-task-settled'
export const GLOBAL_TASKS_UPDATED_EVENT = 'create-flow-global-tasks-updated'
export const SCP_ACTIVE_TAB_SESSION_PREFIX = 'scp-active-tab:'

/**
 * 原 Vue setup() 单闭包按 UI 区块 / 逻辑内聚拆分后共享的运行时上下文。
 * useScpState 创建 base 部分，各子 hook 把自己的 API Object.assign 进来（延迟绑定解环）。
 */
export interface ScpBaseCtx {
  /** 事件回调 / 异步流程内读最新 props（默认值已填充） */
  props: () => ResolvedSceneCharacterPropProps
  route: () => RouteLikeLocation
  /** 原 Pinia creationStore：每次调用取最新 Zustand 快照 */
  store: () => CreationStoreState
  /** 原 inject(createFlowShellKey, null) */
  createFlowShell: () => CreateFlowShellContext | null
  /** 原 store 字段直接赋值（Pinia 可变写法）的 Zustand 替代 */
  patchStore: (partial: Partial<CreationStoreState>) => void
  emitUpdateModelValue: (value: SceneCharacterData) => void
  emitStopExtract: () => void
  emitOpenExtractModal: (scope: 'scene' | 'character' | 'prop') => void

  // —— 顶层可变状态（原 ref；Mirrored=需触发渲染）——
  activeTab: Mirrored<TabKey>
  localValue: Mirrored<SceneCharacterData>
  // 场景来源标记：记录哪些场景是手动添加的（必须在 watch 之前定义）
  manualScenes: Mirrored<Set<number>>
  // 角色来源标记：记录哪些角色是手动添加的
  manualCharacters: Mirrored<Set<number>>
  // 道具来源标记：记录哪些道具是手动添加的
  manualProps: Mirrored<Set<number>>
  /** 列表索引 → 服务端资产 id（用于删除接口；来自 query 列表或 create 返回） */
  sceneAssetIds: Mirrored<Record<number, number>>
  characterAssetIds: Mirrored<Record<number, number>>
  propAssetIds: Mirrored<Record<number, number>>
  /** 列表索引 → 该主表下所有形态 id（删除时需逐个调用 rps/delete） */
  sceneFormIdsByIndex: Mirrored<Record<number, number[]>>
  characterFormIdsByIndex: Mirrored<Record<number, number[]>>
  propFormIdsByIndex: Mirrored<Record<number, number[]>>
  // 角色的形态数据：Record<角色索引, 形态数组>
  characterForms: Mirrored<Record<number, CharacterFormItem[]>>
  // 道具的形态数据：Record<道具索引, 形态数组>
  propForms: Mirrored<Record<number, PropFormItem[]>>
  sceneGenerationStatus: Mirrored<Record<number, FormGenStatus>>
  characterFormGenerationStatus: Mirrored<Record<string, FormGenStatus>>
  propFormGenerationStatus: Mirrored<Record<string, FormGenStatus>>
  /** 当前页并行跟进的 SSE 任务（刷新后可同时恢复多条形态/生图任务） */
  activeTrackedTaskIds: Mirrored<number[]>
  step3TabTaskProgress: Mirrored<Record<TabKey, Step3TabTaskProgress>>
  /** taskId -> Tab，用于 SSE 回调写入对应 Tab 文案，并在任务结束时仅清除该 Tab */
  step3TaskIdToTab: Mirrored<Record<number, TabKey>>
  /** 登记进行中任务元数据，Tab 切换断线后可重连 SSE */
  step3TaskMetaById: Mirrored<
    Record<number, { tab: TabKey; taskType: string | null; assetIds?: number[] }>
  >
  /** 最近一次 /task/list 的第三步相关行（含终态），切 Tab 用 list 判状态，禁止逐条 detail */
  recentStep3TaskRows: Mirrored<UserTaskRow[]>
  tabAssetLoading: Mirrored<Record<TabKey, boolean>>
  /** 上次已加载的「作品+剧集」scope；切换前把 Pinia 里仍是上一作品的扁平生成态写回分桶 */
  lastStep3VisualScopeKey: Mirrored<string>
  /**
   * 当前作品/剧集下：三步 rps 拉取 +「待生成形态」reconcile + 任务恢复跑完之前为 false。
   * 避免刷新后先闪「完整卡片（自动生成/导入）」再切到「待生成形态」小卡片。
   */
  step3AssetBootstrapReady: Mirrored<boolean>
  /** 待生成形态小卡片上：按资产 ID 防止重复点击 */
  pendingFormGenBusy: Mirrored<Record<number, boolean>>
  /** 顶栏「批量生成形态」提交中 */
  batchFormGenerateSubmitting: Mirrored<boolean>
  /** 顶栏「批量删除」提交中 */
  batchDeleteSubmitting: Mirrored<boolean>
  /** 顶栏「其他操作」下拉展开态 */
  batchOpsDropdownOpen: Mirrored<boolean>
  batchCardGenerateSubmitting: Mirrored<boolean>
  /** 设定卡批量生成：按白底主图 imageId 追踪 busy（刷新后可恢复） */
  settingCardGenBusyByImageId: Mirrored<Record<number, boolean>>
  defaultTextModelCode: Mirrored<string>
  ongoingTasks: Mirrored<UserTaskRow[]>
  ongoingTasksLoading: Mirrored<boolean>

  // 场景图片数据存储
  sceneImages: Mirrored<Record<number, any[]>>
  // 角色图片数据存储：Record<角色索引, 图片数组>
  characterImages: Mirrored<Record<number, any[]>>
  // 道具图片数据存储：Record<道具索引, 图片数组>
  propImages: Mirrored<Record<number, any[]>>
  // 角色形态图片数据存储：Record<"characterIndex-formIndex", 图片数组>
  characterFormImages: Mirrored<Record<string, any[]>>
  // 道具形态图片数据存储：Record<"propIndex-formIndex", 图片数组>
  propFormImages: Mirrored<Record<string, any[]>>
  // 场景设定相关
  sceneSettings: Mirrored<Record<string, RpsSettingEditorState>>
  characterSettings: Mirrored<Record<string, RpsSettingEditorState>>
  propSettings: Mirrored<Record<string, RpsSettingEditorState>>

  // 名称/标题编辑态
  editingSceneIndex: Mirrored<number | null>
  editingSceneName: Mirrored<string>
  /** 「待生成形态」横滑卡片标题编辑 */
  editingPendingFormCardKey: Mirrored<string | null>
  editingPendingFormTitle: Mirrored<string>
  // 场景图名称编辑
  editingImageTitleIndex: Mirrored<string | null>
  editingImageTitle: Mirrored<string>
  editingCharacterIndex: Mirrored<number | null>
  editingCharacterName: Mirrored<string>
  editingFormIndex: Mirrored<string | null>
  editingFormName: Mirrored<string>
  editingPropIndex: Mirrored<number | null>
  editingPropName: Mirrored<string>
  editingPropFormIndex: Mirrored<string | null>
  editingPropFormName: Mirrored<string>

  // 弹窗状态
  showSceneSettingModal: Mirrored<boolean>
  currentSceneIndex: Mirrored<number>
  showImportSceneImageModal: Mirrored<boolean>
  currentImportSceneIndex: Mirrored<number>
  showEditSceneImageModal: Mirrored<boolean>
  currentEditSceneIndex: Mirrored<number>
  // 带图片索引的编辑场景图（点击图片时调用）
  currentEditImageIndex: Mirrored<number | null>
  showCharacterSettingModal: Mirrored<boolean>
  currentCharacterIndex: Mirrored<number>
  currentCharacterSettingFormIndex: Mirrored<number>
  showImportCharacterImageModal: Mirrored<boolean>
  currentImportCharacterIndex: Mirrored<number>
  showEditCharacterImageModal: Mirrored<boolean>
  currentEditCharacterIndex: Mirrored<number>
  currentEditCharacterImageIndex: Mirrored<number | null>
  showImportCharacterFormImageModal: Mirrored<boolean>
  currentImportCharacterFormKey: Mirrored<string> // "characterIndex-formIndex"
  showEditCharacterFormImageModal: Mirrored<boolean>
  currentEditCharacterFormKey: Mirrored<string> // "characterIndex-formIndex"
  currentEditCharacterFormImageIndex: Mirrored<number | null>
  showVoiceTimbrePickerModal: Mirrored<boolean>
  currentVoiceCharacterIndex: Mirrored<number>
  currentVoiceFormIndex: Mirrored<number>
  voicePickerInitialName: Mirrored<string>
  playingVoicePreviewKey: Mirrored<string | null>
  showPropSettingModal: Mirrored<boolean>
  currentPropIndex: Mirrored<number>
  currentPropSettingFormIndex: Mirrored<number>
  showImportPropImageModal: Mirrored<boolean>
  currentImportPropIndex: Mirrored<number>
  showEditPropImageModal: Mirrored<boolean>
  currentEditPropIndex: Mirrored<number>
  currentEditPropImageIndex: Mirrored<number | null>
  showImportPropFormImageModal: Mirrored<boolean>
  currentImportPropFormKey: Mirrored<string> // "propIndex-formIndex"
  showEditPropFormImageModal: Mirrored<boolean>
  currentEditPropFormKey: Mirrored<string> // "propIndex-formIndex"
  currentEditPropFormImageIndex: Mirrored<number | null>
  showBatchGenerateModal: Mirrored<boolean>
  batchGenerateType: Mirrored<'scene' | 'character' | 'prop'>
  batchGenerateMode: Mirrored<'image' | 'setting-card' | 'form' | 'delete'>

  // DOM refs
  scpContentRef: RefObject<HTMLDivElement | null>
  voicePreviewAudioRef: RefObject<HTMLAudioElement | null>

  // —— 实例级可变量（原 script setup 顶层 let / Map / Set，不触发渲染）——
  /** bootstrap / 恢复 Tab 时已主动 load，跳过一次 watch 内重复 load */
  suppressActiveTabAssetLoadOnce: boolean
  /** 多类型提取期间 Tab 会跟随 SSE 阶段；结束后恢复到用户提取前停留的 Tab */
  activeTabBeforeExtractStart: TabKey | null
  /** 单类型提取时锁定 Tab，避免 SSE 文案误匹配「场景」等把 Tab 切走 */
  singleExtractTabLock: TabKey | null
  loadAssetTabGeneration: number
  /** 各 Tab 独立 generation，用于切换 Tab 时正确结束 loading（与全局 loadAssetTabGeneration 解耦） */
  tabAssetLoadGen: Record<TabKey, number>
  activeTaskStreamClosers: Map<number, () => void>
  /** Tab 切换主动断开 SSE 时标记，避免误触发终态清理 */
  step3SseTabSwitchClosing: Set<number>
  /**
   * 每个 taskId 的跟随世代：切回 Tab 重连时自增。
   * 旧 follow 收尾前必须校验世代，已被新 follow 接管时不得注销登记、
   * 不得 clearActiveTaskStream（会误杀新 SSE）、不得再打 task/detail 兜底。
   */
  step3TaskFollowGeneration: Map<number, number>
  /** 作品/剧集切换时自增，用于丢弃旧任务 SSE 的后续回调与 finally，避免误 toast、误清 store */
  taskFollowSession: number
  restoreTasksGeneration: number
  /** task/list 不含快照；恢复阶段补全一次进行中任务详情，Tab 切换复用。 */
  ongoingStep3TaskDetailRows: Map<number, UserTaskRow>
  /** 合并路由/Pinia 连续波动触发的多次 watch，避免刷新时 rps/list 等连打 2～3 遍 */
  projectAssetBootstrapEpoch: number
  projectAssetBootstrapDebounceTimer: ReturnType<typeof setTimeout> | null
  assetPageMounted: boolean
  assetRouteWatchBootstrapped: boolean
  /** 并发合并：短时间多次 reload 只保留一次 in-flight，避免 /task/list 成倍触发 */
  reloadOngoingTasksPromise: Promise<boolean> | null
  restoreAndTrackOngoingTasksInFlight: Promise<void> | null
  /** 关窗续跟：若 restore 进行中再请求，结束后须再跑一轮（避免 await 后直接 return 丢接棒） */
  restoreAndTrackRequestId: number
  /** 合并短时间内的多次通知，避免任务角标与 Popover 对 /task/list 风暴式请求 */
  notifyGlobalTasksDebounceTimer: ReturnType<typeof setTimeout> | null
  /** 第三步图片/形态写入 Pinia，第四步/第五步「本作品资产」弹窗从此读取 */
  syncingStep3ToStore: boolean
  cancelEditSceneImageModalPreload: (() => void) | null
}

export type ScpCtx = ScpBaseCtx &
  ScpDerivedApi &
  ScpRpsOpsApi &
  ScpAssetLoadApi &
  ScpGenStatusApi &
  ScpGenStatusSyncApi &
  ScpTaskProgressApi &
  ScpTaskHydrateApi &
  ScpSubmitFieldsApi &
  ScpSettingCardApi &
  ScpFinalizersApi &
  ScpTaskFollowApi &
  ScpTaskRestoreApi &
  ScpModalSseSyncApi &
  ScpBootstrapApi &
  ScpGenerateActionsApi &
  ScpSceneCrudApi &
  ScpCharacterCrudApi &
  ScpCharacterImagesApi &
  ScpPropCrudApi &
  ScpPropImagesApi
