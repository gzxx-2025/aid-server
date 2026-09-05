'use client'

import { useEffect, useRef, useState } from 'react'
import { Modal, message } from 'antd'
import { CloseOutlined, FileTextOutlined, VideoCameraOutlined } from '@ant-design/icons'
import PreviewableImageThumb from '~/components/common/PreviewableImageThumb'
import ImagePreviewViewer from '~/components/common/ImagePreviewViewer'
import { noDataIconUrl as noDataIconRaw } from '~/utils/emptyImageIcon'
import dialogSelectNorRaw from '@/assets/img/icon/dialog-select-nor.svg'
import dialogSelectSelRaw from '@/assets/img/icon/dialog-select-sel.svg'
import fileGrayRaw from '@/assets/img/icon/file_gray.svg'
import { assetUrl } from '~/utils/assetUrl'
import { userAssetCenterDetail } from '~/utils/businessApi'
import type { AssetCenterCategoryTreeVO, AssetCenterDetailVO } from '~/types/business-api'
import { scriptApiTextToEditorHtml } from '~/utils/htmlPlain'
import { sanitizeDisplayHtml } from '~/utils/safeDisplayHtml'
import { useStackedModalZIndex } from '~/hooks/useStackedModalZIndex'
import { ImportAssetVideoThumb } from './ImportAssetVideoThumb'
import {
  isAcceptedImportAsset,
  isVisibleImportAsset,
  type ImportScriptModalProps
} from './import-script/importScriptModalState'
import {
  mapUserAssetRowToImportItem,
  materialKeyToApiType,
  materialLabelToKey,
  fetchOfficialAssetsAsRows,
  fetchOfficialMaterialAllRows,
  countRowsByAssetType,
  buildMaterialFolderItems,
  fetchPersonalCenterAllRows,
  fetchPersonalCenterRowsByCategory,
  countRowsByCategoryCode,
  buildCategoryFolderItems,
  findAssetCenterProject,
  findAssetCenterEpisode,
  getEpisodeCategories,
  resolveCurrentEpisodeNode,
  resolveNodeAssetCount,
  episodeDisplayLabel,
  fetchPersonalTypeTotal,
  resolveImportModalCategoryCode,
  resolveImportAssetDisplayMode
} from '~/utils/importAssetModalQuery'
import './ImportAssetList.css'

const noDataIconUrl = assetUrl(noDataIconRaw)
const dialogSelectNorIcon = assetUrl(dialogSelectNorRaw)
const dialogSelectSelIcon = assetUrl(dialogSelectSelRaw)
const fileGrayIconUrl = assetUrl(fileGrayRaw)

