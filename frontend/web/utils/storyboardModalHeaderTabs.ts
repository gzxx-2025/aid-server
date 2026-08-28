import type { StoryboardRecordListType,StoryboardRecordRow } from '~/types/business-api'
import { groupStoryboardRecordsByStoryboardId } from '~/utils/storyboardRecordBatch'
import {
isComposeStoryboardVideoRecord,
isOriginalStoryboardVideoRecord,
isStoryboardRecordSelected
} from '~/utils/storyboardRecordRow'

export interface StoryboardModalSceneMeta {
  name: string
  storyboardId?: number | string
}

export interface StoryboardModalHeaderTab {
  sceneIndex: number
  storyboardId?: number
  name: string
  thumbnailUrl: string
  /** 是否存在 isSelected=1 的最终产物（图/原视频/compose 配音视频） */
  hasFinalAsset: boolean
  /** 配音弹窗：是否已有 compose 音画同步产物 */
  dubbingConfigured?: boolean
}

export function pickStoryboardHeaderThumbnailRecord(
  rows: StoryboardRecordRow[]
): StoryboardRecordRow | null {
  const withUrl = rows.filter((r) => !!String(r?.fileUrl ?? '').trim())
  if (!withUrl.length) return null
  return withUrl.find((r) => isStoryboardRecordSelected(r)) ?? withUrl[0] ?? null
}

function filterRowsForHeaderType(
  rows: StoryboardRecordRow[],
  type: StoryboardRecordListType
): StoryboardRecordRow[] {
  if (type === 'video') {
    return rows.filter((r) => isOriginalStoryboardVideoRecord(r))
  }
  if (type === 'compose') {
    return rows.filter((r) => isComposeStoryboardVideoRecord(r))
  }
  return rows
}

export function buildStoryboardModalHeaderTabs(
  scenes: StoryboardModalSceneMeta[],
  allRows: StoryboardRecordRow[],
  type: StoryboardRecordListType,
  options?: {
    resolveFallbackThumbnailUrl?: (sceneIndex: number) => string
    resolveDubbingConfigured?: (sceneIndex: number, composeRows: StoryboardRecordRow[]) => boolean
  }
): StoryboardModalHeaderTab[] {
  const byStoryboardId = groupStoryboardRecordsByStoryboardId(allRows)

  return scenes.map((scene, sceneIndex) => {
    const sid = Number(scene.storyboardId)
    const storyboardId = Number.isFinite(sid) && sid > 0 ? sid : undefined
    const sceneRows = storyboardId ? byStoryboardId.get(storyboardId) ?? [] : []
    const filtered = filterRowsForHeaderType(sceneRows, type)
    const picked = pickStoryboardHeaderThumbnailRecord(filtered)
    const thumbnailFromRecord = String(picked?.fileUrl ?? '').trim()
    const fallbackThumb = String(options?.resolveFallbackThumbnailUrl?.(sceneIndex) ?? '').trim()

    let dubbingConfigured: boolean | undefined
    if (type === 'compose') {
      dubbingConfigured =
        options?.resolveDubbingConfigured?.(sceneIndex, filtered) ??
        (!!thumbnailFromRecord && isStoryboardRecordSelected(picked))
    }

    const thumbnailUrl =
      type === 'compose'
        ? thumbnailFromRecord || fallbackThumb
        : thumbnailFromRecord || fallbackThumb

    return {
      sceneIndex,
      storyboardId,
      name: scene.name,
      thumbnailUrl,
      hasFinalAsset: !!picked && isStoryboardRecordSelected(picked),
      ...(type === 'compose' ? { dubbingConfigured: !!dubbingConfigured } : {})
    }
  })
}
