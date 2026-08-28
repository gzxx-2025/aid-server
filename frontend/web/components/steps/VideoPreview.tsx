'use client'

/**
 * 成片预览 / 导出步骤主面板（原 aid-pc/components/steps/VideoPreview.vue）。
 *
 * ## Props 契约（与原 defineProps 一致）
 * - `storyboardVideoPanels: StoryboardVideoPanel[]` —— 第五步分镜视频面板列表
 * - `dubbingPanels: DubbingPanel[]` —— 第六步配音面板列表（时间轴分镜 id 以其 id 为准）
 * - `bgm?: string` —— 全局背景音乐 URL（作为导出 globalBgm 与初始音乐轨）
 *
 * ## 原调用点
 * `pages/create/preview.vue`（创作壳步骤页，后续批次接线）：
 *   `<StepsVideoPreview :storyboard-video-panels="..." :dubbing-panels="..." :bgm="..." />`
 * 面板挂载时通过 CreateFlowShellContext.registerPreviewExportBridge 向壳层注册
 * 「导出完整视频 / 下载分段素材」能力，卸载时注册 null；壳层缺失（未接线）时自动降级为不注册。
 */

import { Suspense, useContext, useEffect, useRef } from 'react'
import { Button, Input, Modal, Slider, message } from 'antd'
import {
  SyncOutlined,
  EyeOutlined,
  SoundOutlined,
  AudioMutedOutlined,
  VideoCameraOutlined,
  LoadingOutlined
} from '@ant-design/icons'
import type { StoryboardPanel } from '~/types'
import { useCreationStore } from '~/stores/creation'
import { useRouteLike } from '~/hooks/useRouteLike'
import { usePreviewPlayerReadyOverlay } from '~/hooks/usePreviewPlayerReadyOverlay'
import { createFlowShellContext } from '~/utils/createFlowInjection'
import { EPISODE_TIMELINE_REBUILD_EVENT } from '~/utils/episodeTimelineRebuildSignal'
import { resolvePreviewPlayerPosterUrl } from '~/utils/previewPlayerPoster'
import { emptyImageIconUrl as emptyImageIconRaw } from '~/utils/emptyImageIcon'
import { assetUrl } from '~/utils/assetUrl'
import { AsyncModalLoading } from '~/components/common/AsyncModalLoading'
import { EditStoryboardDubbingModalLazy, EditStoryboardVideoModalLazy, preloadPreviewEditorModalsWhenIdle } from './heavyEditorModalLoaders'
import { MusicPickerModal } from './MusicPickerModal'
import {
  useVideoPreviewDomRefs,
  useVideoPreviewRuntime,
  useVideoPreviewState
} from './video-preview/useVideoPreviewState'
import type { VideoPreviewCtx, VideoPreviewProps } from './video-preview/types'
import {
  formatTime,
  getRulerMarksWithLayout,
  getRulerWidthPx,
  getTotalDuration,
  getVideoClipAtTime,
  hasPlayableVideoAtTime,
  secToPlayheadPx
} from './video-preview/layoutOps'
import {
  ensureActiveNativeVideoSrc,
  getActiveSubtitleText,
  getNativePreviewVideoUrl,
  getShowNativePreviewVideo,
  getShowNoVideoOverlay,
  onNativePreviewMediaReady,
  onPreviewPlayerAreaClick,
  onTimelineUserScroll,
  preloadVideoUrl,
  refreshNativePreviewFrameReady,
  stopAllPreviewAudios,
  stopPreviewPlaybackLoop,
  syncNativePreviewVideoTime,
  syncPreviewAudios,
  togglePlay,
  toggleMute,
  updateTimelineStripWidth
} from './video-preview/playbackOps'
import { ensureCanvas, resetPreviewTimelineState } from './video-preview/canvasOps'
import {
  hydrateTimelineForCurrentProject,
  normalizeEmptyClipDurations,
  onEpisodeTimelineRebuildRequested,
  syncFromPreviousSteps,
  syncMusicTimelineDurations
} from './video-preview/timelineOps'
import {
  getActiveMusicItem,
  getDubbingPanelsForModal,
  getScriptPanelsForModal,
  getVideoScenes,
  handleDubbingPanelsUpdate,
  handleStoryboardVideoPanelsUpdate,
  handleVideoUpdate,
  onMusicPickerConfirm
} from './video-preview/modalOps'
import {
  autoScrollTimelineWhileScrub,
  onAudioFileSelected,
  onPointerMove,
  onPointerUp,
  onPreviewKeyboard,
  onTimelinePointerDown,
  saveSubtitle,
  setCurrentTimeFromClientX,
  tickVideoClipDragAutoScroll
} from './video-preview/interactionOps'
import {
  handleDownloadSegments,
  handleExport,
  pauseEpisodeExportFollow,
  refreshExportStatusFromServer,
  resumeEpisodeExportFollowIfNeeded
} from './video-preview/exportOps'
import { TimelineTracks } from './video-preview/TimelineTracks'
import './video-preview/video-preview.css'
import './video-preview/video-preview-timeline.css'
import './video-preview/video-preview-global.css'

