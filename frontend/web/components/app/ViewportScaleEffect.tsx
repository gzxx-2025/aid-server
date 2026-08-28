'use client'

import { usePathname } from 'next/navigation'
import { useEffect } from 'react'
import {
VIEWPORT_COMPACT_MEDIA,
VIEWPORT_COMPACT_SCALE_CHANGED_EVENT,
VIEWPORT_DESIGN_CLAMP_MEDIA,
applyViewportCompactScale,
readViewportCompactScalePreference
} from '~/utils/viewportCompactScale'

/** 与原 Nuxt 项目 NUXT_PUBLIC_VIEWPORT_COMPACT_SCALE 语义一致，默认关闭 */
const raw = process.env.NEXT_PUBLIC_VIEWPORT_COMPACT_SCALE
const configEnabled = raw === '1' || raw === 'true'

function applyViewportZoom() {
  applyViewportCompactScale(configEnabled && readViewportCompactScalePreference())
}

/**
 * 全站视口档位标记（与系统 100% 观感一致）：
 * - 根节点 zoom 恒为 1，不再用「撑宽 + 缩小」拟合 1920
 * - Windows 系统缩放只写入 data-viewport-os-scale / 宽屏档位，布局走真实 CSS 视口
 * - 窄屏收紧依赖媒体查询（home-new-compact-viewport 等）
 */
export function ViewportScaleEffect() {
  const pathname = usePathname()

  useEffect(() => {
    let resizeRaf = 0
    let lastDpr = window.devicePixelRatio || 1

    function onResize() {
      cancelAnimationFrame(resizeRaf)
      resizeRaf = requestAnimationFrame(() => {
        const dpr = window.devicePixelRatio || 1
        if (Math.abs(dpr - lastDpr) > 0.01) {
          lastDpr = dpr
        }
        applyViewportZoom()
      })
    }

    applyViewportZoom()

    const mediaQueries = [
      window.matchMedia(VIEWPORT_COMPACT_MEDIA),
      window.matchMedia(VIEWPORT_DESIGN_CLAMP_MEDIA)
    ]
    // 部分浏览器可监听分辨率/缩放变化
    if (typeof window.matchMedia === 'function') {
      mediaQueries.push(
        window.matchMedia(`(resolution: ${Math.round((window.devicePixelRatio || 1) * 96)}dpi)`)
      )
    }

    for (const mq of mediaQueries) {
      if (typeof mq.addEventListener === 'function') {
        mq.addEventListener('change', applyViewportZoom)
      } else {
        ;(mq as unknown as { addListener: (fn: () => void) => void }).addListener(applyViewportZoom)
      }
    }

    window.addEventListener('resize', onResize)
    window.addEventListener(VIEWPORT_COMPACT_SCALE_CHANGED_EVENT, applyViewportZoom)
    window.addEventListener('orientationchange', onResize)
    window.visualViewport?.addEventListener('resize', onResize)
    // 从其它应用切回时系统缩放可能已变
    window.addEventListener('focus', onResize)

    return () => {
      cancelAnimationFrame(resizeRaf)
      for (const mq of mediaQueries) {
        if (typeof mq.removeEventListener === 'function') {
          mq.removeEventListener('change', applyViewportZoom)
        } else {
          ;(mq as unknown as { removeListener: (fn: () => void) => void }).removeListener(
            applyViewportZoom
          )
        }
      }
      window.removeEventListener('resize', onResize)
      window.removeEventListener(VIEWPORT_COMPACT_SCALE_CHANGED_EVENT, applyViewportZoom)
      window.removeEventListener('orientationchange', onResize)
      window.visualViewport?.removeEventListener('resize', onResize)
      window.removeEventListener('focus', onResize)
    }
  }, [])

  // 路由切换后重算（对齐原 router.afterEach + page:finish 双钩子）
  useEffect(() => {
    const t = window.setTimeout(applyViewportZoom, 0)
    return () => window.clearTimeout(t)
  }, [pathname])

  return null
}
