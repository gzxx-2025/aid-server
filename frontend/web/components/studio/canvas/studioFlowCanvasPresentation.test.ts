import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const canvasSource = readFileSync('components/studio/canvas/StudioCanvas.tsx', 'utf-8')
const nodeCardSource = readFileSync('components/studio/canvas/StudioNodeCard.tsx', 'utf-8')
const canvasCss = readFileSync('components/studio/studio-canvas.css', 'utf-8')

describe('flow canvas presentation contracts', () => {
  it('keeps visible edge anchors on every flow node', () => {
    expect(nodeCardSource.match(/<Handle/g)).toHaveLength(2)
    expect(nodeCardSource).toContain('type="target"')
    expect(nodeCardSource).toContain('type="source"')
  })

  it('renders only the primary image inside an image node', () => {
    expect(nodeCardSource).not.toContain('StudioFlowAssetGallery')
    expect(nodeCardSource).toContain('studio-node-card--image-only')
    expect(canvasCss).toMatch(/\.studio-node-card--image-only \.studio-node-card__preview img\s*\{\s*object-fit:\s*cover;/)
  })

  it('keeps the node composer anchored and blocks media interaction during space panning', () => {
    expect(canvasSource).toContain('useStudioFlowNodeDockPosition')
    expect(canvasSource).toContain("spacePanning ? ' is-space-panning' : ''")
    expect(canvasCss).toContain('.studio-canvas.is-space-panning .studio-node-card__preview')
    expect(canvasCss).toContain('pointer-events: none !important;')
  })

  it('restores both pane and node right-click entry points', () => {
    expect(canvasSource).toContain('onPaneContextMenu=')
    expect(canvasSource).toContain('onNodeContextMenu=')
  })
})
