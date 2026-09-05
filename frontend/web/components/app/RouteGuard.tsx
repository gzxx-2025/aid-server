'use client'

import { useUserStore } from '@/stores/user'
import { usePathname, useRouter } from 'next/navigation'
import { useEffect } from 'react'
import {
  AID_LEAVE_AUTH_ROUTE_EVENT,
  requireLogin
} from '~/utils/authLoginNavigation'
import { isAuthRequiredPath } from '~/utils/authRequiredPath'

const MOBILE_ONLY_PATH = '/mobile'

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

/**
 * 客户端路由守卫，与原 Nuxt 全局中间件对齐：
 * - 移动端仅限制需要登录的工作区；公开链接保持原路径可读
 * - auth：需登录路由未登录时打开登录弹窗并离开受保护路由（不再跳 /login）
 */
export function RouteGuard({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()

  useEffect(() => {
    const onLeaveAuthRoute = () => {
      router.replace('/')
    }
    window.addEventListener(AID_LEAVE_AUTH_ROUTE_EVENT, onLeaveAuthRoute)
    return () => window.removeEventListener(AID_LEAVE_AUTH_ROUTE_EVENT, onLeaveAuthRoute)
  }, [router])

  useEffect(() => {
    // 移动端工作区使用专用入口。
    const mobile = isMobileClient()
    if (mobile && isAuthRequiredPath(pathname)) {
      router.replace(MOBILE_ONLY_PATH)
      return
    }

    // 登录守卫：弹窗 + 离开受保护路由，禁止再跳 /login
    if (!isAuthRequiredPath(pathname)) {
      return
    }
    const store = useUserStore.getState()
    if (!store.token) {
      store.hydrateFromStorage()
    }
    const token = useUserStore.getState().token || localStorage.getItem('token') || ''
    if (!token) {
      requireLogin({
        redirect: `${window.location.pathname}${window.location.search}${window.location.hash}`
      })
    }
  }, [pathname, router])

  return <>{children}</>
}
