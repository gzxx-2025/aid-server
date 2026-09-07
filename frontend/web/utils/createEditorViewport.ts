import type { CSSProperties } from 'react'

export const CREATE_EDITOR_DESIGN_WIDTH = 1920
export const CREATE_EDITOR_DESIGN_HEIGHT = 1080

/** CSS viewport only: browser/OS zoom already reduces available width and height. */
export function computeCreateEditorScale(width: number, height: number): number {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return 1
  return Math.max(1, Math.min(width / CREATE_EDITOR_DESIGN_WIDTH, height / CREATE_EDITOR_DESIGN_HEIGHT, 2))
}

export function readCreateEditorScale(): number {
  return typeof window === 'undefined' ? 1 : computeCreateEditorScale(window.innerWidth, window.innerHeight)
}

/** Placement callbacks read the current viewport, not a stale resize-listener closure. */
export function readCreateEditorElementScale(element: HTMLElement | null): number {
  if (!element || !getComputedStyle(element).getPropertyValue('--create-editor-scale').trim()) return 1
  return readCreateEditorScale()
}

export function createEditorScaleStyle(scale: number): CSSProperties {
  return {
    '--create-editor-scale': scale,
    '--radius-sm': `calc(0.5rem * ${scale})`,
    '--radius-md': `calc(0.75rem * ${scale})`,
    '--radius-lg': `calc(1rem * ${scale})`
  } as CSSProperties
}
