import { describe,expect,it } from 'vitest'
import {
parseTaskSseEventBlock,
taskStreamDoneForRace,
type TaskStreamHandle
} from './useTaskStream'
describe('task SSE stream primitives', () => {
  it('parses named events, multiline data and heartbeat comments', () => {
    expect(
      parseTaskSseEventBlock(': keep-alive\nevent: progress\ndata: {"step":1}\ndata: next')
    ).toEqual({
      event: 'progress',
      data: '{"step":1}\nnext'
    })
  })

  it('treats unnamed data as a message and ignores empty heartbeat blocks', () => {
    expect(parseTaskSseEventBlock('data: {"progress":40}')).toEqual({
      event: 'message',
      data: '{"progress":40}'
    })
    expect(parseTaskSseEventBlock(': heartbeat')).toBeNull()
  })

  it('turns stream rejection into a terminal error result so loading cannot hang', async () => {
    const stream = {
      done: Promise.reject(new Error('Task SSE ended unexpectedly'))
    } as TaskStreamHandle

    await expect(taskStreamDoneForRace(stream)).resolves.toEqual({
      type: 'error',
      errorMessage: 'Task SSE ended unexpectedly'
    })
  })
})
