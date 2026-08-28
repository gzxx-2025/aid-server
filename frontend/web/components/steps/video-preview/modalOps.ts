import { message } from 'antd'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel, StoryboardVideoPanel, DubbingPanel } from '~/types'
import {
  resolvePreviewSubtitleText,
  resolvePreviewTimelineVideoUrl
} from '~/utils/storyboardVideoCover'
import type { EditStoryboardVideoModalScene } from '~/components/steps/edit-storyboard-video/types'
import type { MusicPickerConfirmPayload } from '~/components/steps/MusicPickerModal'
import { getVideoVolume, getVideoTimelineTotalSec } from './layoutOps'
import { stopPlayback } from './playbackOps'
import { scheduleRebuild } from './canvasOps'
import {
  applyClipTimelineDuration,
  hydrateVideoDurationsFromSource,
  hydrateVoiceDurationsFromSource,
  probeAudioDuration,
  relayoutVideoTrackAndLinkedTracks,
  resolveUntimedSubtitleDuration,
  scheduleTimelinePersist,
  syncMusicTimelineDurations,
  touchMusicItems,
  touchVideoClips,
  touchVoiceItems
} from './timelineOps'
import {
  EMPTY_CLIP_DURATION,
  MIN_DURATION,
  type TimelineAudioItem,
  type VideoPreviewCtx
} from './types'

/** 编辑视频 / 编辑配音 / 音乐弹窗的打开与回写联动（原 setup 弹窗函数区） */

/** 原 computed videoScenes：分镜视频面板 + 分镜脚本对齐后的弹窗场景列表 */
export function getVideoScenes(ctx: VideoPreviewCtx): EditStoryboardVideoModalScene[] {
  const scriptPanels = (useCreationStore.getState().formData.storyboardScript?.panels ||
    []) as StoryboardPanel[]
  return ctx.getProps().storyboardVideoPanels.map((panel, i) => {
    const byIndex = scriptPanels[i]
    const sid = Number(byIndex?.id)
    const sp =
      Number.isFinite(sid) && sid > 0
        ? scriptPanels.find((s) => Number(s.id) === sid) || byIndex
        : byIndex
    return {
      name: panel.title,
      videos: Array.isArray(panel.videos) ? panel.videos.map((v) => ({ ...v })) : [],
      scriptContent: sp?.scriptContent ?? '',
      scriptPanelTitle: sp?.title ?? panel.title,
      storyboardId: Number.isFinite(Number(sp?.id)) ? Number(sp?.id) : undefined,
      storyboardImages: Array.isArray(sp?.images) ? sp.images.map((img: any) => ({ ...img })) : []
    }
  })
}

/** 原 computed dubbingPanelsForModal */
export function getDubbingPanelsForModal(ctx: VideoPreviewCtx): DubbingPanel[] {
  const panels = useCreationStore.getState().formData.dubbing?.panels
  return Array.isArray(panels) && panels.length ? panels : ctx.getProps().dubbingPanels || []
}

/** 原 computed scriptPanelsForModal */
export function getScriptPanelsForModal(): StoryboardPanel[] {
  return (useCreationStore.getState().formData.storyboardScript?.panels || []) as StoryboardPanel[]
}

export function openEditVideoModalForClip(ctx: VideoPreviewCtx, clipId: string) {
  const S = ctx.state
  const panelIdx = ctx.getProps().storyboardVideoPanels.findIndex((p) => p.id === clipId)
  if (panelIdx < 0) {
    const clipIdx = S.videoClips.get().findIndex((c) => c.id === clipId)
    if (clipIdx >= 0 && clipIdx < ctx.getProps().storyboardVideoPanels.length) {
      S.editingVideoClipIndex.set(clipIdx)
    } else {
      message.warning('该片段暂无关联分镜，请先从前面步骤同步')
      return
    }
  } else {
    S.editingVideoClipIndex.set(panelIdx)
  }
  stopPlayback(ctx)
  S.isVideoModalOpen.set(true)
}

export function handleVideoUpdate(
  ctx: VideoPreviewCtx,
  sceneIndex: number,
  data: { name?: string; videos?: any[]; scriptContent?: string; scriptTitle?: string }
) {
  const S = ctx.state
  const props = ctx.getProps()
  if (sceneIndex < 0 || sceneIndex >= props.storyboardVideoPanels.length) return
  const store = useCreationStore.getState()
  const panels = [...store.formData.storyboardVideo.panels]
  const prev = panels[sceneIndex]
  if (!prev) return
  panels[sceneIndex] = {
    ...prev,
    title: data.name ?? prev.title,
    videos: data.videos ?? prev.videos
  }
  store.updateFormData({
    storyboardVideo: { ...store.formData.storyboardVideo, panels }
  })

  const clip =
    S.videoClips.get()[sceneIndex] ||
    S.videoClips.get().find((c) => c.id === panels[sceneIndex]?.id)
  if (clip) {
    clip.name = panels[sceneIndex]!.title
    const dub = props.dubbingPanels?.[sceneIndex]
    const nextUrl = resolvePreviewTimelineVideoUrl(dub, panels[sceneIndex])
    if (nextUrl) {
      clip.url = nextUrl
      touchVideoClips(ctx)
      void hydrateVideoDurationsFromSource(ctx)
    } else {
      clip.url = ''
      applyClipTimelineDuration(clip, EMPTY_CLIP_DURATION)
      touchVideoClips(ctx)
      relayoutVideoTrackAndLinkedTracks(ctx)
      scheduleRebuild(ctx, 'video')
    }
  }
}

