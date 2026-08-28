'use client'

import { ShimmerImage } from '~/components/common/ShimmerImage'
import './HomeHeroCarouselCardReveal.css'

export interface HomeHeroCarouselCardRevealProps {
  cover: string
  title: string
  videoUrl?: string
  defaultVideoUrl?: string
  /** @deprecated 悬停揭示已下线，保留 prop 以免父级传参报错 */
  enabled?: boolean
  /** 骨架占位：无封面流光 + 标题灰条 */
  skeleton?: boolean
}

export default function HomeHeroCarouselCardReveal(props: HomeHeroCarouselCardRevealProps) {
  const { cover, title, skeleton } = props
  const isSkeleton = !!skeleton || !String(cover || '').trim()

  return (
    <div className={`hero-card-reveal${isSkeleton ? ' is-skeleton' : ''}`}>
      <div className="hero-card-reveal__cover">
        {!isSkeleton && cover ? (
          <ShimmerImage
            src={cover}
            alt={title}
            wrapperClass="hero-card-reveal__shimmer"
            imgClass="hero-card-reveal__thumb"
            objectFit="cover"
            revealDirection="fade"
            revealMs={320}
            fastRevealThresholdMs={120}
            minShimmerMs={0}
          />
        ) : (
          <div className="hero-card-reveal__skel-media" aria-hidden="true">
            <div className="hero-card-reveal__skel-shimmer" />
          </div>
        )}
      </div>

      <div className="hero-card-reveal__shade" aria-hidden="true" />
      {!isSkeleton && title ? (
        <p className="hero-card-reveal__title">{title}</p>
      ) : isSkeleton ? (
        <div className="hero-card-reveal__title-skel" aria-hidden="true" />
      ) : null}
    </div>
  )
}
