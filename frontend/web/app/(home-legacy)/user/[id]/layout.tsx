/** 静态导出需要至少枚举一个动态参数；真实访问由 /user?id= 入口承接。 */
export function generateStaticParams() {
  return process.env.NEXT_STATIC_EXPORT === '1' ? [{ id: '__static__' }] : []
}

export default function UserDetailRouteLayout({ children }: { children: React.ReactNode }) {
  return children
}