function resolveDubbingPanelIndex(ctx: VideoPreviewCtx, clipId: string): number {
  const panels = getDubbingPanelsForModal(ctx)
  const byPanel = panels.findIndex((p) => p.id === clipId)
  if (byPanel >= 0) return byPanel
  const clipIdx = ctx.state.videoClips.get().findIndex((c) => c.id === clipId)
  if (clipIdx >= 0 && clipIdx < panels.length) return clipIdx
  return -1
}

export function openEditDubbingModalForClip(ctx: VideoPreviewCtx, clipId: string) {
  const S = ctx.state
  const panelIdx = resolveDubbingPanelIndex(ctx, clipId)
  if (panelIdx < 0) {
    message.warning('未找到对应配音分镜，请先从前面步骤同步')
    return
  }
  const panels = getDubbingPanelsForModal(ctx)
  const store = useCreationStore.getState()
  if (store.formData.dubbing) {
    store.updateFormData({
      dubbing: { ...store.formData.dubbing, panels: panels.map((p) => ({ ...p })) }
    })
  }
  S.editingDubbingClipIndex.set(panelIdx)
  stopPlayback(ctx)
  S.isDubbingModalOpen.set(true)
}

function applyDubbingPanelToTimeline(
  ctx: VideoPreviewCtx,
  index: number,
  dub: DubbingPanel
): boolean {
  const S = ctx.state
  const clip = S.videoClips.get().find((c) => c.id === dub.id) || S.videoClips.get()[index]
  if (!clip) return false

  clip.name = dub.title || clip.name

  const vPanel = ctx.getProps().storyboardVideoPanels[index]
  const nextVideoUrl = resolvePreviewTimelineVideoUrl(dub, vPanel)
  let videoUrlChanged = false
  if (nextVideoUrl && nextVideoUrl !== clip.url) {
    clip.url = nextVideoUrl
    videoUrlChanged = true
  }

  const presetVol = getVideoVolume(ctx, clip.id)
  const voiceUrl = dub.dubbingUploadedAudioUrl?.trim()
  const voice = S.voiceItems.get().find((v) => v.videoClipId === clip.id)
  if (voiceUrl) {
    if (voice) {
      if (voice.url !== voiceUrl) voice.sourceDuration = undefined
      voice.url = voiceUrl
      voice.name = dub.title || voice.name
      voice.start = clip.start
      voice.duration = Math.max(MIN_DURATION, Number(clip.duration.toFixed(2)))
    } else {
      S.voiceItems.get().push({
        id: `voice-${clip.id}`,
        kind: 'voice',
        name: dub.title || `配音 ${index + 1}`,
        url: voiceUrl,
        videoClipId: clip.id,
        start: clip.start,
        duration: Math.max(MIN_DURATION, Number(clip.duration.toFixed(2))),
        volume: presetVol,
        fadeIn: 0,
        fadeOut: 0,
        loop: false,
        volumeCurve: [presetVol, presetVol, presetVol]
      })
    }
  }

  const subtitleText = resolvePreviewSubtitleText(dub)
  const sub = S.subtitleItems.get().find((s) => s.videoClipId === clip.id && !s.cue)
  const hasTimedSubtitle = S.subtitleItems.get().some((s) => s.videoClipId === clip.id && s.cue)
  if (hasTimedSubtitle && sub?.id === `sub-${clip.id}`) {
    S.subtitleItems.set(S.subtitleItems.get().filter((item) => item.id !== sub.id))
  } else if (subtitleText && !hasTimedSubtitle) {
    const linkedVoice = S.voiceItems.get().find((v) => v.videoClipId === clip.id)
    const subtitleDuration = resolveUntimedSubtitleDuration(clip, linkedVoice)
    if (sub) {
      sub.text = subtitleText
      sub.start = clip.start
      sub.duration = subtitleDuration
      S.subtitleItems.set([...S.subtitleItems.get()])
    } else {
      S.subtitleItems.set([
        ...S.subtitleItems.get(),
        {
          id: `sub-${clip.id}`,
          kind: 'subtitle',
          text: subtitleText,
          fontSize: 40,
          videoClipId: clip.id,
          start: clip.start,
          duration: subtitleDuration
        }
      ])
    }
  } else if (!subtitleText && sub?.id === `sub-${clip.id}`) {
    S.subtitleItems.set(S.subtitleItems.get().filter((item) => item.id !== sub.id))
  } else if (!subtitleText && sub) {
    sub.text = ''
    S.subtitleItems.set([...S.subtitleItems.get()])
  }

  return videoUrlChanged
}

