/**
 * 成品预览：时间轴读写 + 分段导出清单（扁平 API，无深层嵌套）。
 */
import { useCreationStore,type CreationStoreState } from '~/stores/creation'
import type {
EpisodeSegmentVideosResult,
EpisodeSegmentZipDownloadRequest,
EpisodeTimelineResult,
TimelineData
} from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import {
triggerBrowserBlobDownload,
userEpisodeExportSegments,
userEpisodeExportSegmentsZip,
userEpisodeTimelineGet,
userEpisodeTimelineSave
} from '~/utils/businessApi'
import {
mapServerTimelineToUi,
mapUiTimelineToServer,
type PreviewTimelineUiState
} from '~/utils/episodeTimelineMap'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'

export type EpisodeTimelineLoadOptions = {
  store?: CreationStoreState
  route: RouteLikeLocation
  rebuild?: boolean
  episodeEditorId?: number | null
}

export type EpisodeTimelineSaveUiPayload = {
  videoClips: PreviewTimelineUiState['videoClips']
  voiceItems: PreviewTimelineUiState['voiceItems']
  subtitleItems: PreviewTimelineUiState['subtitleItems']
  musicItems: PreviewTimelineUiState['musicItems']
  videoVolumePreset?: Record<string, number>
  resolution?: string
}

function applyEditorMetaToStore(store: CreationStoreState, result: EpisodeTimelineResult) {
  store.setCurrentMediaContext({
    episodeEditorId: result.episodeEditorId,
    finalVideoUrl: result.finalVideoUrl ?? null,
    exportStatus: result.exportStatus
  })
}

export async function loadEpisodeTimeline(
  opts: EpisodeTimelineLoadOptions
): Promise<{ result: EpisodeTimelineResult; ui: PreviewTimelineUiState }> {
  const store = opts.store ?? useCreationStore.getState()
  const ctx = await resolveStoryScriptSaveContext(store, opts.route)
  if (!ctx) throw new Error('缺少作品信息，请从作品库重新进入')

  const editorId = Number(opts.episodeEditorId ?? store.currentEpisodeEditorId)
  const body =
    Number.isFinite(editorId) && editorId > 0
      ? { episodeEditorId: editorId, rebuild: Boolean(opts.rebuild) }
      : {
          projectId: ctx.projectId,
          episodeId: ctx.episodeId,
          rebuild: Boolean(opts.rebuild)
        }

  const result = await userEpisodeTimelineGet(body)
  applyEditorMetaToStore(store, result)
  return { result, ui: mapServerTimelineToUi(result.timeline) }
}

export async function saveEpisodeTimelineFromUi(opts: {
  store?: CreationStoreState
  route: RouteLikeLocation
  ui: EpisodeTimelineSaveUiPayload
  previousTimeline?: TimelineData | null
}): Promise<EpisodeTimelineResult> {
  const store = opts.store ?? useCreationStore.getState()
  const ctx = await resolveStoryScriptSaveContext(store, opts.route)
  if (!ctx) throw new Error('缺少作品信息，请从作品库重新进入')

  const timeline = mapUiTimelineToServer(opts.ui, {
    resolution: opts.ui.resolution,
    previous: opts.previousTimeline
  })

  const editorId = Number(store.currentEpisodeEditorId)
  const body =
    Number.isFinite(editorId) && editorId > 0
      ? { episodeEditorId: editorId, timeline }
      : { projectId: ctx.projectId, episodeId: ctx.episodeId, timeline }

  const result = await userEpisodeTimelineSave(body)
  applyEditorMetaToStore(store, result)
  return result
}

export function createDebouncedTimelineSaver(delayMs = 2500) {
  let timer: ReturnType<typeof setTimeout> | null = null
  let latest: Parameters<typeof saveEpisodeTimelineFromUi>[0] | null = null
  let inflight: Promise<EpisodeTimelineResult | null> | null = null

  const flush = async (): Promise<EpisodeTimelineResult | null> => {
    if (!latest) return null
    const payload = latest
    latest = null
    try {
      return await saveEpisodeTimelineFromUi(payload)
    } catch (e) {
      latest = payload
      throw e
    }
  }

  return {
    schedule(payload: Parameters<typeof saveEpisodeTimelineFromUi>[0]) {
      latest = payload
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => {
        timer = null
        inflight = flush().catch(() => null)
      }, delayMs)
    },
    async flushNow() {
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
      if (inflight) {
        await inflight
        inflight = null
      }
      return flush()
    },
    cancel() {
      if (timer) clearTimeout(timer)
      timer = null
      latest = null
    }
  }
}

