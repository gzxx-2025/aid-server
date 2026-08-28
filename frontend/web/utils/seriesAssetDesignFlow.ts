import { message } from 'antd'
import {
fetchUserTaskDetailOnce,
waitUserTaskSseTerminal
} from '~/composables/useTaskSseFollow'
import type {
AssetExtractType,
UserAssetExtractFormGenerateImageData,
UserAssetRpsRow
} from '~/types/business-api'
import {
userAssetExtractFormGenerateImage,
userAssetRpsCreate,
userAssetRpsFormCreate,
userAssetRpsFormImageCreate,
userAssetRpsFormImageList,
userAssetRpsUpdateMain
} from '~/utils/businessApi'
import { setFormImageInUse } from '~/utils/formImageAutoUse'
import {
FORM_IMAGE_SCENE_CODE_BY_TYPE,
getProjectGenConfigBySceneCode,
resolveProjectGenImageSubmitFields
} from '~/utils/projectGenConfig'

export type SeriesAssetDesignType = Extract<AssetExtractType, 'character' | 'prop'>

export interface SeriesCharacterDesignForm {
  name: string
  ageRange: string
  gender: string
  introduction: string
  backgroundStory: string
}

export interface SeriesPropDesignForm {
  name: string
  summary: string
  introduction: string
}

export type SeriesAssetDesignForm = SeriesCharacterDesignForm | SeriesPropDesignForm

export interface SeriesAssetDraftState {
  assetId: number
  formId: number
  imageId?: number | null
  imageUrl?: string | null
}

