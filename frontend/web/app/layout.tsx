import type { Metadata, Viewport } from 'next'
import { AntdRegistry } from '@ant-design/nextjs-registry'
import { AntdThemeProvider } from '@/components/app/AntdThemeProvider'
import { AppBootstrap } from '@/components/app/AppBootstrap'
import { AppShellOverlay } from '@/components/app/AppShellOverlay'
import { RouteGuard } from '@/components/app/RouteGuard'
import { ViewportScaleEffect } from '@/components/app/ViewportScaleEffect'

import './globals.css'
import 'antd/dist/reset.css'
/* 以下引入顺序与原 Nuxt 项目 nuxt.config.ts 的 css 数组一致，顺序即优先级，勿调整 */
import '@/assets/css/root-font-size.css'
import '@/assets/css/main.css'
import '@/assets/css/home-theme.css'
import '@/assets/css/home-legacy-page.css'
import '@/assets/css/home-new-page.css'
import '@/assets/css/home-new-sidebar.css'
import '@/assets/css/home-new-compact-viewport.css'
import '@/assets/css/create-flow-compact-viewport.css'
import '@/assets/css/compact-viewport-btn-radius.css'
import '@/assets/css/create-steps-ant-overrides.css'
import '@/assets/css/app-confirm-modal.css'
import '@/assets/css/viewport-compact-scale-overrides.css'
import '@/assets/css/viewport-large-scale-overrides.css'
import '@/assets/css/viewport-wide-range.css'
import '@/assets/css/storyboard-step-shared.css'
import '@/assets/css/scp-step-shared.css'
import '@/assets/css/shimmer-image.css'
import '@/assets/css/video-play-btn.css'
import '@/assets/css/asset-card-cancel-hint.css'
import '@/assets/css/empty-image-icon.css'
import '@/assets/font/font.css'

export const metadata: Metadata = {
  title: 'AI 创作平台',
  description: '从剧本到成片的全流程创作工具'
}

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN">
      <body>
        <AntdRegistry>
          <AntdThemeProvider>
            <AppBootstrap />
            <ViewportScaleEffect />
            <RouteGuard>{children}</RouteGuard>
            {/* 应用根壳遮罩（原 app.vue）：全局 loading + 跨壳层路由遮罩，仅覆盖不卸载页面子树 */}
            <AppShellOverlay />
          </AntdThemeProvider>
        </AntdRegistry>
      </body>
    </html>
  )
}
