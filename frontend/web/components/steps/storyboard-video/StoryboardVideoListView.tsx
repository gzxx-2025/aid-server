'use client'

import type { MutableRefObject } from 'react'
import { ReactSortable } from 'react-sortablejs'
import type { SortableEvent } from 'sortablejs'
import { Button, Input, Tooltip } from 'antd'
import { HolderOutlined, PlusOutlined } from '@ant-design/icons'
import type { StoryboardVideoPanel } from '~/types'
import { getPanelStoryboardVideo, type StoryboardVideoViewSharedProps } from './storyboardVideoViewShared'
import StoryboardVideoBlock from './StoryboardVideoBlock'

export interface StoryboardVideoListViewProps extends StoryboardVideoViewSharedProps {
  listInteractive: boolean
  isVideoDragging: boolean
  onDragStart: () => void
  onDragEnd: () => void
  /** 原 vuedraggable @change moved：拖拽后 (from, to) 下标 */
  onListDragChange: (from: number, to: number) => void
  /** 插入空白卡片交互 */
  activeInsertSlot: number | null
  onInsertSlotEnter: (idx: number) => void
  onInsertSlotLeave: () => void
  onClearInsertSlotImmediate: () => void
  onInsertBlankPanelAt: (atIndex: number) => void
  onAddPanel: () => void
  listRef: MutableRefObject<HTMLDivElement | null>
  bottomAddBarRef: MutableRefObject<HTMLDivElement | null>
}

/** 列表视图（原 StoryboardVideo.vue 模板列表分支 + vuedraggable → react-sortablejs） */
export function StoryboardVideoListView(props: StoryboardVideoListViewProps) {
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
    onJumpToDubbingWithModal,
    listInteractive,
    isVideoDragging,
    onDragStart,
    onDragEnd,
    onListDragChange,
    activeInsertSlot,
    onInsertSlotEnter,
    onInsertSlotLeave,
    onClearInsertSlotImmediate,
    onInsertBlankPanelAt,
    onAddPanel,
    listRef,
    bottomAddBarRef
  } = props

  function handleSortEnd(evt: SortableEvent) {
    onDragEnd()
    const from = evt.oldIndex
    const to = evt.newIndex
    // vuedraggable 的 change.moved 仅在位置变化时触发
    if (from == null || to == null || from === to) return
    onListDragChange(from, to)
  }

  /** 原 <Transition name="storyboard-insert-fade"> + v-show：常驻渲染，靠 CSS 类淡入淡出 */
  function insertUiClass(visible: boolean): string {
    return `storyboard-insert-ui${visible ? '' : ' storyboard-insert-ui--fade-hidden'}`
  }

  function renderPanelItem(panel: StoryboardVideoPanel, index: number) {
    return (
      <div key={panel.id}>
        {index > 0 ? (
          <div
            className="storyboard-insert-gap"
            onMouseEnter={() => onInsertSlotEnter(index)}
            onMouseLeave={onInsertSlotLeave}
          >
            <div className={insertUiClass(activeInsertSlot === index)}>
              <div className="storyboard-insert-dash-line" aria-hidden="true" />
              <Tooltip title="插入空白卡片">
                <button
                  type="button"
                  className="storyboard-insert-plus"
                  aria-label="插入空白卡片"
                  onClick={(e) => {
                    e.stopPropagation()
                    onInsertBlankPanelAt(index)
                  }}
                >
                  <PlusOutlined />
                </button>
              </Tooltip>
              <span className="storyboard-insert-label">插入空白卡片</span>
            </div>
          </div>
        ) : null}
        <div className="storyboard-list-item">
          <div className="storyboard-list-header">
            <span
              className="storyboard-drag-handle"
              aria-label="拖动排序"
              title="拖动排序"
              onClick={(e) => e.stopPropagation()}
            >
              <HolderOutlined />
            </span>
            <div className="storyboard-list-title" onClick={() => onStartEditTitle(panel)}>
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
                <span className="storyboard-title-text">{displayPanelTitle(panel, index)}</span>
              )}
            </div>
            <div className="storyboard-list-actions">
              <button
                className="storyboard-action-link"
                type="button"
                onClick={() => onJumpToScriptWithImageModal(index)}
              >
                分镜设计
              </button>
              {getPanelStoryboardVideo(panel) ? (
                <button
                  className="storyboard-action-link"
                  type="button"
                  onClick={() => onJumpToDubbingWithModal(index)}
                >
                  音画同步
                </button>
              ) : null}
              <Button size="small" onClick={() => onOpenEditVideoModal(index)}>
                编辑分镜视频
              </Button>
              <Button size="small" onClick={() => onCopyPanel(index)}>
                复制分镜
              </Button>
              <Button size="small" danger onClick={() => onRemovePanel(index)}>
                删除分镜
              </Button>
            </div>
          </div>
          <div className="storyboard-list-body">
            <div className="storyboard-block storyboard-block-video storyboard-video-insert-host">
              {index > 0 ? (
                <div
                  className="storyboard-video-insert-edge storyboard-video-insert-edge--top"
                  onMouseEnter={() => onInsertSlotEnter(index)}
                  onMouseLeave={onInsertSlotLeave}
                />
              ) : null}
              <div className="storyboard-video-insert-mid" onMouseEnter={onClearInsertSlotImmediate}>
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
              <div
                className="storyboard-video-insert-edge storyboard-video-insert-edge--bottom"
                onMouseEnter={() => onInsertSlotEnter(index + 1)}
                onMouseLeave={onInsertSlotLeave}
              />
            </div>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div ref={listRef} className="storyboard-list">
      {listInteractive && panels.length > 0 ? (
        <ReactSortable
          list={panels}
          setList={() => {
            /* 顺序由 onEnd → applyVideoStepReorder 统一写 store，避免双写 */
          }}
          handle=".storyboard-drag-handle"
          animation={180}
          forceFallback
          fallbackOnBody={false}
          scroll
          bubbleScroll
          scrollSensitivity={90}
          scrollSpeed={16}
          fallbackClass="storyboard-list-item--fallback"
          className={isVideoDragging ? 'storyboard-list--dragging' : undefined}
          ghostClass="storyboard-list-item--ghost"
          chosenClass="storyboard-list-item--chosen"
          dragClass="storyboard-list-item--dragging"
          onStart={onDragStart}
          onEnd={handleSortEnd}
        >
          {panels.map((panel, index) => renderPanelItem(panel, index))}
        </ReactSortable>
      ) : null}
      <div
        className="storyboard-insert-gap"
        ref={bottomAddBarRef}
        onMouseEnter={() => onInsertSlotEnter(panels.length)}
        onMouseLeave={onInsertSlotLeave}
      >
        <div className={insertUiClass(activeInsertSlot === panels.length || panels.length > 0)}>
          <div className="storyboard-insert-dash-line" aria-hidden="true" />
          <div
            className="storyboard-insert-label"
            onClick={(e) => {
              e.stopPropagation()
              onAddPanel()
            }}
          >
            <PlusOutlined />
            <div className="text-gradient">添加分镜视频</div>
          </div>
        </div>
      </div>
    </div>
  )
}

export default StoryboardVideoListView
