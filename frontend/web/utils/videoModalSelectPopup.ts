/**
 * 分镜视频底部 Select 浮层必须挂 document.body。
 * 挂进 .ant-modal 后，下拉视觉盖住描述框，但点击会落到 Quill 上，选项选不中；
 * 大屏 zoom 反补偿也只对 body 浮层成立。自动合上靠失焦守卫，不靠改挂载点。
 */
export function resolveVideoModalSelectPopupContainer(): HTMLElement {
  return document.body
}

export function blurVideoPromptEditorIfFocused(
  activeElement: { closest?: (selector: string) => { blur?: () => void } | null } | null | undefined = typeof document !==
  'undefined'
    ? document.activeElement
    : null
): void {
  const editor = activeElement?.closest?.('.ql-editor')
  editor?.blur?.()
}
