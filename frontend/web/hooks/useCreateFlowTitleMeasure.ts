'use client'

import { useCallback,useEffect,useRef,useState,type CSSProperties } from 'react'
import { useCreationStore } from '~/stores/creation'

/**
 * 工具栏作品标题输入框宽度随文字自适应（原 composables/useCreateFlowTitleMeasure.ts）
 */
export function useCreateFlowTitleMeasure(pageReady: boolean) {
  const workTitle = useCreationStore((s) => s.workTitle)
  const titleMeasureRef = useRef<HTMLElement | null>(null)
  const [titleInputWidthPx, setTitleInputWidthPx] = useState(160)

  const trimmedTitle = workTitle?.trim()
  const titleMeasureText = trimmedTitle && trimmedTitle.length > 0 ? trimmedTitle : '作品名称'
  const titleInputWrapStyle: CSSProperties = { width: `${titleInputWidthPx}px` }

  const syncTitleInputWidth = useCallback(() => {
    // 原 nextTick：等 measure 节点渲染出最新文字后再取宽
    setTimeout(() => {
      const el = titleMeasureRef.current
      if (!el) return
      const w = el.offsetWidth
      const pad = 40
      setTitleInputWidthPx(Math.min(Math.max(w + pad, 96), 560))
    }, 0)
  }, [])

  // 原 watch([workTitle, pageReady], flush: 'post') + onMounted
  useEffect(() => {
    syncTitleInputWidth()
  }, [workTitle, pageReady, syncTitleInputWidth])

  return {
    titleMeasureRef,
    titleMeasureText,
    titleInputWrapStyle,
    syncTitleInputWidth
  }
}
