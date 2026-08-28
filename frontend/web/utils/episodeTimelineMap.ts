/**
 * 服务端 TimelineData ↔ 成品预览 UI 轨道的扁平映射（无嵌套循环拼装）。
 */
import type {
  TimelineData,
  TimelineSegment,
  TimelineBgm,
  TimedSubtitleCue
} from '~/types/business-api'
import type {
  PreviewAudioItem,
  PreviewSubtitleItem,
  PreviewTimelineUiState,
  PreviewVideoClip
} from './episodeTimelineTypes'

export * from './episodeTimelineTypes'
export { mapTimelineToExportGroups } from './episodeTimelineExportGroups'

/** 可持久化/导出的媒体 URL（排除 blob、以及历史 `cdn/blob:` 脏数据） */
function persistableMediaUrl(url: unknown): string {
  const raw = String(url || '').trim()
  if (!raw) return ''
  if (/^(blob:|data:)/i.test(raw)) return ''
  if (/\/blob:/i.test(raw)) return ''
  return raw
}

function num(v: unknown, fallback = 0): number {
  const n = Number(v)
  return Number.isFinite(n) ? n : fallback
}

/** 仅接受 >0 的时长；0 / null / 非法值回落 fallback（避免配音 sourceDuration 被写成 0.1s） */
function positiveNum(v: unknown, fallback: number): number {
  const n = Number(v)
  if (Number.isFinite(n) && n > 0) return n
  return fallback
}

function clamp01(v: number): number {
  if (!Number.isFinite(v)) return 1
  return Math.max(0, Math.min(1, v))
}

function finiteNonNegative(v: unknown, fallback = 0): number {
  const n = Number(v)
  return Number.isFinite(n) && n >= 0 ? n : fallback
}

function displayCueText(cue: TimedSubtitleCue): string {
  const body = String(cue.text || '').trim()
  if (!body) return ''
  const speaker = String(cue.speaker || '').trim()
  return speaker ? `${speaker}：${body}` : body
}

function parseDisplayCueText(text: string): { speaker: string | null; body: string } {
  const raw = String(text || '').trim()
  const m = raw.match(/^([^：:\r\n]{1,40})[：:](.+)$/)
  if (!m) return { speaker: null, body: raw }
  return { speaker: m[1]!.trim() || null, body: m[2]!.trim() }
}

function subtitleTextFromItems(items: PreviewSubtitleItem[], fallback?: string | null): string | null {
  const lines = items
    .map((item) => String(item.text || '').trim())
    .filter(Boolean)
  if (lines.length) return Array.from(new Set(lines)).join('\n')
  const oldText = String(fallback || '').trim()
  return oldText || null
}

function roundSeconds(v: number): number {
  return Number(Math.max(0, v).toFixed(3))
}

function shouldPersistSubtitleItemsAsCues(items: PreviewSubtitleItem[], clip: PreviewVideoClip): boolean {
  if (!items.length) return false
  if (items.length > 1) return true
  const item = items[0]!
  if (item.cue) return true
  return Math.abs(item.start - clip.start) > 0.01
    || Math.abs(item.duration - clip.duration) > 0.01
}

function buildTimedSubtitleCues(items: PreviewSubtitleItem[], clip: PreviewVideoClip): TimedSubtitleCue[] {
  if (!shouldPersistSubtitleItemsAsCues(items, clip)) return []
  const clipStart = Number(clip.start) || 0
  const clipDuration = Math.max(0, Number(clip.duration) || 0)
  const cues: TimedSubtitleCue[] = []
  for (const item of [...items].sort((a, b) => a.start - b.start)) {
    const parsed = parseDisplayCueText(item.text)
    const relativeStart = Math.max(0, Number(item.start) - clipStart)
    const relativeEnd = Math.min(
      clipDuration,
      relativeStart + Math.max(0, Number(item.duration) || 0)
    )
    if (!parsed.body || relativeEnd <= relativeStart) continue
    cues.push({
      startSeconds: roundSeconds(relativeStart),
      endSeconds: roundSeconds(relativeEnd),
      speaker: parsed.speaker ?? item.cue?.speaker ?? null,
      text: parsed.body,
      source: item.cue?.source || 'MANUAL'
    })
  }
  return cues
}

/** 服务端音量 0–100 → UI 0–1 */
export function volumeServerToUi(v: unknown, fallback = 1): number {
  const n = num(v, fallback * 100)
  return clamp01(n / 100)
}

/** UI 音量 0–1 → 服务端 0–100 */
export function volumeUiToServer(v: unknown, fallback = 100): number {
  const n = num(v, fallback / 100)
  return Math.max(0, Math.min(100, Math.round(n * 100)))
}

