'use client'

import { useCallback, useTransition } from 'react'
import { useRouter } from 'next/navigation'

/** 在路由资源加载期间立即反馈，由 React 跟踪导航完成，而非等待 pathname 变化。 */
export function useRouteNavigation() {
  const router = useRouter()
  const [isPending, startTransition] = useTransition()
  const navigate = useCallback((href: string) => {
    startTransition(() => router.push(href))
  }, [router])
  return { navigate, isPending }
}
