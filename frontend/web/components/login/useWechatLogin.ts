'use client'

import { message } from 'antd'
import { useCallback,useEffect,useRef,useState } from 'react'
import type { LoginData } from '~/types/business-api'
import { wechatLoginCheck,wechatLoginQrcode } from '~/utils/businessApi'

export type WechatUiStatus = 'LOADING' | 'WAITING' | 'SCANNED' | 'SUCCESS' | 'EXPIRED' | 'FAIL'

export interface WechatLoginState {
  qrUrl: string
  loading: boolean
  status: WechatUiStatus
  statusMessage: string
  /** 二维码已过期：遮罩常显，引导点击刷新（对齐 /auth/wechat/check 的 EXPIRED） */
  qrExpired: boolean
}

type WechatCheckPayload = {
  msg?: string
  data?: LoginData & { status?: string; expireSeconds?: number }
}

function parseWechatCheckPayload(input: unknown): WechatCheckPayload | null {
  const payload = input as WechatCheckPayload
  return payload?.data?.status ? payload : null
}

function isWechatQrExpiredPayload(err: unknown): boolean {
  const data = err as { msg?: string; message?: string; data?: { status?: string } }
  const status = String(data?.data?.status || '').toUpperCase()
  if (status === 'EXPIRED') return true
  const m = String(data?.msg ?? data?.message ?? '')
  return m.includes('失效') || m.includes('过期')
}

export interface UseWechatLoginOptions {
  /** 微信扫码登录是否开启（读公开配置） */
  enabled: boolean
  /** 读取当前规范化后的邀请码（拉码时绑定进扫码会话） */
  getInviteCode: () => string | undefined
  /** SUCCESS 且带 token/userInfo 时回调（走统一 completeLogin） */
  onLoginSuccess: (data: LoginData) => void
}

/**
 * 微信扫码登录：拉二维码 + 2s 轮询 /auth/wechat/check + 本地过期倒计时。
 * 会话号（session）保证刷新/切换后旧轮询回调全部失效。
 */
