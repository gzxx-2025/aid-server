'use client'

import { useEffect, useRef, useState } from 'react'
import { Button, Modal, message } from 'antd'
import { UploadOutlined, FolderOutlined, FileTextOutlined, CloseOutlined } from '@ant-design/icons'
import ModalTitleWatermark from '~/components/ModalTitleWatermark'
import ImportScriptModal from './ImportScriptModal'
import { useCreationStore } from '~/stores/creation'
import { userAssetRpsFormList, userProjectList } from '~/utils/businessApi'
import type { UserAssetApiType } from '~/types/business-api'
import { assetUrl } from '~/utils/assetUrl'
import { useStackedModalZIndex } from '~/hooks/useStackedModalZIndex'
import { invokeImportModalCallback } from '~/utils/importModalCallback'
import PreviewableImageThumb from '~/components/common/PreviewableImageThumb'
import noDataRaw from '@/assets/img/icon/no_data.svg'
import emptyIconRaw from '@/assets/img/icon/empty_icon.svg'
import dialogSelectNorRaw from '@/assets/img/icon/dialog-select-nor.svg'
import dialogSelectSelRaw from '@/assets/img/icon/dialog-select-sel.svg'
import './ImportSceneImageModal.css'

const noDataIconUrl = assetUrl(noDataRaw)
const emptyIconUrl = assetUrl(emptyIconRaw)
const dialogSelectNorIcon = assetUrl(dialogSelectNorRaw)
const dialogSelectSelIcon = assetUrl(dialogSelectSelRaw)

export interface ImportSceneImageModalProps {
  open: boolean
  assetType?: UserAssetApiType
  title?: string
  onOpenChange: (open: boolean) => void
  onImport: (
    asset: File | string | Record<string, any>
  ) => void | boolean | Promise<void | boolean>
}

const titleMap: Record<string, string> = {
  scene: '导入场景',
  character: '导入角色',
  prop: '导入道具'
}

const countLabelMap: Record<string, string> = {
  scene: '场景数',
  character: '角色数',
  prop: '道具数'
}

function pickThumb(form: any): string {
  const imgs = Array.isArray(form?.images) ? form.images : []
  const inUse = imgs.find((x: any) => Number(x?.isUse) === 1)
  const latest = imgs[0]
  return inUse?.imageUrl || latest?.imageUrl || form?.imageUrl || ''
}

