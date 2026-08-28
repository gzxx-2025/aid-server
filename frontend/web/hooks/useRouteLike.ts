'use client'

import { usePathname,useRouter,useSearchParams } from 'next/navigation'
import { useMemo } from 'react'
import type { RouteLikeLocation,RouteLikeNavigator } from '~/types/routeLike'

/** 把 Next 的 pathname + searchParams 组装成原 vue-router route 形状，供平移的 utils 使用 */
export function useRouteLike(): RouteLikeLocation {
  const path = usePathname() ?? ''
  const searchParams = useSearchParams()
  return useMemo(() => {
    const query: RouteLikeLocation['query'] = {}
    for (const key of Array.from(new Set(searchParams.keys()))) {
      const all = searchParams.getAll(key)
      query[key] = all.length > 1 ? all : all[0]
    }
    return { path, query }
  }, [path, searchParams])
}

/** 把 Next router 适配为原 vue-router Router.replace 的对象签名 */
export function useRouteLikeNavigator(): RouteLikeNavigator {
  const router = useRouter()
  return useMemo(
    () => ({
      replace({ path, query }) {
        const qs = new URLSearchParams(query).toString()
        router.replace(qs ? `${path}?${qs}` : path)
      }
    }),
    [router]
  )
}

/** 事件回调等非渲染场景下取当前路由快照（仅客户端） */
export function getRouteLikeSnapshot(): RouteLikeLocation {
  if (typeof window === 'undefined') return { path: '', query: {} }
  const params = new URLSearchParams(window.location.search)
  const query: RouteLikeLocation['query'] = {}
  for (const key of Array.from(new Set(params.keys()))) {
    const all = params.getAll(key)
    query[key] = all.length > 1 ? all : all[0]
  }
  return { path: window.location.pathname, query }
}
