'use client'

/** 时间轴五条轨道（视频/音量/配音/字幕/音乐）的 JSX 拆分；随主组件每次渲染同步刷新 */

import { EditOutlined } from '@ant-design/icons'
import {
  clipStyle,
  formatVolumeLabel,
  getClipPageLabel,
  getVideoTimelineTotalSec,
  getVideoVolumePercent,
  hasClipVideoUrl,
  musicBarStyle,
  musicSourceCycleStyle,
  secToLayoutPx
} from './layoutOps'
import {
  onMusicBarClick,
  openEditMusicModal,
  openEditVideoModalForClip,
  openEditDubbingModalForClip
} from './modalOps'
import {
  editSubtitle,
  isVolumeBarActive,
  onClipPointerDown,
  onMissingSubtitleClick,
  onMissingVoiceClick,
  onResizePointerDown,
  onSubtitleRangePointerDown,
  onTrackClick,
  onVolumeBarMouseEnter,
  onVolumeBarMouseLeave,
  onVolumeBarPointerDown,
  selectClip
} from './interactionOps'
import {
  MIN_DURATION,
  type MusicDisplayBar,
  type TimelineAudioItem,
  type VideoPreviewCtx
} from './types'

export function TimelineTracks({ ctx, emptyImageIconUrl }: { ctx: VideoPreviewCtx; emptyImageIconUrl: string }) {
  const S = ctx.state
  const videoClips = S.videoClips.value
  const voiceItems = S.voiceItems.value
  const subtitleItems = S.subtitleItems.value
  const musicItems = S.musicItems.value
  const selectedClip = S.selectedClip.value
  const swappingClipIds = S.swappingClipIds.value

  // 原 computed missingVoiceSlots
  const missingVoiceSlots = (() => {
    const hasVoice = new Set(voiceItems.map((item) => item.videoClipId).filter(Boolean) as string[])
    return videoClips.filter((clip) => !hasVoice.has(clip.id))
  })()

  // 原 computed missingSubtitleSlots
  const missingSubtitleSlots = (() => {
    const hasSubtitle = new Set(
      subtitleItems.map((item) => item.videoClipId).filter(Boolean) as string[]
    )
    return videoClips.filter((clip) => !hasSubtitle.has(clip.id))
  })()

  // 原 computed musicDisplayBars
  const musicDisplayBars: MusicDisplayBar[] = (() => {
    const total = Math.max(MIN_DURATION, getVideoTimelineTotalSec(ctx))
    if (musicItems.length > 0) {
      return musicItems.map((item) => ({ key: item.id, item, empty: false }))
    }
    if (!videoClips.length) return []
    const emptyItem: TimelineAudioItem = {
      id: 'music-empty',
      kind: 'music',
      name: '无音乐',
      url: '',
      start: 0,
      duration: total,
      volume: 0.25,
      fadeIn: 0,
      fadeOut: 0,
      loop: true,
      volumeCurve: [0.25, 0.25, 0.25]
    }
    return [{ key: 'music-empty', item: emptyItem, empty: true }]
  })()

  // 原 computed subtitleRangeStyle
  const subtitleRangeStyle = (() => {
    const r = S.subtitleRange.value
    const s = Math.min(r.startSec, r.endSec)
    const e = Math.max(r.startSec, r.endSec)
    const leftPx = secToLayoutPx(ctx, s)
    const rightPx = secToLayoutPx(ctx, e)
    return { left: `${leftPx}px`, width: `${Math.max(2, rightPx - leftPx)}px` }
  })()

  return (
    <>
      <div className="track-row track-row-video">
        <div className="track-label">视频</div>
        <div className="track-strip track-strip-video" data-track="video">
          {videoClips.map((clip) => (
            <div
              key={clip.id}
              className={[
                'track-clip',
                'track-clip-video',
                selectedClip?.id === clip.id && selectedClip?.track === 'video'
                  ? 'track-clip-selected'
                  : '',
                swappingClipIds.has(clip.id) ? 'track-clip-swapping' : '',
                !hasClipVideoUrl(clip) ? 'track-clip-video-empty' : ''
              ]
                .filter(Boolean)
                .join(' ')}
              style={clipStyle(ctx, clip)}
              onClick={(e) => {
                e.stopPropagation()
                selectClip(ctx, 'video', clip.id)
              }}
              onPointerDown={(e) => {
                e.stopPropagation()
                onClipPointerDown(ctx, e, 'video', clip.id)
              }}
            >
              {clip.url ? (
                <video className="clip-thumb-video" src={clip.url} muted playsInline preload="metadata" />
              ) : (
                <div className="clip-thumb-placeholder">
                  <img
                    src={emptyImageIconUrl}
                    alt=""
                    className="clip-thumb-placeholder__icon empty-image-icon empty-image-icon--sm"
                  />
                </div>
              )}
              <div className="clip-video-meta">
                <span className="clip-page-badge">{getClipPageLabel(ctx, clip)}</span>
                <span className="clip-text">{clip.name}</span>
              </div>
              <div className="clip-hover-mask">
                <button
                  type="button"
                  className="clip-edit-btn"
                  onClick={(e) => {
                    e.stopPropagation()
                    openEditVideoModalForClip(ctx, clip.id)
                  }}
                >
                  <EditOutlined />
                  编辑视频
                </button>
              </div>
              <span
                className="clip-handle clip-handle-left"
                onPointerDown={(e) => {
                  e.stopPropagation()
                  onResizePointerDown(ctx, e, 'video', clip.id, 'start')
                }}
              />
              <span
                className="clip-handle clip-handle-right"
                onPointerDown={(e) => {
                  e.stopPropagation()
                  onResizePointerDown(ctx, e, 'video', clip.id, 'end')
                }}
              />
            </div>
          ))}
        </div>
      </div>

      <div className="track-row track-row-volume">
        <div className="track-label">音量</div>
        <div className="track-strip track-strip-volume">
          {videoClips.map((clip, clipIndex) => (
            <div
              key={`vol-${clip.id}`}
              className={`volume-bar-segment${isVolumeBarActive(ctx, clip.id) ? ' volume-bar-segment-active' : ''}`}
              data-volume-clip={clip.id}
              style={clipStyle(ctx, clip, clipIndex)}
              onMouseEnter={() => onVolumeBarMouseEnter(ctx, clip.id)}
              onMouseLeave={() => onVolumeBarMouseLeave(ctx, clip.id)}
            >
              <div
                className="volume-bar-shell"
                onPointerDown={(e) => {
                  e.stopPropagation()
                  onVolumeBarPointerDown(ctx, e, clip.id)
                }}
              >
                <div
                  className={`volume-bar-fill${S.volumeDrag.value?.clipId === clip.id ? ' volume-bar-fill-dragging' : ''}`}
                  style={{ height: `${getVideoVolumePercent(ctx, clip.id)}%` }}
                />
                {isVolumeBarActive(ctx, clip.id) && (
                  <div className="volume-bar-value">{formatVolumeLabel(ctx, clip.id)}</div>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="track-row track-row-aux track-row-dubbing">
        <div className="track-label">配音</div>
        <div
          className="track-strip track-strip-aux track-strip-dubbing track-strip-clickable"
          data-track="voice"
          onClick={(e) => onTrackClick(ctx, e, 'voice')}
        >
          {voiceItems.map((item) => (
            <div
              key={item.id}
              className={[
                'track-clip',
                'track-clip-dubbing',
                'track-clip-dubbing-has-audio',
                selectedClip?.id === item.id && selectedClip?.track === 'voice'
                  ? 'track-clip-selected'
                  : '',
                swappingClipIds.has(item.id) ? 'track-clip-swapping' : ''
              ]
                .filter(Boolean)
                .join(' ')}
              style={clipStyle(ctx, item)}
              onClick={(e) => {
                e.stopPropagation()
                selectClip(ctx, 'voice', item.id)
              }}
              onPointerDown={(e) => {
                e.stopPropagation()
                onClipPointerDown(ctx, e, 'voice', item.id)
              }}
              onDoubleClick={(e) => {
                e.stopPropagation()
                openEditDubbingModalForClip(ctx, item.videoClipId || item.id)
              }}
            >
              <div className="dubbing-wave-layer" aria-hidden="true" />
              <span className="clip-text">{item.name || '有配音'}</span>
              <div className="clip-hover-mask clip-hover-mask-dubbing">
                <button
                  type="button"
                  className="clip-edit-btn"
                  onClick={(e) => {
                    e.stopPropagation()
                    openEditDubbingModalForClip(ctx, item.videoClipId || item.id)
                  }}
                >
                  <EditOutlined />
                  编辑配音
                </button>
              </div>
              <span
                className="clip-handle clip-handle-left"
                onPointerDown={(e) => {
                  e.stopPropagation()
                  onResizePointerDown(ctx, e, 'voice', item.id, 'start')
                }}
              />
              <span
                className="clip-handle clip-handle-right"
                onPointerDown={(e) => {
                  e.stopPropagation()
                  onResizePointerDown(ctx, e, 'voice', item.id, 'end')
                }}
              />
            </div>
          ))}
          {missingVoiceSlots.map((clip) => (
            <div
              key={`voice-empty-${clip.id}`}
              className="track-clip track-clip-dubbing track-clip-empty-record"
              style={clipStyle(ctx, clip)}
              onPointerDown={(e) => e.stopPropagation()}
              onClick={(e) => {
                e.stopPropagation()
                onMissingVoiceClick(ctx, clip.id)
              }}
            >
              <span className="clip-text">无配音</span>
              <div className="clip-hover-mask clip-hover-mask-dubbing">
                <button
                  type="button"
                  className="clip-edit-btn"
                  onClick={(e) => {
                    e.stopPropagation()
                    openEditDubbingModalForClip(ctx, clip.id)
                  }}
                >
                  <EditOutlined />
                  编辑配音
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="track-row track-row-aux track-row-subtitle">
        <div className="track-label">字幕</div>
        <div
          ref={ctx.dom.subtitleStripRef}
          className="track-strip track-strip-aux track-strip-subtitle track-strip-clickable"
          data-track="subtitle"
          onPointerDown={(e) => onSubtitleRangePointerDown(ctx, e)}
          onClick={(e) => onTrackClick(ctx, e, 'subtitle')}
        >
          {subtitleItems.map((item) => (
            <div
              key={item.id}
              className={[
                'track-clip',
                'track-clip-subtitle',
                selectedClip?.id === item.id && selectedClip?.track === 'subtitle'
                  ? 'track-clip-selected'
                  : '',
                swappingClipIds.has(item.id) ? 'track-clip-swapping' : ''
              ]
                .filter(Boolean)
                .join(' ')}
              style={clipStyle(ctx, item)}
              onClick={(e) => {
                e.stopPropagation()
                selectClip(ctx, 'subtitle', item.id)
              }}
              onPointerDown={(e) => {
                e.stopPropagation()
                onClipPointerDown(ctx, e, 'subtitle', item.id)
              }}
            >
              <span className="clip-text">{item.text || '有字幕'}</span>
              <div className="clip-hover-mask clip-hover-mask-subtitle">
                <button
                  type="button"
                  className="clip-edit-btn"
                  onClick={(e) => {
                    e.stopPropagation()
                    editSubtitle(ctx, item.id)
                  }}
                >
                  <EditOutlined />
                  编辑字幕
                </button>
              </div>
              <span
                className="clip-handle clip-handle-left"
                onPointerDown={(e) => {
                  e.stopPropagation()
                  onResizePointerDown(ctx, e, 'subtitle', item.id, 'start')
                }}
              />
              <span
                className="clip-handle clip-handle-right"
                onPointerDown={(e) => {
                  e.stopPropagation()
                  onResizePointerDown(ctx, e, 'subtitle', item.id, 'end')
                }}
              />
            </div>
          ))}
          {missingSubtitleSlots.map((clip) => (
            <div
              key={`subtitle-empty-${clip.id}`}
              className="track-clip track-clip-subtitle track-clip-empty-record"
              style={clipStyle(ctx, clip)}
              onPointerDown={(e) => e.stopPropagation()}
              onClick={(e) => {
                e.stopPropagation()
                onMissingSubtitleClick(ctx, clip.id)
              }}
            >
              <span className="clip-text">无字幕</span>
              <div className="clip-hover-mask clip-hover-mask-subtitle">
                <button
                  type="button"
                  className="clip-edit-btn"
                  onClick={(e) => {
                    e.stopPropagation()
                    onMissingSubtitleClick(ctx, clip.id)
                  }}
                >
                  <EditOutlined />
                  编辑字幕
                </button>
              </div>
            </div>
          ))}
          {S.subtitleRange.value.active && (
            <div className="subtitle-range-mask" style={subtitleRangeStyle} />
          )}
        </div>
      </div>

      <div className="track-row track-row-aux track-row-music">
        <div className="track-label">音乐</div>
        <div className="track-strip track-strip-aux track-strip-music" data-track="music">
          {musicDisplayBars.map((bar) => (
            <div
              key={bar.key}
              className={[
                'track-clip',
                'track-clip-music',
                !bar.empty ? 'track-clip-music-has-audio' : '',
                bar.empty ? 'track-clip-empty-record' : '',
                bar.empty ? 'track-clip-music-empty' : '',
                !bar.empty && selectedClip?.id === bar.item.id && selectedClip?.track === 'music'
                  ? 'track-clip-selected'
                  : '',
                !bar.empty && swappingClipIds.has(bar.item.id) ? 'track-clip-swapping' : ''
              ]
                .filter(Boolean)
                .join(' ')}
              style={musicBarStyle(ctx, bar.item, bar.empty)}
              onPointerDown={(e) => e.stopPropagation()}
              onClick={(e) => {
                e.stopPropagation()
                onMusicBarClick(ctx, bar)
              }}
            >
              <div className="music-wave-layer" aria-hidden="true" />
              {!bar.empty &&
                bar.item.sourceDuration &&
                bar.item.duration > bar.item.sourceDuration && (
                  <span
                    className="music-source-cycle"
                    style={musicSourceCycleStyle(bar.item)}
                    aria-hidden="true"
                  />
                )}
              <span className="clip-text">{bar.empty ? '无音乐' : bar.item.name}</span>
              <div className="clip-hover-mask clip-hover-mask-music">
                <button
                  type="button"
                  className="clip-edit-btn"
                  onClick={(e) => {
                    e.stopPropagation()
                    openEditMusicModal(ctx)
                  }}
                >
                  <EditOutlined />
                  编辑音乐
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>
    </>
  )
}
