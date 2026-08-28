import type {
AssetExtractType,
ProjectGenConfigSaveItem,
ProjectGenConfigVO,
UserModelListItem
} from '~/types/business-api'
import type { AgentOption } from '~/types/modelAgentOptions'
import { userProjectGenConfigGet,userProjectGenConfigSave } from '~/utils/businessApi'
import {
CHARACTER_CARD_IMAGE_AGENT_BIZ_CATEGORY,
EXTRACT_PARALLEL_AGENT_BIZ_CATEGORY,
FORM_GENERATE_AGENT_BIZ_CATEGORY,
FORM_IMAGE_AGENT_BIZ_CATEGORY,
STORYBOARD_IMAGE_AGENT_BIZ_CATEGORY,
STORYBOARD_SCRIPT_AGENT_BIZ_CATEGORY,
STORYBOARD_STYLIST_MODEL_FUNC_CODE,
STORYBOARD_VIDEO_PROMPT_AGENT_BIZ_CATEGORY,
STORYBOARD_VIDEO_PROMPT_IMAGE_AGENT_BIZ_CATEGORY,
agentOptionsFromGenConfigVo,
pickFirstAgentOption
} from '~/utils/extractAgentBiz'
import {
PROJECT_GEN_CONFIG_SCENE_GROUPS,
type ProjectGenConfigSceneGroup
} from '~/utils/projectGenConfigScenes'

export const STORYBOARD_GEN_CONFIG_SCENE_CODES = {
  script: STORYBOARD_SCRIPT_AGENT_BIZ_CATEGORY,
  stylist: STORYBOARD_STYLIST_MODEL_FUNC_CODE,
  videoPrompt: STORYBOARD_VIDEO_PROMPT_AGENT_BIZ_CATEGORY,
  videoPromptImage: STORYBOARD_VIDEO_PROMPT_IMAGE_AGENT_BIZ_CATEGORY,
  videoPromptGrid: 'main_storyboard_video_prompt_grid',
  image: STORYBOARD_IMAGE_AGENT_BIZ_CATEGORY
} as const

/** 分镜视频提示词场景（按创作模式仅命中其一） */
export const STORYBOARD_VIDEO_PROMPT_SCENE_CODES = [
  STORYBOARD_VIDEO_PROMPT_AGENT_BIZ_CATEGORY,
  STORYBOARD_VIDEO_PROMPT_IMAGE_AGENT_BIZ_CATEGORY,
  'main_storyboard_video_prompt_grid'
] as const

export interface ProjectGenScenePickerData {
  sceneCode: string
  agents: AgentOption[]
  availableModels: UserModelListItem[]
  agentCode: string
  modelCode: string
}

/** 从 gen-config/get 单场景 VO 解析智能体/模型池（供二次选择弹窗使用） */
export function projectGenScenePickerDataFromVo(vo: ProjectGenConfigVO): ProjectGenScenePickerData {
  return {
    sceneCode: String(vo.sceneCode || '').trim(),
    agents: agentOptionsFromGenConfigVo(vo),
    availableModels: Array.isArray(vo.availableModels) ? vo.availableModels : [],
    agentCode: String(vo.agentCode || '').trim(),
    modelCode: String(vo.modelCode || '').trim()
  }
}

/** 按 sceneCode 取 gen-config 智能体/模型池 */
export async function getProjectGenScenePickerData(
  projectId: number,
  sceneCode: string
): Promise<ProjectGenScenePickerData | null> {
  const cfg = await getProjectGenConfigBySceneCode(projectId, sceneCode)
  if (!cfg) return null
  return projectGenScenePickerDataFromVo(cfg)
}

/** 取当前创作模式下适用的分镜视频提示词场景配置 */
export async function getProjectGenVideoPromptPickerData(
  projectId: number
): Promise<ProjectGenScenePickerData | null> {
  const list = await fetchProjectGenConfigList(projectId)
  const codes = new Set<string>(STORYBOARD_VIDEO_PROMPT_SCENE_CODES)
  const hit = list.find((row) => codes.has(String(row.sceneCode || '').trim()))
  return hit ? projectGenScenePickerDataFromVo(hit) : null
}

