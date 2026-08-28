'use client'

import { Button, Dropdown, Input } from 'antd'
import type { MenuProps } from 'antd'
import {
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  InfoCircleOutlined,
  MoreOutlined
} from '@ant-design/icons'
import type { StoryboardVideoPanel } from '~/types'
import { getPanelStoryboardVideo, type StoryboardVideoViewSharedProps } from './storyboardVideoViewShared'
import StoryboardVideoBlock from './StoryboardVideoBlock'

/** 卡片视图（原 StoryboardVideo.vue 模板 v-else-if="viewMode === 'card'" 分支） */
export function StoryboardVideoCardView(props: StoryboardVideoViewSharedProps) {
  const {
    panels,
    displayPanelTitle,
    editingId,
    editingTitle,
    onEditingTitleChange,
    onStartEditTitle,
    onFinishEditTitle,
    onCancelEditTitle,
    onOpenEditVideoModal,
    onCopyPanel,
    onRemovePanel,
    onJumpToScriptWithImageModal,
    onJumpToDubbingWithModal
  } = props

  function cardMenuItems(panel: StoryboardVideoPanel): MenuProps['items'] {
    const items: MenuProps['items'] = [
      { key: 'edit', icon: <EditOutlined />, label: '编辑分镜视频' },
      { key: 'copy', icon: <CopyOutlined />, label: '复制分镜' },
      { key: 'remove', icon: <DeleteOutlined />, label: '删除分镜', danger: true },
      { key: 'script', icon: <InfoCircleOutlined />, label: '分镜设计' }
    ]
    if (getPanelStoryboardVideo(panel)) {
      items.push({ key: 'dubbing', icon: <InfoCircleOutlined />, label: '音画同步' })
    }
    return items
  }

  function onCardMenuClick(key: string, index: number) {
    if (key === 'edit') onOpenEditVideoModal(index)
    else if (key === 'copy') onCopyPanel(index)
    else if (key === 'remove') onRemovePanel(index)
    else if (key === 'script') onJumpToScriptWithImageModal(index)
    else if (key === 'dubbing') onJumpToDubbingWithModal(index)
  }

  return (
    <div className="storyboard-cards">
      {panels.map((panel, index) => (
        <div key={panel.id} className="storyboard-card">
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
                items: cardMenuItems(panel),
                onClick: ({ key }) => onCardMenuClick(String(key), index)
              }}
            >
              <Button
                type="text"
                size="small"
                className="storyboard-card-more"
                onClick={(e) => e.stopPropagation()}
                icon={<MoreOutlined />}
              />
            </Dropdown>
          </div>
          <div className="storyboard-card-body">
            <div className="storyboard-block storyboard-block-video">
              <div className="storyboard-block-title">分镜视频：</div>
              <StoryboardVideoBlock
                panel={panel}
                index={index}
                isPanelVideoGenerating={props.isPanelVideoGenerating}
                panelVideoGenerateError={props.panelVideoGenerateError}
                playingPanelIndex={props.playingPanelIndex}
                panelVideoMediaReady={props.panelVideoMediaReady}
                setPanelVideoRef={props.setPanelVideoRef}
                onMarkPanelVideoMediaReady={props.onMarkPanelVideoMediaReady}
                onPanelVideoEnded={props.onPanelVideoEnded}
                onPanelVideoPause={props.onPanelVideoPause}
                onPlayPanelVideo={props.onPlayPanelVideo}
                onFullscreenPanelVideo={props.onFullscreenPanelVideo}
                onOpenEditVideoModal={props.onOpenEditVideoModal}
                onCancelStoryboardVideo={props.onCancelStoryboardVideo}
                onPreviewStoryboardVideo={props.onPreviewStoryboardVideo}
                onDownloadStoryboardVideo={props.onDownloadStoryboardVideo}
                onRegeneratePanel={props.onRegeneratePanel}
                resolveRegeneratePanelBillingRequest={props.resolveRegeneratePanelBillingRequest}
              />
            </div>
          </div>
        </div>
      ))}
    </div>
  )
}

export default StoryboardVideoCardView
