import { userStoryboardGenerateImageWithPrompt } from '~/utils/businessApi'
import {
  STORYBOARD_GEN_CONFIG_SCENE_CODES,
  resolveProjectGenImageSubmitFields,
  resolveStoryboardGenConfigLlmFields
} from '~/utils/projectGenConfig'
import { imageBatchBizErr, parseImageBatchTaskId } from '~/utils/storyboardImageBatchShared'
import type { ProjectEpisodeContext } from '~/utils/storyboardRecordBatch'

export async function submitStoryboardImageWithPromptBatch(
  context: ProjectEpisodeContext,
  targets: number[],
  overwrite: boolean,
  options?: {
    manualAgentModelPick?: boolean
    agentCode?: string
    modelCode?: string
    genScenario?: string
    genNegativePrompt?: string
  }
): Promise<{ ok: boolean; taskId?: number; message?: string; totalShots?: number }> {
  const promptFields = await resolveStoryboardGenConfigLlmFields(
    context.projectId,
    STORYBOARD_GEN_CONFIG_SCENE_CODES.stylist,
    false,
    '',
    ''
  )
  const imageFields = await resolveProjectGenImageSubmitFields(
    context.projectId,
    STORYBOARD_GEN_CONFIG_SCENE_CODES.image,
    options?.manualAgentModelPick
      ? { agentCode: options.agentCode, modelCode: options.modelCode }
      : undefined
  )
  try {
    const submitted = await userStoryboardGenerateImageWithPrompt({
      projectId: context.projectId,
      episodeId: context.episodeId,
      storyboardIds: targets,
      overwrite,
      ...(promptFields.agentCode ? { agentCode: promptFields.agentCode } : {}),
      ...(promptFields.modelCode ? { modelCode: promptFields.modelCode } : {}),
      ...(imageFields.agentCode ? { genAgentCode: imageFields.agentCode } : {}),
      ...(imageFields.modelCode ? { genModelName: imageFields.modelCode } : {}),
      ...(imageFields.aspectRatio ? { genAspectRatio: imageFields.aspectRatio } : {}),
      ...(imageFields.resolution ? { genSize: imageFields.resolution } : {}),
      ...(String(options?.genScenario || '').trim()
        ? { genScenario: String(options?.genScenario || '').trim() }
        : {}),
      ...(String(options?.genNegativePrompt || '').trim()
        ? { genNegativePrompt: String(options?.genNegativePrompt || '').trim() }
        : {})
    })
    const taskId = parseImageBatchTaskId(submitted.taskId)
    if (!taskId) return { ok: false, message: '提交失败：未返回任务ID' }
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
    }
    return {
      ok: true,
      taskId,
      totalShots: Number(submitted.totalShots) > 0 ? Number(submitted.totalShots) : targets.length
    }
  } catch (error: unknown) {
    return { ok: false, message: imageBatchBizErr(error) }
  }
}
