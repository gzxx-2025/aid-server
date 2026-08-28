import type { RefObject } from 'react'
import type { HorizontalScrollTabBarHandle } from '~/components/common/HorizontalScrollTabBar'
import type { CreationStoreState } from '~/stores/creation'
import type { UserModelListItem } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import type { PromptAssetItem } from '~/utils/storyboardPromptAssetRef'
import type { StoryboardGeneratePanelHandle } from '../StoryboardGeneratePanel'
import type { Mirrored } from './useMirrored'
import type { StoryboardModalCanvasOverlayApi } from './useStoryboardModalCanvasOverlay'
import type { StoryboardModalDialogueApi } from './useStoryboardModalDialogue'
import type { StoryboardModalGenerateApi } from './useStoryboardModalGenerate'
import type { StoryboardModalImageActionsApi } from './useStoryboardModalImageActions'
import type { StoryboardModalModelsApi } from './useStoryboardModalModels'
import type { StoryboardModalPromptApi } from './useStoryboardModalPrompt'
import type { StoryboardModalRecordsApi } from './useStoryboardModalRecords'
import type { StoryboardModalSessionStateApi } from './useStoryboardModalSessionState'

export interface EditStoryboardImageModalScene {
  name: string
  images?: any[]
  scriptContent?: string
  storyboardId?: number | string
}

export interface EditStoryboardImageModalProps {
  open: boolean
  sceneIndex: number
  initialImageIndex?: number | null
  scenes: EditStoryboardImageModalScene[]
  /** 弹窗实例作用域，配合 storyboardId 隔离不同分镜的生图 loading */
  editorScopeKey?: string
  /** 原 emit('update:open') */
  onOpenChange: (value: boolean) => void
  /** 原 emit('update', sceneIndex, data) */
  onUpdate: (sceneIndex: number, data: any) => void
}

/** withDefaults 之后的 props（默认值已填充） */
export type ResolvedEditStoryboardImageModalProps = EditStoryboardImageModalProps & {
  editorScopeKey: string
}

export type CanvasToolbarKey = 'drawing' | 'chat' | 'hd' | 'camera' | 'add'

// 左侧 Tab：生成分镜图 / 对话作图
export type LeftActiveTab = 'generate' | 'dialogue'

export type DialogueSourceImage = { url: string; title?: string }

export type SettingKey =
  | 'composition'
  | 'shotSize'
  | 'cameraAngle'
  | 'focalLength'
  | 'colorTone'
  | 'lighting'
  | 'technique'

export const CANVAS_OVERLAY_TASK_KINDS = ['upscale', 'dialogue', 'multiangle', 'ninegrid'] as const

export type StoryboardCanvasOverlayTaskKind = (typeof CANVAS_OVERLAY_TASK_KINDS)[number]

export const STORYBOARD_GENERATED_IMAGE_DEFAULT_TITLE = '未命名'

export type GenerationSettingsValue = {
  model: string
  aspectRatio: string
  count: number
  quality: string
}

export type SelectAssetModalType =
  | 'scene'
  | 'character'
  | 'prop'
  | 'pose'
  | 'expression'
  | 'effect'
  | 'draft'
  | 'other'

export type SelectedParamValue = { key: string; value: string } | null

/**
 * 原 Vue setup() 单闭包按 UI 区块 / 逻辑内聚拆分后共享的运行时上下文。
 * controller 创建 base 部分，各子 hook 把自己的 API Object.assign 进来（延迟绑定解环）。
 */
export interface EditStoryboardImageModalBaseCtx {
  /** 事件回调 / 异步流程内读最新 props（默认值已填充） */
  props: () => ResolvedEditStoryboardImageModalProps
  route: () => RouteLikeLocation
  store: () => CreationStoreState
  emitOpenChange: (value: boolean) => void
  /** 原 emit('update', sceneIndex, data) */
  emitUpdate: (sceneIndex: number, data: any) => void

  // —— 顶层可变状态（原 ref；Mirrored=需触发渲染，{ current } 风格=纯逻辑量）——
  currentSceneIndex: Mirrored<number>
  currentImageIndex: Mirrored<number>
  leftActiveTab: Mirrored<LeftActiveTab>
  showStoryboardScriptModal: Mirrored<boolean>
  /** 每次打开分镜脚本编辑时递增，保证与列表最新 scriptContent 对齐 */
  scriptEditorKey: Mirrored<number>
  editingImageTitleIndex: Mirrored<number | null>
  editingImageTitle: Mirrored<string>
  localSceneImages: Mirrored<any[]>
  canvasToolbarHoverKey: Mirrored<CanvasToolbarKey | null>

  // 生成设置与模型
  modelDropdownExpanded: Mirrored<boolean>
  initImageModelGen: { current: number }
  cachedStoryboardImageAgentModelCodes: { current: string[] }
  dialogueSourceImages: Mirrored<DialogueSourceImage[]>
  dialogueInstructionHtml: Mirrored<string>
  showDialogueImportModal: Mirrored<boolean>
  dialogueModelDropdownExpanded: Mirrored<boolean>
  dialogueSettings: Mirrored<GenerationSettingsValue>
  generationSettings: Mirrored<GenerationSettingsValue>
  multiViewModelDropdownExpanded: Mirrored<boolean>
  /** 变清晰：listByFunc(image_upscale) 模型池 */
  upscaleModelPool: Mirrored<UserModelListItem[]>
  multiViewSettings: Mirrored<{ model: string }>
  nineGridSettings: Mirrored<{ model: string }>
  nineGridAspectRatio: Mirrored<string>

