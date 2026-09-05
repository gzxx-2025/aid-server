import HomeNewShell from '@/components/layout/HomeNewShell'

/** (home) 路由组：首页 / 我的作品 / 资产库 / 公共信息页共用 home-new 壳。 */
export default function HomeGroupLayout({ children }: { children: React.ReactNode }) {
  return <HomeNewShell>{children}</HomeNewShell>
}
