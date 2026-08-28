'use client'

import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react'
import { Button, Image, Modal } from 'antd'
import { CloseOutlined } from '@ant-design/icons'
import { usePromptDictionary } from '~/composables/usePromptDictionary'
import { assetUrl } from '~/utils/assetUrl'
import dialogAddIconRaw from '~/assets/img/icon/dialog-add.svg'
import dialogSelectNorIconRaw from '~/assets/img/icon/dialog-select-nor.svg'
import dialogSelectSelIconRaw from '~/assets/img/icon/dialog-select-sel.svg'
import {
  extractReferencedAssetIdsFromHtml,
  type PromptAssetItem
} from '~/utils/storyboardPromptAssetRef'
import {
  buildGridItems,
  categoryDefaultNames,
  cloneList,
  computeDraftPromptAssets,
  createEmptyParamSettingsDraft,
  type AssetCategoryKey,
  type GridImageItem,
  type ParamSettingsConfirmPayload,
  type ParamSettingsDraftState,
  type ParamSettingsMode
} from './storyboard-param-settings/paramSettingsDraft'
import ParamSettingsRightPanel from './storyboard-param-settings/ParamSettingsRightPanel'
import './StoryboardParamSettingsModal.css'

const dialogAddIcon = assetUrl(dialogAddIconRaw)
const dialogSelectNorIcon = assetUrl(dialogSelectNorIconRaw)
const dialogSelectSelIcon = assetUrl(dialogSelectSelIconRaw)

export type { ParamSettingsConfirmPayload }

interface Props {
  open: boolean
  mode: ParamSettingsMode
  sceneImages?: any[]
  characterImages?: any[]
  propImages?: any[]
  otherImages?: any[]
  nineGridEnabled?: boolean
  referenceImage?: { id?: string; url?: string; thumbnail?: string; title?: string } | null
  referenceImages?: any[]
  selectedComposition?: { key: string; value: string } | null
  selectedShotSize?: { key: string; value: string } | null
  selectedCameraAngle?: { key: string; value: string } | null
  selectedFocalLength?: { key: string; value: string } | null
  selectedColorTone?: { key: string; value: string } | null
  selectedLighting?: { key: string; value: string } | null
  selectedTechnique?: { key: string; value: string } | null
  compositionDesc?: string
  activeSettingKey?: string | null
  selectedCameraMovement?: { key: string; value: string } | null
  cameraMovementDesc?: string
  selectedShootingTechnique?: { key: string; value: string } | null
  activeVideoSettingKey?: string | null
  /** 多参灵感空间内展示的图生视频参数 */
  imageToVideoNineGridEnabled?: boolean
  imageToVideoReferenceImages?: any[]
  imageToVideoSelectedCameraMovement?: { key: string; value: string } | null
  imageToVideoCameraMovementDesc?: string
  imageToVideoSelectedShootingTechnique?: { key: string; value: string } | null
  imageToVideoActiveVideoSettingKey?: string | null
  /** 主描述框 HTML，用于恢复左侧图片选中态 */
  prompt?: string
  extraPromptAssets?: PromptAssetItem[]
  onOpenChange: (value: boolean) => void
  onOpenSelectModal?: (type: AssetCategoryKey | 'other') => void
  onPreviewAssetImage?: (img: any) => void
  onImportReference?: () => void
  onPreviewReference?: () => void
  onToggleAssetRef?: (payload: { asset: PromptAssetItem; selected: boolean }) => void
  onSyncAssetRefs?: (assets: PromptAssetItem[]) => void
  onConfirm?: (payload: ParamSettingsConfirmPayload) => void
}

/** 原 defineExpose 能力（forwardRef + useImperativeHandle） */
export interface StoryboardParamSettingsModalHandle {
  isOpen: () => boolean
  appendDraftImages: (type: AssetCategoryKey | 'other', items: any[]) => void
  appendDraftReferences: (items: any[]) => void
  setDraftReference: (item: any) => void
}

const uploadCategories = [
  { key: 'scene' as const, label: '导入场景' },
  { key: 'character' as const, label: '导入角色' },
  { key: 'prop' as const, label: '导入道具' },
  { key: 'pose' as const, label: '导入姿态图' },
  { key: 'expression' as const, label: '导入表情图' },
  { key: 'effect' as const, label: '导入特效图' },
  { key: 'draft' as const, label: '导入手绘图' }
]

