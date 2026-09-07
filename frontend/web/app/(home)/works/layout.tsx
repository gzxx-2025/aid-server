import type { Metadata } from 'next'
import { Suspense, type ReactNode } from 'react'

export const metadata: Metadata = {
  title: '我的作品',
  robots: { index: false, follow: false }
}

/** useSearchParams 需 Suspense；边界放在 layout 避免页面反复挂载/卸载时边界抖动 */
export default function Layout({ children }: { children: ReactNode }) {
  return <Suspense fallback={null}>{children}</Suspense>
}
