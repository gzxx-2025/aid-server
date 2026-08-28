'use client'

import { useCallback,useEffect,useRef,useState } from 'react'
import {
buildCountdownStorageKey,
clearCountdown,
getCountdownRemainingSeconds,
setCountdownEndAt
} from '~/utils/persistedCountdown'

const DEFAULT_DURATION_SEC = 120

/**
 * 可跨刷新的倒计时（按 scope + target 区分，如登录页手机号/邮箱）。
 */
export function usePersistedCountdown(scope: string, durationSec = DEFAULT_DURATION_SEC) {
  const [remaining, setRemaining] = useState(0)
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const activeKeyRef = useRef('')

  const storageKey = useCallback(
    (target: string) => buildCountdownStorageKey(scope, target),
    [scope]
  )

  const stopTimer = useCallback(() => {
    if (timerRef.current) {
      clearInterval(timerRef.current)
      timerRef.current = null
    }
    activeKeyRef.current = ''
  }, [])

  const syncFromStorage = useCallback(
    (key: string) => {
      const sec = getCountdownRemainingSeconds(key)
      setRemaining(sec)
      if (sec <= 0) stopTimer()
      return sec
    },
    [stopTimer]
  )

  const startTimer = useCallback(
    (key: string) => {
      stopTimer()
      activeKeyRef.current = key
      timerRef.current = setInterval(() => {
        if (activeKeyRef.current !== key) return
        syncFromStorage(key)
      }, 1000)
    },
    [stopTimer, syncFromStorage]
  )

  /** 发送成功后调用，开始倒计时并写入 localStorage（可覆盖默认秒数） */
  const start = useCallback(
    (target: string, durationOverride?: number) => {
      const dur = durationOverride ?? durationSec
      const key = storageKey(target)
      setCountdownEndAt(key, dur)
      setRemaining(dur)
      startTimer(key)
    },
    [durationSec, storageKey, startTimer]
  )

  /** 页面挂载或切换账号时，从 localStorage 恢复剩余时间 */
  const restore = useCallback(
    (target: string) => {
      const key = storageKey(target)
      const sec = syncFromStorage(key)
      if (sec > 0) startTimer(key)
      else stopTimer()
    },
    [storageKey, syncFromStorage, startTimer, stopTimer]
  )

  const clear = useCallback(
    (target: string) => {
      clearCountdown(storageKey(target))
      if (activeKeyRef.current === storageKey(target)) {
        setRemaining(0)
        stopTimer()
      }
    },
    [storageKey, stopTimer]
  )

  useEffect(() => {
    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current)
        timerRef.current = null
      }
      activeKeyRef.current = ''
    }
  }, [])

  return {
    remaining,
    start,
    restore,
    clear,
    stop: stopTimer
  }
}
