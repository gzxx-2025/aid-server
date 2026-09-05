import type { UserProjectRow, UserEpisodeRow } from '~/types/business-api'
import type { GlobalSettingData } from '~/types'
import type { useCreationStore } from '~/stores/creation'
import { fetchUserProjectDetailOnce, invalidateUserProjectDetailCache } from '~/utils/userProjectDetailOnce'
import {
  normalizeScriptTypeValue,
  resolveProjectCreationMode
} from '~/utils/globalSettingEnums'
import { createInitialCreationState } from '~/stores/creation/state'

type CreationStore = ReturnType<typeof useCreationStore>

export function mapProjectAspectRatio(
  value?: string | null
): GlobalSettingData['aspectRatio'] {
  const v = String(value || '').trim()
  if (v === '16:9' || v === '9:16' || v === '4:3' || v === '3:4' || v === '1:1' || v === '21:9') {
    return v
  }
  return '16:9'
}

export function mapProjectModelStrategy(
  value?: string | null
): GlobalSettingData['modelStrategy'] {
  if (value === 'performance') return 'performance'
  return 'economy'
}

export function mapProjectCreationMode(
  value?: string | null,
  previous?: string | null
): GlobalSettingData['creationMode'] {
  return resolveProjectCreationMode(value, previous)
}

export function mapProjectScriptType(
  value?: string | null
): GlobalSettingData['scriptType'] {
  const normalized = normalizeScriptTypeValue(String(value || '').trim())
  if (normalized) return normalized
  return 'plot'
}

/** 由 project/detail 的风格快照与稳定来源构造 selectedStyle，风格库加载后补齐真实图片。 */
export function buildSelectedStyleFromProjectDetail(
  detail: Pick<
    UserProjectRow,
    'id' | 'videoStyleType' | 'videoStyleValue' | 'coverUrl' | 'styleSource' | 'styleAssetId'
  >
): GlobalSettingData['selectedStyle'] {
  const type = String(detail.videoStyleType || '').trim()
  const value = String(detail.videoStyleValue || '').trim()
  if (!type && !value) return null

  const isUrl = /^https?:\/\//i.test(value)
  const name = type || (isUrl ? '自定义风格' : value) || '已选风格'
  const source = detail.styleSource === 'official' || detail.styleSource === 'custom'
    ? detail.styleSource
    : null
  const assetId = Number(detail.styleAssetId)
  const stableSource = source && Number.isFinite(assetId) && assetId > 0 ? source : null
  return {
    id: stableSource
      ? `${stableSource.toUpperCase()}-${assetId}`
      : `project-${detail.id}-style`,
    name,
    // 项目封面不是风格图；稳定ID会在风格库加载后补齐真实图片。
    thumbnail: isUrl ? value : (stableSource ? '' : String(detail.coverUrl || '').trim()),
    ...(stableSource ? { assetId, sourceFlag: stableSource } : {}),
    ...(type ? { assetName: type } : {}),
    ...(!isUrl && value ? { promptText: value } : {})
  }
}

export function buildGlobalSettingFromProjectDetail(
  detail: UserProjectRow,
  previous?: GlobalSettingData
): GlobalSettingData {
  const base = previous ?? ({} as GlobalSettingData)
  const selectedStyle = buildSelectedStyleFromProjectDetail(detail)
  return {
    ...base,
    title: detail.projectName || base.title || '',
    // 作品描述按当前 project 为准；空串不得回退到 previous，否则切作品会串上一作品文案
    description: String(detail.projectDesc || '').trim(),
    aspectRatio: mapProjectAspectRatio(detail.aspectRatio),
    scriptType: mapProjectScriptType(detail.scriptType),
    modelStrategy: mapProjectModelStrategy(detail.defaultGenMode),
    creationMode: mapProjectCreationMode(detail.defaultCreationMode, base.creationMode),
    style: selectedStyle?.name || detail.videoStyleType || base.style || '',
    selectedStyle: selectedStyle ?? base.selectedStyle ?? null,
    myStyles: base.myStyles ?? [],
    styleSelectionTouched: false,
    styleLocked: detail.styleLocked === true
  }
}