export const CHARACTER_CARD_SCENE_CODE = CHARACTER_CARD_IMAGE_AGENT_BIZ_CATEGORY

/** 资产提取 sceneCode */
export const EXTRACT_SCENE_CODE_BY_TYPE: Record<AssetExtractType, string> = {
  ...EXTRACT_PARALLEL_AGENT_BIZ_CATEGORY
}

/** 形态文案 sceneCode */
export const FORM_GENERATE_SCENE_CODE_BY_TYPE: Record<AssetExtractType, string> = {
  ...FORM_GENERATE_AGENT_BIZ_CATEGORY
}

/** 形态图 sceneCode */
export const FORM_IMAGE_SCENE_CODE_BY_TYPE: Record<AssetExtractType, string> = {
  ...FORM_IMAGE_AGENT_BIZ_CATEGORY
}

type ProjectGenConfigCacheEntry = {
  at: number
  list: ProjectGenConfigVO[]
  bySceneCode: Map<string, ProjectGenConfigVO>
}

const CACHE_TTL_MS = 30_000
const cache = new Map<string, ProjectGenConfigCacheEntry>()

function projectGenConfigCacheKey(projectId: number, episodeId?: number | null): string {
  const pid = Number(projectId)
  const eid = Number(episodeId)
  if (Number.isFinite(eid) && eid > 0) return `${pid}::${eid}`
  return String(pid)
}

/** 从 gen-config/get 单项解析 sceneCode（兼容 snake_case） */
export function resolveProjectGenConfigSceneCode(
  vo: Pick<ProjectGenConfigVO, 'sceneCode'> & { scene_code?: string | null }
): string {
  return String(vo.sceneCode ?? vo.scene_code ?? '').trim()
}

/** 按接口返回的 sceneCode 集合，过滤出应展示的分组列表 */
export function buildProjectGenConfigVisibleGroups(
  apiSceneCodes: Iterable<string>
): ProjectGenConfigSceneGroup[] {
  const codes = new Set(
    Array.from(apiSceneCodes)
      .map((code) => String(code || '').trim())
      .filter(Boolean)
  )
  if (!codes.size) return PROJECT_GEN_CONFIG_SCENE_GROUPS

  return PROJECT_GEN_CONFIG_SCENE_GROUPS.map((group) => ({
    ...group,
    scenes: group.scenes.filter((scene) => codes.has(String(scene.sceneCode)))
  })).filter((group) => group.scenes.length > 0)
}

function indexBySceneCode(list: ProjectGenConfigVO[]): Map<string, ProjectGenConfigVO> {
  const map = new Map<string, ProjectGenConfigVO>()
  for (const row of list) {
    const code = resolveProjectGenConfigSceneCode(row)
    if (code) map.set(code, row)
  }
  return map
}

/** 清除项目配置缓存（切换作品或保存后调用） */
export function clearProjectGenConfigCache(projectId?: number) {
  if (projectId == null) {
    cache.clear()
    return
  }
  const prefix = `${Number(projectId)}::`
  for (const key of [...cache.keys()]) {
    if (key === String(projectId) || key.startsWith(prefix)) cache.delete(key)
  }
}

/** 拉取项目 16 项生成配置（带短时缓存） */
export async function fetchProjectGenConfigList(
  projectId: number,
  options?: { episodeId?: number | null; force?: boolean }
): Promise<ProjectGenConfigVO[]> {
  const pid = Number(projectId)
  if (!Number.isFinite(pid) || pid <= 0) return []

  const episodeId = Number(options?.episodeId)
  const cacheKey = projectGenConfigCacheKey(pid, episodeId)
  const now = Date.now()
  const hit = options?.force ? undefined : cache.get(cacheKey)
  if (hit && now - hit.at < CACHE_TTL_MS) return hit.list

  const list = await userProjectGenConfigGet({
    projectId: pid,
    ...(Number.isFinite(episodeId) && episodeId > 0 ? { episodeId } : {})
  })
  cache.set(cacheKey, { at: now, list, bySceneCode: indexBySceneCode(list) })
  return list
}