export const StoryboardParamSettingsModal = forwardRef<StoryboardParamSettingsModalHandle, Props>(
  function StoryboardParamSettingsModal(props, ref) {
    const {
      open,
      mode,
      onOpenChange,
      onOpenSelectModal,
      onPreviewAssetImage,
      onImportReference,
      onPreviewReference,
      onToggleAssetRef,
      onSyncAssetRefs,
      onConfirm
    } = props

    /** 草稿态整体放一个对象：ref 镜像供 defineExpose 方法 / 异步回调读最新值 */
    const draftRef = useRef<ParamSettingsDraftState>(createEmptyParamSettingsDraft())
    const [draft, setDraftState] = useState<ParamSettingsDraftState>(draftRef.current)
    const patchDraft = (patch: Partial<ParamSettingsDraftState>) => {
      draftRef.current = { ...draftRef.current, ...patch }
      setDraftState(draftRef.current)
    }

    /** 事件回调内读最新 props（imperative 方法可能在渲染间隙被父级调用） */
    const propsRef = useRef(props)
    propsRef.current = props

    const enableDraftPromptAssetRefs =
      mode === 'storyboard' || mode === 'imageToVideo' || mode === 'storyboardVideo'

    const draftPromptAssetsOf = (d: ParamSettingsDraftState): PromptAssetItem[] =>
      computeDraftPromptAssets(propsRef.current.mode, d, propsRef.current.extraPromptAssets ?? [])

    function findPromptAssetForGridItem(
      item: GridImageItem,
      d: ParamSettingsDraftState
    ): PromptAssetItem | null {
      const assetId = String(item.img?.id ?? '')
      if (!assetId) return null
      return draftPromptAssetsOf(d).find((a) => a.assetId === assetId) ?? null
    }

    function isGridItemSelected(item: GridImageItem): boolean {
      return draft.selectedGridKeys.has(item.key)
    }

    function restoreGridSelectionFromPrompt(d: ParamSettingsDraftState): Set<string> {
      const ids = extractReferencedAssetIdsFromHtml(propsRef.current.prompt ?? '')
      if (!ids.size) return new Set()
      const keys = new Set<string>()
      for (const item of buildGridItems(propsRef.current.mode, d)) {
        const assetId = String(item.img?.id ?? '')
        if (assetId && ids.has(assetId)) keys.add(item.key)
      }
      return keys
    }

    const {
      ensureLoaded,
      cameraMovementOptions,
      shootingTechniqueOptions,
      compositionOptions,
      shotSizeOptions,
      cameraAngleOptions,
      focalLengthOptions,
      colorToneOptions,
      lightingOptions,
      techniqueOptions
    } = usePromptDictionary()

    useEffect(() => {
      void ensureLoaded()
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    function initDraftFromProps() {
      const p = propsRef.current
      const refsFromProps =
        (p.referenceImages?.length ?? 0) > 0
          ? cloneList(p.referenceImages!)
          : p.referenceImage?.url || p.referenceImage?.thumbnail
            ? [{ ...p.referenceImage }]
            : []
      const next: ParamSettingsDraftState = {
        sceneImages: cloneList(p.sceneImages ?? []),
        characterImages: cloneList(p.characterImages ?? []),
        propImages: cloneList(p.propImages ?? []),
        otherImages: cloneList(p.otherImages ?? []),
        nineGridEnabled: p.nineGridEnabled ?? false,
        referenceImages: refsFromProps,
        referenceImage: refsFromProps[0] ? { ...refsFromProps[0] } : null,
        i2vNineGridEnabled: p.imageToVideoNineGridEnabled ?? p.nineGridEnabled ?? false,
        i2vReferenceImages: cloneList(
          p.imageToVideoReferenceImages?.length ? p.imageToVideoReferenceImages : refsFromProps
        ),
        i2vSelectedCameraMovement: p.imageToVideoSelectedCameraMovement
          ? { ...p.imageToVideoSelectedCameraMovement }
          : p.selectedCameraMovement
            ? { ...p.selectedCameraMovement }
            : null,
        i2vCameraMovementDesc: p.imageToVideoCameraMovementDesc ?? p.cameraMovementDesc ?? '',
        i2vSelectedShootingTechnique: p.imageToVideoSelectedShootingTechnique
          ? { ...p.imageToVideoSelectedShootingTechnique }
          : p.selectedShootingTechnique
            ? { ...p.selectedShootingTechnique }
            : null,
        i2vActiveVideoSettingKey:
          p.imageToVideoActiveVideoSettingKey ?? p.activeVideoSettingKey ?? null,
        selectedComposition: p.selectedComposition ? { ...p.selectedComposition } : null,
        selectedShotSize: p.selectedShotSize ? { ...p.selectedShotSize } : null,
        selectedCameraAngle: p.selectedCameraAngle ? { ...p.selectedCameraAngle } : null,
        selectedFocalLength: p.selectedFocalLength ? { ...p.selectedFocalLength } : null,
        selectedColorTone: p.selectedColorTone ? { ...p.selectedColorTone } : null,
        selectedLighting: p.selectedLighting ? { ...p.selectedLighting } : null,
        selectedTechnique: p.selectedTechnique ? { ...p.selectedTechnique } : null,
        compositionDesc: p.compositionDesc ?? '',
        activeSettingKey: p.activeSettingKey ?? null,
        selectedCameraMovement: p.selectedCameraMovement ? { ...p.selectedCameraMovement } : null,
        cameraMovementDesc: p.cameraMovementDesc ?? '',
        selectedShootingTechnique: p.selectedShootingTechnique
          ? { ...p.selectedShootingTechnique }
          : null,
        activeVideoSettingKey: p.activeVideoSettingKey ?? null,
        selectedGridKeys: new Set<string>()
      }
      // 原 nextTick(() => restoreGridSelectionFromPrompt())：网格由草稿态直接推导，可同步恢复选中
      next.selectedGridKeys = restoreGridSelectionFromPrompt(next)
      draftRef.current = next
      setDraftState(next)
    }

    // 原 watch(() => props.open, (v) => { if (v) initDraftFromProps() })
    useEffect(() => {
      if (open) initDraftFromProps()
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open])

    const allGridImages = buildGridItems(mode, draft)

    function onImportClick(type: AssetCategoryKey) {
      onOpenSelectModal?.(type)
    }

    function onGridItemClick(item: GridImageItem) {
      if (item.source === 'reference') {
        if (propsRef.current.mode === 'storyboardVideo') {
          onGridItemPreview(item)
        } else {
          onGridItemPreview(item)
        }
        return
      }
      const next = new Set(draftRef.current.selectedGridKeys)
      const wasSelected = next.has(item.key)
      if (wasSelected) next.delete(item.key)
      else next.add(item.key)
      patchDraft({ selectedGridKeys: next })

      const asset = findPromptAssetForGridItem(item, draftRef.current)
      if (asset && enableDraftPromptAssetRefs) {
        onToggleAssetRef?.({ asset, selected: !wasSelected })
      }
    }

    function onGridItemPreview(item: GridImageItem) {
      if (item.source === 'reference') {
        onPreviewReference?.()
        return
      }
      onPreviewAssetImage?.(item.img)
    }

    function removeGridItem(item: GridImageItem) {
      if (item.source === 'reference') {
        const referenceImages = draftRef.current.referenceImages.filter((_, i) => i !== item.index)
        const patch: Partial<ParamSettingsDraftState> = {
          referenceImages,
          referenceImage: referenceImages[0] ? { ...referenceImages[0] } : null
        }
        if (!referenceImages.length) {
          patch.nineGridEnabled = false
        }
        patchDraft(patch)
        return
      }
      const patch: Partial<ParamSettingsDraftState> = {}
      if (item.source === 'other') {
        patch.otherImages = draftRef.current.otherImages.filter((_, i) => i !== item.index)
      }
      const next = new Set(draftRef.current.selectedGridKeys)
      next.delete(item.key)
      patch.selectedGridKeys = next
      patchDraft(patch)
      const asset = findPromptAssetForGridItem(item, draftRef.current)
      if (asset) {
        onToggleAssetRef?.({ asset, selected: false })
      }
    }

    function appendDraftImages(type: AssetCategoryKey | 'other', items: any[]) {
      if (!items?.length) return
      const list = items.map((item) => ({
        ...item,
        id: item.id || `img-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
        url: item.url || item.thumbnail,
        thumbnail: item.thumbnail || item.url,
        title:
          item.title ||
          item.name ||
          `${categoryDefaultNames[type === 'other' ? 'pose' : type] || '参考'}`
      }))
      if (type === 'scene') {
        patchDraft({ sceneImages: [...draftRef.current.sceneImages, ...list] })
      } else if (type === 'character') {
        patchDraft({ characterImages: [...draftRef.current.characterImages, ...list] })
      } else if (type === 'prop') {
        patchDraft({ propImages: [...draftRef.current.propImages, ...list] })
      } else {
        patchDraft({ otherImages: [...draftRef.current.otherImages, ...list] })
      }

      // 原 nextTick：草稿已同步进 draftRef，可直接推导网格并同步选中/引用
      const newAssets: PromptAssetItem[] = []
      const gridItems = buildGridItems(propsRef.current.mode, draftRef.current)
      for (const img of list) {
        const assetId = String(img.id)
        const gridItem = gridItems.find((g) => String(g.img?.id) === assetId)
        if (!gridItem) continue
        patchDraft({
          selectedGridKeys: new Set([...draftRef.current.selectedGridKeys, gridItem.key])
        })
        const asset = findPromptAssetForGridItem(gridItem, draftRef.current)
        if (asset) newAssets.push(asset)
      }
      if (newAssets.length && enableDraftPromptAssetRefs) {
        onSyncAssetRefs?.(newAssets)
      }
    }

    function appendDraftReferences(items: any[]) {
      if (!items?.length) return
      const list = items.map((item, idx) => ({
        ...item,
        id: item.id || `ref-${Date.now()}-${idx}-${Math.random().toString(36).slice(2, 6)}`,
        url: item.url || item.thumbnail,
        thumbnail: item.thumbnail || item.url,
        title: item.title || item.name || '参考图'
      }))
      if (propsRef.current.mode === 'storyboardVideo') {
        const referenceImages = list.slice(0, 1)
        patchDraft({
          referenceImages,
          referenceImage: referenceImages[0] ? { ...referenceImages[0] } : null
        })
        return
      }
      const referenceImages = [...draftRef.current.referenceImages, ...list]
      const patch: Partial<ParamSettingsDraftState> = { referenceImages }
      if (!draftRef.current.referenceImage && referenceImages[0]) {
        patch.referenceImage = { ...referenceImages[0] }
      }
      patchDraft(patch)
    }

    function setDraftReference(item: any) {
      appendDraftReferences([item])
    }

    function buildConfirmPayload(): ParamSettingsConfirmPayload {
      const d = draftRef.current
      const referenceImages = cloneList(d.referenceImages)
      const primaryReference = referenceImages[0] ? { ...referenceImages[0] } : null
      return {
        sceneImages: cloneList(d.sceneImages),
        characterImages: cloneList(d.characterImages),
        propImages: cloneList(d.propImages),
        otherImages: cloneList(d.otherImages),
        nineGridEnabled: d.nineGridEnabled,
        referenceImage: primaryReference,
        referenceImages,
        selectedComposition: d.selectedComposition ? { ...d.selectedComposition } : null,
        selectedShotSize: d.selectedShotSize ? { ...d.selectedShotSize } : null,
        selectedCameraAngle: d.selectedCameraAngle ? { ...d.selectedCameraAngle } : null,
        selectedFocalLength: d.selectedFocalLength ? { ...d.selectedFocalLength } : null,
        selectedColorTone: d.selectedColorTone ? { ...d.selectedColorTone } : null,
        selectedLighting: d.selectedLighting ? { ...d.selectedLighting } : null,
        selectedTechnique: d.selectedTechnique ? { ...d.selectedTechnique } : null,
        compositionDesc: d.compositionDesc,
        activeSettingKey: d.activeSettingKey,
        selectedCameraMovement: d.selectedCameraMovement ? { ...d.selectedCameraMovement } : null,
        cameraMovementDesc: d.cameraMovementDesc,
        selectedShootingTechnique: d.selectedShootingTechnique
          ? { ...d.selectedShootingTechnique }
          : null,
        activeVideoSettingKey: d.activeVideoSettingKey,
        ...(propsRef.current.mode === 'imageToVideo'
          ? {
              imageToVideoNineGridEnabled: d.i2vNineGridEnabled,
              imageToVideoSelectedCameraMovement: d.i2vSelectedCameraMovement
                ? { ...d.i2vSelectedCameraMovement }
                : null,
              imageToVideoCameraMovementDesc: d.i2vCameraMovementDesc,
              imageToVideoSelectedShootingTechnique: d.i2vSelectedShootingTechnique
                ? { ...d.i2vSelectedShootingTechnique }
                : null,
              imageToVideoActiveVideoSettingKey: d.i2vActiveVideoSettingKey
            }
          : {})
      }
    }

    function handleCancel() {
      onOpenChange(false)
    }

    function handleConfirm() {
      onConfirm?.(buildConfirmPayload())
      onOpenChange(false)
    }

    useImperativeHandle(ref, () => ({
      isOpen: () => propsRef.current.open,
      appendDraftImages,
      appendDraftReferences,
      setDraftReference
    }))

    return (
      <Modal
        open={open}
        width={1100}
        footer={null}
        title={null}
        closable={false}
        className="storyboard-param-settings-modal"
        wrapClassName="create-flow-modal storyboard-param-settings-modal-wrap"
        destroyOnHidden
        onCancel={handleCancel}
      >
        <div className="spsm-inner">
          <header className="spsm-header">
            <h2 className="spsm-title">灵感空间</h2>
            <button type="button" className="spsm-close" aria-label="关闭" onClick={handleCancel}>
              <CloseOutlined />
            </button>
          </header>

          <div className="spsm-body">
            {/* 左侧：上传按钮 + 图片网格 */}
            <aside className="spsm-left">
              {/* 分镜视频：单个上传按钮 */}
              {mode === 'storyboardVideo' ? (
                <div className="spsm-upload-row">
                  <button type="button" className="spsm-upload-btn" onClick={() => onImportReference?.()}>
                    <img src={dialogAddIcon} alt="" />
                    <div>导入参考图</div>
                  </button>
                </div>
              ) : (
                /* 分镜图 / 多参生视频：多个上传按钮 */
                <div className="spsm-upload-row">
                  {uploadCategories.map((cat) => (
                    <button
                      key={cat.key}
                      type="button"
                      className="spsm-upload-btn"
                      onClick={() => onImportClick(cat.key)}
                    >
                      <img src={dialogAddIcon} alt="" />
                      <div>{cat.label}</div>
                    </button>
                  ))}
                </div>
              )}

              <div className="spsm-grid-scroll spsm-hidden-scrollbar">
                {allGridImages.length ? (
                  <div className="spsm-grid">
                    {allGridImages.map((item) => (
                      <div
                        key={item.key}
                        className={
                          mode !== 'storyboardVideo' && isGridItemSelected(item)
                            ? 'spsm-grid-item spsm-grid-item--selected'
                            : 'spsm-grid-item'
                        }
                        onClick={() => onGridItemClick(item)}
                        onDoubleClick={(e) => {
                          e.stopPropagation()
                          onGridItemPreview(item)
                        }}
                      >
                        {item.img.url || item.img.thumbnail ? (
                          <Image
                            src={item.img.url || item.img.thumbnail}
                            alt={item.displayName}
                            preview={false}
                            rootClassName="spsm-grid-img"
                          />
                        ) : null}
                        {mode !== 'storyboardVideo' ? (
                          <img
                            className="spsm-grid-select"
                            src={isGridItemSelected(item) ? dialogSelectSelIcon : dialogSelectNorIcon}
                            alt=""
                          />
                        ) : null}
                        <span className="spsm-grid-name" title={item.displayName}>
                          {item.displayName}
                        </span>
                        {item.removable ? (
                          <button
                            type="button"
                            className="spsm-grid-remove"
                            title="移除"
                            onClick={(e) => {
                              e.stopPropagation()
                              removeGridItem(item)
                            }}
                          >
                            ×
                          </button>
                        ) : null}
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="spsm-grid-empty">
                    <span>暂无图片，请点击上方按钮导入</span>
                  </div>
                )}
              </div>
            </aside>

            {/* 右侧：参数选择区 */}
            <aside className="spsm-right">
              <div
                className={
                  mode === 'storyboardVideo'
                    ? 'spsm-settings-scroll spsm-hidden-scrollbar spsm-settings-readonly'
                    : 'spsm-settings-scroll spsm-hidden-scrollbar'
                }
              >
                <ParamSettingsRightPanel
                  mode={mode}
                  draft={draft}
                  dict={{
                    cameraMovementOptions,
                    shootingTechniqueOptions,
                    compositionOptions,
                    shotSizeOptions,
                    cameraAngleOptions,
                    focalLengthOptions,
                    colorToneOptions,
                    lightingOptions,
                    techniqueOptions
                  }}
                  onPatchDraft={patchDraft}
                />
              </div>
            </aside>
          </div>

          <footer className="spsm-footer">
            <Button className="spsm-btn-cancel" onClick={handleCancel}>
              <span className="text-gradient">取消</span>
            </Button>
            <Button type="primary" className="spsm-btn-ok" onClick={handleConfirm}>
              确定
            </Button>
          </footer>
        </div>
      </Modal>
    )
  }
)

export default StoryboardParamSettingsModal
