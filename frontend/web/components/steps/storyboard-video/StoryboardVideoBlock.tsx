'use client'

import { FullscreenOutlined,LoadingOutlined } from '@ant-design/icons'
import { Button } from 'antd'
import iconPreviewRaw from '~/assets/img/icon/Preview.svg'
import iconReplaceRaw from '~/assets/img/icon/Replace.svg'
import iconDownloadRaw from '~/assets/img/icon/download.svg'
import iconEmptyFailRaw from '~/assets/img/icon/empty_fail.svg'
import pencilIconRaw from '~/assets/img/icon/pencil.svg'
import { AssetCardCancelIcon } from '~/components/common/AssetCardCancelIcon'
import { BillingQuoteConfirm } from '~/components/common/BillingQuoteConfirm'
import { ShimmerVideo } from '~/components/common/ShimmerVideo'
import type { StoryboardVideoPanel } from '~/types'
import type { BillingQuoteRequest } from '~/types/business-api'
import { assetUrl } from '~/utils/assetUrl'
import { getPanelStoryboardVideo } from './storyboardVideoViewShared'
const iconPreview = assetUrl(iconPreviewRaw)
const iconReplace = assetUrl(iconReplaceRaw)
const iconDownload = assetUrl(iconDownloadRaw)
const iconEmptyFail = assetUrl(iconEmptyFailRaw)
const pencilIcon = assetUrl(pencilIconRaw)

export interface StoryboardVideoBlockProps {
  panel: StoryboardVideoPanel
  index: number
  isPanelVideoGenerating: (panel: StoryboardVideoPanel, index: number) => boolean
  panelVideoGenerateError: (panel: StoryboardVideoPanel, index: number) => string | undefined
  playingPanelIndex: number
  panelVideoMediaReady: Record<number, boolean>
  setPanelVideoRef: (el: unknown, panelIndex: number) => void
  onMarkPanelVideoMediaReady: (panelIndex: number) => void
  onPanelVideoEnded: (panelIndex: number) => void
  onPanelVideoPause: (panelIndex: number) => void
  onPlayPanelVideo: (panelIndex: number) => void
  onFullscreenPanelVideo: (panelIndex: number) => void
  onOpenEditVideoModal: (index: number) => void
  onCancelStoryboardVideo: (index: number) => void
  onPreviewStoryboardVideo: (index: number) => void
  onDownloadStoryboardVideo: (index: number) => void
  onRegeneratePanel: (index: number) => void
  resolveRegeneratePanelBillingRequest: (index: number) => Promise<BillingQuoteRequest | null>
}

/**
 * 单个分镜的「分镜视频」卡片体（原 StoryboardVideo.vue 模板中卡片视图与列表视图
 * 重复的 loading / 失败 / 已设置 / 占位 四分支，抽为共享块）。
 */
export function StoryboardVideoBlock({
  panel,
  index,
  isPanelVideoGenerating,
  panelVideoGenerateError,
  playingPanelIndex,
  panelVideoMediaReady,
  setPanelVideoRef,
  onMarkPanelVideoMediaReady,
  onPanelVideoEnded,
  onPanelVideoPause,
  onPlayPanelVideo,
  onFullscreenPanelVideo,
  onOpenEditVideoModal,
  onCancelStoryboardVideo,
  onPreviewStoryboardVideo,
  onDownloadStoryboardVideo,
  onRegeneratePanel,
  resolveRegeneratePanelBillingRequest
}: StoryboardVideoBlockProps) {
  const mainVideo = getPanelStoryboardVideo(panel)

  if (isPanelVideoGenerating(panel, index)) {
    return (
      <div className="storyboard-block-card storyboard-video-loading">
        <div className="asset-visual-generating-block" role="status" aria-live="polite">
          <div className="asset-visual-generating-block__shimmer" aria-hidden="true" />
          <LoadingOutlined spin className="asset-visual-generating-block__icon" />
          <p className="asset-visual-generating-block__text">正在生成分镜视频…</p>
        </div>
      </div>
    )
  }

  if (panelVideoGenerateError(panel, index)) {
    return (
      <div className="storyboard-block-card scene-card scene-card-failed storyboard-video-generate-failed">
        <div className="scene-card-failed-content">
          <div className="scene-card-failed-icon">
            <img src={iconEmptyFail} alt="" className="scene-card-failed-icon-image" />
          </div>
          <div className="scene-card-failed-text">生成失败</div>
          <BillingQuoteConfirm
            resolveRequest={() => resolveRegeneratePanelBillingRequest(index)}
            onConfirm={() => onRegeneratePanel(index)}
            title="确认重新生成该分镜视频？"
          >
            <Button
              type="primary"
              className="scene-card-failed-retry"
              onClick={(e) => e.stopPropagation()}
            >
              重新生成
            </Button>
          </BillingQuoteConfirm>
        </div>
      </div>
    )
  }

  if (mainVideo) {
    return (
      <div className="storyboard-block-card storyboard-video-set has-image">
        <div className="storyboard-block-card-header">
          <span className="storyboard-block-image-title">{mainVideo.title || '分镜视频'}</span>
          <AssetCardCancelIcon label="取消分镜视频" onClick={() => onCancelStoryboardVideo(index)} />
        </div>
        <div className="storyboard-video-preview-wrap">
          <ShimmerVideo
            ref={(el) => setPanelVideoRef(el, index)}
            src={mainVideo.url || ''}
            videoClass="storyboard-video-preview"
            objectFit="cover"
            revealDirection="fade"
            lazy
            preload="metadata"
            onLoad={() => onMarkPanelVideoMediaReady(index)}
            onEnded={() => onPanelVideoEnded(index)}
            onPause={() => onPanelVideoPause(index)}
          />
          {mainVideo.url && playingPanelIndex !== index && panelVideoMediaReady[index] ? (
            <button
              type="button"
              className="dubbing-video-play-btn dubbing-video-play-btn--card"
              title="播放视频"
              aria-label="播放视频"
              onClick={(e) => {
                e.stopPropagation()
                onPlayPanelVideo(index)
              }}
            />
          ) : null}
          {mainVideo.url ? (
            <div className="storyboard-video-top-actions">
              <Button
                type="text"
                size="small"
                className="storyboard-video-action-btn"
                onClick={(e) => {
                  e.stopPropagation()
                  onFullscreenPanelVideo(index)
                }}
              >
                <FullscreenOutlined />
              </Button>
            </div>
          ) : null}
          <div className="scene-card-image-footer asset-action-footer">
            <Button
              onClick={(e) => {
                e.stopPropagation()
                onPreviewStoryboardVideo(index)
              }}
              icon={<img src={iconPreview} alt="" className="footer-action-icon" />}
            >
              预览
            </Button>
            <Button
              onClick={(e) => {
                e.stopPropagation()
                onOpenEditVideoModal(index)
              }}
              icon={<img src={iconReplace} alt="" className="footer-action-icon" />}
            >
              替换
            </Button>
            <Button
              onClick={(e) => {
                e.stopPropagation()
                onDownloadStoryboardVideo(index)
              }}
              icon={<img src={iconDownload} alt="" className="footer-action-icon" />}
            >
              下载
            </Button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div
      className="storyboard-block-card storyboard-video-placeholder"
      onClick={() => onOpenEditVideoModal(index)}
    >
      <img src={pencilIcon} alt="" />
      <div className="storyboard-block-text">编辑分镜视频</div>
      <div className="storyboard-block-sub">点击去创建此分镜视频</div>
    </div>
  )
}

export default StoryboardVideoBlock
