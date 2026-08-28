'use client'

/* eslint-disable @next/next/no-img-element -- 勾选态使用项目内 SVG 资源，不经过远程图片加载器。 */
import type { CSSProperties,MouseEvent as ReactMouseEvent,ReactNode } from 'react'
import { useEffect,useRef,useState } from 'react'
import { createPortal } from 'react-dom'
import '~/assets/css/history-record-card.css'
import dialogSelectNormalRaw from '~/assets/img/icon/dialog-select-nor.svg'
import dialogSelectSelectedRaw from '~/assets/img/icon/dialog-select-sel.svg'
import { assetUrl } from '~/utils/assetUrl'
import './HistoryRecordWrap.css'

const dialogSelectNormal = assetUrl(dialogSelectNormalRaw)
const dialogSelectSelected = assetUrl(dialogSelectSelectedRaw)

export interface HistoryRecordWrapProps {
  showSetMain?: boolean
  isMain?: boolean
  setMainLabel?: string
  selectedMainLabel?: string
  unsetMainLabel?: string
  setMainLoading?: boolean
  onSetMain?: () => void
  onUnsetMain?: () => void
  children?: ReactNode
}

/** 悬浮按钮的过渡阶段（对应原 <Transition name="history-set-main-btn-fade">） */
type BtnPhase = 'hidden' | 'enter-from' | 'shown' | 'leaving'

const BTN_FADE_CLASS: Record<Exclude<BtnPhase, 'hidden'>, string> = {
  'enter-from': 'history-set-main-btn-fade-enter-active history-set-main-btn-fade-enter-from',
  shown: 'history-set-main-btn-fade-enter-active',
  leaving: 'history-set-main-btn-fade-leave-active history-set-main-btn-fade-leave-to'
}

/** 过渡时长 220ms + 余量 */
const BTN_LEAVE_MS = 240

/** 生成记录卡片包装：悬停在卡片右侧浮出「设为主图」按钮（teleport 到 body） */
export function HistoryRecordWrap({
  showSetMain,
  isMain = false,
  setMainLabel,
  selectedMainLabel = '已设置主图',
  unsetMainLabel = '取消主图',
  setMainLoading,
  onSetMain,
  onUnsetMain,
  children
}: HistoryRecordWrapProps) {
  const wrapRef = useRef<HTMLDivElement | null>(null)
  const [btnPhase, setBtnPhase] = useState<BtnPhase>('hidden')
  const [btnStyle, setBtnStyle] = useState<CSSProperties>({})

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

  function updateBtnPos() {
    const el = wrapRef.current
    if (!el) return
    const rect = el.getBoundingClientRect()
    setBtnStyle({
      top: `${rect.top + rect.height / 2}px`,
      left: `${rect.right + 6}px`
    })
  }

  function beginLeave() {
    setBtnPhase((phase) => (phase === 'hidden' ? phase : 'leaving'))
    clearLeaveTimer()
    leaveTimerRef.current = window.setTimeout(() => {
      leaveTimerRef.current = null
      setBtnPhase('hidden')
    }, BTN_LEAVE_MS)
  }

  function showBtn() {
    clearHideTimer()
    if (!showSetMain && !isMain) return
    clearLeaveTimer()
    setBtnPhase((phase) => (phase === 'hidden' ? 'enter-from' : 'shown'))
    updateBtnPos()
  }

  function scheduleHide() {
    clearHideTimer()
    hideTimerRef.current = window.setTimeout(() => {
      hideTimerRef.current = null
      beginLeave()
    }, 80)
  }

  function handleSetMainClick(event: ReactMouseEvent<HTMLButtonElement>) {
    event.stopPropagation()
    onSetMain?.()
  }

  function handleToggleMainClick(event: ReactMouseEvent<HTMLButtonElement>) {
    event.stopPropagation()
    event.preventDefault()
    if (setMainLoading) return
    if (isMain) {
      onUnsetMain?.()
      return
    }
    onSetMain?.()
  }

  // enter-from → 下一帧移除，触发淡入过渡
  useEffect(() => {
    if (btnPhase !== 'enter-from') return
    let raf2 = 0
    const raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => {
        setBtnPhase((phase) => (phase === 'enter-from' ? 'shown' : phase))
      })
    })
    return () => {
      cancelAnimationFrame(raf1)
      cancelAnimationFrame(raf2)
    }
  }, [btnPhase])

  // 卡片无法设置且当前也不是主结果时收起按钮（带淡出）
  useEffect(() => {
    if (showSetMain || isMain) return
    clearHideTimer()
    beginLeave()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isMain, showSetMain])

  const btnActive = btnPhase === 'enter-from' || btnPhase === 'shown'

  useEffect(() => {
    if (!btnActive) return
    const onScrollOrResize = () => updateBtnPos()
    window.addEventListener('scroll', onScrollOrResize, true)
    window.addEventListener('resize', onScrollOrResize)
    return () => {
      window.removeEventListener('scroll', onScrollOrResize, true)
      window.removeEventListener('resize', onScrollOrResize)
    }
  }, [btnActive])

  useEffect(() => {
    return () => {
      clearHideTimer()
      clearLeaveTimer()
    }
  }, [])

  return (
    <div
      ref={wrapRef}
      className="history-record-wrap"
      onMouseEnter={showBtn}
      onMouseLeave={scheduleHide}
    >
      {children}
      {showSetMain || isMain ? (
        <button
          type="button"
          className="history-main-toggle"
          aria-label={isMain ? unsetMainLabel : setMainLabel || '设置主图'}
          aria-pressed={isMain}
          disabled={setMainLoading}
          onClick={handleToggleMainClick}
        >
          <img
            src={isMain ? dialogSelectSelected : dialogSelectNormal}
            alt=""
            className="history-main-toggle__icon"
          />
        </button>
      ) : null}
      {btnPhase !== 'hidden' && typeof document !== 'undefined'
        ? createPortal(
            isMain ? (
              <span
                className={`history-set-main-btn history-set-main-btn--status history-set-main-btn--floating ${BTN_FADE_CLASS[btnPhase]}`}
                style={btnStyle}
                role="status"
                onMouseEnter={showBtn}
                onMouseLeave={scheduleHide}
              >
                {selectedMainLabel}
              </span>
            ) : (
              <button
                type="button"
                className={`history-set-main-btn history-set-main-btn--floating ${BTN_FADE_CLASS[btnPhase]}`}
                style={btnStyle}
                disabled={setMainLoading}
                onMouseEnter={showBtn}
                onMouseLeave={scheduleHide}
                onClick={handleSetMainClick}
              >
                {setMainLabel}
              </button>
            ),
            document.body
          )
        : null}
    </div>
  )
}
