'use client'

import { message } from 'antd'
import { useRouter } from 'next/navigation'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useAuthPublicConfig } from '@/hooks/useAuthPublicConfig'
import { useAuthSendCode } from '@/hooks/useAuthSendCode'
import { useWechatLogin } from '@/components/login/useWechatLogin'
import { closeLoginModal, useLoginModalStore, type LoginModalTab } from '@/stores/loginModal'
import { useUserStore } from '@/stores/user'
import type { LoginData } from '~/types/business-api'
import { setAuthLoginChannel, type AuthLoginChannel } from '~/utils/authLoginChannel'
import { normalizeInviteCode, withLoginInviteCode } from '~/utils/authLoginInvite'
import {
  getCodeLoginPresentation,
  isValidCodeLoginTarget
} from '~/utils/authLoginMethods'
import { unlockAuthInputAutofill } from '~/utils/authUnlockAutofill'
import { authLogin } from '~/utils/businessApi'
import { loadTacScriptFallback } from '~/utils/tacAssets'
import { mapLoginDataToUser } from '~/utils/userProfile'

const LOGIN_SEND_CODE_COUNTDOWN_SCOPE = 'login-send-code'
const LOGIN_CAPTCHA_EL = '#login-captcha-box'

export function useLoginModalSession() {
  const router = useRouter()
  const tab = useLoginModalStore((s) => s.tab)
  const inviteCode = useLoginModalStore((s) => s.inviteCode)
  const redirect = useLoginModalStore((s) => s.redirect)
  const setTab = useLoginModalStore((s) => s.setTab)

  const {
    smsLoginEnabled,
    emailLoginEnabled,
    wechatLoginEnabled,
    siteName,
    platformLogoUrl,
    termsOfServiceUrl,
    privacyPolicyUrl,
    loadPublicConfig,
    getCodeMaxLength
  } = useAuthPublicConfig()

  const [account, setAccount] = useState('')
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const loadingRef = useRef(false)
  const readyRef = useRef(false)

  const authSend = useAuthSendCode({
    scene: 'login',
    countdownScope: LOGIN_SEND_CODE_COUNTDOWN_SCOPE,
    captchaBindEl: LOGIN_CAPTCHA_EL,
    account,
    inviteCode
  })

  const codeLoginPresentation = authSend.codeLoginPresentation
  const activeCodeLoginChannel = authSend.activeChannel
  const loginCodeMaxLength = activeCodeLoginChannel ? getCodeMaxLength(activeCodeLoginChannel) : 6

  const completeLoginRef = useRef<(data: LoginData, channel: AuthLoginChannel) => void>(() => {})

  const wechat = useWechatLogin({
    enabled: wechatLoginEnabled,
    getInviteCode: () => normalizeInviteCode(inviteCode),
    onLoginSuccess: (data) => completeLoginRef.current(data, 'wechat')
  })

  const completeLogin = useCallback(
    (data: LoginData, channel: AuthLoginChannel) => {
      setAuthLoginChannel(channel)
      const userStore = useUserStore.getState()
      userStore.login(mapLoginDataToUser(data, account.trim()), data.token)
      void useUserStore.getState().fetchProfile()
      message.success('登录成功', 2)
      const next = redirect && redirect !== '/' ? redirect : ''
      closeLoginModal()
      if (next) {
        setTimeout(() => router.replace(next), 0)
      }
    },
    [account, redirect, router]
  )
  completeLoginRef.current = completeLogin

  const handleCodeLogin = useCallback(async () => {
    const loginType = activeCodeLoginChannel
    if (!loginType) throw new Error('验证码登录未开启')
    if (!isValidCodeLoginTarget(account.trim(), loginType)) {
      throw new Error(loginType === 'sms' ? '手机号格式不正确' : '邮箱格式不正确')
    }
    const data = await authLogin(
      withLoginInviteCode(
        {
          loginType,
          account: account.trim(),
          code: code.trim()
        },
        normalizeInviteCode(inviteCode)
      )
    )
    completeLogin(data, loginType)
  }, [account, activeCodeLoginChannel, code, completeLogin, inviteCode])

  const handlePasswordLogin = useCallback(async () => {
    const data = await authSend.withCaptchaToken((captchaToken) =>
      authLogin(
        {
          loginType: 'password',
          account: account.trim(),
          password
        },
        captchaToken || undefined
      )
    )
    if (data) completeLogin(data, 'password')
  }, [account, authSend, completeLogin, password])

  const handleSubmit = useCallback(async () => {
    if (loadingRef.current || authSend.isCaptchaOpening()) return
    loadingRef.current = true
    setLoading(true)
    try {
      if (tab === 'code') await handleCodeLogin()
      else if (tab === 'password') await handlePasswordLogin()
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg ?? err?.message ?? '登录失败')
    } finally {
      loadingRef.current = false
      setLoading(false)
    }
  }, [authSend, handleCodeLogin, handlePasswordLogin, tab])

  useEffect(() => {
    void loadTacScriptFallback()
    let cancelled = false
    void loadPublicConfig().then((cfg) => {
      if (cancelled) return
      const codeEnabled = getCodeLoginPresentation(
        cfg?.login?.smsEnabled === true,
        cfg?.login?.emailEnabled === true
      ).enabled
      const wechatEnabled = cfg?.login?.wechatEnabled === true
      const current = useLoginModalStore.getState().tab
      if (current === 'code' && !codeEnabled) {
        setTab(wechatEnabled ? 'wechat' : 'password')
      } else if (current === 'wechat' && !wechatEnabled) {
        setTab(codeEnabled ? 'code' : 'password')
      }
      readyRef.current = true
      if (cfg?.login?.wechatEnabled === true && useLoginModalStore.getState().tab === 'wechat') {
        void wechat.openWechatLogin()
      }
    })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!readyRef.current) return
    if (tab === 'wechat' && wechatLoginEnabled) {
      void wechat.openWechatLogin()
      return
    }
    wechat.resetOnDisabled()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, wechatLoginEnabled])

  const selectTab = useCallback(
    (next: LoginModalTab) => {
      if (next === 'code' && !codeLoginPresentation.enabled) return
      if (next === 'wechat' && !wechatLoginEnabled) return
      setTab(next)
    },
    [codeLoginPresentation.enabled, setTab, wechatLoginEnabled]
  )

  return {
    tab,
    selectTab,
    account,
    setAccount,
    code,
    setCode,
    password,
    setPassword,
    loading: loading || authSend.captchaOpening,
    sendCodeLoading: authSend.sendCodeLoading,
    sendCodeCountdown: authSend.sendCodeCountdown,
    handleSendCode: authSend.handleSendCode,
    handleSubmit,
    clearLoginInputReadonly: unlockAuthInputAutofill,
    codeLoginPresentation,
    wechatLoginEnabled,
    wechat,
    loginCodeMaxLength,
    siteName,
    platformLogoUrl,
    termsOfServiceUrl,
    privacyPolicyUrl
  }
}
