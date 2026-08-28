import type { AssetCenterCategoryTreeVO } from '~/types/business-api'
import type { ImportScriptTab } from './ImportScriptSidebarTree'

export interface ImportScriptModalProps {
  open: boolean
  title?: string
  /** 嵌套弹窗时传入更高 z-index，避免被父级弹窗遮挡 */
  zIndex?: number
  multiple?: boolean
  acceptAssetType?: 'image' | 'video' | 'script' | 'all'
  initialTab?: ImportScriptTab | null
  initialMaterialCategory?: string | null
  beforeScriptImport?: () => Promise<boolean>
  onOpenChange: (open: boolean) => void
  onImport?: (content: string | any) => void
  onImportMultiple?: (assets: any[]) => void
}

/** 判断是否为视频资产（按 type、扩展名或 mimeType）。 */
export function isVideoAsset(asset: Record<string, unknown> | null | undefined): boolean {
  if (!asset) return false
  if (asset.type === 'video') return true
  const url = String(asset.url || asset.src || '')
  const name = String(asset.name || asset.title || '')
  const mime = String(asset.mimeType || asset.type || '')
  const videoExtension = /\.(mp4|webm|mov|avi|mkv|m4v)(\?|$)/i
  return videoExtension.test(url) || videoExtension.test(name) || mime.startsWith('video/')
}

export function categoryToSelection(
  category: AssetCenterCategoryTreeVO,
  projectId: number,
  episodeId: number
) {
  const code = category.categoryCode || ''
  return {
    key: code,
    label: category.categoryName || code,
    projectId,
    episodeId
  }
}
