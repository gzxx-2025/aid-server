/** @vitest-environment jsdom */

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentPausedRuntimeNotice } from './AgentPausedRuntimeNotice'

describe('AgentPausedRuntimeNotice', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true)
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => root.unmount())
    document.body.innerHTML = ''
    vi.unstubAllGlobals()
  })

  it('renders the soft-pause copy and resume action with panel styles', async () => {
    const onResume = vi.fn()
    await act(async () => {
      root.render(<AgentPausedRuntimeNotice onResume={onResume} />)
      await Promise.resolve()
    })
    expect(container.querySelector('.agent-paused-runtime-notice')).toBeTruthy()
    expect(container.textContent).toContain('已暂停接收')
    expect(container.textContent).toContain('任务仍在后台处理')
    const button = container.querySelector('.agent-paused-runtime-notice__resume') as HTMLButtonElement
    expect(button?.textContent).toContain('恢复生成')
    await act(async () => {
      button.click()
      await Promise.resolve()
    })
    expect(onResume).toHaveBeenCalledTimes(1)
  })
})
