'use client'

import { Button, Dropdown, Input } from 'antd'
import type { MenuProps } from 'antd'
import {
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  InfoCircleOutlined,
  LoadingOutlined,
  MoreOutlined
} from '@ant-design/icons'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { AssetCardCancelIcon } from '~/components/common/AssetCardCancelIcon'
import { BillingQuoteConfirm } from '~/components/common/BillingQuoteConfirm'
import { assetUrl } from '~/utils/assetUrl'
import iconPreviewRaw from '~/assets/img/icon/Preview.svg'
import iconReplaceRaw from '~/assets/img/icon/Replace.svg'
import iconDownloadRaw from '~/assets/img/icon/download.svg'
import {
  displayPanelTitle,
  getPanelCoverImage,
  getPanelCoverImageIndex,
  renderStoryboardScriptContent,
  type StoryboardScriptViewSharedProps
} from './storyboardScriptShared'

const iconPreview = assetUrl(iconPreviewRaw)
const iconReplace = assetUrl(iconReplaceRaw)
const iconDownload = assetUrl(iconDownloadRaw)

export type StoryboardScriptCardViewProps = StoryboardScriptViewSharedProps

/** 卡片视图（原 StoryboardScript.vue 模板 .storyboard-cards 分支） */
export function StoryboardScriptCardView({
  panels,
  isProMode,
  editingId,
  editingTitle,
  onEditingTitleChange,
  onStartEditTitle,
  onFinishEditTitle,
  onCancelEditTitle,
  isPanelImageGenerating,
  onOpenStoryboardScriptModal,
  onOpenStoryboardImage,
  onCopyPanel,
  onRemovePanel,
  onJumpToVideoWithModal,
  onPreviewStoryboardImage,
  onDownloadStoryboardImage,
  onDeleteStoryboardImage,
  showStoryboardPartialBanner,
  generationError,
  canResumePartialFailed,
  isResumingPartialFailed,
  resumeBillingRequest,
  onResumePartialFailed,
  failedPanels
}: StoryboardScriptCardViewProps) {
  function cardMenuItems(): MenuProps['items'] {
    if (isProMode) {
      return [
        { key: 'edit-script', icon: <EditOutlined />, label: '修改分镜脚本' },
        { key: 'remove', icon: <DeleteOutlined />, label: '删除分镜', danger: true }
      ]
    }
    return [
      { key: 'edit-image', icon: <EditOutlined />, label: '编辑分镜图' },
      { key: 'copy', icon: <CopyOutlined />, label: '复制分镜' },
      { key: 'remove', icon: <DeleteOutlined />, label: '删除分镜', danger: true },
      { key: 'to-video', icon: <InfoCircleOutlined />, label: '视频生成' }
    ]
  }

  function onCardMenuClick(index: number, key: string) {
    if (key === 'edit-script') onOpenStoryboardScriptModal(index)
    else if (key === 'edit-image') onOpenStoryboardImage(index)
    else if (key === 'copy') onCopyPanel(index)
    else if (key === 'remove') onRemovePanel(index)
    else if (key === 'to-video') onJumpToVideoWithModal(index)
  }

  return (
    <div className="storyboard-cards">
      {showStoryboardPartialBanner || failedPanels.length > 0 ? (
        <div className="storyboard-cards-status">
          {showStoryboardPartialBanner ? (
            <div className="storyboard-partial-banner">
              <div className="storyboard-partial-banner__text">{generationError}</div>
              {canResumePartialFailed ? (
                <BillingQuoteConfirm
                  resolveRequest={() => resumeBillingRequest}
                  onConfirm={onResumePartialFailed}
                  disabled={isResumingPartialFailed || !resumeBillingRequest}
                  title="确认续生失败项？"
                  okText="续生"
                >
                  <Button
                    type="primary"
                    size="small"
                    className="storyboard-resume-btn"
                    loading={isResumingPartialFailed}
                    disabled={!resumeBillingRequest}
                  >
                    续生失败项
                  </Button>
                </BillingQuoteConfirm>
              ) : null}
            </div>
          ) : null}
          {failedPanels.length > 0 ? (
            <div className="storyboard-failed-list storyboard-failed-list--card">
              {failedPanels.map((item, idx) => (
                <div key={`failed-card-${item.id || idx}`} className="storyboard-failed-item">
                  <div className="storyboard-failed-item__title">
                    {item.title || `分镜脚本${idx + 1}`}
                  </div>
                  <div className="storyboard-failed-item__desc">
                    {item.message || '生成失败，请重试'}
                  </div>
                </div>
              ))}
            </div>
          ) : null}
        </div>
      ) : null}
      {panels.map((panel, index) => {
        const cover = getPanelCoverImage(panel)
        const generating = isPanelImageGenerating(panel)
        return (
          <div
            key={panel.id}
            className="storyboard-card"
          >
            <div className="storyboard-card-header">
              <div
                className="storyboard-card-title"
                onClick={(e) => {
                  e.stopPropagation()
                  onStartEditTitle(panel)
                }}
              >
                {editingId === panel.id ? (
                  <Input
                    value={editingTitle}
                    onChange={(e) => onEditingTitleChange(e.target.value)}
                    variant="borderless"
                    className="storyboard-title-input"
                    onBlur={() => onFinishEditTitle(panel)}
                    onPressEnter={() => onFinishEditTitle(panel)}
                    onKeyDown={(e) => {
                      if (e.key === 'Escape') onCancelEditTitle()
                    }}
                  />
                ) : (
                  <span className="storyboard-card-title-text">
                    {displayPanelTitle(panel, index)}
                  </span>
                )}
              </div>
              <Dropdown
                trigger={['click']}
                placement="bottomRight"
                menu={{
                  items: cardMenuItems(),
                  onClick: ({ key }) => onCardMenuClick(index, key)
                }}
              >
                <Button
                  type="text"
                  size="small"
                  className="storyboard-card-more"
                  icon={<MoreOutlined />}
                  onClick={(e) => e.stopPropagation()}
                />
              </Dropdown>
            </div>
            <div
              className={`storyboard-card-body${
                isProMode ? ' storyboard-card-body--pro-script-only' : ''
              }`}
            >
              {!isProMode ? (
                <div className="storyboard-block storyboard-block-small">
                  <div className="storyboard-block-title">分镜图：</div>
                  <div
                    className={`storyboard-block-card${cover?.url ? ' has-image' : ''}${
                      generating ? ' storyboard-image-loading' : ''
                    }`}
                    onClick={() => {
                      if (!cover && !generating) onOpenStoryboardImage(index)
                    }}
                  >
                    {generating ? (
                      <div className="asset-visual-generating-block" role="status" aria-live="polite">
                        <div className="asset-visual-generating-block__shimmer" aria-hidden="true" />
                        <LoadingOutlined spin className="asset-visual-generating-block__icon" />
                        <p className="asset-visual-generating-block__text">正在生成分镜图…</p>
                      </div>
                    ) : cover ? (
                      <>
                        <div className="storyboard-block-card-header">
                          <span className="storyboard-block-image-title">
                            {cover?.title || '分镜图'}
                          </span>
                          {/* 组件内部已 stopPropagation（原 @click.stop） */}
                          <AssetCardCancelIcon
                            label="取消分镜图"
                            onClick={() =>
                              onDeleteStoryboardImage(index, getPanelCoverImageIndex(panel))
                            }
                          />
                        </div>
                        <div
                          className="storyboard-block-image-wrap"
                          onClick={(e) => {
                            e.stopPropagation()
                            onOpenStoryboardImage(index)
                          }}
                        >
                          <ShimmerImage
                            src={cover?.url || cover?.thumbnail || ''}
                            imgClass="storyboard-block-thumb"
                            objectFit="cover"
                            revealDirection="fade"
                          />
                        </div>
                        <div className="scene-card-image-footer asset-action-footer">
                          <Button
                            onClick={(e) => {
                              e.stopPropagation()
                              onPreviewStoryboardImage(index, getPanelCoverImageIndex(panel))
                            }}
                            icon={<img src={iconPreview} alt="" className="footer-action-icon" />}
                          >
                            预览
                          </Button>
                          <Button
                            onClick={(e) => {
                              e.stopPropagation()
                              onOpenStoryboardImage(index)
                            }}
                            icon={<img src={iconReplace} alt="" className="footer-action-icon" />}
                          >
                            替换
                          </Button>
                          <Button
                            onClick={(e) => {
                              e.stopPropagation()
                              onDownloadStoryboardImage(index, getPanelCoverImageIndex(panel))
                            }}
                            icon={<img src={iconDownload} alt="" className="footer-action-icon" />}
                          >
                            下载
                          </Button>
                        </div>
                      </>
                    ) : (
                      <>
                        <EditOutlined className="storyboard-block-icon" />
                        <div className="storyboard-block-text">编辑分镜图片</div>
                        <div className="storyboard-block-sub">点击去创建此分镜图片</div>
                      </>
                    )}
                  </div>
                </div>
              ) : (
                <div className="storyboard-block storyboard-block-script">
                  <div className="storyboard-block-title">分镜脚本：</div>
                  {panel.scriptContent ? (
                    <div
                      className="storyboard-script-content"
                      onClick={() => onOpenStoryboardScriptModal(index)}
                      dangerouslySetInnerHTML={{
                        __html: renderStoryboardScriptContent(panel.scriptContent)
                      }}
                    />
                  ) : (
                    <div
                      className="storyboard-script-tip"
                      onClick={() => onOpenStoryboardScriptModal(index)}
                    >
                      可点击「修改分镜脚本」或「自动生成分镜」生成脚本
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        )
      })}
    </div>
  )
}

export default StoryboardScriptCardView
