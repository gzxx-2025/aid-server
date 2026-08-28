/**
 * 将文本写入系统剪贴板，并兼容非安全上下文和不支持 Clipboard API 的浏览器。
 */
export async function copyPlainText(text: string): Promise<boolean> {
  if (
    !text ||
    typeof window === 'undefined' ||
    typeof document === 'undefined' ||
    typeof navigator === 'undefined'
  ) {
    return false
  }

  if (window.isSecureContext && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 权限拒绝时继续尝试浏览器兼容复制。
    }
  }

  if (!document.body || typeof document.execCommand !== 'function') return false

  const activeElement = document.activeElement
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.readOnly = true
  textarea.setAttribute('aria-hidden', 'true')
  Object.assign(textarea.style, {
    position: 'fixed',
    left: '-9999px',
    top: '0',
    opacity: '0',
    pointerEvents: 'none'
  })
  document.body.appendChild(textarea)

  let copied = false
  try {
    textarea.focus()
    textarea.select()
    textarea.setSelectionRange(0, textarea.value.length)
    copied = document.execCommand('copy')
  } catch {
    copied = false
  } finally {
    textarea.remove()
    if (activeElement instanceof HTMLElement) activeElement.focus()
  }
  return copied
}
