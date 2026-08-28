'use client'

import { useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { userModelListByFuncCodes } from '~/utils/businessApi'
import { AI_MODEL_FUNC_CODE } from '~/utils/aiModelFuncCodes'
import { modelsFromListByFuncGroups } from '~/utils/modelListByFuncBatch'
import { resolveUpscaleResolutionFromModel } from '~/utils/modelCapability'
import { shouldShowModelFreeBadge } from '~/utils/modelFreeStatus'
import type { BillingQuoteRequest, ModelBillingDetailVO, UserModelListItem } from '~/types/business-api'
import { BillingQuoteHint } from '~/components/common/BillingQuoteHint'
import { ModelBillingRules } from '~/components/common/ModelBillingRules'
import { ModelFreeBadge } from '~/components/common/ModelFreeBadge'
import { useBillingQuote } from '~/hooks/useBillingQuote'
import './UpscaleModelPopover.css'

export interface UpscaleModelOption {
  id: string
  name: string
  icon?: string
  iconBg?: string
  desc?: string
  resolution: string
  resolutionLabel?: string
  isFree?: boolean
  billing?: ModelBillingDetailVO | null
}

export type UpscaleBillingTarget =
  | { kind: 'storyboard'; genRecordId: number }
  | { kind: 'form'; imageId: number }

interface Props {
  imageIndex?: number
  disabled?: boolean
  generating?: boolean
  /** form-image 用小写 2k；分镜图高清用大写 2K */
  resolutionFormat?: 'lower' | 'upper'
  /** 父级批量 listByFunc 预取的高清模型池（funcCode=image_upscale） */
  prefetchedModels?: UserModelListItem[] | null
  /** 点击即提交前，按当前图片与该模型展示服务端权威报价。 */
  billingTarget?: UpscaleBillingTarget
  onSelect?: (payload: { modelCode: string; resolution: string; imageIndex: number }) => void
  /** 原默认插槽：触发区内容 */
  children?: ReactNode
}

const ESTIMATED_PANEL_MIN = 200
const GAP = 8

/** 原 <Transition name="upscale-model-fade"> 时长（transition: opacity/transform 0.15s ease） */
const FADE_DURATION_MS = 150

function UpscaleOptionQuote({
  target,
  option
}: {
  target?: UpscaleBillingTarget
  option: UpscaleModelOption
}) {
  const request = useMemo<BillingQuoteRequest | null>(() => {
    if (!target) return null
    return target.kind === 'storyboard'
      ? {
          quoteType: 'STORYBOARD_IMAGE_UPSCALE',
          payload: {
            genRecordId: target.genRecordId,
            modelCode: option.id,
            resolution: option.resolution
          }
        }
      : {
          quoteType: 'FORM_IMAGE_UPSCALE',
          payload: {
            imageId: target.imageId,
            modelCode: option.id,
            resolution: option.resolution
          }
        }
  }, [option.id, option.resolution, target])
  // 整个浮层只挂载这一份报价 hook；快速划过选项由防抖合并为最后一项。
  const result = useBillingQuote(request, { debounceMs: 320 })
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

function normalizeSizeCode(raw: string): string {
  const t = String(raw || '').trim()
  if (!t) return ''
  if (/^\d+k$/i.test(t)) return t.toLowerCase()
  return t.toLowerCase()
}

function formatResolutionLabel(raw: string): string {
  const normalized = normalizeSizeCode(raw) || '2k'
  return /^\d+k$/i.test(normalized) ? normalized.toUpperCase() : normalized
}

export function UpscaleModelPopover({
  imageIndex = 0,
  disabled = false,
  generating = false,
  resolutionFormat = 'lower',
  prefetchedModels = null,
  billingTarget,
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
  const [modelOptions, setModelOptions] = useState<UpscaleModelOption[]>([])
  const [billingPreviewModelCode, setBillingPreviewModelCode] = useState('')

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
    if (!value) setBillingPreviewModelCode('')
  }

  function mapModelRow(item: UserModelListItem): UpscaleModelOption | null {
    const code = String(item.modelCode || '').trim()
    if (!code) return null
    const resolution = resolveUpscaleResolutionFromModel(item, resolutionFormat)
    const rawSize = String(
      item.defaultSizeCode ||
        (item.capability as { defaultSize?: string } | null | undefined)?.defaultSize ||
        resolution
    ).trim()
    return {
      id: code,
      name: item.modelName || code,
      icon: String(item.providerLogo || '').trim() || undefined,
      iconBg: '#60A5FA',
      desc: '',
      resolution,
      resolutionLabel: formatResolutionLabel(rawSize || resolution),
      isFree: item.isFree === true,
      billing: item.billing
    }
  }

  function applyModelRows(models: UserModelListItem[]) {
    setModelOptions(
      (Array.isArray(models) ? models : [])
        .map(mapModelRow)
        .filter((m): m is UpscaleModelOption => Boolean(m))
    )
  }

  async function loadOptions() {
    const prefetched = prefetchedModels
    if (Array.isArray(prefetched) && prefetched.length > 0) {
      applyModelRows(prefetched)
      return
    }

    setLoading(true)
    try {
      const groups = await userModelListByFuncCodes([AI_MODEL_FUNC_CODE.IMAGE_UPSCALE])
      const models = modelsFromListByFuncGroups(groups, AI_MODEL_FUNC_CODE.IMAGE_UPSCALE)
      applyModelRows(models)
    } catch {
      setModelOptions([])
    } finally {
      setLoading(false)
    }
  }

  function pick(opt: UpscaleModelOption) {
    if (generating) return
    const modelCode = String(opt.id || '').trim()
    if (!modelCode) return
    onSelect?.({
      modelCode,
      resolution: opt.resolution,
      imageIndex
    })
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

  // 原 deep watch prefetchedModels：父级传入数组，以引用变化为准
  useEffect(() => {
    if (openRef.current) void loadOptions()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prefetchedModels])

  // 原 watch(() => open.value, ..., { flush: 'post' })
  useEffect(() => {
    if (open) {
      setReadyToShowPanel(false)
      unbindClickOutside()
      void loadOptions()
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

  // 复刻 <Transition name="upscale-model-fade">：进入/离开动画 class + 离场后再卸载
  const visible = open && readyToShowPanel
  useEffect(() => {
    if (transitionTimerRef.current) {
      clearTimeout(transitionTimerRef.current)
      transitionTimerRef.current = null
    }

    if (visible) {
      renderedRef.current = true
      setRendered(true)
      setTransitionClass('upscale-model-fade-enter-from upscale-model-fade-enter-active')
      nextFrame(() => {
        setTransitionClass('upscale-model-fade-enter-active')
        transitionTimerRef.current = setTimeout(() => {
          transitionTimerRef.current = null
          setTransitionClass('')
        }, FADE_DURATION_MS)
      })
      return
    }

    if (!renderedRef.current) return
    setTransitionClass('upscale-model-fade-leave-active upscale-model-fade-leave-to')
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
    'upscale-model-panel',
    !openUpward ? 'is-open-down' : 'is-open-up',
    transitionClass
  ]
    .filter(Boolean)
    .join(' ')
  const billingPreviewOption = useMemo(
    () =>
      modelOptions.find((option) => option.id === billingPreviewModelCode) ?? null,
    [billingPreviewModelCode, modelOptions]
  )

  return (
    <div ref={rootRef} className="upscale-model-popover-root">
      <div
        ref={triggerRef}
        className={
          disabled
            ? 'upscale-model-popover-trigger is-disabled'
            : 'upscale-model-popover-trigger'
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
              aria-label="变清晰模型"
            >
              {loading ? (
                <div className="upscale-model-empty">加载中…</div>
              ) : modelOptions.length ? (
                <div className="upscale-model-list">
                  {modelOptions.map((opt) => (
                    <button
                      key={`upscale-${opt.id}`}
                      type="button"
                      className="upscale-model-item"
                      role="menuitem"
                      disabled={generating}
                      onMouseEnter={() => setBillingPreviewModelCode(opt.id)}
                      onFocus={() => setBillingPreviewModelCode(opt.id)}
                      onClick={(e) => {
                        e.stopPropagation()
                        pick(opt)
                      }}
                    >
                      <div className="upscale-model-thumb" aria-hidden="true">
                        {opt.icon ? (
                          <img src={opt.icon} alt={opt.name} className="upscale-model-thumb__img" />
                        ) : (
                          <span className="upscale-model-thumb__letter">
                            {(opt.name || '?').slice(0, 1)}
                          </span>
                        )}
                      </div>
                      <div className="upscale-model-body">
                        <div className="upscale-model-title-row">
                          <span className="upscale-model-title">{opt.name}</span>
                          {shouldShowModelFreeBadge(opt) ? <ModelFreeBadge /> : null}
                          {opt.resolutionLabel ? (
                            <span className="upscale-model-tag">{opt.resolutionLabel}</span>
                          ) : null}
                        </div>
                        {opt.desc ? <p className="upscale-model-desc">{opt.desc}</p> : null}
                      </div>
                    </button>
                  ))}
                </div>
              ) : (
                <div className="upscale-model-empty">暂无可用高清模型</div>
              )}
              {billingPreviewOption ? (
                <div className="upscale-model-billing-preview" aria-live="polite">
                  <div className="upscale-model-billing-preview__name">
                    {billingPreviewOption.name} 价格
                  </div>
                  <ModelBillingRules billing={billingPreviewOption.billing} maxRules={2} />
                  <UpscaleOptionQuote target={billingTarget} option={billingPreviewOption} />
                </div>
              ) : null}
            </div>,
            document.body
          )
        : null}
    </div>
  )
}

export default UpscaleModelPopover
