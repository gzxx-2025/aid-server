/** @vitest-environment jsdom */

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentThinkingProcess } from './AgentThinkingProcess'
import { AgentResponseStoppedDivider } from './AgentResponseStoppedDivider'

describe('AgentResponseStoppedDivider', () => {
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

  it('renders the default stopped label', async () => {
    await act(async () => {
      root.render(<AgentResponseStoppedDivider />)
      await Promise.resolve()
    })
    expect(container.querySelector('.agent-response-stopped-divider__label')?.textContent).toBe(
      '你已停止本次回复'
    )
  })
})

describe('AgentThinkingProcess shimmer', () => {
  let container: HTMLDivElement
  let root: Root

  beforeEach(() => {
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true)
    vi.useFakeTimers()
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
  })

  afterEach(() => {
    act(() => root.unmount())
    document.body.innerHTML = ''
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('applies shimmer to the active live step label', async () => {
    await act(async () => {
      root.render(
        <AgentThinkingProcess
          live
          startedAt={new Date().toISOString()}
          steps={[
            { id: '1', kind: 'read_document', label: '正在阅读 1 个文档', status: 'active' }
          ]}
        />
      )
      await Promise.resolve()
    })
    expect(container.querySelector('.agent-thinking-step__label.is-shimmer')?.textContent).toContain(
      '正在阅读 1 个文档'
    )
  })

  it('keeps a completed thinking trace visible even without step rows', async () => {
    await act(async () => {
      root.render(
        <AgentThinkingProcess
          live={false}
          startedAt="2026-09-02T08:00:00.000Z"
          completedAt="2026-09-02T08:00:14.000Z"
        />
      )
      await Promise.resolve()
    })
    expect(container.textContent).toContain('处理了 14s')
  })

  it('streams creative reasoning text inside a fixed live viewport', async () => {
    await act(async () => {
      root.render(
        <AgentThinkingProcess
          live
          collapsibleLive
          startedAt={new Date().toISOString()}
          reasoning="先用空罐建立目标，再让声音触发行动。"
          steps={[
            { id: '1', kind: 'analyze', label: '正在理解创作目标…', status: 'done' }
          ]}
        />
      )
      await Promise.resolve()
    })
    const viewport = container.querySelector('.agent-thinking-process__reasoning-viewport.is-live')
    expect(viewport).toBeTruthy()
    expect(viewport?.textContent).toContain('先用空罐建立目标，再让声音触发行动。')
  })

  it('keeps the full live reasoning text instead of sliding-window truncation', async () => {
    const longReasoning = `${'甲'.repeat(120)}${'乙'.repeat(120)}`
    await act(async () => {
      root.render(
        <AgentThinkingProcess
          live
          collapsibleLive
          startedAt={new Date().toISOString()}
          reasoning={longReasoning}
        />
      )
      await Promise.resolve()
    })
    const text = container.querySelector('.agent-thinking-process__reasoning')?.textContent || ''
    expect(text.startsWith('…')).toBe(false)
    expect(text).toBe(longReasoning)
  })
})