export function ImportSceneImageModal({
  open,
  assetType = 'scene',
  title = '导入场景',
  onOpenChange,
  onImport
}: ImportSceneImageModalProps) {
  const [activeSubTab, setActiveSubTab] = useState<'current' | 'history'>('current')
  const [selectedAsset, setSelectedAsset] = useState<any>(null)
  const [selectedSceneId, setSelectedSceneId] = useState<string | null>(null)
  const [showAssetLibraryModal, setShowAssetLibraryModal] = useState(false)
  const [scenesList, setScenesList] = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const modalZIndex = useStackedModalZIndex(open)
  const assetLibraryZIndex = modalZIndex + 100

  const countLabel = countLabelMap[assetType] || '资产数'

  async function loadCurrentAssets(): Promise<any[]> {
    const creationStore = useCreationStore.getState()
    const projectId = creationStore.currentProjectId
    if (!projectId) {
      return []
    }
    const payload: Record<string, any> = {
      projectId,
      assetType
    }
    if (creationStore.currentEpisodeId != null && creationStore.currentEpisodeId >= 0) {
      payload.episodeId = creationStore.currentEpisodeId
    }
    const rows = await userAssetRpsFormList(payload as any)
    return rows
      .map((row: any, index: number) => {
        const thumbnail = pickThumb(row)
        return {
          id: `current-${row.id}-${index}`,
          name: row.name || `${countLabel.replace('数', '')}${index + 1}`,
          thumbnail,
          url: thumbnail,
          rpsFormId: row.id,
          rpsImageId: row.currentImageId,
          type: assetType
        }
      })
      .filter((x: any) => !!x.thumbnail)
  }

  async function loadHistoryAssets(): Promise<any[]> {
    const { rows: projects } = await userProjectList({ pageNum: 1, pageSize: 50 })
    const currentProjectId = Number(useCreationStore.getState().currentProjectId || 0)
    const historyProjects = (projects || []).filter((p) => Number(p.id) !== currentProjectId)
    if (!historyProjects.length) {
      return []
    }
    const formBatches = await Promise.all(
      historyProjects.map(async (p) => {
        try {
          const forms = await userAssetRpsFormList({
            projectId: Number(p.id),
            assetType
          } as any)
          return forms.map((f: any, index: number) => {
            const thumbnail = pickThumb(f)
            return {
              id: `history-${p.id}-${f.id}-${index}`,
              name: f.name || `${countLabel.replace('数', '')}${index + 1}`,
              thumbnail,
              url: thumbnail,
              projectName: p.projectName || `项目${p.id}`,
              rpsFormId: f.id,
              rpsImageId: f.currentImageId,
              type: assetType
            }
          })
        } catch {
          return []
        }
      })
    )
    return formBatches.flat().filter((x: any) => !!x.thumbnail)
  }

  async function loadScenesByTab(tab: 'current' | 'history') {
    setLoading(true)
    try {
      const list = tab === 'current' ? await loadCurrentAssets() : await loadHistoryAssets()
      setScenesList(list)
    } catch (e: any) {
      setScenesList([])
      message.error(e?.msg || e?.message || `${titleMap[assetType] || '资产'}加载失败`)
    } finally {
      setLoading(false)
    }
  }

  const selectScene = (scene: any) => {
    setSelectedSceneId(scene.id)
    setSelectedAsset({ ...scene, type: assetType })
    message.success(`${titleMap[assetType] || '资产'}已选择`)
  }

  async function importAsset(asset: File | string | Record<string, any>): Promise<boolean> {
    const result = await invokeImportModalCallback(onImport, asset)
    if (result.error) {
      const cause = result.error as { msg?: string; message?: string }
      message.error(cause?.msg || cause?.message || '导入失败，请重试')
    }
    return result.accepted
  }

  const handleSelectLocalFile = () => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = 'image/*'
    input.onchange = async (e: Event) => {
      const t = e.target as HTMLInputElement
      const file = t.files?.[0]
      if (file) {
        if (!(await importAsset(file))) return
        onOpenChange(false)
        setSelectedAsset(null)
        setSelectedSceneId(null)
        setShowAssetLibraryModal(false)
        message.success('导入成功')
      }
    }
    input.click()
  }

  const handleOpenAssetLibrary = () => {
    setShowAssetLibraryModal(true)
  }

  const handleDirectImport = async (asset: any) => {
    const imageUrl = String(asset?.url || asset?.thumbnail || '').trim()
    if (!imageUrl) {
      message.warning('未获取到可用图片地址')
      return false
    }
    if (!(await importAsset({ ...asset, url: imageUrl, thumbnail: imageUrl }))) return false
    setShowAssetLibraryModal(false)
    onOpenChange(false)
    setSelectedAsset(null)
    setSelectedSceneId(null)
    return true
  }

  const handleConfirm = async () => {
    if (!selectedAsset) {
      message.warning('请选择要导入的图片或场景')
      return
    }

    let payload: File | string | Record<string, any> | null = null
    if (selectedAsset.type === 'local') {
      payload = selectedAsset.file
    } else if (selectedAsset.type === 'library') {
      payload = selectedAsset.url
        ? { url: selectedAsset.url, name: selectedAsset.name || '导入的图片' }
        : { ...selectedAsset }
    } else if (
      selectedAsset.type === 'scene' ||
      selectedAsset.type === 'character' ||
      selectedAsset.type === 'prop'
    ) {
      payload = selectedAsset
    }

    if (!payload || !(await importAsset(payload))) return

    setShowAssetLibraryModal(false)
    onOpenChange(false)
    setSelectedAsset(null)
    setSelectedSceneId(null)
    message.success('导入成功')
  }

  const handleCancel = () => {
    onOpenChange(false)
    setSelectedAsset(null)
    setSelectedSceneId(null)
    setShowAssetLibraryModal(false)
  }

  useEffect(() => {
    if (!open) {
      // The asset library is a separate portal and cannot remain open after its owner closes.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setShowAssetLibraryModal(false)
      return
    }
    setSelectedAsset(null)
    setSelectedSceneId(null)
    void loadScenesByTab(activeSubTab)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const tabMountedRef = useRef(false)
  useEffect(() => {
    // 原 watch(activeSubTab) 非 immediate：跳过首次执行
    if (!tabMountedRef.current) {
      tabMountedRef.current = true
      return
    }
    if (!open) return
    setSelectedAsset(null)
    setSelectedSceneId(null)
    void loadScenesByTab(activeSubTab)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSubTab])

  return (
    <Modal
      open={open}
      width={1100}
      footer={null}
      title={null}
      closable={false}
      centered
      zIndex={modalZIndex}
      className="import-scene-image-modal"
      wrapClassName="create-flow-modal import-scene-image-modal-wrap"
      onCancel={handleCancel}
    >
      <div className="import-scene-shell">
        <header className="import-scene-header">
          <ModalTitleWatermark title={title} watermark="IMPORT" />
          <button type="button" className="import-scene-close" aria-label="关闭" onClick={handleCancel}>
            <CloseOutlined />
          </button>
        </header>

        <div className="import-body">
          <div className="import-tab-bar">
            <div></div>
            <div className="import-tab-bar__inner">
              <button
                type="button"
                className={`import-tab${activeSubTab === 'current' ? ' import-tab--active' : ''}`}
                onClick={() => setActiveSubTab('current')}
              >
                本作品资产
              </button>
              <button
                type="button"
                className={`import-tab${activeSubTab === 'history' ? ' import-tab--active' : ''}`}
                onClick={() => setActiveSubTab('history')}
              >
                历史作品资产
              </button>
            </div>
            <span className="import-tab-bar__count">
              {countLabel}：{scenesList.length}项
            </span>
          </div>

          <div className="import-content">
            {loading ? (
              <div className="import-empty">
                <img src={noDataIconUrl} alt="" />
                <p className="import-empty__text">加载中...</p>
              </div>
            ) : scenesList.length > 0 ? (
              <div className="import-scenes-row" role="listbox" aria-label="可导入图片">
                {scenesList.map((scene) => {
                  const selected = selectedSceneId === scene.id
                  return (
                    <article
                      key={scene.id}
                      className={`import-scene-card${selected ? ' import-scene-card--selected' : ''}`}
                      role="option"
                      aria-selected={selected}
                      tabIndex={0}
                      onClick={() => selectScene(scene)}
                      onKeyDown={(event) => {
                        if (event.key !== 'Enter' && event.key !== ' ') return
                        event.preventDefault()
                        selectScene(scene)
                      }}
                    >
                      <div className="import-scene-card__thumb">
                        {scene.thumbnail ? (
                          <PreviewableImageThumb
                            src={scene.thumbnail}
                            alt={scene.name}
                            title={scene.name}
                            objectFit="contain"
                          />
                        ) : (
                          <div className="import-scene-card__placeholder">
                            <FileTextOutlined />
                          </div>
                        )}
                      </div>
                      <div className="import-scene-card__label">
                        <span className="import-scene-card__name">{scene.name}</span>
                      </div>
                      <button
                        type="button"
                        className="import-scene-card__select"
                        aria-label={selected ? `已选择${scene.name}` : `选择${scene.name}`}
                        aria-pressed={selected}
                        onClick={(event) => {
                          event.stopPropagation()
                          selectScene(scene)
                        }}
                      >
                        <img
                          src={selected ? dialogSelectSelIcon : dialogSelectNorIcon}
                          alt=""
                          aria-hidden="true"
                        />
                      </button>
                    </article>
                  )
                })}
              </div>
            ) : (
              <div className="import-empty">
                <img src={emptyIconUrl} alt="" />
                <p className="import-empty__text">暂无可导入资产</p>
              </div>
            )}
          </div>
        </div>

        <div className="import-footer">
          <div className="import-footer__left">
            <Button className="import-btn-dashed" icon={<UploadOutlined />} onClick={handleSelectLocalFile}>
              选择本地文件
            </Button>
            <Button className="import-btn-dashed" icon={<FolderOutlined />} onClick={handleOpenAssetLibrary}>
              资产库导入
            </Button>
          </div>
          <div className="import-footer__right">
            <Button className="import-btn-cancel" size="large" onClick={handleCancel}>
              <div className="text-gradient">取消</div>
            </Button>
            <Button
              type="primary"
              size="large"
              className="import-btn-ok"
              disabled={!selectedAsset}
              onClick={() => void handleConfirm()}
            >
              确定
            </Button>
          </div>
        </div>
      </div>

      <ImportScriptModal
        open={showAssetLibraryModal}
        onOpenChange={setShowAssetLibraryModal}
        title="导入图片"
        acceptAssetType="image"
        zIndex={assetLibraryZIndex}
        onImport={handleDirectImport}
      />
    </Modal>
  )
}

export default ImportSceneImageModal
