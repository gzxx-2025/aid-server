export interface EditorTextSelection {
  index: number
  length: number
  text: string
  startLine: number
  endLine: number
  startColumn: number
  endColumn: number
  contextBefore: string
  contextAfter: string
}

/** 选区末端在浏览器可视区域中的位置，仅用于定位临时操作入口，不参与持久化。 */
export interface EditorTextSelectionAnchor {
  left: number
  top: number
  bottom: number
}

export interface EditorTextSelectionChange {
  selection: EditorTextSelection
  anchor: EditorTextSelectionAnchor
}

export interface EditorTextReplacement {
  selection: EditorTextSelection
  replacement: string
}

const CONTEXT_CHARS = 180

/** 将 Quill 选区转换成可持久化、可供 Agent 精确定位的纯文本快照。 */
export function createEditorTextSelection(
  documentText: string,
  index: number,
  length: number
): EditorTextSelection | null {
  const normalizedIndex = Math.max(0, Math.min(Math.trunc(index), documentText.length))
  const normalizedLength = Math.max(
    0,
    Math.min(Math.trunc(length), documentText.length - normalizedIndex)
  )
  if (normalizedLength <= 0) return null

  const text = documentText.slice(normalizedIndex, normalizedIndex + normalizedLength)
  if (!text.trim()) return null
  const before = documentText.slice(0, normalizedIndex)
  const startLine = before.split('\n').length
  const startColumn = normalizedIndex - before.lastIndexOf('\n')
  const selectedLines = text.split('\n')
  const endLine = startLine + selectedLines.length - 1
  const endColumn = selectedLines.length === 1
    ? startColumn + text.length
    : selectedLines[selectedLines.length - 1].length + 1

  return {
    index: normalizedIndex,
    length: normalizedLength,
    text,
    startLine,
    endLine,
    startColumn,
    endColumn,
    contextBefore: documentText.slice(Math.max(0, normalizedIndex - CONTEXT_CHARS), normalizedIndex),
    contextAfter: documentText.slice(
      normalizedIndex + normalizedLength,
      normalizedIndex + normalizedLength + CONTEXT_CHARS
    )
  }
}

export function formatEditorSelectionLocation(selection: EditorTextSelection): string {
  const lineLabel =
    selection.startLine === selection.endLine
      ? `第 ${selection.startLine} 行`
      : `第 ${selection.startLine}–${selection.endLine} 行`
  return `${lineLabel} · 字符 ${selection.index + 1}–${selection.index + selection.length}`
}
