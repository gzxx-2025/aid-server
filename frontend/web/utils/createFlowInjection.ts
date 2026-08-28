import { createContext } from 'react'
import type { ExtractModalScope } from '~/components/steps/ExtractAgentModal'
import type { GlobalSettingData,StoryboardPanel } from '~/types'
/** 创作流程内「项目配置」页 / 弹窗状态与保存 */
export interface CreateFlowGlobalSettingContext {
  confirmLoading: boolean
  titleDraft: string
  projectTypeDraft: 'movie' | 'series'
  draft: GlobalSettingData
  projectTypeLocked: boolean
  /** 项目配置弹窗是否打开（剧集分集列表入口） */
  showModal: boolean
  syncFromStore: () => void
  openModal: () => void
  /** 原 Vue 版为可写 ref：global-setting 步骤页直接写 titleDraft / projectTypeDraft */
  setTitleDraft: (value: string) => void
  setProjectTypeDraft: (value: 'movie' | 'series') => void
  updateField: <K extends keyof GlobalSettingData>(key: K, value: GlobalSettingData[K]) => void
  patchStyle: (
    patch: Pick<
      GlobalSettingData,
      'selectedStyle' | 'myStyles' | 'style' | 'styleSelectionTouched' | 'styleLocked'
    >
  ) => void
  save: () => Promise<void>
}

/** 成品预览页注册到壳层的导出能力（顶栏「导出视频」下拉调用） */
export interface PreviewExportBridge {
  exportFullVideo: () => Promise<{
    videoUrl: string
    needReaudit?: boolean
    episodeEditorId?: number
  } | null>
  exportSegments: () => Promise<void>
  exporting: boolean
  segmentsDownloading: boolean
}

/** 创作壳层提供给子路由页的回调（侧栏、流程条仍在壳内） */
export interface CreateFlowShellContext {
  goToStep: (stepIndex: number) => void
  stopExtractAssets: () => void | Promise<void>
  openExtractModalFromScp: (scope: Exclude<ExtractModalScope, 'all'>) => void
  openContinueExtractModal: () => void
  dismissScriptChangeLightBanner: () => void
  jumpToStoryboardScriptFromVideo: (panelIndex: number) => void
  clearStoryboardScriptJumpTooltip: () => void
  storyboardScriptTooltipTargetIndex: number | null
  storyboardScriptTooltipKey: number
  syncVideoAndDubbingFromScriptPanels: (panels: StoryboardPanel[]) => void
  setDubbingGenerating: (v: boolean) => void
  /** 分镜列表接口拉取中（刷新/切作品） */
  storyboardListLoading: boolean
  /** 当前作品分镜列表已完成首次同步 */
  storyboardListSyncReady: boolean
  globalSetting: CreateFlowGlobalSettingContext
  openProjectGenConfig: () => void
  /** 成品预览页挂载时注册导出桥接，卸载时传 null */
  registerPreviewExportBridge: (bridge: PreviewExportBridge | null) => void
  /** 完整导出成功通知（含刷新恢复场景）：壳层自动保存成片至本地 */
  notifyPreviewExportSuccess: (videoUrl: string) => void
}

/** 原 provide/inject（createFlowShellKey）的 React Context 版本；壳层 Provider 挂载后子步骤页 useContext 读取 */
export const createFlowShellContext = createContext<CreateFlowShellContext | null>(null)
