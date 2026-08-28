/**
 * 视口适配（与系统 100% 缩放观感一致）：
 *
 * 背景：
 * - Windows 125%/150%/175%/200% 会把 CSS 视口压窄（如 2240@175% → innerWidth≈1280）
 * - 旧方案用「layout=1920 + zoom=css/1920」铺满屏幕，但整页被缩小，文字/控件相对 100% 明显偏小
 * - 浏览器 + 系统缩放已经处理了物理像素；前端再 zoom<1 等于二次缩小
 *
 * 现行策略：
 * - 根节点 zoom 恒为 1，不撑开虚假 layoutWidth（避免半屏空白 + 文字缩小）
 * - 仅写入 data-viewport-* 供宽屏/系统缩放 CSS 微调
 * - 窄 CSS 视口依赖媒体查询与现有 compact CSS（home-new-compact-viewport 等）
 *
 * 由 plugins/viewport-compact-scale.client.ts 与 composables/useViewportCompactScale.ts 共用。
 */

export const VIEWPORT_COMPACT_MEDIA = '(min-width: 769px) and (max-width: 1440px)'
/** @deprecated 紧凑档不再使用根节点 zoom，保留常量以免外部引用报错 */
export const VIEWPORT_COMPACT_ZOOM = 0.75
export const VIEWPORT_DESIGN_WIDTH = 1920
export const VIEWPORT_WIDE_MAX_WIDTH = 3840
export const VIEWPORT_DESIGN_CLAMP_MIN_WIDTH = 1441
export const VIEWPORT_WIDE_TIER_QHD_MAX = 2560
export const VIEWPORT_WIDE_TIER_UHD_MAX = 3840
export type ViewportWideTier = 'design' | 'qhd' | 'uhd' | 'beyond'
export const VIEWPORT_DESIGN_CLAMP_MEDIA = `(min-width: ${VIEWPORT_DESIGN_CLAMP_MIN_WIDTH}px)`
/** @deprecated 使用 VIEWPORT_DESIGN_CLAMP_MEDIA */
export const VIEWPORT_LARGE_DESIGN_WIDTH = VIEWPORT_DESIGN_WIDTH
/** @deprecated 使用 VIEWPORT_DESIGN_CLAMP_MEDIA */
export const VIEWPORT_LARGE_MEDIA = VIEWPORT_DESIGN_CLAMP_MEDIA
export const VIEWPORT_COMPACT_SCALE_STORAGE_KEY = 'viewport-compact-scale:enabled'
export const VIEWPORT_COMPACT_SCALE_CHANGED_EVENT = 'viewport-compact-scale:changed'

const WINDOWS_OS_SCALE_CANDIDATES = [1.25, 1.5, 1.75, 2, 2.25, 2.5, 3] as const

export type ViewportScaleMode = 'none' | 'compact' | 'design-fill' | 'os-corrected' | 'wide'

export interface ViewportScalePlan {
  mode: ViewportScaleMode
  /** 已废弃：恒为 null（不再写 html.width） */
  layoutWidthPx: number | null
  /** 恒为 1：与系统 100% 字号/控件观感一致 */
  zoom: number
  osScale: number
  tierWidth: number
  compact: boolean
  largeClamp: boolean
  osCorrected: boolean
}

export function isMobileOnlyShell(): boolean {
  if (typeof document === 'undefined') return false
  return document.documentElement.classList.contains('mobile-only-shell')
}

export function isWindowsPlatform(): boolean {
  if (typeof navigator === 'undefined') return false
  return /windows/i.test(navigator.userAgent)
}

/**
 * Windows 系统显示缩放（125% / 150% / 175% / 200% 等）。
 * 非 Windows、或约等于 100% 时返回 1，避免误伤 Mac Retina（dpr=2）。
 */
export function getWindowsOsScaleFactor(): number {
  if (typeof window === 'undefined' || !isWindowsPlatform()) return 1
  const dpr = Number(window.devicePixelRatio) || 1
  if (dpr < 1.1) return 1

  let best: number = WINDOWS_OS_SCALE_CANDIDATES[0]
  let bestDiff = Math.abs(dpr - best)
  for (const candidate of WINDOWS_OS_SCALE_CANDIDATES) {
    const diff = Math.abs(dpr - candidate)
    if (diff < bestDiff) {
      best = candidate
      bestDiff = diff
    }
  }

  if (bestDiff <= 0.08) return best
  if (dpr > 1.1 && dpr <= 3) return Math.round(dpr * 100) / 100
  return 1
}

export function getViewportCssWidth(): number {
  if (typeof window === 'undefined') return VIEWPORT_DESIGN_WIDTH
  return window.innerWidth
}

