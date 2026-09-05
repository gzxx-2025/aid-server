'use client'

import { message } from 'antd'
import { useCallback, useEffect, useRef, useState } from 'react'
import { usePersistedCountdown } from '@/components/login/usePersistedCountdown'
import { useAuthPublicConfig } from '@/hooks/useAuthPublicConfig'
import { useBehaviorCaptcha } from '@/hooks/useBehaviorCaptcha'
import {
  getCodeLoginPresentation,
  isValidCodeLoginTarget,
  resolveCodeLoginChannel
} from '~/utils/authLoginMethods'
import { authSendCode } from '~/utils/businessApi'
import { clearPendingCaptchaToken, setPendingCaptchaToken } from '~/utils/captchaToken'
import { normalizeInviteCode } from '~/utils/authLoginInvite'

export type AuthSendCodeScene = 'login' | 'reset'

export interface UseAuthSendCodeOptions {
  scene: AuthSendCodeScene
  countdownScope: string
  captchaBindEl: string
  account: string
  /** 仅 login 场景携带 */
  inviteCode?: string
}

/**
 * 登录 / 找回密码共用：行为验证码 + 发码 + 持久倒计时。
 * 页面只负责传入 account / scene，不复制发码流程。
 */
export function useAuthSendCode({
  scene,
  countdownScope,
  captchaBindEl,
  account,
  inviteCode = ''
}: UseAuthSendCodeOptions) {
  const {
    captchaEnabled,
    captchaType,
    smsLoginEnabled,
    emailLoginEnabled,
    getSendCodeIntervalSeconds
  } = useAuthPublicConfig()

  const {
    opening: captchaOpening,
    isOpening: isCaptchaOpening,
    openBehaviorCaptcha,
    destroyActive: destroyCaptcha
  } = useBehaviorCaptcha()

  const countdown = usePersistedCountdown(countdownScope)
  const [sendCodeLoading, setSendCodeLoading] = useState(false)
  const sendCodeLoadingRef = useRef(false)

  const codeLoginPresentation = getCodeLoginPresentation(smsLoginEnabled, emailLoginEnabled)
  const activeChannel = resolveCodeLoginChannel(account.trim(), smsLoginEnabled, emailLoginEnabled)

  useEffect(() => {
    countdown.restore(account.trim())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [account, scene])

  useEffect(() => {
    return () => {
      countdown.stop()
      destroyCaptcha()
      clearPendingCaptchaToken()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const withCaptchaToken = useCallback(
    async <T,>(action: (captchaToken: string) => Promise<T>): Promise<T | null> => {
      if (!captchaEnabled) return action('')
      if (isCaptchaOpening()) return null
      let result: T | null = null
      const captchaResult = await openBehaviorCaptcha({
        bindEl: captchaBindEl,
        captchaType,
        onSuccess: async (token) => {
          setPendingCaptchaToken(token)
          result = await action(token)
        }
      })
      if (captchaResult.error) throw captchaResult.error
      if (!captchaResult.ok) return null
      return result
    },
    [captchaBindEl, captchaEnabled, captchaType, isCaptchaOpening, openBehaviorCaptcha]
  )

  const handleSendCode = useCallback(async () => {
    if (sendCodeLoadingRef.current || countdown.remaining > 0 || isCaptchaOpening()) return
    const target = account.trim()
    const channel = activeChannel
    if (!channel) {
      message.error('验证码登录未开启')
      return
    }
    if (!target) {
      message.error(codeLoginPresentation.accountPlaceholder)
      return
    }
    if (!isValidCodeLoginTarget(target, channel)) {
      message.error(channel === 'sms' ? '手机号格式不正确' : '邮箱格式不正确')
      return
    }

    sendCodeLoadingRef.current = true
    setSendCodeLoading(true)
    try {
      const sent = await withCaptchaToken(async (captchaToken) => {
        await authSendCode(
          {
            target,
            codeType: channel,
            scene,
            inviteCode: scene === 'login' ? normalizeInviteCode(inviteCode) : undefined
          },
          captchaToken || undefined
        )
        return true
      })
      if (!sent) return
      message.success('验证码已发送')
      countdown.start(target, getSendCodeIntervalSeconds(channel))
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg ?? err?.message ?? '发送验证码失败')
    } finally {
      sendCodeLoadingRef.current = false
      setSendCodeLoading(false)
    }
  }, [
    account,
    activeChannel,
    codeLoginPresentation.accountPlaceholder,
    countdown,
    getSendCodeIntervalSeconds,
    inviteCode,
    isCaptchaOpening,
    scene,
    withCaptchaToken
  ])

  return {
    activeChannel,
    codeLoginPresentation,
    sendCodeLoading: sendCodeLoading || captchaOpening,
    sendCodeCountdown: countdown.remaining,
    handleSendCode,
    withCaptchaToken,
    isCaptchaOpening,
    captchaOpening,
    destroyCaptcha
  }
}
