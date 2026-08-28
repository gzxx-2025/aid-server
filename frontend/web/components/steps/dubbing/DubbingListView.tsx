'use client'

import { ReactSortable } from 'react-sortablejs'
import type { SortableEvent } from 'sortablejs'
import { Button, Input } from 'antd'
import { HolderOutlined, LoadingOutlined } from '@ant-design/icons'
import { ShimmerVideo } from '~/components/common/ShimmerVideo'
import type { DubbingPanel } from '~/types'
import type { DubbingViewSharedProps } from './dubbingViewShared'

export interface DubbingListViewProps extends DubbingViewSharedProps {
  listInteractive: boolean
  isDubbingDragging: boolean
  editingId: string | null
  editingTitle: string
  onEditingTitleChange: (v: string) => void
  onStartEditTitle: (panel: DubbingPanel) => void
  onFinishEditTitle: (panel: DubbingPanel) => void
  onCancelEditTitle: () => void
  onDragStart: () => void
  onDragEnd: () => void
  /** 原 vuedraggable @change moved：拖拽后 (from, to) 下标 */
  onListDragChange: (from: number, to: number) => void
}

/** 列表视图（原 Dubbing.vue 模板列表分支 + vuedraggable → react-sortablejs） */
export function DubbingListView({
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
  goToStoryboardVideo,
  listInteractive,
  isDubbingDragging,
  editingId,
  editingTitle,
  onEditingTitleChange,
  onStartEditTitle,
  onFinishEditTitle,
  onCancelEditTitle,
  onDragStart,
  onDragEnd,
  onListDragChange
}: DubbingListViewProps) {
  function handleSortEnd(evt: SortableEvent) {
    onDragEnd()
    const from = evt.oldIndex
    const to = evt.newIndex
    // vuedraggable 的 change.moved 仅在位置变化时触发
    if (from == null || to == null || from === to) return
    onListDragChange(from, to)
  }

  return (
    <div className="storyboard-list">
      {listInteractive && panels.length > 0 ? (
        <ReactSortable
          list={panels}
          setList={() => {
            /* 顺序由 onEnd → applyDubbingStepReorder 统一写 store，避免双写 */
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
          className={isDubbingDragging ? 'storyboard-list--dragging' : undefined}
          ghostClass="storyboard-list-item--ghost"
          chosenClass="storyboard-list-item--chosen"
          dragClass="storyboard-list-item--dragging"
          onStart={onDragStart}
          onEnd={handleSortEnd}
        >
          {panels.map((panel, index) => (
            <div
              key={panel.id}
              className="storyboard-list-item"
              onMouseEnter={() => onHoverIndexChange(index)}
              onMouseLeave={() => onHoverIndexChange(null)}
            >
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
                  <button className="storyboard-action-link" type="button" onClick={() => onGoStep(3)}>
                    分镜设计
                  </button>
                  <button className="storyboard-action-link" type="button" onClick={() => onGoStep(4)}>
                    视频生成
                  </button>
                  <Button size="small" onClick={() => onEditDubbing(index)}>
                    {isDubbingBatchGenerating(index) ? (
                      <LoadingOutlined spin className="dubbing-edit-btn-loading" />
                    ) : null}
                    <span>编辑分镜配音</span>
                  </Button>
                  <Button size="small" onClick={() => onCopyPanel(index)}>
                    复制分镜
                  </Button>
                  <Button size="small" danger onClick={() => onRemovePanel(index)}>
                    删除分镜
                  </Button>
                </div>
              </div>
              {/* 主体区：高度与分镜脚本/分镜视频列表行一致；遮罩铺满本区域（不含顶部标题与操作按钮） */}
              <div className="dubbing-list-body-shell">
                <div className="dubbing-list-body dubbing-list-body--compact">
                  <div className="dubbing-video-block">
                    <div className="storyboard-block-title">
                      音画同步：
                    </div>
                    <div
                      className={`dubbing-video-area dubbing-video-area--list${
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
                            className="dubbing-video-play-btn"
                            title="预览视频"
                            aria-label="预览视频"
                            onClick={(e) => {
                              e.stopPropagation()
                              onPreviewDubbingVideo(index)
                            }}
                          />
                        </>
                      ) : (
                        <div className="dubbing-video-placeholder dubbing-video-placeholder--list" />
                      )}
                    </div>
                  </div>
                  <div className="dubbing-info-block dubbing-info-block--list">
                    <div className="storyboard-block-title">台词：</div>
                    {isDubbingBatchGenerating(index) ? (
                      <div className="dubbing-skeleton">
                        <div className="dubbing-skeleton-line dubbing-skeleton-line--lg" />
                        <div className="dubbing-skeleton-line dubbing-skeleton-line--md" />
                        <div className="dubbing-skeleton-line dubbing-skeleton-line--sm" />
                      </div>
                    ) : (
                      <>
                        <div className="dubbing-dialogue-render">{getRenderedDialogue(index)}</div>
                        <div className="dubbing-meta dubbing-meta--inline">
                          <div className="dubbing-meta-item">
                            <span className="dubbing-meta-label">配音类型：</span>
                            <span className="dubbing-meta-value">
                              {panel.dubbingType || '旁白/画外音'}
                            </span>
                          </div>
                          <div className="dubbing-meta-item">
                            <span className="dubbing-meta-label">发言角色：</span>
                            <span className="dubbing-meta-value">{panel.speakerRole || '艾米'}</span>
                          </div>
                        </div>
                        <div className="dubbing-status dubbing-status--list">
                          <span className="dubbing-status-label">状态：</span>
                          <span
                            className={`dubbing-status-value ${
                              panel.status === 'done' ? 'done' : 'pending'
                            }`}
                          >
                            {panel.status === 'done' ? '已配音' : '未配音'}
                          </span>
                        </div>
                      </>
                    )}
                  </div>
                </div>
                {/* 无分镜视频时：鼠标移入铺满主体区的遮罩 */}
                <div
                  className={`dubbing-body-mask${
                    !hasVideoForIndex(index) && hoverIndex === index
                      ? ' dubbing-body-mask--visible'
                      : ''
                  }`}
                  onClick={(e) => {
                    e.stopPropagation()
                    goToStoryboardVideo()
                  }}
                >
                  <Button type="primary" className="dubbing-mask-btn dubbing-mask-btn--list">
                    前往视频生成
                  </Button>
                  <p className="dubbing-mask-tip">暂无分镜数据，添加视频后即可进行音画同步</p>
                </div>
              </div>
            </div>
          ))}
        </ReactSortable>
      ) : null}
    </div>
  )
}

export default DubbingListView