/** 按 sceneCode 取单场景配置（含 availableModels 模型池） */
export async function getProjectGenConfigBySceneCode(
  projectId: number,
  sceneCode: string
): Promise<ProjectGenConfigVO | null> {
  const pid = Number(projectId)
  const code = String(sceneCode || '').trim()
  if (!Number.isFinite(pid) || pid <= 0 || !code) return null

  const now = Date.now()
  const cacheKey = projectGenConfigCacheKey(pid, undefined)
  let entry = cache.get(cacheKey)
  if (!entry || now - entry.at >= CACHE_TTL_MS) {
    const list = await fetchProjectGenConfigList(pid)
    entry = cache.get(cacheKey) ?? { at: now, list, bySceneCode: indexBySceneCode(list) }
  }
  return entry.bySceneCode.get(code) ?? null
}

/** 保存部分场景配置 */
export async function saveProjectGenConfigItems(
  projectId: number,
  configs: ProjectGenConfigSaveItem[]
): Promise<ProjectGenConfigVO[]> {
  const pid = Number(projectId)
  if (!Number.isFinite(pid) || pid <= 0) throw new Error('项目不能空')
  if (!configs.length) throw new Error('配置不能空')

  await userProjectGenConfigSave({ projectId: pid, configs })
  clearProjectGenConfigCache(pid)
  return fetchProjectGenConfigList(pid)
}

/** 保存单场景（供各步骤「生成设置」弹窗同步项目配置） */
export async function saveSingleProjectGenConfigScene(
  projectId: number,
  sceneCode: string,
  agentCode: string,
  modelCode: string,
  imageParams?: { resolution?: string; aspectRatio?: string }
): Promise<void> {
  const agent = String(agentCode || '').trim()
  const model = String(modelCode || '').trim()
  if (!agent || !model) return
  const item: ProjectGenConfigSaveItem = {
    sceneCode,
    agentCode: agent,
    modelCode: model,
    ...(imageParams?.resolution ? { resolution: imageParams.resolution } : {}),
    ...(imageParams?.aspectRatio ? { aspectRatio: imageParams.aspectRatio } : {})
  }
  await saveProjectGenConfigItems(projectId, [item])
}

/** 从配置解析智能体编码；无配置时返回空串 */
export async function resolveProjectGenAgentCode(
  projectId: number | null | undefined,
  sceneCode: string
): Promise<string> {
  const pid = Number(projectId)
  if (!Number.isFinite(pid) || pid <= 0) return ''
  const cfg = await getProjectGenConfigBySceneCode(pid, sceneCode)
  return String(cfg?.agentCode || '').trim()
}

/** 从配置解析模型编码 */
export async function resolveProjectGenModelCode(
  projectId: number | null | undefined,
  sceneCode: string
): Promise<string> {
  const pid = Number(projectId)
  if (!Number.isFinite(pid) || pid <= 0) return ''
  const cfg = await getProjectGenConfigBySceneCode(pid, sceneCode)
  return String(cfg?.modelCode || '').trim()
}

/** 图片场景：解析清晰度 + 比例 */
export async function resolveProjectGenImageParams(
  projectId: number | null | undefined,
  sceneCode: string
): Promise<{ modelCode: string; resolution: string; aspectRatio: string }> {
  const pid = Number(projectId)
  if (!Number.isFinite(pid) || pid <= 0) {
    return { modelCode: '', resolution: '', aspectRatio: '' }
  }
  const cfg = await getProjectGenConfigBySceneCode(pid, sceneCode)
  return {
    modelCode: String(cfg?.modelCode || '').trim(),
    resolution: String(cfg?.resolution || '').trim(),
    aspectRatio: String(cfg?.aspectRatio || '').trim()
  }
}

