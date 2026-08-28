'use client'

import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  type CSSProperties
} from 'react'
import { createPortal } from 'react-dom'
import type { FloatingPanelHandle } from '~/components/common/OpenSourcePanel'
import './DiscussionGroupPanel.css'

export type { FloatingPanelHandle }

interface DiscussionGroupPanelProps {
  open: boolean
  floatingStyle?: Record<string, string>
  qrImageUrl?: string
}

const POP_DURATION_MS = 180

/** 对齐 Vue transition 的 nextFrame：两帧后再切换 class 才能触发过渡 */
function nextFrame(cb: () => void) {
  requestAnimationFrame(() => {
    requestAnimationFrame(cb)
  })
}

const DiscussionGroupPanel = forwardRef<FloatingPanelHandle, DiscussionGroupPanelProps>(
  function DiscussionGroupPanel({ open, floatingStyle, qrImageUrl }, ref) {
    const floatingRootRef = useRef<HTMLDivElement | null>(null)
    // rendered 控制 DOM 存在（含离场动画期间），transitionClass 复刻 Vue transition 各阶段 class
    const [rendered, setRendered] = useState(false)
    const [transitionClass, setTransitionClass] = useState('')
    const renderedRef = useRef(false)
    const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

    useImperativeHandle(
      ref,
      () => ({
        get floatingRoot() {
          return floatingRootRef.current
        }
      }),
      []
    )

    useEffect(() => {
      if (timerRef.current) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }

      if (open) {
        renderedRef.current = true
        setRendered(true)
        setTransitionClass(
          'discussion-group-panel-pop-enter-from discussion-group-panel-pop-enter-active'
        )
        nextFrame(() => {
          setTransitionClass('discussion-group-panel-pop-enter-active')
          timerRef.current = setTimeout(() => {
            timerRef.current = null
            setTransitionClass('')
          }, POP_DURATION_MS)
        })
        return
      }

      if (!renderedRef.current) return
      setTransitionClass('discussion-group-panel-pop-leave-active discussion-group-panel-pop-leave-to')
      timerRef.current = setTimeout(() => {
        timerRef.current = null
        renderedRef.current = false
        setRendered(false)
        setTransitionClass('')
      }, POP_DURATION_MS)
    }, [open])

    useEffect(() => {
      return () => {
        if (timerRef.current) clearTimeout(timerRef.current)
      }
    }, [])

    if (!rendered || typeof document === 'undefined') return null

    return createPortal(
      <div
        ref={floatingRootRef}
        className={
          transitionClass ? `discussion-group-panel ${transitionClass}` : 'discussion-group-panel'
        }
        style={floatingStyle as CSSProperties}
        role="dialog"
        aria-label="交流群"
      >
        <div className="discussion-group-panel__qrcode">
          {qrImageUrl ? (
            <img className="discussion-group-panel__qrcode-img" src={qrImageUrl} alt="交流群二维码" />
          ) : (
            <div className="discussion-group-panel__qrcode-img discussion-group-panel__qrcode-img--placeholder" />
          )}
          <p className="discussion-group-panel__hint">扫码加入交流群</p>
        </div>
      </div>,
      document.body
    )
  }
)

export default DiscussionGroupPanel