  // 左侧资产面板：场景/角色/道具/其他（多选回传的图片列表）
  sceneImages: Mirrored<any[]>
  characterImages: Mirrored<any[]>
  propImages: Mirrored<any[]>
  otherImages: Mirrored<any[]> // 姿态图、表情图、特效图、手绘稿合并列表
  storyboardGeneratePanelRef: RefObject<StoryboardGeneratePanelHandle | null>

  // 选择弹窗状态
  selectAssetModalOpen: Mirrored<boolean>
  selectAssetModalType: Mirrored<Exclude<SelectAssetModalType, never>>
  showOtherListDropdown: Mirrored<boolean>

  activeSettingKey: Mirrored<SettingKey | null>

  // 左侧（分镜）面板状态
  isSettingExpanded: Mirrored<boolean>
  compositionDesc: Mirrored<string>
  selectedComposition: Mirrored<SelectedParamValue>
  selectedShotSize: Mirrored<SelectedParamValue>
  selectedCameraAngle: Mirrored<SelectedParamValue>
  selectedFocalLength: Mirrored<SelectedParamValue>
  selectedColorTone: Mirrored<SelectedParamValue>
  selectedLighting: Mirrored<SelectedParamValue>
  selectedTechnique: Mirrored<SelectedParamValue>

  storyboardPrompt: Mirrored<string>
  resolvedPromptAssets: Mirrored<PromptAssetItem[]>
  /** 接口回填提示词时暂停面板内 prompt/参数联动，避免 Quill 与 watcher 递归更新 */
  storyboardPromptProgrammaticSyncDepth: Mirrored<number>
  isGeneratingPrompt: Mirrored<boolean>
  isGeneratingStoryboardImage: Mirrored<boolean>
  storyboardGenerateProgressText: Mirrored<string>
  storyboardGenerateTargetKey: Mirrored<string>
  promptGenerateTargetKey: Mirrored<string>

  resumeStoryboardImageFollowGen: { current: number }
  resumeStoryboardPromptFollowGen: { current: number }
  resumeDialogueFollowGen: { current: number }
  activePromptFollowStoryboardIds: Set<number>
  resumeCanvasOverlayFollowGen: { current: number }

  showAssetLibraryModal: Mirrored<boolean>
  showMaterialFromLibraryModal: Mirrored<boolean>
  showMultiAngleModal: Mirrored<boolean>
  multiAngleTargetIndex: { current: number | null }
  multiAngleImageUrl: Mirrored<string>
  /** pose | expression | effect，与素材库左侧分类 key 一致 */
  materialLibraryCategoryKey: Mirrored<string>
  /** 是否由「+ 导入其他」打开素材库，确认导入后需把图片地址写入描述文本域 */
  materialImportAppendToStoryPrompt: Mirrored<boolean>
  isSelectingSceneImage: Mirrored<boolean>
  selectedSceneImageIndex: Mirrored<number | null>
  addingAfterIndex: { current: number | null }
  pendingImage: { current: any | null }
  addedImageIds: Mirrored<Set<string>>
  isSettingFinalImage: Mirrored<boolean>
  uploadingLocalImageAtKey: Mirrored<string>
  assetLibraryImportInFlight: { current: boolean }
  isDeletingRecord: Mirrored<boolean>

  mainContentRef: RefObject<HTMLDivElement | null>
  sceneTabBarRef: RefObject<HorizontalScrollTabBarHandle | null>

  // 切换分镜 Tab 时，左右两侧分别展示骨架屏
  leftPanelLoading: Mirrored<boolean>
  rightPanelLoading: Mirrored<boolean>

  /** 点选改图入口（暂不开放） */
  showTouchEditToolbar: boolean
  showTouchEditModal: Mirrored<boolean>
  touchEditImageUrl: Mirrored<string>

  /** 分镜图高清（/storyboard/generate/upscale）：画布遮罩 */
  upscaleUiPhase: Mirrored<'idle' | 'running' | 'failed'>
  upscaleTargetKey: Mirrored<string>
  upscaleProgressText: Mirrored<string>
  upscaleFailedMessage: Mirrored<string>
  upscaleContext: { current: { sceneIndex: number; imageIndex: number } | null }
  /** 画布遮罩当前任务类型，工具栏 loading 与任务一一对应 */
  canvasOverlayTaskKind: Mirrored<StoryboardCanvasOverlayTaskKind | null>

  // —— 基础 helpers（controller 内实现）——
  refreshHeaderTabs: (force?: boolean) => Promise<void>
  scrollActiveSceneTabIntoView: () => void
  syncSceneDetailAndRestore: (sceneIdx: number) => Promise<void>
}

export type EditStoryboardImageModalCtx = EditStoryboardImageModalBaseCtx &
  StoryboardModalSessionStateApi &
  StoryboardModalModelsApi &
  StoryboardModalRecordsApi &
  StoryboardModalGenerateApi &
  StoryboardModalPromptApi &
  StoryboardModalDialogueApi &
  StoryboardModalCanvasOverlayApi &
  StoryboardModalImageActionsApi
