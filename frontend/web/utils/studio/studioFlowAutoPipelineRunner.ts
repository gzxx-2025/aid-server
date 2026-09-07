import { getRouteLikeSnapshot } from '@/hooks/useRouteLike'
import { useCreationStore } from '@/stores/creation'
import type { AssetExtractType } from '@/types/business-api'
import type { StoryboardPanel, StoryboardVideoPanel, DubbingPanel } from '@/types'
import { buildAssetExtractSubmitBody } from '@/utils/assetExtractSubmitBody'
import {
  userAssetExtractEstimate,
  userAssetExtractFormGenerate,
  userAssetExtractFormGenerateImage,
  userAssetExtractParallel
} from '@/utils/businessApi'
import {
  extractFormGenerateImageSubmitTaskId,
  extractFormGenerateTextSubmitTaskId
} from '@/components/steps/scene-character-prop/scpTaskUtils'
import { syncExtractedAssetsFromServer } from '@/hooks/createFlowExtractAgents/extractRuntime'
import {
  FORM_GENERATE_SCENE_CODE_BY_TYPE,
  FORM_IMAGE_SCENE_CODE_BY_TYPE,
  resolveProjectGenImageSubmitFields,
  resolveProjectGenLlmSubmitFields
} from '@/utils/projectGenConfig'
import { resolveStoryScriptSaveContext } from '@/utils/storyScriptSaveContext'
import {
  collectStudioFlowAssetsMissingForms,
  collectStudioFlowAllFormIdsMissingImages,
  collectStudioFlowFormIdsMissingImages,
  countStudioFlowRpsAssets,
  type StudioFlowRpsAssetType
} from './studioFlowAutoPipelineAssets'
import { waitStudioFlowTaskStreamDone, maybeOpenStudioFlowRechargeFromMessage } from './studioFlowAutoPipelineTask'
import {
  markStudioFlowRpsNodesGeneratingByFormIds,
  seedStudioFlowTaskFormIdTargets
} from './studioFlowProject'
import { skipsStoryboardImageGeneration } from '@/utils/creationModeUiRules'
import { resolveStoryboardPanelCoverImage } from '@/utils/storyboardImageCover'
import { panelHasStoryboardVideo } from '@/utils/storyboardVideoBatchShared'

export type StudioFlowAutoPipelineStage =
  | 'extract'
  | 'form-text'
  | 'form-image'
  | 'storyboard-script'
  | 'storyboard-image'
  | 'storyboard-video'
  | 'dubbing'
  | 'done'

export interface StudioFlowAutoPipelineProgress {
  stage: StudioFlowAutoPipelineStage
  title: string
  message: string
  percent?: number
}

export interface StudioFlowAutoPipelineRunOptions {
  projectId: number
  episodeId: number
  /** 从该阶段开始（含）；用于失败/充值后续跑 */
  fromStage?: StudioFlowAutoPipelineStage
  onProgress: (progress: StudioFlowAutoPipelineProgress) => void
  shouldCancel: () => boolean
  onStageComplete?: (stage: StudioFlowAutoPipelineStage) => void | Promise<void>
  /** 任务已提交、等待 SSE 期间刷新画布任务投影 */
  onTaskSubmitted?: (stage: StudioFlowAutoPipelineStage) => void | Promise<void>
  runStoryboardScriptBatch: (panels: StoryboardPanel[]) => Promise<{
    ok: boolean
    panels: StoryboardPanel[]
    message?: string
  }>
  runStoryboardImageBatch: (panels: StoryboardPanel[]) => Promise<{
    ok: boolean
    panels: StoryboardPanel[]
    message?: string
  }>
  runStoryboardVideoBatch: (payload: {
    scriptPanels: StoryboardPanel[]
    videoPanels: StoryboardVideoPanel[]
  }) => Promise<{
    ok: boolean
    message?: string
  }>
  runDubbingBatch: (payload: {
    scriptPanels: StoryboardPanel[]
    dubbingPanels: DubbingPanel[]
    panelIndices: number[]
  }) => Promise<{
    ok: boolean
    message?: string
  }>
}

const STAGE_TITLES: Record<StudioFlowAutoPipelineStage, string> = {
  extract: '提取场景/角色/道具',
  'form-text': '生成形态文案',
  'form-image': '批量生成形态图',
  'storyboard-script': '生成分镜脚本',
  'storyboard-image': '批量生成分镜图',
  'storyboard-video': '批量生成分镜视频',
  dubbing: '批量配音',
  done: '创作流水线完成'
}

