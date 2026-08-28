/**
 * 应用部署基路径（兼容部署在 /aid/ 等子路径）。
 * 对齐原 Nuxt useRuntimeConfig().app.baseURL 语义：Next 侧由构建期
 * NEXT_PUBLIC_BASE_PATH 注入（与 next.config 的 basePath 保持一致），默认根路径。
 */
export function withAppBasePath(path: string): string {
  const p = path.startsWith('/') ? path : `/${path}`
  const base = String(process.env.NEXT_PUBLIC_BASE_PATH ?? '/')
  if (!base || base === '/') return p
  const normalized = base.endsWith('/') ? base.slice(0, -1) : base
  return `${normalized}${p}`
}
