'use client'

import { LoginAgreementHint } from './LoginAgreementHint'
import { LoginModalField } from './LoginModalField'
import {
  LOGIN_MODAL_AUX_ROW_CLASS,
  LOGIN_MODAL_FIELDS_CLASS,
  LOGIN_MODAL_STACK_CLASS,
  LOGIN_MODAL_SUBMIT_CLASS
} from '~/utils/loginModalLayout'

interface LoginCodePanelProps {
  account: string
  code: string
  accountPlaceholder: string
  registrationHint: string
  loading: boolean
  sendCodeLoading: boolean
  sendCodeCountdown: number
  codeMaxLength: number
  termsOfServiceUrl?: string | null
  privacyPolicyUrl?: string | null
  onAccountChange: (value: string) => void
  onCodeChange: (value: string) => void
  onSendCode: () => void
  onSubmit: () => void
  onUnlockAutofill: (e: React.SyntheticEvent) => void
}

export function LoginCodePanel({
  account,
  code,
  accountPlaceholder,
  registrationHint,
  loading,
  sendCodeLoading,
  sendCodeCountdown,
  codeMaxLength,
  termsOfServiceUrl,
  privacyPolicyUrl,
  onAccountChange,
  onCodeChange,
  onSendCode,
  onSubmit,
  onUnlockAutofill
}: LoginCodePanelProps) {
  return (
    <form
      className={LOGIN_MODAL_STACK_CLASS}
      onSubmit={(e) => {
        e.preventDefault()
        onSubmit()
      }}
    >
      <div className={LOGIN_MODAL_FIELDS_CLASS}>
        <LoginModalField
          name="login-account"
          value={account}
          placeholder={accountPlaceholder}
          onChange={onAccountChange}
          onUnlockAutofill={onUnlockAutofill}
        />
        <LoginModalField
          name="login-code"
          inputMode="numeric"
          maxLength={codeMaxLength}
          value={code}
          placeholder="请输入验证码"
          onChange={onCodeChange}
          onUnlockAutofill={onUnlockAutofill}
          suffix={
            <button
              type="button"
              disabled={sendCodeLoading || sendCodeCountdown > 0}
              className="cursor-pointer text-sm text-[var(--login-modal-muted)] transition-colors hover:text-white disabled:cursor-not-allowed disabled:opacity-60"
              onClick={onSendCode}
            >
              {sendCodeCountdown > 0 ? `${sendCodeCountdown}s` : '获取验证码'}
            </button>
          }
        />
      </div>
      <div className={LOGIN_MODAL_AUX_ROW_CLASS} aria-hidden="true" />
      <button type="submit" disabled={loading} className={LOGIN_MODAL_SUBMIT_CLASS}>
        登录
      </button>
      <LoginAgreementHint
        extra={registrationHint}
        termsOfServiceUrl={termsOfServiceUrl}
        privacyPolicyUrl={privacyPolicyUrl}
      />
    </form>
  )
}
