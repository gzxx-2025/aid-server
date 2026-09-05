import { describe, expect, it } from 'vitest'
import { runtimeResponseMode } from './storyScriptAgentRuntime'

describe('runtimeResponseMode', () => {
  it('does not demote an in-flight screenplay run when the snapshot briefly reports CHAT', () => {
    expect(runtimeResponseMode(
      { responseMode: 'CHAT', status: 'RUNNING', outputText: '' } as never,
      'SCREENPLAY'
    )).toBe('SCREENPLAY')
  })

  it('keeps succeeded screenplay output on SCREENPLAY even if handle.responseMode is CHAT', () => {
    const output = [
      '《末班车》',
      '',
      '1. 内景 地铁车厢 夜',
      '',
      '李明看着窗外。'
    ].join('\n')
    expect(runtimeResponseMode(
      {
        responseMode: 'CHAT',
        status: 'SUCCEEDED',
        outputText: output,
        assistantMessage: output
      } as never,
      'SCREENPLAY'
    )).toBe('SCREENPLAY')
  })

  it('keeps true chat replies as CHAT when there is no screenplay body', () => {
    expect(runtimeResponseMode(
      {
        responseMode: 'CHAT',
        status: 'SUCCEEDED',
        assistantMessage: '我是 AID 平台的专业编剧助手，我可以帮你创作剧本。'
      } as never,
      'SCREENPLAY'
    )).toBe('CHAT')
  })
})
