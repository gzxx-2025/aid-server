/**
 * 分镜视频弹窗：参考图/参考音频统一媒体项
 */

export type ReferenceMediaKind = 'image' | 'audio'
export type ReferenceAudioSource = 'voice_sample' | 'upload'

export interface ReferenceMediaItem {
  kind: ReferenceMediaKind
  name: string
  url?: string
  /** 上传参考音频 ID；仅 upload 有值 */
  referenceAudioId?: number
  audioSource?: ReferenceAudioSource
  durationMs?: number
  audioFormat?: string
  id?: string | number
  thumbnail?: string
  title?: string
}

export function isReferenceAudioItem(item: ReferenceMediaItem | null | undefined): boolean {
  return item?.kind === 'audio'
}

/** 出片用：仅收集自定义上传的 referenceAudioIds */
export function collectReferenceAudioIds(items: ReferenceMediaItem[] | null | undefined): number[] {
  if (!Array.isArray(items) || !items.length) return []
  const ids: number[] = []
  const seen = new Set<number>()
  for (const item of items) {
    if (item?.kind !== 'audio' || item.audioSource !== 'upload') continue
    const id = Number(item.referenceAudioId)
    if (!Number.isFinite(id) || id <= 0 || seen.has(id)) continue
    seen.add(id)
    ids.push(id)
  }
  return ids
}

export function fromOfficialVoice(opts: {
  name: string
  previewUrl: string
  /** 音色头像，写入 thumbnail 供已导入素材展示 */
  avatarUrl?: string
  voiceLibraryId?: number
  durationMs?: number
}): ReferenceMediaItem {
  const name = String(opts.name || '').trim() || '未命名音色'
  const avatar = String(opts.avatarUrl || '').trim()
  return {
    kind: 'audio',
    name,
    title: name,
    url: String(opts.previewUrl || '').trim() || undefined,
    thumbnail: avatar || undefined,
    audioSource: 'voice_sample',
    id: opts.voiceLibraryId != null ? `voice-${opts.voiceLibraryId}` : `voice-${name}`,
    durationMs: opts.durationMs,
    audioFormat: undefined
  }
}

export function fromUploadedReferenceAudio(opts: {
  id: number
  audioName: string
  audioUrl: string
  durationMs?: number | null
  audioFormat?: string | null
}): ReferenceMediaItem {
  const name = String(opts.audioName || '').trim() || `音频${opts.id}`
  return {
    kind: 'audio',
    name,
    title: name,
    url: String(opts.audioUrl || '').trim() || undefined,
    referenceAudioId: Number(opts.id),
    audioSource: 'upload',
    id: `upload-${opts.id}`,
    durationMs: opts.durationMs != null ? Number(opts.durationMs) : undefined,
    audioFormat: opts.audioFormat ? String(opts.audioFormat).toLowerCase() : undefined
  }
}

/** 提示词占位名：音频-{展示名} */
export function audioPlaceholderName(displayName: string): string {
  const raw = String(displayName || '').trim()
  if (!raw) return '音频-未命名'
  if (raw.startsWith('音频-')) return raw
  return `音频-${raw}`
}
