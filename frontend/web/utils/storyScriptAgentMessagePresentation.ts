import type { UserSkillRuntimeResponseMode } from '~/types/user-skill'

export type StoryScriptAgentMessagePresentation = 'chat' | 'document'

export interface StoryScriptAgentContentPart {
  partSeq?: number | null
  type?: string | null
  slot?: string | null
  text?: string | null
}

export interface StoryScriptAgentPresentationInput {
  content: string
  responseMode?: UserSkillRuntimeResponseMode | string | null
  assistantContentSlot?: string | null
  status?: 'complete' | 'streaming' | 'error' | 'stopped'
  userPrompt?: string
}

const CHAT_CONTENT_SLOTS = new Set(['prompt', 'chat', 'reply', 'assistant'])

function presentationText(content: string): string {
  return String(content || '').trim()
}

export function resolveAssistantPrimaryContentSlot(
  parts?: StoryScriptAgentContentPart[] | null
): string | null {
  const candidates = (parts || [])
    .filter((part) => part.type === 'TEXT' && part.slot !== 'thinking_trace')
    .filter((part) => String(part.text || '').trim())
  if (!candidates.length) return null
  return candidates
    .sort((left, right) => (left.partSeq ?? 0) - (right.partSeq ?? 0))
    .at(-1)?.slot ?? null
}

export function isConversationalUserPrompt(prompt: string): boolean {
  const text = String(prompt || '').trim()
  if (!text) return false
  if (/^(你是谁|你是什么|你能做什么|你可以做什么|你能帮我什么|怎么用|如何使用|帮助|介绍一下)/u.test(text)) {
    return true
  }
  if (/[?？]$/.test(text) && text.length <= 48 && !/(写|剧本|分镜|生成|续写|诊断|改写)/u.test(text)) {
    return true
  }
  return false
}

export function looksLikeScreenplayContent(content: string): boolean {
  const text = presentationText(content)
  if (!text) return false
  if (/^\[AID_MODE:SCREENPLAY\]/i.test(text)) return true
  if (/^(INT\.|EXT\.|内景|外景)/m.test(text)) return true
  if (/^第\s*[0-9一二三四五六七八九十百千]+\s*场/m.test(text)) return true
  if (/^场次\s*[0-9一二三四五六七八九十百千]+/m.test(text)) return true
  if (/^[0-9]+\.\s*内景|^[0-9]+\.\s*外景/m.test(text)) return true
  if (/^《[^》]{1,48}》\s*[\r\n]/m.test(text) && /(内景|外景|INT|EXT|场景|场次|——|△)/m.test(text)) {
    return true
  }
  if (
    /^[\u4e00-\u9fa5A-Z（(【\[]?[\u4e00-\u9fa5A-Z·]{1,12}[\u4e00-\u9fa5A-Z）)\]】]?[\s\n:：]/m.test(text)
    && text.length >= 180
    && /(内景|外景|INT\.|EXT\.|场次|镜头)/m.test(text)
  ) {
    return true
  }
  if (/(内景|外景|INT\.|EXT\.)/.test(text) && /(场次|镜头|△|——)/.test(text) && text.length >= 80) {
    return true
  }
  return false
}

/** Strong assistant intro / help copy — not the length<=420 catch-all used for free chat heuristics. */
export function looksLikeStrongConversationalAssistantContent(content: string): boolean {
  const text = presentationText(content)
  if (!text) return false
  if (/^(我是|我可以|我能|您好|你好|很高兴|作为.*助手|我们是一个|这里是)/u.test(text)) return true
  if (/可以用来做什么|能做什么|如何使用|有什么功能|如果你愿意/u.test(text)) return true
  return false
}

export function looksLikeConversationalAssistantContent(content: string): boolean {
  const text = presentationText(content)
  if (!text) return false
  if (looksLikeStrongConversationalAssistantContent(text)) return true
  if (text.length <= 420 && !looksLikeScreenplayContent(text)) return true
  return false
}

/**
 * 决定助手消息用对话气泡还是剧本文档卡。
 * SCREENPLAY / DIAGNOSTIC 运行默认文档卡（流式打字机 + 完成后带入）；
 * 仅明确闲聊/帮助回复才走气泡。
 */
export function resolveStoryScriptAgentMessagePresentation(
  input: StoryScriptAgentPresentationInput
): StoryScriptAgentMessagePresentation {
  const slot = String(input.assistantContentSlot || '').trim().toLowerCase()
  const content = presentationText(input.content)
  const streaming = input.status === 'streaming'
  const conversationalPrompt = isConversationalUserPrompt(input.userPrompt ?? '')

  if (input.responseMode === 'DIAGNOSTIC' || slot === 'diagnostic') return 'document'
  if (slot && CHAT_CONTENT_SLOTS.has(slot)) return 'chat'
  if (input.responseMode === 'CHAT') return 'chat'

  if (input.responseMode === 'SCREENPLAY') {
    if (streaming) {
      if (!content && conversationalPrompt) return 'chat'
      return 'document'
    }
    if (!content) return 'document'
    if (looksLikeScreenplayContent(content)) return 'document'
    if (looksLikeStrongConversationalAssistantContent(content)) return 'chat'
    // Screenplay runs may emit non-standard formatting; keep the document card + apply actions.
    return 'document'
  }

  if (slot === 'screenplay') {
    if (!content) return streaming ? 'document' : 'chat'
    return looksLikeScreenplayContent(content) ? 'document' : 'chat'
  }

  if (streaming && !content) {
    return conversationalPrompt ? 'chat' : 'document'
  }

  if (content) {
    if (looksLikeScreenplayContent(content)) return 'document'
    if (looksLikeConversationalAssistantContent(content)) return 'chat'
    return 'chat'
  }

  return conversationalPrompt ? 'chat' : 'document'
}