export function clipIdFromStoryboardId(storyboardId: number | null | undefined, index: number): string {
  const sid = Number(storyboardId)
  if (Number.isFinite(sid) && sid > 0) return `sb-${sid}`
  return `seg-${index + 1}`
}

export function parseStoryboardIdFromClipId(clipId: string): number | null {
  const m = String(clipId || '').match(/^sb-(\d+)$/)
  if (!m) return null
  const n = Number(m[1])
  return Number.isFinite(n) && n > 0 ? n : null
}

function emptyVideo(): TimelineSegment['video'] {
  return { genRecordId: null, url: null, durationSeconds: 0, volume: 100, muted: false }
}

function emptyVoice(): TimelineSegment['voice'] {
  return {
    audioRecordId: null,
    url: null,
    durationSeconds: 0,
    volume: 100,
    muted: false,
    ttsText: null,
    voiceLibraryId: null,
    voiceModelId: null,
    timbreCode: null,
    voiceName: null,
    emotion: null,
    speed: null,
    pitch: null
  }
}

function emptySubtitle(): TimelineSegment['subtitle'] {
  return {
    text: null,
    fontSize: 40,
    fontColor: '#FFFFFF',
    fontFamily: null,
    position: 'bottom',
    show: true
  }
}

function emptyBgm(): TimelineBgm {
  return { url: null, name: null, volume: 30, loop: true, fade: true }
}

/** 服务端 timeline → 预览 UI 状态 */
export function mapServerTimelineToUi(timeline: TimelineData | null | undefined): PreviewTimelineUiState {
  const segments = Array.isArray(timeline?.segments) ? timeline!.segments : []
  const videoClips: PreviewVideoClip[] = []
  const voiceItems: PreviewAudioItem[] = []
  const subtitleItems: PreviewSubtitleItem[] = []
  const videoVolumePreset: Record<string, number> = {}

  let cursor = 0
  segments.forEach((seg, index) => {
    const id = clipIdFromStoryboardId(seg.storyboardId, index)
    const video = seg.video || emptyVideo()
    const url = String(video.url || '').trim()
    const duration = Math.max(0.1, num(video.durationSeconds, url ? 5 : 1.5))
    const volUi = video.muted ? 0 : volumeServerToUi(video.volume, 1)
    videoVolumePreset[id] = volUi

    videoClips.push({
      id,
      kind: 'video',
      name: `分镜${num(seg.sortOrder, index + 1)}`,
      url,
      start: cursor,
      duration,
      sourceDuration: duration,
      trimStart: 0,
      trimEnd: duration,
      storyboardId: seg.storyboardId ?? null,
      genRecordId: video.genRecordId ?? null
    })

    const voice = seg.voice || emptyVoice()
    const voiceUrl = String(voice.url || '').trim()
    if (voiceUrl) {
      const vVol = voice.muted ? 0 : volumeServerToUi(voice.volume, 1)
      // 真实音频时长存 sourceDuration；轨道展示时长与分镜视频对齐（同字幕轨）
      // durationSeconds=0/空时必须回落分镜时长，否则预览会在 0.1s 后停掉配音
      const vDur = positiveNum(voice.durationSeconds, duration)
      voiceItems.push({
        id: `voice-${id}`,
        kind: 'voice',
        name: voice.voiceName || `配音 ${index + 1}`,
        url: voiceUrl,
        videoClipId: id,
        start: cursor,
        duration,
        sourceDuration: vDur,
        volume: vVol,
        fadeIn: 0,
        fadeOut: 0,
        loop: false,
        volumeCurve: [vVol, vVol, vVol],
        audioRecordId: voice.audioRecordId ?? null,
        ttsText: voice.ttsText ?? null,
        voiceLibraryId: voice.voiceLibraryId ?? null,
        voiceModelId: voice.voiceModelId ?? null,
        timbreCode: voice.timbreCode ?? null,
        voiceName: voice.voiceName ?? null
      })
    }

    const sub = seg.subtitle || emptySubtitle()
    const text = String(sub.text || '').trim()
    const cues = Array.isArray(sub.cues) ? sub.cues : []
    if ((text || cues.length) && sub.show !== false) {
      const fontSize = Math.max(12, Math.min(120, num(sub.fontSize, 40)))
      const fontColor = sub.fontColor || '#FFFFFF'
      let cueIndex = 0
      for (const cue of cues) {
        const startSeconds = finiteNonNegative(cue?.startSeconds)
        const endSeconds = finiteNonNegative(cue?.endSeconds, startSeconds)
        const cueText = displayCueText(cue)
        if (!cueText || startSeconds >= duration || endSeconds <= startSeconds) continue
        const clippedEnd = Math.min(endSeconds, duration)
        subtitleItems.push({
          id: `sub-${id}-cue-${cueIndex++}`,
          kind: 'subtitle',
          text: cueText,
          fontSize,
          fontColor,
          videoClipId: id,
          start: Number((cursor + startSeconds).toFixed(2)),
          duration: Math.max(0.01, Number((clippedEnd - startSeconds).toFixed(2))),
          show: true,
          cue,
          sourceMediaFingerprint: sub.sourceMediaFingerprint ?? null,
          sourceDialogueFingerprint: sub.sourceDialogueFingerprint ?? null,
          recognitionStatus: sub.recognitionStatus ?? null,
          recognitionProvider: sub.recognitionProvider ?? null,
          recognitionUpdatedAt: sub.recognitionUpdatedAt ?? null,
          recognitionError: sub.recognitionError ?? null
        })
      }
      // 无逐句 cues 时用整段 text；对口型合成等场景 voice.url 可能为空，仍应挂字幕轨
      if (!cueIndex && text) {
        const subtitleDuration = voiceUrl
          ? Math.min(duration, positiveNum(voice.durationSeconds, duration))
          : duration
        subtitleItems.push({
          id: `sub-${id}`,
          kind: 'subtitle',
          text,
          fontSize,
          fontColor,
          videoClipId: id,
          start: cursor,
          // 有独立配音时按配音真实时长窗口显示；无配音（含声音已在合成视频内）则铺满分镜时长。
          duration: subtitleDuration,
          show: true,
          sourceMediaFingerprint: sub.sourceMediaFingerprint ?? null,
          sourceDialogueFingerprint: sub.sourceDialogueFingerprint ?? null,
          recognitionStatus: sub.recognitionStatus ?? null,
          recognitionProvider: sub.recognitionProvider ?? null,
          recognitionUpdatedAt: sub.recognitionUpdatedAt ?? null,
          recognitionError: sub.recognitionError ?? null
        })
      }
    }

    cursor += duration
  })

  const bgm = timeline?.bgm || emptyBgm()
  const bgmUrl = persistableMediaUrl(bgm.url)
  const musicItems: PreviewAudioItem[] = []
  if (bgmUrl) {
    const mVol = volumeServerToUi(bgm.volume, 0.3)
    const total = Math.max(1, cursor)
    musicItems.push({
      id: `music-${Date.now()}`,
      kind: 'music',
      name: bgm.name || '背景音乐',
      url: bgmUrl,
      start: 0,
      duration: total,
      sourceDuration: total,
      volume: mVol,
      fadeIn: 0,
      fadeOut: 0,
      loop: bgm.loop !== false,
      volumeCurve: [mVol, mVol, mVol]
    })
  }

  return {
    videoClips,
    voiceItems,
    subtitleItems,
    musicItems,
    videoVolumePreset,
    resolution: String(timeline?.resolution || 'FHD')
  }
}

