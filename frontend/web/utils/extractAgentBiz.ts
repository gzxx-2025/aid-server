import type {
AgentInfoVO,
AgentListGroupVO,
AssetExtractType,
ProjectGenConfigVO
} from '~/types/business-api'
import type { AgentOption } from '~/types/modelAgentOptions'
import { aidAgentList } from '~/utils/businessApi'

export {
  emptyExtractModelCodes,
  resolvePreferredModelId,
  resolvePreferredModelIdFromAgentCodes,
  resolveSelectedModelOption
} from './extractAgentModelSelection'

export type ExtractModelCodes = Record<AssetExtractType, string>

/** listByFunc.funcCode，与并行提取 biz_category 一致 */
export const EXTRACT_MODEL_FUNC_CODE: Record<AssetExtractType, string> = {
  scene: 'main_scene_extract',
  character: 'main_character_extract',
  prop: 'main_prop_extract'
}

/** 并行提取弹窗：bizCategoryCodes（与接口文档强映射表一致） */
export const EXTRACT_PARALLEL_AGENT_BIZ_CATEGORY: Record<AssetExtractType, string> = {
  scene: 'main_scene_extract',
  character: 'main_character_extract',
  prop: 'main_prop_extract'
}

/** 批量形态生成（character / scene / prop 均走 LLM，agentCode 按类型强映射） */
export const FORM_GENERATE_AGENT_BIZ_CATEGORY: Record<AssetExtractType, string> = {
  character: 'main_character_form',
  scene: 'main_scene_form',
  prop: 'main_prop_form'
}

/** 批量形态图生成 */
export const FORM_IMAGE_AGENT_BIZ_CATEGORY: Record<AssetExtractType, string> = {
  scene: 'main_scene_image',
  character: 'main_character_image',
  prop: 'main_prop_image'
}

export const CHARACTER_CARD_IMAGE_AGENT_BIZ_CATEGORY = 'main_character_card_image'

/** 分镜脚本生成设置 */
export const STORYBOARD_SCRIPT_AGENT_BIZ_CATEGORY = 'main_storyboard_script'

/** listByFunc：分镜脚本生成设置弹窗模型列表 */
export const STORYBOARD_SCRIPT_MODEL_FUNC_CODE = STORYBOARD_SCRIPT_AGENT_BIZ_CATEGORY

/** 剧情演绎（plot）允许的分镜脚本智能体 */
export const STORYBOARD_SCRIPT_AGENTS_PLOT = [
  'aid_storyboard_script_extractor',
  'aid_storyboard_script_extractor_simple'
] as const

/** 真人解说（monologue）允许的分镜脚本智能体 */
export const STORYBOARD_SCRIPT_AGENTS_MONOLOGUE = [
  'aid_storyboard_script_commentary',
  'aid_storyboard_script_commentary_simple'
] as const

export type StoryboardScriptApiScriptType = 'plot' | 'monologue'

/** 前端 globalSetting.scriptType → 后端 script_type */
export function resolveStoryboardScriptApiScriptType(
  scriptType?: string | null
): StoryboardScriptApiScriptType {
  if (scriptType === 'monologue' || scriptType === 'live-commentary') return 'monologue'
  return 'plot'
}

export function storyboardScriptAgentCodesForScriptType(
  scriptType: StoryboardScriptApiScriptType
): readonly string[] {
  return scriptType === 'monologue'
    ? STORYBOARD_SCRIPT_AGENTS_MONOLOGUE
    : STORYBOARD_SCRIPT_AGENTS_PLOT
}

export function filterStoryboardScriptAgentsByScriptType(
  options: AgentOption[],
  scriptType: StoryboardScriptApiScriptType
): AgentOption[] {
  const allowed = new Set(storyboardScriptAgentCodesForScriptType(scriptType))
  const filtered = options.filter((a) => allowed.has(String(a.id || '').trim()))
  return filtered.length ? filtered : options
}

