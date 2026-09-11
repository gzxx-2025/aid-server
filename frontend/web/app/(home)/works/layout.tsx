import { Suspense, type ReactNode } from 'react'

/** useSearchParams 需 Suspense；边界放在 layout 避免页面反复挂载/卸载时边界抖动 */
export default function Layout({ children }: { children: ReactNode }) {
  return <Suspense fallback={null}>{children}</Suspense>
}
