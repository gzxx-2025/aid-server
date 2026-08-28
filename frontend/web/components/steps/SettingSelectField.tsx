'use client'

import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { createPortal } from 'react-dom'
import { RightOutlined } from '@ant-design/icons'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { toLayoutPx } from '~/utils/viewportZoom'
import type { SettingSelectOption } from '~/composables/usePromptDictionary'
import './SettingSelectField.css'

// 保持原导入路径兼容：类型契约暂由 hooks/usePromptDictionary 承载，这里 re-export
export type { SettingSelectOption }

interface Props {
  modelValue: { key: string; value: string } | null
  options: SettingSelectOption[]
  placeholder?: string
  panelTitle: string
  open: boolean
  onModelValueChange?: (value: { key: string; value: string }) => void
  onOpenChange?: (value: boolean) => void
}

const PANEL_WIDTH = 520
const PANEL_HEIGHT = 460
const GAP = 8
/** 须高于 antd6 嵌套 Modal（基座 1000+100/层，灵感空间约 1200），否则卡片列表会被挡在弹窗后 */
const PANEL_Z_INDEX = '12000'

/** 原 <Transition name="setting-select-fade"> 时长（transition: opacity 0.15s ease） */
const FADE_DURATION_MS = 150

/** 对齐 Vue transition 的 nextFrame：两帧后再切换 class 才能触发过渡 */
function nextFrame(cb: () => void) {
  requestAnimationFrame(() => {
    requestAnimationFrame(cb)
  })
}