/** 将 /api/user/project/detail 写入 creationStore（作品库打开、流程页刷新回显共用） */
export function applyProjectDetailToCreationStore(
  store: CreationStore,
  detail: UserProjectRow,
  projectId: number,
  options?: {
    explicitSeriesEpisodeId?: number | null
    preservePreviousProjectStyle?: boolean
  }
) {
  const currentSetting = options?.preservePreviousProjectStyle === false
    ? {
        ...createInitialCreationState().formData.globalSetting,
        myStyles: store.formData.globalSetting.myStyles ?? []
      }
    : store.formData.globalSetting
  const nextSetting = buildGlobalSettingFromProjectDetail(detail, currentSetting)

  store.setWorkTitle(detail.projectName || '未命名作品')
  store.updateFormData({ globalSetting: nextSetting })
  store.setCurrentProjectType(detail.projectType)
  store.setCurrentProjectContext({
    projectId,
    episodeId: detail.projectType === 'movie'
      ? 0
      : (Number(options?.explicitSeriesEpisodeId) > 0
          ? Number(options?.explicitSeriesEpisodeId)
          : null)
  })
  store.setCurrentMediaContext({
    projectStatus: detail.status ?? null,
    projectStatusReason: detail.statusReason ?? null,
    episodeStatus: detail.projectType === 'movie' ? detail.status ?? null : null,
    episodeStatusReason: detail.projectType === 'movie' ? detail.statusReason ?? null : null,
    episodeEditorId: detail.projectType === 'movie' ? detail.episodeEditorId ?? null : null,
    finalVideoUrl: detail.projectType === 'movie' ? detail.finalVideoUrl ?? null : null,
    pendingVideoUrl: detail.projectType === 'movie' ? detail.pendingVideoUrl ?? null : null,
    exportStatus: detail.projectType === 'movie' ? detail.exportStatus ?? null : null
  })
  if (detail.projectType === 'series') {
    store.setSeriesFlowEnteredStoryScript(true)
  } else {
    store.setSeriesFlowEnteredStoryScript(false)
  }
}

/** 剧集切换时写入当前集的成片/审核字段 */
export function applyEpisodeRowToCreationStore(store: CreationStore, episode: UserEpisodeRow) {
  store.setCurrentMediaContext({
    episodeEditorId: episode.episodeEditorId ?? null,
    finalVideoUrl: episode.finalVideoUrl ?? null,
    pendingVideoUrl: episode.pendingVideoUrl ?? null,
    exportStatus: episode.exportStatus ?? null,
    episodeStatus: episode.status ?? null,
    episodeStatusReason: episode.statusReason ?? null
  })
}

export function resetProjectDetailHydrateCache(projectId?: number) {
  invalidateUserProjectDetailCache(projectId)
}

/**
 * 刷新/进入流程时从服务端拉取项目详情并灌入 store。
 * 须始终 apply：即便同一 projectId+updateTime，也要纠正 persist/中途写坏的 creationMode
 * （专业版刷新后分镜列表退回普通版 UI 的根因之一是「跳过写入」）。
 */
export async function hydrateCreationStoreFromProjectDetail(
  store: CreationStore,
  projectId: number,
  options?: {
    force?: boolean
    /** await 期间上下文可能已切换（快速切作品）：返回 false 则丢弃本次结果，不写 store */
    shouldApply?: () => boolean
    explicitSeriesEpisodeId?: number | null
    preservePreviousProjectStyle?: boolean
  }
): Promise<UserProjectRow | null> {
  const pid = Number(projectId)
  if (!Number.isFinite(pid) || pid <= 0) return null

  /** force 语义须穿透到接口缓存层：调用方要求强刷时不得命中 5s detail 短缓存 */
  const detail = await fetchUserProjectDetailOnce(pid, { force: options?.force })
  if (options?.shouldApply && !options.shouldApply()) {
    return null
  }
  applyProjectDetailToCreationStore(store, detail, pid, options)
  return detail
}

export function resolveRouteProjectId(query: Record<string, unknown>): number | null {
  const raw = Number(query.projectId ?? query.id ?? query.workId)
  return Number.isFinite(raw) && raw > 0 ? raw : null
}