/** listByFunc / 智能体 biz_category：分镜图脚本（image-prompt），见接口 10.2 */
export const STORYBOARD_STYLIST_MODEL_FUNC_CODE = 'main_storyboard_stylist'

/** @alias STORYBOARD_STYLIST_MODEL_FUNC_CODE */
export const STORYBOARD_IMAGE_PROMPT_AGENT_BIZ_CATEGORY = STORYBOARD_STYLIST_MODEL_FUNC_CODE

/** 分镜视频提示词（video-prompt），见接口 10.5 */
export const STORYBOARD_VIDEO_PROMPT_AGENT_BIZ_CATEGORY = 'main_storyboard_video_prompt'
/** 图生方向分镜视频提示词智能体（aid_visual_director_image） */
export const STORYBOARD_VIDEO_PROMPT_IMAGE_AGENT_BIZ_CATEGORY = 'main_storyboard_video_prompt_image'
/** 宫格方向分镜视频提示词智能体（aid_visual_director_grid） */
export const STORYBOARD_VIDEO_PROMPT_GRID_AGENT_BIZ_CATEGORY = 'main_storyboard_video_prompt_grid'
/** 图生视频出片模型池对应智能体 biz（与 listByFunc main_storyboard_video_image 对齐） */
export const STORYBOARD_VIDEO_IMAGE_AGENT_BIZ_CATEGORY = 'main_storyboard_video_image'
/** 多参生视频出片模型池对应智能体 biz（与 listByFunc main_storyboard_video 对齐） */
export const STORYBOARD_VIDEO_AGENT_BIZ_CATEGORY = 'main_storyboard_video'
/** 宫格生视频出片模型池对应智能体 biz（与 listByFunc main_storyboard_video_grid 对齐） */
export const STORYBOARD_VIDEO_GRID_AGENT_BIZ_CATEGORY = 'main_storyboard_video_grid'

/** 分镜生图 / 编辑弹窗底部模型下拉：智能体 biz_category_code */
export const STORYBOARD_IMAGE_AGENT_BIZ_CATEGORY = 'main_storyboard_image'

/** 多机位单机位：智能体 biz_category_code（与 listByFunc image_multi_view 一致） */
export const IMAGE_MULTI_VIEW_AGENT_BIZ_CATEGORY = 'image_multi_view'
/** 多机位九宫格：智能体 biz_category_code（与 listByFunc image_multi_grid 一致） */
export const IMAGE_MULTI_GRID_AGENT_BIZ_CATEGORY = 'image_multi_grid'

/** 接口文档约定的多机位智能体 agent_code */
export const IMAGE_MULTI_VIEW_AGENT_CODE = 'aid_multi_camera'
export const IMAGE_MULTI_GRID_AGENT_CODE = 'aid_multi_camera_grid'

/** 多参 video-prompt 默认智能体 */
export const STORYBOARD_VIDEO_PROMPT_AGENT_CODE = 'aid_visual_director'
/** 图生 video-prompt-image 默认智能体 */
export const STORYBOARD_VIDEO_PROMPT_IMAGE_AGENT_CODE = 'aid_visual_director_image'
/** 宫格 video-prompt-grid 默认智能体 */
export const STORYBOARD_VIDEO_PROMPT_GRID_AGENT_CODE = 'aid_visual_director_grid'

export type StoryboardVideoPromptSubmitEndpoint =
  | 'video_prompt'
  | 'video_prompt_image'
  | 'video_prompt_grid'

/**
 * 分镜视频提示词提交 agentCode。
 * store.agentId 来自「分镜视频生成设置」（main_storyboard_video_prompt），
 * 仅在与目标接口 biz 一致时传入；否则 omit，由后端走该接口默认智能体。
 */
