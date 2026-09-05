import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: '邀请管理',
  robots: { index: false, follow: false }
}

export default function Layout({ children }: { children: React.ReactNode }) {
  return children
}
