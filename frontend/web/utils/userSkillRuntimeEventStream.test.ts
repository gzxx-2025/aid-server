import { describe, expect, it, vi } from 'vitest'
import {
  parseUserSkillRuntimeOutputDelta,
  dispatchUserSkillRuntimeSseEvent
} from './userSkillRuntimeEventStream'

describe('userSkillRuntimeEventStream', () => {
  it('parses CREATIVE_REASONING reasoning_delta payloads', () => {
    expect(parseUserSkillRuntimeOutputDelta({
      payloadJson: JSON.stringify({
        content: '先用空罐建立目标。',
        artifactType: 'CREATIVE_REASONING',
        stepExecutionId: 'write-1',
        reset: true
      })
    })).toEqual({
      content: '先用空罐建立目标。',
      artifactType: 'CREATIVE_REASONING',
      stepExecutionId: 'write-1',
      reset: true
    })
  })

  it('routes reasoning_delta to onReasoningDelta with seq, not onMilestone', () => {
    const onReasoningDelta = vi.fn()
    const onOutputDelta = vi.fn()
    const onMilestone = vi.fn()

    dispatchUserSkillRuntimeSseEvent(
      'reasoning_delta',
      JSON.stringify({
        seq: 14,
        eventType: 'reasoning_delta',
        stage: 'WRITING',
        payloadJson: JSON.stringify({
          content: '先用空罐建立目标。',
          artifactType: 'CREATIVE_REASONING',
          stepExecutionId: 'write-1',
          reset: true
        })
      }),
      { onReasoningDelta, onOutputDelta, onMilestone }
    )

    expect(onReasoningDelta).toHaveBeenCalledWith({
      content: '先用空罐建立目标。',
      artifactType: 'CREATIVE_REASONING',
      stepExecutionId: 'write-1',
      reset: true
    }, 14)
    expect(onOutputDelta).not.toHaveBeenCalled()
    expect(onMilestone).not.toHaveBeenCalled()
  })

  it('routes output_delta to onOutputDelta with seq', () => {
    const onReasoningDelta = vi.fn()
    const onOutputDelta = vi.fn()
    const onMilestone = vi.fn()

    dispatchUserSkillRuntimeSseEvent(
      'output_delta',
      JSON.stringify({
        seq: 15,
        eventType: 'output_delta',
        payloadJson: JSON.stringify({
          content: '场次 1：客厅 内 夜',
          artifactType: 'SCREENPLAY_TEXT',
          stepExecutionId: 'write-1',
          reset: true
        })
      }),
      { onReasoningDelta, onOutputDelta, onMilestone }
    )

    expect(onOutputDelta).toHaveBeenCalledWith({
      content: '场次 1：客厅 内 夜',
      artifactType: 'SCREENPLAY_TEXT',
      stepExecutionId: 'write-1',
      reset: true
    }, 15)
    expect(onReasoningDelta).not.toHaveBeenCalled()
    expect(onMilestone).not.toHaveBeenCalled()
  })
})
