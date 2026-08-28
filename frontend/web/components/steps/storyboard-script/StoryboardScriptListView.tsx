'use client'

import type { MutableRefObject } from 'react'
import { ReactSortable } from 'react-sortablejs'
import type { SortableEvent } from 'sortablejs'
import { Button, Input, Tooltip } from 'antd'
import { HolderOutlined, LoadingOutlined, PlusOutlined } from '@ant-design/icons'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { AssetCardCancelIcon } from '~/components/common/AssetCardCancelIcon'
import { BillingQuoteConfirm } from '~/components/common/BillingQuoteConfirm'
import { assetUrl } from '~/utils/assetUrl'
import iconPreviewRaw from '~/assets/img/icon/Preview.svg'
import iconReplaceRaw from '~/assets/img/icon/Replace.svg'
import iconDownloadRaw from '~/assets/img/icon/download.svg'
import pencilIconRaw from '~/assets/img/icon/pencil.svg'
import {
  displayPanelTitle,
  getPanelCoverImage,
  getPanelCoverImageIndex,
  getPanelReferencePreviewImages,
  getPanelReferencePreviewTitle,
  renderStoryboardScriptContent,
  type StoryboardPanel,
  type StoryboardScriptViewSharedProps
} from './storyboardScriptShared'

const iconPreview = assetUrl(iconPreviewRaw)
const iconReplace = assetUrl(iconReplaceRaw)
const iconDownload = assetUrl(iconDownloadRaw)
const pencilIcon = assetUrl(pencilIconRaw)

export interface StoryboardScriptListViewProps extends StoryboardScriptViewSharedProps {
  listInteractive: boolean
  isShotDragging: boolean
  onDragStart: () => void
  onDragEnd: () => void
  /** 原 vuedraggable @change moved：拖拽后 (from, to) 下标 */
  onListDragChange: (from: number, to: number) => void
  activeInsertSlot: number | null
  onInsertSlotEnter: (idx: number) => void
  onInsertSlotLeave: () => void
  onClearInsertSlotImmediate: () => void
  onInsertBlankPanelAt: (idx: number) => void
  onAddPanel: () => void
  onPreviewReferenceImages: (panel: StoryboardPanel) => void
  editScriptTooltipVisibleIndex: number | null
  onEditScriptTooltipOpenChange: (open: boolean, index: number) => void
  listRef: MutableRefObject<HTMLDivElement | null>
  bottomAddBarRef: MutableRefObject<HTMLDivElement | null>
}

