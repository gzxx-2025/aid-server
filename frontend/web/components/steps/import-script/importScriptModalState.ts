import type { AssetCenterCategoryTreeVO } from '~/types/business-api'
import {
  CENTER_CATEGORY_FALLBACK,
  getEpisodeCategories,
  resolveCurrentEpisodeNode
} from '~/utils/importAssetModalQuery'
import type { ImportScriptTab } from './ImportScriptSidebarTree'

export interface ImportScriptModalProps {
  open: boolean
  title?: string
  zIndex?: number
  multiple?: boolean
  acceptAssetType?: 'image' | 'video' | 'script' | 'all'
  initialTab?: ImportScriptTab | null
  initialMaterialCategory?: string | null
  beforeScriptImport?: () => Promise<boolean>
  onOpenChange: (open: boolean) => void
  onImport?: (content: string | any) => void | boolean | Promise<void | boolean>
  onImportMultiple?: (assets: any[]) => void | boolean | Promise<void | boolean>
}

export function isVideoAsset(asset: Record<string, unknown> | null | undefined): boolean {
  if (!asset) return false
  if (asset.type === 'video') return true
  const url = String(asset.url || asset.src || '')
  const name = String(asset.name || asset.title || '')
  const mime = String(asset.mimeType || asset.type || '')
  const videoExt = /\.(mp4|webm|mov|avi|mkv|m4v)(\?|$)/i
  return videoExt.test(url) || videoExt.test(name) || mime.startsWith('video/')
}

export function isImageAsset(asset: Record<string, unknown> | null | undefined): boolean {
  if (!asset || asset.type !== 'image') return false
  return !!String(asset.url || asset.thumbnail || '').trim()
}

export function isScriptAsset(asset: Record<string, unknown> | null | undefined): boolean {
  if (!asset || isImageAsset(asset) || isVideoAsset(asset)) return false
  const type = String(asset.type || '').trim().toLowerCase()
  const name = String(asset.name || asset.title || '').trim()
  const mime = String(asset.mimeType || '').trim().toLowerCase()
  return (
    type === 'script' ||
    type === 'text' ||
    (type === 'file' && (/\.txt$/i.test(name) || mime === 'text/plain'))
  )
}

export function isAcceptedImportAsset(
  asset: Record<string, unknown> | null | undefined,
  acceptAssetType: NonNullable<ImportScriptModalProps['acceptAssetType']>
): boolean {
  if (!asset) return false
  if (asset.type === 'folder') return true
  if (acceptAssetType === 'image') return isImageAsset(asset)
  if (acceptAssetType === 'video') return isVideoAsset(asset)
  // `all` means the complete library is browsable in script-import contexts;
  // it must not allow binary media to enter the screenplay import callback.
  return isScriptAsset(asset)
}

/**
 * Whether an asset should remain visible while browsing the library.
 *
 * Script import intentionally keeps every category and asset card browsable so
 * users can inspect the complete project asset library. Selection is guarded
 * separately by `isAcceptedImportAsset`, which only accepts text/script assets.
 */
export function isVisibleImportAsset(
  asset: Record<string, unknown> | null | undefined,
  acceptAssetType: NonNullable<ImportScriptModalProps['acceptAssetType']>
): boolean {
  if (!asset) return false
  if (asset.type === 'folder' || acceptAssetType === 'script' || acceptAssetType === 'all') {
    return true
  }
  return isAcceptedImportAsset(asset, acceptAssetType)
}

export function resolveImportModalProjectState(input: {
  projects: Array<{ id: string; name: string }>
  selectedProjectId: string
  storeProjectId: number | null
  storeWorkTitle: string
  episodeId: number
  assetCenterTree: AssetCenterCategoryTreeVO[]
  treeLoading: boolean
}) {
  const selectedProject = input.projects.find((project) => project.id === input.selectedProjectId)
  const currentProject = selectedProject || input.projects[0] || {
    id: input.storeProjectId ? String(input.storeProjectId) : '',
    name: input.storeWorkTitle || '未命名作品'
  }
  const displayProjectId =
    input.selectedProjectId ||
    (input.storeProjectId ? String(input.storeProjectId) : '') ||
    input.projects[0]?.id ||
    ''
  const projectId = Number(displayProjectId)
  const episode = Number.isFinite(projectId) && projectId > 0
    ? resolveCurrentEpisodeNode(input.assetCenterTree, projectId, input.episodeId)
    : undefined
  const treeCategories = getEpisodeCategories(episode)
  const categories = treeCategories.length || input.treeLoading || !Number.isFinite(projectId) || projectId <= 0
    ? treeCategories
    : CENTER_CATEGORY_FALLBACK.map((item) => ({
        projectId,
        projectName: currentProject.name,
        categoryCode: item.categoryCode,
        categoryName: item.categoryName,
        assetCount: null
      }))

  return { currentProject, displayProjectId, currentEpisodeCategories: categories }
}