export function useWechatLogin(options: UseWechatLoginOptions) {
  const [state, setState] = useState<WechatLoginState>({
    qrUrl: '',
    loading: false,
    status: 'LOADING',
    statusMessage: '正在获取二维码',
    qrExpired: false
  })
  const stateRef = useRef(state)

  const enabledRef = useRef(options.enabled)
  enabledRef.current = options.enabled
  const getInviteCodeRef = useRef(options.getInviteCode)
  getInviteCodeRef.current = options.getInviteCode
  const onLoginSuccessRef = useRef(options.onLoginSuccess)
  onLoginSuccessRef.current = options.onLoginSuccess

  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const expireTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pollSessionRef = useRef(0)
  /** 当前微信扫码会话已绑定的邀请码（用于变更后重新拉码） */
  const inviteBoundRef = useRef('')
  const inviteRefreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const patch = useCallback((p: Partial<WechatLoginState>) => {
    stateRef.current = { ...stateRef.current, ...p }
    setState(stateRef.current)
  }, [])

  const setWechatStatus = useCallback(
    (status: WechatUiStatus, text: string) => {
      patch({ status, statusMessage: text })
    },
    [patch]
  )

  const stopWechatPoll = useCallback(() => {
    pollSessionRef.current += 1
    if (pollTimerRef.current) {
      clearInterval(pollTimerRef.current)
      pollTimerRef.current = null
    }
  }, [])

  const clearWechatExpireTimer = useCallback(() => {
    if (expireTimerRef.current) {
      clearTimeout(expireTimerRef.current)
      expireTimerRef.current = null
    }
  }, [])

  /** 标记二维码过期并停止轮询，展示刷新入口 */
  const markWechatQrExpired = useCallback(
    (text = '二维码已失效，请刷新重试') => {
      stopWechatPoll()
      clearWechatExpireTimer()
      setWechatStatus('EXPIRED', text)
      if (stateRef.current.qrUrl) {
        patch({ qrExpired: true })
      }
    },
    [stopWechatPoll, clearWechatExpireTimer, setWechatStatus, patch]
  )

  const markWechatLoginFailed = useCallback(
    (text: string) => {
      stopWechatPoll()
      clearWechatExpireTimer()
      setWechatStatus('FAIL', text || '登录失败，请刷新重试')
      patch({ qrExpired: Boolean(stateRef.current.qrUrl) })
    },
    [stopWechatPoll, clearWechatExpireTimer, setWechatStatus, patch]
  )

  /** 按接口返回的 expireSeconds 本地倒计时；到期后展示刷新（默认 300 秒） */
  const scheduleWechatQrExpiry = useCallback(
    (expireSeconds?: number | null) => {
      clearWechatExpireTimer()
      patch({ qrExpired: false })
      const sec =
        typeof expireSeconds === 'number' && Number.isFinite(expireSeconds) && expireSeconds > 0
          ? Math.floor(expireSeconds)
          : 300
      expireTimerRef.current = setTimeout(() => {
        markWechatQrExpired()
        expireTimerRef.current = null
      }, sec * 1000)
    },
    [clearWechatExpireTimer, patch, markWechatQrExpired]
  )

  const applyWechatCheckPayload = useCallback(
    (payload: WechatCheckPayload) => {
      const status = String(payload.data?.status || '').toUpperCase()
      if (status === 'WAITING') {
        setWechatStatus('WAITING', payload.msg || '请使用微信扫码')
        return
      }
      if (status === 'SCANNED') {
        setWechatStatus('SCANNED', payload.msg || '已扫码，登录处理中')
        return
      }
      if (status === 'EXPIRED') {
        markWechatQrExpired(payload.msg)
        return
      }
      if (status === 'FAIL') {
        markWechatLoginFailed(payload.msg || '登录失败，请刷新重试')
        return
      }
      if (status === 'SUCCESS' && payload.data?.token && payload.data.userInfo) {
        stopWechatPoll()
        clearWechatExpireTimer()
        setWechatStatus('SUCCESS', payload.msg || '登录成功，正在跳转')
        onLoginSuccessRef.current(payload.data)
      }
    },
    [setWechatStatus, markWechatQrExpired, markWechatLoginFailed, stopWechatPoll, clearWechatExpireTimer]
  )

  const canRunWechatPolling = useCallback(() => enabledRef.current, [])

  const openWechatLogin = useCallback(async () => {
    if (!canRunWechatPolling() || stateRef.current.loading) return
    patch({ loading: true })
    setWechatStatus('LOADING', '正在获取二维码')
    patch({ qrExpired: false, qrUrl: '' })
    clearWechatExpireTimer()
    stopWechatPoll()
    const currentSession = pollSessionRef.current
    // 地址栏回填或手填邀请码均传给 /auth/wechat/qrcode（仅新用户扫码注册生效）
    const inviteForSession = getInviteCodeRef.current()
    try {
      const data = await wechatLoginQrcode(inviteForSession)
      if (!canRunWechatPolling() || currentSession !== pollSessionRef.current) return
      inviteBoundRef.current = inviteForSession || ''
      patch({ qrUrl: data.qrCodeUrl, qrExpired: false })
      setWechatStatus('WAITING', '请使用微信扫码')
      scheduleWechatQrExpiry(data.expireSeconds)
      const scene = data.sceneStr
      let ticks = 0
      let checkPending = false
      const maxTicks = 150
      pollTimerRef.current = setInterval(async () => {
        if (!canRunWechatPolling() || currentSession !== pollSessionRef.current) {
          return
        }
        if (checkPending) return
        checkPending = true
        ticks += 1
        if (ticks > maxTicks) {
          markWechatQrExpired()
          return
        }
        try {
          const res = await wechatLoginCheck(scene)
          if (!canRunWechatPolling() || currentSession !== pollSessionRef.current) {
            return
          }
          const payload = parseWechatCheckPayload(res)
          if (payload) applyWechatCheckPayload(payload)
        } catch (err: unknown) {
          const payload = parseWechatCheckPayload(err)
          if (payload) {
            applyWechatCheckPayload(payload)
          } else if (isWechatQrExpiredPayload(err)) {
            markWechatQrExpired()
          }
        } finally {
          checkPending = false
        }
      }, 2000)
    } catch (e: any) {
      if (canRunWechatPolling()) {
        markWechatLoginFailed(e?.msg ?? e?.message ?? '获取微信二维码失败')
        message.error(e?.msg ?? e?.message ?? '获取微信二维码失败')
      }
    } finally {
      patch({ loading: false })
    }
  }, [
    canRunWechatPolling,
    patch,
    setWechatStatus,
    clearWechatExpireTimer,
    stopWechatPoll,
    scheduleWechatQrExpiry,
    markWechatQrExpired,
    markWechatLoginFailed,
    applyWechatCheckPayload
  ])

  /** 邀请码变更后需重新拉码（地址栏回填 / 手填都算），否则扫码会话仍是旧码 */
  const scheduleQrRefreshForInvite = useCallback(() => {
    if (inviteRefreshTimerRef.current) clearTimeout(inviteRefreshTimerRef.current)
    inviteRefreshTimerRef.current = setTimeout(() => {
      inviteRefreshTimerRef.current = null
      const next = getInviteCodeRef.current() || ''
      if (next === inviteBoundRef.current) return
      if (stateRef.current.status === 'SCANNED' || stateRef.current.status === 'SUCCESS') return
      void openWechatLogin()
    }, 500)
  }, [openWechatLogin])

  /** 微信登录开关关闭时：停止轮询并清空二维码 */
  const resetOnDisabled = useCallback(() => {
    stopWechatPoll()
    clearWechatExpireTimer()
    patch({ qrUrl: '' })
  }, [stopWechatPoll, clearWechatExpireTimer, patch])

  useEffect(() => {
    return () => {
      stopWechatPoll()
      clearWechatExpireTimer()
      if (inviteRefreshTimerRef.current) clearTimeout(inviteRefreshTimerRef.current)
    }
  }, [stopWechatPoll, clearWechatExpireTimer])

  return {
    state,
    openWechatLogin,
    scheduleQrRefreshForInvite,
    resetOnDisabled
  }
}