/** 列表视图（原 StoryboardScript.vue 模板 .storyboard-list 分支 + vuedraggable → react-sortablejs） */
export function StoryboardScriptListView(props: StoryboardScriptListViewProps) {
  const {
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
    failedPanels,
    listInteractive,
    isShotDragging,
    onDragStart,
    onDragEnd,
    onListDragChange,
    activeInsertSlot,
    onInsertSlotEnter,
    onInsertSlotLeave,
    onClearInsertSlotImmediate,
    onInsertBlankPanelAt,
    onAddPanel,
    onPreviewReferenceImages,
    editScriptTooltipVisibleIndex,
    onEditScriptTooltipOpenChange,
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

  function renderEditScriptButton(index: number) {
    return (
      <Tooltip
        open={editScriptTooltipVisibleIndex === index}
        placement="bottom"
        title="点击编辑，修改该条分镜素材"
        classNames={{ root: 'storyboard-edit-script-tooltip' }}
        onOpenChange={(open) => onEditScriptTooltipOpenChange(open, index)}
      >
        <Button
          size="small"
          className={
            editScriptTooltipVisibleIndex === index
              ? 'storyboard-edit-script-btn--highlight'
              : undefined
          }
          onClick={() => onOpenStoryboardScriptModal(index)}
        >
          修改分镜脚本
        </Button>
      </Tooltip>
    )
  }

  function renderPanelItem(panel: StoryboardPanel, index: number) {
    const cover = getPanelCoverImage(panel)
    const generating = isPanelImageGenerating(panel)
    const referenceImages = getPanelReferencePreviewImages(panel)
    return (
      <div key={panel.id}>
        {/* 行首/行间插入：热区在「分镜脚本」列对齐，虚线贯穿整行 */}
        {index > 0 ? (
          <div
            className="storyboard-insert-gap"
            onMouseEnter={() => onInsertSlotEnter(index)}
            onMouseLeave={onInsertSlotLeave}
          >
            {/* 原 <Transition name="storyboard-insert-fade"> + v-show：CSS opacity 过渡等价 */}
            <div
              className={`storyboard-insert-ui${
                activeInsertSlot === index ? '' : ' storyboard-insert-ui--hidden'
              }`}
            >
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
        <div className="storyboard-list-item" data-panel-index={index}>
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
              {isProMode ? (
                <>
                  {renderEditScriptButton(index)}
                  <Button size="small" danger onClick={() => onRemovePanel(index)}>
                    删除分镜
                  </Button>
                </>
              ) : (
                <>
                  <button
                    className="storyboard-action-link"
                    type="button"
                    onClick={() => onJumpToVideoWithModal(index)}
                  >
                    视频生成
                  </button>
                  {renderEditScriptButton(index)}
                  <Button size="small" onClick={() => onOpenStoryboardImage(index)}>
                    编辑分镜图
                  </Button>
                  <Button size="small" onClick={() => onCopyPanel(index)}>
                    复制分镜
                  </Button>
                  <Button size="small" danger onClick={() => onRemovePanel(index)}>
                    删除分镜
                  </Button>
                </>
              )}
            </div>
          </div>
          <div
            className={`storyboard-list-body${
              isProMode ? ' storyboard-list-body--pro-script-only' : ''
            }`}
          >
            {!isProMode ? (
              <div
                className="storyboard-block storyboard-block-small"
                onMouseEnter={onClearInsertSlotImmediate}
              >
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
                      <img src={pencilIcon} alt="" />
                      <div className="storyboard-block-text">编辑分镜</div>
                      <div className="storyboard-block-sub">点击去创建此分镜</div>
                    </>
                  )}
                </div>
              </div>
            ) : null}
            {!isProMode ? (
              <div
                className="storyboard-block storyboard-block-small"
                onMouseEnter={onClearInsertSlotImmediate}
              >
                <div className="storyboard-block-title">参考图片：</div>
                {!referenceImages.length ? (
                  <div className="storyboard-reference-card">暂无参考图</div>
                ) : (
                  <div className="storyboard-block-card storyboard-reference-card has-image storyboard-reference-card--has-image">
                    <div className="storyboard-block-card-header">
                      <span className="storyboard-block-image-title">
                        {getPanelReferencePreviewTitle(panel)}
                        {referenceImages.length > 1 ? (
                          <span className="storyboard-reference-count">
                            共{referenceImages.length}张
                          </span>
                        ) : null}
                      </span>
                    </div>
                    <div
                      className="storyboard-block-image-wrap"
                      onClick={(e) => {
                        e.stopPropagation()
                        onPreviewReferenceImages(panel)
                      }}
                    >
                      <ShimmerImage
                        src={referenceImages[0]?.url || ''}
                        imgClass="storyboard-block-thumb"
                        objectFit="cover"
                        revealDirection="fade"
                      />
                    </div>
                    <div className="scene-card-image-footer asset-action-footer">
                      <Button
                        onClick={(e) => {
                          e.stopPropagation()
                          onPreviewReferenceImages(panel)
                        }}
                        icon={<img src={iconPreview} alt="" className="footer-action-icon" />}
                      >
                        预览
                      </Button>
                    </div>
                  </div>
                )}
              </div>
            ) : null}
            <div className="storyboard-block storyboard-block-script storyboard-script-insert-host">
              {index > 0 ? (
                <div
                  className="storyboard-script-insert-edge storyboard-script-insert-edge--top"
                  onMouseEnter={() => onInsertSlotEnter(index)}
                  onMouseLeave={onInsertSlotLeave}
                />
              ) : null}
              <div className="storyboard-block-title" onMouseEnter={onClearInsertSlotImmediate}>
                分镜脚本：
              </div>
              {panel.scriptContent ? (
                <div
                  className="storyboard-script-content"
                  onClick={() => onOpenStoryboardScriptModal(index)}
                  onMouseEnter={onClearInsertSlotImmediate}
                  dangerouslySetInnerHTML={{
                    __html: renderStoryboardScriptContent(panel.scriptContent)
                  }}
                />
              ) : (
                <div
                  className="storyboard-script-tip"
                  onClick={() => onOpenStoryboardScriptModal(index)}
                  onMouseEnter={onClearInsertSlotImmediate}
                >
                  可点击「修改分镜脚本」或「自动生成分镜」生成脚本
                </div>
              )}
              <div
                className="storyboard-script-insert-edge storyboard-script-insert-edge--bottom"
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
        <div className="storyboard-failed-list storyboard-failed-list--inline">
          {failedPanels.map((item, idx) => (
            <div key={`failed-inline-${item.id || idx}`} className="storyboard-failed-item">
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
      {listInteractive && panels.length > 0 ? (
        <ReactSortable
          list={panels}
          setList={() => {
            /* 顺序由 onEnd → applyShotReorder 统一写 store，避免双写 */
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
          className={isShotDragging ? 'storyboard-list--dragging' : undefined}
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
        <div
          className={`storyboard-insert-ui${
            activeInsertSlot === panels.length || panels.length > 0
              ? ''
              : ' storyboard-insert-ui--hidden'
          }`}
        >
          <div className="storyboard-insert-dash-line" aria-hidden="true" />
          <div
            className="storyboard-insert-label"
            onClick={(e) => {
              e.stopPropagation()
              onAddPanel()
            }}
          >
            <PlusOutlined />
            <div className="text-gradient">添加分镜</div>
          </div>
        </div>
      </div>
    </div>
  )
}

export default StoryboardScriptListView
