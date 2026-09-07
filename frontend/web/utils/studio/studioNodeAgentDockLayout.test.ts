import { describe, expect, it } from 'vitest'
import {
  placeStudioNodeAgentDock,
  resolveStudioNodeAgentDockSize,
  type StudioAgentDockRect
} from './studioNodeAgentDockLayout'

const viewport: StudioAgentDockRect = {
  left: 0,
  top: 0,
  right: 1200,
  bottom: 800,
  width: 1200,
  height: 800
}

describe('studio node composer dock placement', () => {
  it('places the composer below the active node instead of at a global fixed position', () => {
    const first = placeStudioNodeAgentDock(rect(80, 100, 280, 220), viewport, { width: 640, height: 220 })
    const second = placeStudioNodeAgentDock(rect(720, 360, 280, 220), viewport, { width: 640, height: 220 })

    expect(first.top).toBe(332)
    expect(second.top).toBe(592)
    expect(first.left).not.toBe(second.left)
    expect(first.placement).toBe('bottom')
  })

  it('keeps the dock inside the horizontal canvas bounds', () => {
    const left = placeStudioNodeAgentDock(rect(-120, 80, 240, 180), viewport, { width: 640, height: 180 })
    const right = placeStudioNodeAgentDock(rect(1120, 80, 240, 180), viewport, { width: 640, height: 180 })

    expect(left.left).toBe(16)
    expect(right.left + right.width).toBe(1184)
  })

  it('uses the full flow composer width and clamps its measured height', () => {
    expect(resolveStudioNodeAgentDockSize({ width: 10, height: 80 })).toEqual({ width: 640, height: 140 })
    expect(resolveStudioNodeAgentDockSize({ width: 900, height: 700 })).toEqual({ width: 640, height: 400 })
  })
})

function rect(left: number, top: number, width: number, height: number): StudioAgentDockRect {
  return {
    left,
    top,
    right: left + width,
    bottom: top + height,
    width,
    height
  }
}
