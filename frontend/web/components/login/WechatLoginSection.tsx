'use client'

import type { WechatLoginPresentation } from '~/utils/authLoginMethods'
import type { WechatLoginState } from './useWechatLogin'

export interface WechatLoginSectionProps {
  presentation: WechatLoginPresentation
  state: WechatLoginState
  onRefresh: () => void
}

/**
 * 登录页右侧微信扫码区：二维码 / 加载动画 / 已扫码遮罩 / 过期刷新遮罩 / 底部状态 / 未开启占位。
 */
export function WechatLoginSection({ presentation, state, onRefresh }: WechatLoginSectionProps) {
  const { qrUrl, loading, status, statusMessage, qrExpired } = state
  const statusText = statusMessage || ''

  /**
   * 底部状态区：仅展示二维码区域内尚未传达的信息。
   * 加载中 / 已扫码 / 过期·失败且遮罩可点刷新 时，文案已在二维码上，底部不再重复。
   */
  const showBottomStatus = (() => {
    if (status === 'WAITING' || status === 'LOADING' || status === 'SCANNED') return false
    if ((status === 'EXPIRED' || status === 'FAIL') && qrUrl) return false
    return true
  })()

  if (!presentation.enabled) {
    return (
      <section className="wechat-section">
        <p className="wechat-title">{presentation.title}</p>
        <div className="wechat-disabled" role="status" aria-live="polite">
          <div className="wechat-disabled__icon" aria-hidden="true">
            <span />
            <span />
            <span />
            <span />
          </div>
          <p className="wechat-disabled__title">扫码登录暂未开启</p>
          <p className="wechat-disabled__description">{presentation.description}</p>
        </div>
      </section>
    )
  }

  const qrWrapClass = [
    'wechat-qr-wrap',
    !qrUrl ? 'is-loading' : '',
    qrExpired ? 'is-expired' : '',
    status === 'SCANNED' ? 'is-scanned' : ''
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <section className="wechat-section">
      <p className="wechat-title">{presentation.title}</p>
      <div className={qrWrapClass}>
        {qrUrl ? (
          <img src={qrUrl} alt="微信登录二维码" className="wechat-qr" />
        ) : (
          <div className="wechat-loading">
            <div className="qr-loading-ring">
              <div className="qr-loading-spinner" />
              <div className="qr-loading-inner">
                <div className="qr-loading-mini-qr">
                  <div className="mini-square top-left" />
                  <div className="mini-square top-right" />
                  <div className="mini-square bottom-left" />
                  <div className="mini-square bottom-right" />
                </div>
              </div>
            </div>
            <div className="qr-loading-text">正在获取二维码…</div>
            <div className="qr-loading-subtext">{presentation.description}</div>
          </div>
        )}
        {status === 'SCANNED' && (
          <div className="wechat-scanned-overlay">
            <span className="wechat-status-spinner" aria-hidden="true" />
            <span>已扫码，登录处理中</span>
          </div>
        )}
        {qrUrl && (
          <button
            type="button"
            className="wechat-qr-refresh-overlay"
            disabled={loading || status === 'SCANNED' || status === 'SUCCESS'}
            aria-busy={loading}
            onClick={onRefresh}
          >
            <span className="wechat-qr-refresh-overlay__text">
              {loading
                ? '刷新中...'
                : status === 'FAIL'
                  ? '登录失败，点击刷新'
                  : qrExpired
                    ? '二维码已过期，点击刷新'
                    : '刷新二维码'}
            </span>
          </button>
        )}
      </div>
      {showBottomStatus && (
        <div
          className={`wechat-status is-${status.toLowerCase()}`}
          role="status"
          aria-live="polite"
        >
          <span className="wechat-status__indicator" aria-hidden="true">
            {(status === 'LOADING' || status === 'SCANNED') && (
              <span className="wechat-status-spinner" />
            )}
          </span>
          <span className="wechat-status__text">{statusText}</span>
          {(status === 'EXPIRED' || status === 'FAIL') && (
            <button className="wechat-status__retry" type="button" onClick={onRefresh}>
              重新获取
            </button>
          )}
        </div>
      )}
    </section>
  )
}
