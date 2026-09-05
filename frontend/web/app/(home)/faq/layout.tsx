import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: '常见问题',
  description: '视觉·AID 常见问题、产品使用说明与帮助中心',
  keywords: ['视觉·AID', '常见问题', '使用帮助', 'AI 视频创作']
}

export default function FaqLayout({ children }: { children: React.ReactNode }) {
  return children
}
