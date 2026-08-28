'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import type { ChangeEvent } from 'react'
import { Button, Modal, message } from 'antd'
import {
  LeftOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  RightOutlined
} from '@ant-design/icons'
import { useCreationStore } from '~/stores/creation'
import {
  collectOriginalStoryboardVideosFromPanels,
  type StoryboardVideoPick
} from '~/utils/collectProjectStoryboardVideos'
import {
  clampVideoFrameTime,
  captureVideoTimelineFrames,
  captureVideoUrlFrame,
  type CapturedVideoFrame
} from '~/utils/videoFrameCapture'
import { formatVideoFrameName } from '~/utils/videoFrameName'
import { uploadImageToOssWithToast } from '~/utils/ossUpload'
import { emptyImageIconUrl as emptyImageIconRaw } from '~/utils/emptyImageIcon'
import { assetUrl } from '~/utils/assetUrl'
import { useVideoPlaybackSpaceShortcut } from '~/composables/useVideoPlaybackSpaceShortcut'
import { ShimmerVideo } from '~/components/common/ShimmerVideo'
import './CaptureVideoFrameModal.css'

const emptyImageIconUrl = assetUrl(emptyImageIconRaw)

export interface CaptureVideoFrameModalProps {
  open: boolean
  projectId: number
  episodeId?: number | null
  zIndex?: number
  onOpenChange: (open: boolean) => void
  onCaptured: (payload: CapturedVideoFrame) => void
}

