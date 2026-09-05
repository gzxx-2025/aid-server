import { describe, expect, it } from 'vitest'
import {
  isConversationalUserPrompt,
  looksLikeConversationalAssistantContent,
  looksLikeScreenplayContent,
  resolveAssistantPrimaryContentSlot,
  resolveStoryScriptAgentMessagePresentation
} from './storyScriptAgentMessagePresentation'

describe('storyScriptAgentMessagePresentation', () => {
  it('treats meta questions as chat presentation while streaming', () => {
    expect(resolveStoryScriptAgentMessagePresentation({
      content: '',
      responseMode: 'SCREENPLAY',
      status: 'streaming',
      userPrompt: '你是谁可以用来做什么'
    })).toBe('chat')
  })

  it('shows clearly conversational assistant replies as chat even when responseMode is SCREENPLAY', () => {
    const content = [
      '我是 AID 平台的专业编剧助手，我可以帮你创作、改写、续写和诊断剧本。',
      '如果你愿意，我们可以先从《恐龙蛋破壳美女》这个短片开始。'
    ].join('\n')
    expect(looksLikeConversationalAssistantContent(content)).toBe(true)
    expect(resolveStoryScriptAgentMessagePresentation({
      content,
      responseMode: 'SCREENPLAY',
      status: 'complete',
      userPrompt: '你是谁'
    })).toBe('chat')
  })

  it('keeps screenplay content in document presentation', () => {
    const content = [
      '《末班车》',
      '',
      '1. 内景 地铁车厢 夜',
      '',
      '李明（30岁，疲惫）',
      '（看着窗外）',
      '今天又是最后一个离开办公室的人。'
    ].join('\n')
    expect(looksLikeScreenplayContent(content)).toBe(true)
    expect(resolveStoryScriptAgentMessagePresentation({
      content,
      responseMode: 'SCREENPLAY',
      assistantContentSlot: 'screenplay',
      status: 'complete'
    })).toBe('document')
  })

  it('keeps SCREENPLAY streaming output on the document card even before screenplay shape appears', () => {
    expect(resolveStoryScriptAgentMessagePresentation({
      content: '先从客厅空罐的声音写起',
      responseMode: 'SCREENPLAY',
      status: 'streaming',
      userPrompt: '写一个悬疑短片剧本'
    })).toBe('document')
  })

  it('keeps completed SCREENPLAY runs on the document card even when heuristics miss screenplay shape', () => {
    const content = [
      '空罐短片',
      '',
      '客厅里只剩一台嗡嗡作响的冰箱。',
      '女人把空罐放上桌面，听着金属碰撞声，决定今晚就出发。',
      '远处有脚步声靠近，她关掉灯，把袋子背到肩上。'
    ].join('\n')
    expect(looksLikeScreenplayContent(content)).toBe(false)
    expect(resolveStoryScriptAgentMessagePresentation({
      content,
      responseMode: 'SCREENPLAY',
      status: 'complete',
      userPrompt: '写一个悬疑短片剧本'
    })).toBe('document')
  })

  it('shows an empty SCREENPLAY streaming shell on the document card', () => {
    expect(resolveStoryScriptAgentMessagePresentation({
      content: '',
      responseMode: 'SCREENPLAY',
      status: 'streaming',
      userPrompt: '写一个赛博朋克短片剧本'
    })).toBe('document')
  })

  it('uses assistant prompt slot as chat content', () => {
    expect(resolveStoryScriptAgentMessagePresentation({
      content: '你好，我是助手。',
      responseMode: 'SCREENPLAY',
      assistantContentSlot: 'prompt',
      status: 'complete'
    })).toBe('chat')
  })

  it('reads the latest non-thinking assistant part slot', () => {
    expect(resolveAssistantPrimaryContentSlot([
      { partSeq: 0, type: 'TEXT', slot: 'thinking_trace', text: '{}' },
      { partSeq: 1, type: 'TEXT', slot: 'prompt', text: '你好，我是助手。' }
    ])).toBe('prompt')
  })

  it('detects conversational user prompts', () => {
    expect(isConversationalUserPrompt('你是谁可以用来做什么')).toBe(true)
    expect(isConversationalUserPrompt('你能做什么事情')).toBe(true)
    expect(isConversationalUserPrompt('写一个赛博朋克短片剧本')).toBe(false)
  })
})
