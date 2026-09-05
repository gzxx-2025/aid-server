'use client'

import { message,Modal } from 'antd'
import {
userAssetRpsFormImageDelete,
userAssetRpsFormImageUpdate
} from '~/utils/businessApi'
import { FORM_IMAGE_REFERENCE_LIMIT } from '~/utils/formImageEditPrefill'
import { openImagePreviewModal } from '~/utils/openImagePreviewModal'
import { downloadMediaFileWithToast } from '~/utils/mediaFileDownload'
import { uploadImageToOssWithToast } from '~/utils/ossUpload'
import {
handleAssetLibraryImportImpl,
handleUploadLocalImageImpl
} from './sceneModalImportImages'
import {
resolveRpsImageIdFromLocalImage
} from './sceneModalTaskParsers'
import type { DialogueSourceImage,EditSceneImageModalCtx } from './types'

export function createSceneModalImageInteractionOps(ctx: EditSceneImageModalCtx) {
  const isAddingSceneImage = () =>
    !!ctx.addingSceneImageAtKey.get() &&
    ctx.addingSceneImageAtKey.get() ===
      ctx.buildCanvasOverlayKey(ctx.currentSceneIndex.get(), ctx.currentImageIndex.get())

  const isCancellingAdd = () =>
    !!ctx.cancellingAddAtKey.get() &&
    ctx.cancellingAddAtKey.get() ===
      ctx.buildCanvasOverlayKey(ctx.currentSceneIndex.get(), ctx.currentImageIndex.get())

  // 本地上传图片（实现移至 sceneModalImportImages.ts，仅代码搬移不改逻辑）
  const handleUploadLocalImage = () => handleUploadLocalImageImpl(ctx)

  // 打开资源库
  const handleOpenAssetLibrary = () => {
    ctx.showAssetLibraryModal.set(true)
  }

  // 处理资源库导入（实现移至 sceneModalImportImages.ts，仅代码搬移不改逻辑）
  const handleAssetLibraryImport = (asset: any) => handleAssetLibraryImportImpl(ctx, asset)

  // 编辑图片标题
  const startEditImageTitle = (index: number) => {
    ctx.editingImageTitleIndex.set(index)
    const img = ctx.currentSceneImages()[index]
    ctx.editingImageTitle.set(
      String(img?.title || img?.name || '').trim() || ctx.getImageTitleFallback(index)
    )
  }

  /** 与列表点击改名一致：POST /api/user/asset/rps/form-image/update */
  const handleImageTitleBlur = async (index: number) => {
    if (ctx.editingImageTitleIndex.get() !== index) return
    const nextTitle = ctx.editingImageTitle.get().trim()
    // 先清空编辑态，避免 press-enter + blur 重复提交
    ctx.editingImageTitleIndex.set(null)
    ctx.editingImageTitle.set('')
    if (!nextTitle) return

    const img = ctx.localSceneImages.get()[index] || ctx.currentSceneImages()[index]
    const prevTitle =
      String(img?.title || img?.name || '').trim() || ctx.getImageTitleFallback(index)
    if (nextTitle === prevTitle) return

    const imageId = resolveRpsImageIdFromLocalImage(img)
    const localId = img?.id
    if (imageId != null) {
      try {
        await userAssetRpsFormImageUpdate({ imageId, name: nextTitle })
      } catch (e: unknown) {
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || '图片名称同步失败')
        return
      }
    }

    // await 后按 imageId/id 定位，避免列表刷新导致按旧 index 写错槽
    let writeIndex = index
    if (imageId != null) {
      const byRps = ctx.localSceneImages.get().findIndex(
        (row) => resolveRpsImageIdFromLocalImage(row) === imageId
      )
      if (byRps >= 0) writeIndex = byRps
    } else if (localId != null) {
      const byId = ctx.localSceneImages.get().findIndex((row) => row?.id === localId)
      if (byId >= 0) writeIndex = byId
    }

    if (ctx.localSceneImages.get()[writeIndex]) {
      const arr = [...ctx.localSceneImages.get()]
      arr[writeIndex] = {
        ...arr[writeIndex],
        title: nextTitle,
        name: nextTitle
      }
      ctx.localSceneImages.set(arr)
    }

    // 只回写 images，避免父组件因带 setting 误走 update-main
    ctx.props().onUpdate(ctx.currentSceneIndex.get(), { images: ctx.buildVisibleImagesForParent() })
    message.success('标题已更新')
  }

  // 移除图片
  const removeImage = (index: number) => {
    const updatedScenes = [...ctx.props().scenes]
    if (updatedScenes[ctx.currentSceneIndex.get()].images) {
      updatedScenes[ctx.currentSceneIndex.get()].images!.splice(index, 1)
      // 调整当前图片索引
      if (ctx.currentImageIndex.get() >= updatedScenes[ctx.currentSceneIndex.get()].images!.length) {
        ctx.currentImageIndex.set(Math.max(0, updatedScenes[ctx.currentSceneIndex.get()].images!.length - 1))
      }
      ctx.props().onUpdate(ctx.currentSceneIndex.get(), updatedScenes[ctx.currentSceneIndex.get()])
      message.success('图片已移除')
    }
  }

  function handleDialogueImportMultiple(payload: { sceneIndex: number; images: any[] }) {
    void payload.sceneIndex
    const list = (payload.images || [])
      .map((img) => {
        const url = String(img?.url || img?.thumbnail || '').trim()
        if (!url) return null
        return { url, title: img?.title || img?.name }
      })
      .filter(Boolean) as DialogueSourceImage[]
    const existed = new Set(ctx.dialogueSourceImages.get().map((item) => item.url))
    const toAppend = list.filter((item) => !existed.has(item.url))
    const remaining = FORM_IMAGE_REFERENCE_LIMIT - ctx.dialogueSourceImages.get().length
    const finalAppend = toAppend.slice(0, Math.max(0, remaining))
    if (finalAppend.length < toAppend.length) {
      message.warning(`参考图最多 ${FORM_IMAGE_REFERENCE_LIMIT} 张，已截取前几张`)
    }
    ctx.dialogueSourceImages.set([...ctx.dialogueSourceImages.get(), ...finalAppend])
    if (finalAppend.length > 0) {
      message.success(`已导入 ${finalAppend.length} 张参考图`)
    }
  }

  function removeDialogueSourceImage(index: number) {
    ctx.dialogueSourceImages.set(ctx.dialogueSourceImages.get().filter((_, i) => i !== index))
  }

  // 图片操作
  const handlePreviewImage = (index: number) => {
    const img = ctx.currentSceneImages()[index]
    if (img && img.url) {
      openImagePreviewModal({
        url: img.url,
        title: img.title || `场景图${index + 1}`
      })
    } else {
      message.warning('暂无图片可预览')
    }
  }

  const handleReplaceImage = (index: number) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = 'image/*'
    input.onchange = async (e: any) => {
      const file = e.target.files[0]
      if (file) {
        const url = await uploadImageToOssWithToast(file)
        if (!url) return
        const tabIdx = ctx.currentSceneIndex.get()
        const updatedScenes = [...ctx.props().scenes]
        if (updatedScenes[tabIdx].images && updatedScenes[tabIdx].images![index]) {
          updatedScenes[tabIdx].images![index].url = url
          updatedScenes[tabIdx].images![index].thumbnail = url
          ctx.currentImageIndex.set(index)
          const snapshotImages = [...updatedScenes[tabIdx].images!]
          const title = snapshotImages[index]?.title || ''
          const rpsImageIdRaw = Number(snapshotImages[index]?.rpsImageId)
          if (Number.isFinite(rpsImageIdRaw)) {
            try {
              await userAssetRpsFormImageUpdate({
                imageId: rpsImageIdRaw,
                imageUrl: url,
                name: title
              })
            } catch (e: unknown) {
              const err = e as { msg?: string; message?: string }
              message.warning(err?.msg || err?.message || '同步个人资产形态图失败')
            }
          }
          if (Number.isFinite(rpsImageIdRaw)) {
            ctx.emitSceneTabUpdate(snapshotImages, tabIdx)
          } else {
            ;(async () => {
              const result = await ctx.syncImageToRpsApi(url, title, 'upload', snapshotImages[index])
              if (result?.imageId != null) {
                snapshotImages[index] = { ...snapshotImages[index], rpsImageId: result.imageId }
              }
              if (result?.formId != null) {
                snapshotImages[index] = { ...snapshotImages[index], rpsFormId: result.formId }
              }
              ctx.emitSceneTabUpdate(snapshotImages, tabIdx)
            })()
          }
          message.success('图片已替换')
        }
      }
    }
    input.click()
  }

  const handleDownloadImage = (index: number) => {
    const img = ctx.currentSceneImages()[index]
    if (img && img.url) {
      void downloadMediaFileWithToast(img.url, img.title || `场景图${index + 1}.png`, '图片')
    } else {
      message.warning('暂无图片可下载')
    }
  }

  const handleDeleteImage = (index: number) => {
    Modal.confirm({
      title: '确认删除',
      content: '确定要删除这张图片吗？',
      okText: '确定',
      cancelText: '取消',
      onOk: async () => {
        const img = ctx.currentSceneImages()[index] as { rpsImageId?: number } | undefined
        const rpsImageId = Number(img?.rpsImageId)
        if (Number.isFinite(rpsImageId)) {
          try {
            await userAssetRpsFormImageDelete({ imageId: rpsImageId })
          } catch (e: unknown) {
            const err = e as { msg?: string; message?: string }
            message.warning(err?.msg || err?.message || '删除后端形态图失败，仅移除本地展示')
          }
        }
        // 重新拉取，确保后端可能已调整 isUse 主图状态
        await ctx.initFormImageListOnOpen()
        ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent())
      }
    })
  }

  const handleModifyImage = (index: number) => {
    const img = ctx.currentSceneImages()[index]
    if (!img?.url) {
      message.warning('请先选择一张可编辑的图片')
      return
    }
    ctx.touchEditImageUrl.set(img.url)
    ctx.showTouchEditModal.set(true)
  }

  return {
    handleAssetLibraryImport,
    handleDeleteImage,
    handleDialogueImportMultiple,
    handleDownloadImage,
    handleImageTitleBlur,
    handleModifyImage,
    handleOpenAssetLibrary,
    handlePreviewImage,
    handleReplaceImage,
    handleUploadLocalImage,
    isAddingSceneImage,
    isCancellingAdd,
    removeDialogueSourceImage,
    removeImage,
    startEditImageTitle,
  }
}