export function resolveStoryboardVideoPromptSubmitAgentCode(
  endpoint: StoryboardVideoPromptSubmitEndpoint,
  storedAgentId?: string | null
): string {
  const stored = String(storedAgentId ?? '').trim()
  if (!stored) return ''

  if (endpoint === 'video_prompt_image') {
    return stored === STORYBOARD_VIDEO_PROMPT_IMAGE_AGENT_CODE ? stored : ''
  }

  if (endpoint === 'video_prompt_grid') {
    return stored === STORYBOARD_VIDEO_PROMPT_GRID_AGENT_CODE ? stored : ''
  }

  if (
    stored === STORYBOARD_VIDEO_PROMPT_IMAGE_AGENT_CODE ||
    stored === STORYBOARD_VIDEO_PROMPT_GRID_AGENT_CODE
  ) {
    return ''
  }
  return stored
}

/** @deprecated 使用 EXTRACT_PARALLEL_AGENT_BIZ_CATEGORY */
export const EXTRACT_PARALLEL_AGENT_BIZ_TYPE = EXTRACT_PARALLEL_AGENT_BIZ_CATEGORY

/** @deprecated 使用 FORM_GENERATE_AGENT_BIZ_CATEGORY */
export const FORM_GENERATE_AGENT_BIZ_TYPE = FORM_GENERATE_AGENT_BIZ_CATEGORY

/** @deprecated 使用 FORM_IMAGE_AGENT_BIZ_CATEGORY */
export const FORM_IMAGE_AGENT_BIZ_TYPE = FORM_IMAGE_AGENT_BIZ_CATEGORY

export function agentInfoToOption(row: AgentInfoVO): AgentOption {
  const code = String(row.agentCode || '').trim()
  const bizCategoryCode = String(row.bizCategoryCode || '').trim()
  const intro = String(row.introduction || row.subTitle || '').trim()
  const defaultModelCode = String(row.modelCode || '').trim()
  // agent/list → iconUrl；gen-config agentOptions → agentIconUrl
  const thumbnail = String(row.iconUrl || row.agentIconUrl || '').trim()
  return {
    id: code,
    name: String(row.name || code || '未命名智能体'),
    desc: intro,
    thumbnail,
    ...(bizCategoryCode ? { bizCategoryCode } : {}),
    ...(defaultModelCode ? { defaultModelCode } : {})
  }
}

/** /asset/extract/parallel 的 agentCodes 取值：agentCode（AgentOption.id） */
export function resolveParallelExtractAgentCode(
  _extractType: AssetExtractType,
  selected?: Pick<AgentOption, 'id'> | null
): string {
  return String(selected?.id || '').trim()
}

/** @deprecated 使用 resolveParallelExtractAgentCode */
export const resolveParallelExtractBizCategoryCode = resolveParallelExtractAgentCode

export function agentsInGroup(groups: AgentListGroupVO[], bizCategoryCode: string): AgentInfoVO[] {
  const code = bizCategoryCode.trim()
  const hit = groups.find((g) => String(g.bizCategoryCode ?? '').trim() === code)
  return hit?.agents ?? []
}

export function agentOptionsFromGroup(
  groups: AgentListGroupVO[],
  bizCategoryCode: string
): AgentOption[] {
  return agentsInGroup(groups, bizCategoryCode)
    .map((r) => agentInfoToOption(r))
    .filter((a) => a.id)
}

/** 从 gen-config/get 单场景的 agentOptions 转为前端 AgentOption 列表 */
export function agentOptionsFromGenConfigVo(
  vo: Pick<ProjectGenConfigVO, 'agentOptions' | 'agentCode' | 'agentIconUrl'>
): AgentOption[] {
  const sceneIcon = String(vo.agentIconUrl || '').trim()
  const sceneAgentCode = String(vo.agentCode || '').trim()
  return (vo.agentOptions ?? [])
    .map((r) => {
      const opt = agentInfoToOption(r)
      // 场景级 agentIconUrl：回填到当前已选智能体（options 单项可能未带图标）
      if (!opt.thumbnail && sceneIcon && opt.id === sceneAgentCode) {
        opt.thumbnail = sceneIcon
      }
      return opt
    })
    .filter((a) => a.id)
}

