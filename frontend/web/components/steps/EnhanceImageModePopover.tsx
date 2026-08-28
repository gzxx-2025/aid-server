'use client'

import { useEffect,useRef,useState,type CSSProperties,type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import coinIcon from '~/assets/img/home/starlightCoin.svg'
import type { EnhanceImageMode } from '~/types/enhanceImageMode'
import { assetUrl } from '~/utils/assetUrl'
import './EnhanceImageModePopover.css'
interface Props {
  /** 当前画布图片索引，随选项一并回传便于父级处理 */
  imageIndex?: number
  /** 可选模式；默认展示全部，场景/角色/道具弹窗仅传 redraw_ultra */
  modes?: EnhanceImageMode[]
  onSelect?: (payload: { mode: EnhanceImageMode; imageIndex: number }) => void
  /** 原默认插槽：触发区内容 */
  children?: ReactNode
}

const ESTIMATED_PANEL_MIN = 200
const GAP = 8

const ALL_OPTIONS: Array<{
  mode: EnhanceImageMode
  title: string
  coins: number
  desc: string
}> = [
  {
    mode: 'redraw_ultra',
    title: '重绘超清',
    coins: 16,
    desc: 'AI 补充细节与纹理，生成4K大片质感'
  },
  {
    mode: 'lossless_upscale',
    title: '无损放大',
    coins: 2,
    desc: '忠于原图，放大至2倍尺寸，上限2K'
  }
]

const DEFAULT_MODES: EnhanceImageMode[] = ['redraw_ultra', 'lossless_upscale']

/** 原 <Transition name="enhance-mode-fade"> 时长（transition: opacity/transform 0.15s ease） */
const FADE_DURATION_MS = 150

/** 对齐 Vue transition 的 nextFrame：两帧后再切换 class 才能触发过渡 */
function nextFrame(cb: () => void) {
  requestAnimationFrame(() => {
    requestAnimationFrame(cb)
  })
}

export function EnhanceImageModePopover({
  imageIndex = 0,
  modes = DEFAULT_MODES,
  onSelect,
  children
}: Props) {
  const allowed = new Set(modes)
  const visibleOptions = ALL_OPTIONS.filter((opt) => allowed.has(opt.mode))

  const rootRef = useRef<HTMLDivElement | null>(null)
  const triggerRef = useRef<HTMLDivElement | null>(null)
  const panelRef = useRef<HTMLDivElement | null>(null)
  const [open, setOpen] = useState(false)
  const [openUpward, setOpenUpward] = useState(false)
  const [readyToShowPanel, setReadyToShowPanel] = useState(false)
  const [panelFixedStyle, setPanelFixedStyle] = useState<Record<string, string>>({})

  // 事件回调（resize/scroll/click-outside）读取当前展开态用
  const openRef = useRef(false)
  const docMouseDownHandlerRef = useRef<((e: MouseEvent) => void) | null>(null)

  // rendered 控制 DOM 存在（含离场动画期间），transitionClass 复刻 Vue transition 各阶段 class
  const [rendered, setRendered] = useState(false)
  const [transitionClass, setTransitionClass] = useState('')
  const renderedRef = useRef(false)
  const transitionTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const panelStyle = panelFixedStyle as CSSProperties

  function setOpenBoth(value: boolean) {
    openRef.current = value
    setOpen(value)
  }

  function updatePlacement() {
    if (!openRef.current || !triggerRef.current) return

    const rect = triggerRef.current.getBoundingClientRect()
    const spaceBelow = window.innerHeight - rect.bottom - GAP
    const spaceAbove = rect.top - GAP

    let upward = false
    if (spaceBelow >= ESTIMATED_PANEL_MIN) {
      upward = false
    } else if (spaceAbove >= ESTIMATED_PANEL_MIN) {
      upward = true
    } else {
      upward = spaceAbove > spaceBelow
    }

    setOpenUpward(upward)

    const minW = 320
    const widthPx = Math.min(Math.max(rect.width, minW), Math.min(420, window.innerWidth - 24))
    let leftPx = rect.left
    leftPx = Math.max(12, Math.min(leftPx, window.innerWidth - widthPx - 12))

    const z = '5500'

    if (!upward) {
      setPanelFixedStyle({
        position: 'fixed',
        left: `${leftPx}px`,
        top: `${rect.bottom + GAP}px`,
        width: `${widthPx}px`,
        zIndex: z
      })
    } else {
      setPanelFixedStyle({
        position: 'fixed',
        left: `${leftPx}px`,
        bottom: `${window.innerHeight - rect.top + GAP}px`,
        width: `${widthPx}px`,
        zIndex: z
      })
    }
  }

  function toggle() {
    setOpenBoth(!openRef.current)
  }

  function pick(mode: EnhanceImageMode) {
    onSelect?.({ mode, imageIndex })
    setOpenBoth(false)
  }

  function unbindClickOutside() {
    if (docMouseDownHandlerRef.current) {
      document.removeEventListener('mousedown', docMouseDownHandlerRef.current, true)
      docMouseDownHandlerRef.current = null
    }
  }

  function bindClickOutside() {
    unbindClickOutside()
    docMouseDownHandlerRef.current = (e: MouseEvent) => {
      const root = rootRef.current
      const panel = panelRef.current
      if (!openRef.current || !root) return
      const target = e.target as Node | null
      if (!target) return
      if (root.contains(target) || (panel && panel.contains(target))) return
      setOpenBoth(false)
    }
    document.addEventListener('mousedown', docMouseDownHandlerRef.current, true)
  }

  // 原 watch(() => open.value, ..., { flush: 'post' })
  useEffect(() => {
    if (open) {
      setReadyToShowPanel(false)
      unbindClickOutside()
      let cancelled = false
      // 原 await nextTick()：等触发区渲染稳定后再计算方向
      requestAnimationFrame(() => {
        if (cancelled) return
        updatePlacement()
        setReadyToShowPanel(true)
        // 原第二个 await nextTick()：面板挂载后再绑定 click-outside
        requestAnimationFrame(() => {
          if (cancelled) return
          bindClickOutside()
        })
      })
      return () => {
        cancelled = true
      }
    }
    setReadyToShowPanel(false)
    unbindClickOutside()
    return undefined
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // 原 watch(() => props.imageIndex)：展开时索引变化需重算定位
  useEffect(() => {
    if (openRef.current) updatePlacement()
     
  }, [imageIndex])

  // 复刻 <Transition name="enhance-mode-fade">：进入/离开动画 class + 离场后再卸载
  const visible = open && readyToShowPanel
  useEffect(() => {
    if (transitionTimerRef.current) {
      clearTimeout(transitionTimerRef.current)
      transitionTimerRef.current = null
    }

    if (visible) {
      renderedRef.current = true
      setRendered(true)
      setTransitionClass('enhance-mode-fade-enter-from enhance-mode-fade-enter-active')
      nextFrame(() => {
        setTransitionClass('enhance-mode-fade-enter-active')
        transitionTimerRef.current = setTimeout(() => {
          transitionTimerRef.current = null
          setTransitionClass('')
        }, FADE_DURATION_MS)
      })
      return
    }

    if (!renderedRef.current) return
    setTransitionClass('enhance-mode-fade-leave-active enhance-mode-fade-leave-to')
    transitionTimerRef.current = setTimeout(() => {
      transitionTimerRef.current = null
      renderedRef.current = false
      setRendered(false)
      setTransitionClass('')
      // 离场结束后再复位方向与定位（Vue 在关闭瞬间清空，React 侧保留至卸载避免离场闪跳）
      setOpenUpward(false)
      setPanelFixedStyle({})
    }, FADE_DURATION_MS)
  }, [visible])

  useEffect(() => {
    const onViewportChange = () => {
      if (openRef.current) updatePlacement()
    }
    window.addEventListener('resize', onViewportChange)
    window.addEventListener('scroll', onViewportChange, true)
    return () => {
      unbindClickOutside()
      window.removeEventListener('resize', onViewportChange)
      window.removeEventListener('scroll', onViewportChange, true)
      if (transitionTimerRef.current) clearTimeout(transitionTimerRef.current)
    }
     
  }, [])

  const panelClass = [
    'enhance-mode-panel',
    !openUpward ? 'is-open-down' : 'is-open-up',
    transitionClass
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <div ref={rootRef} className="enhance-mode-popover-root">
      <div
        ref={triggerRef}
        className="enhance-mode-popover-trigger"
        onClick={(e) => {
          e.stopPropagation()
          toggle()
        }}
      >
        {children}
      </div>

      {rendered && typeof document !== 'undefined'
        ? createPortal(
            <div
              ref={panelRef}
              className={panelClass}
              style={panelStyle}
              role="menu"
              aria-label="变清晰方式"
            >
              {visibleOptions.map((opt) => (
                <button
                  key={opt.mode}
                  type="button"
                  className="enhance-mode-item"
                  role="menuitem"
                  onClick={(e) => {
                    e.stopPropagation()
                    pick(opt.mode)
                  }}
                >
                  <div
                    className={`enhance-mode-thumb enhance-mode-thumb--${opt.mode}`}
                    aria-hidden="true"
                  >
                    <span className="enhance-mode-thumb__half enhance-mode-thumb__half--before" />
                    <span className="enhance-mode-thumb__divider" />
                    <span className="enhance-mode-thumb__half enhance-mode-thumb__half--after" />
                  </div>
                  <div className="enhance-mode-body">
                    <div className="enhance-mode-title-row">
                      <span className="enhance-mode-title">{opt.title}</span>
                      <span className="enhance-mode-cost">
                        （消耗
                        <img src={assetUrl(coinIcon)} alt="" className="enhance-mode-coin" />
                        {opt.coins}/张）
                      </span>
                    </div>
                    <p className="enhance-mode-desc">{opt.desc}</p>
                  </div>
                </button>
              ))}
            </div>,
            document.body
          )
        : null}
    </div>
  )
}

export default EnhanceImageModePopover