export interface ImportAssetListProps {
  category: string | null
  type: string
  projectId?: string | null
  episodeId?: number | null
  assetCenterTree?: AssetCenterCategoryTreeVO[]
  currentPath?: string[]
  selectedCategory?: any
  multiple?: boolean
  selectedAssetIds?: string[]
  acceptAssetType?: NonNullable<ImportScriptModalProps['acceptAssetType']>
  modalOpen?: boolean
  onSelect: (asset: any) => void
  onNavigate: (folderName: string) => void
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function buildPreviewContent(detail: AssetCenterDetailVO): { imageUrl: string; html: string } {
  const c = detail.content ?? {}
  const imageUrl = String(detail.imageUrl || '').trim()
  const videoUrl = String(detail.videoUrl || '').trim()
  const audioUrl = String(detail.audioUrl || '').trim()
  const coverUrl = String(detail.coverUrl || '').trim()

  if (imageUrl) {
    return { imageUrl, html: '' }
  }
  if (videoUrl) {
    return {
      imageUrl: '',
      html: `<video class="import-asset-preview-modal__video" src="${escapeHtml(videoUrl)}" controls playsinline></video>`
    }
  }
  if (audioUrl) {
    return {
      imageUrl: '',
      html: `<audio class="import-asset-preview-modal__audio" src="${escapeHtml(audioUrl)}" controls></audio>`
    }
  }

  const originalText = typeof c.originalText === 'string' ? c.originalText.trim() : ''
  if (originalText) {
    return {
      imageUrl: '',
      html: `<div class="import-asset-preview-modal__rich">${scriptApiTextToEditorHtml(originalText)}</div>`
    }
  }
  const storyScript = typeof c.storyScript === 'string' ? c.storyScript.trim() : ''
  if (storyScript) {
    return {
      imageUrl: '',
      html: `<pre class="import-asset-preview-modal__plain">${escapeHtml(storyScript)}</pre>`
    }
  }
  const text =
    (typeof c.dialogueText === 'string' && c.dialogueText.trim()) ||
    (typeof c.ttsText === 'string' && c.ttsText.trim()) ||
    (typeof c.promptText === 'string' && c.promptText.trim()) ||
    (typeof c.introduction === 'string' && c.introduction.trim()) ||
    (typeof c.summary === 'string' && c.summary.trim()) ||
    ''
  if (text) {
    return {
      imageUrl: '',
      html: `<pre class="import-asset-preview-modal__plain">${escapeHtml(text)}</pre>`
    }
  }
  if (coverUrl) {
    return { imageUrl: coverUrl, html: '' }
  }
  return { imageUrl: '', html: '<p class="import-asset-preview-modal__empty">暂无预览内容</p>' }
}

function formatTime(time: string) {
  const raw = String(time || '').trim()
  if (!raw) return ''
  const date = new Date(raw.includes('-') ? raw.replace(/-/g, '/') : raw)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleDateString('zh-CN')
}

function showAssetMeta(asset: any) {
  return !!formatTime(asset.updatedAt)
}

export function ImportAssetList({
  category,
  type,
  projectId = null,
  episodeId = null,
  assetCenterTree = [],
  currentPath = [],
  selectedCategory = null,
  multiple = false,
  selectedAssetIds = [],
  acceptAssetType = 'all',
  modalOpen = true,
  onSelect,
  onNavigate
}: ImportAssetListProps) {
  const [selectedAssetId, setSelectedAssetId] = useState<string | null>(null)
  const [listLoading, setListLoading] = useState(false)
  const [assets, setAssets] = useState<any[]>([])
  const loadSeqRef = useRef(0)

  const activeCategoryCode = resolveImportModalCategoryCode(category, selectedCategory)

  const displayMode = resolveImportAssetDisplayMode(assets, activeCategoryCode)

  const [previewOpen, setPreviewOpen] = useState(false)
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewTitle, setPreviewTitle] = useState('')
  const [previewImageUrl, setPreviewImageUrl] = useState('')
  const [previewHtml, setPreviewHtml] = useState('')
  const previewModalZIndex = useStackedModalZIndex(previewOpen)

  useEffect(() => {
    if (modalOpen) return
    // Preview uses a separate portal and must not outlive its owner import modal.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setPreviewOpen(false)
    setPreviewLoading(false)
    setPreviewImageUrl('')
    setPreviewHtml('')
    setPreviewTitle('')
  }, [modalOpen])

  const previewModalWidth =
    typeof window === 'undefined' ? 1200 : Math.min(1200, Math.max(320, window.innerWidth - 48))

  const isAssetSelected = (asset: any) => {
    if (multiple && selectedAssetIds?.length) {
      return selectedAssetIds.includes(asset.id)
    }
    return selectedAssetId === asset.id
  }

  function closePreview() {
    setPreviewOpen(false)
    setPreviewImageUrl('')
    setPreviewHtml('')
    setPreviewTitle('')
  }

