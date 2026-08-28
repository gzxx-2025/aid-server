'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { Button, Modal, Spin, message } from 'antd'
import { CloseOutlined, UploadOutlined, FolderOutlined } from '@ant-design/icons'
import { openImagePreviewModal } from '~/utils/openImagePreviewModal'
import ImportScriptModal from '../ImportScriptModal'
import SelectAssetVoiceTab, { type OfficialVoicePick } from '../SelectAssetVoiceTab'
import SelectAssetVideoFrameTab from '../SelectAssetVideoFrameTab'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { useCreationStore } from '~/stores/creation'
import { useReferenceAudioPreview } from '~/composables/useReferenceAudioPreview'
import { useRouteLike } from '~/composables/useRouteLike'
import { assetUrl } from '~/utils/assetUrl'
import dialogSelectNorRaw from '@/assets/img/icon/dialog-select-nor.svg'
import dialogSelectSelRaw from '@/assets/img/icon/dialog-select-sel.svg'
import { emptyImageIconUrl as emptyImageIconRaw } from '~/utils/emptyImageIcon'
import {
  ensureStep3AssetsForSelect,
  step3SelectNeedsFetch,
  type Step3SelectAssetType
} from '~/utils/step3AssetSelectLoader'
import {
  parseReferenceAudioCapability,
  validateReferenceAudioAdd,
  type ReferenceAudioCapability
} from '~/utils/referenceAudioCapability'
import { fromOfficialVoice, type ReferenceMediaItem } from '~/utils/referenceMediaItem'
import { userReferenceAudioDelete } from '~/utils/businessApi'
import { useStackedModalZIndex } from '~/hooks/useStackedModalZIndex'
import {
  buildProjectAssetGroups,
  formatDate,
  isAudioPendingItem,
  rowKey,
  TAB_OPTIONS_MAP,
  TITLE_MAP,
  type AssetImageType
} from './assetGroups'
import { uploadLocalReferenceAudios } from './referenceAudioUpload'
import { PendingImportList } from './PendingImportList'
import './select-asset-image-modal.css'

export type { AssetImageType }

const dialogSelectNorIcon = assetUrl(dialogSelectNorRaw)
const dialogSelectSelIcon = assetUrl(dialogSelectSelRaw)
const emptyImageIconUrl = assetUrl(emptyImageIconRaw)

export interface SelectAssetImageModalProps {
  open: boolean
  type: AssetImageType
  stepTabName?: string
  /** 当前分镜下的分镜图（第二 Tab） */
  stepPanelImages?: any[]
  /** 各分镜脚本的分镜图分组（reference 类型「本作品资产」Tab） */
  storyboardScriptGroups?: { label: string; images: any[] }[]
  /** 是否展示「音色」Tab（仅编辑分镜视频弹窗的导入参考图） */
  enableVoiceTab?: boolean
  /** 当前视频模型（用于参考音频 capability） */
  videoModel?: { capability?: unknown } | null
  projectId?: number
  episodeId?: number
  onOpenChange: (open: boolean) => void
  onConfirm: (items: any[]) => void
}

function isStep3AssetType(type: AssetImageType): type is Step3SelectAssetType {
  return type === 'scene' || type === 'character' || type === 'prop'
}

