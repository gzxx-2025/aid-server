'use client'

import { LoginAgreementHint } from './LoginAgreementHint'
import type { WechatLoginState } from './useWechatLogin'

interface LoginWechatPanelProps {
  state: WechatLoginState
  enabled: boolean
  termsOfServiceUrl?: string | null
  privacyPolicyUrl?: string | null
  onRefresh: () => void
}

export function LoginWechatPanel({
  state,
  enabled,
  termsOfServiceUrl,
  privacyPolicyUrl,
  onRefresh
}: LoginWechatPanelProps) {
  const { qrUrl, loading, status, qrExpired } = state

  if (!enabled) {
    return (
      <div className="flex w-3/4 max-w-sm min-w-0 flex-col items-center gap-8">
        <p className="text-sm text-[var(--login-modal-muted)]">
          扫码登录暂未开启
        </p>
      </div>
    )
  }

  return (
    <div className="flex w-3/4 max-w-sm min-w-0 flex-col items-center gap-8">
      <div className="relative size-52 overflow-hidden rounded bg-white">
        {qrUrl ? (
          <img src={qrUrl} alt="微信登录二维码" className="h-full w-full object-contain p-3" />
        ) : (
          <div className="flex h-full w-full items-center justify-center">
            <span className="size-8 animate-spin rounded-full border-2 border-[var(--login-modal-muted)] border-t-[var(--home-cyan)]" />
          </div>
        )}
        {status === 'SCANNED' && (
          <div className="absolute inset-0 flex items-center justify-center bg-black/55 px-3 text-center text-sm text-white">
            已扫码，登录处理中
          </div>
        )}
        {qrUrl && (qrExpired || status === 'FAIL' || status === 'EXPIRED') && (
          <button
            type="button"
            disabled={loading || status === 'SCANNED'}
            className="absolute inset-0 flex items-center justify-center bg-black/60 px-3 text-center text-sm text-white"
            onClick={onRefresh}
          >
            {loading ? '刷新中...' : status === 'FAIL' ? '登录失败，点击刷新' : '二维码已过期，点击刷新'}
          </button>
        )}
      </div>
      <LoginAgreementHint termsOfServiceUrl={termsOfServiceUrl} privacyPolicyUrl={privacyPolicyUrl} />
    </div>
  )
}
