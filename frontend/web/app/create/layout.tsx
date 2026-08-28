'use client'

/**
 * 创作流程布局（原 layouts/create.vue）：
 * - 挂 html class `app-shell-create layout-create-flow`（原 useHead htmlAttrs）
 * - 以 CreateFlowShell 包裹七步页面与剧集 chrome 页
 * - /create 首页为重定向页（原 definePageMeta layout:false），不挂壳与 html class
 */

import { HtmlShellClass } from '@/components/app/HtmlShellClass'
import { usePathname } from 'next/navigation'
import { Suspense } from 'react'
import CreateFlowShell from '~/components/create/CreateFlowShell'
import './create-layout.css'

export default function CreateLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname() ?? ''
  const normalized = pathname.replace(/\/+$/, '') || '/'
  if (normalized === '/create') {
    return <>{children}</>
  }
  return (
    <>
      <HtmlShellClass classes="app-shell-create layout-create-flow" />
      {/* 壳内经 useRouteLike 读 useSearchParams，构建期预渲染需 Suspense 边界（CSR bailout） */}
      <Suspense fallback={null}>
        <CreateFlowShell>{children}</CreateFlowShell>
      </Suspense>
    </>
  )
}