export function studioFlowAutoPipelineStageTitle(stage: StudioFlowAutoPipelineStage): string {
  return STAGE_TITLES[stage] || stage
}

const PIPELINE_STAGE_ORDER: StudioFlowAutoPipelineStage[] = [
  'extract',
  'form-text',
  'form-image',
  'storyboard-script',
  'storyboard-image',
  'storyboard-video',
  'dubbing'
]

/** 专业版 / 多参跳过分镜图；图生 / 宫格在分镜脚本后多一步出图 */
export function studioFlowAutoPipelineStages(
  creationMode?: string | null
): StudioFlowAutoPipelineStage[] {
  return PIPELINE_STAGE_ORDER.filter((stage) =>
    stage !== 'storyboard-image' || !skipsStoryboardImageGeneration(creationMode)
  )
}

export function studioFlowAutoPipelineStartIndex(
  stages: StudioFlowAutoPipelineStage[],
  fromStage?: StudioFlowAutoPipelineStage
): number {
  if (!fromStage) return 0
  const exact = stages.indexOf(fromStage)
  if (exact >= 0) return exact
  const origin = PIPELINE_STAGE_ORDER.indexOf(fromStage)
  const next = stages.findIndex((stage) => PIPELINE_STAGE_ORDER.indexOf(stage) > origin)
  return next >= 0 ? next : stages.length
}

function parseTaskId(raw: unknown): number | null {
  const value = Number(raw)
  return Number.isFinite(value) && value > 0 ? value : null
}

function reportProgress(
  onProgress: StudioFlowAutoPipelineRunOptions['onProgress'],
  stage: StudioFlowAutoPipelineStage,
  message: string,
  percent?: number
) {
  onProgress({
    stage,
    title: STAGE_TITLES[stage],
    message,
    percent
  })
}

async function runExtractStage(
  options: StudioFlowAutoPipelineRunOptions
): Promise<{ ok: boolean; message?: string; skipped?: boolean }> {
  const store = useCreationStore.getState()
  const route = getRouteLikeSnapshot()
  const ctx = await resolveStoryScriptSaveContext(store, route)
  if (!ctx) return { ok: false, message: '缺少项目信息，无法提取素材' }

  const existingCount = await countStudioFlowRpsAssets(ctx.projectId, ctx.episodeId)
  if (existingCount > 0) {
    reportProgress(options.onProgress, 'extract', '已有素材，跳过提取')
    return { ok: true, skipped: true }
  }

  const extractTypes: AssetExtractType[] = ['scene', 'character', 'prop']
  reportProgress(options.onProgress, 'extract', '正在提交提取任务…')

  const estimate = await userAssetExtractEstimate({
    projectId: ctx.projectId,
    episodeId: ctx.episodeId,
    extractTypes
  })
  const isSeries = String(estimate?.projectType || '').toLowerCase() === 'series'
  const runOnce = async (types: AssetExtractType[]) => {
    if (!types.length) return { ok: true as const }
    const submitBody = await buildAssetExtractSubmitBody({
      store,
      route,
      extractTypes: types,
      modelCodes: store.extractModelCodes,
      overwrite: false
    })
    if (!submitBody) return { ok: false as const, message: '无法构造提取请求' }
    const submit = await userAssetExtractParallel(submitBody)
    const taskId = parseTaskId((submit as { taskId?: number }).taskId ?? submit.id)
    if (!taskId) return { ok: false as const, message: '提取任务提交失败' }

    const outcome = await waitStudioFlowTaskStreamDone(taskId, (snapshot) => {
      reportProgress(
        options.onProgress,
        'extract',
        snapshot.message || snapshot.stepTitle || '提取进行中…',
        snapshot.progress
      )
    })
    if (!outcome.ok) return { ok: false as const, message: outcome.message || '提取失败' }

    await syncExtractedAssetsFromServer(ctx, types)
    return { ok: true as const }
  }

  if (isSeries && extractTypes.includes('character') &&
    (extractTypes.includes('scene') || extractTypes.includes('prop'))) {
    const characterOutcome = await runOnce(['character'])
    if (!characterOutcome.ok) return characterOutcome
    if (options.shouldCancel()) return { ok: false, message: '已取消' }
    const rest: AssetExtractType[] = []
    if (extractTypes.includes('scene')) rest.push('scene')
    if (extractTypes.includes('prop')) rest.push('prop')
    return runOnce(rest)
  }

  return runOnce(extractTypes)
}

