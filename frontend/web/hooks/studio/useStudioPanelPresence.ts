'use client'

import { useEffect, useRef, useState } from 'react'

export const STUDIO_PANEL_MOTION_MS = 220

export function useStudioPanelPresence(
  open: boolean,
  options?: { reducedMotion?: boolean; durationMs?: number }
) {
  const reducedMotion = Boolean(options?.reducedMotion)
  const durationMs = options?.durationMs ?? STUDIO_PANEL_MOTION_MS
  const [held, setHeld] = useState(open)
  const heldRef = useRef(open)

  useEffect(() => {
    if (open) {
      heldRef.current = true
      setHeld(true)
      return
    }
    if (reducedMotion) {
      heldRef.current = false
      setHeld(false)
      return
    }
    if (!heldRef.current) return
    const timer = window.setTimeout(() => {
      heldRef.current = false
      setHeld(false)
    }, durationMs)
    return () => window.clearTimeout(timer)
  }, [durationMs, open, reducedMotion])

  return {
    rendered: open || (held && !reducedMotion),
    exiting: !open && held && !reducedMotion
  }
}

export function useStudioExitingPanelRef(exiting: boolean) {
  const ref = useRef<HTMLElement>(null)
  useEffect(() => {
    if (!exiting) return
    const active = document.activeElement
    if (active instanceof HTMLElement && ref.current?.contains(active)) active.blur()
  }, [exiting])
  return ref
}
