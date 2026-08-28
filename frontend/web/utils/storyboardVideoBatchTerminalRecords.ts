import { useCreationStore } from '~/stores/creation'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import { userStoryboardSetFinalVideo } from '~/utils/businessApi'
import { clearProjectStoryboardRecordCache, type ProjectEpisodeContext } from '~/utils/storyboardRecordBatch'
import { setStoryboardVideoStepFormPanels } from '~/utils/storyboardVideoBatchShared'
import type { StoryboardPanel } from '~/types'
import type { TaskVideoBatchSuccessItem } from '~/utils/taskPartialFailed'

export function syncScriptFinalVideoFromTerminalItems(items: TaskVideoBatchSuccessItem[]) {
  if (!items.length) return
  const scripts = [
    ...((useCreationStore.getState().formData.storyboardScript.panels as StoryboardPanel[]) || [])
  ]
  let changed = false
  for (const item of items) {
    const storyboardId = Number(item.storyboardId)
    const url = String(item.videoUrl ?? '').trim()
    const recordId = Number(item.recordId)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0 || !url) continue
    const index = scripts.findIndex((panel) => parseServerStoryboardId(panel.id) === storyboardId)
    if (index < 0) continue
    const current = scripts[index]!
    const sameUrl = String(current.finalVideoUrl ?? '').trim() === url
    const sameId = Number(current.finalVideoId) > 0 && Number(current.finalVideoId) === recordId
    if (sameUrl && sameId) continue
    scripts[index] = {
      ...current,
      finalVideoUrl: url,
      ...(Number.isFinite(recordId) && recordId > 0 ? { finalVideoId: recordId } : {})
    }
    changed = true
  }
  if (changed) setStoryboardVideoStepFormPanels({ script: scripts })
}

export async function setFinalVideosFromTerminalItems(
  context: ProjectEpisodeContext,
  items: TaskVideoBatchSuccessItem[]
) {
  if (!items.length) return
  const batchSize = 50
  for (let index = 0; index < items.length; index += batchSize) {
    const batch = items.slice(index, index + batchSize).map((item) => ({
      storyboardId: item.storyboardId,
      recordId: item.recordId
    }))
    try {
      await userStoryboardSetFinalVideo({
        projectId: context.projectId,
        episodeId: context.episodeId,
        items: batch
      })
    } catch {
      // 设主失败不阻断 SSE 视频展示。
    }
  }
  clearProjectStoryboardRecordCache(context)
}
