'use client'

import { HtmlShellClass } from '@/components/app/HtmlShellClass'
import { LoginPageClient } from '@/components/login/LoginPageClient'
import { Suspense } from 'react'

/** 登录页：无壳布局（原 layout: false），html class 挂 app-shell-login；useSearchParams 需 Suspense 边界 */
export default function LoginPage() {
  return (
    <>
      <HtmlShellClass classes="app-shell-login" />
      <Suspense fallback={null}>
        <LoginPageClient />
      </Suspense>
    </>
  )
}
