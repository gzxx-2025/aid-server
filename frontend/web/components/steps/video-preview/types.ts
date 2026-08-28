import type { RefObject } from 'react'
import type { createDebouncedTimelineSaver } from '~/hooks/useEpisodeTimeline'
import type { DubbingPanel,StoryboardVideoPanel } from '~/types'
import type { TimelineData } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import type { CreateFlowShellContext } from '~/utils/createFlowInjection'
import type {
PreviewAudioItem,
PreviewSubtitleItem,
PreviewVideoClip
} from '~/utils/episodeTimelineMap'
import type { Mirrored } from './useMirrored'
export type TrackType = 'video' | 'voice' | 'subtitle' | 'music'
export type ResizeSide = 'start' | 'end'

export type TimelineBase = {
  id: string
  start: number
  duration: number
}

/** 与服务端 timeline 映射层（episodeTimelineMap）的 UI 结构完全一致 */
export type TimelineVideoClip = PreviewVideoClip
export type TimelineAudioItem = PreviewAudioItem
export type TimelineSubtitleItem = PreviewSubtitleItem

export const SCALE_PX_PER_SEC = 90
export const MIN_DURATION = 0.1
/** 无分镜视频时的占位时长（秒），进度条更短便于区分 */
export const EMPTY_CLIP_DURATION = 1.5
/** 有视频但尚未探测到真实时长时的初始占位 */
export const VIDEO_CLIP_FALLBACK_DURATION = 5
/** 分镜块最小宽度（无视频时的占位宽度） */
export const MIN_CLIP_WIDTH_PX = EMPTY_CLIP_DURATION * SCALE_PX_PER_SEC
/** 播放中由视频时钟驱动时间轴；切镜前 ~0.8s 才预载下一分镜 */
export const STANDBY_PREPARE_REMAIN_SEC = 0.85
/** 播放中仅在漂移过大时纠偏，避免频繁 seek 把播放头拽进未缓冲区 */
export const PLAYING_SEEK_DRIFT_SEC = 0.4

export type ClipLayoutEntry = {
  id: string
  leftPx: number
  widthPx: number
  startSec: number
  durationSec: number
}

export type ClipDisplayLayout = {
  totalWidthPx: number
  playheadScalePxPerSec: number
  entries: ClipLayoutEntry[]
}

export type RulerMarkType = 'major' | 'medium' | 'minor'

export type MusicDisplayBar = {
  key: string
  item: TimelineAudioItem
  empty: boolean
}

export type DragState =
  | {
      kind: 'move'
      track: TrackType
      id: string
      startX: number
      originStart: number
      /** 按下时时间轴 scrollLeft，用于拖拽中自动滚动补偿 */
      originScrollLeft: number
      /** 最近一次指针 X，供 rAF 边缘滚动使用 */
      lastClientX: number
    }
  | {
      kind: 'resize'
      track: TrackType
      id: string
      side: ResizeSide
      startX: number
      originStart: number
      originDuration: number
      originScrollLeft: number
      lastClientX: number
    }
  | null

export type VolumeDragState = {
  clipId: string
  startY: number
  startVolume: number
  barHeight: number
} | null

export type SelectedClipState = { track: TrackType; id: string } | null

export type SubtitleRangeState = { active: boolean; startSec: number; endSec: number }

export type PreviewAudioEl = HTMLAudioElement & { _aidUrl?: string; _aidPlayFailed?: boolean }

/** 原组件 defineProps（调用点：pages/create/preview.vue → 创作壳步骤页） */
export interface VideoPreviewProps {
  storyboardVideoPanels: StoryboardVideoPanel[]
  dubbingPanels: DubbingPanel[]
  bgm?: string
}

/** 原 Vue setup 中全部响应式 ref（渲染态），集中由 useVideoPreviewState 创建 */
export interface VideoPreviewState {
  isVideoModalOpen: Mirrored<boolean>
  editingVideoClipIndex: Mirrored<number>
  isDubbingModalOpen: Mirrored<boolean>
  editingDubbingClipIndex: Mirrored<number>
  isMusicModalOpen: Mirrored<boolean>
  videoVolumePreset: Mirrored<Record<string, number>>
  volumeHoverClipId: Mirrored<string | null>
  volumeDrag: Mirrored<VolumeDragState>

  /** 双缓冲：切换分镜时互换可见层，避免单 video 改 src 黑闪 */
  nativeActiveSlot: Mirrored<'A' | 'B'>
  /** 当前可见 native 槽是否已有可展示首帧 */
  nativePreviewFrameReady: Mirrored<boolean>

