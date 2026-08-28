'use client'

import { message } from 'antd'
import { createSceneModalImageInteractionOps } from './sceneModalImageInteractionOps'
import {
normalizeImageId,
resolveRpsSourceType
} from './sceneModalTaskParsers'
import type { EditSceneImageModalCtx } from './types'

export interface SceneModalImageActionsApi {
  isAddingSceneImage: () => boolean
  isCancellingAdd: () => boolean
  handleUploadLocalImage: () => void
  handleOpenAssetLibrary: () => void
  handleAssetLibraryImport: (asset: any) => Promise<void>
  startEditImageTitle: (index: number) => void
  /** 与列表点击改名一致：POST /api/user/asset/rps/form-image/update */
  handleImageTitleBlur: (index: number) => Promise<void>
  removeImage: (index: number) => void
  handleDialogueImportMultiple: (payload: { sceneIndex: number; images: any[] }) => void
  handleGenerateImportMultiple: (payload: { sceneIndex: number; images: any[] }) => void
  removeDialogueSourceImage: (index: number) => void
  handlePreviewImage: (index: number) => void
  handleReplaceImage: (index: number) => void
  handleDownloadImage: (index: number) => void
  handleDeleteImage: (index: number) => void
  handleModifyImage: (index: number) => void
  handleSetMainFromHistory: (imageIndex: number) => Promise<void>
  handleAddSceneImage: () => Promise<void>
  handleConfirmAddImage: (index: number) => Promise<void>
  handleCancelAddImage: (index: number) => Promise<void>
}