async function runFormTextForTab(
  options: StudioFlowAutoPipelineRunOptions,
  tab: StudioFlowRpsAssetType,
  assetIds: number[]
): Promise<{ ok: boolean; message?: string }> {
  if (!assetIds.length) return { ok: true }
  const store = useCreationStore.getState()
  const projectId = store.currentProjectId
  if (!projectId) return { ok: false, message: '缺少项目信息' }

  const label = tab === 'scene' ? '场景' : tab === 'character' ? '角色' : '道具'
  reportProgress(
    options.onProgress,
    'form-text',
    `正在为 ${assetIds.length} 个${label}生成形态文案…`
  )

  const fields = await resolveProjectGenLlmSubmitFields(
    projectId,
    FORM_GENERATE_SCENE_CODE_BY_TYPE[tab]
  )
  if (!fields.agentCode) {
    return { ok: false, message: `请先在生成配置中为「${label}形态」配置智能体` }
  }

  const submit = await userAssetExtractFormGenerate({
    assetIds,
    agentCode: fields.agentCode,
    ...(fields.modelCode ? { modelCode: fields.modelCode } : {})
  })
  const taskId = extractFormGenerateTextSubmitTaskId(submit)
  if (!taskId) return { ok: false, message: '形态文案任务提交失败' }

  const outcome = await waitStudioFlowTaskStreamDone(taskId, (snapshot) => {
    reportProgress(
      options.onProgress,
      'form-text',
      snapshot.message || snapshot.stepTitle || `${label}形态文案生成中…`,
      snapshot.progress
    )
  })
  return outcome.ok
    ? { ok: true }
    : { ok: false, message: outcome.message || `${label}形态文案生成失败` }
}

async function runFormTextStage(
  options: StudioFlowAutoPipelineRunOptions
): Promise<{ ok: boolean; message?: string; skipped?: boolean }> {
  const { projectId, episodeId } = options
  const tabs: StudioFlowRpsAssetType[] = ['scene', 'character', 'prop']
  let ranAny = false

  for (const tab of tabs) {
    if (options.shouldCancel()) return { ok: false, message: '已取消' }
    const assetIds = await collectStudioFlowAssetsMissingForms(projectId, episodeId, tab)
    if (!assetIds.length) continue
    ranAny = true
    const outcome = await runFormTextForTab(options, tab, assetIds)
    if (!outcome.ok) return outcome
  }

  if (!ranAny) {
    reportProgress(options.onProgress, 'form-text', '形态文案已就绪，跳过')
    return { ok: true, skipped: true }
  }
  return { ok: true }
}

async function runFormImageStage(
  options: StudioFlowAutoPipelineRunOptions
): Promise<{ ok: boolean; message?: string; skipped?: boolean }> {
  const { projectId, episodeId } = options
  const missingFormIds = await collectStudioFlowAllFormIdsMissingImages(projectId, episodeId)
  if (!missingFormIds.length) {
    reportProgress(options.onProgress, 'form-image', '形态图已就绪，跳过出图')
    return { ok: true, skipped: true }
  }

  const store = useCreationStore.getState()
  const projectType = store.currentProjectType === 'series' ? 'series' : 'movie'
  const tabs: StudioFlowRpsAssetType[] = ['scene', 'character', 'prop']
  let ranAny = false

  for (const tab of tabs) {
    if (options.shouldCancel()) return { ok: false, message: '已取消' }
    const tabFormIds = await collectStudioFlowFormIdsMissingImages(projectId, episodeId, tab)
    if (!tabFormIds.length) continue

    const label = tab === 'scene' ? '场景' : tab === 'character' ? '角色' : '道具'
    reportProgress(
      options.onProgress,
      'form-image',
      `正在为 ${tabFormIds.length} 个${label}形态生成参考图…`
    )

    const fields = await resolveProjectGenImageSubmitFields(
      store.currentProjectId,
      FORM_IMAGE_SCENE_CODE_BY_TYPE[tab]
    )
    if (!fields.agentCode) {
      return { ok: false, message: `请先在生成配置中为「${label}图」配置智能体` }
    }

    const submit = await userAssetExtractFormGenerateImage({
      formIds: tabFormIds,
      agentCode: fields.agentCode,
      ...(fields.modelCode ? { modelCode: fields.modelCode } : {}),
      ...(fields.resolution ? { resolution: fields.resolution } : {}),
      ...(fields.aspectRatio ? { aspectRatio: fields.aspectRatio } : {})
    })
    const taskId = extractFormGenerateImageSubmitTaskId(submit)
    if (!taskId) return { ok: false, message: '形态图任务提交失败' }

    ranAny = true
    seedStudioFlowTaskFormIdTargets({
      projectId,
      projectType,
      episodeId,
      taskId,
      formIds: tabFormIds,
      taskType: 'FORM_IMAGE_BATCH'
    })
    markStudioFlowRpsNodesGeneratingByFormIds(tabFormIds)
    await options.onTaskSubmitted?.('form-image')

    const outcome = await waitStudioFlowTaskStreamDone(taskId, (snapshot) => {
      reportProgress(
        options.onProgress,
        'form-image',
        snapshot.message || snapshot.stepTitle || `${label}形态图生成中…`,
        snapshot.progress
      )
    })
    if (!outcome.ok) return { ok: false, message: outcome.message || `${label}形态图生成失败` }
  }

  if (!ranAny) {
    reportProgress(options.onProgress, 'form-image', '形态图已就绪，跳过')
    return { ok: true, skipped: true }
  }
  return { ok: true }
}

