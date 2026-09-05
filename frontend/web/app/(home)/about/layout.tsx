import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: '关于我们',
  description: '了解产品、服务与创作平台',
  robots: { index: true, follow: true }
}

export default function Layout({ children }: { children: React.ReactNode }) {
  return children
}
