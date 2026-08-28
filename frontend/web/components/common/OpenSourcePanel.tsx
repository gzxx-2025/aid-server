'use client'

import { CopyOutlined } from '@ant-design/icons'
import { message } from 'antd'
import {
forwardRef,
useEffect,
useImperativeHandle,
useRef,
useState,
type CSSProperties
} from 'react'
import { createPortal } from 'react-dom'
import './OpenSourcePanel.css'

/** 浮层面板对外句柄：暴露浮层根节点，供触发方做 document click 关闭判定 */
export interface FloatingPanelHandle {
  floatingRoot: HTMLElement | null
}

interface OpenSourcePanelProps {
  open: boolean
  floatingStyle?: Record<string, string>
  giteeUrl?: string
  gitUrl?: string
}

const POP_DURATION_MS = 180

/** 对齐 Vue transition 的 nextFrame：两帧后再切换 class 才能触发过渡 */
function nextFrame(cb: () => void) {
  requestAnimationFrame(() => {
    requestAnimationFrame(cb)
  })
}

async function copyUrl(url: string) {
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    message.success('已复制')
  } catch {
    message.error('复制失败')
  }
}

const OpenSourcePanel = forwardRef<FloatingPanelHandle, OpenSourcePanelProps>(
  function OpenSourcePanel({ open, floatingStyle, giteeUrl, gitUrl }, ref) {
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
        setTransitionClass('open-source-panel-pop-enter-from open-source-panel-pop-enter-active')
        nextFrame(() => {
          setTransitionClass('open-source-panel-pop-enter-active')
          timerRef.current = setTimeout(() => {
            timerRef.current = null
            setTransitionClass('')
          }, POP_DURATION_MS)
        })
        return
      }

      if (!renderedRef.current) return
      setTransitionClass('open-source-panel-pop-leave-active open-source-panel-pop-leave-to')
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
        className={transitionClass ? `open-source-panel ${transitionClass}` : 'open-source-panel'}
        style={floatingStyle as CSSProperties}
        role="dialog"
        aria-label="开源地址"
      >
        <h3 className="open-source-panel__title">开源地址</h3>

        {giteeUrl ? (
          <div className="open-source-panel__field">
            <div className="open-source-panel__label">Gitee地址：</div>
            <div className="open-source-panel__input">
              <a
                className="open-source-panel__link"
                href={giteeUrl}
                target="_blank"
                rel="noopener noreferrer"
                title={giteeUrl}
              >
                {giteeUrl}
              </a>
              <button
                type="button"
                className="open-source-panel__copy"
                aria-label="复制 Gitee 地址"
                onClick={() => copyUrl(giteeUrl)}
              >
                <CopyOutlined />
              </button>
            </div>
          </div>
        ) : null}

        {gitUrl ? (
          <div className="open-source-panel__field">
            <div className="open-source-panel__label">Git地址：</div>
            <div className="open-source-panel__input">
              <a
                className="open-source-panel__link"
                href={gitUrl}
                target="_blank"
                rel="noopener noreferrer"
                title={gitUrl}
              >
                {gitUrl}
              </a>
              <button
                type="button"
                className="open-source-panel__copy"
                aria-label="复制 Git 地址"
                onClick={() => copyUrl(gitUrl)}
              >
                <CopyOutlined />
              </button>
            </div>
          </div>
        ) : null}
      </div>,
      document.body
    )
  }
)

export default OpenSourcePanel