async function runStoryboardScriptStage(
  options: StudioFlowAutoPipelineRunOptions
): Promise<{ ok: boolean; message?: string; skipped?: boolean }> {
  const store = useCreationStore.getState()
  const panels = store.formData.storyboardScript?.panels ?? []
  reportProgress(
    options.onProgress,
    'storyboard-script',
    panels.length ? '正在保留已有分镜并补充新的分镜脚本…' : '正在生成分镜脚本…'
  )
  const result = await options.runStoryboardScriptBatch(panels)
  if (!result.ok) {
    maybeOpenStudioFlowRechargeFromMessage(result.message)
    return { ok: false, message: result.message || '分镜脚本生成失败' }
  }
  store.updateFormData({ storyboardScript: { panels: result.panels } })
  return { ok: true }
}

async function runStoryboardImageStage(
  options: StudioFlowAutoPipelineRunOptions
): Promise<{ ok: boolean; message?: string; skipped?: boolean }> {
  const store = useCreationStore.getState()
  const creationMode = store.formData.globalSetting?.creationMode
  if (skipsStoryboardImageGeneration(creationMode)) {
    reportProgress(options.onProgress, 'storyboard-image', '当前模式无需分镜图，跳过出图')
    return { ok: true, skipped: true }
  }
  const panels = store.formData.storyboardScript?.panels ?? []
  if (!panels.length) {
    reportProgress(options.onProgress, 'storyboard-image', '暂无分镜，跳过出图')
    return { ok: true, skipped: true }
  }
  if (panels.every((panel) => Boolean(resolveStoryboardPanelCoverImage(panel)))) {
    reportProgress(options.onProgress, 'storyboard-image', '分镜图已就绪，保留现有节点并跳过')
    return { ok: true, skipped: true }
  }
  reportProgress(options.onProgress, 'storyboard-image', `正在为 ${panels.length} 个分镜生成图片…`)
  const result = await options.runStoryboardImageBatch(panels)
  if (!result.ok) {
    maybeOpenStudioFlowRechargeFromMessage(result.message)
    return { ok: false, message: result.message || '分镜图生成失败' }
  }
  store.updateFormData({ storyboardScript: { panels: result.panels } })
  return { ok: true }
}

async function runStoryboardVideoStage(
  options: StudioFlowAutoPipelineRunOptions
): Promise<{ ok: boolean; message?: string; skipped?: boolean }> {
  const store = useCreationStore.getState()
  const scriptPanels = store.formData.storyboardScript?.panels ?? []
  if (!scriptPanels.length) {
    reportProgress(options.onProgress, 'storyboard-video', '暂无分镜，跳过视频')
    return { ok: true, skipped: true }
  }
  const videoPanels = [...(store.formData.storyboardVideo?.panels ?? [])]
  const hasMissingVideo = scriptPanels.some((_, index) => {
    const panel = videoPanels[index]
    return !panel || !panelHasStoryboardVideo(panel)
  })
  if (!hasMissingVideo) {
    reportProgress(options.onProgress, 'storyboard-video', '分镜视频已就绪，保留现有节点并跳过')
    return { ok: true, skipped: true }
  }
  reportProgress(
    options.onProgress,
    'storyboard-video',
    `正在为 ${scriptPanels.length} 个分镜生成视频…`
  )
  const result = await options.runStoryboardVideoBatch({ scriptPanels, videoPanels })
  if (!result.ok) {
    maybeOpenStudioFlowRechargeFromMessage(result.message)
    return { ok: false, message: result.message || '分镜视频生成失败' }
  }
  return { ok: true }
}

