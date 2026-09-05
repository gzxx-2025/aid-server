'use client'

import { ConfigProvider } from 'antd'
import { ForgotPasswordDoneStep } from './ForgotPasswordDoneStep'
import { ForgotPasswordResetStep } from './ForgotPasswordResetStep'
import { ForgotPasswordStepper } from './ForgotPasswordStepper'
import { ForgotPasswordVerifyStep } from './ForgotPasswordVerifyStep'
import { FORGOT_PASSWORD_CAPTCHA_EL, useForgotPasswordFlow } from '@/hooks/useForgotPasswordFlow'
import { LOGIN_MODAL_ANTD_THEME } from '~/utils/loginModalAntdTheme'
import {
  FORGOT_PASSWORD_CARD_CLASS,
  FORGOT_PASSWORD_GLOW_CLASS,
  FORGOT_PASSWORD_PAGE_CLASS,
  FORGOT_PASSWORD_STACK_CLASS,
  FORGOT_PASSWORD_TITLE_CLASS
} from '~/utils/forgotPasswordLayout'

export function ForgotPasswordPage() {
  const flow = useForgotPasswordFlow()
  const captchaId = FORGOT_PASSWORD_CAPTCHA_EL.slice(1)

  return (
    <div className={FORGOT_PASSWORD_PAGE_CLASS}>
      <div className={FORGOT_PASSWORD_GLOW_CLASS} aria-hidden="true" />
      <div
        id={captchaId}
        className="absolute left-1/2 top-24 z-20 -translate-x-1/2"
        aria-hidden="true"
      />

      <div className={FORGOT_PASSWORD_STACK_CLASS}>
        <h1 className={FORGOT_PASSWORD_TITLE_CLASS}>找回密码</h1>
        <ForgotPasswordStepper step={flow.step} />

        <section className={FORGOT_PASSWORD_CARD_CLASS} aria-label="找回密码表单">
          <ConfigProvider theme={LOGIN_MODAL_ANTD_THEME} wave={{ disabled: true }}>
            {flow.step === 1 && (
              <ForgotPasswordVerifyStep
                account={flow.account}
                code={flow.code}
                accountPlaceholder={flow.accountPlaceholder}
                loading={flow.loading}
                sendCodeLoading={flow.sendCodeLoading}
                sendCodeCountdown={flow.sendCodeCountdown}
                codeMaxLength={flow.codeMaxLength}
                onAccountChange={flow.setAccount}
                onCodeChange={flow.setCode}
                onSendCode={() => void flow.handleSendCode()}
                onNext={flow.goVerifyNext}
                onUnlockAutofill={flow.clearInputReadonly}
              />
            )}
            {flow.step === 2 && (
              <ForgotPasswordResetStep
                newPassword={flow.newPassword}
                confirmPassword={flow.confirmPassword}
                loading={flow.loading}
                passwordMaxLength={flow.passwordMaxLength}
                onNewPasswordChange={flow.setNewPassword}
                onConfirmPasswordChange={flow.setConfirmPassword}
                onNext={() => void flow.goResetNext()}
                onUnlockAutofill={flow.clearInputReadonly}
              />
            )}
            {flow.step === 3 && <ForgotPasswordDoneStep onGoLogin={flow.goLogin} />}
          </ConfigProvider>
        </section>
      </div>
    </div>
  )
}

export default ForgotPasswordPage
