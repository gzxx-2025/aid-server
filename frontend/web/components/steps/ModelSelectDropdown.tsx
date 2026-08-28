'use client'

import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { createPortal } from 'react-dom'
import { DownOutlined, UpOutlined, CheckCircleFilled } from '@ant-design/icons'
import { ModelFreeBadge } from '~/components/common/ModelFreeBadge'
import { ModelBillingRules } from '~/components/common/ModelBillingRules'
import { shouldShowModelFreeBadge } from '~/utils/modelFreeStatus'
import type { ModelOption } from '~/types/modelAgentOptions'
import './ModelSelectDropdown.css'

// 保持原导入路径兼容：原类型定义在本组件文件，现提升到 types/modelAgentOptions
export type { ModelOption }

interface Props {
  value: ModelOption
  options: ModelOption[]
  expanded: boolean
  onToggle?: () => void
  onSelect?: (model: ModelOption) => void
  /** 点击组件外部时关闭，行为对齐 Ant Select */
  onClose?: () => void
}

/** 预估菜单最小高度，用于判断是否该向上展开 */
const ESTIMATED_MENU_MIN = 280

/** 原 <Transition name="dropdown"> 时长（transition: opacity/transform 0.2s ease） */
const DROPDOWN_DURATION_MS = 200

/** 对齐 Vue transition 的 nextFrame：两帧后再切换 class 才能触发过渡 */
function nextFrame(cb: () => void) {
  requestAnimationFrame(() => {
    requestAnimationFrame(cb)
  })
}

