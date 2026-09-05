// @vitest-environment jsdom

import { beforeEach, describe, expect, it } from 'vitest'
import type { StoryScriptAgentProjectState } from './storyScriptAgentState'
import { readStoryScriptAgentState, writeStoryScriptAgentState } from './storyScriptAgentState'

function state(projectId: number, episodeId: number, runId: number): StoryScriptAgentProjectState {
  const idempotencyKey = `web-${runId}`
  return {
    version: 3,
    projectId,
    episodeId,
    skill: { id: 1, skillCode: 'screenplay' },
    autoOpen: false,
    activeRun: {
      idempotencyKey,
      invokeRequest: {
        skillCode: 'screenplay',
        idempotencyKey,
        projectId,
        episodeId,
        operation: 'CREATE',
        qualityMode: 'NORMAL'
      },
      prompt: '生成剧本',
      references: [],
      responseMode: 'SCREENPLAY',
      runId,
      afterSeq: 0,
      partialOutputTrusted: true
    },
    updatedAt: Date.now()
  }
}

describe('storyScriptAgentState runtime scope', () => {
  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
  })

  it('isolates Runtime checkpoints by project and episode', () => {
    expect(writeStoryScriptAgentState(state(10, 101, 1001))).toBe(true)
    expect(writeStoryScriptAgentState(state(10, 102, 1002))).toBe(true)
    expect(writeStoryScriptAgentState(state(11, 0, 1003))).toBe(true)

    expect(readStoryScriptAgentState(10, 101)?.activeRun?.runId).toBe(1001)
    expect(readStoryScriptAgentState(10, 102)?.activeRun?.runId).toBe(1002)
    expect(readStoryScriptAgentState(11, 0)?.activeRun?.runId).toBe(1003)
    expect(readStoryScriptAgentState(10, 0)).toBeNull()
  })

  it('removes incompatible v2 session state', () => {
    localStorage.setItem('aid-story-script-agent:v2:10:0', JSON.stringify({ version: 2 }))
    expect(readStoryScriptAgentState(10, 0)).toBeNull()
    expect(localStorage.getItem('aid-story-script-agent:v2:10:0')).toBeNull()
  })

  it('keeps a full checkpoint while the invoke response has no known run id', () => {
    const pending = state(12, 0, 1200)
    pending.activeRun = {
      ...pending.activeRun!,
      runId: null,
      partialOutputTrusted: false
    }

    expect(writeStoryScriptAgentState(pending)).toBe(true)
    const restored = readStoryScriptAgentState(12, 0)?.activeRun
    expect(restored?.runId).toBeNull()
    expect(restored?.idempotencyKey).toBe('web-1200')
    expect(restored?.invokeRequest.idempotencyKey).toBe('web-1200')
    expect(restored?.partialOutputTrusted).toBe(false)
  })
})