export function pickFirstAgentOption(options: AgentOption[]): AgentOption | null {
  return options[0] ?? null
}

/** 在指定 biz 分组内按 agentCode 取智能体默认 modelCode；未传或未命中时取分组内首个智能体 */
export function resolveAgentModelCodeInGroup(
  groups: AgentListGroupVO[],
  bizCategoryCode: string,
  agentCode?: string | null
): string {
  const agents = agentsInGroup(groups, bizCategoryCode)
  const code = String(agentCode ?? '').trim()
  if (code) {
    const hit = agents.find((a) => String(a.agentCode || '').trim() === code)
    if (hit) return String(hit.modelCode || '').trim()
  }
  return String(agents[0]?.modelCode || '').trim()
}

/** @deprecated 仅兼容旧调用；请使用 resolveAgentModelCodeInGroup / fetchAgentDefaultModelCode */
export function resolveAgentDefaultModelCode(
  groups: AgentListGroupVO[],
  bizCategoryCode: string
): string {
  const agents = agentsInGroup(groups, bizCategoryCode)
  return String(agents[0]?.modelCode || '').trim()
}

const agentDefaultModelCache = new Map<string, Promise<string>>()

function agentDefaultModelCacheKey(bizCategoryCode: string, agentCode?: string | null): string {
  return `${bizCategoryCode}::${String(agentCode ?? '').trim()}`
}

function scopedAgentDefaultModelCacheKey(payload: {
  bizCategoryCode: string
  agentCode?: string | null
  projectId?: number
  episodeId?: number
}): string {
  const scopeKey = `${payload.projectId ?? ''}:${payload.episodeId ?? ''}`
  return `${agentDefaultModelCacheKey(payload.bizCategoryCode, payload.agentCode)}|${scopeKey}`
}

/** 将批量 agent/list 结果写入缓存并返回各 payload 对应的 modelCode */
export function cacheAgentDefaultModelCodes(
  groups: AgentListGroupVO[],
  payloads: Array<{
    bizCategoryCode: string
    agentCode?: string | null
    projectId?: number
    episodeId?: number
  }>
): Record<string, string> {
  const out: Record<string, string> = {}
  for (const payload of payloads) {
    const bizCategoryCode = String(payload.bizCategoryCode || '').trim()
    if (!bizCategoryCode) continue
    const cacheKey = scopedAgentDefaultModelCacheKey(payload)
    const code = resolveAgentModelCodeInGroup(groups, bizCategoryCode, payload.agentCode)
    out[cacheKey] = code
    agentDefaultModelCache.set(cacheKey, Promise.resolve(code))
  }
  return out
}

export function getAgentDefaultModelCacheKey(
  bizCategoryCode: string,
  agentCode?: string | null,
  scope?: { projectId?: number; episodeId?: number }
): string {
  return scopedAgentDefaultModelCacheKey({
    bizCategoryCode,
    agentCode,
    projectId: scope?.projectId,
    episodeId: scope?.episodeId
  })
}

export type AidAgentListScope = { projectId?: number; episodeId?: number }

/** 拉取指定 biz 分类下某智能体（agentCode）的默认 modelCode */
export async function fetchAgentDefaultModelCode(payload: {
  bizCategoryCode: string
  agentCode?: string | null
  projectId?: number
  episodeId?: number
}): Promise<string> {
  const bizCategoryCode = String(payload.bizCategoryCode || '').trim()
  if (!bizCategoryCode) return ''

  const agentCode = String(payload.agentCode ?? '').trim()
  const cacheKey = scopedAgentDefaultModelCacheKey({
    bizCategoryCode,
    agentCode,
    projectId: payload.projectId,
    episodeId: payload.episodeId
  })
  const cached = agentDefaultModelCache.get(cacheKey)
  if (cached) return cached

  const pending = (async () => {
    try {
      const groups = await aidAgentList({
        bizCategoryCodes: [bizCategoryCode],
        ...(payload.projectId != null && Number(payload.projectId) > 0
          ? {
              projectId: Number(payload.projectId),
              ...(payload.episodeId != null ? { episodeId: Number(payload.episodeId) } : {})
            }
          : {})
      })
      return resolveAgentModelCodeInGroup(groups, bizCategoryCode, agentCode)
    } catch {
      return ''
    }
  })()

  agentDefaultModelCache.set(cacheKey, pending)
  return pending
}

