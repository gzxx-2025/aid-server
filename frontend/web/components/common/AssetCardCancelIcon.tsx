'use client'

import type {
CSSProperties,
KeyboardEvent as ReactKeyboardEvent,
MouseEvent as ReactMouseEvent
} from 'react'
import { useEffect,useRef,useState } from 'react'
import { createPortal } from 'react-dom'
import cancelIcon from '~/assets/img/icon/cancel.svg'
import { assetUrl } from '~/utils/assetUrl'
interface AssetCardCancelIconProps {
  label: string
  onClick?: () => void
}

/** 提示气泡的过渡阶段（对应原 <Transition name="asset-card-cancel-hint-fade">） */
type HintPhase = 'hidden' | 'enter-from' | 'shown' | 'leaving'

const HINT_FADE_CLASS: Record<Exclude<HintPhase, 'hidden'>, string> = {
  'enter-from': 'asset-card-cancel-hint-fade-enter-active asset-card-cancel-hint-fade-enter-from',
  shown: 'asset-card-cancel-hint-fade-enter-active',
  leaving: 'asset-card-cancel-hint-fade-leave-active asset-card-cancel-hint-fade-leave-to'
}

/** 过渡时长 220ms + 余量 */
const HINT_LEAVE_MS = 240

/** 列表卡片右上角取消图标 + body 悬浮提示（样式来自全局 asset-card-cancel-hint.css） */
export function AssetCardCancelIcon({ label, onClick }: AssetCardCancelIconProps) {
  const rootRef = useRef<HTMLSpanElement | null>(null)
  const [hintPhase, setHintPhase] = useState<HintPhase>('hidden')
  const [hintStyle, setHintStyle] = useState<CSSProperties>({})

  const hideTimerRef = useRef<number | null>(null)
  const leaveTimerRef = useRef<number | null>(null)

  function clearHideTimer() {
    if (hideTimerRef.current != null) {
      clearTimeout(hideTimerRef.current)
      hideTimerRef.current = null
    }
  }

  function clearLeaveTimer() {
    if (leaveTimerRef.current != null) {
      clearTimeout(leaveTimerRef.current)
      leaveTimerRef.current = null
    }
  }

  function updateHintPos() {
    const el = rootRef.current
    if (!el) return
    const rect = el.getBoundingClientRect()
    setHintStyle({
      top: `${rect.top}px`,
      left: `${rect.left + rect.width / 2}px`
    })
  }

  function showHint() {
    clearHideTimer()
    clearLeaveTimer()
    setHintPhase((phase) => (phase === 'hidden' ? 'enter-from' : 'shown'))
    updateHintPos()
  }

  function scheduleHide() {
    clearHideTimer()
    hideTimerRef.current = window.setTimeout(() => {
      hideTimerRef.current = null
      setHintPhase((phase) => (phase === 'hidden' ? phase : 'leaving'))
      clearLeaveTimer()
      leaveTimerRef.current = window.setTimeout(() => {
        leaveTimerRef.current = null
        setHintPhase('hidden')
      }, HINT_LEAVE_MS)
    }, 80)
  }

  // enter-from → 下一帧移除，触发淡入过渡
  useEffect(() => {
    if (hintPhase !== 'enter-from') return
    let raf2 = 0
    const raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => {
        setHintPhase((phase) => (phase === 'enter-from' ? 'shown' : phase))
      })
    })
    return () => {
      cancelAnimationFrame(raf1)
      cancelAnimationFrame(raf2)
    }
  }, [hintPhase])

  const hintActive = hintPhase === 'enter-from' || hintPhase === 'shown'

  useEffect(() => {
    if (!hintActive) return
    const onScrollOrResize = () => updateHintPos()
    window.addEventListener('scroll', onScrollOrResize, true)
    window.addEventListener('resize', onScrollOrResize)
    return () => {
      window.removeEventListener('scroll', onScrollOrResize, true)
      window.removeEventListener('resize', onScrollOrResize)
    }
  }, [hintActive])

  useEffect(() => {
    return () => {
      clearHideTimer()
      clearLeaveTimer()
    }
  }, [])

  function handleClick(event: ReactMouseEvent<HTMLSpanElement>) {
    event.stopPropagation()
    onClick?.()
  }

  function handleKeyDown(event: ReactKeyboardEvent<HTMLSpanElement>) {
    if (event.key !== 'Enter') return
    event.stopPropagation()
    event.preventDefault()
    onClick?.()
  }

  return (
    <span
      ref={rootRef}
      className="asset-card-cancel-icon"
      role="button"
      tabIndex={0}
      onMouseEnter={showHint}
      onMouseLeave={scheduleHide}
      onClick={handleClick}
      onKeyDown={handleKeyDown}
    >
      <img
        src={assetUrl(cancelIcon)}
        alt=""
        className="asset-card-cancel-icon__img"
        width={16}
        height={16}
      />
      {hintPhase !== 'hidden' && typeof document !== 'undefined'
        ? createPortal(
            <span
              className={`asset-card-cancel-hint ${HINT_FADE_CLASS[hintPhase]}`}
              style={hintStyle}
            >
              {label}
            </span>,
            document.body
          )
        : null}
    </span>
  )
}
