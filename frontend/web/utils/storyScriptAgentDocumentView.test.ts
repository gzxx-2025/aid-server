import { describe, expect, it } from 'vitest'
import { resolveStoryScriptAgentDocumentView } from './storyScriptAgentDocumentView'
import { storyScriptAgentResponseForDisplay } from './storyScriptAgentReference'
import { parseUserSkillRuntimeOutputDelta } from './userSkillRuntimeEventStream'
import type { EditorTextSelection } from './quill/editorTextSelection'

function selection(index: number, text: string): EditorTextSelection {
  return {
    index,
    length: text.length,
    text,
    startLine: index + 1,
    endLine: index + 1,
    startColumn: 1,
    endColumn: text.length + 1,
    contextBefore: '',
    contextAfter: ''
  }
}

describe('resolveStoryScriptAgentDocumentView', () => {
  it('shows runtime status before screenplay content and switches to writing state', () => {
    const thinking = resolveStoryScriptAgentDocumentView({
      status: 'streaming',
      content: ''
    })
    const writing = resolveStoryScriptAgentDocumentView({
      status: 'streaming',
      content: '第一场 夜 内'
    })

    expect(thinking.showBody).toBe(false)
    expect(writing.showBody).toBe(true)
    expect(writing.title).toBe('正在撰写剧本')
  })

  it('labels cancelled partial output as stopped instead of completed', () => {
    const view = resolveStoryScriptAgentDocumentView({
      status: 'stopped',
      content: '已生成的半段剧本'
    })

    expect(view.title).toBe('已停止')
    expect(view.subtitle).toBe('本次生成已停止')
    expect(view.showActions).toBe(true)
  })

  it('keeps untrusted cancelled output visible but read-only', () => {
    const view = resolveStoryScriptAgentDocumentView({
      status: 'stopped',
      content: '可能缺段的剧本',
      actionsAllowed: false
    })

    expect(view.showBody).toBe(true)
    expect(view.showActions).toBe(false)
  })

  it('blocks incomplete multi-selection envelopes from copy and apply actions', () => {
    const display = storyScriptAgentResponseForDisplay(
      '[[AID_SCRIPT_REPLACEMENTS_V1]][{"referenceIndex":0,"replacement":"片段一"}',
      [selection(0, '原文一'), selection(10, '原文二')]
    )
    const view = resolveStoryScriptAgentDocumentView({
      status: 'stopped',
      content: display.text,
      contentIsPlaceholder: display.placeholder,
      actionsAllowed: display.applicable
    })

    expect(display).toMatchObject({ placeholder: true, applicable: false })
    expect(view.bodyIsPlaceholder).toBe(true)
    expect(view.showActions).toBe(false)
  })

  it('keeps a non-empty single-selection response applicable after Runtime annotation support', () => {
    expect(storyScriptAgentResponseForDisplay(
      '电影正文\n场次 1：客厅 内 日',
      [selection(0, '原选段')]
    )).toMatchObject({ placeholder: false, applicable: true })
  })

  it('parses Runtime output artifact metadata and reset semantics', () => {
    expect(parseUserSkillRuntimeOutputDelta({
      payloadJson: JSON.stringify({
        content: '第一场',
        artifactType: 'SCREENPLAY_TEXT',
        stepExecutionId: 'write-1',
        reset: true
      })
    })).toEqual({
      content: '第一场',
      artifactType: 'SCREENPLAY_TEXT',
      stepExecutionId: 'write-1',
      reset: true
    })
  })

  it('labels diagnostic output without presenting it as an applicable screenplay', () => {
    const view = resolveStoryScriptAgentDocumentView({
      status: 'complete',
      content: '第二幕缺少有效升级。',
      responseMode: 'DIAGNOSTIC'
    })

    expect(view.title).toBe('剧本诊断')
    expect(view.subtitle).toContain('不会覆盖')
  })

  it('uses diagnostic wording after a diagnostic run is stopped', () => {
    const view = resolveStoryScriptAgentDocumentView({
      status: 'stopped',
      content: '已完成的部分诊断',
      responseMode: 'DIAGNOSTIC'
    })

    expect(view.title).toContain('诊断已停止')
    expect(view.subtitle).toBe('本次诊断已停止')
  })
})
