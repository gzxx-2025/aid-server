/**
 * 剧本有效变更判定：规范化 → hash → 与上次提取基线比对。
 * @see docs/superpowers/specs/2026-07-29-script-change-continue-extract-design.md
 */

export const MIN_NORMALIZED_LEN = 50
export const MIN_DIFF_CHARS = 80
export const MIN_DIFF_RATIO = 0.02

const ENTITY_MAP: Record<string, string> = {
  nbsp: ' ',
  amp: '&',
  lt: '<',
  gt: '>',
  quot: '"',
  apos: "'"
}

function decodeBasicEntities(s: string): string {
  return s
    .replace(/&#(\d+);/g, (all, n) => {
      const code = Number(n)
      return Number.isFinite(code) ? String.fromCharCode(code) : all
    })
    .replace(/&#x([0-9a-f]+);/gi, (all, h) => {
      const code = Number.parseInt(h, 16)
      return Number.isFinite(code) ? String.fromCharCode(code) : all
    })
    .replace(/&([a-z]+);/gi, (all, name: string) => ENTITY_MAP[name.toLowerCase()] ?? all)
}

/** HTML / 富文本 → 可比对纯文本 */
export function htmlToPlainScriptText(htmlOrText: string): string {
  let s = String(htmlOrText ?? '')
  s = s.replace(/\uFEFF/g, '')
  s = s.replace(/[\u200B-\u200D\u2060]/g, '')
  s = s.replace(/<\s*br\s*\/?>/gi, '\n')
  s = s.replace(/<\/\s*p\s*>/gi, '\n')
  s = s.replace(/<\/\s*div\s*>/gi, '\n')
  s = s.replace(/<[^>]+>/g, '')
  s = decodeBasicEntities(s)
  s = s.replace(/\u3000/g, ' ')
  s = s.replace(/\r\n?/g, '\n')
  s = s.replace(/[ \t\f\v]+/g, ' ')
  s = s.replace(/\n{3,}/g, '\n\n')
  s = s
    .split('\n')
    .map((line) => line.trim())
    .join('\n')
  return s.trim()
}

/** FNV-1a 32-bit → hex */
export function fnv1a32Hex(text: string): string {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return (h >>> 0).toString(16).padStart(8, '0')
}

export function normalizeScriptContent(htmlOrText: string): {
  text: string
  len: number
  hash: string
} {
  const text = htmlToPlainScriptText(htmlOrText)
  return { text, len: text.length, hash: fnv1a32Hex(text) }
}

/**
 * 轻量近似编辑量：|Δlen| + 去掉公共前后缀后剩余长度之和。
 */
export function approxDiffChars(a: string, b: string): number {
  if (a === b) return 0
  let i = 0
  const minLen = Math.min(a.length, b.length)
  while (i < minLen && a.charCodeAt(i) === b.charCodeAt(i)) i++
  let j = 0
  while (
    j < minLen - i &&
    a.charCodeAt(a.length - 1 - j) === b.charCodeAt(b.length - 1 - j)
  ) {
    j++
  }
  const midA = a.length - i - j
  const midB = b.length - i - j
  return Math.abs(a.length - b.length) + Math.max(0, midA) + Math.max(0, midB)
}

export type ScriptChangeBaselineSlice = {
  comicVersion: number
  normalizedHash: string
  normalizedLen: number
  /** 可选：若有基线全文则用精确 approxDiff；否则退化为 |Δlen| */
  text?: string
}

export type ScriptChangeCurrentSlice = {
  comicVersion: number
  normalizedHash: string
  normalizedLen: number
  text: string
}

export function diffThresholdForBaselineLen(normalizedLen: number): number {
  return Math.max(MIN_DIFF_CHARS, Math.floor(normalizedLen * MIN_DIFF_RATIO))
}

/**
 * 有效变更：version 升高 + hash 不同 + 当前够长 + 差异过阈值。
 * 有 baseline.text 时用 approxDiffChars；否则用 |Δlen|（同长度大替换可能漏报，可接受）。
 */
export function isMeaningfulScriptChange(
  baseline: ScriptChangeBaselineSlice,
  current: ScriptChangeCurrentSlice
): boolean {
  if (!(current.comicVersion > baseline.comicVersion)) return false
  if (current.normalizedHash === baseline.normalizedHash) return false
  if (current.normalizedLen < MIN_NORMALIZED_LEN) return false

  const threshold = diffThresholdForBaselineLen(baseline.normalizedLen)
  const diff =
    typeof baseline.text === 'string'
      ? approxDiffChars(baseline.text, current.text)
      : Math.abs(current.normalizedLen - baseline.normalizedLen)
  return diff >= threshold
}

export function buildScriptChangeKey(comicVersion: number, normalizedHash: string): string {
  return `${comicVersion}:${normalizedHash}`
}
