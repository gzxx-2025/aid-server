'use client'

import { useRef } from 'react'
import { useCreationStore } from '~/stores/creation'
import type { SceneCharacterData } from '~/types'
import type { UserTaskRow } from '~/types/business-api'
import { useMirrored } from './mirrored'
import { emptyStep3TabTaskProgress } from './scpTaskUtils'
import type {
CharacterFormItem,
FormGenStatus,
PropFormItem,
ScpBaseCtx,
Step3TabTaskProgress,
TabKey
} from './types'
import type { RpsSettingEditorState } from './scpSettingPromptUtils'

/**
 * 原 script setup 顶层可变状态（ref / let / Map / Set）的集中声明。
 * Mirrored 状态需触发渲染；实例级 let 用 useRef 容器保存（不触发渲染）。
 */
export function useScpState() {
  const activeTab = useMirrored<TabKey>('scene')
  const localValue = useMirrored<SceneCharacterData>({
    characters: [],
    scenes: [],
    props: []
  })

  // 场景来源标记：记录哪些场景是手动添加的（必须在 watch 之前定义）
  const manualScenes = useMirrored<Set<number>>(() => new Set())
  // 角色来源标记：记录哪些角色是手动添加的
  const manualCharacters = useMirrored<Set<number>>(() => new Set())
  // 道具来源标记：记录哪些道具是手动添加的
  const manualProps = useMirrored<Set<number>>(() => new Set())

  /** 列表索引 → 服务端资产 id（用于删除接口；来自 query 列表或 create 返回） */
  const sceneAssetIds = useMirrored<Record<number, number>>({})
  const characterAssetIds = useMirrored<Record<number, number>>({})
  const propAssetIds = useMirrored<Record<number, number>>({})
  /** 列表索引 → 该主表下所有形态 id（删除时需逐个调用 rps/delete） */
  const sceneFormIdsByIndex = useMirrored<Record<number, number[]>>({})
  const characterFormIdsByIndex = useMirrored<Record<number, number[]>>({})
  const propFormIdsByIndex = useMirrored<Record<number, number[]>>({})
  // 角色的形态数据：Record<角色索引, 形态数组>
  const characterForms = useMirrored<Record<number, CharacterFormItem[]>>({})
  // 道具的形态数据：Record<道具索引, 形态数组>
  const propForms = useMirrored<Record<number, PropFormItem[]>>({})

  /** 须在 `projectContextDeps` 的 immediate watch 之前初始化，避免 TDZ（Cannot access before initialization） */
  const sceneGenerationStatus = useMirrored<Record<number, FormGenStatus>>(() => ({
    ...useCreationStore.getState().sceneGenerationStatus
  }))
  const characterFormGenerationStatus = useMirrored<Record<string, FormGenStatus>>(() => ({
    ...useCreationStore.getState().characterFormGenerationStatus
  }))
  const propFormGenerationStatus = useMirrored<Record<string, FormGenStatus>>(() => ({
    ...useCreationStore.getState().propFormGenerationStatus
  }))

  /** 当前页并行跟进的 SSE 任务（刷新后可同时恢复多条形态/生图任务） */
  const activeTrackedTaskIds = useMirrored<number[]>([])
  const step3TabTaskProgress = useMirrored<Record<TabKey, Step3TabTaskProgress>>(() => ({
    scene: emptyStep3TabTaskProgress(),
    character: emptyStep3TabTaskProgress(),
    prop: emptyStep3TabTaskProgress()
  }))
  /** taskId -> Tab，用于 SSE 回调写入对应 Tab 文案，并在任务结束时仅清除该 Tab */
  const step3TaskIdToTab = useMirrored<Record<number, TabKey>>({})
  /** 登记进行中任务元数据，Tab 切换断线后可重连 SSE */
  const step3TaskMetaById = useMirrored<
    Record<number, { tab: TabKey; taskType: string | null; assetIds?: number[] }>
  >({})
  /** 最近一次 /task/list 的第三步相关行（含终态），切 Tab 用 list 判状态，禁止逐条 detail */
  const recentStep3TaskRows = useMirrored<UserTaskRow[]>([])

  const tabAssetLoading = useMirrored<Record<TabKey, boolean>>({
    scene: false,
    character: false,
    prop: false
  })

  /** 上次已加载的「作品+剧集」scope；切换前把 Pinia 里仍是上一作品的扁平生成态写回分桶 */
  const lastStep3VisualScopeKey = useMirrored('')

  /**
   * 当前作品/剧集下：三步 rps 拉取 +「待生成形态」reconcile + 任务恢复跑完之前为 false。
   * 避免刷新后先闪「完整卡片（自动生成/导入）」再切到「待生成形态」小卡片。
   */
  const step3AssetBootstrapReady = useMirrored(false)

  /** 待生成形态小卡片上：按资产 ID 防止重复点击 */
  const pendingFormGenBusy = useMirrored<Record<number, boolean>>({})
  /** 顶栏「批量生成形态」提交中 */
  const batchFormGenerateSubmitting = useMirrored(false)
  /** 顶栏「批量删除」提交中 */
  const batchDeleteSubmitting = useMirrored(false)
  /** 顶栏「其他操作」下拉展开态 */
  const batchOpsDropdownOpen = useMirrored(false)
  const batchCardGenerateSubmitting = useMirrored(false)
  /** 设定卡批量生成：按白底主图 imageId 追踪 busy（刷新后可恢复） */
  const settingCardGenBusyByImageId = useMirrored<Record<number, boolean>>({})
  const defaultTextModelCode = useMirrored('')
  const ongoingTasks = useMirrored<UserTaskRow[]>([])
  const ongoingTasksLoading = useMirrored(false)

  // 场景图片数据存储
  const sceneImages = useMirrored<Record<number, any[]>>({})
  // 角色图片数据存储：Record<角色索引, 图片数组>
  const characterImages = useMirrored<Record<number, any[]>>({})
  // 道具图片数据存储：Record<道具索引, 图片数组>
  const propImages = useMirrored<Record<number, any[]>>({})
  // 角色形态图片数据存储：Record<"characterIndex-formIndex", 图片数组>
  const characterFormImages = useMirrored<Record<string, any[]>>({})
  // 道具形态图片数据存储：Record<"propIndex-formIndex", 图片数组>
  const propFormImages = useMirrored<Record<string, any[]>>({})

  // 场景设定相关
  const sceneSettings = useMirrored<Record<string, RpsSettingEditorState>>({})
  const characterSettings = useMirrored<Record<string, RpsSettingEditorState>>({})
  const propSettings = useMirrored<Record<string, RpsSettingEditorState>>({})

  // 场景名称编辑
  const editingSceneIndex = useMirrored<number | null>(null)
  const editingSceneName = useMirrored('')
  /** 「待生成形态」横滑卡片标题编辑 */
  const editingPendingFormCardKey = useMirrored<string | null>(null)
  const editingPendingFormTitle = useMirrored('')
  // 场景图名称编辑
  const editingImageTitleIndex = useMirrored<string | null>(null)
  const editingImageTitle = useMirrored('')
  // 角色名称编辑
  const editingCharacterIndex = useMirrored<number | null>(null)
  const editingCharacterName = useMirrored('')
  // 形态管理
  const editingFormIndex = useMirrored<string | null>(null)
  const editingFormName = useMirrored('')
  // 道具名称编辑
  const editingPropIndex = useMirrored<number | null>(null)
  const editingPropName = useMirrored('')
  // 道具形态管理
  const editingPropFormIndex = useMirrored<string | null>(null)
  const editingPropFormName = useMirrored('')

  const showSceneSettingModal = useMirrored(false)
  const currentSceneIndex = useMirrored(-1)
  const showImportSceneImageModal = useMirrored(false)
  const currentImportSceneIndex = useMirrored(-1)
  const showEditSceneImageModal = useMirrored(false)
  const currentEditSceneIndex = useMirrored(-1)
  // 带图片索引的编辑场景图（点击图片时调用）
  const currentEditImageIndex = useMirrored<number | null>(null)
  const showCharacterSettingModal = useMirrored(false)
  const currentCharacterIndex = useMirrored(-1)
  const currentCharacterSettingFormIndex = useMirrored(-1)
  const showImportCharacterImageModal = useMirrored(false)
  const currentImportCharacterIndex = useMirrored(-1)
  const showEditCharacterImageModal = useMirrored(false)
  const currentEditCharacterIndex = useMirrored(-1)
  const currentEditCharacterImageIndex = useMirrored<number | null>(null)
  const showImportCharacterFormImageModal = useMirrored(false)
  const currentImportCharacterFormKey = useMirrored('') // "characterIndex-formIndex"
  const showEditCharacterFormImageModal = useMirrored(false)
  const currentEditCharacterFormKey = useMirrored('') // "characterIndex-formIndex"
  const currentEditCharacterFormImageIndex = useMirrored<number | null>(null)
  const showVoiceTimbrePickerModal = useMirrored(false)
  const currentVoiceCharacterIndex = useMirrored(-1)
  const currentVoiceFormIndex = useMirrored(-1)
  const voicePickerInitialName = useMirrored('')
  const playingVoicePreviewKey = useMirrored<string | null>(null)
  const showPropSettingModal = useMirrored(false)
  const currentPropIndex = useMirrored(-1)
  const currentPropSettingFormIndex = useMirrored(-1)
  const showImportPropImageModal = useMirrored(false)
  const currentImportPropIndex = useMirrored(-1)
  const showEditPropImageModal = useMirrored(false)
  const currentEditPropIndex = useMirrored(-1)
  const currentEditPropImageIndex = useMirrored<number | null>(null)
  const showImportPropFormImageModal = useMirrored(false)
  const currentImportPropFormKey = useMirrored('') // "propIndex-formIndex"
  const showEditPropFormImageModal = useMirrored(false)
  const currentEditPropFormKey = useMirrored('') // "propIndex-formIndex"
  const currentEditPropFormImageIndex = useMirrored<number | null>(null)
  const showBatchGenerateModal = useMirrored(false)
  const batchGenerateType = useMirrored<'scene' | 'character' | 'prop'>('scene')
  const batchGenerateMode = useMirrored<'image' | 'setting-card' | 'form' | 'delete'>('image')

  const scpContentRef = useRef<HTMLDivElement | null>(null)
  const voicePreviewAudioRef = useRef<HTMLAudioElement | null>(null)

  /** 实例级可变量（原 script setup 顶层 let / Map / Set）：只创建一次 */
  const instanceRef = useRef<null | {
    suppressActiveTabAssetLoadOnce: boolean
    activeTabBeforeExtractStart: TabKey | null
    singleExtractTabLock: TabKey | null
    loadAssetTabGeneration: number
    tabAssetLoadGen: Record<TabKey, number>
    activeTaskStreamClosers: Map<number, () => void>
    step3SseTabSwitchClosing: Set<number>
    step3TaskFollowGeneration: Map<number, number>
    taskFollowSession: number
    restoreTasksGeneration: number
    ongoingStep3TaskDetailRows: Map<number, UserTaskRow>
    projectAssetBootstrapEpoch: number
    projectAssetBootstrapDebounceTimer: ReturnType<typeof setTimeout> | null
    assetPageMounted: boolean
    assetRouteWatchBootstrapped: boolean
    reloadOngoingTasksPromise: Promise<boolean> | null
    restoreAndTrackOngoingTasksInFlight: Promise<void> | null
    restoreAndTrackRequestId: number
    notifyGlobalTasksDebounceTimer: ReturnType<typeof setTimeout> | null
    syncingStep3ToStore: boolean
    cancelEditSceneImageModalPreload: (() => void) | null
  }>(null)
  if (!instanceRef.current) {
    instanceRef.current = {
      /** bootstrap / 恢复 Tab 时已主动 load，跳过一次 watch 内重复 load */
      suppressActiveTabAssetLoadOnce: false,
      /** 多类型提取期间 Tab 会跟随 SSE 阶段；结束后恢复到用户提取前停留的 Tab */
      activeTabBeforeExtractStart: null,
      /** 单类型提取时锁定 Tab，避免 SSE 文案误匹配「场景」等把 Tab 切走 */
      singleExtractTabLock: null,
      loadAssetTabGeneration: 0,
      /** 各 Tab 独立 generation，用于切换 Tab 时正确结束 loading（与全局 loadAssetTabGeneration 解耦） */
      tabAssetLoadGen: { scene: 0, character: 0, prop: 0 },
      activeTaskStreamClosers: new Map<number, () => void>(),
      /** Tab 切换主动断开 SSE 时标记，避免误触发终态清理 */
      step3SseTabSwitchClosing: new Set<number>(),
      /**
       * 每个 taskId 的跟随世代：切回 Tab 重连时自增。
       * 旧 follow 收尾前必须校验世代，已被新 follow 接管时不得注销登记、
       * 不得 clearActiveTaskStream（会误杀新 SSE）、不得再打 task/detail 兜底。
       */
      step3TaskFollowGeneration: new Map<number, number>(),
      /** 作品/剧集切换时自增，用于丢弃旧任务 SSE 的后续回调与 finally，避免误 toast、误清 store */
      taskFollowSession: 0,
      restoreTasksGeneration: 0,
      /** task/list 不含快照；恢复阶段补全一次进行中任务详情，Tab 切换复用。 */
      ongoingStep3TaskDetailRows: new Map<number, UserTaskRow>(),
      /** 合并路由/Pinia 连续波动触发的多次 watch，避免刷新时 rps/list 等连打 2～3 遍 */
      projectAssetBootstrapEpoch: 0,
      projectAssetBootstrapDebounceTimer: null,
      assetPageMounted: false,
      assetRouteWatchBootstrapped: false,
      /** 并发合并：短时间多次 reload 只保留一次 in-flight，避免 /task/list 成倍触发 */
      reloadOngoingTasksPromise: null,
      restoreAndTrackOngoingTasksInFlight: null,
      /** 关窗续跟：若 restore 进行中再请求，结束后须再跑一轮（避免 await 后直接 return 丢接棒） */
      restoreAndTrackRequestId: 0,
      /** 合并短时间内的多次通知，避免任务角标与 Popover 对 /task/list 风暴式请求 */
      notifyGlobalTasksDebounceTimer: null,
      /** 第三步图片/形态写入 Pinia，第四步/第五步「本作品资产」弹窗从此读取 */
      syncingStep3ToStore: false,
      cancelEditSceneImageModalPreload: null
    }
  }

  const mirroredState = {
    activeTab,
    localValue,
    manualScenes,
    manualCharacters,
    manualProps,
    sceneAssetIds,
    characterAssetIds,
    propAssetIds,
    sceneFormIdsByIndex,
    characterFormIdsByIndex,
    propFormIdsByIndex,
    characterForms,
    propForms,
    sceneGenerationStatus,
    characterFormGenerationStatus,
    propFormGenerationStatus,
    activeTrackedTaskIds,
    step3TabTaskProgress,
    step3TaskIdToTab,
    step3TaskMetaById,
    recentStep3TaskRows,
    tabAssetLoading,
    lastStep3VisualScopeKey,
    step3AssetBootstrapReady,
    pendingFormGenBusy,
    batchFormGenerateSubmitting,
    batchDeleteSubmitting,
    batchOpsDropdownOpen,
    batchCardGenerateSubmitting,
    settingCardGenBusyByImageId,
    defaultTextModelCode,
    ongoingTasks,
    ongoingTasksLoading,
    sceneImages,
    characterImages,
    propImages,
    characterFormImages,
    propFormImages,
    sceneSettings,
    characterSettings,
    propSettings,
    editingSceneIndex,
    editingSceneName,
    editingPendingFormCardKey,
    editingPendingFormTitle,
    editingImageTitleIndex,
    editingImageTitle,
    editingCharacterIndex,
    editingCharacterName,
    editingFormIndex,
    editingFormName,
    editingPropIndex,
    editingPropName,
    editingPropFormIndex,
    editingPropFormName,
    showSceneSettingModal,
    currentSceneIndex,
    showImportSceneImageModal,
    currentImportSceneIndex,
    showEditSceneImageModal,
    currentEditSceneIndex,
    currentEditImageIndex,
    showCharacterSettingModal,
    currentCharacterIndex,
    currentCharacterSettingFormIndex,
    showImportCharacterImageModal,
    currentImportCharacterIndex,
    showEditCharacterImageModal,
    currentEditCharacterIndex,
    currentEditCharacterImageIndex,
    showImportCharacterFormImageModal,
    currentImportCharacterFormKey,
    showEditCharacterFormImageModal,
    currentEditCharacterFormKey,
    currentEditCharacterFormImageIndex,
    showVoiceTimbrePickerModal,
    currentVoiceCharacterIndex,
    currentVoiceFormIndex,
    voicePickerInitialName,
    playingVoicePreviewKey,
    showPropSettingModal,
    currentPropIndex,
    currentPropSettingFormIndex,
    showImportPropImageModal,
    currentImportPropIndex,
    showEditPropImageModal,
    currentEditPropIndex,
    currentEditPropImageIndex,
    showImportPropFormImageModal,
    currentImportPropFormKey,
    showEditPropFormImageModal,
    currentEditPropFormKey,
    currentEditPropFormImageIndex,
    showBatchGenerateModal,
    batchGenerateType,
    batchGenerateMode,
    scpContentRef,
    voicePreviewAudioRef
  } satisfies Partial<ScpBaseCtx>

  return { mirroredState, instance: instanceRef.current }
}
