import { userStoryboardUpload } from '~/utils/businessApi'
import { uploadVideoToOssWithToast } from '~/utils/ossUpload'
import type { ReferenceMediaItem } from '~/utils/referenceMediaItem'
import { readVideoDurationSeconds } from '~/utils/videoDuration'

export interface ReferenceVideoImportContext {
  projectId: number
  episodeId: number
  storyboardId: number
}

type LibraryVideoItem = {
  id?: string | number
  name?: string
  title?: string
  url?: string
  thumbnail?: string
  type?: string
  raw?: {
    id?: string | number
    projectId?: string | number
    assetType?: string
  }
}

function assertContext(context: ReferenceVideoImportContext) {
  if (!Number.isFinite(context.projectId) || context.projectId <= 0) {
    throw new Error('缺少项目信息，无法导入参考视频')
  }
  if (!Number.isFinite(context.storyboardId) || context.storyboardId <= 0) {
    throw new Error('缺少分镜信息，无法导入参考视频')
  }
}

function videoName(item: LibraryVideoItem, index = 0): string {
  return String(item.name || item.title || '').trim() || `参考视频${index + 1}`
}

function toReferenceVideoItem(opts: {
  recordId: number
  url: string
  name: string
  durationSeconds?: number
  source: string
}): ReferenceMediaItem {
  return {
    kind: 'video',
    id: `reference-video-${opts.recordId}`,
    referenceVideoRecordId: opts.recordId,
    name: opts.name,
    title: opts.name,
    url: opts.url,
    thumbnail: opts.url,
    durationSeconds: opts.durationSeconds,
    source: opts.source
  }
}

async function registerReferenceVideo(
  item: LibraryVideoItem,
  context: ReferenceVideoImportContext,
  source: string
): Promise<ReferenceMediaItem> {
  assertContext(context)
  const url = String(item.url || item.thumbnail || '').trim()
  if (!url) throw new Error('参考视频地址无效')
  const durationSeconds = await readVideoDurationSeconds(url)
  const record = await userStoryboardUpload({
    projectId: context.projectId,
    episodeId: Math.max(0, Number(context.episodeId) || 0),
    storyboardId: context.storyboardId,
    imageUrl: url,
    mediaType: 'video',
    videoDuration: durationSeconds
  })
  const recordId = Number(record?.id)
  if (!Number.isFinite(recordId) || recordId <= 0) {
    throw new Error('参考视频登记失败，请重试')
  }
  return toReferenceVideoItem({
    recordId,
    url,
    name: videoName(item),
    durationSeconds,
    source
  })
}

/** 本地视频先上传 OSS，再登记为当前项目可校验的分镜视频记录。 */
export async function importLocalReferenceVideos(
  files: File[],
  context: ReferenceVideoImportContext
): Promise<ReferenceMediaItem[]> {
  assertContext(context)
  const result: ReferenceMediaItem[] = []
  for (let index = 0; index < files.length; index += 1) {
    const file = files[index]!
    if (!file.type.startsWith('video/')) continue
    const durationSeconds = await readVideoDurationSeconds(file)
    const url = await uploadVideoToOssWithToast(file)
    if (!url) continue
    const record = await userStoryboardUpload({
      projectId: context.projectId,
      episodeId: Math.max(0, Number(context.episodeId) || 0),
      storyboardId: context.storyboardId,
      imageUrl: url,
      mediaType: 'video',
      videoDuration: durationSeconds
    })
    const recordId = Number(record?.id)
    if (!Number.isFinite(recordId) || recordId <= 0) {
      throw new Error(`视频“${file.name}”登记失败，请重试`)
    }
    result.push(
      toReferenceVideoItem({
        recordId,
        url,
        name: file.name.replace(/\.[^/.]+$/, '') || `参考视频${index + 1}`,
        durationSeconds,
        source: '本地上传'
      })
    )
  }
  return result
}

/**
 * 资产中心的 storyboard_video.id 本身就是 aid_gen_record.id；同项目可直接复用。
 * 其他来源则登记到当前分镜，避免把跨项目记录 ID 或裸 URL 交给生成接口。
 */
export async function importLibraryReferenceVideos(
  items: LibraryVideoItem[],
  context: ReferenceVideoImportContext
): Promise<ReferenceMediaItem[]> {
  assertContext(context)
  const result: ReferenceMediaItem[] = []
  for (let index = 0; index < items.length; index += 1) {
    const item = items[index]!
    const rawType = String(item.raw?.assetType || '').trim()
    const rawProjectId = Number(item.raw?.projectId)
    const recordId = Number(item.raw?.id ?? item.id)
    const url = String(item.url || item.thumbnail || '').trim()
    if (
      (rawType === 'storyboard_video' || rawType === 'storyboard-video') &&
      rawProjectId === context.projectId &&
      Number.isFinite(recordId) &&
      recordId > 0 &&
      url
    ) {
      result.push(
        toReferenceVideoItem({
          recordId,
          url,
          name: videoName(item, index),
          source: '资产库导入'
        })
      )
      continue
    }
    result.push(await registerReferenceVideo(item, context, '资产库导入'))
  }
  return result
}
