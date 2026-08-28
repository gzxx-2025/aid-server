export type EditorSelectionRestoreReason = 'external-html' | 'user-caret'

export function shouldScrollEditorAfterSelectionRestore(
  reason: EditorSelectionRestoreReason
): boolean {
  return reason === 'user-caret'
}

/** 外部 HTML 回填时：用户已经点到下拉框等别处，就不要再把焦点抢回编辑器。 */
export function shouldRestoreEditorFocusAfterExternalSync(params: {
  restoreFocusRequested: boolean
  editorRoot: { contains: (node: Node | null) => boolean } | null | undefined
  activeElement: Node | null | undefined
}): boolean {
  if (!params.restoreFocusRequested || !params.editorRoot) return false
  const active = params.activeElement
  if (!active) return false
  return active === params.editorRoot || params.editorRoot.contains(active)
}

export function restoreEditorScrollTop(
  scroller: { scrollTop: number } | null | undefined,
  scrollTop: number
): void {
  if (!scroller) return
  if (scroller.scrollTop !== scrollTop) scroller.scrollTop = scrollTop
}

export function resolveQuillScrollRoot(
  editorRoot: HTMLElement,
  overflowYOf: (el: HTMLElement) => string = (el) =>
    typeof getComputedStyle === 'function' ? getComputedStyle(el).overflowY : ''
): HTMLElement {
  const container = editorRoot.parentElement
  if (container) {
    const overflowY = overflowYOf(container)
    if (overflowY === 'auto' || overflowY === 'scroll') return container
  }
  return editorRoot
}