async function runDubbingStage(
  options: StudioFlowAutoPipelineRunOptions
): Promise<{ ok: boolean; message?: string; skipped?: boolean }> {
  const store = useCreationStore.getState()
  const scriptPanels = store.formData.storyboardScript?.panels ?? []
  if (!scriptPanels.length) {
    reportProgress(options.onProgress, 'dubbing', '暂无分镜，跳过配音')
    return { ok: true, skipped: true }
  }
  const dubbingPanels = [...(store.formData.dubbing?.panels ?? [])]
  const panelIndices = scriptPanels
    .map((_, index) => index)
    .filter((index) => !studioFlowDubbingPanelCompleted(dubbingPanels[index]))
  if (!panelIndices.length) {
    reportProgress(options.onProgress, 'dubbing', '配音已就绪，保留现有节点并跳过')
    return { ok: true, skipped: true }
  }
  reportProgress(
    options.onProgress,
    'dubbing',
    `正在为 ${panelIndices.length} 个分镜生成配音…`
  )
  const result = await options.runDubbingBatch({
    scriptPanels,
    dubbingPanels,
    panelIndices
  })
  if (!result.ok) {
    maybeOpenStudioFlowRechargeFromMessage(result.message)
    return { ok: false, message: result.message || '配音生成失败' }
  }
  return { ok: true }
}

export function studioFlowDubbingPanelCompleted(panel: DubbingPanel | undefined): boolean {
  if (!panel) return false
  return panel.status === 'done'
    || panel.storyboardDubbingConfirmed === true
    || Boolean(String(panel.dubbingLipSyncVideoUrl || '').trim())
    || Boolean(panel.dubbingGenHistory?.some((item) => String(item.url || '').trim()))
}

export async function runStudioFlowAutoPipeline(
  options: StudioFlowAutoPipelineRunOptions
): Promise<{ ok: boolean; failedStage?: StudioFlowAutoPipelineStage; message?: string }> {
  const stages: Array<{
    id: StudioFlowAutoPipelineStage
    run: () => Promise<{ ok: boolean; message?: string; skipped?: boolean }>
  }> = [
    { id: 'extract', run: () => runExtractStage(options) },
    { id: 'form-text', run: () => runFormTextStage(options) },
    { id: 'form-image', run: () => runFormImageStage(options) },
    { id: 'storyboard-script', run: () => runStoryboardScriptStage(options) },
    { id: 'storyboard-image', run: () => runStoryboardImageStage(options) },
    { id: 'storyboard-video', run: () => runStoryboardVideoStage(options) },
    { id: 'dubbing', run: () => runDubbingStage(options) }
  ]

  const creationMode = useCreationStore.getState().formData.globalSetting?.creationMode
  const allowed = new Set(studioFlowAutoPipelineStages(creationMode))
  const runnable = stages.filter((stage) => allowed.has(stage.id))
  const fromIndex = studioFlowAutoPipelineStartIndex(
    runnable.map((stage) => stage.id),
    options.fromStage
  )
  const stagesToRun = runnable.slice(fromIndex)

  for (const stage of stagesToRun) {
    if (options.shouldCancel()) {
      return { ok: false, failedStage: stage.id, message: '已取消自动创作' }
    }
    reportProgress(options.onProgress, stage.id, STAGE_TITLES[stage.id])
    try {
      const outcome = await stage.run()
      if (!outcome.ok) {
        maybeOpenStudioFlowRechargeFromMessage(outcome.message)
        return { ok: false, failedStage: stage.id, message: outcome.message }
      }
      if (!outcome.skipped) {
        await options.onStageComplete?.(stage.id)
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : '自动创作失败'
      maybeOpenStudioFlowRechargeFromMessage(message)
      return { ok: false, failedStage: stage.id, message }
    }
  }

  reportProgress(options.onProgress, 'done', '剧本已带入，后续节点已自动推进；可在顶栏「交付」菜单导出成片')
  return { ok: true }
}
