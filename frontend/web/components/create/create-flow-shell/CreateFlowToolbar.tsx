'use client'

/**
 * 顶部工具栏（原 CreateFlowShell.vue toolbar 段拆分）：
 * 剧集上传 / 剧集分集列表 / 常规流程三种形态 + 预览步导出下拉。
 */

import { lazy, Suspense, type CSSProperties, type RefObject } from 'react'
import { Button, Dropdown, Input, Tooltip } from 'antd'
import { LeftOutlined } from '@ant-design/icons'
import { useCreationStore } from '~/stores/creation'
import type { BillingQuoteRequest, UserTaskRow } from '~/types/business-api'

const GlobalGenerateTaskPopover = lazy(() =>
  import('~/components/steps/GlobalGenerateTaskPopover').then((m) => ({
    default: m.GlobalGenerateTaskPopover
  }))
)

export interface CreateFlowToolbarProps {
  isSeriesScriptUpload: boolean
  isSeriesEpisodeList: boolean
  titleMeasureRef: RefObject<HTMLElement | null>
  titleMeasureText: string
  titleInputWrapStyle: CSSProperties
  syncTitleInputWidth: () => void
  onSeriesWorkTitleBlur: () => void
  goBack: () => void
  seriesEpisodeCountLabel: string
  seriesProjectConfigChecking: boolean
  onSeriesProjectConfigClick: () => void
  activeProjectId: number | null
  onGlobalTaskStop: (task: UserTaskRow) => void
  onGlobalTaskRestart: (task: UserTaskRow) => void
  resolveGlobalTaskRestartBillingRequest: (
    task: UserTaskRow
  ) => Promise<BillingQuoteRequest | null>
  onGlobalTaskResume: (task: UserTaskRow) => void
  resolveGlobalTaskResumeBillingRequest: (task: UserTaskRow) => BillingQuoteRequest | null
  openProjectGenConfig: () => void
  flowStepIndex: number
  globalSettingConfirmLoading: boolean
  onGlobalSettingSave: () => void
  saveDraftSubmitting: boolean
  toolbarPrimaryLoading: boolean
  saveDraft: () => void
  isPreviewStep: boolean
  exportMenuOpen: boolean
  onExportMenuOpenChange: (open: boolean) => void
  previewExportBusy: boolean
  onExportFullVideo: () => void
  onExportSegments: () => void
  publishToCasePlazaDisabled: boolean
  publishToCasePlazaTooltip: string
  getPublishTooltipPopupContainer: () => HTMLElement
  onPublishToCasePlaza: () => void
  toolbarPrimaryDisabled: boolean
  toolbarPrimaryTooltip: string
  nextStepDelayLoading: boolean
  toolbarPrimaryLabel: string
  onNextStepWithDelay: () => void
}

/** 作品标题输入框：v-model:value="creationStore.workTitle" 的受控等价 */
function WorkTitleInput(props: {
  titleMeasureRef: RefObject<HTMLElement | null>
  titleMeasureText: string
  titleInputWrapStyle: CSSProperties
  syncTitleInputWidth: () => void
  onSeriesWorkTitleBlur: () => void
}) {
  const workTitle = useCreationStore((s) => s.workTitle)
  return (
    <div className="toolbar-title-block">
      <div className="toolbar-title-input-wrap" style={props.titleInputWrapStyle}>
        <span
          ref={props.titleMeasureRef as RefObject<HTMLSpanElement>}
          className="title-input-measure"
          aria-hidden="true"
        >
          {props.titleMeasureText}
        </span>
        <Input
          value={workTitle}
          placeholder="作品名称"
          className="title-input"
          size="large"
          variant="borderless"
          maxLength={100}
          onBlur={props.onSeriesWorkTitleBlur}
          onChange={(e) => {
            useCreationStore.getState().setWorkTitle(e.target.value)
            props.syncTitleInputWidth()
          }}
        />
      </div>
    </div>
  )
}