  async function handleFilePreview(asset: any) {
    const raw = asset?.raw ?? {}
    const id = Number(raw.id ?? asset.id)
    const categoryCode =
      activeCategoryCode ||
      (typeof raw.categoryCode === 'string' ? raw.categoryCode.trim() : '')
    if (!Number.isFinite(id) || id <= 0) {
      message.warning('无法预览该资产')
      return
    }
    if (!categoryCode) {
      message.warning('缺少分类信息，无法预览')
      return
    }
    setPreviewTitle(asset.name || '预览')
    setPreviewOpen(true)
    setPreviewLoading(true)
    setPreviewImageUrl('')
    setPreviewHtml('')
    try {
      const detail = await userAssetCenterDetail({
        categoryCode,
        id
      })
      setPreviewTitle(detail.name || detail.categoryName || asset.name || '预览')
      const content = buildPreviewContent(detail)
      setPreviewImageUrl(content.imageUrl)
      setPreviewHtml(sanitizeDisplayHtml(content.html))
    } catch (e: any) {
      message.error(e?.msg ?? e?.message ?? '加载预览失败')
      setPreviewOpen(false)
    } finally {
      setPreviewLoading(false)
    }
  }

  async function runLoadAssets(seq: number): Promise<any[] | null> {
    const path0 = currentPath.length === 0
    const nowLabel = () => new Date().toLocaleDateString('zh-CN')

    if (type === 'material' && path0 && category === 'material-library') {
      // 不传 assetType/keyword 一次拉全量官方素材，按 assetType 聚合文件夹
      const allRows = await fetchOfficialMaterialAllRows()
      if (seq !== loadSeqRef.current) return null
      return buildMaterialFolderItems(countRowsByAssetType(allRows), nowLabel())
    }

    if (
      type === 'material' &&
      path0 &&
      category?.startsWith('material-') &&
      category !== 'material-library'
    ) {
      const key = category.replace('material-', '')
      const apiType = materialKeyToApiType(key)
      const rows = await fetchOfficialAssetsAsRows(apiType)
      if (seq !== loadSeqRef.current) return null
      return rows.map(mapUserAssetRowToImportItem)
    }

    if (type === 'material' && !path0) {
      const folderLabel = currentPath[currentPath.length - 1] || ''
      const key = materialLabelToKey(folderLabel)
      if (key) {
        const apiType = materialKeyToApiType(key)
        const rows = await fetchOfficialAssetsAsRows(apiType)
        if (seq !== loadSeqRef.current) return null
        return rows.map(mapUserAssetRowToImportItem)
      }
    }

    if (type === 'current' && path0 && category?.startsWith('project-')) {
      const pid = projectId ? Number(projectId) : NaN
      const ep = episodeId != null && episodeId >= 0 ? Number(episodeId) : 0
      if (!Number.isFinite(pid) || pid <= 0) {
        return []
      }
      const episodeNode = resolveCurrentEpisodeNode(assetCenterTree, pid, ep)
      const categories = getEpisodeCategories(episodeNode)
      // 不传 categoryCode 一次拉全量，前端按分类聚合展示文件夹
      const needListCount = categories.some((c) => typeof c.assetCount !== 'number')
      const countByCode = needListCount
        ? countRowsByCategoryCode(await fetchPersonalCenterAllRows(pid, ep))
        : new Map<string, number>()
      if (seq !== loadSeqRef.current) return null
      return buildCategoryFolderItems(categories, countByCode, 'folder-p-', nowLabel())
    }

    if (type === 'current' && path0 && category && !category.startsWith('project-')) {
      const dash = category.indexOf('-')
      if (dash > 0) {
        const projectIdStr = category.slice(0, dash)
        const docKey = category.slice(dash + 1)
        const pid = Number(projectIdStr)
        const ep = episodeId != null && episodeId >= 0 ? Number(episodeId) : 0
        if (Number.isFinite(pid) && pid > 0 && docKey) {
          const rows = await fetchPersonalCenterRowsByCategory(pid, ep, docKey)
          if (seq !== loadSeqRef.current) return null
          return rows.map(mapUserAssetRowToImportItem)
        }
      }
    }

    if (type === 'current' && !path0 && projectId) {
      const folderLabel = currentPath[currentPath.length - 1] || ''
      const pid = Number(projectId)
      const ep = episodeId != null && episodeId >= 0 ? Number(episodeId) : 0
      const episodeNode = resolveCurrentEpisodeNode(assetCenterTree, pid, ep)
      const cat = getEpisodeCategories(episodeNode).find(
        (c) => (c.categoryName || c.categoryCode) === folderLabel
      )
      if (cat?.categoryCode && Number.isFinite(pid) && pid > 0) {
        const rows = await fetchPersonalCenterRowsByCategory(pid, ep, cat.categoryCode)
        if (seq !== loadSeqRef.current) return null
        return rows.map(mapUserAssetRowToImportItem)
      }
    }

    if (type === 'history' && path0 && category?.startsWith('project-')) {
      const pid = Number(category.replace(/^project-/, ''))
      const project = findAssetCenterProject(assetCenterTree, pid)
      const episodes = project?.children ?? []

      if (episodes.length === 1) {
        const ep = episodes[0]!
        const epId = ep.episodeId ?? 0
        const categories = getEpisodeCategories(ep)
        const needListCount = categories.some((c) => typeof c.assetCount !== 'number')
        const countByCode = needListCount
          ? countRowsByCategoryCode(await fetchPersonalCenterAllRows(pid, epId))
          : new Map<string, number>()
        if (seq !== loadSeqRef.current) return null
        return buildCategoryFolderItems(
          categories,
          countByCode,
          `folder-hc-${pid}-${epId}-`,
          nowLabel()
        )
      }

      const folders = await Promise.all(
        (project?.children ?? []).map(async (ep) => {
          const epId = ep.episodeId ?? 0
          const cached = resolveNodeAssetCount(ep)
          // 剧集总数：优先树汇总；否则不传 categoryCode 一次取 total
          const total =
            cached != null ? cached : await fetchPersonalTypeTotal(pid, epId)
          return {
            id: `folder-he-${pid}-${epId}`,
            name: episodeDisplayLabel(ep),
            type: 'folder' as const,
            updatedAt: nowLabel(),
            itemCount: total ?? 0
          }
        })
      )
      if (seq !== loadSeqRef.current) return null
      return folders
    }

    if (type === 'history' && path0 && category?.startsWith('episode-')) {
      const m = category.match(/^episode-(\d+)-(\d+)$/)
      if (m) {
        const pid = Number(m[1])
        const ep = Number(m[2])
        const project = findAssetCenterProject(assetCenterTree, pid)
        const episode = findAssetCenterEpisode(project, ep)
        const categories = getEpisodeCategories(episode)
        const needListCount = categories.some((c) => typeof c.assetCount !== 'number')
        const countByCode = needListCount
          ? countRowsByCategoryCode(await fetchPersonalCenterAllRows(pid, ep))
          : new Map<string, number>()
        if (seq !== loadSeqRef.current) return null
        return buildCategoryFolderItems(
          categories,
          countByCode,
          `folder-hc-${pid}-${ep}-`,
          nowLabel()
        )
      }
    }

    if (type === 'history' && category) {
      const dash = category.indexOf('-')
      if (dash > 0) {
        const parts = category.split('-')
        const pid = Number(parts[0])
        if (!Number.isFinite(pid) || pid <= 0) {
          return []
        }
        let ep = 0
        let docKey = ''
        if (parts.length >= 3 && /^\d+$/.test(parts[1] ?? '')) {
          ep = Number(parts[1])
          docKey = parts.slice(2).join('-')
        } else {
          docKey = parts.slice(1).join('-')
          ep = episodeId != null && episodeId >= 0 ? Number(episodeId) : 0
        }
        if (docKey && docKey !== 'project') {
          const rows = await fetchPersonalCenterRowsByCategory(pid, ep, docKey)
          if (seq !== loadSeqRef.current) return null
          return rows.map(mapUserAssetRowToImportItem)
        }
      }
    }

    if (type === 'history') {
      return []
    }

    return []
  }

