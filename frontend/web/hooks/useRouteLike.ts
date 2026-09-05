'use client'

import { usePathname,useRouter,useSearchParams } from 'next/navigation'
import { createContext, createElement, useContext, useLayoutEffect, useMemo, type ReactNode } from 'react'
import type { RouteLikeLocation,RouteLikeNavigator } from '~/types/routeLike'

const embeddedRoutePathContext = createContext<string | null>(null)
let embeddedSnapshotPath: string | null = null

/** 嵌入式创作流程页面使用；未提供时普通流程路由行为完全不变。 */
export function EmbeddedRouteLikeProvider({ path, children }: { path: string; children: ReactNode }) {
  useLayoutEffect(() => {
    embeddedSnapshotPath = path
    return () => {
      if (embeddedSnapshotPath === path) embeddedSnapshotPath = null
    }
  }, [path])
  return createElement(embeddedRoutePathContext.Provider, { value: path }, children)
}

/** 把 Next 的 pathname + searchParams 组装成原 vue-router route 形状，供平移的 utils 使用 */
export function useRouteLike(): RouteLikeLocation {
  const path = usePathname() ?? ''
  const embeddedPath = useContext(embeddedRoutePathContext)
  const searchParams = useSearchParams()
  return useMemo(() => {
    const query: RouteLikeLocation['query'] = {}
    for (const key of Array.from(new Set(searchParams.keys()))) {
      const all = searchParams.getAll(key)
      query[key] = all.length > 1 ? all : all[0]
    }
    return { path: embeddedPath ?? path, query }
  }, [embeddedPath, path, searchParams])
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
  return { path: embeddedSnapshotPath ?? window.location.pathname, query }
}
