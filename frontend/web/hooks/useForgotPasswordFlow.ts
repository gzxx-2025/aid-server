'use client'

import { message } from 'antd'
import { useRouter } from 'next/navigation'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useAuthPublicConfig } from '@/hooks/useAuthPublicConfig'
import { useAuthSendCode } from '@/hooks/useAuthSendCode'
import { openLoginModal } from '@/stores/loginModal'
import {
  getCodeLoginPresentation,
  isValidCodeLoginTarget
} from '~/utils/authLoginMethods'
import { unlockAuthInputAutofill } from '~/utils/authUnlockAutofill'
import { authResetPassword } from '~/utils/businessApi'
import { loadTacScriptFallback } from '~/utils/tacAssets'
import {
  AUTH_PASSWORD_MAX_LENGTH,
  authPasswordIssueMessage,
  validateAuthPasswordChange
} from '~/utils/authPasswordPolicy'

export type ForgotPasswordStep = 1 | 2 | 3

export const FORGOT_PASSWORD_CAPTCHA_EL = '#forgot-password-captcha-box'
const SEND_CODE_SCOPE = 'forgot-password-send-code'

export function useForgotPasswordFlow() {
  const router = useRouter()
  const {
    smsLoginEnabled,
    emailLoginEnabled,
    loadPublicConfig,
    getCodeMaxLength
  } = useAuthPublicConfig()

  const [step, setStep] = useState<ForgotPasswordStep>(1)
  const [account, setAccount] = useState('')
  const [code, setCode] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const loadingRef = useRef(false)

  const sendCode = useAuthSendCode({
    scene: 'reset',
    countdownScope: SEND_CODE_SCOPE,
    captchaBindEl: FORGOT_PASSWORD_CAPTCHA_EL,
    account
  })

  const codeMaxLength = sendCode.activeChannel
    ? getCodeMaxLength(sendCode.activeChannel)
    : 6

  useEffect(() => {
    void loadTacScriptFallback()
    let cancelled = false
    void loadPublicConfig().then((cfg) => {
      if (cancelled || !cfg) return
      const enabled = getCodeLoginPresentation(
        cfg.login?.smsEnabled === true,
        cfg.login?.emailEnabled === true
      ).enabled
      if (!enabled) {
        message.warning('验证码登录未开启，无法找回密码')
        router.replace('/')
        openLoginModal({ tab: 'password', redirect: null })
      }
    })
    return () => {
      cancelled = true
    }
  }, [loadPublicConfig, router])

  const goVerifyNext = useCallback(() => {
    const target = account.trim()
    const channel = sendCode.activeChannel
    if (!channel) {
      message.error('验证码登录未开启')
      return
    }
    if (!target) {
      message.error(sendCode.codeLoginPresentation.accountPlaceholder)
      return
    }
    if (!isValidCodeLoginTarget(target, channel)) {
      message.error(channel === 'sms' ? '手机号格式不正确' : '邮箱格式不正确')
      return
    }
    if (!code.trim()) {
      message.error('请输入验证码')
      return
    }
    if (code.trim().length > codeMaxLength) {
      message.error('验证码格式不正确')
      return
    }
    setStep(2)
  }, [
    account,
    code,
    codeMaxLength,
    sendCode.activeChannel,
    sendCode.codeLoginPresentation.accountPlaceholder
  ])

  const goResetNext = useCallback(async () => {
    if (loadingRef.current || sendCode.isCaptchaOpening()) return
    const channel = sendCode.activeChannel
    if (!channel) {
      message.error('验证码登录未开启')
      return
    }
    const issue = validateAuthPasswordChange({
      newPassword,
      confirmPassword
    })
    if (issue) {
      message.error(authPasswordIssueMessage(issue))
      return
    }

    loadingRef.current = true
    setLoading(true)
    try {
      await authResetPassword({
        target: account.trim(),
        resetType: channel === 'sms' ? 'phone' : 'email',
        code: code.trim(),
        newPassword,
        confirmPassword
      })
      setNewPassword('')
      setConfirmPassword('')
      setCode('')
      setStep(3)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg ?? err?.message ?? '重置失败')
    } finally {
      loadingRef.current = false
      setLoading(false)
    }
  }, [account, code, confirmPassword, newPassword, sendCode])

  const goLogin = useCallback(() => {
    router.replace('/')
    openLoginModal({ tab: 'password', redirect: null })
  }, [router])

  return {
    step,
    account,
    setAccount,
    code,
    setCode,
    newPassword,
    setNewPassword,
    confirmPassword,
    setConfirmPassword,
    loading: loading || sendCode.captchaOpening,
    sendCodeLoading: sendCode.sendCodeLoading,
    sendCodeCountdown: sendCode.sendCodeCountdown,
    codeMaxLength,
    passwordMaxLength: AUTH_PASSWORD_MAX_LENGTH,
    accountPlaceholder:
      getCodeLoginPresentation(smsLoginEnabled, emailLoginEnabled).accountLabel || '手机号或邮箱',
    handleSendCode: sendCode.handleSendCode,
    goVerifyNext,
    goResetNext,
    goLogin,
    clearInputReadonly: unlockAuthInputAutofill
  }
}
