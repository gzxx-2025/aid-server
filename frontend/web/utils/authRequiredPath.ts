/** 与原 auth.global 对齐：未登录不可直达的路径前缀 */
export const AUTH_REQUIRED_PREFIXES = ['/works', '/assets', '/create', '/invite'] as const

export function isAuthRequiredPath(path: string): boolean {
  const normalized = (path.split('?')[0].split('#')[0] || '/').replace(/\/$/, '') || '/'
  return AUTH_REQUIRED_PREFIXES.some(
    (prefix) => normalized === prefix || normalized.startsWith(`${prefix}/`)
  )
}

export function isForgotPasswordPath(path: string): boolean {
  const normalized = (path.split('?')[0].split('#')[0] || '/').replace(/\/$/, '') || '/'
  return normalized === '/forgot-password' || normalized.startsWith('/forgot-password/')
}

/**
 * 仅允许站内相对路径作为登录后回跳，避免开放重定向。
 * 找回密码页本身是游客可访问流程，不能作为登录回跳目标（否则关弹窗/回广场会再次被打开登录）。
 */
export function sanitizeLoginRedirect(raw?: string | null): string {
  const value = String(raw || '').trim()
  if (!value.startsWith('/') || value.startsWith('//') || value.startsWith('/login')) return '/'
  if (isForgotPasswordPath(value)) return '/'
  return value
}

export function readInviteCodeFromSearch(searchParams: {
  get: (key: string) => string | null
}): string {
  const raw = String(searchParams.get('invite') || searchParams.get('inviteCode') || '').trim()
  return raw ? raw.slice(0, 8) : ''
}
