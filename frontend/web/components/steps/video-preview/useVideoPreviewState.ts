'use client'

import { useRef } from 'react'
import { createDebouncedTimelineSaver } from '~/hooks/useEpisodeTimeline'
import type { TimelineData } from '~/types/business-api'
import type {
DragState,
SelectedClipState,
SubtitleRangeState,
TimelineAudioItem,
TimelineSubtitleItem,
TimelineVideoClip,
VideoPreviewDomRefs,
VideoPreviewRuntime,
VideoPreviewState,
VolumeDragState
} from './types'
import { useMirrored } from './useMirrored'

/** 集中创建全部渲染态（对应原 Vue setup 中的 ref 声明区） */
export function useVideoPreviewState(): VideoPreviewState {
  const isVideoModalOpen = useMirrored(false)
  const editingVideoClipIndex = useMirrored(-1)
  const isDubbingModalOpen = useMirrored(false)
  const editingDubbingClipIndex = useMirrored(-1)
  const isMusicModalOpen = useMirrored(false)
  const videoVolumePreset = useMirrored<Record<string, number>>({})
  const volumeHoverClipId = useMirrored<string | null>(null)
  const volumeDrag = useMirrored<VolumeDragState>(null)

  const nativeActiveSlot = useMirrored<'A' | 'B'>('A')
  const nativePreviewFrameReady = useMirrored(false)

  const playing = useMirrored(false)
  const muted = useMirrored(false)
  const exporting = useMirrored(false)
  const exportProgressPercent = useMirrored(0)
  const timelineLoading = useMirrored(false)
  const segmentsDownloading = useMirrored(false)
  const exportNeedReaudit = useMirrored(false)
  const exportPendingVideoUrl = useMirrored('')
  const exportFinalVideoUrl = useMirrored('')
  const serverTimelineBaseline = useMirrored<TimelineData | null>(null)
  const timelineResolution = useMirrored('FHD')

  const trackLabelWidth = useMirrored(72)
  const videoClips = useMirrored<TimelineVideoClip[]>([])
  const voiceItems = useMirrored<TimelineAudioItem[]>([])
  const subtitleItems = useMirrored<TimelineSubtitleItem[]>([])
  const musicItems = useMirrored<TimelineAudioItem[]>([])
  const selectedClip = useMirrored<SelectedClipState>(null)
  const snapIndicatorPx = useMirrored<number | null>(null)
  const snapEnabled = useMirrored(true)
  const snapSourceMode = useMirrored<'edges' | 'edges-playhead' | 'edges-grid'>('edges-playhead')
  const snapDistancePx = useMirrored(12)
  const swappingClipIds = useMirrored<Set<string>>(() => new Set())
  const timelineStripWidthPx = useMirrored(400)

  const currentTime = useMirrored(0)
  const scrubbing = useMirrored(false)
  const scrubClientX = useMirrored<number | null>(null)
  const autoFollowEnabled = useMirrored(true)

  const dragState = useMirrored<DragState>(null)
  const subtitleRange = useMirrored<SubtitleRangeState>({ active: false, startSec: 0, endSec: 0 })

  const subtitleModalOpen = useMirrored(false)
  const subtitleDraft = useMirrored('')
  const subtitleFontSizeDraft = useMirrored(40)
  const editingSubtitleId = useMirrored<string | null>(null)
  const pendingAddAudioTrack = useMirrored<'voice' | 'music' | null>(null)
  const pendingVoiceVideoClipId = useMirrored<string | null>(null)
  const replacingVoiceId = useMirrored<string | null>(null)

  return {
    isVideoModalOpen,
    editingVideoClipIndex,
    isDubbingModalOpen,
    editingDubbingClipIndex,
    isMusicModalOpen,
    videoVolumePreset,
    volumeHoverClipId,
    volumeDrag,
    nativeActiveSlot,
    nativePreviewFrameReady,
    playing,
    muted,
    exporting,
    exportProgressPercent,
    timelineLoading,
    segmentsDownloading,
    exportNeedReaudit,
    exportPendingVideoUrl,
    exportFinalVideoUrl,
    serverTimelineBaseline,
    timelineResolution,
    trackLabelWidth,
    videoClips,
    voiceItems,
    subtitleItems,
    musicItems,
    selectedClip,
    snapIndicatorPx,
    snapEnabled,
    snapSourceMode,
    snapDistancePx,
    swappingClipIds,
    timelineStripWidthPx,
    currentTime,
    scrubbing,
    scrubClientX,
    autoFollowEnabled,
    dragState,
    subtitleRange,
    subtitleModalOpen,
    subtitleDraft,
    subtitleFontSizeDraft,
    editingSubtitleId,
    pendingAddAudioTrack,
    pendingVoiceVideoClipId,
    replacingVoiceId
  }
}