function formatVideoTime(value: number): string {
  const totalMilliseconds = Math.max(0, Math.floor((Number(value) || 0) * 1000))
  const totalSeconds = Math.floor(totalMilliseconds / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  const milliseconds = totalMilliseconds % 1000
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}.${String(milliseconds).padStart(3, '0')}`
}

export function CaptureVideoFrameModal({
  open,
  projectId,
  episodeId = null,
  zIndex = 1200,
  onOpenChange,
  onCaptured
}: CaptureVideoFrameModalProps) {
  const panels = useCreationStore((s) => s.formData.storyboardVideo.panels)
  const videos = useMemo<StoryboardVideoPick[]>(
    () => collectOriginalStoryboardVideosFromPanels(panels),
    [panels]
  )

  const [selectedVideoId, setSelectedVideoId] = useState('')
  const selectedVideo = videos.find((video) => video.id === selectedVideoId) || null

  const videoRef = useRef<HTMLVideoElement | null>(null)
  const videoStripRef = useRef<HTMLElement | null>(null)
  const [duration, setDuration] = useState(0)
  const [currentTime, setCurrentTime] = useState(0)
  const [videoReady, setVideoReadyState] = useState(false)
  const videoReadyRef = useRef(false)
  const [videoError, setVideoErrorState] = useState(false)
  const videoErrorRef = useRef(false)
  const [isPlaying, setIsPlaying] = useState(false)
  const [confirming, setConfirmingState] = useState(false)
  const confirmingRef = useRef(false)
  const [canScrollLeft, setCanScrollLeft] = useState(false)
  const [canScrollRight, setCanScrollRight] = useState(false)
  const [timelineFrames, setTimelineFrames] = useState<string[]>([])
  const [timelineLoading, setTimelineLoading] = useState(false)
  const timelineProgress = !duration
    ? 0
    : Math.min(100, Math.max(0, (currentTime / duration) * 100))
  const canTogglePlaybackWithSpace = open && videoReady && !videoError && !confirming
  useVideoPlaybackSpaceShortcut(canTogglePlaybackWithSpace, togglePlayback)
  const resizeObserverRef = useRef<ResizeObserver | null>(null)
  const timelineFrameGenerationRef = useRef(0)
  const playbackAnimationFrameRef = useRef<number | null>(null)
  const openRef = useRef(open)
  openRef.current = open

  function setVideoReady(value: boolean) {
    videoReadyRef.current = value
    setVideoReadyState(value)
  }

  function setVideoError(value: boolean) {
    videoErrorRef.current = value
    setVideoErrorState(value)
  }

  function setConfirming(value: boolean) {
    confirmingRef.current = value
    setConfirmingState(value)
  }

  function stopPlaybackProgressAnimation() {
    if (playbackAnimationFrameRef.current == null) return
    window.cancelAnimationFrame(playbackAnimationFrameRef.current)
    playbackAnimationFrameRef.current = null
  }

  function syncPlaybackProgressFrame() {
    const video = videoRef.current
    if (!video || video.paused || video.ended) {
      playbackAnimationFrameRef.current = null
      return
    }
    setCurrentTime(Math.max(0, video.currentTime || 0))
    playbackAnimationFrameRef.current = window.requestAnimationFrame(syncPlaybackProgressFrame)
  }

  function startPlaybackProgressAnimation() {
    stopPlaybackProgressAnimation()
    playbackAnimationFrameRef.current = window.requestAnimationFrame(syncPlaybackProgressFrame)
  }

  function syncSelectedVideo() {
    const currentExists = videos.some((video) => video.id === selectedVideoId)
    if (!currentExists) setSelectedVideoId(videos[0]?.id || '')
    requestAnimationFrame(updateScrollState)
  }
  function resetVideoState() {
    stopPlaybackProgressAnimation()
    setDuration(0)
    setCurrentTime(0)
    setVideoReady(false)
    setVideoError(false)
    setIsPlaying(false)
    timelineFrameGenerationRef.current += 1
    setTimelineFrames([])
    setTimelineLoading(false)
  }
  function selectVideo(id: string) {
    if (selectedVideoId === id) return
    videoRef.current?.pause()
    setSelectedVideoId(id)
  }
  function onLoadedMetadata() {
    const video = videoRef.current
    if (!video) return
    setDuration(Number.isFinite(video.duration) ? Math.max(0, video.duration) : 0)
    setCurrentTime(Math.max(0, video.currentTime || 0))
    setVideoReady(video.videoWidth > 0 && video.videoHeight > 0)
    setVideoError(false)
    void refreshTimelineFrames()
  }
  async function refreshTimelineFrames() {
    const sourceUrl = selectedVideo?.url || ''
    const generation = ++timelineFrameGenerationRef.current
    setTimelineFrames([])
    if (!sourceUrl || !videoReadyRef.current || videoErrorRef.current) {
      setTimelineLoading(false)
      return
    }

    setTimelineLoading(true)
    try {
      const frames = await captureVideoTimelineFrames(sourceUrl, {
        count: 10,
        shouldContinue: () =>
          generation === timelineFrameGenerationRef.current && openRef.current
      })
      if (generation === timelineFrameGenerationRef.current) setTimelineFrames(frames)
    } catch (error) {
      if (generation === timelineFrameGenerationRef.current) {
        setTimelineFrames([])
        console.error('[capture-video-frame] timeline frames failed', error)
      }
    } finally {
      if (generation === timelineFrameGenerationRef.current) setTimelineLoading(false)
    }
  }
  function onTimeUpdate() {
    const video = videoRef.current
    if (!video || video.seeking) return
    setCurrentTime(Math.max(0, video.currentTime || 0))
  }
  function onVideoPlay() {
    setIsPlaying(true)
    startPlaybackProgressAnimation()
  }
  function onVideoPause() {
    setIsPlaying(false)
    stopPlaybackProgressAnimation()
    onTimeUpdate()
  }
  function onVideoEnded() {
    setIsPlaying(false)
    stopPlaybackProgressAnimation()
    onTimeUpdate()
  }
  function onVideoError() {
    stopPlaybackProgressAnimation()
    setVideoReady(false)
    setVideoError(true)
    setIsPlaying(false)
  }
  async function togglePlayback() {
    const video = videoRef.current
    if (!video || !videoReadyRef.current || videoErrorRef.current || confirmingRef.current) return
    if (!video.paused) {
      video.pause()
      return
    }
    if (video.ended && duration > 0) video.currentTime = 0
    try {
      await video.play()
    } catch {
      message.error('视频播放失败')
    }
  }
  function setVideoTime(value: number) {
    const video = videoRef.current
    if (!video || !videoReadyRef.current) return
    const safeValue = Math.min(Math.max(0, value), duration || 0)
    video.pause()
    setIsPlaying(false)
    setCurrentTime(safeValue)
    video.currentTime = safeValue
  }
  function onScrubInput(event: ChangeEvent<HTMLInputElement>) {
    setVideoTime(Number(event.target.value))
  }

  function seekToBoundary(boundary: 'start' | 'end') {
    if (boundary === 'start') {
      setVideoTime(0)
      return
    }
    setVideoTime(clampVideoFrameTime(duration, duration))
  }

  async function confirmCapture() {
    const video = videoRef.current
    const source = selectedVideo
    if (confirming || !video || !source || !videoReady || videoError) return

    setConfirming(true)
    try {
      video.pause()
      const capturedAt = clampVideoFrameTime(currentTime, duration)
      const capturedAtMs = Math.max(0, Math.floor(capturedAt * 1000))
      const name = formatVideoFrameName(source.label, capturedAtMs, new Date())
      const file = await captureVideoUrlFrame(source.url, capturedAt, name)
      const url = await uploadImageToOssWithToast(file)
      if (!url) return
      onCaptured({
        url,
        name,
        sourceVideoId: source.id,
        sourceLabel: source.label,
        capturedAtMs
      })
      onOpenChange(false)
    } catch (error) {
      console.error('[capture-video-frame] capture failed', error)
      message.error('截帧失败，请稍后重试')
    } finally {
      setConfirming(false)
    }
  }

  function closeModal() {
    if (confirmingRef.current) return
    onOpenChange(false)
  }

  function cleanupVideo() {
    const video = videoRef.current
    if (video) {
      video.pause()
      video.removeAttribute('src')
      video.load()
    }
    setSelectedVideoId('')
    resetVideoState()
  }

  function updateScrollState() {
    const element = videoStripRef.current
    if (!element) {
      setCanScrollLeft(false)
      setCanScrollRight(false)
      return
    }
    const maxScrollLeft = Math.max(0, element.scrollWidth - element.clientWidth)
    setCanScrollLeft(element.scrollLeft > 2)
    setCanScrollRight(maxScrollLeft - element.scrollLeft > 2)
  }

  function scrollVideoStrip(direction: -1 | 1) {
    const element = videoStripRef.current
    if (!element) return
    element.scrollBy({
      left: direction * Math.max(180, element.clientWidth * 0.8),
      behavior: 'smooth'
    })
  }

  /** 原 watch(videoStripRef)：元素变化时切换 ResizeObserver 观察对象并刷新箭头态 */
  function setVideoStripElement(element: HTMLDivElement | null) {
    const previous = videoStripRef.current
    if (previous === element) return
    if (previous) resizeObserverRef.current?.unobserve(previous)
    if (element) resizeObserverRef.current?.observe(element)
    videoStripRef.current = element
    requestAnimationFrame(updateScrollState)
  }

  useEffect(() => {
    if (open) {
      syncSelectedVideo()
      return
    }
    cleanupVideo()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const scopeMountedRef = useRef(false)
  useEffect(() => {
    // 原 watch([projectId, episodeId]) 非 immediate：跳过首次执行
    if (!scopeMountedRef.current) {
      scopeMountedRef.current = true
      return
    }
    if (openRef.current) {
      cleanupVideo()
      syncSelectedVideo()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, episodeId])

  const videosMountedRef = useRef(false)
  useEffect(() => {
    // 原 watch(videos) 非 immediate：跳过首次执行
    if (!videosMountedRef.current) {
      videosMountedRef.current = true
      return
    }
    if (openRef.current) syncSelectedVideo()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [videos])

  const selectedIdMountedRef = useRef(false)
  useEffect(() => {
    // 原 watch(selectedVideoId) 非 immediate：跳过首次执行
    if (!selectedIdMountedRef.current) {
      selectedIdMountedRef.current = true
      return
    }
    resetVideoState()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedVideoId])

  useEffect(() => {
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserverRef.current = new ResizeObserver(updateScrollState)
      if (videoStripRef.current) resizeObserverRef.current.observe(videoStripRef.current)
    }
    window.addEventListener('resize', updateScrollState)
    return () => {
      cleanupVideo()
      resizeObserverRef.current?.disconnect()
      window.removeEventListener('resize', updateScrollState)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <Modal
      open={open}
      width={1050}
      footer={null}
      zIndex={zIndex}
      closable={!confirming}
      mask={{ closable: !confirming }}
      keyboard={!confirming}
      title="截取视频帧"
      className="capture-video-frame-modal"
      wrapClassName="create-flow-modal capture-video-frame-modal-wrap"
      onCancel={closeModal}
    >
      <div className="cvfm-content">
        {videos.length > 0 ? (
          <>
            <section className="cvfm-picker" aria-label="选择分镜原视频">
              {canScrollLeft && (
                <button
                  type="button"
                  className="cvfm-scroll-btn cvfm-scroll-btn--left"
                  aria-label="向左查看更多视频"
                  onClick={() => scrollVideoStrip(-1)}
                >
                  <LeftOutlined />
                </button>
              )}
              <div ref={setVideoStripElement} className="cvfm-video-strip" onScroll={updateScrollState}>
                {videos.map((video) => (
                  <button
                    key={video.id}
                    type="button"
                    className={`cvfm-video-card${selectedVideoId === video.id ? ' is-active' : ''}`}
                    title={video.label}
                    onClick={() => selectVideo(video.id)}
                  >
                    <ShimmerVideo
                      src={video.url}
                      videoClass="cvfm-video-card__media"
                      wrapperClass="cvfm-video-card__shimmer"
                      objectFit="cover"
                      preload="metadata"
                      revealDirection="fade"
                      minShimmerMs={220}
                    />
                    <span className="cvfm-video-card__label">{video.label}</span>
                  </button>
                ))}
              </div>
              {canScrollRight && (
                <button
                  type="button"
                  className="cvfm-scroll-btn cvfm-scroll-btn--right"
                  aria-label="向右查看更多视频"
                  onClick={() => scrollVideoStrip(1)}
                >
                  <RightOutlined />
                </button>
              )}
            </section>

            <section className="cvfm-preview">
              {selectedVideo && (
                <video
                  key={`${selectedVideo.id}-${selectedVideo.url}`}
                  ref={videoRef}
                  className="cvfm-preview__video"
                  src={selectedVideo.url}
                  poster={selectedVideo.poster}
                  playsInline
                  preload="auto"
                  aria-label="视频预览，点击或按空格播放或暂停"
                  onClick={togglePlayback}
                  onLoadedMetadata={onLoadedMetadata}
                  onTimeUpdate={onTimeUpdate}
                  onPlay={onVideoPlay}
                  onPause={onVideoPause}
                  onEnded={onVideoEnded}
                  onError={onVideoError}
                />
              )}
              {selectedVideo && videoReady && !videoError && !isPlaying && (
                <button
                  type="button"
                  className="dubbing-video-play-btn dubbing-video-play-btn--card"
                  aria-label="播放视频"
                  onClick={togglePlayback}
                />
              )}
              {videoError && (
                <div className="cvfm-preview__error">
                  <img src={emptyImageIconUrl} alt="" className="empty-image-icon empty-image-icon--md" />
                  <span>视频加载失败，请切换其它视频</span>
                </div>
              )}
            </section>

            <section className="cvfm-timeline" aria-label="视频截帧时间轴">
              <div className="cvfm-timeline__main">
                <button
                  type="button"
                  className="cvfm-play-btn"
                  disabled={!videoReady || videoError}
                  aria-label={isPlaying ? '暂停视频' : '播放视频'}
                  onClick={togglePlayback}
                >
                  {isPlaying ? <PauseCircleOutlined /> : <PlayCircleOutlined />}
                </button>
                <div
                  className={`cvfm-filmstrip${!videoReady || videoError ? ' is-disabled' : ''}${timelineLoading ? ' is-loading' : ''}`}
                >
                  {timelineFrames.map((frame, index) => (
                    <span
                      key={`${selectedVideoId}-${index}`}
                      className="cvfm-filmstrip__frame"
                      style={{ backgroundImage: `url(${frame})` }}
                    />
                  ))}
                  {timelineFrames.length === 0 && (
                    <span
                      className={`cvfm-filmstrip__placeholder${timelineLoading ? ' is-loading' : ''}`}
                    />
                  )}
                  <span className="cvfm-filmstrip__playhead" style={{ left: `${timelineProgress}%` }} />
                  <input
                    className="cvfm-filmstrip__input"
                    type="range"
                    min={0}
                    max={duration || 0}
                    step={0.001}
                    value={currentTime}
                    disabled={!videoReady || videoError}
                    aria-label="选择截帧时间"
                    onChange={onScrubInput}
                  />
                </div>
                <span className="cvfm-time">{formatVideoTime(currentTime)}</span>
              </div>
              <div className="cvfm-timeline__quick">
                <span>快速选取</span>
                <button
                  type="button"
                  className="cvfm-boundary-btn"
                  disabled={!videoReady || videoError}
                  onClick={() => seekToBoundary('start')}
                >
                  首帧
                </button>
                <button
                  type="button"
                  className="cvfm-boundary-btn"
                  disabled={!videoReady || videoError}
                  onClick={() => seekToBoundary('end')}
                >
                  尾帧
                </button>
              </div>
            </section>
          </>
        ) : (
          <div className="cvfm-empty">
            <img src={emptyImageIconUrl} alt="" className="empty-image-icon empty-image-icon--xl" />
            <p>暂无视频</p>
            <span>请先生成当前作品或剧集的分镜视频</span>
          </div>
        )}

        <footer className="cvfm-footer">
          <Button className="cvfm-cancel-btn" disabled={confirming} onClick={closeModal}>
            取消
          </Button>
          <Button
            type="primary"
            className="cvfm-confirm-btn"
            loading={confirming}
            disabled={!selectedVideo || !videoReady || videoError}
            onClick={confirmCapture}
          >
            确认截帧
          </Button>
        </footer>
      </div>
    </Modal>
  )
}

export default CaptureVideoFrameModal