export type ProjectGenFieldOverrides = {
  agentCode?: string | null
  modelCode?: string | null
  resolution?: string | null
  aspectRatio?: string | null
}

/**
 * 与「生成配置」弹窗一致：优先已保存 modelCode，否则智能体默认模型，再否则模型池最高优先级。
 * 避免 gen-config/get 未持久化时 UI 显示 Agnes 但提交仍走 aid 兜底 gpt-5.4。
 */
export function pickProjectGenModelCodeFromVo(
  vo: Pick<ProjectGenConfigVO, 'modelCode' | 'agentCode' | 'availableModels'> | null | undefined,
  agentCode: string,
  agents: AgentOption[]
): string {
  const availableModels = Array.isArray(vo?.availableModels) ? vo.availableModels : []
  const saved = String(vo?.modelCode || '').trim()
  if (
    saved &&
    (!availableModels.length ||
      availableModels.some((m) => String(m.modelCode || '').trim() === saved))
  ) {
    return saved
  }

  const code = String(agentCode || vo?.agentCode || '').trim()
  const agent = agents.find((a) => a.id === code)
  const agentDefault = String(agent?.defaultModelCode || '').trim()
  if (
    agentDefault &&
    (!availableModels.length ||
      availableModels.some((m) => String(m.modelCode || '').trim() === agentDefault))
  ) {
    return agentDefault
  }

  const sorted = [...availableModels].sort(
    (a, b) => Number(b.priority ?? 0) - Number(a.priority ?? 0)
  )
  return String(sorted[0]?.modelCode || '').trim()
}

function resolveProjectGenLlmSubmitFieldsFromVo(
  cfg: ProjectGenConfigVO | null,
  overrides?: Pick<ProjectGenFieldOverrides, 'agentCode' | 'modelCode'>
): { agentCode: string; modelCode?: string } {
  const agents = cfg ? agentOptionsFromGenConfigVo(cfg) : []
  let agentCode =
    String(overrides?.agentCode ?? '').trim() || String(cfg?.agentCode ?? '').trim()
  if (!agentCode) {
    const first = pickFirstAgentOption(agents)
    if (first?.id) agentCode = first.id
  }

  const manualModel = String(overrides?.modelCode ?? '').trim()
  const modelCode =
    manualModel || pickProjectGenModelCodeFromVo(cfg, agentCode, agents)

  return {
    agentCode,
    ...(modelCode ? { modelCode } : {})
  }
}

async function fetchProjectGenConfigVoBySceneCode(
  projectId: number,
  sceneCode: string,
  options?: { force?: boolean }
): Promise<ProjectGenConfigVO | null> {
  const pid = Number(projectId)
  const code = String(sceneCode || '').trim()
  if (!Number.isFinite(pid) || pid <= 0 || !code) return null

  if (options?.force) {
    const list = await fetchProjectGenConfigList(pid, { force: true })
    return list.find((row) => resolveProjectGenConfigSceneCode(row) === code) ?? null
  }
  return getProjectGenConfigBySceneCode(pid, code)
}

/** 文字类场景：手动值优先，否则取项目生成配置（含默认模型推导） */
export async function resolveProjectGenLlmSubmitFields(
  projectId: number | null | undefined,
  sceneCode: string,
  overrides?: Pick<ProjectGenFieldOverrides, 'agentCode' | 'modelCode'>,
  options?: { force?: boolean }
): Promise<{ agentCode: string; modelCode?: string }> {
  const pid = Number(projectId)
  const cfg =
    Number.isFinite(pid) && pid > 0
      ? await fetchProjectGenConfigVoBySceneCode(pid, sceneCode, options)
      : null
  return resolveProjectGenLlmSubmitFieldsFromVo(cfg, overrides)
}

