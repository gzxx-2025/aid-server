'use client'

import { useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Alert } from 'antd'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { BillingQuoteHint } from '~/components/common/BillingQuoteHint'
import { useBillingQuote } from '~/hooks/useBillingQuote'
import type { BillingQuoteRequest } from '~/types/business-api'
import type { AgentOption } from '~/types/modelAgentOptions'
import { aidAgentList } from '~/utils/businessApi'
import { useCreationStore } from '~/stores/creation'
import { buildAidAgentListScopeParams } from '~/utils/createFlowProjectContext'
import {
  CHARACTER_CARD_IMAGE_AGENT_BIZ_CATEGORY,
  agentOptionsFromGroup
} from '~/utils/extractAgentBiz'
import './SettingCardImagePopover.css'

interface Props {
  imageIndex?: number
  isSupported?: boolean
  isWhiteBaseReady?: boolean
  disabled?: boolean
  generating?: boolean
  /** 点击智能体即提交前，按当前图片展示服务端权威报价。 */
  billingImageId?: number
  onSelect?: (payload: { agentCode?: string; imageIndex: number }) => void
  /** 原默认插槽：触发区内容 */
  children?: ReactNode
}

const ESTIMATED_PANEL_MIN = 220
const GAP = 8

/** 原 <Transition name="setting-card-fade"> 时长（transition: opacity/transform 0.15s ease） */
const FADE_DURATION_MS = 150

function SettingCardAgentQuote({ imageId, agentCode }: { imageId?: number; agentCode: string }) {
  const request = useMemo<BillingQuoteRequest | null>(
    () =>
      imageId && agentCode
        ? {
            quoteType: 'FORM_CARD_IMAGE',
            payload: { imageIds: [imageId], agentCode }
          }
        : null,
    [agentCode, imageId]
  )
  const result = useBillingQuote(request, { debounceMs: 120 })
  return (
    <BillingQuoteHint
      quote={result.quote}
      loading={result.loading}
      error={result.error}
      active={Boolean(request)}
    />
  )
}

/** 对齐 Vue transition 的 nextFrame：两帧后再切换 class 才能触发过渡 */
function nextFrame(cb: () => void) {
  requestAnimationFrame(() => {
    requestAnimationFrame(cb)
  })
}

export function SettingCardImagePopover({
  imageIndex = 0,
  isSupported = false,
  isWhiteBaseReady = false,
  disabled = false,
  generating = false,
  billingImageId,
  onSelect,
  children
}: Props) {
  const rootRef = useRef<HTMLDivElement | null>(null)
  const triggerRef = useRef<HTMLDivElement | null>(null)
  const panelRef = useRef<HTMLDivElement | null>(null)
  const [open, setOpen] = useState(false)
  const [openUpward, setOpenUpward] = useState(false)
  const [readyToShowPanel, setReadyToShowPanel] = useState(false)
  const [panelFixedStyle, setPanelFixedStyle] = useState<Record<string, string>>({})

  const [loading, setLoading] = useState(false)
  const [agentOptions, setAgentOptions] = useState<AgentOption[]>([])

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

  async function loadOptions() {
    setLoading(true)
    try {
      const groups = await aidAgentList({
        bizCategoryCodes: [CHARACTER_CARD_IMAGE_AGENT_BIZ_CATEGORY],
        ...buildAidAgentListScopeParams(useCreationStore.getState())
      })
      setAgentOptions(agentOptionsFromGroup(groups, CHARACTER_CARD_IMAGE_AGENT_BIZ_CATEGORY))
    } catch {
      setAgentOptions([])
    } finally {
      setLoading(false)
    }
  }

  function pickAgent(opt: AgentOption) {
    if (generating) return
    const agentCode = String(opt?.id || '').trim()
    if (!agentCode) return
    onSelect?.({ agentCode, imageIndex })
    setOpenBoth(false)
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
    if (disabled) return
    setOpenBoth(!openRef.current)
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
      if (isSupported && isWhiteBaseReady) {
        void loadOptions()
      }
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

  // 复刻 <Transition name="setting-card-fade">：进入/离开动画 class + 离场后再卸载
  const visible = open && readyToShowPanel
  useEffect(() => {
    if (transitionTimerRef.current) {
      clearTimeout(transitionTimerRef.current)
      transitionTimerRef.current = null
    }

    if (visible) {
      renderedRef.current = true
      setRendered(true)
      setTransitionClass('setting-card-fade-enter-from setting-card-fade-enter-active')
      nextFrame(() => {
        setTransitionClass('setting-card-fade-enter-active')
        transitionTimerRef.current = setTimeout(() => {
          transitionTimerRef.current = null
          setTransitionClass('')
        }, FADE_DURATION_MS)
      })
      return
    }

    if (!renderedRef.current) return
    setTransitionClass('setting-card-fade-leave-active setting-card-fade-leave-to')
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
    'setting-card-panel',
    !openUpward ? 'is-open-down' : 'is-open-up',
    transitionClass
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <div ref={rootRef} className="setting-card-popover-root">
      <div
        ref={triggerRef}
        className={
          disabled ? 'setting-card-popover-trigger is-disabled' : 'setting-card-popover-trigger'
        }
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
              aria-label="生成角色设定卡"
            >
              {!isSupported ? (
                <Alert
                  type="info"
                  showIcon
                  message="仅支持角色形态"
                  className="setting-card-alert"
                />
              ) : !isWhiteBaseReady ? (
                <Alert
                  type="warning"
                  showIcon
                  message="请先选择平台生成或本地上传的角色图"
                  className="setting-card-alert"
                />
              ) : loading ? (
                <div className="setting-card-empty">加载中…</div>
              ) : (
                <div className="setting-card-scroll">
                  {agentOptions.length ? (
                    <>
                      <p className="setting-card-section-label">智能体</p>
                      <div className="setting-card-list">
                        {agentOptions.map((opt) => (
                          <button
                            key={`agent-${opt.id}`}
                            type="button"
                            className="setting-card-item"
                            role="menuitem"
                            disabled={generating}
                            onClick={(e) => {
                              e.stopPropagation()
                              pickAgent(opt)
                            }}
                          >
                            <div
                              className="setting-card-thumb setting-card-thumb--agent"
                              aria-hidden="true"
                            >
                              {opt.thumbnail ? (
                                <ShimmerImage
                                  src={opt.thumbnail}
                                  alt={opt.name}
                                  imgClass="setting-card-thumb__img"
                                  wrapperClass="setting-card-thumb__shimmer"
                                  objectFit="cover"
                                  revealDirection="fade"
                                  minShimmerMs={280}
                                />
                              ) : (
                                <span className="setting-card-thumb__letter">
                                  {(opt.name || '?').slice(0, 1)}
                                </span>
                              )}
                            </div>
                            <div className="setting-card-body">
                              <div className="setting-card-title-row">
                                <span className="setting-card-title">{opt.name}</span>
                                <span className="setting-card-tag">智能体</span>
                              </div>
                              {opt.desc ? <p className="setting-card-desc">{opt.desc}</p> : null}
                              <SettingCardAgentQuote
                                imageId={billingImageId}
                                agentCode={String(opt.id || '').trim()}
                              />
                            </div>
                          </button>
                        ))}
                      </div>
                    </>
                  ) : (
                    <div className="setting-card-empty">暂无可用智能体</div>
                  )}
                </div>
              )}
            </div>,
            document.body
          )
        : null}
    </div>
  )
}

export default SettingCardImagePopover
