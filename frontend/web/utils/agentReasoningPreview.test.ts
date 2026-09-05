import { describe, expect, it } from 'vitest'
import { resolveAgentReasoningPreview } from './agentReasoningPreview'

describe('resolveAgentReasoningPreview', () => {
  it('keeps the full live reasoning text so the fixed viewport can scroll it', () => {
    expect(resolveAgentReasoningPreview('1234567890', true, 4)).toEqual({
      text: '1234567890',
      truncated: false
    })
  })

  it('keeps the complete reasoning after the live phase', () => {
    expect(resolveAgentReasoningPreview('完整思考过程', false, 2)).toEqual({
      text: '完整思考过程',
      truncated: false
    })
  })
})
