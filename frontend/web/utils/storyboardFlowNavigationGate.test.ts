import { describe, expect, it } from 'vitest'

import {
  applyStoryboardFlowStepStatusGate,
  hasPersistedStoryboardScriptContent,
  isStoryboardFlowSuccessorStep,
  isStoryboardFlowSuccessorLocked,
  isStoryboardScriptAdvanceBlocked
} from './storyboardFlowNavigationGate'

describe('storyboard flow navigation gate', () => {
  it('同步完成但没有真实分镜记录时阻止进入后续流程', () => {
    expect(
      isStoryboardFlowSuccessorLocked({
        storyboardListSyncReady: true,
        panels: []
      })
    ).toBe(true)
    expect(
      isStoryboardScriptAdvanceBlocked({
        currentStep: 'storyboard-script',
        storyboardListSyncReady: true,
        panels: []
      })
    ).toBe(true)
  })

  it('生成占位卡不视为成功内容', () => {
    expect(
      hasPersistedStoryboardScriptContent([
        { id: 'gen-skeleton-1', title: '生成中' },
        { id: 'local-panel', title: '本地占位' }
      ])
    ).toBe(false)
  })

  it('服务端分镜记录出现后解除门禁', () => {
    expect(
      isStoryboardFlowSuccessorLocked({
        storyboardListSyncReady: true,
        panels: [{ id: '101', title: '分镜1' }]
      })
    ).toBe(false)
    expect(
      isStoryboardScriptAdvanceBlocked({
        currentStep: 'storyboard-script',
        storyboardListSyncReady: true,
        panels: [{ id: '101', title: '分镜1' }]
      })
    ).toBe(false)
  })

  it('只锁定分镜设计之后的流程步骤', () => {
    expect(isStoryboardFlowSuccessorStep(3)).toBe(false)
    expect(isStoryboardFlowSuccessorStep(4)).toBe(true)
    expect(isStoryboardFlowSuccessorStep(6)).toBe(true)
    expect(
      applyStoryboardFlowStepStatusGate(
        ['completed', 'completed', 'completed', 'active', 'completed', 'completed', 'pending'],
        true
      )
    ).toEqual([
      'completed',
      'completed',
      'completed',
      'active',
      'disabled',
      'disabled',
      'disabled'
    ])
  })
})