/** 等效物理宽度（CSS 宽 × 系统缩放），用于宽屏档位判断 */
export function getViewportEffectiveWidth(): number {
  return getViewportCssWidth() * getWindowsOsScaleFactor()
}

/**
 * 计算视口方案：zoom 恒 1，只决定 mode / 档位属性。
 * `compactEnabled` 保留参数兼容；不再驱动根节点 zoom。
 */
export function computeViewportScalePlan(input: {
  cssWidth: number
  osScale: number
  compactEnabled: boolean
}): ViewportScalePlan {
  const cssWidth = Math.max(1, Number(input.cssWidth) || VIEWPORT_DESIGN_WIDTH)
  const osScale = input.osScale > 1.01 ? input.osScale : 1

  const base = {
    layoutWidthPx: null as number | null,
    zoom: 1,
    osScale,
    // 档位必须按真实 CSS 宽度：系统缩放后 innerWidth 变窄，不能用 css×os 冒充宽屏
    // （否则会套用 5 列 + grid-auto-rows:250px，把窄视口里的卡片撑得很高）
    tierWidth: cssWidth,
    compact: false,
    largeClamp: false,
    osCorrected: false
  }

  // Windows 系统缩放：只标记 os，布局档位仍跟 cssWidth
  if (osScale > 1) {
    return {
      ...base,
      mode: 'os-corrected',
      osCorrected: true
    }
  }

  // 紧凑 CSS 视口：只区分 mode，不再打 data-viewport-compact-scale
  if (input.compactEnabled && cssWidth >= 769 && cssWidth <= 1440) {
    return {
      ...base,
      mode: 'compact'
    }
  }

  if (cssWidth >= VIEWPORT_DESIGN_WIDTH) {
    return {
      ...base,
      mode: 'wide'
    }
  }

  if (cssWidth >= VIEWPORT_DESIGN_CLAMP_MIN_WIDTH) {
    return {
      ...base,
      mode: 'design-fill'
    }
  }

  return {
    ...base,
    mode: 'none'
  }
}

export function isCompactViewport(): boolean {
  if (typeof window === 'undefined') return false
  return computeViewportScalePlan({
    cssWidth: getViewportCssWidth(),
    osScale: getWindowsOsScaleFactor(),
    compactEnabled: true
  }).mode === 'compact'
}

export function isDesignClampViewport(): boolean {
  if (typeof window === 'undefined') return false
  const w = getViewportCssWidth()
  return w >= VIEWPORT_DESIGN_CLAMP_MIN_WIDTH
}

/** @deprecated 使用 isDesignClampViewport */
export function isLargeViewport(): boolean {
  return isDesignClampViewport()
}

export function getDesignClampZoomForWidth(_width: number): number {
  return 1
}

/** @deprecated 根节点不再缩放，恒为 1 */
export function getDesignClampZoom(): number {
  return 1
}

/** @deprecated 使用 getDesignClampZoom */
export function getLargeViewportZoom(): number {
  return 1
}

/** 宽屏档位：按真实 CSS 视口宽度（勿用 css×系统缩放，否则 OS 缩放会误判为 2K/4K） */
export function getViewportWideTier(
  width = typeof window !== 'undefined' ? getViewportCssWidth() : VIEWPORT_DESIGN_WIDTH
): ViewportWideTier {
  if (width <= VIEWPORT_DESIGN_WIDTH) return 'design'
  if (width <= VIEWPORT_WIDE_TIER_QHD_MAX) return 'qhd'
  if (width <= VIEWPORT_WIDE_TIER_UHD_MAX) return 'uhd'
  return 'beyond'
}

export function isAboveDesignViewport(): boolean {
  if (typeof window === 'undefined') return false
  return getViewportCssWidth() > VIEWPORT_DESIGN_WIDTH
}

function applyScaleCssVars(el: HTMLElement, zoom: number) {
  el.style.setProperty('--viewport-scale-factor', String(zoom))
  el.style.setProperty('--viewport-compensated-vh', `calc(100vh / ${zoom})`)
  el.style.setProperty('--viewport-compensated-dvh', `calc(100dvh / ${zoom})`)
}

function clearScaleCssVars(el: HTMLElement) {
  el.style.removeProperty('--viewport-scale-factor')
  el.style.removeProperty('--viewport-compensated-vh')
  el.style.removeProperty('--viewport-compensated-dvh')
}

function clearWideTierAttrs(el: HTMLElement) {
  el.removeAttribute('data-viewport-above-design')
  el.removeAttribute('data-viewport-wide-tier')
  el.removeAttribute('data-viewport-scaled')
  el.removeAttribute('data-viewport-os-scale')
}