type MapUiToServerOpts = {
  resolution?: string
  /** 保存时尽量保留服务端原有音色元数据 */
  previous?: TimelineData | null
}

function findPrevSegment(
  previous: TimelineData | null | undefined,
  storyboardId: number | null,
  index: number
): TimelineSegment | undefined {
  const list = previous?.segments
  if (!Array.isArray(list) || !list.length) return undefined
  if (storyboardId != null) {
    const hit = list.find((s) => Number(s.storyboardId) === storyboardId)
    if (hit) return hit
  }
  return list[index]
}

/** 预览 UI 状态 → 服务端 timeline（整份覆盖保存） */
export function mapUiTimelineToServer(
  ui: {
    videoClips: PreviewVideoClip[]
    voiceItems: PreviewAudioItem[]
    subtitleItems: PreviewSubtitleItem[]
    musicItems: PreviewAudioItem[]
    videoVolumePreset?: Record<string, number>
  },
  opts: MapUiToServerOpts = {}
): TimelineData {
  const ordered = [...ui.videoClips].sort((a, b) => a.start - b.start)
  const voiceByClip = new Map(
    ui.voiceItems
      .filter((v) => v.videoClipId && String(v.url || '').trim())
      .map((v) => [v.videoClipId!, v] as const)
  )
  const subtitlesByClip = new Map<string, PreviewSubtitleItem[]>()
  ui.subtitleItems
    .filter((s) => s.videoClipId && String(s.text || '').trim())
    .forEach((s) => {
      const list = subtitlesByClip.get(s.videoClipId!) || []
      list.push(s)
      subtitlesByClip.set(s.videoClipId!, list)
    })

  const segments: TimelineSegment[] = ordered.map((clip, index) => {
    const storyboardId =
      clip.storyboardId ?? parseStoryboardIdFromClipId(clip.id)
    const prev = findPrevSegment(opts.previous, storyboardId, index)
    const voice = voiceByClip.get(clip.id)
    const subs = (subtitlesByClip.get(clip.id) || []).sort((a, b) => a.start - b.start)
    const sub = subs[0]
    const presetVol = ui.videoVolumePreset?.[clip.id]
    const videoVol =
      presetVol != null ? volumeUiToServer(presetVol) : volumeUiToServer(1)

    const video: TimelineSegment['video'] = {
      genRecordId: clip.genRecordId ?? prev?.video?.genRecordId ?? null,
      url: String(clip.url || '').trim() || null,
      durationSeconds: Math.max(0, num(clip.duration)),
      volume: videoVol,
      muted: videoVol <= 0
    }

    const voiceItem: TimelineSegment['voice'] = voice
      ? {
          audioRecordId: voice.audioRecordId ?? prev?.voice?.audioRecordId ?? null,
          url: String(voice.url || '').trim() || null,
          // 回写真实音频时长，避免把「填满分镜」的展示时长当成源音频时长
          durationSeconds: Math.max(
            0,
            num(
              voice.sourceDuration && voice.sourceDuration > 0
                ? voice.sourceDuration
                : voice.duration
            )
          ),
          volume: volumeUiToServer(voice.volume),
          muted: volumeUiToServer(voice.volume) <= 0,
          ttsText: voice.ttsText ?? prev?.voice?.ttsText ?? null,
          voiceLibraryId: voice.voiceLibraryId ?? prev?.voice?.voiceLibraryId ?? null,
          voiceModelId: voice.voiceModelId ?? prev?.voice?.voiceModelId ?? null,
          timbreCode: voice.timbreCode ?? prev?.voice?.timbreCode ?? null,
          voiceName: voice.voiceName ?? prev?.voice?.voiceName ?? null,
          emotion: prev?.voice?.emotion ?? null,
          speed: prev?.voice?.speed ?? null,
          pitch: prev?.voice?.pitch ?? null
        }
      : emptyVoice()

    const cues = buildTimedSubtitleCues(subs, clip)
    const metaSub = subs.find((item) => item.sourceMediaFingerprint || item.recognitionStatus || item.cue)
    // 无人声分镜没有 cues，但 TEXT_FALLBACK 是后端可复用检查点，必须随时间轴原样回传。
    const keepRecognitionCheckpoint =
      cues.length > 0 || metaSub?.recognitionStatus === 'TEXT_FALLBACK'
    const subtitleItem: TimelineSegment['subtitle'] = sub
      ? {
          text: subtitleTextFromItems(subs, prev?.subtitle?.text),
          fontSize: Math.max(12, Math.min(120, num(sub.fontSize, 40))),
          fontColor: sub.fontColor || '#FFFFFF',
          fontFamily: prev?.subtitle?.fontFamily ?? null,
          position: prev?.subtitle?.position || 'bottom',
          show: subs.some((item) => item.show !== false),
          cues: cues.length ? cues : null,
          sourceMediaFingerprint: keepRecognitionCheckpoint
            ? (metaSub?.sourceMediaFingerprint ?? prev?.subtitle?.sourceMediaFingerprint ?? null)
            : null,
          sourceDialogueFingerprint: keepRecognitionCheckpoint
            ? (metaSub?.sourceDialogueFingerprint ?? prev?.subtitle?.sourceDialogueFingerprint ?? null)
            : null,
          recognitionStatus: keepRecognitionCheckpoint
            ? (metaSub?.recognitionStatus ?? prev?.subtitle?.recognitionStatus ?? null)
            : null,
          recognitionProvider: keepRecognitionCheckpoint
            ? (metaSub?.recognitionProvider ?? prev?.subtitle?.recognitionProvider ?? null)
            : null,
          recognitionUpdatedAt: keepRecognitionCheckpoint
            ? (metaSub?.recognitionUpdatedAt ?? prev?.subtitle?.recognitionUpdatedAt ?? null)
            : null,
          recognitionError: keepRecognitionCheckpoint
            ? (metaSub?.recognitionError ?? prev?.subtitle?.recognitionError ?? null)
            : null
        }
      : emptySubtitle()

    return {
      storyboardId,
      sortOrder: index + 1,
      video,
      voice: voiceItem,
      subtitle: subtitleItem
    }
  })

  const music = ui.musicItems.find((m) => persistableMediaUrl(m.url))
  const bgm: TimelineBgm = music
    ? {
        url: persistableMediaUrl(music.url),
        name: music.name || null,
        volume: volumeUiToServer(music.volume, 30),
        loop: music.loop !== false,
        fade: true
      }
    : emptyBgm()

  const totalDurationSeconds = segments.reduce(
    (sum, s) => sum + num(s.video?.durationSeconds),
    0
  )

  return {
    version: opts.previous?.version ?? 1,
    resolution: opts.resolution || opts.previous?.resolution || 'FHD',
    totalDurationSeconds,
    segments,
    bgm,
    extraJson: opts.previous?.extraJson ?? null
  }
}
