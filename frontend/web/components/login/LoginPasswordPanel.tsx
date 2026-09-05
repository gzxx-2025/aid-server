'use client'

import { LoginAgreementHint } from './LoginAgreementHint'
import { LoginModalField } from './LoginModalField'
import {
  LOGIN_MODAL_AUX_ROW_CLASS,
  LOGIN_MODAL_FIELDS_CLASS,
  LOGIN_MODAL_STACK_CLASS,
  LOGIN_MODAL_SUBMIT_CLASS
} from '~/utils/loginModalLayout'

interface LoginPasswordPanelProps {
  account: string
  password: string
  loading: boolean
  showRegister: boolean
  termsOfServiceUrl?: string | null
  privacyPolicyUrl?: string | null
  onAccountChange: (value: string) => void
  onPasswordChange: (value: string) => void
  onSubmit: () => void
  onRegister: () => void
  onForgotPassword: () => void
  onUnlockAutofill: (e: React.SyntheticEvent) => void
}

export function LoginPasswordPanel({
  account,
  password,
  loading,
  showRegister,
  termsOfServiceUrl,
  privacyPolicyUrl,
  onAccountChange,
  onPasswordChange,
  onSubmit,
  onRegister,
  onForgotPassword,
  onUnlockAutofill
}: LoginPasswordPanelProps) {
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
          placeholder="请输入手机号或邮箱"
          onChange={onAccountChange}
          onUnlockAutofill={onUnlockAutofill}
        />
        <LoginModalField
          type="password"
          name="login-password"
          value={password}
          placeholder="请输入密码"
          onChange={onPasswordChange}
          onUnlockAutofill={onUnlockAutofill}
        />
      </div>
      <div className={LOGIN_MODAL_AUX_ROW_CLASS}>
        {showRegister && (
          <>
            <button type="button" className="cursor-pointer hover:opacity-80" onClick={onRegister}>
              免费注册
            </button>
            <span className="px-[0.2em]">｜</span>
          </>
        )}
        <button type="button" className="cursor-pointer hover:opacity-80" onClick={onForgotPassword}>
          忘记密码？
        </button>
      </div>
      <button type="submit" disabled={loading} className={LOGIN_MODAL_SUBMIT_CLASS}>
        登录
      </button>
      <LoginAgreementHint termsOfServiceUrl={termsOfServiceUrl} privacyPolicyUrl={privacyPolicyUrl} />
    </form>
  )
}
