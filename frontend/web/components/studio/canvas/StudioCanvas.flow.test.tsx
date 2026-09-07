/** @vitest-environment jsdom */

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useStudioUiStore } from '@/stores/studioUi'
import { createStudioEdge, createStudioNode } from '@/utils/studio/studioGraph'
import { StudioCanvas } from './StudioCanvas'

class ResizeObserverMock implements ResizeObserver {
  disconnect() {}
  observe() {}
  unobserve() {}
}

class DOMMatrixReadOnlyMock {
  m22 = 1
}

describe('StudioCanvas flow regressions', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true
    vi.stubGlobal('ResizeObserver', ResizeObserverMock)
    vi.stubGlobal('DOMMatrixReadOnly', DOMMatrixReadOnlyMock)
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: vi.fn().mockImplementation((query: string) => ({
        matches: false,
        media: query,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn()
      }))
    })
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      right: 1200,
      bottom: 800,
      width: 1200,
      height: 800,
      toJSON: () => ({})
    } as DOMRect)

    const image = createStudioNode('image', { x: 80, y: 100 }, {
      title: '角色主图',
      status: 'success',
      mediaKind: 'image',
      mediaUrl: 'https://example.com/main.webp',
      flowAssetForms: [{
        formId: 1,
        name: '正面',
        prompt: '角色正面设定',
        selectedImageUrl: 'https://example.com/main.webp',
        images: [
          { imageId: 1, imageUrl: 'https://example.com/main.webp', selected: true },
          { imageId: 2, imageUrl: 'https://example.com/alternate.webp', selected: false }
        ]
      }]
    })
    const text = createStudioNode('text', { x: 460, y: 100 }, {
      title: '剧本文本',
      status: 'success',
      prompt: '第一场：雨夜。'
    })
    useStudioUiStore.getState().hydrate({
      version: 3,
      scopeKey: 'flow-regression',
      title: '流程画布',
      nodes: [image, text],
      edges: [createStudioEdge(image.id, text.id, 'flow')],
      zones: [],
      viewport: { x: 0, y: 0, zoom: 1 },
      tasks: [],
      savedAt: ''
    })

    container = document.createElement('div')
    container.style.width = '1200px'
    container.style.height = '800px'
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('renders edge handles, a single primary image, and the flow-safe context menu', async () => {
    await act(async () => {
      root.render(<StudioCanvas />)
      await Promise.resolve()
    })

    expect(container.querySelectorAll('.studio-flow-node-handle')).toHaveLength(4)
    expect(container.querySelectorAll('.studio-node-card--image-only img')).toHaveLength(1)
    expect(container.querySelector('.studio-flow-asset-gallery')).toBeNull()
    await act(async () => {
      container.querySelector('.react-flow__pane')?.dispatchEvent(new MouseEvent('contextmenu', {
        bubbles: true,
        clientX: 420,
        clientY: 260
      }))
      await Promise.resolve()
    })
    const menu = container.querySelector('.studio-pane-context-menu')
    expect(menu?.textContent).toContain('保存')
    expect(menu?.textContent).not.toContain('添加')
  })

  it('turns off media pointer interaction for the complete space-pan gesture', async () => {
    await act(async () => {
      root.render(<StudioCanvas />)
      await Promise.resolve()
    })
    const canvas = container.querySelector<HTMLElement>('.studio-canvas')
    await act(async () => {
      canvas?.dispatchEvent(new KeyboardEvent('keydown', {
        bubbles: true,
        cancelable: true,
        code: 'Space',
        key: ' '
      }))
    })
    expect(canvas?.classList.contains('is-space-panning')).toBe(true)

    await act(async () => {
      window.dispatchEvent(new KeyboardEvent('keyup', { code: 'Space', key: ' ' }))
    })
    expect(canvas?.classList.contains('is-space-panning')).toBe(false)
  })
})
