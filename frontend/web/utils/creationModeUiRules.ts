import type { GlobalSettingData } from '~/types'
import { AI_MODEL_FUNC_CODE } from '~/utils/aiModelFuncCodes'
import {
  STORYBOARD_VIDEO_AGENT_BIZ_CATEGORY,
  STORYBOARD_VIDEO_GRID_AGENT_BIZ_CATEGORY,
  STORYBOARD_VIDEO_IMAGE_AGENT_BIZ_CATEGORY,
  STORYBOARD_VIDEO_PROMPT_AGENT_BIZ_CATEGORY,
  STORYBOARD_VIDEO_PROMPT_GRID_AGENT_BIZ_CATEGORY,
  STORYBOARD_VIDEO_PROMPT_IMAGE_AGENT_BIZ_CATEGORY
} from '~/utils/extractAgentBiz'
import { uniqueTrimmedCodes } from '~/utils/modelListByFuncBatch'

export type StoryboardVideoModalTabKey = 'imageToVideo' | 'multiParam' | 'gridVideo' | 'startEndFrame'

export type CreationModeValue = GlobalSettingData['creationMode']
export type ScriptTypeValue = GlobalSettingData['scriptType']

/** 专业版 / 多参数：分镜步骤无需分镜图，仅脚本 */
export const PRO_MODE_NO_STORYBOARD_IMAGE_TIP = '该模式下无须生成分镜图'

/** 分镜设计不走生图流程的创作模式（与专业版 UI 规则同步） */
const CREATION_MODES_SKIP_STORYBOARD_IMAGE = new Set<CreationModeValue>(['pro', 'multi'])

/** 真人解说漫下不可用的创作模式 */
const CREATION_MODES_DISABLED_FOR_MONOLOGUE = new Set<CreationModeValue>([
  'pro',
  'multi',
  'auto_grid'
])

export function normalizeCreationMode(creationMode?: string | null): CreationModeValue {
  const mode = String(creationMode || 'i2v').trim()
  if (mode === 'multi' || mode === 'pro' || mode === 'auto_grid' || mode === 'i2v') {
    return mode
  }
  return 'i2v'
}

export function isProCreationMode(creationMode?: string | null): boolean {
  return normalizeCreationMode(creationMode) === 'pro'
}

/** 专业版 / 多参数：分镜设计仅脚本，不生成/不校验分镜图 */
export function skipsStoryboardImageGeneration(creationMode?: string | null): boolean {
  return CREATION_MODES_SKIP_STORYBOARD_IMAGE.has(normalizeCreationMode(creationMode))
}

export interface StoryboardDesignProgressCounts {
  completed: number
  total: number
}

/**
 * 分镜设计、视频生成与音画同步的完成进度：需要分镜图的模式按实际结果统计；
 * 专业版 / 多参数只要求分镜脚本，因此同步出的条目直接按全部完成展示。
 */
export function resolveStoryboardDesignProgressCounts(
  creationMode: string | null | undefined,
  storyboardCount: number,
  actualCompletedCount: number
): StoryboardDesignProgressCounts {
  const total = Math.max(0, Math.trunc(Number(storyboardCount) || 0))
  if (skipsStoryboardImageGeneration(creationMode)) {
    return { completed: total, total }
  }
  const completed = Math.min(total, Math.max(0, Math.trunc(Number(actualCompletedCount) || 0)))
  return { completed, total }
}

/** 剧本类型为真人解说漫时，禁用专业版 / 多参数 / 自动宫格 */
export function isMonologueScriptType(scriptType?: string | null): boolean {
  const t = String(scriptType || '').trim()
  return t === 'monologue' || t === 'live-commentary'
}

export function isCreationModeDisabledForScriptType(
  creationMode?: string | null,
  scriptType?: string | null
): boolean {
  if (!isMonologueScriptType(scriptType)) return false
  return CREATION_MODES_DISABLED_FOR_MONOLOGUE.has(normalizeCreationMode(creationMode))
}

/** 解说漫下若当前创作模式不可用，回退到图生视频 */
export function resolveCreationModeForScriptType(
  creationMode?: string | null,
  scriptType?: string | null
): CreationModeValue {
  const mode = normalizeCreationMode(creationMode)
  if (isCreationModeDisabledForScriptType(mode, scriptType)) return 'i2v'
  return mode
}

/** 创作模式 → 编辑分镜视频弹窗默认主 Tab（不含首尾帧） */
export function resolvePrimaryStoryboardVideoTab(
  creationMode?: string | null
): Exclude<StoryboardVideoModalTabKey, 'startEndFrame'> {
  const mode = normalizeCreationMode(creationMode)
  if (mode === 'multi' || mode === 'pro') return 'multiParam'
  if (mode === 'auto_grid') return 'gridVideo'
  return 'imageToVideo'
}

export function showStoryboardImageToVideoTab(creationMode?: string | null): boolean {
  return normalizeCreationMode(creationMode) === 'i2v'
}

/**
 * 是否展示/提交视频时长：仅图生视频（i2v）。
 * 多参数 / 专业版 / 自动宫格不展示时长选择，也不传 durationSeconds / genDurationSeconds。
 */