export function useSceneModalImageActions(ctx: EditSceneImageModalCtx): SceneModalImageActionsApi {
  const { handleAssetLibraryImport, handleDeleteImage, handleDialogueImportMultiple, handleDownloadImage, handleGenerateImportMultiple, handleImageTitleBlur, handleModifyImage, handleOpenAssetLibrary, handlePreviewImage, handleReplaceImage, handleUploadLocalImage, isAddingSceneImage, isCancellingAdd, removeDialogueSourceImage, removeImage, startEditImageTitle } = createSceneModalImageInteractionOps(ctx)
  async function handleSetMainFromHistory(imageIndex: number) {
    if (isAddingSceneImage()) return
    ctx.currentImageIndex.set(imageIndex)
    await handleAddSceneImage()
  }

  const handleAddSceneImage = async () => {
    if (isAddingSceneImage()) return

    const currentImg = ctx.localSceneImages.get()[ctx.currentImageIndex.get()] as any
    if (currentImg?._pending) {
      await handleConfirmAddImage(ctx.currentImageIndex.get())
      return
    }

    // 如果当前是“已导入但尚未设置为主图”的图片：直接调用 form/use
    if (currentImg && !currentImg._pending) {
      const imageUrl = String(currentImg?.url || '').trim()
      const formId = Number(currentImg?.rpsFormId)
      const currentRpsImageId = Number(currentImg?.rpsImageId)

      if (imageUrl && Number.isFinite(formId) && Number.isFinite(currentRpsImageId)) {
        const sceneIdx = ctx.currentSceneIndex.get()
        const imgIdx = ctx.currentImageIndex.get()
        ctx.addingSceneImageAtKey.set(ctx.buildCanvasOverlayKey(sceneIdx, imgIdx))
        try {
          const resolvedImageId = await ctx.resolveImageIdFromFormImageList({
            formId,
            imageId: currentRpsImageId,
            imageUrl,
            imageTitle: String(currentImg?.title || '')
          })

          const targetImageId = Number(resolvedImageId ?? currentRpsImageId)
          const setOk = await ctx.reserveSetRpsForm({
            imageId: targetImageId,
            formId,
            imageType: ctx.props().imageType
          })

          if (setOk) {
            currentImg._isSet = true
            ;(currentImg as { rpsImageId?: number }).rpsImageId = targetImageId
            ctx.localSceneImages.set([...ctx.localSceneImages.get()])
            const key = normalizeImageId(currentImg.id)
            if (key) ctx.addedImageIds.set(new Set([...ctx.addedImageIds.get(), key]))
            ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent())
            message.success('已设置为主图')
          }
        } finally {
          if (
            ctx.addingSceneImageAtKey.get() ===
            ctx.buildCanvasOverlayKey(ctx.currentSceneIndex.get(), ctx.currentImageIndex.get())
          ) {
            ctx.addingSceneImageAtKey.set('')
          }
        }
        return
      }
    }

    // 兜底：若当前选中项不是 pending，则尝试定位最近导入的 pending 图
    if (ctx.pendingImage.current?.id) {
      const pendingIndex = ctx.localSceneImages.get().findIndex((img: any) => img?.id === ctx.pendingImage.current?.id && img?._pending)
      if (pendingIndex >= 0) {
        ctx.currentImageIndex.set(pendingIndex)
        await handleConfirmAddImage(pendingIndex)
        return
      }
    }

    message.warning(`请先通过“本地上传图片”或“资源库导入”导入图片，再点击${ctx.addImageButtonLabel()}`)
  }

  // 确认添加待添加的图片
  const handleConfirmAddImage = async (index: number) => {
    const sceneIdx = ctx.currentSceneIndex.get()
    const overlayKey = ctx.buildCanvasOverlayKey(sceneIdx, index)
    if (ctx.addingSceneImageAtKey.get() === overlayKey) return
    ctx.addingSceneImageAtKey.set(overlayKey)
    try {
    if (!ctx.pendingImage.current) {
      // 如果没有pendingImage，检查当前图片是否有_pending标记
      const img = ctx.localSceneImages.get()[index]
      if (!img || !img._pending) {
        message.warning('没有待添加的图片')
        return
      }
    }

    // 移除待添加标记，正式添加到列表
    const img = ctx.localSceneImages.get()[index]
    if (img) {
      delete img._pending
      ctx.localSceneImages.set([...ctx.localSceneImages.get()])
      // 记录已添加的图片ID（用于显示"取消添加"按钮）
      const addKey = normalizeImageId(img.id)
      if (addKey) {
        ctx.addedImageIds.set(new Set([...ctx.addedImageIds.get(), addKey]))
      }
    }

    // 清空待添加状态
    ctx.pendingImage.current = null

    let synced = false
    if (img?.url && (img?.rpsImageId == null || !Number.isFinite(Number(img?.rpsImageId)))) {
      const result = await ctx.syncImageToRpsApi(
        img.url,
        img.title || '',
        resolveRpsSourceType(img),
        img
      )
      if (result?.imageId != null) {
        img.rpsImageId = result.imageId
        synced = true
      }
      if (result?.formId != null) {
        img.rpsFormId = result.formId
      }
    }
    void synced
    const formId =
      img?.rpsFormId != null && Number.isFinite(Number(img.rpsFormId))
        ? Number(img.rpsFormId)
        : undefined
    const resolvedImageId = await ctx.resolveImageIdFromFormImageList({
      formId,
      imageId:
        img?.rpsImageId != null && Number.isFinite(Number(img.rpsImageId)) ? Number(img.rpsImageId) : undefined,
      imageUrl: img?.url,
      imageTitle: img?.title
    })
    const setOk =
      !img?.url
        ? true
        : await ctx.reserveSetRpsForm({
            imageId: resolvedImageId ?? undefined,
            formId,
            imageType: ctx.props().imageType
          })
    if (!setOk) {
      message.warning('设置展示图失败，请稍后重试')
      return
    }
    if (img) {
      img._isSet = true
      if (resolvedImageId != null && Number.isFinite(Number(resolvedImageId))) {
        ;(img as { rpsImageId?: number }).rpsImageId = Number(resolvedImageId)
      }
      if (formId != null && Number.isFinite(Number(formId))) {
        ;(img as { rpsFormId?: number }).rpsFormId = Number(formId)
      }
      ctx.localSceneImages.set([...ctx.localSceneImages.get()])
    }

    const images = ctx.buildVisibleImagesForParent()

    ctx.emitSceneTabUpdate(images)

    message.success('场景图已添加')

    // 延迟清空 lastAddedImageIndex，以便用户可以点击"取消添加"
    // 但允许继续添加新的图片（通过导入新的图片来重置状态）
    } finally {
      if (ctx.addingSceneImageAtKey.get() === ctx.buildCanvasOverlayKey(sceneIdx, index)) {
        ctx.addingSceneImageAtKey.set('')
      }
    }
  }

  // 取消添加的场景图（从父级列表移除并同步左侧/Tab，不能先标 _pending 再算「外部索引」否则会找不到）
  const handleCancelAddImage = async (index: number) => {
    const sceneIdx = ctx.currentSceneIndex.get()
    const overlayKey = ctx.buildCanvasOverlayKey(sceneIdx, index)
    if (ctx.cancellingAddAtKey.get() === overlayKey) return
    const img = ctx.localSceneImages.get()[index]
    if (!img) return

    if (ctx.cancelAddDisabledTooltip()) {
      return
    }

    if (!img._isSet) {
      message.info('该图片尚未设置为展示图')
      return
    }

    ctx.cancellingAddAtKey.set(overlayKey)
    try {
    const ci = ctx.currentSceneIndex.get()
    const parentImages = ctx.props().scenes[ci]?.images || []
    const inParent = parentImages.some((x: any) => x.id === img.id)
    const formId = (img as { rpsFormId?: number }).rpsFormId
    const imageId = (img as { rpsImageId?: number }).rpsImageId
    const resolvedImageId = await ctx.resolveImageIdFromFormImageList({
      formId: formId != null && Number.isFinite(Number(formId)) ? Number(formId) : undefined,
      imageId: imageId != null && Number.isFinite(Number(imageId)) ? Number(imageId) : undefined,
      imageUrl: (img as { url?: string }).url,
      imageTitle: (img as { title?: string }).title
    })
    const unsetOk =
      ctx.activeRpsAssetId() == null
        ? true
        : await ctx.reserveUnsetRpsForm({
            imageId: resolvedImageId ?? undefined,
            formId
          })
    if (!unsetOk) {
      // 接口错误提示已在 reserveUnsetRpsForm 内统一提示，避免重复弹两条
      return
    }

    if (inParent) {
      img._isSet = false
      ctx.localSceneImages.set([...ctx.localSceneImages.get()])
      ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent())
    }

    const delKey = normalizeImageId(img.id)
    if (delKey) {
      const n = new Set(ctx.addedImageIds.get())
      n.delete(delKey)
      ctx.addedImageIds.set(n)
    }

    setTimeout(() => {
      const n = ctx.localSceneImages.get().length
      if (n === 0) {
        ctx.currentImageIndex.set(0)
      } else if (ctx.currentImageIndex.get() >= n) {
        ctx.currentImageIndex.set(n - 1)
      }
    }, 0)

    message.success(`已取消设置`)
    } finally {
      if (ctx.cancellingAddAtKey.get() === overlayKey) {
        ctx.cancellingAddAtKey.set('')
      }
    }
  }

  return {
    isAddingSceneImage,
    isCancellingAdd,
    handleUploadLocalImage,
    handleOpenAssetLibrary,
    handleAssetLibraryImport,
    startEditImageTitle,
    handleImageTitleBlur,
    removeImage,
    handleDialogueImportMultiple,
    handleGenerateImportMultiple,
    removeDialogueSourceImage,
    handlePreviewImage,
    handleReplaceImage,
    handleDownloadImage,
    handleDeleteImage,
    handleModifyImage,
    handleSetMainFromHistory,
    handleAddSceneImage,
    handleConfirmAddImage,
    handleCancelAddImage
  }
}
