'use client'

import { Switch } from 'antd'
import { useEffect,useRef,useState } from 'react'
import {
VIEWPORT_COMPACT_MEDIA,
VIEWPORT_COMPACT_SCALE_CHANGED_EVENT,
applyViewportCompactScale,
isCompactViewport,
readViewportCompactScalePreference,
writeViewportCompactScalePreference
} from '~/utils/viewportCompactScale'

/** 与 ViewportScaleEffect 同一开关语义（NEXT_PUBLIC_VIEWPORT_COMPACT_SCALE，默认关闭） */
const rawConfig = process.env.NEXT_PUBLIC_VIEWPORT_COMPACT_SCALE
const configEnabled = rawConfig === '1' || rawConfig === 'true'

/** 低分辨率视口缩放开关：用户偏好存 localStorage，变更后立即重新 apply */
export function ViewportCompactScaleToggle() {
  const [userEnabled, setUserEnabled] = useState(true)
  const [inCompactRange, setInCompactRange] = useState(false)
  const userEnabledRef = useRef(true)

  const canToggle = configEnabled && inCompactRange

  function reapply() {
    applyViewportCompactScale(configEnabled && userEnabledRef.current)
  }

  function setEnabled(next: boolean) {
    userEnabledRef.current = next
    setUserEnabled(next)
    writeViewportCompactScalePreference(next)
    reapply()
  }

  useEffect(() => {
    const syncFromStorage = () => {
      const stored = readViewportCompactScalePreference()
      userEnabledRef.current = stored
      setUserEnabled(stored)
    }
    const onPreferenceChanged = () => {
      syncFromStorage()
      reapply()
    }
    const onMediaChange = () => {
      setInCompactRange(isCompactViewport())
      reapply()
    }

    syncFromStorage()
    setInCompactRange(isCompactViewport())
    reapply()

    window.addEventListener(VIEWPORT_COMPACT_SCALE_CHANGED_EVENT, onPreferenceChanged)

    const mq = window.matchMedia(VIEWPORT_COMPACT_MEDIA)
    if (typeof mq.addEventListener === 'function') {
      mq.addEventListener('change', onMediaChange)
    } else {
      ;(mq as unknown as { addListener: (fn: () => void) => void }).addListener(onMediaChange)
    }

    return () => {
      window.removeEventListener(VIEWPORT_COMPACT_SCALE_CHANGED_EVENT, onPreferenceChanged)
      if (typeof mq.removeEventListener === 'function') {
        mq.removeEventListener('change', onMediaChange)
      } else {
        ;(mq as unknown as { removeListener: (fn: () => void) => void }).removeListener(
          onMediaChange
        )
      }
    }
     
  }, [])

  if (!canToggle) return null

  return (
    <div
      className="viewport-compact-scale-toggle fixed right-[16px] bottom-[16px] z-[900] flex items-center gap-[8px] px-[12px] py-[8px] rounded-[999px] border border-solid border-[rgba(74,231,253,0.22)] bg-[rgba(12,16,24,0.88)] shadow-[0_8px_24px_rgba(0,0,0,0.35)] backdrop-blur-[8px]"
      role="group"
      aria-label="低分辨率界面缩放"
    >
      <span className="viewport-compact-scale-toggle__label text-[12px] text-[rgba(230,237,243,0.88)] whitespace-nowrap select-none">
        界面缩放
      </span>
      <Switch
        checked={userEnabled}
        size="small"
        aria-label="低分辨率界面缩放"
        onChange={(checked) => setEnabled(checked)}
      />
    </div>
  )
}
