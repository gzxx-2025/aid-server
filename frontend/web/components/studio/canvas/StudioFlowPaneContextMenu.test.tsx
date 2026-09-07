/** @vitest-environment jsdom */

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { StudioFlowPaneContextMenu } from './StudioFlowPaneContextMenu'

describe('StudioFlowPaneContextMenu', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => root.unmount())
    container.remove()
  })

  it('restores flow-safe actions without add-node or upload entries', async () => {
    const onUndo = vi.fn()
    const onSave = vi.fn()
    const onClose = vi.fn()
    await act(async () => {
      root.render(
        <StudioFlowPaneContextMenu
          client={{ x: 120, y: 80 }}
          canUndo
          saving={false}
          platform="win"
          onUndo={onUndo}
          onSave={onSave}
          onClose={onClose}
        />
      )
    })

    const menu = container.querySelector<HTMLElement>('[aria-label="流程画布右键菜单"]')
    expect(menu?.textContent).toContain('粘贴')
    expect(menu?.textContent).toContain('撤销')
    expect(menu?.textContent).toContain('保存')
    expect(menu?.textContent).not.toContain('添加')
    expect(menu?.textContent).not.toContain('上传')

    const undo = Array.from(menu?.querySelectorAll('button') ?? [])
      .find((button) => button.textContent?.includes('撤销'))
    await act(async () => undo?.click())
    expect(onUndo).toHaveBeenCalledOnce()
    expect(onClose).toHaveBeenCalledOnce()
    expect(onSave).not.toHaveBeenCalled()
  })
})