export async function fetchEpisodeExportSegmentsForContext(opts: {
  store?: CreationStoreState
  route: RouteLikeLocation
}): Promise<EpisodeSegmentVideosResult> {
  const store = opts.store ?? useCreationStore.getState()
  const ctx = await resolveStoryScriptSaveContext(store, opts.route)
  if (!ctx) throw new Error('缺少作品信息，请从作品库重新进入')
  return userEpisodeExportSegments({
    projectId: ctx.projectId,
    episodeId: ctx.episodeId
  })
}

/**
 * 分段素材 ZIP 流式打包下载（POST /api/user/episode/export/segments/zip）。
 * 返回二进制 zip，不走接口加密。
 */
export async function downloadEpisodeSegmentsZipForContext(
  opts: {
    store?: CreationStoreState
    route: RouteLikeLocation
  } & Partial<
    Pick<
      EpisodeSegmentZipDownloadRequest,
      'includeImages' | 'includeVideos' | 'includeAudios' | 'includeSubtitles'
    >
  >,
  onProgress?: (message: string) => void
): Promise<{ filename: string }> {
  const store = opts.store ?? useCreationStore.getState()
  const ctx = await resolveStoryScriptSaveContext(store, opts.route)
  if (!ctx) throw new Error('缺少作品信息，请从作品库重新进入')
  onProgress?.('正在打包分段素材…')
  const { blob, filename } = await userEpisodeExportSegmentsZip({
    projectId: ctx.projectId,
    episodeId: ctx.episodeId,
    includeImages: opts.includeImages ?? true,
    includeVideos: opts.includeVideos ?? true,
    includeAudios: opts.includeAudios ?? true,
    includeSubtitles: opts.includeSubtitles ?? true
  })
  onProgress?.('正在保存 ZIP…')
  triggerBrowserBlobDownload(blob, filename)
  return { filename }
}

/**
 * 按清单逐个触发浏览器下载（优先对口型成片，其次视频+配音）。
 * 使用隐藏 iframe，避免 target=_blank 新开窗口，也避免 fetch 跨域打包 ZIP。
 * @deprecated 优先使用 {@link downloadEpisodeSegmentsZipForContext}
 */
export async function downloadEpisodeSegmentFiles(
  result: EpisodeSegmentVideosResult,
  onProgress?: (message: string) => void
): Promise<{ downloaded: number; skipped: number }> {
  const items = Array.isArray(result.items) ? result.items : []
  let downloaded = 0
  let skipped = 0

  const trigger = (url: string) => {
    const href = String(url || '').trim()
    if (!href || typeof document === 'undefined') return
    const iframe = document.createElement('iframe')
    iframe.style.cssText = 'position:fixed;width:0;height:0;border:0;opacity:0;pointer-events:none;left:-9999px;top:-9999px'
    iframe.setAttribute('aria-hidden', 'true')
    iframe.src = href
    document.body.appendChild(iframe)
    window.setTimeout(() => {
      try {
        iframe.remove()
      } catch {
        /* ignore */
      }
    }, 60_000)
  }

  for (let i = 0; i < items.length; i++) {
    const item = items[i]!
    const order = item.sortOrder ?? i + 1
    const video = String(item.videoUrl || '').trim()
    const audio = String(item.audioUrl || '').trim()

    if (video) {
      onProgress?.(`下载分段 ${order}/${items.length}…`)
      trigger(video)
      downloaded += 1
      if (audio) {
        // 与视频错开，降低浏览器拦截连续下载概率
        await new Promise((r) => setTimeout(r, 400))
        trigger(audio)
        downloaded += 1
      }
    } else {
      skipped += 1
    }

    // 逐个间隔，尽量让浏览器放行多次下载
    await new Promise((r) => setTimeout(r, 600))
  }

  return { downloaded, skipped }
}
