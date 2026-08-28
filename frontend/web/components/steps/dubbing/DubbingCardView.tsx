'use client'

import { Button, Dropdown } from 'antd'
import type { MenuProps } from 'antd'
import {
  InfoCircleOutlined,
  MoreOutlined,
  VideoCameraOutlined,
  UserOutlined,
  LoadingOutlined
} from '@ant-design/icons'
import { ShimmerVideo } from '~/components/common/ShimmerVideo'
import type { DubbingViewSharedProps } from './dubbingViewShared'

export type DubbingCardViewProps = DubbingViewSharedProps

/** 卡片视图：点击「卡片」按钮后以网格卡片形式展示音画同步列表（原 Dubbing.vue 模板卡片分支） */
export function DubbingCardView({
  panels,
  hoverIndex,
  onHoverIndexChange,
  displayPanelTitle,
  isDubbingBatchGenerating,
  hasVideoForIndex,
  getVideoUrlForIndex,
  getRenderedDialogue,
  onGoStep,
  onEditDubbing,
  onCopyPanel,
  onRemovePanel,
  onPreviewDubbingVideo,
  goToStoryboardVideo
}: DubbingCardViewProps) {
  function cardMenuItems(index: number): MenuProps['items'] {
    return [
      { key: 'go-storyboard', label: '@分镜设计' },
      { key: 'go-video', label: '@视频生成' },
      {
        key: 'edit-dubbing',
        label: (
          <>
            {isDubbingBatchGenerating(index) ? (
              <LoadingOutlined spin className="dubbing-edit-btn-loading" />
            ) : null}
            编辑分镜配音
          </>
        )
      },
      { key: 'copy-panel', label: '复制分镜' },
      { key: 'remove-panel', label: '删除分镜', danger: true }
    ]
  }

  function onCardMenuClick(index: number, key: string) {
    if (key === 'go-storyboard') onGoStep(3)
    else if (key === 'go-video') onGoStep(4)
    else if (key === 'edit-dubbing') onEditDubbing(index)
    else if (key === 'copy-panel') onCopyPanel(index)
    else if (key === 'remove-panel') onRemovePanel(index)
  }

  return (
    <div className="storyboard-cards dubbing-cards">
      {panels.map((panel, index) => (
        <div
          key={panel.id}
          className="storyboard-card dubbing-card"
          onMouseEnter={() => onHoverIndexChange(index)}
          onMouseLeave={() => onHoverIndexChange(null)}
        >
          <div className="storyboard-card-header">
            <div className="storyboard-card-title">
              <span className="storyboard-card-title-text">{displayPanelTitle(panel, index)}</span>
            </div>
            <Dropdown
              trigger={['click']}
              placement="bottomRight"
              classNames={{ root: 'dubbing-card-menu-overlay' }}
              menu={{
                items: cardMenuItems(index),
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
          <div className="dubbing-card-body-shell">
            <div className="storyboard-card-body dubbing-card-body-inner">
              <div className="dubbing-video-block dubbing-video-block--card">
                <div className="storyboard-block-title dubbing-card-video-title">音画同步：</div>
                <div
                  className={`dubbing-video-area card-video-area dubbing-video-area--card${
                    hasVideoForIndex(index) ? ' has-video' : ''
                  }${isDubbingBatchGenerating(index) ? ' is-generating' : ''}`}
                >
                  {isDubbingBatchGenerating(index) ? (
                    <div className="dubbing-video-generating">
                      <LoadingOutlined spin className="dubbing-video-generating-icon" />
                      <span>生成中...</span>
                    </div>
                  ) : hasVideoForIndex(index) ? (
                    <>
                      <ShimmerVideo
                        src={getVideoUrlForIndex(index)}
                        videoClass="dubbing-video-preview"
                        wrapperClass="dubbing-video-shimmer"
                        objectFit="cover"
                        revealDirection="fade"
                        lazy
                        preload="metadata"
                      />
                      <button
                        type="button"
                        className="dubbing-video-play-btn dubbing-video-play-btn--card"
                        title="预览视频"
                        aria-label="预览视频"
                        onClick={(e) => {
                          e.stopPropagation()
                          onPreviewDubbingVideo(index)
                        }}
                      />
                    </>
                  ) : (
                    <div className="dubbing-video-placeholder dubbing-video-placeholder--card" />
                  )}
                </div>
              </div>
              <div className="storyboard-block-title dubbing-card-subtitle">台词：</div>
              {isDubbingBatchGenerating(index) ? (
                <div className="dubbing-skeleton dubbing-skeleton--card">
                  <div className="dubbing-skeleton-line dubbing-skeleton-line--lg" />
                  <div className="dubbing-skeleton-line dubbing-skeleton-line--sm" />
                </div>
              ) : (
                <div className="dubbing-dialogue-render dubbing-dialogue-render--card">
                  {getRenderedDialogue(index)}
                </div>
              )}
            </div>
            <div
              className={`dubbing-body-mask dubbing-body-mask--card${
                !hasVideoForIndex(index) && hoverIndex === index
                  ? ' dubbing-body-mask--visible'
                  : ''
              }`}
              onClick={(e) => {
                e.stopPropagation()
                goToStoryboardVideo()
              }}
            >
              <Button type="primary" className="dubbing-mask-btn" icon={<VideoCameraOutlined />}>
                前往视频生成
              </Button>
              <p className="dubbing-mask-tip">暂无分镜数据，添加视频后即可进行音画同步</p>
            </div>
          </div>
          {/* 遮罩不包含底部状态栏 */}
          <div className="dubbing-card-footer">
            <span
              className={`dubbing-status-value ${panel.status === 'done' ? 'done' : 'pending'}`}
            >
              <InfoCircleOutlined className="dubbing-footer-icon" />
              {panel.status === 'done' ? '已配音' : '未配音'}
            </span>
            <span className="dubbing-card-role">
              <UserOutlined className="dubbing-footer-icon" />
              发言角色: {panel.speakerRole || '艾米'}
            </span>
          </div>
        </div>
      ))}
    </div>
  )
}

export default DubbingCardView
