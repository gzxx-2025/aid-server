/** 与 {@link useAuthPublicConfig} 写入 sessionStorage 的 key 一致 */
const PUBLIC_CONFIG_STORAGE_KEY = 'auth:public-config:v3'

/** sessionStorage 兜底，避免 Nuxt 分包后内存单例不一致 */
const PENDING_CAPTCHA_TOKEN_KEY = 'auth:pending-captcha-token'

/** /captcha/check 返回的一次性 token，供受保护接口（/auth/login、/auth/sendCode）请求头使用 */
let pendingCaptchaToken: string | null = null

export function setPendingCaptchaToken(token: string) {
  const t = String(token || '').trim()
  pendingCaptchaToken = t || null
  if (!(typeof window !== 'undefined')) return
  if (t) sessionStorage.setItem(PENDING_CAPTCHA_TOKEN_KEY, t)
  else sessionStorage.removeItem(PENDING_CAPTCHA_TOKEN_KEY)
}

export function clearPendingCaptchaToken() {
  pendingCaptchaToken = null
  if ((typeof window !== 'undefined')) sessionStorage.removeItem(PENDING_CAPTCHA_TOKEN_KEY)
}

/** 取出并清空，保证 token 仅随下一次受保护请求发送一次 */
export function takePendingCaptchaToken(): string | undefined {
  let token = pendingCaptchaToken
  pendingCaptchaToken = null
  if ((typeof window !== 'undefined')) {
    if (!token) token = sessionStorage.getItem(PENDING_CAPTCHA_TOKEN_KEY)
    sessionStorage.removeItem(PENDING_CAPTCHA_TOKEN_KEY)
  }
  return token || undefined
}

/** 从 public-config 缓存判断行为验证码是否已开启（与登录页 captchaEnabled 逻辑一致） */
export function isBehaviorCaptchaEnabledFromCache(): boolean {
  if (!(typeof window !== 'undefined')) return false
  try {
    const raw = sessionStorage.getItem(PUBLIC_CONFIG_STORAGE_KEY)
    if (!raw) return false
    const data = JSON.parse(raw) as {
      captcha?: { enabled?: boolean; imagesReady?: boolean; applicationOk?: boolean }
    }
    const c = data?.captcha
    if (!c?.enabled) return false
    if (c.imagesReady === false || c.applicationOk === false) return false
    return true
  } catch {
    return false
  }
}

function normalizeRequestPath(url?: string): string {
  const u = String(url || '').split('?')[0] || ''
  const authIdx = u.indexOf('/auth/')
  if (authIdx >= 0) return u.slice(authIdx)
  return u.startsWith('/') ? u : `/${u}`
}

/** 行为验证码受保护的 C 端认证接口（见接口文档「受保护接口调用方式」） */
export function isCaptchaProtectedAuthPath(url?: string): boolean {
  const path = normalizeRequestPath(url)
  return path === '/auth/login' || path === '/auth/sendCode'
}