  async function loadAssets() {
    const seq = ++loadSeqRef.current
    setListLoading(true)
    setAssets([])
    try {
      const next = await runLoadAssets(seq)
      if (next != null && seq === loadSeqRef.current) {
        setAssets(next.filter((asset) => isVisibleImportAsset(asset, acceptAssetType)))
      }
    } catch (e: any) {
      if (seq === loadSeqRef.current) {
        message.error(e?.msg ?? e?.message ?? '加载资产失败')
        setAssets([])
      }
    } finally {
      if (seq === loadSeqRef.current) setListLoading(false)
    }
  }

  /** 原 onMounted + watch(props, deep) 合并：首渲染与任一来源参数变化都重拉列表 */
  useEffect(() => {
    setSelectedAssetId(null)
    void loadAssets()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    category,
    type,
    projectId,
    episodeId,
    assetCenterTree,
    currentPath,
    selectedCategory,
    acceptAssetType
  ])

  const selectAsset = (asset: any) => {
    if (asset.type === 'folder') {
      onNavigate(asset.name)
      return
    }
    if (!isAcceptedImportAsset(asset, acceptAssetType)) {
      // Let the owner show its context-specific warning without creating a
      // misleading local selected state for a non-importable card.
      onSelect(asset)
      return
    }
    if (multiple) {
      setSelectedAssetId(null)
      onSelect(asset)
    } else {
      setSelectedAssetId(selectedAssetId === asset.id ? null : asset.id)
      onSelect(asset)
    }
  }

