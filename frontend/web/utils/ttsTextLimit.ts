/** MiniMax 单条配音文本上限（清洗后字符数） */
export const MINIMAX_TTS_TEXT_MAX_CHARS = 10000

/** 是否 MiniMax 系音色/模型（按 provider / modelCode 粗判） */
export function isMiniMaxTtsProvider(...hints: Array<string | null | undefined>): boolean {
  return hints.some((h) => /minimax|海螺|speech-2/i.test(String(h || '').trim()))
}

/**
 * MiniMax 音色文本过长校验。非 MiniMax 或空文本返回 null；超限返回错误文案「文本过长」。
 */
export function checkMiniMaxTtsTextLength(
  text: string,
  hints: Array<string | null | undefined> = []
): string | null {
  const plain = String(text || '').trim()
  if (!plain || !isMiniMaxTtsProvider(...hints)) return null
  if (plain.length > MINIMAX_TTS_TEXT_MAX_CHARS) return '文本过长'
  return null
}
