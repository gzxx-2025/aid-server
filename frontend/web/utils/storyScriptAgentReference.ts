import type { EditorTextSelection } from '~/utils/quill/editorTextSelection'

const LEGACY_REFERENCE_START = '[[AID_SCRIPT_SELECTION_V1]]'
const LEGACY_REFERENCE_END = '[[/AID_SCRIPT_SELECTION_V1]]'
const REFERENCES_START = '[[AID_SCRIPT_SELECTIONS_V2]]'
const REFERENCES_END = '[[/AID_SCRIPT_SELECTIONS_V2]]'
const INSTRUCTION_START = '[[AID_SCRIPT_COMMENT_V1]]'
const INSTRUCTION_END = '[[/AID_SCRIPT_COMMENT_V1]]'
const REPLACEMENTS_START = '[[AID_SCRIPT_REPLACEMENTS_V1]]'
const REPLACEMENTS_END = '[[/AID_SCRIPT_REPLACEMENTS_V1]]'

export interface StoryScriptAgentReferencedPrompt {
  instruction: string
  references: EditorTextSelection[]
}

export interface StoryScriptAgentReplacement {
  referenceIndex: number
  replacement: string
}

function isEditorTextSelection(value: unknown): value is EditorTextSelection {
  if (!value || typeof value !== 'object') return false
  const row = value as Partial<EditorTextSelection>
  return (
    Number.isInteger(row.index) &&
    Number.isInteger(row.length) &&
    Number.isInteger(row.startLine) &&
    Number.isInteger(row.endLine) &&
    typeof row.text === 'string' &&
    row.index! >= 0 &&
    row.length! > 0 &&
    row.startLine! > 0 &&
    row.endLine! >= row.startLine! &&
    row.text.length > 0
  )
}

export function editorTextSelectionKey(selection: EditorTextSelection): string {
  return `${selection.index}:${selection.length}:${selection.text}`
}

export function normalizeStoryScriptAgentReferences(
  references?: readonly EditorTextSelection[] | null
): EditorTextSelection[] {
  const unique = new Map<string, EditorTextSelection>()
  for (const reference of references || []) {
    if (isEditorTextSelection(reference)) {
      unique.set(editorTextSelectionKey(reference), reference)
    }
  }
  return [...unique.values()].sort((left, right) => left.index - right.index)
}

export function storyScriptAgentReferencesOverlap(
  left: EditorTextSelection,
  right: EditorTextSelection
): boolean {
  return left.index < right.index + right.length && right.index < left.index + left.length
}

/** 选段与批注以可恢复结构写入 prompt，刷新后仍能定位并安全替换原文。 */
export function buildStoryScriptAgentPrompt(
  instruction: string,
  references?: readonly EditorTextSelection[] | null
): string {
  const normalizedInstruction = instruction.trim()
  const normalizedReferences = normalizeStoryScriptAgentReferences(references)
  if (normalizedReferences.length === 0) return normalizedInstruction
  if (normalizedReferences.length === 1) {
    return [
      '请只处理下面给出的剧本选段，并结合相邻上下文理解人物、情节与语气。不要扩写或重写未选中的剧本内容。',
      LEGACY_REFERENCE_START,
      JSON.stringify(normalizedReferences[0]),
      LEGACY_REFERENCE_END,
      INSTRUCTION_START,
      normalizedInstruction,
      INSTRUCTION_END,
      '请仅返回可直接替换所选原文的修改结果，不要重复定位信息，也不要附加解释。'
    ].join('\n')
  }
  return [
    `请分别处理下面给出的 ${normalizedReferences.length} 个剧本选段，并结合每段相邻上下文理解人物、情节与语气。不要扩写或重写未选中的剧本内容。`,
    REFERENCES_START,
    JSON.stringify(normalizedReferences),
    REFERENCES_END,
    INSTRUCTION_START,
    normalizedInstruction,
    INSTRUCTION_END,
    '请为每个选段返回一份可直接替换原文的完整修改结果。必须严格使用以下格式，不要遗漏选段、不要附加格式之外的解释：',
    REPLACEMENTS_START,
    JSON.stringify(
      normalizedReferences.map((_, referenceIndex) => ({
        referenceIndex,
        replacement: `第${referenceIndex + 1}个选段的修改结果`
      }))
    ),
    REPLACEMENTS_END
  ].join('\n')
}