function parseTaskId(raw: unknown): number | null {
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

function extractFormGenerateImageSubmitTaskId(
  submit: UserAssetExtractFormGenerateImageData
): number | null {
  const tid = parseTaskId(submit.taskId)
  if (tid != null) return tid
  const oneTask = submit.tasks?.length === 1 ? submit.tasks[0] : undefined
  const t2 = parseTaskId(oneTask?.taskId)
  if (t2 != null) return t2
  if (submit.taskIds?.length === 1) return parseTaskId(submit.taskIds[0])
  return null
}

export function resolveCreatedFormImageId(created: {
  imgId?: number | null
  id?: number | null
}): number | null {
  const imgId = created?.imgId ?? created?.id
  if (imgId != null && Number.isFinite(Number(imgId))) return Number(imgId)
  return null
}

function buildCharacterUpdatePayload(form: SeriesCharacterDesignForm) {
  return {
    name: form.name.trim(),
    gender: form.gender.trim(),
    ageRange: form.ageRange.trim(),
    introduction: form.introduction.trim(),
    archetype: form.backgroundStory.trim() || undefined
  }
}

function buildPropUpdatePayload(form: SeriesPropDesignForm) {
  return {
    name: form.name.trim(),
    summary: form.summary.trim(),
    introduction: form.introduction.trim()
  }
}

export function validateSeriesAssetDesignForm(
  assetType: SeriesAssetDesignType,
  form: SeriesAssetDesignForm
): string | null {
  if (assetType === 'character') {
    const f = form as SeriesCharacterDesignForm
    if (!f.name.trim()) return '请填写名称'
    if (!f.gender.trim()) return '请选择性别'
    if (!f.ageRange.trim()) return '请选择年龄'
    if (!f.introduction.trim()) return '请填写人物描述'
    return null
  }
  const f = form as SeriesPropDesignForm
  if (!f.name.trim()) return '请填写名称'
  if (!f.summary.trim()) return '请填写道具概要'
  if (!f.introduction.trim()) return '请填写道具描述'
  return null
}

async function resolveImageSubmitFields(projectId: number, assetType: SeriesAssetDesignType) {
  const tab: AssetExtractType = assetType
  const sceneCode = FORM_IMAGE_SCENE_CODE_BY_TYPE[tab]
  const fields = await resolveProjectGenImageSubmitFields(projectId, sceneCode)
  if (!fields.agentCode) {
    const label = assetType === 'character' ? '角色' : '道具'
    throw new Error(`请先在「生成配置」中为「${label}图」配置智能体`)
  }
  return fields
}

export async function resolveSeriesAssetImageCost(
  projectId: number,
  assetType: SeriesAssetDesignType
): Promise<number | null> {
  const tab: AssetExtractType = assetType
  const sceneCode = FORM_IMAGE_SCENE_CODE_BY_TYPE[tab]
  const cfg = await getProjectGenConfigBySceneCode(projectId, sceneCode)
  if (!cfg) return null
  const modelCode = String(cfg.modelCode || '').trim()
  const models = Array.isArray(cfg.availableModels) ? cfg.availableModels : []
  const hit = models.find((m) => String(m.modelCode || '').trim() === modelCode)
  const cost = Number(hit?.costCredits)
  return Number.isFinite(cost) && cost > 0 ? cost : null
}

async function ensureDefaultForm(
  projectId: number,
  episodeId: number,
  assetId: number,
  row: UserAssetRpsRow
): Promise<number> {
  const existing = (row.forms ?? []).find((f) => f?.id != null && Number.isFinite(Number(f.id)))
  if (existing?.id != null) return Number(existing.id)
  const created = await userAssetRpsFormCreate({
    projectId,
    episodeId,
    assetId,
    imageUrl: '',
    name: '形态1: 未命名',
    sourceType: 'official'
  })
  const form = (created.forms ?? []).find((f) => f?.id != null && Number.isFinite(Number(f.id)))
  if (form?.id != null) return Number(form.id)
  throw new Error('形态初始化失败')
}

export async function ensureSeriesAssetDraft(payload: {
  projectId: number
  episodeId: number
  assetType: SeriesAssetDesignType
  form: SeriesAssetDesignForm
  existing?: SeriesAssetDraftState | null
}): Promise<SeriesAssetDraftState> {
  if (payload.existing?.assetId && payload.existing?.formId) {
    await userAssetRpsUpdateMain(
      {
        id: payload.existing.assetId,
        ...(payload.assetType === 'character'
          ? buildCharacterUpdatePayload(payload.form as SeriesCharacterDesignForm)
          : buildPropUpdatePayload(payload.form as SeriesPropDesignForm))
      },
      // series 设计流经 /rps/create 创建，createSource=manual，仅 name/aliases 可写主表
      { isManual: true }
    )
    return payload.existing
  }

  const pendingName =
    payload.form.name.trim() || (payload.assetType === 'character' ? '角色: 未命名' : '道具: 未命名')
  const row = await userAssetRpsCreate({
    projectId: payload.projectId,
    episodeId: payload.episodeId,
    name: pendingName,
    assetType: payload.assetType
  })
  const assetId = Number(row.id)
  if (!Number.isFinite(assetId) || assetId <= 0) throw new Error('创建资产失败')

  await userAssetRpsUpdateMain(
    {
      id: assetId,
      ...(payload.assetType === 'character'
        ? buildCharacterUpdatePayload(payload.form as SeriesCharacterDesignForm)
        : buildPropUpdatePayload(payload.form as SeriesPropDesignForm))
    },
    { isManual: true }
  )

  const formId = await ensureDefaultForm(payload.projectId, payload.episodeId, assetId, row)

  return {
    assetId,
    formId,
    imageId: null,
    imageUrl: null
  }
}

export async function uploadSeriesAssetManualImage(payload: {
  projectId: number
  episodeId: number
  assetType: SeriesAssetDesignType
  form: SeriesAssetDesignForm
  imageUrl: string
  draft?: SeriesAssetDraftState | null
}): Promise<SeriesAssetDraftState> {
  const draft = await ensureSeriesAssetDraft({
    projectId: payload.projectId,
    episodeId: payload.episodeId,
    assetType: payload.assetType,
    form: payload.form,
    existing: payload.draft
  })

  const created = await userAssetRpsFormImageCreate({
    formId: draft.formId,
    imageUrl: payload.imageUrl,
    name: payload.form.name.trim() || '形态图',
    sourceType: 'upload'
  })
  const imageId = resolveCreatedFormImageId(created)
  if (imageId != null) {
    await setFormImageInUse(imageId, { projectId: payload.projectId })
  }

  return {
    ...draft,
    imageId,
    imageUrl: payload.imageUrl
  }
}

async function pickLatestFormImageUrl(formId: number): Promise<string | null> {
  const list = await userAssetRpsFormImageList({ formId })
  const normalized = (Array.isArray(list) ? list : []).filter(
    (x) => x?.id != null && Number.isFinite(Number(x.id))
  )
  const inUse = normalized.find((x) => Number(x.isUse) === 1)
  const hit = inUse ?? normalized[normalized.length - 1]
  const url = String(hit?.imageUrl || '').trim()
  return url || null
}

export async function generateSeriesAssetImage(payload: {
  projectId: number
  episodeId: number
  assetType: SeriesAssetDesignType
  form: SeriesAssetDesignForm
  draft?: SeriesAssetDraftState | null
  onProgress?: (p: { percent?: number; message?: string }) => void
}): Promise<SeriesAssetDraftState> {
  const draft = await ensureSeriesAssetDraft({
    projectId: payload.projectId,
    episodeId: payload.episodeId,
    assetType: payload.assetType,
    form: payload.form,
    existing: payload.draft
  })

  const imageFields = await resolveImageSubmitFields(payload.projectId, payload.assetType)
  const submit = await userAssetExtractFormGenerateImage({
    formIds: [draft.formId],
    agentCode: imageFields.agentCode,
    ...(imageFields.modelCode ? { modelCode: imageFields.modelCode } : {}),
    ...(imageFields.resolution ? { resolution: imageFields.resolution } : {}),
    ...(imageFields.aspectRatio ? { aspectRatio: imageFields.aspectRatio } : {})
  })

  const taskId = extractFormGenerateImageSubmitTaskId(submit)
  if (!taskId) throw new Error('角色图生成任务提交失败：未返回任务ID')

  const sse = await waitUserTaskSseTerminal({
    taskId,
    onProgress: (p) => payload.onProgress?.({ percent: p.percent, message: p.message })
  })

  if (sse.kind === 'timeout' || sse.kind === 'superseded') {
    const detail = await fetchUserTaskDetailOnce(taskId)
    const st = String(detail?.status || '').toUpperCase()
    if (st !== 'SUCCEEDED' && st !== 'PARTIAL_FAILED') {
      throw new Error('生成超时，请稍后在任务中心查看')
    }
  } else if (sse.event.type === 'error') {
    throw new Error(sse.event.errorMessage || '生成失败')
  } else if (sse.event.type === 'cancelled') {
    throw new Error(sse.event.message || '任务已取消')
  } else if (sse.event.type === 'partial_failed') {
    throw new Error('部分生成失败，请稍后重试')
  }

  const imageUrl = await pickLatestFormImageUrl(draft.formId)
  if (!imageUrl) throw new Error('生成成功但未获取到图片，请稍后刷新')

  const list = await userAssetRpsFormImageList({ formId: draft.formId })
  const inUse = (Array.isArray(list) ? list : []).find((x) => Number(x.isUse) === 1)
  const last = (Array.isArray(list) ? list : [])[list.length - 1]
  const imageId = resolveCreatedFormImageId(inUse ?? last ?? {})

  if (imageId != null) {
    await setFormImageInUse(imageId, { projectId: payload.projectId })
  }

  message.success(payload.assetType === 'character' ? '角色图生成成功' : '道具图生成成功')

  return {
    ...draft,
    imageId,
    imageUrl
  }
}
