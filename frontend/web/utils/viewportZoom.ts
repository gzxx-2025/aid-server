/**
 * 视口 zoom 工具：用于处理低分辨率紧凑缩放（`utils/viewportCompactScale.ts`）对 `html` 应用的 `zoom: 0.75`。
 *
 * 场景：`Teleport to="body"` 后的 fixed 面板，依赖 `getBoundingClientRect()` 与 `window.innerHeight` 计算位置。
 * CSS `zoom` 在 html 上生效时，`getBoundingClientRect()` 返回**视觉坐标**（已缩放），
 * 但 fixed 元素声明的 `top/left/width` 会被 zoom 再乘一次，导致视觉位置 = 声明值 × zoom。
 *
 * 为了让 fixed 面板视觉上正好贴在触发元素下方/上方，需把计算出的视觉坐标除以 zoom，
 * 作为声明值传给样式，zoom 再乘回去后才等于目标视觉坐标。
 */

/** 读取 `document.documentElement` 当前生效的 zoom 因子；SSR / 未设置时返回 1。 */
export function readHtmlZoom(): number {
  if (typeof window === 'undefined' || typeof document === 'undefined') return 1
  try {
    const inline = document.documentElement.style.zoom
    const val = inline
      ? parseFloat(inline)
      : parseFloat(getComputedStyle(document.documentElement).zoom || '1')
    if (Number.isFinite(val) && val > 0) return val
  } catch {
    /* ignore */
  }
  return 1
}

/**
 * 将视觉像素值换算为声明值（声明后 zoom 会放大回视觉值）。
 * 例：zoom=0.75 时，readHtmlZoom() 返回 0.75，想让面板视觉出现在 y=100，
 * 则应声明 `top: toLayoutPx(100) = 133.33px`。
 */
export function toLayoutPx(visualPx: number, zoom: number = readHtmlZoom()): number {
  if (!Number.isFinite(zoom) || zoom <= 0) return visualPx
  return visualPx / zoom
}
