'use client'

import { Form } from 'antd'
import { ForgotPasswordField } from './ForgotPasswordField'
import {
  FORGOT_PASSWORD_CODE_BTN_CLASS,
  FORGOT_PASSWORD_FIELDS_CLASS,
  FORGOT_PASSWORD_FORM_CLASS,
  FORGOT_PASSWORD_SUBMIT_CLASS
} from '~/utils/forgotPasswordLayout'

const LABEL_CLASS = 'text-base font-normal text-white'
const ITEM_CLASS = '!mb-0 [&_.ant-form-item-label]:pb-3'

interface ForgotPasswordVerifyStepProps {
  account: string
  code: string
  accountPlaceholder: string
  loading: boolean
  sendCodeLoading: boolean
  sendCodeCountdown: number
  codeMaxLength: number
  onAccountChange: (value: string) => void
  onCodeChange: (value: string) => void
  onSendCode: () => void
  onNext: () => void
  onUnlockAutofill: (e: React.SyntheticEvent) => void
}

export function ForgotPasswordVerifyStep({
  account,
  code,
  accountPlaceholder,
  loading,
  sendCodeLoading,
  sendCodeCountdown,
  codeMaxLength,
  onAccountChange,
  onCodeChange,
  onSendCode,
  onNext,
  onUnlockAutofill
}: ForgotPasswordVerifyStepProps) {
  return (
    <Form
      layout="vertical"
      requiredMark={false}
      className={FORGOT_PASSWORD_FORM_CLASS}
      onFinish={onNext}
    >
      <div className={FORGOT_PASSWORD_FIELDS_CLASS}>
        <Form.Item label={<span className={LABEL_CLASS}>请输入账号</span>} className={ITEM_CLASS}>
          <ForgotPasswordField
            value={account}
            placeholder={accountPlaceholder || '手机号或邮箱'}
            onChange={onAccountChange}
            onUnlockAutofill={onUnlockAutofill}
          />
        </Form.Item>
        <Form.Item label={<span className={LABEL_CLASS}>验证码</span>} className={ITEM_CLASS}>
          <ForgotPasswordField
            value={code}
            placeholder="请输入验证码"
            maxLength={codeMaxLength}
            inputMode="numeric"
            onChange={onCodeChange}
            onUnlockAutofill={onUnlockAutofill}
            suffix={
              <button
                type="button"
                disabled={sendCodeLoading || sendCodeCountdown > 0}
                className={FORGOT_PASSWORD_CODE_BTN_CLASS}
                onClick={onSendCode}
              >
                {sendCodeCountdown > 0 ? `${sendCodeCountdown}s` : '获取验证码'}
              </button>
            }
          />
        </Form.Item>
      </div>
      <button type="submit" disabled={loading} className={FORGOT_PASSWORD_SUBMIT_CLASS}>
        下一步
      </button>
    </Form>
  )
}