function createVideoPreviewRuntime(): VideoPreviewRuntime {
  const runtime: VideoPreviewRuntime = {
      exportProgressScopeKey: '',
      timelineSaver: createDebouncedTimelineSaver(2500),
      autoFollowResumeTimer: null,
      suppressScrollFollowPause: false,
      programmaticScrollLockUntil: 0,
      previewPlayRaf: null,
      previewPlayStartedAt: 0,
      previewPlayStartSec: 0,
      previewPlayEndSec: 0,
      previewAudioEls: new Map(),
      previewVideoPreloads: new Map(),
      slotSrcA: '',
      slotSrcB: '',
      activeNativeClipId: '',
      standbyPreparedClipId: '',
      standbyPrepareToken: 0,
      avCanvas: null,
      avUnsubTime: null,
      avUnsubPlaying: null,
      avUnsubPaused: null,
      mediaBlobCache: new Map(),
      rebuildTimer: null,
      currentPreviewToken: 0,
      exportFollowAbort: null,
      exportFollowInFlight: null,
      exportFollowGeneration: 0,
      timelineResizeObserver: null,
      lastHydratedScopeKey: '',
      setTimelineResizeObserver(observer) {
        runtime.timelineResizeObserver?.disconnect()
        runtime.timelineResizeObserver = observer
      },
      setActiveNativeClipId(clipId) {
        runtime.activeNativeClipId = clipId
      },
      clearNativeSlots(resetPrepareToken = false) {
        runtime.slotSrcA = ''
        runtime.slotSrcB = ''
        runtime.activeNativeClipId = ''
        runtime.standbyPreparedClipId = ''
        if (resetPrepareToken) runtime.standbyPrepareToken = 0
      },
      clearExportProgressScope() {
        runtime.exportProgressScopeKey = ''
      },
      dispose() {
        runtime.timelineSaver.cancel()
        runtime.setTimelineResizeObserver(null)
        if (runtime.autoFollowResumeTimer) window.clearTimeout(runtime.autoFollowResumeTimer)
        runtime.previewAudioEls.clear()
        runtime.previewVideoPreloads.forEach((element) => {
          try {
            element.removeAttribute('src')
            element.load()
          } catch {}
        })
        runtime.previewVideoPreloads.clear()
        runtime.clearNativeSlots(true)
        try {
          runtime.avCanvas?.destroy?.()
        } catch {}
        runtime.avCanvas = null
        runtime.avUnsubTime?.()
        runtime.avUnsubPlaying?.()
        runtime.avUnsubPaused?.()
        runtime.mediaBlobCache.clear()
        if (runtime.rebuildTimer) window.clearTimeout(runtime.rebuildTimer)
      }
    }
  return runtime
}

/** 集中创建全部非响应式运行时（对应原 setup 闭包内 let / Map / 实例） */
export function useVideoPreviewRuntime(): VideoPreviewRuntime {
  const ref = useRef<VideoPreviewRuntime | null>(null)
  if (!ref.current) ref.current = createVideoPreviewRuntime()
  return ref.current
}

/** 集中创建全部 DOM refs */
export function useVideoPreviewDomRefs(): VideoPreviewDomRefs {
  const timelineWrapRef = useRef<HTMLDivElement | null>(null)
  const subtitleStripRef = useRef<HTMLDivElement | null>(null)
  const canvasHostRef = useRef<HTMLDivElement | null>(null)
  const nativePreviewVideoARef = useRef<HTMLVideoElement | null>(null)
  const nativePreviewVideoBRef = useRef<HTMLVideoElement | null>(null)
  const audioInputRef = useRef<HTMLInputElement | null>(null)
  return {
    timelineWrapRef,
    subtitleStripRef,
    canvasHostRef,
    nativePreviewVideoARef,
    nativePreviewVideoBRef,
    audioInputRef
  }
}