export function CreateFlowToolbar(props: CreateFlowToolbarProps) {
  const titleInputProps = {
    titleMeasureRef: props.titleMeasureRef,
    titleMeasureText: props.titleMeasureText,
    titleInputWrapStyle: props.titleInputWrapStyle,
    syncTitleInputWidth: props.syncTitleInputWidth,
    onSeriesWorkTitleBlur: props.onSeriesWorkTitleBlur
  }

  if (props.isSeriesScriptUpload) {
    return (
      <div className="toolbar toolbar--series-upload">
        <div className="toolbar-left">
          <button type="button" className="back-btn" onClick={props.goBack}>
            <LeftOutlined />
          </button>
          <WorkTitleInput {...titleInputProps} />
        </div>
      </div>
    )
  }

  if (props.isSeriesEpisodeList) {
    return (
      <div className="toolbar toolbar--series-upload toolbar--series-episode-list">
        <div className="toolbar-left toolbar-left--wrap">
          <button type="button" className="back-btn" onClick={props.goBack}>
            <LeftOutlined />
          </button>
          <WorkTitleInput {...titleInputProps} />
          <span className="toolbar-series-episode-count">共{props.seriesEpisodeCountLabel}集</span>
        </div>
        <div className="toolbar-right">
          <Button
            size="large"
            className="toolbar-btn-draft"
            loading={props.seriesProjectConfigChecking}
            disabled={props.seriesProjectConfigChecking}
            onClick={props.onSeriesProjectConfigClick}
          >
            <div className="text-gradient">项目配置</div>
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="toolbar">
      <div className="toolbar-left">
        <button type="button" className="back-btn" onClick={props.goBack}>
          <LeftOutlined />
        </button>
        <WorkTitleInput {...titleInputProps} />
      </div>
      <div className="toolbar-right">
        <Suspense fallback={null}>
          <GlobalGenerateTaskPopover
            projectId={props.activeProjectId}
            onStop={props.onGlobalTaskStop}
            onRestart={props.onGlobalTaskRestart}
            resolveRestartBillingRequest={props.resolveGlobalTaskRestartBillingRequest}
            onResume={props.onGlobalTaskResume}
            resolveResumeBillingRequest={props.resolveGlobalTaskResumeBillingRequest}
          />
        </Suspense>
        {props.activeProjectId ? (
          <Button size="large" className="toolbar-btn-draft" onClick={props.openProjectGenConfig}>
            <div className="text-gradient">生成配置</div>
          </Button>
        ) : null}
        {props.flowStepIndex === 0 ? (
          <Button
            size="large"
            className="toolbar-btn-draft"
            loading={props.globalSettingConfirmLoading}
            onClick={props.onGlobalSettingSave}
          >
            <div className="text-gradient">保存</div>
          </Button>
        ) : null}
        {props.flowStepIndex === 1 ? (
          <Button
            size="large"
            className="toolbar-btn-draft"
            loading={props.saveDraftSubmitting}
            disabled={props.saveDraftSubmitting || props.toolbarPrimaryLoading}
            onClick={props.saveDraft}
          >
            <div className="text-gradient">存草稿</div>
          </Button>
        ) : null}
        {props.isPreviewStep ? (
          <Dropdown
            open={props.exportMenuOpen}
            trigger={['click']}
            placement="bottomRight"
            classNames={{ root: 'preview-export-dropdown-overlay' }}
            onOpenChange={props.onExportMenuOpenChange}
            popupRender={() => (
              <div className="preview-export-menu" role="menu" aria-label="选择导出为">
                <div className="preview-export-menu__title">选择导出为</div>
                <button
                  type="button"
                  className="preview-export-menu__btn"
                  role="menuitem"
                  disabled={props.previewExportBusy}
                  onClick={props.onExportFullVideo}
                >
                  <span className="text-gradient">导出完整视频</span>
                </button>
                <button
                  type="button"
                  className="preview-export-menu__btn"
                  role="menuitem"
                  disabled={props.previewExportBusy}
                  onClick={props.onExportSegments}
                >
                  <span className="preview-export-menu__btn-text">导出分段素材</span>
                </button>
                {props.publishToCasePlazaDisabled ? (
                  <Tooltip
                    title={props.publishToCasePlazaTooltip}
                    placement="left"
                    styles={{ root: { zIndex: 11000, maxWidth: '280px' } }}
                    getPopupContainer={props.getPublishTooltipPopupContainer}
                  >
                    <span className="preview-export-menu__btn-wrap">
                      <button type="button" className="preview-export-menu__btn" role="menuitem" disabled>
                        <span className="preview-export-menu__btn-text">发布至案例广场</span>
                      </button>
                    </span>
                  </Tooltip>
                ) : (
                  <button
                    type="button"
                    className="preview-export-menu__btn"
                    role="menuitem"
                    disabled={props.previewExportBusy}
                    onClick={props.onPublishToCasePlaza}
                  >
                    <span className="preview-export-menu__btn-text">发布至案例广场</span>
                  </button>
                )}
              </div>
            )}
          >
            <Button
              type="primary"
              size="large"
              className="toolbar-btn-export"
              disabled={props.previewExportBusy}
              loading={props.previewExportBusy}
            >
              导出/发布
            </Button>
          </Dropdown>
        ) : (
          <Tooltip title={props.toolbarPrimaryTooltip || undefined}>
            <span>
              <Button
                type="primary"
                size="large"
                disabled={props.toolbarPrimaryDisabled || props.nextStepDelayLoading}
                loading={props.toolbarPrimaryLoading}
                onClick={props.onNextStepWithDelay}
              >
                {props.toolbarPrimaryLabel}
              </Button>
            </span>
          </Tooltip>
        )}
      </div>
    </div>
  )
}
