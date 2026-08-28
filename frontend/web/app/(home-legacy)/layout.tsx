import HomeLegacyShell from '@/components/layout/HomeLegacyShell'

/**
 * 旧版宽侧栏布局路由组（原 layouts/home.vue，definePageMeta layout: 'home'）：
 * 承载 /index-legacy、/user、/user/[id] 三个保留页面，路由组不影响 URL。
 */
export default function HomeLegacyLayout({ children }: { children: React.ReactNode }) {
  return <HomeLegacyShell>{children}</HomeLegacyShell>
}
