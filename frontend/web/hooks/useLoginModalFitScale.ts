'use client'

import { useLayoutEffect, useState } from 'react'
import {
  computeLoginModalFitScale,
  LOGIN_MODAL_VIEWPORT_PAD
} from '~/utils/loginModalLayout'

/** 双栏登录弹窗：按设计稿等比缩小以塞进当前视口（含系统缩放后的 CSS 像素）。 */
export function useLoginModalFitScale() {
  const [scale, setScale] = useState(() =>
    typeof window === 'undefined'
      ? 1
      : computeLoginModalFitScale(window.innerWidth, window.innerHeight, LOGIN_MODAL_VIEWPORT_PAD)
  )

  useLayoutEffect(() => {
    const update = () => {
      setScale(
        computeLoginModalFitScale(window.innerWidth, window.innerHeight, LOGIN_MODAL_VIEWPORT_PAD)
      )
    }
    update()
    window.addEventListener('resize', update)
    return () => window.removeEventListener('resize', update)
  }, [])

  return scale
}
