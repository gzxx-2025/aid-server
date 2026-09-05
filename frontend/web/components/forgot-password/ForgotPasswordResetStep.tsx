'use client'

import { Form } from 'antd'
import { ForgotPasswordField } from './ForgotPasswordField'
import {
  FORGOT_PASSWORD_FIELDS_CLASS,
  FORGOT_PASSWORD_FORM_CLASS,
  FORGOT_PASSWORD_SUBMIT_CLASS
} from '~/utils/forgotPasswordLayout'
import {
  AUTH_PASSWORD_MAX_LENGTH,
  AUTH_PASSWORD_RULE_HINT
} from '~/utils/authPasswordPolicy'

const LABEL_CLASS = 'text-base font-normal text-white'
const ITEM_CLASS = '!mb-0 [&_.ant-form-item-label]:pb-3'
const HINT_CLASS = 'm-0 mt-2 text-xs leading-5 text-[var(--home-muted,#8e97a5)]'

interface ForgotPasswordResetStepProps {
  newPassword: string
  confirmPassword: string
  loading: boolean
  passwordMaxLength?: number
  onNewPasswordChange: (value: string) => void
  onConfirmPasswordChange: (value: string) => void
  onNext: () => void
  onUnlockAutofill: (e: React.SyntheticEvent) => void
}

export function ForgotPasswordResetStep({
  newPassword,
  confirmPassword,
  loading,
  passwordMaxLength = AUTH_PASSWORD_MAX_LENGTH,
  onNewPasswordChange,
  onConfirmPasswordChange,
  onNext,
  onUnlockAutofill
}: ForgotPasswordResetStepProps) {
  return (
    <Form
      layout="vertical"
      requiredMark={false}
      className={FORGOT_PASSWORD_FORM_CLASS}
      onFinish={onNext}
    >
      <div className={FORGOT_PASSWORD_FIELDS_CLASS}>
        <Form.Item label={<span className={LABEL_CLASS}>设置新密码</span>} className={ITEM_CLASS}>
          <ForgotPasswordField
            type="password"
            value={newPassword}
            placeholder="输入新密码"
            maxLength={passwordMaxLength}
            autoComplete="new-password"
            showPasswordToggle
            onChange={onNewPasswordChange}
            onUnlockAutofill={onUnlockAutofill}
          />
          <p className={HINT_CLASS}>{AUTH_PASSWORD_RULE_HINT}</p>
        </Form.Item>
        <Form.Item label={<span className={LABEL_CLASS}>确认新密码</span>} className={ITEM_CLASS}>
          <ForgotPasswordField
            type="password"
            value={confirmPassword}
            placeholder="再次输入新密码"
            maxLength={passwordMaxLength}
            autoComplete="new-password"
            showPasswordToggle
            onChange={onConfirmPasswordChange}
            onUnlockAutofill={onUnlockAutofill}
          />
        </Form.Item>
      </div>
      <button type="submit" disabled={loading} className={FORGOT_PASSWORD_SUBMIT_CLASS}>
        下一步
      </button>
    </Form>
  )
}
