import { message } from 'antd'
import {
  userAssetRpsFormCreate,
  userAssetRpsFormImageCreate
} from '~/utils/businessApi'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { openImagePreviewModal } from '~/utils/openImagePreviewModal'
import { uploadImageToOssWithToast } from '~/utils/ossUpload'
import { shouldShowAssetImageRegenerateAction } from '~/utils/assetImageActionMode'
import { downloadMediaFileWithToast } from '~/utils/mediaFileDownload'
import { mapImportSourceType, resolveCreatedFormImageId } from './scpRowUtils'
import type { ScpCtx } from './types'

export function createScpSceneImageCrudOps(ctx: ScpCtx) {
  const handleImportSceneImage = (index: number) => {
    ctx.currentImportSceneIndex.set(index)
    ctx.showImportSceneImageModal.set(true)
  }

  const handleSceneImageImport = async (fileOrAsset: File | string | any) => {
    const now = new Date()
    const targetIndex = ctx.currentImportSceneIndex.get()
    if (targetIndex < 0 || targetIndex >= ctx.localValue.get().scenes.length) {
      message.error('导入失败：未找到目标场景')
      return
    }
    if (!ctx.sceneImages.get()[targetIndex]) {
      ctx.sceneImages.set({ ...ctx.sceneImages.get(), [targetIndex]: [] })
    }

    let imageUrl: string
    let imageTitle: string
    let source: string
    if (fileOrAsset instanceof File) {
      const uploaded = await uploadImageToOssWithToast(fileOrAsset)
      if (!uploaded) return
      imageUrl = uploaded
      imageTitle =
        fileOrAsset.name.replace(/\.[^/.]+$/, '') ||
        `场景图${ctx.sceneImages.get()[targetIndex].length + 1}`
      source = '本地上传'
    } else if (typeof fileOrAsset === 'string') {
      imageUrl = fileOrAsset
      imageTitle = `场景图${ctx.sceneImages.get()[targetIndex].length + 1}`
      source = '资源库导入'
    } else if (fileOrAsset && typeof fileOrAsset === 'object') {
      imageUrl =
        fileOrAsset.url ||
        fileOrAsset.thumbnail ||
        `https://picsum.photos/800/450?random=${Date.now()}`
      imageTitle =
        fileOrAsset.name?.replace(/\.[^/.]+$/, '') ||
        `场景图${ctx.sceneImages.get()[targetIndex].length + 1}`
      source = fileOrAsset.type === 'scene' ? '场景资产导入' : '资源库导入'
    } else {
      message.error('导入失败：无效的图片数据')
      return
    }

    let rpsFormId: number | undefined
    let rpsImageId: number | undefined
    const sceneAssetId = ctx.sceneAssetIds.get()[targetIndex]
    if (sceneAssetId != null && Number.isFinite(Number(sceneAssetId))) {
      const saveContext = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      if (saveContext) {
        try {
          const imageCount = ctx.sceneImages.get()[targetIndex]?.length ?? 0
          const existingFormId = ctx.ensureFormIdForRpsUpdate('scene', targetIndex, imageCount)
          if (existingFormId != null) {
            rpsFormId = existingFormId
          } else {
            const row = await userAssetRpsFormCreate({
              projectId: saveContext.projectId,
              episodeId: saveContext.episodeId,
              assetId: Number(sceneAssetId),
              imageUrl,
              name: imageTitle,
              sourceType: mapImportSourceType(source)
            })
            ctx.applyRpsRowFormIds('scene', targetIndex, row)
            const ids = ctx.sceneFormIdsByIndex.get()[targetIndex] ?? []
            const last = ids[ids.length - 1]
            if (last != null && Number.isFinite(Number(last))) rpsFormId = Number(last)
          }
          if (rpsFormId != null) {
            const created = await userAssetRpsFormImageCreate({
              formId: rpsFormId,
              imageUrl,
              name: imageTitle,
              sourceType: mapImportSourceType(source),
              asInUse: true
            })
            rpsImageId = resolveCreatedFormImageId(created) ?? undefined
            if (rpsImageId != null) await ctx.tryUseFormImage({ imageId: rpsImageId })
          }
        } catch {
          // 后端同步失败不阻断已上传图片的本地回显。
        }
      }
    }

    const nextList = [...(ctx.sceneImages.get()[targetIndex] ?? [])]
    nextList.push({
      id: Date.now().toString(),
      ...(rpsFormId != null ? { rpsFormId } : {}),
      ...(rpsImageId != null ? { rpsImageId } : {}),
      url: imageUrl,
      thumbnail: imageUrl,
      title: imageTitle,
      createdAt: now.toISOString(),
      source,
      importDate: now.toISOString(),
      angles: []
    })
    ctx.sceneImages.set({ ...ctx.sceneImages.get(), [targetIndex]: nextList })
    message.success(`已为「${ctx.localValue.get().scenes[targetIndex]}」导入场景图片`)
    ctx.showImportSceneImageModal.set(false)
  }

  const getSceneImage = (index: number) => ctx.sceneImages.get()[index]?.[0] ?? null

  const handlePreviewSceneImage = (index: number) => {
    if (getSceneImage(index)) message.info('点击图片可放大预览')
  }
  const handleReplaceSceneImage = (index: number) => handleImportSceneImage(index)
  const handleDownloadSceneImage = (index: number) => {
    const image = getSceneImage(index)
    if (!image?.url) {
      message.warning('暂无图片可下载')
      return
    }
    void downloadMediaFileWithToast(image.url, image.title || `场景图${index + 1}.png`, '图片')
  }
  const handleDeleteSceneImage = (index: number) => {
    if (!ctx.sceneImages.get()[index]?.length) return
    ctx.sceneImages.set({ ...ctx.sceneImages.get(), [index]: [] })
    message.success('场景图片已删除')
  }
  const handlePreviewSceneImageByIndex = (sceneIndex: number, imageIndex: number) => {
    const image = ctx.sceneImages.get()[sceneIndex]?.[imageIndex]
    if (!image?.url) {
      message.warning('暂无图片可预览')
      return
    }
    openImagePreviewModal({ url: image.url, title: image.title || `场景图${imageIndex + 1}` })
  }
  const handleReplaceSceneImageByIndex = (sceneIndex: number, _imageIndex: number) => {
    handleImportSceneImage(sceneIndex)
  }
  const handleSceneImageMiddleActionByIndex = (sceneIndex: number, imageIndex: number) => {
    const image = ctx.sceneImages.get()[sceneIndex]?.[imageIndex]
    if (shouldShowAssetImageRegenerateAction(image, ctx.canAutoGenerateSceneImage(sceneIndex))) {
      const formId = Number(image?.rpsFormId)
      ctx.handleAutoGenerateScene(
        sceneIndex,
        Number.isFinite(formId) && formId > 0 ? formId : undefined
      )
      return
    }
    handleReplaceSceneImageByIndex(sceneIndex, imageIndex)
  }
  const handleDownloadSceneImageByIndex = (sceneIndex: number, imageIndex: number) => {
    const image = ctx.sceneImages.get()[sceneIndex]?.[imageIndex]
    if (!image?.url) {
      message.warning('暂无图片可下载')
      return
    }
    void downloadMediaFileWithToast(
      image.url,
      image.title || `场景图${sceneIndex + 1}-${imageIndex + 1}.png`,
      '图片'
    )
  }
  const handleDeleteSceneImageByIndex = async (sceneIndex: number, imageIndex: number) => {
    const images = ctx.sceneImages.get()[sceneIndex]
    if (!images?.[imageIndex]) return
    const image = images[imageIndex]
    const imageId = Number.isFinite(Number(image.rpsImageId)) ? Number(image.rpsImageId) : null
    const indexedFormId = (ctx.sceneFormIdsByIndex.get()[sceneIndex] ?? [])[imageIndex]
    const formId = Number.isFinite(Number(image.rpsFormId)) ? Number(image.rpsFormId) : indexedFormId
    if (!(await ctx.tryUnuseFormImage({ imageId, formId: formId ?? null }))) return
    images.splice(imageIndex, 1)
    ctx.sceneImages.set({ ...ctx.sceneImages.get() })
    message.success('已取消主图展示')
    ctx.syncStep3AssetsToCreationStore()
  }

  return {
    handleImportSceneImage,
    handleSceneImageImport,
    getSceneImage,
    handlePreviewSceneImage,
    handleReplaceSceneImage,
    handleDownloadSceneImage,
    handleDeleteSceneImage,
    handlePreviewSceneImageByIndex,
    handleReplaceSceneImageByIndex,
    handleSceneImageMiddleActionByIndex,
    handleDownloadSceneImageByIndex,
    handleDeleteSceneImageByIndex
  }
}