export function shouldPassStoryboardVideoDuration(creationMode?: string | null): boolean {
  return showStoryboardImageToVideoTab(creationMode)
}

export function showStoryboardMultiParamVideoTab(creationMode?: string | null): boolean {
  const mode = normalizeCreationMode(creationMode)
  return mode === 'multi' || mode === 'pro'
}

export function showStoryboardGridVideoTab(creationMode?: string | null): boolean {
  return normalizeCreationMode(creationMode) === 'auto_grid'
}

/** 专业版 / 自动宫格：剧本类型锁定为剧情演绎，真人解说不可用 */
export function isScriptTypeLockedToPlot(creationMode?: string | null): boolean {
  const mode = normalizeCreationMode(creationMode)
  return mode === 'pro' || mode === 'auto_grid'
}

export function isStoryboardVideoTabVisible(
  tab: StoryboardVideoModalTabKey,
  creationMode?: string | null
): boolean {
  if (tab === 'startEndFrame') return true
  if (tab === 'imageToVideo') return showStoryboardImageToVideoTab(creationMode)
  if (tab === 'multiParam') return showStoryboardMultiParamVideoTab(creationMode)
  return showStoryboardGridVideoTab(creationMode)
}

/** 批量生成分镜视频弹窗：按创作模式解析出片模型池 func_code（专业版优先 multi_pro） */
export function resolveBatchStoryboardVideoModelFuncCodes(creationMode?: string | null): string[] {
  const mode = normalizeCreationMode(creationMode)
  const tab = resolvePrimaryStoryboardVideoTab(creationMode)
  if (tab === 'multiParam') {
    if (mode === 'pro') {
      return uniqueTrimmedCodes([
        AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_MULTI_PRO,
        AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO
      ])
    }
    return [AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO]
  }
  if (tab === 'gridVideo') return [AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_GRID]
  return [AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_IMAGE]
}

/** 编辑分镜视频弹窗：按创作模式仅请求当前可见 Tab 对应的出片模型池 */
export function resolveStoryboardVideoModelFuncCodes(creationMode?: string | null): string[] {
  const codes: string[] = []
  if (showStoryboardImageToVideoTab(creationMode)) {
    codes.push(AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_IMAGE)
  }
  if (showStoryboardMultiParamVideoTab(creationMode)) {
    // 专业版优先 multi_pro；与批量生成弹窗、useModelList fallbacks 对齐
    if (normalizeCreationMode(creationMode) === 'pro') {
      codes.push(
        AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_MULTI_PRO,
        AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO
      )
    } else {
      codes.push(AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO)
    }
  }
  if (showStoryboardGridVideoTab(creationMode)) {
    codes.push(AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_GRID)
  }
  // 首尾帧 Tab 任意创作模式均可用
  codes.push(AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_EDGE)
  return uniqueTrimmedCodes(codes)
}

/** 编辑分镜视频弹窗：按创作模式请求主 Tab 智能体（出片智能体优先，用于模型默认选中） */
export function resolveStoryboardVideoAgentBizCategories(creationMode?: string | null): string[] {
  const codes: string[] = []
  if (showStoryboardImageToVideoTab(creationMode)) {
    codes.push(
      STORYBOARD_VIDEO_IMAGE_AGENT_BIZ_CATEGORY,
      STORYBOARD_VIDEO_PROMPT_IMAGE_AGENT_BIZ_CATEGORY
    )
  }
  if (showStoryboardMultiParamVideoTab(creationMode)) {
    codes.push(STORYBOARD_VIDEO_AGENT_BIZ_CATEGORY, STORYBOARD_VIDEO_PROMPT_AGENT_BIZ_CATEGORY)
  }
  if (showStoryboardGridVideoTab(creationMode)) {
    codes.push(
      STORYBOARD_VIDEO_GRID_AGENT_BIZ_CATEGORY,
      STORYBOARD_VIDEO_PROMPT_GRID_AGENT_BIZ_CATEGORY
    )
  }
  return uniqueTrimmedCodes(codes)
}

/** 批量生成分镜视频弹窗：仅主 Tab 对应智能体（出片优先） */
export function resolveBatchStoryboardVideoAgentBizCategories(
  creationMode?: string | null
): string[] {
  const tab = resolvePrimaryStoryboardVideoTab(creationMode)
  if (tab === 'multiParam') {
    return uniqueTrimmedCodes([
      STORYBOARD_VIDEO_AGENT_BIZ_CATEGORY,
      STORYBOARD_VIDEO_PROMPT_AGENT_BIZ_CATEGORY
    ])
  }
  if (tab === 'gridVideo') {
    return uniqueTrimmedCodes([
      STORYBOARD_VIDEO_GRID_AGENT_BIZ_CATEGORY,
      STORYBOARD_VIDEO_PROMPT_GRID_AGENT_BIZ_CATEGORY
    ])
  }
  return uniqueTrimmedCodes([
    STORYBOARD_VIDEO_IMAGE_AGENT_BIZ_CATEGORY,
    STORYBOARD_VIDEO_PROMPT_IMAGE_AGENT_BIZ_CATEGORY
  ])
}
