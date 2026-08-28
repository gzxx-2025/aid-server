import { message } from 'antd'
import type { MutableRefObject } from 'react'
import type { StoryboardPanel, StoryboardVideoPanel } from '~/types'
import { type useStoryboardWorkbenchMutations } from '~/composables/useStoryboardWorkbenchMutations'
import { fetchOriginalVideoRecordsForStoryboard } from '~/utils/storyboardRecordBatch'
import {
  resolveStoryboardVideoRecordId,
  resolveStoryboardVideoRecordIdFromRows
} from '~/utils/storyboardFinalRecordId'
import { openVideoPreviewModal } from '~/utils/openVideoPreviewModal'
import { userStoryboardUnSetFinalVideo } from '~/utils/businessApi'
import { downloadMediaFileWithToast } from '~/utils/mediaFileDownload'
import { STORYBOARD_WORKBENCH_NEED_PROJECT_MSG } from '~/composables/useStoryboardWorkbenchMutations'
import { getPanelStoryboardVideo } from './storyboardVideoViewShared'

type StoryboardWorkbench = ReturnType<typeof useStoryboardWorkbenchMutations>

export function previewStoryboardVideo(panel: StoryboardVideoPanel | undefined, panelIndex: number) {
  const video = getPanelStoryboardVideo(panel)
  if (!video?.url) {
    message.warning('暂无视频可预览')
    return
  }
  openVideoPreviewModal({
    url: video.url,
    title: video.title || `分镜视频${panelIndex + 1}`
  })
}

export function downloadStoryboardVideo(panel: StoryboardVideoPanel | undefined, panelIndex: number) {
  const video = getPanelStoryboardVideo(panel)
  if (!video?.url) {
    message.warning('暂无视频可下载')
    return
  }
  void downloadMediaFileWithToast(video.url, video.title || `分镜视频${panelIndex + 1}`, '视频')
}

function errorMessage(error: unknown): string {
  const value = error as { msg?: string; message?: string }
  return value?.msg || value?.message || '操作失败'
}

export async function cancelStoryboardVideo(params: {
  panelIndex: number
  panelsRef: MutableRefObject<StoryboardVideoPanel[]>
  scriptPanelsRef: MutableRefObject<StoryboardPanel[]>
  resolvePanelStoryboardId: (index: number) => number | null
  onChange: (next: StoryboardVideoPanel[]) => void
  workbench: StoryboardWorkbench
}) {
  const {
    panelIndex,
    panelsRef,
    scriptPanelsRef,
    resolvePanelStoryboardId,
    onChange,
    workbench
  } = params
  const panel = panelsRef.current[panelIndex]
  const finalVideo = panel ? getPanelStoryboardVideo(panel) : null
  if (!finalVideo) return

  const storyboardId = resolvePanelStoryboardId(panelIndex)
  if (storyboardId == null) {
    message.warning('分镜信息异常，请刷新后重试')
    return
  }
  const context = await workbench.getProjectEpisodeContext()
  if (!context) {
    message.error(STORYBOARD_WORKBENCH_NEED_PROJECT_MSG)
    return
  }

  let recordId = resolveStoryboardVideoRecordId(finalVideo)
  if (recordId == null) {
    const scriptPanel = scriptPanelsRef.current[panelIndex]
    if (scriptPanel?.finalVideoId != null && Number(scriptPanel.finalVideoId) > 0) {
      recordId = Number(scriptPanel.finalVideoId)
    }
  }
  if (recordId == null) {
    try {
      const rows = await fetchOriginalVideoRecordsForStoryboard(context, storyboardId)
      recordId = resolveStoryboardVideoRecordIdFromRows(finalVideo, rows)
    } catch {
      // 统一走下方异常提示。
    }
  }
  if (recordId == null) {
    message.warning('分镜信息异常，请刷新后重试')
    return
  }

  try {
    await userStoryboardUnSetFinalVideo({
      projectId: context.projectId,
      episodeId: context.episodeId,
      storyboardId,
      recordId
    })
    const next = panelsRef.current.map((current, index) => {
      if (index !== panelIndex) return current
      const videos = Array.isArray(current.videos)
        ? current.videos.filter((video) => !video.isStoryboardVideo)
        : []
      return { ...current, videos, generating: false, generateError: undefined }
    })
    onChange(next)
    message.success('已取消分镜视频')
  } catch (error: unknown) {
    message.error(errorMessage(error))
  }
}
