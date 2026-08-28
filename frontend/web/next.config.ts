import type { NextConfig } from 'next'

/** 开发代理目标；请在 `.env.development` 中配置 `NEXT_PROXY_TARGET`（与原 Nuxt 项目 NUXT_PROXY_TARGET 语义一致） */
const proxyTarget = process.env.NEXT_PROXY_TARGET || 'http://127.0.0.1:8080'
const staticExport = process.env.NEXT_STATIC_EXPORT === '1'

const nextConfig: NextConfig = {
  ...(staticExport
    ? {
        /** 对齐原 `nuxt generate`：输出纯静态文件，发布脚本再归档到 dist/public。 */
        output: 'export' as const,
        trailingSlash: true
      }
    : {
        /** 开发/Node 运行时：请求走 `/url` 前缀由 Next 代理转发到后端。 */
        async rewrites() {
          return [
            {
              source: '/case/:id(\\d+)',
              destination: '/case?id=:id'
            },
            {
              source: '/url/:path*',
              destination: `${proxyTarget.replace(/\/$/, '')}/:path*`
            }
          ]
        }
      }),
  /** 媒体资源来自 OSS 等外部域名，域名不固定，关闭 Next 图片优化以保持与原项目一致的直链行为 */
  images: {
    unoptimized: true
  }
}

export default nextConfig