export function parseStoryScriptAgentPrompt(prompt: string): StoryScriptAgentReferencedPrompt {
  const instructionStart = prompt.indexOf(INSTRUCTION_START)
  const instructionEnd = prompt.indexOf(INSTRUCTION_END)
  const referencesStart = prompt.indexOf(REFERENCES_START)
  const referencesEnd = prompt.indexOf(REFERENCES_END)
  if (
    referencesStart >= 0 &&
    referencesEnd > referencesStart &&
    instructionStart > referencesEnd &&
    instructionEnd > instructionStart
  ) {
    try {
      const parsed = JSON.parse(
        prompt.slice(referencesStart + REFERENCES_START.length, referencesEnd).trim()
      ) as unknown
      if (!Array.isArray(parsed)) return { instruction: prompt, references: [] }
      const references = normalizeStoryScriptAgentReferences(parsed as EditorTextSelection[])
      if (references.length !== parsed.length) return { instruction: prompt, references: [] }
      return {
        instruction: prompt.slice(instructionStart + INSTRUCTION_START.length, instructionEnd).trim(),
        references
      }
    } catch {
      return { instruction: prompt, references: [] }
    }
  }

  const referenceStart = prompt.indexOf(LEGACY_REFERENCE_START)
  const referenceEnd = prompt.indexOf(LEGACY_REFERENCE_END)
  if (
    referenceStart < 0 ||
    referenceEnd <= referenceStart ||
    instructionStart < referenceEnd ||
    instructionEnd <= instructionStart
  ) {
    return { instruction: prompt, references: [] }
  }
  try {
    const rawReference = prompt
      .slice(referenceStart + LEGACY_REFERENCE_START.length, referenceEnd)
      .trim()
    const parsed = JSON.parse(rawReference) as unknown
    if (!isEditorTextSelection(parsed)) return { instruction: prompt, references: [] }
    return {
      instruction: prompt
        .slice(instructionStart + INSTRUCTION_START.length, instructionEnd)
        .trim(),
      references: [parsed]
    }
  } catch {
    return { instruction: prompt, references: [] }
  }
}

export function parseStoryScriptAgentReplacements(
  content: string,
  referenceCount: number
): StoryScriptAgentReplacement[] | null {
  if (referenceCount <= 0) return null
  const start = content.indexOf(REPLACEMENTS_START)
  const end = content.indexOf(REPLACEMENTS_END)
  if (start < 0 || end <= start) return null
  try {
    const parsed = JSON.parse(
      content.slice(start + REPLACEMENTS_START.length, end).trim()
    ) as unknown
    if (!Array.isArray(parsed) || parsed.length !== referenceCount) return null
    const replacements = parsed.map((value) => {
      if (!value || typeof value !== 'object') return null
      const row = value as Partial<StoryScriptAgentReplacement>
      const referenceIndex = Number(row.referenceIndex)
      const replacement = String(row.replacement || '').trim()
      if (
        !Number.isInteger(referenceIndex) ||
        referenceIndex < 0 ||
        referenceIndex >= referenceCount
      ) {
        return null
      }
      if (!replacement) return null
      return { referenceIndex, replacement }
    })
    if (replacements.some((value) => value == null)) return null
    const valid = replacements as StoryScriptAgentReplacement[]
    if (new Set(valid.map((value) => value.referenceIndex)).size !== referenceCount) return null
    return valid.sort((left, right) => left.referenceIndex - right.referenceIndex)
  } catch {
    return null
  }
}

export interface StoryScriptAgentResponseDisplay {
  text: string
  placeholder: boolean
  applicable: boolean
}

export function storyScriptAgentResponseForDisplay(
  content: string,
  references?: readonly EditorTextSelection[] | null
): StoryScriptAgentResponseDisplay {
  const normalizedReferences = normalizeStoryScriptAgentReferences(references)
  if (normalizedReferences.length === 0) {
    return { text: content, placeholder: false, applicable: true }
  }
  if (normalizedReferences.length === 1) {
    return { text: content, placeholder: false, applicable: Boolean(content.trim()) }
  }
  const replacements = parseStoryScriptAgentReplacements(content, normalizedReferences.length)
  if (replacements) {
    return {
      text: replacements
        .map((item) => `批注 ${item.referenceIndex + 1}\n${item.replacement}`)
        .join('\n\n'),
      placeholder: false,
      applicable: true
    }
  }
  if (content.includes(REPLACEMENTS_START) && !content.includes(REPLACEMENTS_END)) {
    return {
      text: '正在整理多个选段的修改结果…',
      placeholder: true,
      applicable: false
    }
  }
  return { text: content, placeholder: false, applicable: false }
}
