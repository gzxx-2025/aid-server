import type { DubbingPanel,StoryboardPanel,StoryboardVideoPanel } from '~/types'
import type { StoryboardRecordRow } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import type { DubbingGenHistoryItem } from '~/utils/storyboardDubbingGenHistory'
import type { Mirrored } from './useMirrored'

/** 右侧「生成记录」条目 key：__source__ = 原分镜视频 */
export const navKeySource = '__source__'
/** 右侧「生成记录」条目 key：__loading__ = 生成中占位 */
export const navKeyLoading = '__loading__'

export const TAB_SWITCH_SKELETON_MS = 260

export type DubbingGenItem = DubbingGenHistoryItem

export type DubbingNavEntry = { key: string; type: 'source' | 'gen' | 'loading'; url?: string }

export type DubbingDraft = {
  dialogue: string
  emotion: string
  lipSync: boolean
  voiceName: string
  voiceAvatarUrl: string
  voiceLibraryId?: number
  voiceModelId?: number
  timbreCode?: string
}

export type DubbingPendingPayload = { mode: 'tts' | 'upload'; localFile: File | null }

export type ServerVideoThumb = {
  id: string
  url: string
  title?: string
  isSelected?: boolean
  _serverRow?: StoryboardRecordRow
}

/** 弹窗全部可变状态（原 Vue setup 内 ref 的 Mirrored 镜像集合） */
export interface DubbingModalState {
  currentSceneIndex: Mirrored<number>
  leftPanelLoading: Mirrored<boolean>
  rightPanelLoading: Mirrored<boolean>
  /** 与「添加场景图」一致：开始配音后待确认 */
  pendingDubbingByIndex: Mirrored<Record<number, boolean>>
  pendingPayloadByIndex: Mirrored<Record<number, DubbingPendingPayload>>
  /** 本会话内点击「设置分镜音画同步结果」后，可用「取消设置」恢复到此快照前（对齐「取消添加」） */
  preConfirmPanelByIndex: Mirrored<Record<number, DubbingPanel>>
  confirmedDubbingThisSession: Mirrored<Set<number>>
  genHistoryByIndex: Mirrored<Record<number, DubbingGenItem[]>>
  /** 按分镜 panel.id 记录 loading，避免切换 Tab 后下标串流 */
  genLoadingByPanelKey: Mirrored<Record<string, boolean>>
  selectedNavKeyByIndex: Mirrored<Record<number, string>>
  generatingMetaByIndex: Mirrored<
    Record<number, { voice: string; emotion: string; timeLabel: string }>
  >
  /** 对口型 SSE 配音阶段试听（按场景） */
  lipSyncProgressHintByIndex: Mirrored<Record<number, string>>
  isSettingFinalDubbing: Mirrored<boolean>
  isDeletingDubbingRecord: Mirrored<boolean>
  serverVideoRecordsByIndex: Mirrored<Record<number, ServerVideoThumb[]>>
  draftDialogue: Mirrored<string>
  draftEmotion: Mirrored<string>
  draftLipSync: Mirrored<boolean>
  draftVoiceName: Mirrored<string>
  draftVoiceAvatarUrl: Mirrored<string>
  draftVoiceLibraryId: Mirrored<number>
  draftVoiceModelId: Mirrored<number>
  draftTimbreCode: Mirrored<string>
  /** 音色服务商/模型提示（MiniMax 字数预检） */
  draftVoiceProviderHint: Mirrored<string>
  draftByIndex: Mirrored<Record<number, DubbingDraft>>
  voicePickerOpen: Mirrored<boolean>
  emotionNameToCode: Mirrored<Map<string, string>>
  /** 情绪按钮文案（与 emotionNameToCode 同源，避免子组件再打一次 tags） */
  emotionLabelOptions: Mirrored<string[]>
}

/** 弹窗最新 props（事件回调 / 异步流程内读取） */
export interface DubbingModalPropsSnapshot {
  open: boolean
  sceneIndex: number
  dubbingPanels: DubbingPanel[]
  storyboardVideoPanels: StoryboardVideoPanel[]
  storyboardScriptPanels: StoryboardPanel[]
  batchGeneratingIndices: number[]
  editorScopeKey: string
}

/** 跨模块共享上下文：ops 模块通过它读写状态、props 与回调 */
export interface DubbingModalCtx {
  props: () => DubbingModalPropsSnapshot
  /** 原 emit('update:panels', ...) */
  emitPanelsUpdate: (panels: DubbingPanel[]) => void
  /** 原 emit('update:open', ...) */
  emitOpenChange: (open: boolean) => void
  state: DubbingModalState
  /** useStoryboardModalHeaderTabs.refreshHeaderTabs（渲染期同步最新引用） */
  refreshHeaderTabs: (force?: boolean) => Promise<void>
  /** 事件回调内取路由快照（原 useRoute()） */
  route: () => RouteLikeLocation
  /** 中间大图预览重置（切换选中项 / 分镜时暂停并复位） */
  resetHeroVideoPreviewState: () => void
  /** 恢复跟进代际：切换 / 重入时递增使在途恢复失效 */
  resumeDubbingFollowGen: { current: number }
  serverVideoRecordsInflightByIndex: Map<number, Promise<void>>
  prefetchComposeGenHistoryInflight: { current: Promise<void> | null }
}
