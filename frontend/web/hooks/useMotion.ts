import type { RefObject } from 'react'
import { useCallback,useEffect,useRef } from 'react'
import { loadGsap } from '~/composables/useGSAP'

type MotionGsap = typeof import('gsap').gsap
type MotionCallback = (gsap: MotionGsap) => void

/**
 * 组件级 GSAP 生命周期：context 作用域 + 卸载清理。
 * 仅在 root 已挂载后调用 `run`（内部异步加载 gsap）。
 */
export function useMotion<T extends HTMLElement>(rootRef: RefObject<T | null>) {
  const ctxRef = useRef<{ revert: () => void } | null>(null)
  const runTokenRef = useRef(0)

  const kill = useCallback(() => {
    ctxRef.current?.revert()
    ctxRef.current = null
  }, [])

  const run = useCallback(
    async (callback: MotionCallback) => {
      if (!(typeof window !== 'undefined')) return
      const token = ++runTokenRef.current
      kill()
      const root = rootRef.current
      if (!root) return
      const gsap = await loadGsap()
      if (token !== runTokenRef.current || rootRef.current !== root) return
      ctxRef.current = gsap.context(() => {
        callback(gsap)
      }, root)
    },
    [kill, rootRef]
  )

  useEffect(() => {
    return () => {
      runTokenRef.current += 1
      kill()
    }
  }, [kill])

  return {
    run,
    kill
  }
}