/** 图片类场景：手动值优先，否则取项目生成配置 */
export async function resolveProjectGenImageSubmitFields(
  projectId: number | null | undefined,
  sceneCode: string,
  overrides?: ProjectGenFieldOverrides
): Promise<{
  agentCode: string
  modelCode?: string
  resolution?: string
  aspectRatio?: string
}> {
  const pid = Number(projectId)
  const cfg =
    Number.isFinite(pid) && pid > 0
      ? await getProjectGenConfigBySceneCode(pid, sceneCode)
      : null

  const agentCode =
    String(overrides?.agentCode ?? '').trim() || String(cfg?.agentCode ?? '').trim()
  const modelCode =
    String(overrides?.modelCode ?? '').trim() || String(cfg?.modelCode ?? '').trim()
  const resolution =
    String(overrides?.resolution ?? '').trim() || String(cfg?.resolution ?? '').trim()
  const aspectRatio =
    String(overrides?.aspectRatio ?? '').trim() || String(cfg?.aspectRatio ?? '').trim()

  return {
    agentCode,
    ...(modelCode ? { modelCode } : {}),
    ...(resolution ? { resolution } : {}),
    ...(aspectRatio ? { aspectRatio } : {})
  }
}

/**
 * 分镜 LLM 批量/自动：手动在弹窗切换时传 manualPick=true 用手动值；
 * 否则显式传项目生成配置（不再依赖后端隐式兜底）。
 */
export async function resolveStoryboardGenConfigLlmFields(
  projectId: number | null | undefined,
  sceneCode: string,
  manualPick: boolean,
  manualAgentCode: string,
  manualModelCode: string
): Promise<{ agentCode?: string; modelCode?: string }> {
  if (manualPick) {
    const agentCode = String(manualAgentCode || '').trim()
    const modelCode = String(manualModelCode || '').trim()
    return {
      ...(agentCode ? { agentCode } : {}),
      ...(modelCode ? { modelCode } : {})
    }
  }
  const fields = await resolveProjectGenLlmSubmitFields(projectId, sceneCode, undefined, {
    force: true
  })
  if (!fields.agentCode) return {}
  return {
    agentCode: fields.agentCode,
    ...(fields.modelCode ? { modelCode: fields.modelCode } : {})
  }
}

/**
 * 并行提取提交：以项目生成配置为准解析 agentCode/modelCode；
 * manualModelCodes 仅传入用户在提取弹窗内手动切换过的模型（勿传 Pinia 持久化缓存）。
 */
export async function buildParallelExtractSubmitPayload(
  projectId: number,
  types: AssetExtractType[],
  manualModelCodes: Partial<Record<AssetExtractType, string>> = {}
): Promise<{
  agentCodes: Record<string, string>
  modelCodes?: Partial<Record<AssetExtractType, string>>
}> {
  const pid = Number(projectId)
  if (!Number.isFinite(pid) || pid <= 0) {
    throw new Error('缺少项目信息，无法读取生成配置')
  }

  const agentCodes: Record<string, string> = {}
  const modelCodesOut: Partial<Record<AssetExtractType, string>> = {}
  const configList = await fetchProjectGenConfigList(pid, { force: true })

  for (const t of types) {
    const sceneCode = EXTRACT_PARALLEL_AGENT_BIZ_CATEGORY[t]
    const manualModel = String(manualModelCodes[t] || '').trim()
    const cfg =
      configList.find((row) => resolveProjectGenConfigSceneCode(row) === sceneCode) ?? null
    const fields = resolveProjectGenLlmSubmitFieldsFromVo(
      cfg,
      manualModel ? { modelCode: manualModel } : undefined
    )
    if (!fields.agentCode) {
      const label = t === 'scene' ? '场景' : t === 'character' ? '角色' : '道具'
      throw new Error(`请先在「生成配置」中为「${label}提取」配置智能体`)
    }
    agentCodes[t] = fields.agentCode
    if (fields.modelCode) modelCodesOut[t] = fields.modelCode
  }

  return {
    agentCodes,
    modelCodes: Object.keys(modelCodesOut).length ? modelCodesOut : undefined
  }
}
