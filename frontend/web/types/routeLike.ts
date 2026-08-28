/**
 * 最小路由快照类型：替代原 vue-router 的 RouteLocationNormalizedLoaded。
 * Next 侧由 usePathname() + useSearchParams() 组装（见 hooks/useRouteLike）。
 * query 值类型与 vue-router LocationQuery 对齐，保证平移的 utils 取值逻辑不变。
 */
export interface RouteLikeLocation {
  path: string
  query: Record<string, string | null | Array<string | null> | undefined>
}

/** 最小导航器：对齐原 vue-router Router.replace 的对象签名（Next 侧适配见 hooks/useRouteLike） */
export interface RouteLikeNavigator {
  replace: (to: { path: string; query: Record<string, string> }) => void | Promise<void>
}
