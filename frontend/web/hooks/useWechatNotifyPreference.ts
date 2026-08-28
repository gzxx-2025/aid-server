'use client'

import { useCallback,useRef,useState } from 'react'
import type { WechatNotifyPreferenceData } from '~/types/business-api'
import { isWechatLoginChannel } from '~/utils/authLoginChannel'
import {
wechatNotifyDisable,
wechatNotifyEnable,
wechatNotifyPreference
} from '~/utils/businessApi'

/** 微信任务推送偏好：仅微信登录用户展示，开关与后端偏好双向同步 */
export function useWechatNotifyPreference() {
  const [preference, setPreference] = useState<WechatNotifyPreferenceData | null>(null)
  const [loading, setLoading] = useState(false)
  const [toggling, setToggling] = useState(false)

  // 回调闭包读最新值用
  const prefRef = useRef<WechatNotifyPreferenceData | null>(null)
  const togglingRef = useRef(false)

  const setPref = useCallback((next: WechatNotifyPreferenceData | null) => {
    prefRef.current = next
    setPreference(next)
  }, [])

  const isWechatLoginUser = isWechatLoginChannel()
  const showWechatNotifyRow = isWechatLoginUser && preference?.systemEnabled === true
  const wechatNotifyChecked = preference?.userEnabled === true
  const wechatNotifySwitchDisabled = preference?.wechatBound !== true

  const loadPreference = useCallback(
    async (force = false) => {
      if (!isWechatLoginChannel()) {
        setPref(null)
        return null
      }
      if (!force && prefRef.current) return prefRef.current
      setLoading(true)
      try {
        const data = await wechatNotifyPreference()
        setPref(data)
        return data
      } catch {
        return prefRef.current
      } finally {
        setLoading(false)
      }
    },
    [setPref]
  )

  const setWechatNotifyEnabled = useCallback(
    async (next: boolean) => {
      const current = prefRef.current
      if (!current || current.wechatBound !== true || togglingRef.current) {
        return { ok: false as const, message: '' }
      }
      const prev = current.userEnabled
      setPref({ ...current, userEnabled: next })
      togglingRef.current = true
      setToggling(true)
      try {
        const data = next ? await wechatNotifyEnable() : await wechatNotifyDisable()
        setPref(data)
        return { ok: true as const, message: '' }
      } catch (e: unknown) {
        if (prefRef.current) {
          setPref({ ...prefRef.current, userEnabled: prev })
        }
        const err = e as { msg?: string; message?: string }
        return {
          ok: false as const,
          message: err?.msg || err?.message || '微信推送设置失败，请稍后重试'
        }
      } finally {
        togglingRef.current = false
        setToggling(false)
      }
    },
    [setPref]
  )

  const resetPreference = useCallback(() => {
    setPref(null)
  }, [setPref])

  return {
    preference,
    loading,
    toggling,
    isWechatLoginUser,
    showWechatNotifyRow,
    wechatNotifyChecked,
    wechatNotifySwitchDisabled,
    loadPreference,
    setWechatNotifyEnabled,
    resetPreference
  }
}
