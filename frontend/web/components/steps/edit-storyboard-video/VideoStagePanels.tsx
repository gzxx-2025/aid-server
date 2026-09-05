'use client'

import { Button } from 'antd'
import {
  UploadOutlined,
  FolderOutlined,
  UnorderedListOutlined,
  AppstoreOutlined,
  VideoCameraOutlined,
  CheckOutlined,
  CheckCircleFilled,
  FullscreenOutlined,
  DownloadOutlined,
  LoadingOutlined
} from '@ant-design/icons'
import { ShimmerVideo, type ShimmerVideoHandle } from '~/components/common/ShimmerVideo'
import { VideoPosterThumb } from '~/components/common/VideoPosterThumb'
import { VIDEO_POSTER_PRIORITY } from '~/utils/ensureVideoPoster'
import { HistoryRecordWrap } from '~/components/common/HistoryRecordWrap'
import { EllipsisTooltip } from '~/components/common/EllipsisTooltip'
import { assetUrl } from '~/utils/assetUrl'
import deleteIconRaw from '~/assets/img/icon/del-black.svg'
import type { VideoModalCtx } from './types'

const deleteIcon = assetUrl(deleteIconRaw)

/** 左：生成记录（与 EditStoryboardImageModal 一致） */
export function VideoHistoryPanel({ ctx }: { ctx: VideoModalCtx }) {
  const videos = ctx.currentSceneVideos()
  const selectedVideoIdx = ctx.selectedVideoIdx.value

  return (
    <aside className="stage-history-panel">
      <h4 className="panel-title">生成记录</h4>
      <div className="history-list">
        {videos.length === 0 ? (
          <div className="history-empty-msg">暂无生成记录</div>
        ) : (
          videos.map((v: any, idx: number) => (
            <HistoryRecordWrap
              key={v.id || idx}
              showSetMain={ctx.canSetMainFromHistory(idx)}
              isMain={ctx.isHistoryVideoMain(idx)}
              setMainLabel="选为分镜视频"
              selectedMainLabel="已设置主视频"
              unsetMainLabel="取消主视频"
              setMainLoading={ctx.isSettingFinalVideo.value}
              onSetMain={() => void ctx.handleSetMainFromHistory(idx)}
              onUnsetMain={() => void ctx.unsetAsStoryboardVideo(idx)}
            >
              <button
                type="button"
                className={[
                  'history-item',
                  'video-history-item',
                  selectedVideoIdx === idx ? 'active' : '',
                  ctx.isHistoryVideoMain(idx) ? 'history-item--main' : '',
                  ctx.isHistoryVideoItemGenerating(idx) ? 'history-item--generating' : ''
                ]
                  .filter(Boolean)
                  .join(' ')}
                onClick={() => ctx.selectHistoryVideo(idx)}
              >
                {v.url ? (
                  /* 选中态只调整加载优先级；每条完成记录都必须展示视频首帧。 */
                  <VideoPosterThumb
                    src={v.url}
                    priority={
                      selectedVideoIdx === idx
                        ? VIDEO_POSTER_PRIORITY.selectedHistory
                        : VIDEO_POSTER_PRIORITY.history
                    }
                    imgClass="history-thumb-video"
                    videoClass="history-thumb-video"
                    objectFit="cover"
                    allowVideoFallback
                    fallbackLazy={selectedVideoIdx !== idx}
                    fallbackGated={selectedVideoIdx !== idx}
                  />
                ) : !ctx.isHistoryVideoItemGenerating(idx) ? (
                  <div className="history-empty">空</div>
                ) : null}
                {ctx.isHistoryVideoItemGenerating(idx) ? (
                  <div className="history-generating-mask" role="status" aria-live="polite">
                    <LoadingOutlined spin className="history-generating-mask__icon" />
                  </div>
                ) : null}
                {ctx.canDeleteHistoryVideo(v) ? (
                  <div
                    className="history-delete-icon"
                    role="button"
                    tabIndex={0}
                    onClick={(e) => {
                      e.stopPropagation()
                      e.preventDefault()
                      ctx.handleDeleteVideo(idx)
                    }}
                    onKeyDown={(e) => {
                      if (e.key !== 'Enter') return
                      e.stopPropagation()
                      e.preventDefault()
                      ctx.handleDeleteVideo(idx)
                    }}
                  >
                    <img src={deleteIcon} alt="删除" />
                  </div>
                ) : null}
              </button>
            </HistoryRecordWrap>
          ))
        )}
      </div>
      <div className="history-actions">
        <Button block onClick={ctx.handleUploadLocalVideo} icon={<UploadOutlined />}>
          <EllipsisTooltip title="本地上传视频" />
        </Button>
        <Button block onClick={ctx.handleOpenVideoLibrary} icon={<FolderOutlined />}>
          <EllipsisTooltip title="资源库导入视频" />
        </Button>
      </div>
    </aside>
  )
}

