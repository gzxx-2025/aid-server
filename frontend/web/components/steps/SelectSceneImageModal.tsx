'use client'

import { useEffect, useRef, useState } from 'react'
import { Button, Modal, message } from 'antd'
import { UploadOutlined, FolderOutlined, CloseOutlined } from '@ant-design/icons'
import HorizontalScrollTabBar, {
  type HorizontalScrollTabBarHandle
} from '~/components/common/HorizontalScrollTabBar'
import ModalTitleWatermark from '~/components/ModalTitleWatermark'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { noDataIconUrl as noDataIconRaw } from '~/utils/emptyImageIcon'
import ImportScriptModal from './ImportScriptModal'
import { uploadImageToOssWithToast } from '~/utils/ossUpload'
import { assetUrl } from '~/utils/assetUrl'
import { useStackedModalZIndex } from '~/hooks/useStackedModalZIndex'
import './SelectSceneImageModal.css'

const noDataIconUrl = assetUrl(noDataIconRaw)

export interface SelectSceneImageModalProps {
  open: boolean
  scenes: Array<{ name: string; images?: any[] }>
  /** 当前正在编辑的场景索引 */
  editingSceneIndex: number
  /** 是否多选（默认单选） */
  multiple?: boolean
  title?: string
  onOpenChange: (open: boolean) => void
  onSelect?: (sceneIndex: number, imageIndex: number, image: any) => void
  onSelectMultiple?: (payload: { sceneIndex: number; images: any[] }) => void
}