  const handleAssetThumbnailClick = (asset: any) => {
    if (asset.type === 'folder') {
      onNavigate(asset.name)
      return
    }
    if (!isAcceptedImportAsset(asset, acceptAssetType)) {
      void handleFilePreview(asset)
      return
    }
    if (displayMode === 'file' || asset.type === 'script' || asset.type === 'video') {
      void handleFilePreview(asset)
      return
    }
    selectAsset(asset)
  }

  const handleThumbnailClick = handleAssetThumbnailClick

  return (
    <div className="asset-list">
      {listLoading ? (
        <div className="asset-list__loading">加载中…</div>
      ) : assets.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon-wrapper">
            <img src={noDataIconUrl} alt="" className="empty-image-icon empty-image-icon--xl" />
          </div>
          <p className="empty-text">暂无数据</p>
        </div>
      ) : displayMode === 'folder' ? (
        /* 文件夹：宽扁卡片网格 */
        <div className="assets-grid assets-grid--folder">
          {assets.map((asset) => (
            <div
              key={asset.id}
              className={`asset-card is-folder${isAssetSelected(asset) ? ' active' : ''}`}
              onClick={() => selectAsset(asset)}
            >
              <div
                className="asset-thumbnail asset-thumbnail--folder"
                onClick={(e) => {
                  e.stopPropagation()
                  handleThumbnailClick(asset)
                }}
              >
                <img src={fileGrayIconUrl} alt="" className="asset-icon folder-icon" />
              </div>
              <div className="asset-info">
                <div className="asset-name">{asset.name}</div>
                <div className="asset-meta">
                  <span className="asset-count">{asset.itemCount ?? 0}项</span>
                  {formatTime(asset.updatedAt) && (
                    <span className="asset-time">{formatTime(asset.updatedAt)}</span>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      ) : displayMode === 'video' ? (
        <div className="assets-grid assets-grid--image">
          {assets.map((asset) => (
            <div
              key={asset.id}
              className={`asset-card asset-card--image${isAssetSelected(asset) ? ' asset-card--selected' : ''}`}
              onClick={() => selectAsset(asset)}
            >
              <div className="asset-thumbnail aspect-video min-h-[100px] items-stretch justify-stretch">
                {asset.url ? (
                  <ImportAssetVideoThumb src={asset.url} title={asset.name} />
                ) : (
                  <VideoCameraOutlined className="asset-icon asset-icon--center" />
                )}
                {asset.featured && <span className="featured-badge">精选</span>}
                <img
                  className="asset-card-select"
                  src={isAssetSelected(asset) ? dialogSelectSelIcon : dialogSelectNorIcon}
                  alt=""
                  role="checkbox"
                  aria-checked={isAssetSelected(asset)}
                  onClick={(event) => {
                    event.stopPropagation()
                    selectAsset(asset)
                  }}
                />
              </div>
              <div className="asset-info asset-info--image">
                <div className="asset-name">{asset.name}</div>
              </div>
            </div>
          ))}
        </div>
      ) : displayMode === 'image' ? (
        /* 图片：缩略图卡片网格（上图下文）；选中态对齐批量生成分镜图列表 */
        <div className="assets-grid assets-grid--image">
          {assets.map((asset) => (
            <div
              key={asset.id}
              className={`asset-card asset-card--image${isAssetSelected(asset) ? ' asset-card--selected' : ''}`}
              onClick={() => selectAsset(asset)}
            >
              <div
                className="asset-thumbnail asset-thumbnail--image"
                onClick={(e) => {
                  e.stopPropagation()
                  handleAssetThumbnailClick(asset)
                }}
              >
                {asset.thumbnail ? (
                  <PreviewableImageThumb
                    src={asset.thumbnail}
                    alt={asset.name}
                    title={asset.name}
                    objectFit="cover"
                  />
                ) : (
                  <img src={noDataIconUrl} alt="" className="asset-image-placeholder" />
                )}
                {asset.featured && <span className="featured-badge">精选</span>}
                <img
                  className="asset-card-select"
                  src={isAssetSelected(asset) ? dialogSelectSelIcon : dialogSelectNorIcon}
                  alt=""
                  role="checkbox"
                  aria-checked={isAssetSelected(asset)}
                  onClick={(e) => {
                    e.stopPropagation()
                    selectAsset(asset)
                  }}
                />
              </div>
              <div className="asset-info asset-info--image">
                <div className="asset-name">{asset.name}</div>
              </div>
            </div>
          ))}
        </div>
      ) : (
        /* 文件：宽扁卡片网格 */
        <div className="assets-grid assets-grid--card">
          {assets.map((asset) => (
            <div
              key={asset.id}
              className={`asset-card${isAssetSelected(asset) ? ' active' : ''}`}
              onClick={() => selectAsset(asset)}
            >
              <div
                className="asset-thumbnail asset-thumbnail--file"
                onClick={(e) => {
                  e.stopPropagation()
                  handleAssetThumbnailClick(asset)
                }}
              >
                {asset.type === 'video' ? (
                  <VideoCameraOutlined className="asset-icon asset-icon--center" />
                ) : (
                  <FileTextOutlined className="asset-icon asset-icon--center" />
                )}
                {asset.featured && <span className="featured-badge">精选</span>}
              </div>
              <div className="asset-info">
                <div className="asset-name">{asset.name}</div>
                {showAssetMeta(asset) && (
                  <div className="asset-meta">
                    {formatTime(asset.updatedAt) && (
                      <span className="asset-time">{formatTime(asset.updatedAt)}</span>
                    )}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      <Modal
        open={previewOpen}
        width={previewModalWidth}
        footer={null}
        title={null}
        closable={false}
        centered
        zIndex={previewModalZIndex}
        className="import-asset-preview-modal"
        wrapClassName="create-flow-modal import-asset-preview-modal-wrap"
        destroyOnHidden
        onCancel={closePreview}
      >
        <div className="import-asset-preview-modal-shell">
          <header className="import-asset-preview-modal-header">
            <h3 className="import-asset-preview-modal-title">{previewTitle}</h3>
            <button
              type="button"
              className="import-asset-preview-modal-close"
              aria-label="关闭"
              onClick={closePreview}
            >
              <CloseOutlined />
            </button>
          </header>
          <div className="import-asset-preview-modal-content">
            {previewLoading ? (
              <div className="import-asset-preview-modal__loading">加载预览…</div>
            ) : previewImageUrl ? (
              <ImagePreviewViewer url={previewImageUrl} alt={previewTitle} maxHeight="62vh" />
            ) : (
              <div
                className="import-asset-preview-modal__body"
                dangerouslySetInnerHTML={{ __html: previewHtml }}
              />
            )}
          </div>
        </div>
      </Modal>
    </div>
  )
}

export default ImportAssetList
