/** 剧本域：剧本详情/保存/静默保存/文件上传、分集预览与确认入库。 */
import { request } from '~/utils/api'
import {
  unwrap,
  extractPaginatedResponse,
  runListDedupe,
  OSS_UPLOAD_TIMEOUT_MS,
  type ListBurstSlot
} from '~/utils/business/shared'
import type {
  ApiEnvelope,
  ApiListEnvelopeData,
  ScriptDetailByProjectRequest,
  ScriptDetailRow,
  ScriptListRequest,
  ScriptListResult,
  ScriptSaveRequest,
  ScriptUploadRequest,
  ScriptSplitPreviewRequest,
  ScriptSplitPreviewVO,
  ScriptSplitConfirmVO
} from '~/types/business-api'

const scriptDetailInflight = new Map<string, Promise<ScriptDetailRow | null>>()
const scriptDetailBurst: ListBurstSlot<ScriptDetailRow | null> = { current: null }

function scriptDetailDedupeKey(body: ScriptDetailByProjectRequest): string {
  const pid = body.projectId != null && Number.isFinite(Number(body.projectId)) ? Number(body.projectId) : 0
  const eid = body.episodeId != null && Number.isFinite(Number(body.episodeId)) ? Number(body.episodeId) : 0
  return `${pid}:${eid}`
}

/**
 * 根据项目/剧集获取剧本详情（/api/user/script/detailByProject）
 * 电影 episodeId 传 0；剧集传具体集数 ID。
 * 剧本不存在时（常见 code 500）返回 `null`，不抛错。
 */
export async function userScriptDetailByProject(
  body: ScriptDetailByProjectRequest,
  options: { force?: boolean } = {}
): Promise<ScriptDetailRow | null> {
  const key = scriptDetailDedupeKey(body)
  const load = async () => {
    try {
      const res = await request.post<ApiEnvelope<ScriptDetailRow>>('/api/user/script/detailByProject', body)
      return unwrap(res)
    } catch (e: unknown) {
      const err = e as { code?: number; msg?: string }
      if (err?.code === 500 && /剧本不存在/.test(String(err?.msg ?? ''))) {
        return null
      }
      throw e
    }
  }
  return options.force ? load() : runListDedupe(key, scriptDetailInflight, scriptDetailBurst, load)
}

/** 保存剧本（版本+1，旧版进历史）/api/user/script/save */
export async function userScriptSave(body: ScriptSaveRequest): Promise<ScriptDetailRow> {
  const res = await request.post<ApiEnvelope<ScriptDetailRow>>('/api/user/script/save', body)
  return unwrap(res)
}

/** 静默保存剧本（不升版本）/api/user/script/autoSave */
export async function userScriptAutoSave(body: ScriptSaveRequest): Promise<ScriptDetailRow> {
  const res = await request.post<ApiEnvelope<ScriptDetailRow>>('/api/user/script/autoSave', body)
  return unwrap(res)
}

/** 查询当前项目/剧集的全部剧本版本，分页参数走查询参数。 */
export async function userScriptList(body: ScriptListRequest): Promise<ScriptListResult> {
  const { pageNum = 1, pageSize = 20, ...query } = body
  const res = await request.post<ApiListEnvelopeData<ScriptDetailRow>>(
    '/api/user/script/list',
    query,
    { params: { pageNum, pageSize } }
  )
  return extractPaginatedResponse<ScriptDetailRow>(res, pageNum, pageSize)
}

/** 软删除历史剧本。 */
export async function userScriptDelete(id: number): Promise<void> {
  await request.post<ApiEnvelope<unknown>>('/api/user/script/delete', { id })
}

/** 上传剧本文件（multipart）POST /api/user/script/upload */
export async function userScriptUpload(body: ScriptUploadRequest): Promise<ScriptDetailRow> {
  const fd = new FormData()
  fd.append('file', body.file)
  fd.append('projectId', String(body.projectId))
  if (body.episodeId != null && Number.isFinite(body.episodeId) && body.episodeId > 0) {
    fd.append('episodeId', String(body.episodeId))
  }
  const res = await request.post<ApiEnvelope<ScriptDetailRow>>('/api/user/script/upload', fd, {
    timeout: OSS_UPLOAD_TIMEOUT_MS,
    headers: { 'Content-Type': false } as unknown as Record<string, string>
  })
  return unwrap(res)
}

/** 剧本分集预览（只解析不入库）POST /api/user/script/split/preview */
export async function userScriptSplitPreview(
  body: ScriptSplitPreviewRequest
): Promise<ScriptSplitPreviewVO> {
  const res = await request.post<ApiEnvelope<ScriptSplitPreviewVO>>(
    '/api/user/script/split/preview',
    body
  )
  const data = unwrap(res)
  return {
    totalEpisodes: Number(data?.totalEpisodes) || 0,
    totalCharCount: data?.totalCharCount ?? null,
    episodeKeyword: data?.episodeKeyword ?? null,
    items: Array.isArray(data?.items) ? data.items : []
  }
}

/** 剧本分集确认入库 POST /api/user/script/split/confirm */
export async function userScriptSplitConfirm(
  body: ScriptSplitPreviewRequest
): Promise<ScriptSplitConfirmVO> {
  const res = await request.post<ApiEnvelope<ScriptSplitConfirmVO>>(
    '/api/user/script/split/confirm',
    body
  )
  const data = unwrap(res)
  return {
    totalEpisodes: Number(data?.totalEpisodes) || 0,
    episodes: Array.isArray(data?.episodes) ? data.episodes : []
  }
}
