/** 仅允许可导航的 HTTP(S) 地址，拒绝 javascript:/data: 等可执行协议。 */
export function resolveSafeHttpUrl(raw: unknown, baseUrl?: string): string | null {
  const value = String(raw ?? '').trim()
  if (!value) return null
  try {
    const parsed = new URL(value, baseUrl || (typeof window !== 'undefined' ? window.location.href : undefined))
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.href : null
  } catch {
    return null
  }
}

/** 将后端配置的站内链接限制在当前源，避免协议相对地址跳出站点。 */
export function resolveSafeInternalPath(raw: unknown, baseUrl?: string): string | null {
  const value = String(raw ?? '').trim()
  const hasControlCharacter = Array.from(value).some((char) => {
    const code = char.charCodeAt(0)
    return code <= 31 || code === 127
  })
  if (!value || hasControlCharacter) return null
  const base = baseUrl || (typeof window !== 'undefined' ? window.location.href : '')
  if (!base) return null
  try {
    const target = new URL(value, base)
    const current = new URL(base)
    if (target.origin !== current.origin) return null
    return `${target.pathname}${target.search}${target.hash}`
  } catch {
    return null
  }
}
