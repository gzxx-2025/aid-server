'use client'

import { SettingOutlined } from '@ant-design/icons'
import { Select,Tooltip } from 'antd'
import type { ReactNode } from 'react'
import type { SelectOption } from '~/utils/modelCapability'
import './GenerateModelConfigBlock.css'
export interface GenerateModelConfigBlockProps {
  /** listByFunc capability 驱动的比例选项；未传则使用默认三项 */
  aspectRatioOptions?: SelectOption<string>[]
  /** 张数选项 */
  countOptions?: SelectOption<number>[]
  /** 画质选项 */
  qualityOptions?: SelectOption<string>[]
  /** 视频模式：比例 / 时长 / 数量 / 画质 / 音频（由 capability + 字典驱动） */
  videoAspectRatioOptions?: SelectOption<string>[]
  videoDurationOptions?: SelectOption<string>[]
  videoCountOptions?: SelectOption<number>[]
  videoQualityOptions?: SelectOption<string>[]
  videoAudioOptions?: SelectOption<string>[]
  /** 为 false 时不展示时长（模型不支持） */
  showDuration?: boolean
  /** 为 false 时不展示音频（模型 capability.supportsAudio !== true） */
  showAudio?: boolean
  /** 时长选择框 hover tooltip（如「当前为推荐最优时长」） */
  durationTip?: string
  /** 为 false 时不渲染底部操作区，由父级固定在右栏底部（低分辨率下主按钮始终可见） */
  showAction?: boolean
  /** 是否展示区块标题「模型配置」 */
  showTitle?: boolean
  /** 是否展示各字段 label */
  showFieldLabels?: boolean
  /** 区块标题 */
  title?: string
  modelLabel?: string
  /** 参数行栅格列数（模型独占首行，其余参数按此列数换行） */
  paramColumns?: 2 | 3 | 4
  /** @deprecated 兼容旧用法，等同于 paramColumns */
  columns?: 2 | 3 | 4
  /** a-select 类名，与父级原样式一致 */
  selectClass?: string
  /**
   * scene：编辑场景图（下拉略高）
   * storyboard：编辑分镜图（紧凑下拉 + 模型槽位高度）
   */
  density?: 'scene' | 'storyboard'
  /** 配置模式：image 为生图；video 为生视频 */
  mode?: 'image' | 'video'
  /** 下拉菜单 popup class */
  selectPopupClassName?: string
  /** 下拉浮层挂载点。分镜视频必须挂 body，才能点中选项。 */
  getPopupContainer?: (triggerNode: HTMLElement) => HTMLElement
  /** 下拉开关；用于视频弹窗在打开时先让文本域失焦 */
  onSelectOpenChange?: (open: boolean) => void
  /** 是否展示 3K（分镜图消耗配置里有 3K） */
  showQuality3k?: boolean
  aspectRatio: string
  onAspectRatioChange?: (value: string) => void
  count: number
  onCountChange?: (value: number) => void
  quality: string
  onQualityChange?: (value: string) => void
  duration?: string
  onDurationChange?: (value: string) => void
  audio?: string
  onAudioChange?: (value: string) => void
  /** 原 #model 插槽 */
  modelSlot?: ReactNode
  /** 原 #action 插槽 */
  actionSlot?: ReactNode
}

const DEFAULT_ASPECT_RATIO_OPTIONS: SelectOption<string>[] = [
  { value: '16:9', label: '16:9' },
  { value: '9:16', label: '9:16' },
  { value: '1:1', label: '1:1' }
]
const DEFAULT_COUNT_OPTIONS: SelectOption<number>[] = [
  { value: 1, label: '1张' },
  { value: 2, label: '2张' },
  { value: 3, label: '3张' },
  { value: 4, label: '4张' }
]
const DEFAULT_QUALITY_OPTIONS: SelectOption<string>[] = [
  { value: '1k', label: '1K' },
  { value: '2k', label: '2K' },
  { value: '4k', label: '4K' }
]

