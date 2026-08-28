const DEFAULT_MODAL_Z_INDEX = 1000
const DEFAULT_MODAL_STACK_STEP = 100

/**
 * 为即将打开的嵌套弹窗计算高于当前所有 Ant Design Modal 的层级。
 * 读取 wrap 的实际样式，兼容父弹窗使用动态 zIndex 的情况。
 */
export function resolveStackedModalZIndex(
  baseZIndex = DEFAULT_MODAL_Z_INDEX,
  stackStep = DEFAULT_MODAL_STACK_STEP
): number {
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return baseZIndex + stackStep
  }

  let maxZIndex = baseZIndex
  document.querySelectorAll('.ant-modal-wrap').forEach((element) => {
    const style = window.getComputedStyle(element)
    if (style.display === 'none' || style.visibility === 'hidden') return
    const zIndex = Number.parseInt(style.zIndex, 10)
    if (Number.isFinite(zIndex) && zIndex > maxZIndex) maxZIndex = zIndex
  })
  return maxZIndex + stackStep
}
