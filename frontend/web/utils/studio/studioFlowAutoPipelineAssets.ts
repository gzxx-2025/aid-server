import type { AssetExtractType } from '@/types/business-api'
import { userAssetRpsList } from '@/utils/businessApi'

export type StudioFlowRpsAssetType = AssetExtractType

export function rpsRowHasForms(row: { forms?: Array<{ id?: number | null }> | null }): boolean {
  return Array.isArray(row.forms) && row.forms.some((form) => {
    const id = Number(form?.id)
    return Number.isFinite(id) && id > 0
  })
}

/** 形态是否已有可用参考图（续跑/批量时跳过，避免已完成节点再次进入加载） */
export function rpsFormHasImage(form: {
  imageUrl?: string | null
  imageCount?: number | null
  currentImageId?: number | null
  images?: Array<{ imageUrl?: string | null }> | null
}): boolean {
  if (String(form.imageUrl ?? '').trim()) return true
  if (typeof form.imageCount === 'number' && form.imageCount > 0) return true
  const currentImageId = Number(form.currentImageId)
  if (Number.isFinite(currentImageId) && currentImageId > 0) return true
  return (form.images ?? []).some((image) => Boolean(String(image.imageUrl ?? '').trim()))
}

export async function listStudioFlowRpsAssets(
  projectId: number,
  episodeId: number,
  assetType: StudioFlowRpsAssetType
) {
  const response = await userAssetRpsList({ projectId, episodeId, assetType })
  return Array.isArray(response.rows) ? response.rows : []
}

export async function collectStudioFlowAssetsMissingForms(
  projectId: number,
  episodeId: number,
  assetType: StudioFlowRpsAssetType
): Promise<number[]> {
  const rows = await listStudioFlowRpsAssets(projectId, episodeId, assetType)
  return rows
    .filter((row) => !rpsRowHasForms(row))
    .map((row) => Number(row.id))
    .filter((id) => Number.isFinite(id) && id > 0)
}

export function collectFormIdsMissingImagesFromRows(
  rows: Array<{ forms?: Array<{
    id?: number | null
    imageUrl?: string | null
    imageCount?: number | null
    currentImageId?: number | null
    images?: Array<{ imageUrl?: string | null }> | null
  }> | null }>
): number[] {
  const formIds = new Set<number>()
  for (const row of rows) {
    for (const form of row.forms ?? []) {
      const id = Number(form?.id)
      if (!Number.isFinite(id) || id <= 0) continue
      if (!rpsFormHasImage(form)) formIds.add(id)
    }
  }
  return [...formIds]
}

export async function collectStudioFlowFormIdsMissingImages(
  projectId: number,
  episodeId: number,
  assetType: StudioFlowRpsAssetType
): Promise<number[]> {
  const rows = await listStudioFlowRpsAssets(projectId, episodeId, assetType)
  return collectFormIdsMissingImagesFromRows(rows)
}

export async function collectStudioFlowAllFormIds(
  projectId: number,
  episodeId: number
): Promise<number[]> {
  const types: StudioFlowRpsAssetType[] = ['scene', 'character', 'prop']
  const formIds = new Set<number>()
  for (const assetType of types) {
    const rows = await listStudioFlowRpsAssets(projectId, episodeId, assetType)
    for (const row of rows) {
      for (const form of row.forms ?? []) {
        const id = Number(form?.id)
        if (Number.isFinite(id) && id > 0) formIds.add(id)
      }
    }
  }
  return [...formIds]
}

export async function collectStudioFlowAllFormIdsMissingImages(
  projectId: number,
  episodeId: number
): Promise<number[]> {
  const types: StudioFlowRpsAssetType[] = ['scene', 'character', 'prop']
  const formIds = new Set<number>()
  for (const assetType of types) {
    for (const id of await collectStudioFlowFormIdsMissingImages(projectId, episodeId, assetType)) {
      formIds.add(id)
    }
  }
  return [...formIds]
}

export async function countStudioFlowRpsAssets(
  projectId: number,
  episodeId: number
): Promise<number> {
  const types: StudioFlowRpsAssetType[] = ['scene', 'character', 'prop']
  let total = 0
  for (const assetType of types) {
    total += (await listStudioFlowRpsAssets(projectId, episodeId, assetType)).length
  }
  return total
}
