import type { StoryboardPanel } from '@/types'
import type { UserAssetRpsRow } from '@/types/business-api'
import type { StudioFlowBinding } from '@/types/studio'
import { sortUserAssetRpsRows } from '@/utils/business/rps'

export function buildStoryboardModalScenes(panels: StoryboardPanel[]) {
  return panels.map((panel) => ({
    name: panel.title,
    images: Array.isArray(panel.images) ? panel.images.map((img) => ({ ...img })) : [],
    scriptContent: panel.scriptContent ?? '',
    storyboardId: Number.isFinite(Number(panel.id)) ? Number(panel.id) : undefined
  }))
}

export function resolveStoryboardPanelIndex(
  serverId: number | undefined,
  panels: StoryboardPanel[]
): number {
  if (!Number.isFinite(serverId) || !serverId || serverId <= 0) return -1
  return panels.findIndex((panel) => Number(panel.id) === serverId)
}

export function buildSceneModalScenesFromAssets(
  assetType: 'scene' | 'character' | 'prop',
  rows: UserAssetRpsRow[],
  targetAssetId: number
) {
  const sorted = sortUserAssetRpsRows(rows)
  const sceneIndex = sorted.findIndex((row) => Number(row.id) === targetAssetId)
  const scenes = sorted.map((row) => ({
    name: row.assetName || '未命名',
    images: (row.forms ?? []).flatMap((form) => {
      const url = String(form.imageUrl || '').trim()
      if (!url) return []
      return [{
        url,
        thumbnail: url,
        rpsFormId: form.id,
        rpsImageId: form.currentImageId ?? form.id
      }]
    }),
    setting: assetPrompt(row)
  }))
  return { sceneIndex: sceneIndex >= 0 ? sceneIndex : 0, scenes, rpsAssetIdsByIndex: Object.fromEntries(
    sorted.map((row, index) => [index, Number(row.id)])
  ) }
}

export function resolveSceneAssetModalContext(
  binding: StudioFlowBinding,
  assets: Record<'scene' | 'character' | 'prop', UserAssetRpsRow[]>
) {
  const assetType = binding.assetType
  const assetId = Number(binding.serverId)
  if (!assetType || !Number.isFinite(assetId) || assetId <= 0) return null
  const rows = assets[assetType] ?? []
  return buildSceneModalScenesFromAssets(assetType, rows, assetId)
}

function assetPrompt(asset: UserAssetRpsRow): string {
  const forms = asset.forms ?? []
  return [
    ...forms.flatMap((form) => [form.descriptions, form.prompt, form.promptText, form.introduction, form.summary]),
    asset.assetName,
    asset.forms?.[0]?.summary
  ].map((value) => String(value ?? '').trim()).find(Boolean) ?? ''
}