// 格式化日期
const formatDate = (dateString: string) => {
  if (!dateString) return ''
  const date = new Date(dateString)
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

export function SelectSceneImageModal({
  open,
  scenes,
  editingSceneIndex,
  multiple = false,
  title = '导入场景',
  onOpenChange,
  onSelect,
  onSelectMultiple
}: SelectSceneImageModalProps) {
  const sceneTabBarRef = useRef<HorizontalScrollTabBarHandle | null>(null)

  function refreshSceneTabBar() {
    sceneTabBarRef.current?.refresh()
  }

  function scrollActiveSceneTabIntoView() {
    requestAnimationFrame(() => {
      sceneTabBarRef.current?.scrollItemIntoView('.import-tab--active')
      sceneTabBarRef.current?.refresh()
    })
  }

  const [currentSceneIndex, setCurrentSceneIndex] = useState(editingSceneIndex)
  const [selectedImageIndex, setSelectedImageIndex] = useState<number | null>(null)
  const [selectedImageIndexSet, setSelectedImageIndexSet] = useState<Set<number>>(new Set())
  const isMulti = !!multiple
  const [showAssetLibraryModal, setShowAssetLibraryModal] = useState(false)
  const [extraImagesBySceneIndex, setExtraImagesBySceneIndex] = useState<Record<number, any[]>>({})
  const modalZIndex = useStackedModalZIndex(open)
  const assetLibraryZIndex = modalZIndex + 100

  const currentSceneImages = [
    ...(scenes[currentSceneIndex]?.images || []),
    ...(extraImagesBySceneIndex[currentSceneIndex] || [])
  ]

  // 切换场景
  const switchScene = (index: number) => {
    setCurrentSceneIndex(index)
    setSelectedImageIndex(null) // 切换场景时清空选择
    setSelectedImageIndexSet(new Set())
    scrollActiveSceneTabIntoView()
  }

  useEffect(() => {
    if (open) {
      requestAnimationFrame(() => {
        refreshSceneTabBar()
        scrollActiveSceneTabIntoView()
      })
    }
     
  }, [open, scenes.length])

  // 选择图片
  const selectImage = (index: number) => {
    if (isMulti) {
      setSelectedImageIndexSet((prev) => {
        const next = new Set(prev)
        if (next.has(index)) next.delete(index)
        else next.add(index)
        return next
      })
      return
    }
    setSelectedImageIndex(index)
  }

  // 取消
  const handleCancel = () => {
    setShowAssetLibraryModal(false)
    onOpenChange(false)
    setSelectedImageIndex(null)
    setSelectedImageIndexSet(new Set())
  }

  // 确认选择
  const handleConfirm = () => {
    if (isMulti) {
      if (selectedImageIndexSet.size === 0) {
        message.warning('请选择要导入的图片')
        return
      }
      const indices = Array.from(selectedImageIndexSet).sort((a, b) => a - b)
      const images = indices.map((i) => currentSceneImages[i]).filter(Boolean)
      if (!images.length) {
        message.error('选择的图片不存在')
        return
      }
      onSelectMultiple?.({ sceneIndex: currentSceneIndex, images })
      onOpenChange(false)
      setSelectedImageIndexSet(new Set())
      return
    }

    if (selectedImageIndex === null) {
      message.warning('请选择要添加的场景图')
      return
    }

    const image = currentSceneImages[selectedImageIndex]
    if (!image) {
      message.error('选择的场景图不存在')
      return
    }

    // 发送选择事件：场景索引、图片索引、图片数据
    onSelect?.(currentSceneIndex, selectedImageIndex, image)
    onOpenChange(false)
    setSelectedImageIndex(null)
    message.success('场景图已选择')
  }

  const handleUploadLocalImage = () => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = 'image/*'
    input.onchange = async (e: Event) => {
      const target = e.target as HTMLInputElement
      const file = target.files?.[0]
      if (!file) return

      const url = await uploadImageToOssWithToast(file)
      if (!url) return

      const now = new Date().toISOString()
      setExtraImagesBySceneIndex((prev) => {
        const list = prev[currentSceneIndex] || []
        const total = (scenes[currentSceneIndex]?.images?.length || 0) + list.length
        return {
          ...prev,
          [currentSceneIndex]: [
            ...list,
            {
              id: `local-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
              url,
              thumbnail: url,
              title: file.name.replace(/\.[^/.]+$/, '') || `场景图${total + 1}`,
              source: '本地上传',
              importDate: now
            }
          ]
        }
      })
      message.success('本地图片已添加')
    }
    input.click()
  }

  const handleAssetLibraryImport = (asset: any) => {
    const imageUrl = asset?.url || asset?.thumbnail
    if (!imageUrl) {
      message.warning('未获取到可用图片地址')
      return false
    }

    const now = new Date().toISOString()
    setExtraImagesBySceneIndex((prev) => {
      const list = prev[currentSceneIndex] || []
      const total = (scenes[currentSceneIndex]?.images?.length || 0) + list.length
      return {
        ...prev,
        [currentSceneIndex]: [
          ...list,
          {
            id: `asset-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
            url: imageUrl,
            thumbnail: imageUrl,
            title: asset?.name || `场景图${total + 1}`,
            source: '资产库导入',
            importDate: now
          }
        ]
      }
    })
    setShowAssetLibraryModal(false)
    message.success('已从资产库添加图片')
    return true
  }

  useEffect(() => {
    if (open) return
    // Nested asset-library portals must close with the reference picker that owns them.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setShowAssetLibraryModal(false)
  }, [open])

  return (
    <Modal
      open={open}
      width={1200}
      footer={null}
      title={null}
      closable={false}
      centered
      zIndex={modalZIndex}
      className="select-scene-image-modal"
      wrapClassName="create-flow-modal select-scene-image-modal-wrap"
      onCancel={handleCancel}
    >
      <div className="select-scene-shell">
        {/* 头部：标题和场景切换器 */}
        <header className="modal-header">
          <div className="header-top">
            <ModalTitleWatermark title={title} watermark="IMPORT" />
            <button type="button" className="select-scene-close" aria-label="关闭" onClick={handleCancel}>
              <CloseOutlined />
            </button>
          </div>
          {/* 场景切换器：显示所有场景，当前场景高亮 */}
          <div className="header-tabs">
            <div className="import-tab-bar__inner">
              <HorizontalScrollTabBar
                ref={sceneTabBarRef}
                trackClass="import-tab-inner"
                suffix={<div className="scene-count">场景数: {scenes.length}项</div>}
              >
                {scenes.map((scene, index) => (
                  <button
                    key={index}
                    type="button"
                    className={`import-tab${currentSceneIndex === index ? ' import-tab--active' : ''}`}
                    onClick={() => switchScene(index)}
                  >
                    <span className="scene-tab-name">{scene.name || `未命名${index + 1}`}</span>
                  </button>
                ))}
              </HorizontalScrollTabBar>
            </div>
          </div>
        </header>

        {/* 内容区域：显示当前选中场景的场景图列表 */}
        <div className="select-scene-body">
          <div className="import-container">
            <div className="content-area">
              {currentSceneImages.length === 0 ? (
                <div className="empty-state">
                  <div className="empty-icon-wrapper">
                    <img src={noDataIconUrl} alt="" className="empty-image-icon empty-image-icon--xl" />
                  </div>
                  <p className="empty-text">该场景暂无场景图</p>
                </div>
              ) : (
                <div className="images-grid">
                  {currentSceneImages.map((img, index) => (
                    <div
                      key={img.id || index}
                      className={`image-card${
                        (isMulti ? selectedImageIndexSet.has(index) : selectedImageIndex === index)
                          ? ' selected'
                          : ''
                      }`}
                      onClick={() => selectImage(index)}
                    >
                      <div className="image-wrapper">
                        {img.url ? (
                          <ShimmerImage
                            src={img.url}
                            imgClass="scene-image"
                            wrapperClass="scene-image-shimmer"
                            objectFit="cover"
                            revealDirection="fade"
                          />
                        ) : (
                          <div className="image-placeholder">
                            <img
                              src={noDataIconUrl}
                              alt=""
                              className="empty-image-icon empty-image-icon--md"
                            />
                            <p>暂无图片</p>
                          </div>
                        )}
                      </div>
                      <div className="image-info">
                        <p className="image-title">{img.title || `场景图${index + 1}`}</p>
                        <div className="image-meta">
                          {img.source && <span className="image-source">{img.source}</span>}
                          {img.importDate && (
                            <span className="image-date">{formatDate(img.importDate)}</span>
                          )}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* 底部操作栏 */}
        <footer className="modal-footer">
          <div className="footer-left">
            <Button icon={<UploadOutlined />} onClick={handleUploadLocalImage}>
              选择本地文件
            </Button>
            <Button icon={<FolderOutlined />} onClick={() => setShowAssetLibraryModal(true)}>
              资产库导入
            </Button>
          </div>
          <div className="footer-right">
            <Button onClick={handleCancel}>取消</Button>
            <Button
              type="primary"
              onClick={handleConfirm}
              disabled={isMulti ? selectedImageIndexSet.size === 0 : selectedImageIndex === null}
            >
              {isMulti ? '导入图片' : '选择此场景图'}
            </Button>
          </div>
        </footer>
      </div>

      <ImportScriptModal
        open={showAssetLibraryModal}
        onOpenChange={setShowAssetLibraryModal}
        title="导入图片"
        acceptAssetType="image"
        zIndex={assetLibraryZIndex}
        onImport={handleAssetLibraryImport}
      />
    </Modal>
  )
}

export default SelectSceneImageModal
