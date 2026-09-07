'use client'

/* eslint-disable @next/next/no-img-element -- 服务商 LOGO 来自模型列表接口 URL。 */
import { ApiOutlined } from '@ant-design/icons'

export function StudioModelProviderIcon({
  logo,
  name
}: {
  logo?: string | null
  name?: string
}) {
  const src = String(logo || '').trim()
  if (!src) return <ApiOutlined />
  return <img src={src} alt="" title={name} />
}