export function handleDubbingPanelsUpdate(ctx: VideoPreviewCtx, next: DubbingPanel[]) {
  const S = ctx.state
  const cloned = next.map((p) => ({ ...p }))
  const store = useCreationStore.getState()
  store.updateFormData({
    dubbing: { ...store.formData.dubbing, panels: cloned }
  })
  let needsHydrate = false
  cloned.forEach((panel, index) => {
    if (applyDubbingPanelToTimeline(ctx, index, panel)) needsHydrate = true
  })
  touchVideoClips(ctx)
  touchVoiceItems(ctx)
  if (needsHydrate) {
    void hydrateVideoDurationsFromSource(ctx)
  } else {
    relayoutVideoTrackAndLinkedTracks(ctx)
    scheduleRebuild(ctx, 'all')
  }
  if (S.voiceItems.get().some((voice) => voice.url)) {
    void hydrateVoiceDurationsFromSource(ctx)
  }
}

export function handleStoryboardVideoPanelsUpdate(
  ctx: VideoPreviewCtx,
  next: StoryboardVideoPanel[]
) {
  const S = ctx.state
  const store = useCreationStore.getState()
  store.updateFormData({
    storyboardVideo: { ...store.formData.storyboardVideo, panels: next.map((p) => ({ ...p })) }
  })
  next.forEach((panel, index) => {
    const clip = S.videoClips.get()[index] || S.videoClips.get().find((c) => c.id === panel.id)
    if (!clip) return
    const dub = ctx.getProps().dubbingPanels?.[index]
    const nextUrl = resolvePreviewTimelineVideoUrl(dub, panel)
    if (nextUrl && nextUrl !== clip.url) {
      clip.url = nextUrl
    }
  })
  touchVideoClips(ctx)
  void hydrateVideoDurationsFromSource(ctx)
}

export function onMusicBarClick(
  ctx: VideoPreviewCtx,
  bar: { empty: boolean; item: TimelineAudioItem }
) {
  if (!ctx.state.videoClips.get().length) {
    message.warning('请先同步前面步骤')
    return
  }
  if (bar.empty) {
    openEditMusicModal(ctx)
    return
  }
  ctx.state.selectedClip.set({ track: 'music', id: bar.item.id })
}

export function openEditMusicModal(ctx: VideoPreviewCtx) {
  if (!ctx.state.videoClips.get().length) {
    message.warning('请先同步前面步骤')
    return
  }
  ctx.state.isMusicModalOpen.set(true)
}

function syncBgmToStore(url: string) {
  const store = useCreationStore.getState()
  if (store.formData?.dubbing) {
    store.updateFormData({ dubbing: { ...store.formData.dubbing, bgm: url } })
  }
}

export function applyMusicSelection(ctx: VideoPreviewCtx, payload: MusicPickerConfirmPayload) {
  const S = ctx.state
  if (payload.type === 'none') {
    S.musicItems.set([])
    syncBgmToStore('')
    scheduleRebuild(ctx, 'audio')
    scheduleTimelinePersist(ctx)
    message.success('已设置为无音乐')
    return
  }

  const existing = S.musicItems.get()[0]
  const item: TimelineAudioItem = existing
    ? { ...existing }
    : {
        id: `music-${Date.now()}`,
        kind: 'music',
        name: payload.name,
        url: payload.url,
        start: 0,
        duration: Math.max(MIN_DURATION, getVideoTimelineTotalSec(ctx)),
        volume: payload.volume,
        fadeIn: 0,
        fadeOut: 0,
        loop: true,
        volumeCurve: [payload.volume, payload.volume, payload.volume]
      }

  item.name = payload.name
  item.url = payload.url
  item.volume = payload.volume
  item.volumeCurve = [payload.volume, payload.volume, payload.volume]
  item.loop = true
  item.start = 0

  S.musicItems.set([item])
  syncBgmToStore(payload.url)
  syncMusicTimelineDurations(ctx)
  scheduleRebuild(ctx, 'audio')
  scheduleTimelinePersist(ctx)
  void probeAudioDuration(payload.url).then((dur) => {
    item.sourceDuration = dur
    touchMusicItems(ctx)
    syncMusicTimelineDurations(ctx)
    scheduleRebuild(ctx, 'audio')
  })
  message.success(payload.type === 'local' ? '已应用本地音乐' : `已选择音乐：${payload.name}`)
}

export function onMusicPickerConfirm(ctx: VideoPreviewCtx, payload: MusicPickerConfirmPayload) {
  applyMusicSelection(ctx, payload)
}

/** 原 computed activeMusicItem */
export function getActiveMusicItem(ctx: VideoPreviewCtx): TimelineAudioItem | null {
  return ctx.state.musicItems.get()[0] || null
}
