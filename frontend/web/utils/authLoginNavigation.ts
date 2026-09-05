import { openLoginModal, closeLoginModal, useLoginModalStore } from '@/stores/loginModal'
import { isAuthRequiredPath, sanitizeLoginRedirect } from '~/utils/authRequiredPath'

/** 离开需登录路由（由 RouteGuard / AppBootstrap 监听并 soft replace 到首页） */
export const AID_LEAVE_AUTH_ROUTE_EVENT = 'aid:leave-auth-route'

export type RequireLoginOptions = {
  /**
   * 登录成功后回跳。
   * - undefined：默认取当前 URL
   * - null：显式不回跳
   */
  redirect?: string | null
  /** 是否离开当前需登录路由（默认 true，避免守卫/API 反复顶牛） */
  leaveAuthRoute?: boolean
}

function currentLocationHref(): string {
  if (typeof window === 'undefined') return '/'
  return `${window.location.pathname}${window.location.search}${window.location.hash}`
}

/** 清本地登录态；lazy import user store，避免 api ↔ store 环依赖 */
export function clearAuthSessionArtifacts(): void {
  if (typeof window === 'undefined') return
  try {
    localStorage.removeItem('token')
    localStorage.removeItem('user-info')
  } catch {
    /* ignore */
  }
  void import('@/stores/user')
    .then(({ useUserStore }) => {
      const state = useUserStore.getState()
      if (state.token || state.user) state.logout()
    })
    .catch(() => {
      /* ignore */
    })
}

function resolveRequireLoginRedirect(options?: RequireLoginOptions): string | null {
  if (!options || options.redirect === undefined) {
    return sanitizeLoginRedirect(currentLocationHref())
  }
  if (options.redirect == null) return null
  return sanitizeLoginRedirect(options.redirect)
}

/**
 * 统一「需要登录」入口（弹窗时代）：只开登录弹窗，禁止再跳 /login 路由。
 * /login 仅作旧书签兼容页，由 app/login/page.tsx 自己落到首页并开弹窗。
 */
export function requireLogin(options?: RequireLoginOptions): void {
  if (typeof window === 'undefined') return

  const pathname = window.location.pathname
  // 兼容页自己会 openLoginModal + replace('/')，此处介入会打架
  if (pathname === '/login' || pathname.startsWith('/login/')) return

  const redirect = resolveRequireLoginRedirect(options)
  const alreadyOpen = useLoginModalStore.getState().open

  if (alreadyOpen) {
    if (options && options.redirect !== undefined) {
      openLoginModal({ redirect })
    }
  } else {
    openLoginModal({ redirect })
  }

  const leave = options?.leaveAuthRoute !== false
  if (leave && isAuthRequiredPath(pathname)) {
    window.dispatchEvent(new CustomEvent(AID_LEAVE_AUTH_ROUTE_EVENT))
  }
}

/**
 * 401 / 「请先登录」等：清会话并唤起登录弹窗。
 * 绝不能再用 location.assign('/login')——会与兼容页 replace('/') 形成死循环。
 */
export function redirectToLogin(): void {
  if (typeof window === 'undefined') return
  clearAuthSessionArtifacts()
  // 首页后续请求不能覆盖守卫已保存的登录回跳（例如 /works）。
  requireLogin()
}

/** 退出登录：清会话、关弹窗、回公共首页（不强制再打开登录） */
export function logoutToPublicHome(navigate: (href: string) => void): void {
  clearAuthSessionArtifacts()
  closeLoginModal()
  navigate('/')
}
