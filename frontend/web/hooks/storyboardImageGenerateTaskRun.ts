import type {
StoryboardGenerateImageData,
StoryboardGenerateImageRequest
} from '~/types/business-api'
import { openRechargeModalFromInsufficientBalance } from '~/utils/api'
import { userStoryboardGenerateImage } from '~/utils/businessApi'
import {
type ProjectEpisodeContext
} from '~/utils/storyboardRecordBatch'
import { buildStoryboardImageSuccess,emitStoryboardImageCompleteProgress,parseCompleteData,submitAndFollowStoryboardImageBatchTask,validateSubmitItems,type StoryboardImageBatchGenerateResult,type StoryboardImageGenerateProgress,type StoryboardImageGenerateResult } from '~/hooks/storyboardImageGenerateTaskCore'
import { followStoryboardImageBatchGenerateTask,followStoryboardImageGenerateTask } from '~/hooks/storyboardImageGenerateTaskFollow'
/** 分镜图生成（单镜头，支持 count 1~8）并通过 SSE 追踪进度。
 * 结果写入 aid_gen_record，由调用方 refreshSceneRecords 从服务端拉列表。
 */
export async function runStoryboardImageGenerateTask(payload: {
  body: StoryboardGenerateImageRequest
  projectEpisode?: ProjectEpisodeContext | null
  onProgress?: (p: StoryboardImageGenerateProgress) => void
  onSubmitted?: (p: { taskId: number; recordId: number | null }) => void
  notifyGlobalTasks?: boolean
}): Promise<StoryboardImageGenerateResult> {
  const { body, onProgress, onSubmitted, projectEpisode } = payload
  const storyboardId = Number(body.storyboardIds?.[0])
  if (!Number.isFinite(storyboardId) || storyboardId <= 0) {
    return { ok: false, errorMessage: '分镜ID无效' }
  }

  let submitted: StoryboardGenerateImageData
  try {
    submitted = await userStoryboardGenerateImage(body)
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    const msg = String(err?.msg || err?.message || '提交生图失败')
    openRechargeModalFromInsufficientBalance(msg)
    return { ok: false, errorMessage: msg }
  }

  const rejectReason = validateSubmitItems(submitted)
  if (rejectReason) {
    return { ok: false, errorMessage: rejectReason }
  }

  const taskId = Number(submitted?.taskId)
  if (!Number.isFinite(taskId) || taskId <= 0) {
    const items = submitted?.items ?? []
    const reason = items
      .filter((item) => !item.accepted)
      .map((item) => item.reason)
      .filter(Boolean)
      .join('；')
    return { ok: false, errorMessage: reason || '提交失败：未返回任务ID' }
  }

  onSubmitted?.({ taskId, recordId: null })

  if (payload.notifyGlobalTasks !== false && typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
  }

  const submitStatus = String(submitted.status || '').toUpperCase()
  if (submitStatus === 'SUCCEEDED') {
    const parsed = parseCompleteData(submitted)
    if (parsed.imageUrl || parsed.recordId || parsed.items.length) {
      emitStoryboardImageCompleteProgress(onProgress, { taskId, storyboardId, data: submitted })
      return buildStoryboardImageSuccess({
        parsed,
        taskId,
        storyboardId,
        recordId: null,
        projectEpisode
      })
    }
  }
  if (submitStatus === 'FAILED') {
    return { ok: false, errorMessage: '分镜图生成失败' }
  }

  return followStoryboardImageGenerateTask({
    taskId,
    storyboardId,
    recordId: null,
    projectEpisode,
    onProgress: (p) => {
      onProgress?.({
        ...p,
        totalCount: p.totalCount ?? body.count ?? 1
      })
    }
  })
}
