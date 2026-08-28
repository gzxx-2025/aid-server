import type { TimedSubtitleCue } from '~/types/business-api'

export type PreviewVideoClip = {
  id: string
  kind: 'video'
  name: string
  url: string
  start: number
  duration: number
  sourceDuration: number
  trimStart: number
  trimEnd: number
  storyboardId?: number | null
  genRecordId?: number | null
}

export type PreviewAudioItem = {
  id: string
  kind: 'voice' | 'music'
  name: string
  url: string
  start: number
  duration: number
  videoClipId?: string
  sourceDuration?: number
  volume: number
  fadeIn: number
  fadeOut: number
  loop: boolean
  volumeCurve: number[]
  audioRecordId?: number | null
  ttsText?: string | null
  voiceLibraryId?: number | null
  voiceModelId?: number | null
  timbreCode?: string | null
  voiceName?: string | null
}

export type PreviewSubtitleItem = {
  id: string
  kind: 'subtitle'
  text: string
  start: number
  duration: number
  videoClipId?: string
  fontSize: number
  fontColor?: string
  show?: boolean
  cue?: TimedSubtitleCue
  sourceMediaFingerprint?: string | null
  sourceDialogueFingerprint?: string | null
  recognitionStatus?: string | null
  recognitionProvider?: string | null
  recognitionUpdatedAt?: string | null
  recognitionError?: string | null
}

export type PreviewTimelineUiState = {
  videoClips: PreviewVideoClip[]
  voiceItems: PreviewAudioItem[]
  subtitleItems: PreviewSubtitleItem[]
  musicItems: PreviewAudioItem[]
  videoVolumePreset: Record<string, number>
  resolution: string
}
