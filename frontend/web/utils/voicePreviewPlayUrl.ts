import type { VoicePreviewResult } from '~/types/business-api'

const HTTP_URL_RE = /^https?:\/\//i
const DATA_AUDIO_RE = /^data:audio\//i

function stripWhitespace(value: string): string {
  return value.replace(/\s/g, '')
}

/** 根据 base64 头部字节推断音频 MIME（豆包可能返回 wav/mp3 等） */
function inferAudioMimeFromBase64(rawBase64: string): string {
  const head = stripWhitespace(rawBase64).slice(0, 16)
  if (head.startsWith('UklGR')) return 'audio/wav'
  if (head.startsWith('T2dn')) return 'audio/ogg'
  if (
    head.startsWith('SUQz') ||
    head.startsWith('/+MY') ||
    head.startsWith('//u') ||
    head.startsWith('GkXf')
  ) {
    return 'audio/mpeg'
  }
  return 'audio/mpeg'
}

function looksLikeRawBase64(value: string): boolean {
  const trimmed = stripWhitespace(value)
  if (trimmed.length < 64) return false
  if (HTTP_URL_RE.test(trimmed) || DATA_AUDIO_RE.test(trimmed)) return false
  return /^[A-Za-z0-9+/]+={0,2}$/.test(trimmed.slice(0, Math.min(trimmed.length, 512)))
}

function toDataAudioUri(rawBase64: string): string {
  const cleaned = stripWhitespace(rawBase64)
  if (DATA_AUDIO_RE.test(cleaned)) return cleaned
  const mime = inferAudioMimeFromBase64(cleaned)
  return `data:${mime};base64,${cleaned}`
}

/**
 * 将 `/api/user/voice/preview` 返回结果转为 `<audio>` 可播放地址。
 * - minimax：通常返回 `audioUrl`（https 临时链接）
 * - 豆包：通常返回 `audioBase64`（原始 base64 或带 data: 前缀）
 */
export function resolveVoicePreviewPlayUrl(result: VoicePreviewResult | null | undefined): string {
  if (!result) return ''

  const audioBase64 = String(result.audioBase64 ?? '').trim()
  if (audioBase64) {
    return toDataAudioUri(audioBase64)
  }

  const audioUrl = String(result.audioUrl ?? '').trim()
  if (!audioUrl) return ''

  if (HTTP_URL_RE.test(audioUrl) || /^blob:/i.test(audioUrl)) {
    return audioUrl
  }

  if (DATA_AUDIO_RE.test(audioUrl)) {
    return audioUrl
  }

  if (looksLikeRawBase64(audioUrl)) {
    return toDataAudioUri(audioUrl)
  }

  return audioUrl
}