export function SettingSelectField({
  modelValue,
  options,
  placeholder = '请选择',
  panelTitle,
  open,
  onModelValueChange,
  onOpenChange
}: Props) {
  const rootRef = useRef<HTMLDivElement | null>(null)
  const triggerRef = useRef<HTMLButtonElement | null>(null)
  const panelRef = useRef<HTMLDivElement | null>(null)
  const [readyToShowPanel, setReadyToShowPanel] = useState(false)
  const [panelFixedStyle, setPanelFixedStyle] = useState<Record<string, string>>({})

  // 事件回调（resize/scroll/click-outside）读取当前展开态用
  const openRef = useRef(open)
  const docMouseDownHandlerRef = useRef<((e: MouseEvent) => void) | null>(null)
  const onOpenChangeRef = useRef(onOpenChange)
  onOpenChangeRef.current = onOpenChange

  // rendered 控制 DOM 存在（含离场动画期间），transitionClass 复刻 Vue transition 各阶段 class
  const [rendered, setRendered] = useState(false)
  const [transitionClass, setTransitionClass] = useState('')
  const renderedRef = useRef(false)
  const transitionTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const panelStyle = panelFixedStyle as CSSProperties

  const triggerLabel = !options.length ? '暂无数据' : modelValue ? modelValue.value : placeholder

  function updatePlacement() {
    if (!openRef.current || !triggerRef.current) return

    const rect = triggerRef.current.getBoundingClientRect()
    const panelW = Math.min(PANEL_WIDTH, window.innerWidth - 24)
    const panelH = Math.min(PANEL_HEIGHT, window.innerHeight * 0.85)

    let leftPx = rect.right + GAP
    if (leftPx + panelW > window.innerWidth - 12) {
      leftPx = rect.left - panelW - GAP
    }
    leftPx = Math.max(12, Math.min(leftPx, window.innerWidth - panelW - 12))

    let topPx = rect.top
    topPx = Math.max(12, Math.min(topPx, window.innerHeight - panelH - 12))

    setPanelFixedStyle({
      position: 'fixed',
      left: `${toLayoutPx(leftPx)}px`,
      top: `${toLayoutPx(topPx)}px`,
      width: `${toLayoutPx(panelW)}px`,
      height: `${toLayoutPx(panelH)}px`,
      zIndex: PANEL_Z_INDEX
    })
  }

  function onTriggerClick() {
    if (!options.length) return
    onOpenChange?.(!open)
  }

  const onSelect = (opt: { key: string; value: string }) => {
    onModelValueChange?.({ key: opt.key, value: opt.value })
    onOpenChange?.(false)
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
      onOpenChangeRef.current?.(false)
    }
    document.addEventListener('mousedown', docMouseDownHandlerRef.current, true)
  }

  // 原 watch(() => props.open, ..., { flush: 'post' })
  useEffect(() => {
    openRef.current = open
    if (open && options.length) {
      setReadyToShowPanel(false)
      unbindClickOutside()
      let cancelled = false
      // 原 await nextTick()：等触发条渲染稳定后再计算定位
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
    // 对齐原 watch：仅 open 变化触发（options.length 取当次值）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // 复刻 <Transition name="setting-select-fade">：进入/离开动画 class + 离场后再卸载
  const visible = open && options.length > 0 && readyToShowPanel
  useEffect(() => {
    if (transitionTimerRef.current) {
      clearTimeout(transitionTimerRef.current)
      transitionTimerRef.current = null
    }

    if (visible) {
      renderedRef.current = true
      setRendered(true)
      setTransitionClass('setting-select-fade-enter-from setting-select-fade-enter-active')
      nextFrame(() => {
        setTransitionClass('setting-select-fade-enter-active')
        transitionTimerRef.current = setTimeout(() => {
          transitionTimerRef.current = null
          setTransitionClass('')
        }, FADE_DURATION_MS)
      })
      return
    }

    if (!renderedRef.current) return
    setTransitionClass('setting-select-fade-leave-active setting-select-fade-leave-to')
    transitionTimerRef.current = setTimeout(() => {
      transitionTimerRef.current = null
      renderedRef.current = false
      setRendered(false)
      setTransitionClass('')
      // 离场结束后再清空定位（Vue 在关闭瞬间清空，React 侧保留至卸载避免离场闪跳）
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

  const triggerClass = [
    'setting-select',
    open ? 'active' : '',
    !options.length ? 'is-empty' : ''
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <div ref={rootRef} className="setting-select-field">
      <button
        ref={triggerRef}
        type="button"
        disabled={!options.length}
        className={triggerClass}
        onClick={(e) => {
          e.stopPropagation()
          onTriggerClick()
        }}
      >
        <span>{triggerLabel}</span>
        <RightOutlined />
      </button>

      {rendered && typeof document !== 'undefined'
        ? createPortal(
            <div
              ref={panelRef}
              className={
                transitionClass
                  ? `setting-select-panel-fixed ${transitionClass}`
                  : 'setting-select-panel-fixed'
              }
              style={panelStyle}
              role="dialog"
              aria-label={panelTitle}
            >
              <div className="setting-select-panel">
                <div className="setting-select-panel-title">{panelTitle}</div>
                <div className="setting-select-grid">
                  {options.map((opt) => (
                    <div
                      key={opt.key}
                      className={
                        modelValue?.key === opt.key
                          ? 'setting-select-option is-selected'
                          : 'setting-select-option'
                      }
                      onClick={() => onSelect(opt)}
                    >
                      <div className="setting-select-option-thumb">
                        {opt.image ? (
                          <ShimmerImage
                            key={`${opt.key}-${opt.image}`}
                            src={opt.image}
                            alt={opt.value}
                            wrapperClass="setting-select-option-thumb__shimmer"
                            imgClass="option-thumb-img"
                            objectFit="cover"
                            revealDirection="fade"
                            minShimmerMs={200}
                          />
                        ) : opt.key === 'none' ? (
                          <span className="option-icon-none">／</span>
                        ) : (
                          <span className="option-icon-placeholder">
                            {(opt.value || '').charAt(0)}
                          </span>
                        )}
                      </div>
                      <span className="setting-select-option-label">{opt.value}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>,
            document.body
          )
        : null}
    </div>
  )
}

export default SettingSelectField
