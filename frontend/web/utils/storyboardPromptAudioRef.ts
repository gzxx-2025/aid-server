/**
 * 分镜视频提示词：@音频N[音频-名称] 占位（与图片 ref 平行，避免膨胀 storyboardPromptAssetRef）
 */

import { normalizePromptAssetPlaceholderName } from './storyboardPromptAssetCore'

const API_AUDIO_PLACEHOLDER_RE = /@音频(\d+)\[([^\]]+)\]/g

export function ensureAudioNamePrefix(displayName: string): string {
  const raw = normalizePromptAssetPlaceholderName(displayName)
  if (!raw) return '音频-未命名'
  if (raw.startsWith('音频-')) return raw
  return `音频-${raw}`
}

export function buildAudioPlaceholder(audioIndex: number, displayName: string): string {
  const n = Math.max(1, Math.floor(Number(audioIndex) || 1))
  return `@音频${n}[${ensureAudioNamePrefix(displayName)}]`
}

export function parseApiAudioPlaceholders(
  plain: string
): Array<{ audioIndex: number; name: string }> {
  const list: Array<{ audioIndex: number; name: string }> = []
  const re = /@音频(\d+)\[([^\]]+)\]/g
  let m: RegExpExecArray | null
  while ((m = re.exec(String(plain || ''))) !== null) {
    list.push({ audioIndex: Number(m[1]), name: m[2] })
  }
  return list.sort((a, b) => a.audioIndex - b.audioIndex)
}

export function nextAudioIndex(plain: string): number {
  const list = parseApiAudioPlaceholders(plain)
  if (!list.length) return 1
  return Math.max(...list.map((x) => x.audioIndex)) + 1
}

export function stripAudioPlaceholdersFromPlain(plain: string): string {
  return String(plain || '')
    .replace(API_AUDIO_PLACEHOLDER_RE, '')
    .replace(/[ \t]{2,}/g, ' ')
    .trim()
}

/** 从纯文本移除指定名称的 @音频N[...]（名称含或不含 音频- 前缀均可） */
export function removeAudioPlaceholderByName(plain: string, displayName: string): string {
  const target = ensureAudioNamePrefix(displayName)
  const re = /@音频(\d+)\[([^\]]+)\]/g
  return String(plain || '')
    .replace(re, (full, _n, name) => (ensureAudioNamePrefix(name) === target ? '' : full))
    .replace(/[ \t]{2,}/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
}

/** 若纯文本尚无该音频名占位，则追加一条 */
export function appendAudioPlaceholderIfMissing(plain: string, displayName: string): string {
  const target = ensureAudioNamePrefix(displayName)
  const existing = parseApiAudioPlaceholders(plain)
  if (existing.some((x) => ensureAudioNamePrefix(x.name) === target)) {
    return String(plain || '')
  }
  const idx = nextAudioIndex(plain)
  const token = buildAudioPlaceholder(idx, target)
  const base = String(plain || '').trimEnd()
  if (!base) return token
  return `${base}${/\s$/.test(base) ? '' : ' '}${token}`
}
