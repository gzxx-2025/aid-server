'use client'

import { useEffect, useState } from 'react'
import { resolveStackedModalZIndex } from '~/utils/stackedModalZIndex'

const DEFAULT_MODAL_Z_INDEX = 1000

/** Resolve a portal layer after the modal opens so nested modals remain interactive. */
export function useStackedModalZIndex(open: boolean, zIndex?: number): number {
  const [resolvedZIndex, setResolvedZIndex] = useState(zIndex ?? DEFAULT_MODAL_Z_INDEX)

  useEffect(() => {
    if (!open || zIndex != null) return
    // Modal portals are siblings in document.body, so their DOM nesting cannot establish stacking.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setResolvedZIndex(resolveStackedModalZIndex())
  }, [open, zIndex])

  return zIndex ?? resolvedZIndex
}