  playing: Mirrored<boolean>
  muted: Mirrored<boolean>
  exporting: Mirrored<boolean>
  exportProgressPercent: Mirrored<number>
  timelineLoading: Mirrored<boolean>
  segmentsDownloading: Mirrored<boolean>
  exportNeedReaudit: Mirrored<boolean>
  exportPendingVideoUrl: Mirrored<string>
  exportFinalVideoUrl: Mirrored<string>
  /** 最近一次从服务端加载的 timeline，保存时用于保留音色元数据 */
  serverTimelineBaseline: Mirrored<TimelineData | null>
  timelineResolution: Mirrored<string>

  /** 与 .timeline-inner padding-left（--vp-timeline-label-w）同步 */
  trackLabelWidth: Mirrored<number>
  videoClips: Mirrored<TimelineVideoClip[]>
  voiceItems: Mirrored<TimelineAudioItem[]>
  subtitleItems: Mirrored<TimelineSubtitleItem[]>
  musicItems: Mirrored<TimelineAudioItem[]>
  selectedClip: Mirrored<SelectedClipState>
  snapIndicatorPx: Mirrored<number | null>
  snapEnabled: Mirrored<boolean>
  snapSourceMode: Mirrored<'edges' | 'edges-playhead' | 'edges-grid'>
  snapDistancePx: Mirrored<number>
  swappingClipIds: Mirrored<Set<string>>
  timelineStripWidthPx: Mirrored<number>

  currentTime: Mirrored<number>
  scrubbing: Mirrored<boolean>
  scrubClientX: Mirrored<number | null>
  autoFollowEnabled: Mirrored<boolean>

  dragState: Mirrored<DragState>
  subtitleRange: Mirrored<SubtitleRangeState>

  subtitleModalOpen: Mirrored<boolean>
  subtitleDraft: Mirrored<string>
  subtitleFontSizeDraft: Mirrored<number>
  editingSubtitleId: Mirrored<string | null>
  pendingAddAudioTrack: Mirrored<'voice' | 'music' | null>
  pendingVoiceVideoClipId: Mirrored<string | null>
  replacingVoiceId: Mirrored<string | null>
}

/** 原 setup 闭包内的非响应式可变量（let/Map/实例），不触发渲染 */
export interface VideoPreviewRuntime {
  exportProgressScopeKey: string
  timelineSaver: ReturnType<typeof createDebouncedTimelineSaver>

  autoFollowResumeTimer: number | null
  suppressScrollFollowPause: boolean
  programmaticScrollLockUntil: number

  previewPlayRaf: number | null
  previewPlayStartedAt: number
  previewPlayStartSec: number
  /** 当前播放允许到达的最远时间点（连续有视频分镜段的末尾） */
  previewPlayEndSec: number
  previewAudioEls: Map<string, HTMLAudioElement>
  previewVideoPreloads: Map<string, HTMLVideoElement>
  /** 各缓冲槽已加载的视频 URL */
  slotSrcA: string
  slotSrcB: string
  /** 当前正在显示的分镜 id */
  activeNativeClipId: string
  /** 待命缓冲已为哪个分镜准备好（可无缝切换） */
  standbyPreparedClipId: string
  standbyPrepareToken: number

  avCanvas: any
  avUnsubTime: (() => void) | null
  avUnsubPlaying: (() => void) | null
  avUnsubPaused: (() => void) | null
  mediaBlobCache: Map<string, Blob>
  rebuildTimer: number | null
  currentPreviewToken: number

  exportFollowAbort: AbortController | null
  exportFollowInFlight: Promise<void> | null
  exportFollowGeneration: number

  timelineResizeObserver: ResizeObserver | null
  lastHydratedScopeKey: string

  setTimelineResizeObserver: (observer: ResizeObserver | null) => void
  setActiveNativeClipId: (clipId: string) => void
  clearNativeSlots: (resetPrepareToken?: boolean) => void
  clearExportProgressScope: () => void
  dispose: () => void
}

export interface VideoPreviewDomRefs {
  timelineWrapRef: RefObject<HTMLDivElement | null>
  subtitleStripRef: RefObject<HTMLDivElement | null>
  canvasHostRef: RefObject<HTMLDivElement | null>
  nativePreviewVideoARef: RefObject<HTMLVideoElement | null>
  nativePreviewVideoBRef: RefObject<HTMLVideoElement | null>
  audioInputRef: RefObject<HTMLInputElement | null>
}

/** ops 模块共享上下文：状态 + 运行时 + DOM + 最新 props/route/壳层 */
export interface VideoPreviewCtx {
  state: VideoPreviewState
  runtime: VideoPreviewRuntime
  dom: VideoPreviewDomRefs
  getProps: () => VideoPreviewProps
  getRoute: () => RouteLikeLocation
  getShell: () => CreateFlowShellContext | null
}