function applyWideTierAttrs(el: HTMLElement, width: number, osScale = 1) {
  el.setAttribute('data-viewport-scaled', '1')
  const tier = getViewportWideTier(width)
  el.setAttribute('data-viewport-wide-tier', tier)
  if (width > VIEWPORT_DESIGN_WIDTH) {
    el.setAttribute('data-viewport-above-design', '1')
  } else {
    el.removeAttribute('data-viewport-above-design')
  }
  if (osScale > 1) {
    el.setAttribute('data-viewport-os-scale', String(osScale))
  } else {
    el.removeAttribute('data-viewport-os-scale')
  }
}

function clearLayoutWidth(el: HTMLElement) {
  el.style.removeProperty('width')
  el.classList.remove('viewport-os-scale-corrected')
  el.classList.remove('viewport-layout-filled')
}

export function readViewportCompactScalePreference(): boolean {
  if (typeof window === 'undefined') return true
  try {
    const raw = localStorage.getItem(VIEWPORT_COMPACT_SCALE_STORAGE_KEY)
    if (raw === null) return true
    return raw !== '0' && raw !== 'false'
  } catch {
    return true
  }
}

export function writeViewportCompactScalePreference(enabled: boolean): void {
  if (typeof window === 'undefined') return
  try {
    localStorage.setItem(VIEWPORT_COMPACT_SCALE_STORAGE_KEY, enabled ? '1' : '0')
  } catch {
    /* ignore */
  }
  window.dispatchEvent(new CustomEvent(VIEWPORT_COMPACT_SCALE_CHANGED_EVENT))
}

export function clearCompactLayout(el: HTMLElement) {
  el.removeAttribute('data-viewport-compact-scale')
}

export function clearLargeLayout(el: HTMLElement) {
  el.removeAttribute('data-viewport-large-clamp')
  clearWideTierAttrs(el)
  clearScaleCssVars(el)
}

export function clearAllViewportScale(el: HTMLElement) {
  clearCompactLayout(el)
  clearLargeLayout(el)
  clearWideTierAttrs(el)
  clearScaleCssVars(el)
  clearLayoutWidth(el)
  el.style.removeProperty('zoom')
}

function applyPlan(el: HTMLElement, plan: ViewportScalePlan) {
  // 清除历史 zoom / 虚假宽度，避免半屏空白或文字二次缩小
  clearLayoutWidth(el)
  el.style.removeProperty('zoom')
  clearScaleCssVars(el)
  clearCompactLayout(el)
  el.removeAttribute('data-viewport-large-clamp')
  clearWideTierAttrs(el)

  // zoom 恒 1；仍写入变量，供旧 CSS/引导层读取时得到恒等变换
  applyScaleCssVars(el, 1)

  if (plan.compact) {
    el.setAttribute('data-viewport-compact-scale', '1')
  }

  // 系统缩放：只打 data 标记供调试/CSS；不再加 viewport-os-scale-corrected
  // （该类曾配合 width:1920，会强制恢复登录大内边距，在真实窄 CSS 视口下再次挤坏 Tab）
  if (plan.osCorrected) {
    el.setAttribute('data-viewport-os-scale', String(plan.osScale))
  }

  if (plan.mode !== 'none') {
    applyWideTierAttrs(el, plan.tierWidth, plan.osScale)
  }
}

/** @deprecated 由 applyViewportCompactScale 统一处理 */
export function applyCompactLayout(el: HTMLElement) {
  applyPlan(
    el,
    computeViewportScalePlan({
      cssWidth: getViewportCssWidth(),
      osScale: getWindowsOsScaleFactor(),
      compactEnabled: true
    })
  )
}

/** @deprecated 由 applyViewportCompactScale 统一处理 */
export function applyDesignClampLayout(el: HTMLElement) {
  applyPlan(
    el,
    computeViewportScalePlan({
      cssWidth: getViewportCssWidth(),
      osScale: getWindowsOsScaleFactor(),
      compactEnabled: false
    })
  )
}

/** @deprecated 使用 applyDesignClampLayout */
export function applyLargeLayout(el: HTMLElement) {
  applyDesignClampLayout(el)
}

/**
 * 应用视口档位标记（不再缩放根节点）。
 * @param enabled 紧凑档 data 标记开关（不影响 zoom；窄屏样式主要靠媒体查询）
 */
export function applyViewportCompactScale(enabled = true) {
  if (typeof document === 'undefined') return
  const el = document.documentElement
  if (isMobileOnlyShell()) {
    clearAllViewportScale(el)
    return
  }

  const plan = computeViewportScalePlan({
    cssWidth: getViewportCssWidth(),
    osScale: getWindowsOsScaleFactor(),
    compactEnabled: enabled
  })

  applyPlan(el, plan)
}