const emptyImageIconUrl = assetUrl(emptyImageIconRaw)

export function VideoPreview(props: VideoPreviewProps) {
  const { storyboardVideoPanels, dubbingPanels, bgm } = props

  const route = useRouteLike()
  const shell = useContext(createFlowShellContext)

  const S = useVideoPreviewState()
  const runtime = useVideoPreviewRuntime()
  const dom = useVideoPreviewDomRefs()
  useEffect(() => preloadPreviewEditorModalsWhenIdle(), [])

  // 事件回调 / 异步流程内读最新 props / route / 壳层
  const propsRef = useRef(props)
  propsRef.current = props
  const routeRef = useRef(route)
  routeRef.current = route
  const shellRef = useRef(shell)
  shellRef.current = shell
  const registerPreviewExportBridge = shell?.registerPreviewExportBridge

  const ctxRef = useRef<VideoPreviewCtx | null>(null)
  if (!ctxRef.current) {
    ctxRef.current = {
      state: S,
      runtime,
      dom,
      getProps: () => propsRef.current,
      getRoute: () => routeRef.current,
      getShell: () => shellRef.current
    }
  }
  const ctx = ctxRef.current
  ctx.state = S

  // 渲染期订阅 store（selector 防全量重渲）
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)
  const scriptPanelsRaw = useCreationStore((s) => s.formData.storyboardScript?.panels)
  // 仅为触发配音弹窗 props 重算而订阅
  useCreationStore((s) => s.formData.dubbing?.panels)

  const projectScopeKey = `${currentProjectId ?? ''}:${currentEpisodeId ?? ''}`
  const scriptPanels = (scriptPanelsRaw || []) as StoryboardPanel[]

  // --- 派生值（原 computed，渲染期直接计算） ---
  const rulerWidthPx = getRulerWidthPx(ctx)
  const rulerMarksWithLayout = getRulerMarksWithLayout(ctx)
  const playheadLeftPx = S.trackLabelWidth.value + secToPlayheadPx(ctx, S.currentTime.value)
  const showNativePreviewVideo = getShowNativePreviewVideo(ctx)
  const showNoVideoOverlay = getShowNoVideoOverlay(ctx)
  const activeSubtitleText = getActiveSubtitleText(ctx)
  const nativePreviewVideoUrl = getNativePreviewVideoUrl(ctx)
  const videoClips = S.videoClips.value
  const activeMusicItem = getActiveMusicItem(ctx)

  const previewReadyPosterUrl = (() => {
    const clip = getVideoClipAtTime(ctx, S.currentTime.value) || videoClips[0] || null
    if (!clip) return ''
    const clipIndex = videoClips.findIndex((c) => c.id === clip.id)
    return resolvePreviewPlayerPosterUrl({
      storyboardId: clip.storyboardId,
      clipIndex: clipIndex >= 0 ? clipIndex : 0,
      scriptPanels
    })
  })()

  const {
    overlayMounted: previewReadyOverlayMounted,
    overlayOpaque: previewReadyOverlayOpaque,
    hintText: previewReadyHintText
  } = usePreviewPlayerReadyOverlay({
    scopeKey: projectScopeKey,
    timelineLoading: S.timelineLoading.value,
    videoClipCount: videoClips.length,
    hasPlayableAtCurrentTime: hasPlayableVideoAtTime(ctx, S.currentTime.value),
    frameReady: S.nativePreviewFrameReady.value,
    posterUrl: previewReadyPosterUrl
  })

  const videoScenes = getVideoScenes(ctx)
  const dubbingPanelsForModal = getDubbingPanelsForModal(ctx)
  const scriptPanelsForModal = getScriptPanelsForModal()

  // --- 壳层导出桥接：挂载注册 / exporting 变化重注册 / 卸载注销 ---
  useEffect(() => {
    registerPreviewExportBridge?.({
      exportFullVideo: () => handleExport(ctx),
      exportSegments: () => handleDownloadSegments(ctx),
      exporting: S.exporting.value,
      segmentsDownloading: S.segmentsDownloading.value
    })
  }, [registerPreviewExportBridge, S.exporting.value, S.segmentsDownloading.value])

  // --- onMounted / onUnmounted ---
  useEffect(() => {
    const handlePointerMove = (e: PointerEvent) => onPointerMove(ctx, e)
    const handlePointerUp = () => onPointerUp(ctx)
    const handleResize = () => updateTimelineStripWidth(ctx)
    const handleKeydown = (e: KeyboardEvent) => onPreviewKeyboard(ctx, e)
    const handleRebuild = () => onEpisodeTimelineRebuildRequested(ctx)
    window.addEventListener('pointermove', handlePointerMove)
    window.addEventListener('pointerup', handlePointerUp)
    window.addEventListener('resize', handleResize)
    window.addEventListener('keydown', handleKeydown)
    window.addEventListener(EPISODE_TIMELINE_REBUILD_EVENT, handleRebuild)
    void ensureCanvas(ctx)
    void refreshExportStatusFromServer(ctx)
    void resumeEpisodeExportFollowIfNeeded(ctx)
    normalizeEmptyClipDurations(ctx)
    const setupTimer = window.setTimeout(() => {
      updateTimelineStripWidth(ctx)
      if (typeof ResizeObserver !== 'undefined' && dom.timelineWrapRef.current) {
        const observer = new ResizeObserver(() => updateTimelineStripWidth(ctx))
        runtime.setTimelineResizeObserver(observer)
        observer.observe(dom.timelineWrapRef.current)
      }
    }, 0)
    // 拖动进度 / 分镜换序：停在边缘时持续自动滚动
    let scrubRaf = 0
    const loop = () => {
      if (S.scrubbing.get() && S.scrubClientX.get() !== null) {
        autoScrollTimelineWhileScrub(ctx, S.scrubClientX.get()!)
        setCurrentTimeFromClientX(ctx, S.scrubClientX.get()!)
      }
      tickVideoClipDragAutoScroll(ctx)
      scrubRaf = requestAnimationFrame(loop)
    }
    scrubRaf = requestAnimationFrame(loop)

    return () => {
      cancelAnimationFrame(scrubRaf)
      window.clearTimeout(setupTimer)
      pauseEpisodeExportFollow(ctx)
      shellRef.current?.registerPreviewExportBridge(null)
      window.removeEventListener('pointermove', handlePointerMove)
      window.removeEventListener('pointerup', handlePointerUp)
      window.removeEventListener('resize', handleResize)
      window.removeEventListener('keydown', handleKeydown)
      window.removeEventListener(EPISODE_TIMELINE_REBUILD_EVENT, handleRebuild)
      stopPreviewPlaybackLoop(ctx)
      stopAllPreviewAudios(ctx)
      runtime.dispose()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 原 watch(currentTime)
  useEffect(() => {
    if (!getTotalDuration(ctx)) return
    syncNativePreviewVideoTime(ctx)
    if (S.playing.get()) syncPreviewAudios(ctx)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [S.currentTime.value])

  // 原 watch(showNativePreviewVideo)
  const prevShowNativeRef = useRef<boolean | null>(null)
  useEffect(() => {
    if (prevShowNativeRef.current === null) {
      prevShowNativeRef.current = showNativePreviewVideo
      return
    }
    if (prevShowNativeRef.current === showNativePreviewVideo) return
    prevShowNativeRef.current = showNativePreviewVideo
    if (showNativePreviewVideo) {
      S.nativePreviewFrameReady.set(false)
      setTimeout(() => {
        const clip = getVideoClipAtTime(ctx, S.currentTime.get())
        if (clip?.url) {
          const offset = Math.max(0, S.currentTime.get() - clip.start + (clip.trimStart || 0))
          void ensureActiveNativeVideoSrc(ctx, clip.url, offset).then(() => {
            runtime.setActiveNativeClipId(clip.id)
            refreshNativePreviewFrameReady(ctx)
            syncNativePreviewVideoTime(ctx)
          })
        } else {
          syncNativePreviewVideoTime(ctx)
        }
      }, 0)
    } else {
      runtime.clearNativeSlots()
      S.nativePreviewFrameReady.set(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [showNativePreviewVideo])

  // 原 watch(nativePreviewVideoUrl, (url, prevUrl))
  const prevNativeUrlRef = useRef('')
  useEffect(() => {
    const prevUrl = prevNativeUrlRef.current
    prevNativeUrlRef.current = nativePreviewVideoUrl
    const url = nativePreviewVideoUrl
    if (!url) return
    preloadVideoUrl(ctx, url)
    if (getShowNativePreviewVideo(ctx)) {
      setTimeout(() => {
        const clip = getVideoClipAtTime(ctx, S.currentTime.get())
        if (!clip || clip.url !== url) return
        if (runtime.activeNativeClipId === clip.id) {
          if (prevUrl !== url) S.nativePreviewFrameReady.set(false)
          void ensureActiveNativeVideoSrc(ctx, url)
        } else {
          syncNativePreviewVideoTime(ctx)
        }
      }, 0)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nativePreviewVideoUrl])

  // 原 watch(projectScopeKey)：切作品/集重置并重新装载
  const prevScopeKeyRef = useRef(projectScopeKey)
  useEffect(() => {
    if (prevScopeKeyRef.current === projectScopeKey) return
    prevScopeKeyRef.current = projectScopeKey
    const wasExporting = S.exporting.get()
    pauseEpisodeExportFollow(ctx)
    S.exporting.set(false)
    S.exportProgressPercent.set(0)
    runtime.clearExportProgressScope()
    if (wasExporting) message.destroy('export')
    resetPreviewTimelineState(ctx)
    setTimeout(() => {
      hydrateTimelineForCurrentProject(ctx)
      void refreshExportStatusFromServer(ctx)
      void resumeEpisodeExportFollowIfNeeded(ctx)
    }, 0)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectScopeKey])

  // 原 watch([scopeKey, panels 数量, bgm], { immediate: true })：装载时间轴
  const dubbingPanelCount = dubbingPanels?.length ?? 0
  const storyboardVideoPanelCount = storyboardVideoPanels?.length ?? 0
  const backgroundMusicUrl = bgm ?? ''
  useEffect(() => {
    hydrateTimelineForCurrentProject(ctx)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectScopeKey, dubbingPanelCount, storyboardVideoPanelCount, backgroundMusicUrl])

  // 原 watch(videoClips 指纹)：分镜时长变化联动音乐轨
  const clipsFingerprint = videoClips.map((clip) => `${clip.id}:${clip.duration}`).join('|')
  const prevClipsFingerprintRef = useRef(clipsFingerprint)
  useEffect(() => {
    if (prevClipsFingerprintRef.current === clipsFingerprint) return
    prevClipsFingerprintRef.current = clipsFingerprint
    syncMusicTimelineDurations(ctx)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clipsFingerprint])

  return (
    <div className="video-preview-step">
      <div className="preview-toolbar">
        <Button
          size="small"
          loading={S.timelineLoading.value}
          icon={<SyncOutlined />}
          onClick={() => syncFromPreviousSteps(ctx)}
        >
          从前面步骤同步到时间轴
        </Button>

        <input
          ref={dom.audioInputRef}
          className="hidden-file-input"
          type="file"
          accept="audio/*"
          onChange={(e) => void onAudioFileSelected(ctx, e)}
        />
      </div>

      <div className="preview-simple-wrap">
        <div className="preview-player-wrap">
          <div className="preview-player-area" onClick={() => onPreviewPlayerAreaClick(ctx)}>
            <div
              ref={dom.canvasHostRef}
              className={`preview-canvas-host${showNativePreviewVideo ? ' preview-canvas-host-behind' : ''}`}
            />
            {showNativePreviewVideo && (
              <>
                <video
                  ref={dom.nativePreviewVideoARef}
                  className={`preview-native-video${S.nativeActiveSlot.value === 'A' ? ' is-active' : ''}`}
                  playsInline
                  preload="auto"
                  onLoadedMetadata={() => syncNativePreviewVideoTime(ctx)}
                  onLoadedData={() => onNativePreviewMediaReady(ctx)}
                  onCanPlay={() => onNativePreviewMediaReady(ctx)}
                />
                <video
                  ref={dom.nativePreviewVideoBRef}
                  className={`preview-native-video${S.nativeActiveSlot.value === 'B' ? ' is-active' : ''}`}
                  playsInline
                  preload="auto"
                  onLoadedMetadata={() => syncNativePreviewVideoTime(ctx)}
                  onLoadedData={() => onNativePreviewMediaReady(ctx)}
                  onCanPlay={() => onNativePreviewMediaReady(ctx)}
                />
              </>
            )}
            {previewReadyOverlayMounted && (
              <div
                className={`preview-ready-overlay${previewReadyOverlayOpaque ? ' is-opaque' : ''}`}
                aria-live="polite"
                aria-busy="true"
              >
                {previewReadyPosterUrl && (
                  <img src={previewReadyPosterUrl} alt="" className="preview-ready-overlay__poster" />
                )}
                <div className="preview-ready-overlay__scrim" />
                <div className="preview-ready-overlay__hint">
                  <LoadingOutlined spin className="preview-ready-overlay__spin" />
                  <span>{previewReadyHintText}</span>
                </div>
              </div>
            )}
            {activeSubtitleText && <div className="preview-subtitle-overlay">{activeSubtitleText}</div>}
            {showNoVideoOverlay && (
              <div className="preview-no-video-overlay">
                <VideoCameraOutlined className="preview-no-video-icon" />
                <p>暂无视频无法播放</p>
              </div>
            )}
            {!videoClips.length && !S.timelineLoading.value ? (
              <div className="preview-placeholder">
                <EyeOutlined className="placeholder-icon" />
                <p>请先同步前面步骤</p>
              </div>
            ) : (
              <div className="preview-overlay-controls">
                {!S.playing.value && (
                  <button
                    type="button"
                    className="dubbing-video-play-btn"
                    aria-label="播放"
                    onClick={(e) => {
                      e.stopPropagation()
                      void togglePlay(ctx)
                    }}
                  />
                )}
                <button
                  type="button"
                  className="volume-btn"
                  aria-label="音量"
                  onClick={(e) => {
                    e.stopPropagation()
                    toggleMute(ctx)
                  }}
                >
                  {!S.muted.value ? <SoundOutlined /> : <AudioMutedOutlined />}
                </button>
              </div>
            )}
          </div>
        </div>

        <div
          className="timeline-wrap"
          ref={dom.timelineWrapRef}
          onPointerDown={(e) => onTimelinePointerDown(ctx, e)}
          onWheel={() => onTimelineUserScroll(ctx)}
        >
          <div className="timeline-inner" style={{ width: `${S.trackLabelWidth.value + rulerWidthPx}px` }}>
            <div className="timeline-grid-overlay" style={{ width: `${rulerWidthPx}px` }} aria-hidden="true">
              {rulerMarksWithLayout.map((mark) => (
                <div
                  key={`grid-${mark.sec}`}
                  className={`ruler-grid-line ruler-grid-line-${mark.type}`}
                  style={{ left: `${mark.leftPx}px` }}
                />
              ))}
            </div>
            <div className="timeline-ruler-gutter" aria-hidden="true" />
            <div className="timeline-ruler" style={{ width: `${rulerWidthPx}px` }}>
              {rulerMarksWithLayout.map((mark) => (
                <div
                  key={`tick-${mark.sec}`}
                  className={`ruler-tick ruler-tick-${mark.type}`}
                  style={{ left: `${mark.leftPx}px` }}
                >
                  {mark.type === 'major' && <span className="ruler-label">{formatTime(mark.sec)}</span>}
                </div>
              ))}
            </div>
            <div className="timeline-playhead" style={{ left: `${playheadLeftPx}px` }}>
              <span className="playhead-head" />
            </div>
            {S.snapIndicatorPx.value !== null && (
              <div
                className="snap-indicator"
                style={{ left: `${S.trackLabelWidth.value + S.snapIndicatorPx.value}px` }}
              />
            )}

            <div className="timeline-tracks" style={{ width: `${rulerWidthPx}px` }}>
              <TimelineTracks ctx={ctx} emptyImageIconUrl={emptyImageIconUrl} />
            </div>
          </div>
        </div>
      </div>

      <Modal
        open={S.subtitleModalOpen.value}
        title="编辑字幕"
        okText="确定"
        cancelText="取消"
        wrapClassName="create-flow-modal"
        onOk={() => saveSubtitle(ctx)}
        onCancel={() => S.subtitleModalOpen.set(false)}
      >
        <div className="subtitle-edit-form">
          <div className="subtitle-edit-form-row">
            <div className="subtitle-edit-form-label">字幕内容</div>
            <Input.TextArea
              value={S.subtitleDraft.value}
              onChange={(e) => S.subtitleDraft.set(e.target.value)}
              rows={4}
              placeholder="请输入字幕内容"
            />
          </div>
          <div className="subtitle-edit-form-row">
            <div className="subtitle-edit-form-label">字体大小</div>
            <div className="subtitle-size-row">
              <Slider
                value={S.subtitleFontSizeDraft.value}
                onChange={(v) => S.subtitleFontSizeDraft.set(Number(v))}
                min={20}
                max={72}
                step={1}
              />
              <span className="subtitle-size-value">{S.subtitleFontSizeDraft.value}px</span>
            </div>
          </div>
        </div>
      </Modal>

      {S.isVideoModalOpen.value && S.editingVideoClipIndex.value >= 0 && (
        <Suspense fallback={<AsyncModalLoading />}>
          <EditStoryboardVideoModalLazy
          key={`preview-video-${storyboardVideoPanels[S.editingVideoClipIndex.value]?.id ?? S.editingVideoClipIndex.value}`}
          open={S.isVideoModalOpen.value}
          onOpenChange={(v) => S.isVideoModalOpen.set(v)}
          sceneIndex={S.editingVideoClipIndex.value}
          editorScopeKey={`preview-video-${storyboardVideoPanels[S.editingVideoClipIndex.value]?.id ?? S.editingVideoClipIndex.value}`}
          scenes={videoScenes}
          onUpdate={(sceneIndex, data) => handleVideoUpdate(ctx, sceneIndex, data)}
          />
        </Suspense>
      )}

      {S.isDubbingModalOpen.value && S.editingDubbingClipIndex.value >= 0 && (
        <Suspense fallback={<AsyncModalLoading />}>
          <EditStoryboardDubbingModalLazy
          key={`preview-dubbing-${dubbingPanelsForModal[S.editingDubbingClipIndex.value]?.id ?? S.editingDubbingClipIndex.value}`}
          open={S.isDubbingModalOpen.value}
          onOpenChange={(v) => S.isDubbingModalOpen.set(v)}
          sceneIndex={S.editingDubbingClipIndex.value}
          editorScopeKey={`preview-dubbing-${dubbingPanelsForModal[S.editingDubbingClipIndex.value]?.id ?? S.editingDubbingClipIndex.value}`}
          dubbingPanels={dubbingPanelsForModal}
          storyboardVideoPanels={storyboardVideoPanels}
          storyboardScriptPanels={scriptPanelsForModal}
          onPanelsChange={(panels) => handleDubbingPanelsUpdate(ctx, panels)}
          onStoryboardVideoPanelsChange={(panels) => handleStoryboardVideoPanelsUpdate(ctx, panels)}
          />
        </Suspense>
      )}

      {S.isMusicModalOpen.value && (
        <MusicPickerModal
          open={S.isMusicModalOpen.value}
          onOpenChange={(v) => S.isMusicModalOpen.set(v)}
          initialMusicName={activeMusicItem?.name}
          initialVolume={activeMusicItem?.volume ?? 0.25}
          onConfirm={(payload) => onMusicPickerConfirm(ctx, payload)}
        />
      )}
    </div>
  )
}