export function ModelSelectDropdown({ value, options, expanded, onToggle, onSelect, onClose }: Props) {
  const hasSelectedModel = Boolean(String(value.id || '').trim())

  const rootRef = useRef<HTMLDivElement | null>(null)
  const triggerRef = useRef<HTMLDivElement | null>(null)
  const optionsListRef = useRef<HTMLDivElement | null>(null)
  const [openUpward, setOpenUpward] = useState(false)
  const [menuMaxHeightPx, setMenuMaxHeightPx] = useState(500)
  /** 先计算上下方向再渲染列表，避免首帧方向错误 */
  const [readyToShowList, setReadyToShowList] = useState(false)
  /** fixed 定位（相对视口），与触发条对齐 */
  const [panelFixedStyle, setPanelFixedStyle] = useState<Record<string, string>>({})

  // 事件回调（resize/scroll/click-outside）读取当前展开态用
  const expandedRef = useRef(expanded)
  const docMouseDownHandlerRef = useRef<((e: MouseEvent) => void) | null>(null)

  // rendered 控制 DOM 存在（含离场动画期间），transitionClass 复刻 Vue transition 各阶段 class
  const [rendered, setRendered] = useState(false)
  const [transitionClass, setTransitionClass] = useState('')
  const renderedRef = useRef(false)
  const transitionTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const optionsListStyle: CSSProperties = {
    ...(panelFixedStyle as CSSProperties),
    maxHeight: `${menuMaxHeightPx}px`
  }

  function updateDropdownPlacement() {
    if (!expandedRef.current || !triggerRef.current) return

    const rect = triggerRef.current.getBoundingClientRect()
    const gap = 8
    const spaceBelow = window.innerHeight - rect.bottom - gap
    const spaceAbove = rect.top - gap

    // 下方空间足够则向下；否则若上方更宽裕则向上；否则选空间更大的一侧
    let upward = false
    if (spaceBelow >= ESTIMATED_MENU_MIN) {
      upward = false
    } else if (spaceAbove >= ESTIMATED_MENU_MIN) {
      upward = true
    } else {
      upward = spaceAbove > spaceBelow
    }

    setOpenUpward(upward)
    const avail = upward ? spaceAbove : spaceBelow
    setMenuMaxHeightPx(Math.max(120, Math.min(500, Math.floor(avail))))

    const minW = 360
    const widthPx = Math.min(Math.max(rect.width, minW), Math.min(520, window.innerWidth - 24))
    let leftPx = rect.left
    leftPx = Math.max(12, Math.min(leftPx, window.innerWidth - widthPx - 12))

    const z = '5000'

    if (!upward) {
      setPanelFixedStyle({
        position: 'fixed',
        left: `${leftPx}px`,
        top: `${rect.bottom + gap}px`,
        width: `${widthPx}px`,
        zIndex: z
      })
    } else {
      setPanelFixedStyle({
        position: 'fixed',
        left: `${leftPx}px`,
        bottom: `${window.innerHeight - rect.top + gap}px`,
        width: `${widthPx}px`,
        zIndex: z
      })
    }
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
      const el = rootRef.current
      const listEl = optionsListRef.current
      if (!el || !expandedRef.current) return
      const target = e.target as Node | null
      if (target && (el.contains(target) || (listEl && listEl.contains(target)))) return
      onCloseRef.current?.()
    }
    document.addEventListener('mousedown', docMouseDownHandlerRef.current, true)
  }

  // onClose 由父级每次渲染重建，经 ref 转发给一次性绑定的 document 监听
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose

  // 原 watch(() => props.expanded, ..., { flush: 'post' })
  useEffect(() => {
    expandedRef.current = expanded
    if (expanded) {
      setReadyToShowList(false)
      unbindClickOutside()
      let cancelled = false
      // 原 await nextTick()：等触发条渲染稳定后再计算方向
      requestAnimationFrame(() => {
        if (cancelled) return
        updateDropdownPlacement()
        setReadyToShowList(true)
        // 原第二个 await nextTick()：列表挂载后再绑定 click-outside
        requestAnimationFrame(() => {
          if (cancelled) return
          bindClickOutside()
        })
      })
      return () => {
        cancelled = true
      }
    }
    setReadyToShowList(false)
    unbindClickOutside()
    return undefined
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expanded])

  // 复刻 <Transition name="dropdown">：进入/离开动画 class + 离场后再卸载
  const visible = expanded && readyToShowList
  useEffect(() => {
    if (transitionTimerRef.current) {
      clearTimeout(transitionTimerRef.current)
      transitionTimerRef.current = null
    }

    if (visible) {
      renderedRef.current = true
      setRendered(true)
      setTransitionClass('dropdown-enter-from dropdown-enter-active')
      nextFrame(() => {
        setTransitionClass('dropdown-enter-active')
        transitionTimerRef.current = setTimeout(() => {
          transitionTimerRef.current = null
          setTransitionClass('')
        }, DROPDOWN_DURATION_MS)
      })
      return
    }

    if (!renderedRef.current) return
    setTransitionClass('dropdown-leave-active dropdown-leave-to')
    transitionTimerRef.current = setTimeout(() => {
      transitionTimerRef.current = null
      renderedRef.current = false
      setRendered(false)
      setTransitionClass('')
      // 离场结束后再复位方向与定位（Vue 在关闭瞬间清空，React 侧保留至卸载避免离场闪跳）
      setOpenUpward(false)
      setPanelFixedStyle({})
    }, DROPDOWN_DURATION_MS)
  }, [visible])

  useEffect(() => {
    const onViewportChange = () => {
      if (expandedRef.current) {
        updateDropdownPlacement()
      }
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
    'selected-model',
    expanded ? 'expanded' : '',
    expanded && !openUpward ? 'is-open-down' : '',
    expanded && openUpward ? 'is-open-up' : ''
  ]
    .filter(Boolean)
    .join(' ')

  const panelClass = [
    'options-list',
    'model-select-dropdown-portal',
    !openUpward ? 'is-open-down' : 'is-open-up',
    transitionClass
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <div ref={rootRef} className="model-select-dropdown">
      {/* 当前选中的模型（可点击展开） */}
      <div ref={triggerRef} className={triggerClass} onClick={() => onToggle?.()}>
        <div className="model-preview">
          {hasSelectedModel ? (
            <div className="model-icon-wrapper">
              {value.icon ? (
                <img src={value.icon} alt={value.name} className="model-icon" />
              ) : (
                <div
                  className="model-icon placeholder"
                  style={{ background: value.iconBg || '#10B981' }}
                >
                  {value.name?.slice(0, 1) || '?'}
                </div>
              )}
            </div>
          ) : null}
          <div className="model-info">
            <div className="model-name-row">
              <div className={hasSelectedModel ? 'model-name' : 'model-name is-placeholder'}>
                {value.name || '请选择模型'}
              </div>
              {shouldShowModelFreeBadge(value) ? <ModelFreeBadge /> : null}
            </div>
          </div>
        </div>
        {!expanded ? (
          <DownOutlined className="expand-icon" />
        ) : (
          <UpOutlined className="expand-icon" />
        )}
      </div>

      {/* 下拉列表：挂到 body + fixed，避免在 overflow 容器内撑高滚动区、把底部按钮顶下去 */}
      {rendered && typeof document !== 'undefined'
        ? createPortal(
            <div ref={optionsListRef} className={panelClass} style={optionsListStyle}>
              {options.map((option) => (
                <div
                  key={option.id}
                  className={value.id === option.id ? 'option-item selected' : 'option-item'}
                  onClick={() => onSelect?.(option)}
                >
                  <div className="option-left">
                    <div className="model-icon-wrapper">
                      {option.icon ? (
                        <img src={option.icon} alt={option.name} className="model-icon" />
                      ) : (
                        <div
                          className="model-icon placeholder"
                          style={{ background: option.iconBg || '#10B981' }}
                        >
                          {option.name?.slice(0, 1) || '?'}
                        </div>
                      )}
                    </div>
                  </div>
                  <div className="option-right">
                    <div className="option-header">
                      <div className="option-name-row">
                        <span className="option-name">{option.name}</span>
                        {shouldShowModelFreeBadge(option) ? <ModelFreeBadge /> : null}
                        {option.tag ? (
                          <span className={`option-tag ${option.tagType || 'default'}`}>
                            {option.tag}
                          </span>
                        ) : null}
                      </div>
                      {value.id === option.id ? (
                        <CheckCircleFilled className="check-icon" />
                      ) : null}
                    </div>
                    {option.desc || option.supportsAudio ? (
                      <div className="option-meta-row">
                        {option.desc ? <div className="option-desc">{option.desc}</div> : null}
                        {option.supportsAudio ? (
                          <span
                            className="option-capability option-capability--audio"
                            title="支持音画同步"
                          >
                            <span className="option-capability__waves" aria-hidden="true">
                              <i />
                              <i />
                              <i />
                            </span>
                            音画同步
                          </span>
                        ) : null}
                      </div>
                    ) : null}
                    {option.prices && option.prices.length > 0 ? (
                      <div className="option-prices">
                        {option.prices.map((price, index) => (
                          <span key={index} className="price-item">
                            {price.resolution} {price.cost}/张
                          </span>
                        ))}
                      </div>
                    ) : null}
                    <ModelBillingRules billing={option.billing} />
                  </div>
                </div>
              ))}
            </div>,
            document.body
          )
        : null}
    </div>
  )
}

export default ModelSelectDropdown