/** 一次 agent/list 拉取多个 biz 的默认 modelCode */
export async function fetchAgentDefaultModelCodes(
  payloads: Array<{
    bizCategoryCode: string
    agentCode?: string | null
    projectId?: number
    episodeId?: number
  }>
): Promise<Record<string, string>> {
  const uncached: typeof payloads = []
  const result: Record<string, string> = {}

  for (const payload of payloads) {
    const bizCategoryCode = String(payload.bizCategoryCode || '').trim()
    if (!bizCategoryCode) continue
    const cacheKey = scopedAgentDefaultModelCacheKey(payload)
    const cached = agentDefaultModelCache.get(cacheKey)
    if (cached) {
      result[cacheKey] = await cached
    } else {
      uncached.push(payload)
    }
  }

  if (!uncached.length) return result

  const bizCategoryCodes = [
    ...new Set(uncached.map((p) => String(p.bizCategoryCode || '').trim()).filter(Boolean))
  ]
  const scope = uncached.find((p) => p.projectId != null && Number(p.projectId) > 0)
  try {
    const groups = await aidAgentList({
      bizCategoryCodes,
      ...(scope
        ? {
            projectId: Number(scope.projectId),
            ...(scope.episodeId != null ? { episodeId: Number(scope.episodeId) } : {})
          }
        : {})
    })
    Object.assign(result, cacheAgentDefaultModelCodes(groups, uncached))
  } catch {
    for (const payload of uncached) {
      const cacheKey = scopedAgentDefaultModelCacheKey(payload)
      result[cacheKey] = ''
      agentDefaultModelCache.set(cacheKey, Promise.resolve(''))
    }
  }
  return result
}

export function clearAgentDefaultModelCache() {
  agentDefaultModelCache.clear()
}

/** @deprecated 请使用 fetchAgentDefaultModelCode({ bizCategoryCode: STORYBOARD_IMAGE_AGENT_BIZ_CATEGORY, agentCode }) */
export async function fetchStoryboardImageAgentDefaultModelCode(agentCode?: string | null): Promise<string> {
  return fetchAgentDefaultModelCode({
    bizCategoryCode: STORYBOARD_IMAGE_AGENT_BIZ_CATEGORY,
    agentCode
  })
}

export function clearStoryboardImageAgentDefaultModelCache() {
  clearAgentDefaultModelCache()
}

/** 形态图生图：仅当 modelCode 属于该类型 image 模型池时才提交（避免 text 模型如 claude 污染 generate-image） */
export async function resolveFormImageModelCodeForTab(
  tab: AssetExtractType,
  rawCode: string | undefined,
  listByFunc: (funcCode: string) => Promise<Array<{ id?: number | string; modelCode?: string | null }>>
): Promise<string | undefined> {
  const code = String(rawCode || '').trim()
  if (!code) return undefined

  const funcCodes = [FORM_IMAGE_AGENT_BIZ_CATEGORY[tab], EXTRACT_MODEL_FUNC_CODE[tab]]
  for (const funcCode of funcCodes) {
    const trimmed = String(funcCode || '').trim()
    if (!trimmed) continue
    try {
      const list = await listByFunc(trimmed)
      const hit = list.find(
        (row) =>
          String(row.modelCode || '').trim() === code || String(row.id ?? '').trim() === code
      )
      if (hit?.modelCode) return String(hit.modelCode).trim()
    } catch {
      /* try next funcCode */
    }
  }
  return undefined
}
