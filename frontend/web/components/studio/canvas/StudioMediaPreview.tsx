'use client'

/* eslint-disable @next/next/no-img-element -- Studio 节点需预览运行时 OSS URL。 */
import type { RefObject } from 'react'
import { PlayCircleOutlined } from '@ant-design/icons'
import type { StudioMediaKind, StudioNodeKind } from '@/types/studio'
import { resolveMediaPlaybackUrl } from '@/utils/mediaFetch'
import { openImagePreviewModal } from '@/utils/openImagePreviewModal'
import { openVideoPreviewModal } from '@/utils/openVideoPreviewModal'

export interface StudioMediaPreviewProps {
  kind: StudioNodeKind
  mediaKind?: StudioMediaKind
  mediaUrl: string
  title: string
  videoRef?: RefObject<HTMLVideoElement | null>
  onIntrinsicSize?: (width: number, height: number) => void
}

export function StudioMediaPreview({ kind, mediaKind, mediaUrl, title, videoRef, onIntrinsicSize }: StudioMediaPreviewProps) {
  if (kind === 'video' || mediaKind === 'video') {
    if (isImagePreviewUrl(mediaUrl)) {
      return (
        <button
          type="button"
          className="studio-node-card__preview studio-node-card__preview--video-poster nodrag"
          aria-label={`预览${title}视频缩略图`}
          onClick={(event) => {
            event.stopPropagation()
            openImagePreviewModal({ url: mediaUrl, title: `${title} · 视频缩略图` })
          }}
        >
          <img
            src={mediaUrl}
            alt={`${title}视频缩略图`}
            draggable={false}
            onLoad={(event) => onIntrinsicSize?.(event.currentTarget.naturalWidth, event.currentTarget.naturalHeight)}
          />
          <span className="studio-node-card__video-placeholder"><PlayCircleOutlined /> 仅有视频缩略图</span>
        </button>
      )
    }
    const playbackUrl = resolveMediaPlaybackUrl(mediaUrl)
    return (
      <div className="studio-node-card__preview nodrag nowheel">
        <video
          ref={videoRef}
          src={playbackUrl}
          controls
          preload="metadata"
          playsInline
          aria-label={`${title}视频预览`}
          onLoadedMetadata={(event) => onIntrinsicSize?.(event.currentTarget.videoWidth, event.currentTarget.videoHeight)}
          onDoubleClick={(event) => {
            event.stopPropagation()
            openVideoPreviewModal({ url: playbackUrl, title })
          }}
        >
          当前浏览器无法播放该视频。
        </video>
      </div>
    )
  }
  if (mediaKind === 'image') {
    return (
      <button
        type="button"
        className="studio-node-card__preview nodrag"
        aria-label={`全屏预览${title}`}
        onClick={(event) => {
          event.stopPropagation()
          openImagePreviewModal({ url: mediaUrl, title })
        }}
      >
        <img
          src={mediaUrl}
          alt={`${title}预览`}
          draggable={false}
          onLoad={(event) => onIntrinsicSize?.(event.currentTarget.naturalWidth, event.currentTarget.naturalHeight)}
        />
      </button>
    )
  }
  if (mediaKind === 'audio') {
    return <div className="studio-node-card__audio nodrag nowheel"><audio controls src={resolveMediaPlaybackUrl(mediaUrl)} /></div>
  }
  return null
}

export function isImagePreviewUrl(url: string): boolean {
  return /^data:image\//i.test(url) || /\.(?:avif|gif|jpe?g|png|svg|webp)(?:[?#]|$)/i.test(url)
}