/** 单张视频卡片（列表 / 卡片两种视图 DOM 一致，仅容器类不同） */
function VideoCanvasCard({
  ctx,
  v,
  idx,
  cardView
}: {
  ctx: VideoModalCtx
  v: any
  idx: number
  cardView: boolean
}) {
  const generating = ctx.isVideoCanvasItemGenerating(idx)
  return (
    <div
      data-video-canvas-idx={idx}
      className={[
        'video-card',
        cardView ? 'video-card-view' : '',
        generating ? 'video-card--generating' : ''
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {v.importDate ? (
        <div className="video-card-header">
          <span className="video-date">{ctx.formatDate(v.importDate)}</span>
        </div>
      ) : null}
      <div className="video-preview-wrap">
        {v.url ? (
          <ShimmerVideo
            key={`${idx}-${v.url}`}
            ref={(el: ShimmerVideoHandle | null) => ctx.setVideoPreviewRef(el, idx)}
            src={v.url}
            videoClass="video-preview"
            objectFit="contain"
            revealDirection="fade"
            lazy
            preload="metadata"
            onLoad={() => ctx.markVideoPreviewMediaReady(idx)}
            onEnded={() => ctx.onVideoPreviewEnded(idx)}
            onPause={() => ctx.onVideoPreviewPause(idx)}
            onClick={(e) => {
              e.stopPropagation()
              void ctx.toggleVideoPreviewPlayback(idx)
            }}
          />
        ) : !generating ? (
          <div className="video-placeholder">
            <VideoCameraOutlined />
            <span>未设置分镜视频</span>
          </div>
        ) : (
          <div className="video-placeholder video-placeholder--blank" />
        )}
        {generating ? (
          <div className="video-card-generating-mask" role="status" aria-live="polite">
            <LoadingOutlined spin className="video-card-generating-mask__icon" />
            <span className="video-card-generating-mask__text">
              {ctx.videoGenerateProgressText.value}
            </span>
          </div>
        ) : null}
        {v.url && ctx.playingVideoIdx.value !== idx && ctx.videoPreviewMediaReady.value[idx] ? (
          <button
            type="button"
            className="dubbing-video-play-btn"
            title="播放视频"
            aria-label="播放视频"
            onClick={(e) => {
              e.stopPropagation()
              void ctx.toggleVideoPreviewPlayback(idx)
            }}
          />
        ) : null}
        {v.url ? (
          <div className="video-top-actions">
            <Button
              type="text"
              size="small"
              className="video-action-btn"
              onClick={(e) => {
                e.stopPropagation()
                void ctx.handleFullscreenVideo(idx)
              }}
            >
              <FullscreenOutlined />
            </Button>
            <Button
              type="text"
              size="small"
              className="video-action-btn"
              onClick={(e) => {
                e.stopPropagation()
                ctx.handleDownloadVideo(idx, v)
              }}
            >
              <DownloadOutlined />
            </Button>
          </div>
        ) : null}
      </div>
      {!generating ? (
        <div className="video-card-actions">
          {!v.isStoryboardVideo ? (
            <Button
              type="primary"
              size="small"
              className="btn-set-storyboard"
              loading={ctx.isSettingFinalVideo.value}
              disabled={ctx.isSettingFinalVideo.value}
              onClick={() => void ctx.setAsStoryboardVideo(idx)}
            >
              <CheckOutlined className="mr-1" />
              设置为分镜视频
            </Button>
          ) : (
            <Button
              size="small"
              className="btn-set-storyboard-done"
              loading={ctx.isSettingFinalVideo.value}
              disabled={ctx.isSettingFinalVideo.value}
              onClick={() => void ctx.unsetAsStoryboardVideo(idx)}
            >
              <CheckCircleFilled className="mr-1" />
              取消设置
            </Button>
          )}
        </div>
      ) : null}
    </div>
  )
}

/** 切分镜 Tab 时左右两侧的骨架屏（原 panel-skeleton 模板段） */
export function VideoStageSkeleton() {
  return (
    <div className="panel-skeleton right-panel-skeleton">
      <div className="skeleton-stage-layout">
        <aside className="skeleton-history-panel">
          <div className="skeleton-panel-title" />
          <div className="skeleton-history-list">
            {[1, 2, 3, 4, 5, 6].map((n) => (
              <div key={`sk-h-${n}`} className="skeleton-history-item" />
            ))}
          </div>
          <div className="skeleton-history-actions">
            <div className="skeleton-btn" />
            <div className="skeleton-btn" />
          </div>
        </aside>
        <section className="skeleton-canvas-panel">
          <div className="skeleton-canvas-toolbar">
            {[1, 2, 3, 4, 5].map((n) => (
              <div key={`sk-t-${n}`} className="skeleton-chip" />
            ))}
          </div>
          <div className="skeleton-canvas-main" />
        </section>
        <aside className="skeleton-config-panel">
          <div className="skeleton-config-tabs">
            <div className="skeleton-tab" />
            <div className="skeleton-tab" />
            <div className="skeleton-tab" />
          </div>
          <div className="skeleton-file-row" />
          <div className="skeleton-textarea" />
          <div className="skeleton-select-row">
            {[1, 2, 3, 4].map((n) => (
              <div key={`sk-s-${n}`} className="skeleton-select" />
            ))}
          </div>
          <div className="skeleton-primary-btn" />
        </aside>
      </div>
    </div>
  )
}

/** 中：工具栏 + 列表/预览 */
export function VideoCanvasSection({ ctx }: { ctx: VideoModalCtx }) {
  const videos = ctx.currentSceneVideos()
  const viewMode = ctx.viewMode.value

  return (
    <section className="stage-canvas-panel video-stage-canvas">
      <div className="video-canvas-toolbar">
        <div className="view-switcher">
          <button
            className={`view-btn${viewMode === 'list' ? ' active' : ''}`}
            onClick={() => ctx.viewMode.set('list')}
          >
            <UnorderedListOutlined className="view-btn-icon" />
            列表
          </button>
          <button
            className={`view-btn${viewMode === 'card' ? ' active' : ''}`}
            onClick={() => ctx.viewMode.set('card')}
          >
            <AppstoreOutlined className="view-btn-icon" />
            卡片
          </button>
        </div>
      </div>
      <div ref={ctx.videoCanvasBodyRef} className="video-canvas-body video-canvas-body--enhance-wrap">
        {videos.length === 0 && !ctx.isSceneVideoGenerating(ctx.currentSceneIndex.value) ? (
          <div className="canvas-empty video-canvas-empty">还没有内容,先去右侧配置并生成吧</div>
        ) : viewMode === 'list' ? (
          <div className="videos-list videos-list--in-canvas">
            {videos.map((v: any, idx: number) => (
              <VideoCanvasCard key={v.id || idx} ctx={ctx} v={v} idx={idx} cardView={false} />
            ))}
          </div>
        ) : (
          <div className="videos-list videos-list-card videos-list--in-canvas">
            {videos.map((v: any, idx: number) => (
              <VideoCanvasCard key={v.id || idx} ctx={ctx} v={v} idx={idx} cardView={true} />
            ))}
          </div>
        )}
      </div>
    </section>
  )
}
