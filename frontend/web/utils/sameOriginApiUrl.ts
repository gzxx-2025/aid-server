import { withAppBasePath } from '~/utils/appBasePath'

/** 解析同源 API 路径（兼容部署在 /aid/ 等子路径） */
export function resolveSameOriginApiUrl(path: string): string {
  const p = path.startsWith('/') ? path : `/${path}`
  if (typeof window === 'undefined') return p
  return withAppBasePath(p)
}
