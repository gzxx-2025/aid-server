import type { Step4PlusLiveGenSnapshot } from '~/stores/creation'

function hasPositiveTaskId(raw: unknown): boolean {
  const taskId = Number(raw)
  return Number.isFinite(taskId) && taskId > 0
}

export function scoreStep4PlusLiveGenBlob(blob: Step4PlusLiveGenSnapshot): number {
  let score = 0
  if (blob.isGeneratingStoryboard) score += 100
  if (blob.isGeneratingStoryboardImageBatch) score += 100
  if (blob.isGeneratingStoryboardVideo) score += 100
  if (hasPositiveTaskId(blob.storyboardImageBatchActiveTaskId)) score += 40
  if (hasPositiveTaskId(blob.storyboardImageBatchActiveImageTaskId)) score += 40
  if (hasPositiveTaskId(blob.storyboardVideoBatchActivePromptTaskId)) score += 40
  if (hasPositiveTaskId(blob.storyboardVideoBatchActiveVideoTaskId)) score += 40
  score += Object.values(blob.storyboardPanelImageGenStatusByStoryboardId || {}).filter(
    (status) => status === 'generating'
  ).length
  score += Object.values(blob.storyboardPanelVideoGenStatusByStoryboardId || {}).filter(
    (status) => status === 'generating'
  ).length
  score += blob.storyboardImageBatchTargetStoryboardIds?.length ?? 0
  score += blob.storyboardVideoBatchTargetStoryboardIds?.length ?? 0
  score += Object.keys(blob.storyboardImageGenTasksByStoryboardId || {}).length * 20
  score += Object.keys(blob.storyboardImagePromptGenTasksByStoryboardId || {}).length * 20
  score += Object.keys(blob.storyboardVideoGenTasksByStoryboardId || {}).length * 20
  score += Object.keys(blob.storyboardVideoPromptGenTasksByStoryboardId || {}).length * 20
  score += Object.keys(blob.storyboardDubbingGenTasksByStoryboardId || {}).length * 20
  return score
}