export function SelectAssetImageModal({
  open,
  type,
  stepTabName = '当前分镜',
  stepPanelImages = [],
  storyboardScriptGroups = [],
  enableVoiceTab = false,
  videoModel = null,
  projectId = 0,
  episodeId = 0,
  onOpenChange,
  onConfirm
}: SelectAssetImageModalProps) {
  const {
    playingId: pendingAudioPlayingId,
    play: playPendingAudio,
    stop: stopPendingAudio
  } = useReferenceAudioPreview()

  const audioCapability = useMemo<ReferenceAudioCapability>(
    () => parseReferenceAudioCapability(videoModel),
    [videoModel]
  )

  const showVoiceTab =
    enableVoiceTab &&
    (type === 'reference' || type === 'multiParamReference') &&
    audioCapability.supportsReferenceAudio

  const showVideoFrameTab = type === 'reference' || type === 'multiParamReference'

  const route = useRouteLike()
  const sceneCharacter = useCreationStore((s) => s.formData.sceneCharacter)
  const sceneImages = useCreationStore((s) => s.sceneImages)
  const characterImages = useCreationStore((s) => s.characterImages)
  const propImages = useCreationStore((s) => s.propImages)
  const characterForms = useCreationStore((s) => s.characterForms)
  const propForms = useCreationStore((s) => s.propForms)
  const characterFormImages = useCreationStore((s) => s.characterFormImages)
  const propFormImages = useCreationStore((s) => s.propFormImages)
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)

  const resolvedProjectId = (() => {
    const fromProps = Number(projectId)
    if (Number.isFinite(fromProps) && fromProps > 0) return fromProps
    const fromStore = Number(currentProjectId)
    return Number.isFinite(fromStore) && fromStore > 0 ? fromStore : 0
  })()

  const resolvedEpisodeId = (() => {
    const fromProps = Number(episodeId)
    if (Number.isFinite(fromProps) && fromProps > 0) return fromProps
    const fromStore = Number(currentEpisodeId)
    return Number.isFinite(fromStore) && fromStore > 0 ? fromStore : 0
  })()

  const modalZIndex = useStackedModalZIndex(open)
  const assetLibraryZIndex = modalZIndex + 100

  const modalTitle = TITLE_MAP[type] || '选择'

  const tabOptions = useMemo(() => {
    const base = TAB_OPTIONS_MAP[type] || { current: '本作品资产', step: '当前分镜' }
    const tabs: Record<string, string> = {
      current: base.current,
      step: stepTabName || base.step
    }
    if (showVoiceTab) tabs.voice = '音色'
    if (showVideoFrameTab) tabs.videoFrame = '视频帧'
    return tabs
  }, [type, stepTabName, showVoiceTab, showVideoFrameTab])

  const projectAssetGroups = useMemo(
    () =>
      buildProjectAssetGroups(
        type,
        {
          sceneCharacter,
          sceneImages,
          characterImages,
          propImages,
          characterForms,
          propForms,
          characterFormImages,
          propFormImages
        },
        storyboardScriptGroups
      ),
    [
      type,
      sceneCharacter,
      sceneImages,
      characterImages,
      propImages,
      characterForms,
      propForms,
      characterFormImages,
      propFormImages,
      storyboardScriptGroups
    ]
  )

  const categoryOptions = projectAssetGroups.map((g) => g.label)

  const [activeTab, setActiveTab] = useState<'current' | 'step' | 'voice' | 'videoFrame'>('current')

  function setActiveTabSafe(key: string) {
    if (
      key === 'step' ||
      key === 'current' ||
      (key === 'voice' && showVoiceTab) ||
      (key === 'videoFrame' && showVideoFrameTab)
    ) {
      setActiveTab(key as 'current' | 'step' | 'voice' | 'videoFrame')
    }
  }

  const [selectedCategoryIndex, setSelectedCategoryIndex] = useState(0)
  const [selectedList, setSelectedListState] = useState<any[]>([])
  const selectedListRef = useRef<any[]>([])
  function setSelectedList(next: any[] | ((prev: any[]) => any[])) {
    const value =
      typeof next === 'function'
        ? (next as (prev: any[]) => any[])(selectedListRef.current)
        : next
    selectedListRef.current = value
    setSelectedListState(value)
  }
  const [assetLoading, setAssetLoading] = useState(false)
  const assetLoadGenerationRef = useRef(0)
  const openRef = useRef(open)
  openRef.current = open

  const selectedOfficialVoiceIds = useMemo(() => {
    const set = new Set<string>()
    for (const item of selectedList) {
      if (item?.kind === 'audio' && item?.audioSource === 'voice_sample' && item?.id != null) {
        set.add(String(item.id).replace(/^voice-/, ''))
      }
    }
    return set
  }, [selectedList])

  function isPendingAudioPlaying(item: any): boolean {
    return pendingAudioPlayingId === rowKey(item)
  }

  function pendingAudioItems(): ReferenceMediaItem[] {
    return selectedListRef.current.filter((x) => isAudioPendingItem(x)) as ReferenceMediaItem[]
  }

  async function loadStep3AssetsIfNeeded() {
    const typesToLoad: Step3SelectAssetType[] =
      type === 'reference' || type === 'multiParamReference'
        ? ['scene', 'character', 'prop']
        : isStep3AssetType(type)
          ? [type]
          : []

    if (!typesToLoad.length) return
    const store = useCreationStore.getState()
    if (!typesToLoad.some((t) => step3SelectNeedsFetch(store, t))) return

    const gen = ++assetLoadGenerationRef.current
    setAssetLoading(true)
    const isStale = () => gen !== assetLoadGenerationRef.current
    try {
      for (const t of typesToLoad) {
        if (isStale()) break
        if (step3SelectNeedsFetch(useCreationStore.getState(), t)) {
          await ensureStep3AssetsForSelect(useCreationStore.getState(), route, t, { isStale })
        }
      }
    } finally {
      if (gen === assetLoadGenerationRef.current) setAssetLoading(false)
    }
  }

  const displayList = useMemo(() => {
    if (activeTab === 'step') {
      const imgs = stepPanelImages || []
      return imgs.map((img: any, i: number) => ({
        ...img,
        id: img.id || `step-panel-${i}-${img.url || img.thumbnail || i}`
      }))
    }
    const groups = projectAssetGroups
    const idx = Math.min(selectedCategoryIndex, Math.max(0, groups.length - 1))
    return groups[idx]?.images || []
  }, [activeTab, stepPanelImages, projectAssetGroups, selectedCategoryIndex])

  const emptyHint = (() => {
    if (activeTab === 'step') {
      return '当前分镜暂无分镜图，请先在第四步生图或上传'
    }
    if (type === 'reference' || type === 'multiParamReference') {
      return '暂无可选参考图，请先在第三步生成场景/角色/道具图，或在第四步上传分镜图'
    }
    return '该分类下暂无图片'
  })()

  const [assetLibraryOpen, setAssetLibraryOpen] = useState(false)
  const assetLibraryTitle = `从资源库选择 - ${modalTitle}`

  useEffect(() => {
    if (open) {
      setActiveTab('current')
      setSelectedCategoryIndex(0)
      setSelectedList([])
      void loadStep3AssetsIfNeeded()
    } else {
      setAssetLibraryOpen(false)
      assetLoadGenerationRef.current++
      setAssetLoading(false)
      setSelectedList([])
      stopPendingAudio()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  /** 作品/剧集切换时 store 会被清空；弹窗仍打开则重新拉当前上下文资产，避免展示旧作品残留 */
  const scopeMountedRef = useRef(false)
  useEffect(() => {
    if (!scopeMountedRef.current) {
      scopeMountedRef.current = true
      return
    }
    if (!openRef.current) return
    setSelectedCategoryIndex(0)
    setSelectedList([])
    void loadStep3AssetsIfNeeded()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    currentProjectId,
    currentEpisodeId,
    route.query.projectId,
    route.query.id,
    route.query.workId,
    route.query.episodeId
  ])

  /** 启用音色/视频帧 Tab 时保留「已导入素材」跨 Tab；仅纯图片模式沿用切换清空 */
  const prevTabRef = useRef(activeTab)
  useEffect(() => {
    const prev = prevTabRef.current
    prevTabRef.current = activeTab
    if (prev === activeTab) return
    if (showVoiceTab || showVideoFrameTab) return
    setSelectedList([])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab])

  useEffect(() => {
    if (open && selectedCategoryIndex >= projectAssetGroups.length) {
      setSelectedCategoryIndex(0)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectAssetGroups.length, open])

  function isSelected(item: any) {
    const k = rowKey(item)
    return selectedListRef.current.some((s) => rowKey(s) === k)
  }

  function toggleSelect(item: any) {
    const u = item.url || item.thumbnail
    if (!u && !item.id) return
    const k = rowKey(item)
    const idx = selectedListRef.current.findIndex((s) => rowKey(s) === k)
    if (idx >= 0) {
      setSelectedList((prev) => prev.filter((s) => rowKey(s) !== k))
    } else {
      setSelectedList((prev) => [...prev, { ...item }])
    }
  }

  async function removePendingItem(item: any) {
    const k = rowKey(item)
    if (isAudioPendingItem(item) && item.audioSource === 'upload' && Number(item.referenceAudioId) > 0) {
      try {
        await userReferenceAudioDelete({ id: Number(item.referenceAudioId) })
      } catch (e: unknown) {
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || '删除参考音频失败')
        return
      }
    }
    if (isPendingAudioPlaying(item)) stopPendingAudio()
    setSelectedList((prev) => prev.filter((s) => rowKey(s) !== k))
  }

  function previewPendingItem(item: any) {
    if (isAudioPendingItem(item)) {
      void playPendingAudio(String(item.url || ''), rowKey(item))
      return
    }
    const url = item?.url || item?.thumbnail
    if (!url) return
    openImagePreviewModal({
      url,
      title: item?.title || item?.name || '预览'
    })
  }

  function onOfficialVoiceSelect(voice: OfficialVoicePick) {
    const media = fromOfficialVoice({
      name: voice.name,
      previewUrl: voice.previewUrl,
      avatarUrl: voice.avatar,
      voiceLibraryId: voice.voiceLibraryId
    })
    const check = validateReferenceAudioAdd({
      capability: audioCapability,
      existing: pendingAudioItems(),
      next: { durationMs: media.durationMs, audioFormat: 'mp3' }
    })
    if (!check.ok) {
      message.warning('message' in check ? check.message : '参考音频不符合要求')
      return
    }
    const k = rowKey(media)
    if (selectedListRef.current.some((s) => rowKey(s) === k)) {
      message.info('该音色已在已导入素材中')
      return
    }
    setSelectedList((prev) => [...prev, media])
    message.success(`已选择音色：${voice.name}`)
  }

  function handleSelectLocalFile() {
    const input = document.createElement('input')
    const pickAudio = activeTab === 'voice' && showVoiceTab
    input.type = 'file'
    input.accept = pickAudio
      ? (audioCapability.referenceAudioFormats.length
          ? audioCapability.referenceAudioFormats.map((f) => `.${f}`).join(',')
          : '.wav,.mp3')
      : 'image/*'
    input.multiple = !pickAudio
    input.onchange = (e: Event) => {
      const files = (e.target as HTMLInputElement).files
      if (!files?.length) return
      void (async () => {
        if (pickAudio) {
          await uploadLocalReferenceAudios({
            files: Array.from(files),
            projectId: Number(projectId),
            episodeId,
            capability: audioCapability,
            getPendingAudioItems: pendingAudioItems,
            appendItem: (media) => setSelectedList((prev) => [...prev, media])
          })
          return
        }
        const { uploadImagesToOssWithToast } = await import('~/utils/ossUpload')
        const list = Array.from(files)
        const urls = await uploadImagesToOssWithToast(list)
        if (!urls) return
        const now = new Date().toISOString()
        for (let i = 0; i < urls.length; i++) {
          const url = urls[i]!
          const file = list[i]!
          const name =
            file.name.replace(/\.[^/.]+$/, '') || `图片${selectedListRef.current.length + 1}`
          setSelectedList((prev) => [
            ...prev,
            {
              id: `local-${Date.now()}-${i}`,
              url,
              thumbnail: url,
              title: name,
              importDate: now,
              name,
              source: '本地上传',
              kind: 'image'
            }
          ])
        }
        message.success(`已添加 ${urls.length} 张图片`)
      })()
    }
    input.click()
  }

  function handleOpenAssetLibrary() {
    setAssetLibraryOpen(true)
  }

  function handleAssetLibraryImportMultiple(items: any[]) {
    if (Array.isArray(items) && items.length) {
      items.forEach((asset) => {
        const url = asset.url || asset.thumbnail
        if (!url) return
        const id = asset.id || `lib-${Date.now()}-${Math.random().toString(36).slice(2)}`
        if (selectedListRef.current.some((s) => s.id === id)) return
        setSelectedList((prev) => [
          ...prev,
          {
            id,
            url,
            thumbnail: url,
            title: asset.name || asset.title,
            name: asset.name,
            importDate: asset.updatedAt || new Date().toISOString(),
            source: '资源库导入'
          }
        ])
      })
      message.success(`已添加 ${items.length} 项`)
    }
    setAssetLibraryOpen(false)
  }

  function handleCancel() {
    setAssetLibraryOpen(false)
    onOpenChange(false)
  }

  function handleConfirm() {
    if (selectedListRef.current.length === 0) {
      message.warning('请至少选择一项')
      return
    }
    stopPendingAudio()
    onConfirm([...selectedListRef.current])
    onOpenChange(false)
  }

  return (
    <Modal
      open={open}
      width={1050}
      footer={null}
      title={null}
      closable={false}
      zIndex={modalZIndex}
      className="select-asset-image-modal select-asset-image-modal--figma"
      wrapClassName="create-flow-modal select-asset-image-modal-wrap"
      onCancel={handleCancel}
    >
      <div className="saim-inner">
        <header className="saim-header">
          <h2 className="saim-title">{modalTitle}</h2>
          <div className="saim-header-actions">
            <button type="button" className="saim-icon-btn" aria-label="关闭" onClick={handleCancel}>
              <CloseOutlined />
            </button>
          </div>
        </header>
        <div className="content_box">
          {/* 与 EditStoryboardImageModal 右侧一致的居中分段 Tab */}
          <div className="saim-header-tabs" role="tablist" aria-label="资产来源">
            <div className="config-tabs config-tabs--three saim-config-tabs">
              {Object.entries(tabOptions).map(([key, tab]) => (
                <button
                  key={key}
                  type="button"
                  role="tab"
                  aria-selected={activeTab === key}
                  className={`config-tab${activeTab === key ? ' active' : ''}`}
                  onClick={() => setActiveTabSafe(key)}
                >
                  {tab}
                </button>
              ))}
            </div>
          </div>

          {/* 音色 Tab：官方音色列表 */}
          {activeTab === 'voice' ? (
            <div className="saim-body-shell saim-body-shell--voice">
              <SelectAssetVoiceTab
                open={open && activeTab === 'voice'}
                selectedIds={selectedOfficialVoiceIds}
                onSelect={onOfficialVoiceSelect}
              />
            </div>
          ) : activeTab === 'videoFrame' ? (
            /* 视频帧 Tab：截帧与持久化均由独立子组件负责，父级只复用选中逻辑 */
            <div className="saim-body-shell saim-body-shell--video-frame">
              <SelectAssetVideoFrameTab
                open={open && activeTab === 'videoFrame'}
                projectId={resolvedProjectId}
                episodeId={resolvedEpisodeId}
                isSelected={isSelected}
                modalZIndex={modalZIndex + 100}
                onToggle={toggleSelect}
              />
            </div>
          ) : (
            /* 左侧分类 + 右侧网格 */
            <div className="saim-body-shell">
              <div className="saim-content-row">
                <aside className="saim-sidebar" aria-label="资产分类">
                  <div className="saim-sidebar-scroll">
                    {categoryOptions.map((cat, idx) => (
                      <button
                        key={idx}
                        type="button"
                        className={`saim-cat${selectedCategoryIndex === idx ? ' saim-cat--active' : ''}`}
                        onClick={() => setSelectedCategoryIndex(idx)}
                        title={cat}
                      >
                        {cat}
                      </button>
                    ))}
                  </div>
                </aside>

                <div className="saim-main">
                  <div className="saim-main-scroll">
                    {assetLoading ? (
                      <div className="saim-empty">
                        <Spin size="large" />
                        <p className="saim-empty__text">正在加载本作品资产…</p>
                      </div>
                    ) : displayList.length === 0 ? (
                      <div className="saim-empty">
                        <img
                          src={emptyImageIconUrl}
                          alt=""
                          className="empty-image-icon empty-image-icon--md saim-empty__ico-img"
                          aria-hidden="true"
                        />
                        <p className="saim-empty__text">{emptyHint}</p>
                      </div>
                    ) : (
                      <div className="saim-grid">
                        {displayList.map((item: any, index: number) => (
                          <div
                            key={item.id || index}
                            className={`saim-card${isSelected(item) ? ' saim-card--selected' : ''}`}
                            role="button"
                            tabIndex={0}
                            onClick={() => toggleSelect(item)}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter') {
                                e.preventDefault()
                                toggleSelect(item)
                              }
                            }}
                          >
                            <div className="saim-card__media">
                              {item.url || item.thumbnail ? (
                                <ShimmerImage
                                  src={String(item.url || item.thumbnail || '')}
                                  imgClass="saim-card__img"
                                  wrapperClass="saim-card__shimmer"
                                  objectFit="cover"
                                  revealDirection="fade"
                                />
                              ) : (
                                <div className="saim-card__placeholder">
                                  <img
                                    src={emptyImageIconUrl}
                                    alt=""
                                    className="empty-image-icon empty-image-icon--sm"
                                  />
                                </div>
                              )}
                              <img
                                className="saim-card-select"
                                src={isSelected(item) ? dialogSelectSelIcon : dialogSelectNorIcon}
                                alt=""
                              />
                            </div>
                            <div className="saim-card__meta">
                              {formatDate(item.importDate || item.updatedAt)}
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>

        <footer className="saim-footer">
          <PendingImportList
            selectedList={selectedList}
            isPendingAudioPlaying={isPendingAudioPlaying}
            onPreview={previewPendingItem}
            onRemove={removePendingItem}
          />
          <div className="saim-footer__row">
            <div className="saim-footer__left">
              <Button className="saim-btn-tool" icon={<UploadOutlined />} onClick={handleSelectLocalFile}>
                选择本地文件
              </Button>
              <Button className="saim-btn-tool" icon={<FolderOutlined />} onClick={handleOpenAssetLibrary}>
                资产库导入
              </Button>
            </div>
            <div className="saim-footer__right">
              <Button className="saim-btn-cancel" onClick={handleCancel}>
                <div className="text-gradient">取消</div>
              </Button>
              <Button
                type="primary"
                className="saim-btn-ok"
                disabled={selectedList.length === 0}
                onClick={handleConfirm}
              >
                确定
              </Button>
            </div>
          </div>
        </footer>
      </div>

      {/* 资产库多选弹窗 */}
      <ImportScriptModal
        open={assetLibraryOpen}
        onOpenChange={setAssetLibraryOpen}
        title={assetLibraryTitle}
        multiple={true}
        acceptAssetType="image"
        zIndex={assetLibraryZIndex}
        onImport={() => {}}
        onImportMultiple={handleAssetLibraryImportMultiple}
      />
    </Modal>
  )
}

export default SelectAssetImageModal
