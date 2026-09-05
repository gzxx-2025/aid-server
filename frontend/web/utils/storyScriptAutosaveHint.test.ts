import { describe, expect, it } from 'vitest'
import { formatStoryScriptAutosaveHint } from './storyScriptAutosaveHint'

describe('formatStoryScriptAutosaveHint', () => {
  it('shows autosaving while dirty or saving', () => {
    expect(formatStoryScriptAutosaveHint('dirty', null)).toBe('自动保存中')
    expect(formatStoryScriptAutosaveHint('saving', null)).toBe('自动保存中')
  })

  it('shows time and saved label when idle', () => {
    const hint = formatStoryScriptAutosaveHint('saved', Date.parse('2026-08-26T16:36:00'))
    expect(hint).toMatch(/16:36 已保存/)
  })

  it('falls back to saved without time before first save timestamp', () => {
    expect(formatStoryScriptAutosaveHint('saved', null)).toBe('已保存')
  })

  it('shows failure and conflict labels', () => {
    expect(formatStoryScriptAutosaveHint('error', null)).toBe('保存失败')
    expect(formatStoryScriptAutosaveHint('conflict', null)).toBe('内容冲突')
  })
})
