import type { StoryboardVideoPanel } from '~/types'
import type { BillingQuoteRequest } from '~/types/business-api'

/** 面板上展示的分镜视频条目（原 StoryboardVideo.vue getPanelStoryboardVideo 返回形状） */
export type StoryboardVideoPanelVideoItem = NonNullable<StoryboardVideoPanel['videos']>[number]

/** 当前分镜已设置为分镜视频的那条；videos 空时回落 finalVideoUrl（批量生成后同步缺口） */
export function getPanelStoryboardVideo(
  panel: StoryboardVideoPanel | undefined
): StoryboardVideoPanelVideoItem | null {
  if (!panel) return null
  const list = Array.isArray(panel.videos) ? panel.videos : []
  const fromVideos = list.find((v: any) => v.isStoryboardVideo) || null
  if (fromVideos) return fromVideos
  const finalUrl = String(panel.finalVideoUrl ?? '').trim()
  if (!finalUrl) return null
  return {
    id: `final-video-fallback`,
    url: finalUrl,
    title: panel.title || '分镜视频',
    source: '生成记录',
    isStoryboardVideo: true
  } as StoryboardVideoPanelVideoItem
}

/** 列表 / 卡片两种视图共享的回调与派生数据（由 StoryboardVideo.tsx 统一下发） */
export interface StoryboardVideoViewSharedProps {
  panels: StoryboardVideoPanel[]
  displayPanelTitle: (panel: StoryboardVideoPanel, index: number) => string
  isPanelVideoGenerating: (panel: StoryboardVideoPanel, index: number) => boolean
  panelVideoGenerateError: (panel: StoryboardVideoPanel, index: number) => string | undefined
  editingId: string | null
  editingTitle: string
  onEditingTitleChange: (v: string) => void
  onStartEditTitle: (panel: StoryboardVideoPanel) => void
  onFinishEditTitle: (panel: StoryboardVideoPanel) => void
  onCancelEditTitle: () => void
  onOpenEditVideoModal: (index: number) => void
  onCopyPanel: (index: number) => void
  onRemovePanel: (index: number) => void
  onJumpToScriptWithImageModal: (index: number) => void
  onJumpToDubbingWithModal: (index: number) => void
  onRegeneratePanel: (index: number) => void
  resolveRegeneratePanelBillingRequest: (index: number) => Promise<BillingQuoteRequest | null>
  onCancelStoryboardVideo: (index: number) => void
  onPreviewStoryboardVideo: (index: number) => void
  onDownloadStoryboardVideo: (index: number) => void
  /** 播放控制（useStoryboardVideoPlayback） */
  playingPanelIndex: number
  panelVideoMediaReady: Record<number, boolean>
  setPanelVideoRef: (el: unknown, panelIndex: number) => void
  onMarkPanelVideoMediaReady: (panelIndex: number) => void
  onPanelVideoEnded: (panelIndex: number) => void
  onPanelVideoPause: (panelIndex: number) => void
  onPlayPanelVideo: (panelIndex: number) => void
  onFullscreenPanelVideo: (panelIndex: number) => void
}
