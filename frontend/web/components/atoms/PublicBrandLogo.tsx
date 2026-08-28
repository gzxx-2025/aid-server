'use client'

import { useEffect, useState } from 'react'
import { useAuthPublicConfig } from '~/composables/useAuthPublicConfig'
import './PublicBrandLogo.css'

interface PublicBrandLogoProps {
  className?: string
  alt?: string
  compactFallback?: boolean
}

/** 平台品牌标识统一读取公开配置；图片缺失或加载失败时仅展示配置中的站点名称。 */
export default function PublicBrandLogo({
  className,
  alt,
  compactFallback = false
}: PublicBrandLogoProps) {
  const { platformLogoUrl, siteName, loadPublicConfig } = useAuthPublicConfig()
  const [failedUrl, setFailedUrl] = useState('')

  useEffect(() => {
    void loadPublicConfig()
  }, [loadPublicConfig])

  const logoUrl = platformLogoUrl && failedUrl !== platformLogoUrl ? platformLogoUrl : ''
  const accessibleName = alt?.trim() || siteName || '平台标识'
  const fallbackText = compactFallback
    ? Array.from(siteName).slice(0, 2).join('')
    : siteName
  const rootClassName = className
    ? `public-brand-logo ${className}`
    : 'public-brand-logo'

  return (
    <span
      className={rootClassName}
      role={logoUrl || fallbackText ? 'img' : undefined}
      aria-label={logoUrl || fallbackText ? accessibleName : undefined}
      aria-hidden={logoUrl || fallbackText ? undefined : true}
      data-empty={logoUrl || fallbackText ? undefined : 'true'}
    >
      {logoUrl ? (
        // eslint-disable-next-line @next/next/no-img-element -- 配置域名不固定，需保持公开配置直链可用
        <img
          className="public-brand-logo__image"
          src={logoUrl}
          alt=""
          decoding="async"
          onError={() => setFailedUrl(logoUrl)}
        />
      ) : fallbackText ? (
        <span className="public-brand-logo__text" aria-hidden="true">
          {fallbackText}
        </span>
      ) : null}
    </span>
  )
}
