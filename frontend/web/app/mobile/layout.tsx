import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: '移动端提示',
  robots: { index: false, follow: false }
}

export default function Layout({ children }: { children: React.ReactNode }) {
  return children
}
