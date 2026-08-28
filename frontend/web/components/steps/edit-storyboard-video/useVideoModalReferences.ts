'use client'

import { message } from 'antd'
import type { ParamSettingsConfirmPayload } from '~/components/steps/StoryboardParamSettingsModal'
import { openImagePreviewModal } from '~/utils/openImagePreviewModal'
import { resolveSafeHttpUrl } from '~/utils/safeNavigation'
import { pruneResolvedPromptAssetsForRemovedImage } from '~/utils/storyboardPromptAssetStripSync'
import { splitReferenceConfirmItems } from '~/utils/storyboardVideoReferenceAudioWire'
import type {
EdgeFrameImage,
ImageToVideoSettingKey,
SelectAssetModalType,
VideoModalCtx,
VideoModalReferencesApi
} from './types'
import { createVideoModalReferenceCore } from './videoModalReferenceCore'

/** 图生视频接口：参考图最多 1 张 */
const MAX_IMAGE_TO_VIDEO_REFERENCE_COUNT = 1

/** 参考图 / 多参素材桶 / 首尾帧 / 各选择弹窗回调（原 setup 素材段逻辑） */
export function useVideoModalReferences(ctx: VideoModalCtx): void {
  /** 原 referenceImage computed（首图读写包装） */
  const { applyDefaultStoryboardReferenceImages, cleanStoryboardScriptTabLabel, collectMultiParamAssetImages, collectReferenceImageUrls, currentPanelStoryboardImages, handleImportReference, handleMultiParamImportReference, inferMultiParamAssetType, mapMultiParamReferenceImportItem, normalizeImageToVideoReferenceItems, referenceImageGet, referenceStepTabName, resetStoryboardReferenceState, resolveBaseImageRecordId, resolveDefaultStoryboardReferenceImage, resolveSceneCoverImageUrl, setReferenceImage, storyboardScriptAssetGroups, syncResolvedPromptAssetsToImportReferences, validateImageToVideoReferenceImages, validateMultiParamAssetImages } = createVideoModalReferenceCore(ctx)
  function appendMultiParamAssetImages(type: 'scene' | 'character' | 'prop' | 'other', list: any[]) {
    if (!list.length) return
    if (type === 'scene') {
      ctx.sceneImages.set([...ctx.sceneImages.get(), ...list])
    } else if (type === 'character') {
      ctx.characterImages.set([...ctx.characterImages.get(), ...list])
    } else if (type === 'prop') {
      ctx.propImages.set([...ctx.propImages.get(), ...list])
    } else {
      ctx.otherImages.set([...ctx.otherImages.get(), ...list])
    }
  }

  function onSelectMultiParamReferenceConfirm(items: any[]) {
    if (!items?.length) return
    const { images, audios } = splitReferenceConfirmItems(items)
    ctx.applyImportedReferenceAudios(audios)
    if (!images.length) {
      if (audios.length) message.success(`已导入 ${audios.length} 条参考音频`)
      return
    }
    const list = images.map(mapMultiParamReferenceImportItem)
    if (ctx.getActiveStoryboardPanel()?.isParamSettingsOpen?.()) {
      for (const item of list) {
        ctx.getActiveStoryboardPanel()?.applyParamDraftAssets(inferMultiParamAssetType(item), [item])
      }
      message.success(
        audios.length
          ? `已导入 ${list.length} 张参考图、${audios.length} 条参考音频`
          : `已导入 ${list.length} 张参考图`
      )
      return
    }
    for (const item of list) {
      appendMultiParamAssetImages(inferMultiParamAssetType(item), [item])
    }
    message.success(
      audios.length
        ? `已导入 ${list.length} 张参考图、${audios.length} 条参考音频`
        : `已导入 ${list.length} 张参考图`
    )
  }

  function removeMultiParamAssetReference(index: number) {
    const all = [
      ...ctx.sceneImages.get(),
      ...ctx.characterImages.get(),
      ...ctx.propImages.get(),
      ...ctx.otherImages.get()
    ]
    const target = all[index]
    if (!target) return
    const key = String(target.id || target.url || target.thumbnail || '')
    const filterOut = (arr: any[]) =>
      arr.filter((img) => String(img.id || img.url || img.thumbnail || '') !== key)
    ctx.sceneImages.set(filterOut(ctx.sceneImages.get()))
    ctx.characterImages.set(filterOut(ctx.characterImages.get()))
    ctx.propImages.set(filterOut(ctx.propImages.get()))
    ctx.otherImages.set(filterOut(ctx.otherImages.get()))
    // 同步剔除 resolve 缓存，避免后续同步再次把已删图写回描述框
    ctx.resolvedMultiParamPromptAssets.set(
      pruneResolvedPromptAssetsForRemovedImage(ctx.resolvedMultiParamPromptAssets.get(), target)
    )
  }

  function mapReferenceImportItem(item: any, idx: number) {
    return {
      id: item.id || `ref-${Date.now()}-${idx}-${Math.random().toString(36).slice(2, 6)}`,
      url: item.url || item.thumbnail,
      thumbnail: item.thumbnail || item.url,
      title: item.title || item.name
    }
  }

  function onSelectReferenceConfirm(items: any[]) {
    if (!items?.length) return
    const { images, audios } = splitReferenceConfirmItems(items)
    ctx.applyImportedReferenceAudios(audios)
    if (!images.length) {
      if (audios.length) message.success(`已导入 ${audios.length} 条参考音频`)
      return
    }
    const mapped = normalizeImageToVideoReferenceItems(images.map(mapReferenceImportItem))
    const panel = ctx.getActiveStoryboardPanel()
    if (panel?.isParamSettingsOpen?.()) {
      panel.applyParamDraftReferences(mapped)
      message.success(audios.length ? '已导入参考图与参考音频' : '已导入参考图')
      return
    }
    ctx.referenceImages.set(mapped)
    message.success(audios.length ? '已导入参考图与参考音频' : '已导入参考图')
  }

  function clearReferenceImage() {
    ctx.referenceImages.set([])
    ctx.nineGridEnabled.set(false)
    message.success('已移除')
  }

  function removeReferenceImageAt(index: number) {
    const next = [...ctx.referenceImages.get()]
    next.splice(index, 1)
    ctx.referenceImages.set(next)
    if (!next.length) {
      ctx.nineGridEnabled.set(false)
    }
  }

  function previewReferenceImage(ref: { url?: string; thumbnail?: string }) {
    const src = resolveSafeHttpUrl(ref?.url || ref?.thumbnail, window.location.href)
    if (!src) return
    window.open(src, '_blank', 'noopener,noreferrer')
  }

  function onPreviewReferenceImage() {
    const r = referenceImageGet()
    if (r && (r.url || r.thumbnail)) previewReferenceImage(r)
  }

  function openSelectAssetModal(type: SelectAssetModalType) {
    if (type === 'pose' || type === 'expression' || type === 'effect' || type === 'draft') {
      ctx.materialLibraryCategoryKey.set(type)
      ctx.showMaterialFromLibraryModal.set(true)
      return
    }
    if (type === 'other') {
      ctx.materialLibraryCategoryKey.set('misc')
      ctx.showMaterialFromLibraryModal.set(true)
      return
    }
    ctx.selectAssetModalType.set(type)
    ctx.selectAssetModalOpen.set(true)
  }

  function handleMaterialLibraryOtherImport(assets: any[]) {
    if (!assets?.length) return
    const list = assets.map((item) => ({
      ...item,
      url: item.url || item.thumbnail,
      thumbnail: item.thumbnail || item.url,
      title: item.title || item.name || '参考图',
      id: item.id || `img-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
    }))
    if (ctx.getActiveStoryboardPanel()?.isParamSettingsOpen?.()) {
      const type =
        ctx.materialLibraryCategoryKey.get() === 'misc' ? 'other' : ctx.materialLibraryCategoryKey.get()
      ctx.getActiveStoryboardPanel()?.applyParamDraftAssets(type as any, list)
      message.success(`已添加 ${list.length} 项`)
      ctx.showMaterialFromLibraryModal.set(false)
      return
    }
    ctx.otherImages.set([...ctx.otherImages.get(), ...list])
    message.success(`已添加 ${list.length} 项`)
    ctx.showMaterialFromLibraryModal.set(false)
  }

  function onSelectAssetConfirm(items: any[]) {
    if (!items?.length) return
    const list = items.map((item) => ({
      ...item,
      id: item.id || `img-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
    }))
    if (ctx.getActiveStoryboardPanel()?.isParamSettingsOpen?.()) {
      ctx.getActiveStoryboardPanel()?.applyParamDraftAssets(ctx.selectAssetModalType.get(), list)
      message.success(`已添加 ${list.length} 项`)
      return
    }
    if (ctx.selectAssetModalType.get() === 'scene') {
      ctx.sceneImages.set([...ctx.sceneImages.get(), ...list])
    } else if (ctx.selectAssetModalType.get() === 'character') {
      ctx.characterImages.set([...ctx.characterImages.get(), ...list])
    } else if (ctx.selectAssetModalType.get() === 'prop') {
      ctx.propImages.set([...ctx.propImages.get(), ...list])
    } else {
      ctx.otherImages.set([...ctx.otherImages.get(), ...list])
    }
    message.success(`已添加 ${list.length} 项`)
  }

  function removeOtherImage(index: number) {
    ctx.otherImages.set(ctx.otherImages.get().filter((_, i) => i !== index))
  }

  function previewAssetImage(img: any) {
    const url = img?.url || img?.thumbnail
    if (!url) return
    openImagePreviewModal({
      url,
      title: img?.title || img?.name || '预览'
    })
  }

  // ---------- 首尾帧 ----------

  function mapEdgeFrameImportItem(item: any): EdgeFrameImage {
    const serverRow = item?._serverRow
    const recordId = Number(serverRow?.id ?? item?.id)
    const idStr = String(item?.id ?? '')
    const isGenRecord =
      !!item?._fromServer &&
      Number.isFinite(recordId) &&
      recordId > 0 &&
      !idStr.startsWith('local-') &&
      !idStr.startsWith('sb-ref-') &&
      !idStr.startsWith('ref-') &&
      !idStr.startsWith('proj-') &&
      !idStr.startsWith('lib-')
    return {
      ...item,
      id: isGenRecord ? recordId : item.id || `local-${Date.now()}`,
      url: item.url || item.thumbnail,
      thumbnail: item.thumbnail || item.url,
      title: item.title || item.name,
      _fromServer: isGenRecord,
      _serverRow: isGenRecord ? { id: recordId } : serverRow
    }
  }

  function buildEdgeFrameApiFields(frame: EdgeFrameImage | null, role: 'first' | 'last') {
    if (!frame) return {}
    const url = String(frame.url || frame.thumbnail || '').trim()
    const recordId = Number(frame._serverRow?.id)
    const canUseRecordId = !!frame._fromServer && Number.isFinite(recordId) && recordId > 0

    if (url) {
      return role === 'first' ? { firstImageUrl: url } : { lastImageUrl: url }
    }
    if (canUseRecordId) {
      return role === 'first' ? { firstImageRecordId: recordId } : { lastImageRecordId: recordId }
    }
    return {}
  }

  function validateEdgeFrameImages(): boolean {
    const firstFields = buildEdgeFrameApiFields(ctx.firstFrameImage.get(), 'first')
    if (!firstFields.firstImageUrl && !firstFields.firstImageRecordId) {
      message.warning('请选首帧')
      return false
    }
    return true
  }

  function onEdgeFrameCardClick(target: 'first' | 'last') {
    const current = target === 'first' ? ctx.firstFrameImage.get() : ctx.lastFrameImage.get()
    if (current?.url || current?.thumbnail) {
      previewAssetImage(current)
      return
    }
    ctx.edgeFramePickTarget.set(target)
    ctx.selectEdgeFrameModalOpen.set(true)
  }

  function onSelectEdgeFrameConfirm(items: any[]) {
    if (!items?.length) return
    const { images, audios } = splitReferenceConfirmItems(items)
    ctx.applyImportedReferenceAudios(audios)
    const imageItem = images[0]
    if (!imageItem) {
      if (audios.length) message.success(`已导入 ${audios.length} 条参考音频`)
      return
    }
    const mapped = mapEdgeFrameImportItem(imageItem)
    if (ctx.edgeFramePickTarget.get() === 'first') {
      ctx.firstFrameImage.set(mapped)
    } else {
      ctx.lastFrameImage.set(mapped)
    }
    message.success(
      audios.length
        ? `已导入${ctx.edgeFramePickTarget.get() === 'first' ? '首帧' : '尾帧'}与参考音频`
        : `已导入${ctx.edgeFramePickTarget.get() === 'first' ? '首帧' : '尾帧'}`
    )
  }

  function clearEdgeFrame(target: 'first' | 'last') {
    if (target === 'first') ctx.firstFrameImage.set(null)
    else ctx.lastFrameImage.set(null)
  }

  function cloneEdgeFrameImage(frame: EdgeFrameImage | null): EdgeFrameImage | null {
    if (!frame) return null
    return {
      ...frame,
      _serverRow: frame._serverRow ? { ...frame._serverRow } : undefined
    }
  }

  function swapEdgeFrames() {
    const first = ctx.firstFrameImage.get()
    const last = ctx.lastFrameImage.get()
    if (!first && !last) {
      message.warning('请先上传首帧或尾帧')
      return
    }
    ctx.firstFrameImage.set(cloneEdgeFrameImage(last))
    ctx.lastFrameImage.set(cloneEdgeFrameImage(first))
    message.success('已交换首尾帧')
  }

  function showEdgeFrameSwap(): boolean {
    return (
      !!(ctx.firstFrameImage.get()?.url || ctx.firstFrameImage.get()?.thumbnail) ||
      !!(ctx.lastFrameImage.get()?.url || ctx.lastFrameImage.get()?.thumbnail)
    )
  }

  // ---------- 灵感空间确认回填 ----------

  function applyImageToVideoParamSettingsConfirm(payload: ParamSettingsConfirmPayload) {
    ctx.nineGridEnabled.set(payload.nineGridEnabled)
    const refs = payload.referenceImages?.length
      ? [...payload.referenceImages]
      : payload.referenceImage
        ? [{ ...payload.referenceImage }]
        : []
    ctx.referenceImages.set(normalizeImageToVideoReferenceItems(refs))
    ctx.selectedCameraMovement.set(payload.selectedCameraMovement)
    ctx.cameraMovementDesc.set(payload.cameraMovementDesc)
    ctx.selectedImageToVideoShootingTechnique.set(payload.selectedShootingTechnique)
    ctx.activeImageToVideoSettingKey.set(
      payload.activeVideoSettingKey as ImageToVideoSettingKey | null
    )
  }

  function applyMultiParamSettingsConfirm(payload: ParamSettingsConfirmPayload) {
    ctx.sceneImages.set(payload.sceneImages)
    ctx.characterImages.set(payload.characterImages)
    ctx.propImages.set(payload.propImages)
    ctx.otherImages.set(payload.otherImages)
    ctx.multiParamShootingTechnique.set(
      payload.imageToVideoSelectedShootingTechnique ?? payload.selectedShootingTechnique ?? null
    )
    ctx.activeMultiParamSettingKey.set(payload.activeVideoSettingKey)
    if (payload.imageToVideoNineGridEnabled !== undefined) {
      ctx.nineGridEnabled.set(payload.imageToVideoNineGridEnabled)
    }
    if (payload.imageToVideoSelectedCameraMovement !== undefined) {
      ctx.selectedCameraMovement.set(payload.imageToVideoSelectedCameraMovement)
    }
    if (payload.imageToVideoCameraMovementDesc !== undefined) {
      ctx.cameraMovementDesc.set(payload.imageToVideoCameraMovementDesc)
    }
    if (payload.imageToVideoSelectedShootingTechnique !== undefined) {
      ctx.selectedImageToVideoShootingTechnique.set(payload.imageToVideoSelectedShootingTechnique)
    }
    if (payload.imageToVideoActiveVideoSettingKey !== undefined) {
      ctx.activeImageToVideoSettingKey.set(
        payload.imageToVideoActiveVideoSettingKey as ImageToVideoSettingKey | null
      )
    }
  }

  const api: VideoModalReferencesApi = {
    referenceImageGet,
    setReferenceImage,
    resetStoryboardReferenceState,
    applyDefaultStoryboardReferenceImages,
    resolveDefaultStoryboardReferenceImage,
    syncResolvedPromptAssetsToImportReferences,
    collectReferenceImageUrls,
    collectMultiParamAssetImages,
    validateImageToVideoReferenceImages,
    validateMultiParamAssetImages,
    normalizeImageToVideoReferenceItems,
    resolveBaseImageRecordId,
    handleImportReference,
    handleMultiParamImportReference,
    onSelectReferenceConfirm,
    onSelectMultiParamReferenceConfirm,
    onSelectAssetConfirm,
    onSelectEdgeFrameConfirm,
    openSelectAssetModal,
    handleMaterialLibraryOtherImport,
    removeMultiParamAssetReference,
    removeReferenceImageAt,
    clearReferenceImage,
    previewReferenceImage,
    onPreviewReferenceImage,
    previewAssetImage,
    removeOtherImage,
    inferMultiParamAssetType,
    onEdgeFrameCardClick,
    clearEdgeFrame,
    swapEdgeFrames,
    buildEdgeFrameApiFields,
    validateEdgeFrameImages,
    showEdgeFrameSwap,
    applyImageToVideoParamSettingsConfirm,
    applyMultiParamSettingsConfirm,
    referenceStepTabName,
    currentPanelStoryboardImages,
    storyboardScriptAssetGroups,
    resolveSceneCoverImageUrl,
    cleanStoryboardScriptTabLabel
  }
  Object.assign(ctx, api)
}