const DEFAULT_VIDEO_ASPECT_OPTIONS: SelectOption<string>[] = [
  { value: '16:9', label: '16:9' },
  { value: '9:16', label: '9:16' },
  { value: '1:1', label: '1:1' }
]
const DEFAULT_VIDEO_DURATION_OPTIONS: SelectOption<string>[] = [
  { value: '5', label: '5 s' },
  { value: '10', label: '10 s' }
]
const DEFAULT_VIDEO_COUNT_OPTIONS: SelectOption<number>[] = [
  { value: 1, label: '1个' },
  { value: 2, label: '2个' },
  { value: 3, label: '3个' },
  { value: 4, label: '4个' }
]
const DEFAULT_VIDEO_QUALITY_OPTIONS: SelectOption<string>[] = [
  { value: '1080p', label: '1080P' },
  { value: '720p', label: '720P' }
]
const DEFAULT_VIDEO_AUDIO_OPTIONS: SelectOption<string>[] = [
  { value: 'silent', label: '无声视频' },
  { value: 'with_audio', label: '带音频' }
]

export function GenerateModelConfigBlock({
  aspectRatioOptions,
  countOptions,
  qualityOptions,
  videoAspectRatioOptions,
  videoDurationOptions,
  videoCountOptions,
  videoQualityOptions,
  videoAudioOptions,
  showDuration = true,
  showAudio = true,
  durationTip = '',
  showAction = true,
  showTitle = false,
  showFieldLabels = false,
  title = '模型配置',
  modelLabel = '模型版本',
  paramColumns = 3,
  columns = 3,
  selectClass = 'setting-select',
  density = 'scene',
  mode = 'image',
  selectPopupClassName = '',
  getPopupContainer,
  onSelectOpenChange,
  showQuality3k = false,
  aspectRatio,
  onAspectRatioChange,
  count,
  onCountChange,
  quality,
  onQualityChange,
  duration = '5',
  onDurationChange,
  audio = 'with_audio',
  onAudioChange,
  modelSlot,
  actionSlot
}: GenerateModelConfigBlockProps) {
  const resolvedParamColumns = paramColumns ?? columns ?? 3

  const aspectRatioSelectOptions = aspectRatioOptions?.length
    ? aspectRatioOptions
    : DEFAULT_ASPECT_RATIO_OPTIONS
  const countSelectOptions = countOptions?.length ? countOptions : DEFAULT_COUNT_OPTIONS
  const qualitySelectOptions = (() => {
    const base = qualityOptions?.length ? qualityOptions : DEFAULT_QUALITY_OPTIONS
    if (showQuality3k && !base.some((o) => o.value === '3k')) {
      return [...base, { value: '3k', label: '3K' }]
    }
    if (!showQuality3k) {
      return base.filter((o) => o.value !== '3k')
    }
    return base
  })()

  const videoAspectRatioSelectOptions = videoAspectRatioOptions?.length
    ? videoAspectRatioOptions
    : DEFAULT_VIDEO_ASPECT_OPTIONS
  const videoDurationSelectOptions = videoDurationOptions?.length
    ? videoDurationOptions
    : DEFAULT_VIDEO_DURATION_OPTIONS
  const videoCountSelectOptions = videoCountOptions?.length
    ? videoCountOptions
    : DEFAULT_VIDEO_COUNT_OPTIONS
  const videoQualitySelectOptions = videoQualityOptions?.length
    ? videoQualityOptions
    : DEFAULT_VIDEO_QUALITY_OPTIONS
  // 显式传入（含空数组）时不回退默认项，便于按 supportsAudio 隐藏选项
  const videoAudioSelectOptions =
    videoAudioOptions != null ? videoAudioOptions : DEFAULT_VIDEO_AUDIO_OPTIONS

  const selectPopupProps = {
    ...(getPopupContainer ? { getPopupContainer } : {}),
    ...(onSelectOpenChange ? { onOpenChange: onSelectOpenChange } : {}),
    ...(selectPopupClassName ? { classNames: { popup: { root: selectPopupClassName } } } : {})
  }

  return (
    <div
      className={`generate-model-config-block generate-model-config-block--${density}`}
    >
      {showTitle ? (
        <div className="model-config-title">
          <SettingOutlined />
          <span>{title}</span>
        </div>
      ) : null}

      <div className="generate-settings-stacked">
        <div className="setting-item setting-item--model">
          {showFieldLabels ? <label>{modelLabel}</label> : null}
          {modelSlot}
        </div>

        <div
          className={`generate-settings-params generate-settings-params--cols-${resolvedParamColumns}`}
        >
          {mode === 'video' ? (
            <>
              <div className="setting-item">
                {showFieldLabels ? <label>分辨率</label> : null}
                <Select
                  value={aspectRatio}
                  onChange={(v) => onAspectRatioChange?.(v)}
                  className={selectClass}
                  {...selectPopupProps}
                  disabled={!videoAspectRatioSelectOptions.length}
                  options={videoAspectRatioSelectOptions}
                />
              </div>
              {showDuration ? (
                <div className="setting-item">
                  {showFieldLabels ? <label>时长</label> : null}
                  <div className="setting-duration-select-wrap">
                    <Tooltip title={durationTip || undefined} placement="top">
                      <Select
                        value={duration}
                        onChange={(v) => onDurationChange?.(v)}
                        className={selectClass}
                        {...selectPopupProps}
                        disabled={!videoDurationSelectOptions.length}
                        options={videoDurationSelectOptions}
                      />
                    </Tooltip>
                  </div>
                </div>
              ) : null}
              <div className="setting-item">
                {showFieldLabels ? <label>数量</label> : null}
                <Select
                  value={count}
                  onChange={(v) => onCountChange?.(v)}
                  className={selectClass}
                  {...selectPopupProps}
                  disabled={!videoCountSelectOptions.length}
                  options={videoCountSelectOptions}
                />
              </div>
              <div className="setting-item">
                {showFieldLabels ? <label>画质</label> : null}
                <Select
                  value={quality}
                  onChange={(v) => onQualityChange?.(v)}
                  className={selectClass}
                  {...selectPopupProps}
                  disabled={!videoQualitySelectOptions.length}
                  options={videoQualitySelectOptions}
                />
              </div>
              {showAudio ? (
                <div className="setting-item">
                  {showFieldLabels ? <label>音频</label> : null}
                  <Select
                    value={audio}
                    onChange={(v) => onAudioChange?.(v)}
                    className={selectClass}
                    {...selectPopupProps}
                    disabled={!videoAudioSelectOptions.length}
                    options={videoAudioSelectOptions}
                  />
                </div>
              ) : null}
            </>
          ) : (
            <>
              <div className="setting-item">
                {showFieldLabels ? <label>分辨率</label> : null}
                <Select
                  value={aspectRatio}
                  onChange={(v) => onAspectRatioChange?.(v)}
                  className={selectClass}
                  {...selectPopupProps}
                  disabled={!aspectRatioSelectOptions.length}
                  options={aspectRatioSelectOptions}
                />
              </div>
              <div className="setting-item">
                {showFieldLabels ? <label>张数</label> : null}
                <Select
                  value={count}
                  onChange={(v) => onCountChange?.(v)}
                  className={selectClass}
                  {...selectPopupProps}
                  disabled={!countSelectOptions.length}
                  options={countSelectOptions}
                />
              </div>
              <div className="setting-item">
                {showFieldLabels ? <label>画质</label> : null}
                <Select
                  value={quality}
                  onChange={(v) => onQualityChange?.(v)}
                  className={selectClass}
                  {...selectPopupProps}
                  disabled={!qualitySelectOptions.length}
                  options={qualitySelectOptions}
                />
              </div>
            </>
          )}
        </div>
      </div>

      {showAction ? (
        <div className="generate-model-config-block__action">{actionSlot}</div>
      ) : null}
    </div>
  )
}

export default GenerateModelConfigBlock
