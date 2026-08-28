'use client'

import { useUserStore } from '@/stores/user'
import { usePathname,useRouter } from 'next/navigation'
import { useEffect } from 'react'
const MOBILE_ONLY_PATH = '/mobile'

/** /invite 对应原 pages/invite.vue 的页面级 middleware:'auth'（行为与全局 auth 一致，统一收口在守卫） */
const AUTH_REQUIRED_PREFIXES = ['/works', '/assets', '/create', '/invite']

function isMobileUserAgent(ua: string): boolean {
  return /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini|Mobile|Windows Phone/i.test(
    ua
  )
}

function isMobileClient(): boolean {
  if (typeof window === 'undefined') return false
  const ua = navigator.userAgent || ''
  return isMobileUserAgent(ua) || window.matchMedia('(max-width: 900px)').matches
}

function isAuthRequiredPath(path: string): boolean {
  return AUTH_REQUIRED_PREFIXES.some((prefix) => path === prefix || path.startsWith(`${prefix}/`))
}

/**
 * 客户端路由守卫，与原 Nuxt 全局中间件对齐：
 * - 00.mobile-only.global.ts：移动端强制 /mobile，桌面访问 /mobile 回首页
 * - auth.global.ts：/works、/assets、/create* 未登录跳 /login?redirect=
 */
export function RouteGuard({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()

  useEffect(() => {
    // 移动端拦截（优先级最高，与原中间件 00. 前缀一致）
    const mobile = isMobileClient()
    if (mobile && pathname !== MOBILE_ONLY_PATH) {
      router.replace(MOBILE_ONLY_PATH)
      return
    }
    if (!mobile && pathname === MOBILE_ONLY_PATH) {
      router.replace('/')
      return
    }

    // 登录守卫
    if (!isAuthRequiredPath(pathname)) return
    const store = useUserStore.getState()
    if (!store.token) {
      store.hydrateFromStorage()
    }
    const token = useUserStore.getState().token || localStorage.getItem('token') || ''
    if (!token) {
      const current = `${window.location.pathname}${window.location.search}${window.location.hash}`
      router.replace(`/login?redirect=${encodeURIComponent(current)}`)
    }
  }, [pathname, router])

  return <>{children}</>
}
