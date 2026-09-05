'use client'

/* eslint-disable @next/next/no-img-element -- 发送态图标由运行时资源映射器解析，需保留原生 img。 */

import { Tooltip } from 'antd'
import type { ReactNode } from 'react'
import btnSendDisabledRaw from '~/assets/img/home/btn-send-h.svg'
import groupIconRaw from '~/assets/img/home/group-icon.svg'
import btnSendRaw from '~/assets/img/home/btn-send.svg'
import { assetUrl } from '~/utils/assetUrl'
import {
  COMPOSER_SEND_EMPTY_TOOLTIP,
  isComposerSendClickDisabled,
  resolveComposerSendVisualState,
  type ComposerSendMode
} from '~/utils/composerSendButton'
import './composer-send-button.css'

const sendIconUrl = assetUrl(btnSendRaw)
const sendDisabledIconUrl = assetUrl(btnSendDisabledRaw)
const sendStopIconUrl = assetUrl(groupIconRaw)

export interface ComposerSendButtonProps {
  hasContent: boolean
  mode?: ComposerSendMode
  loading?: boolean
  generating?: boolean
  pauseDisabled?: boolean
  showResume?: boolean
  disabled?: boolean
  emptyTooltip?: string
  className?: string
  leadingContent?: ReactNode
  pauseTooltip?: string
  pauseBusyTooltip?: string
  pauseAriaLabel?: string
  resumeTooltip?: string
  resumeAriaLabel?: string
  onSend: () => void
  onPause?: () => void
  onResume?: () => void
}

export function ComposerSendButton({
  hasContent,
  mode = 'agent',
  loading = false,
  generating = false,
  pauseDisabled = false,
  showResume = false,
  disabled = false,
  emptyTooltip = COMPOSER_SEND_EMPTY_TOOLTIP,
  className = '',
  leadingContent,
  pauseTooltip = '暂停生成',
  pauseBusyTooltip = '正在暂停…',
  pauseAriaLabel = '暂停生成',
  resumeTooltip = '继续接收生成内容',
  resumeAriaLabel = '继续生成',
  onSend,
  onPause,
  onResume
}: ComposerSendButtonProps) {
  const visualState = resolveComposerSendVisualState({
    mode,
    hasContent,
    loading,
    generating,
    showResume
  })
  const clickDisabled = isComposerSendClickDisabled({
    visualState,
    disabled,
    pauseDisabled
  })

  const ariaLabel =
    visualState === 'loading'
      ? '正在创建项目'
      : visualState === 'pause'
        ? pauseDisabled
          ? '正在暂停生成'
          : pauseAriaLabel
        : visualState === 'resume'
          ? resumeAriaLabel
          : visualState === 'empty'
            ? '发送'
            : '发送'

  const buttonClassName = [
    'composer-send-button',
    visualState === 'resume' ? 'is-resume' : '',
    visualState === 'pause' ? 'is-pause' : '',
    visualState === 'active' && disabled ? 'is-active-disabled' : '',
    leadingContent ? 'has-leading-content' : '',
    className
  ]
    .filter(Boolean)
    .join(' ')

  const button = (
    <button
      type="button"
      className={buttonClassName}
      aria-label={ariaLabel}
      aria-busy={visualState === 'loading' ? true : undefined}
      disabled={clickDisabled}
      onClick={() => {
        if (visualState === 'pause') {
          onPause?.()
          return
        }
        if (visualState === 'resume') {
          onResume?.()
          return
        }
        onSend()
      }}
    >
      {leadingContent ? <span className="composer-send-button__leading">{leadingContent}</span> : null}
      {visualState === 'loading' ? (
        <span className="composer-send-button__spinner" aria-hidden="true" />
      ) : null}
      {visualState === 'pause' ? (
        <img src={sendStopIconUrl} alt="" width={32} height={32} />
      ) : null}
      {visualState === 'resume' ? (
        <img src={sendIconUrl} alt="" width={32} height={32} />
      ) : null}
      {visualState === 'empty' || visualState === 'active' ? (
        <>
          <span
            className={`composer-send-button__icon-layer ${
              visualState === 'empty' ? 'is-visible' : 'is-hidden'
            }`}
            aria-hidden="true"
          >
            <img src={sendDisabledIconUrl} alt="" width={32} height={32} />
          </span>
          <span
            className={`composer-send-button__icon-layer ${
              visualState === 'active' ? 'is-visible' : 'is-hidden'
            }`}
            aria-hidden="true"
          >
            <img src={sendIconUrl} alt="" width={32} height={32} />
          </span>
        </>
      ) : null}
    </button>
  )

  if (visualState === 'empty') {
    return (
      <Tooltip title={emptyTooltip}>
        <span className="composer-send-button__tooltip-wrap">{button}</span>
      </Tooltip>
    )
  }
  if (visualState === 'pause') {
    return <Tooltip title={pauseDisabled ? pauseBusyTooltip : pauseTooltip}>{button}</Tooltip>
  }
  if (visualState === 'resume') {
    return <Tooltip title={resumeTooltip}>{button}</Tooltip>
  }
  return button
}

export default ComposerSendButton
