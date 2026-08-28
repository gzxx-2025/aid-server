'use client'

import { message } from 'antd'
import {
  userAssetRpsFormCreate,
  userAssetRpsFormImageCreate,
  userAssetRpsUpdateMain
} from '~/utils/businessApi'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { buildUpdateMainPayloadFromSettingHtml } from '~/utils/characterSettingProfile'
import { openImagePreviewModal } from '~/utils/openImagePreviewModal'
import { uploadImageToOssWithToast } from '~/utils/ossUpload'
import { shouldShowAssetImageRegenerateAction } from '~/utils/assetImageActionMode'
import { downloadMediaFileWithToast } from '~/utils/mediaFileDownload'
import { mapImportSourceType, resolveCreatedFormImageId } from './scpRowUtils'
import { syncImageTitleToRps } from './useScpRpsOps'
import { preloadEditSceneImageModal } from './editSceneImageModalLoader'
import { createScpPropPrimaryImageOps } from './scpPropPrimaryImageOps'
import type { ScpCtx } from './types'

export interface ScpPropImagesApi {
  handleImportPropImage: (index: number) => void
  handlePropImageImport: (fileOrAsset: File | string | any) => Promise<void>
  handleEditPropImage: (index: number) => void
  handleEditPropImageWithIndex: (propIndex: number, imageIndex: number) => void
  handlePropImageUpdate: (propIndex: number, propData: any) => Promise<void>
  handleAutoGenerateProp: (index: number) => void
  handleImportPropFormImage: (propIndex: number, formIndex: number) => void
  handlePropFormImageImport: (fileOrAsset: File | string | any) => Promise<void>
  handleEditPropFormImage: (propIndex: number, formIndex: number) => void
  handleEditPropFormImageWithIndex: (propIndex: number, formIndex: number, imageIndex: number) => void
  handlePropFormImageUpdate: (formKey: string, formData: any) => void
  handleAutoGeneratePropForm: (propIndex: number, formIndex: number) => Promise<void>
  // 道具形态图片操作函数
  startEditPropFormImageTitle: (
    propIndex: number,
    formIndex: number,
    imageIndex: number,
    currentTitle: string
  ) => void
  handlePropFormImageTitleBlur: (
    propIndex: number,
    formIndex: number,
    _imageIndex: number
  ) => Promise<void>
  handlePreviewPropFormImageByIndex: (propIndex: number, formIndex: number, imageIndex: number) => void
  handleReplacePropFormImageByIndex: (propIndex: number, formIndex: number, imageIndex: number) => void
  handlePropFormImageMiddleActionByIndex: (
    propIndex: number,
    formIndex: number,
    imageIndex: number
  ) => void
  handleDownloadPropFormImageByIndex: (
    propIndex: number,
    formIndex: number,
    imageIndex: number
  ) => void
  handleDeletePropFormImageByIndex: (
    propIndex: number,
    formIndex: number,
    imageIndex: number
  ) => Promise<void>
  // 道具图片操作函数
  startEditPropImageTitle: (propIndex: number, imageIndex: number, currentTitle: string) => void
  handlePropImageTitleBlur: (propIndex: number, imageIndex: number) => void
  handlePreviewPropImageByIndex: (propIndex: number, imageIndex: number) => void
  handleReplacePropImageByIndex: (propIndex: number, imageIndex: number) => void
  handleDownloadPropImageByIndex: (propIndex: number, imageIndex: number) => void
  handleDeletePropImageByIndex: (propIndex: number, imageIndex: number) => Promise<void>
}

export function useScpPropImages(ctx: ScpCtx): ScpPropImagesApi {
  const {
    handleImportPropImage,
    handlePropImageImport,
    handleEditPropImage,
    handleEditPropImageWithIndex,
    handlePropImageUpdate
  } = createScpPropPrimaryImageOps(ctx)

  const handleAutoGenerateProp = (index: number) => {
    const propName = ctx.localValue.get().props[index]
    message.info(`正在为「${propName}」自动生成道具图（待接入实际技能/接口）`)
  }

  const handleImportPropFormImage = (propIndex: number, formIndex: number) => {
    ctx.currentImportPropFormKey.set(`${propIndex}-${formIndex}`)
    ctx.showImportPropFormImageModal.set(true)
  }

  const handlePropFormImageImport = async (fileOrAsset: File | string | any) => {
    const now = new Date()
    let imageUrl: string
    let imageTitle: string
    let source: string
    const [propIndex, formIndex] = ctx.currentImportPropFormKey.get().split('-').map(Number)
    const formKey = ctx.currentImportPropFormKey.get()

    // 添加图片到道具形态
    if (!ctx.propFormImages.get()[formKey]) {
      ctx.propFormImages.set({ ...ctx.propFormImages.get(), [formKey]: [] })
    }

    // 处理不同类型的导入
    if (fileOrAsset instanceof File) {
      const uploaded = await uploadImageToOssWithToast(fileOrAsset)
      if (!uploaded) return
      imageUrl = uploaded
      imageTitle =
        fileOrAsset.name.replace(/\.[^/.]+$/, '') ||
        `形态图${ctx.propFormImages.get()[formKey].length + 1}`
      source = '本地上传'
    } else if (typeof fileOrAsset === 'string') {
      imageUrl = fileOrAsset
      imageTitle = `形态图${ctx.propFormImages.get()[formKey].length + 1}`
      source = '资源库导入'
    } else if (fileOrAsset && typeof fileOrAsset === 'object') {
      imageUrl =
        fileOrAsset.url ||
        fileOrAsset.thumbnail ||
        'https://picsum.photos/800/450?random=' + Date.now()
      imageTitle =
        fileOrAsset.name?.replace(/\.[^/.]+$/, '') ||
        `形态图${ctx.propFormImages.get()[formKey].length + 1}`
      source = '资源库导入'
    } else {
      message.error('导入失败：无效的图片数据')
      return
    }

    const formId = ctx.ensureFormIdForRpsUpdate('prop', propIndex, formIndex)
    let createdImageId: number | undefined
    if (formId != null) {
      try {
        const created = await userAssetRpsFormImageCreate({
          formId,
          imageUrl,
          name: imageTitle,
          sourceType: mapImportSourceType(source)
        })
        createdImageId = resolveCreatedFormImageId(created) ?? undefined
        if (createdImageId != null) {
          await ctx.tryUseFormImage({ imageId: createdImageId })
        }
      } catch {
        // 不阻断本地回显，避免用户操作丢失
      }
    }

    const nextList = [...(ctx.propFormImages.get()[formKey] ?? [])]
    nextList.push({
      id: Date.now().toString(),
      ...(formId != null ? { rpsFormId: formId } : {}),
      ...(createdImageId != null ? { rpsImageId: createdImageId } : {}),
      url: imageUrl,
      thumbnail: imageUrl,
      title: imageTitle,
      createdAt: now.toISOString(),
      source: source,
      importDate: now.toISOString(),
      angles: []
    })

    ctx.propFormImages.set({ ...ctx.propFormImages.get(), [formKey]: nextList })

    const formName = ctx.propForms.get()[propIndex][formIndex].name
    message.success(`已为「${formName}」导入形态图片`)
    ctx.showImportPropFormImageModal.set(false)
  }

  const handleEditPropFormImage = (propIndex: number, formIndex: number) => {
    if (ctx.isPropFormEditImageDisabled(propIndex, formIndex)) return
    void preloadEditSceneImageModal()
    ctx.currentEditPropFormKey.set(`${propIndex}-${formIndex}`)
    ctx.currentEditPropFormImageIndex.set(null)
    ctx.showEditPropFormImageModal.set(true)
  }

  const handleEditPropFormImageWithIndex = (
    propIndex: number,
    formIndex: number,
    imageIndex: number
  ) => {
    if (ctx.isPropFormEditImageDisabled(propIndex, formIndex)) return
    void preloadEditSceneImageModal()
    ctx.currentEditPropFormKey.set(`${propIndex}-${formIndex}`)
    ctx.currentEditPropFormImageIndex.set(imageIndex)
    ctx.showEditPropFormImageModal.set(true)
  }

  const handlePropFormImageUpdate = (formKey: string, formData: any) => {
    if (formData && formData.images) {
      ctx.propFormImages.set({
        ...ctx.propFormImages.get(),
        [formKey]: Array.isArray(formData.images)
          ? formData.images.map((img: any) => ({ ...img }))
          : []
      })
    }
    if (formData?.rpsRow) {
      const pi = Number(formKey.split('-')[0])
      if (Number.isFinite(pi)) ctx.applyRpsRowFormIds('prop', pi, formData.rpsRow)
    }
  }

  const handleAutoGeneratePropForm = async (propIndex: number, formIndex: number) => {
    if (!ctx.canAutoGeneratePropFormImage(propIndex, formIndex)) {
      message.info('手动新增的形态请使用「图片导入」上传配图')
      return
    }
    const form = ctx.propForms.get()[propIndex]?.[formIndex]
    if (!form) return
    const formKey = `${propIndex}-${formIndex}`
    const formName = form.name
    const routeCtx = ctx.captureStep3RouteContext()
    ctx.propFormGenerationStatus.set({ ...ctx.propFormGenerationStatus.get(), [formKey]: 'generating' })
    ctx.store().setPropFormGenerationStatus(formKey, 'generating')
    let aid = ctx.propAssetIds.get()[propIndex]
    if (aid == null) {
      await ctx.loadPersonalAssetsForTab('prop')
      if (!ctx.matchesStep3RouteContext(routeCtx)) return
      aid = ctx.propAssetIds.get()[propIndex]
    }
    if (aid == null) {
      ctx.patchPropFormGenStatus(formKey, 'idle', routeCtx)
      message.warning('缺少道具资产ID，请刷新页面后重试')
      return
    }
    const formId = await ctx.resolveFormIdForAssetForm('prop', propIndex, formIndex)
    if (!ctx.matchesStep3RouteContext(routeCtx)) return
    if (formId == null) {
      ctx.patchPropFormGenStatus(formKey, 'idle', routeCtx)
      message.warning('未找到该形态的 ID，请稍后重试或先点击「新增形态」保存后再生成')
      return
    }
    await ctx.runSingleFormGenerate({
      assetId: Number(aid),
      tab: 'prop',
      formKey,
      formName,
      formId
    })
  }

  // 道具形态图片操作函数
  const startEditPropFormImageTitle = (
    propIndex: number,
    formIndex: number,
    imageIndex: number,
    currentTitle: string
  ) => {
    ctx.editingImageTitleIndex.set(`prop-form-${propIndex}-${formIndex}-${imageIndex}`)
    ctx.editingImageTitle.set(currentTitle || `形态图${imageIndex + 1}`)
  }

  const handlePropFormImageTitleBlur = async (
    propIndex: number,
    formIndex: number,
    imageIndex: number
  ) => {
    const formKey = `${propIndex}-${formIndex}`
    if (
      ctx.editingImageTitleIndex.get() === `prop-form-${propIndex}-${formIndex}-${imageIndex}` &&
      ctx.editingImageTitle.get().trim()
    ) {
      const images = ctx.propFormImages.get()[formKey]
      if (images && images[imageIndex]) {
        const nextTitle = ctx.editingImageTitle.get().trim()
        const ok = await syncImageTitleToRps(images[imageIndex], nextTitle)
        if (!ok) {
          ctx.editingImageTitleIndex.set(null)
          ctx.editingImageTitle.set('')
          return
        }
        images[imageIndex].title = nextTitle
        ctx.propFormImages.set({ ...ctx.propFormImages.get() })
        message.success('形态图名称已更新')
      }
    }
    ctx.editingImageTitleIndex.set(null)
    ctx.editingImageTitle.set('')
  }

  const handlePreviewPropFormImageByIndex = (
    propIndex: number,
    formIndex: number,
    imageIndex: number
  ) => {
    const formKey = `${propIndex}-${formIndex}`
    const images = ctx.propFormImages.get()[formKey]
    const item = images?.[imageIndex]
    if (item?.url) {
      openImagePreviewModal({
        url: item.url,
        title: item.title || `形态图${imageIndex + 1}`
      })
    }
  }

  const handleReplacePropFormImageByIndex = (
    propIndex: number,
    formIndex: number,
    _imageIndex: number
  ) => {
    ctx.currentImportPropFormKey.set(`${propIndex}-${formIndex}`)
    ctx.showImportPropFormImageModal.set(true)
    // TODO: 替换指定索引的图片
  }

  const handlePropFormImageMiddleActionByIndex = (
    propIndex: number,
    formIndex: number,
    imageIndex: number
  ) => {
    const img = ctx.propFormImages.get()[`${propIndex}-${formIndex}`]?.[imageIndex]
    if (
      shouldShowAssetImageRegenerateAction(
        img,
        ctx.canAutoGeneratePropFormImage(propIndex, formIndex)
      )
    ) {
      handleAutoGeneratePropForm(propIndex, formIndex)
      return
    }
    handleReplacePropFormImageByIndex(propIndex, formIndex, imageIndex)
  }

  const handleDownloadPropFormImageByIndex = (
    propIndex: number,
    formIndex: number,
    imageIndex: number
  ) => {
    const formKey = `${propIndex}-${formIndex}`
    const images = ctx.propFormImages.get()[formKey]
    const img = images && images[imageIndex]
    if (img && img.url) {
      void downloadMediaFileWithToast(
        img.url,
        img.title || `形态图${propIndex + 1}-${formIndex + 1}-${imageIndex + 1}.png`,
        '图片'
      )
    } else {
      message.warning('暂无图片可下载')
    }
  }

  const handleDeletePropFormImageByIndex = async (
    propIndex: number,
    formIndex: number,
    imageIndex: number
  ) => {
    const formKey = `${propIndex}-${formIndex}`
    const images = ctx.propFormImages.get()[formKey]
    if (!images || images.length <= imageIndex) return
    const img = images[imageIndex]
    const imageId =
      img?.rpsImageId != null && Number.isFinite(Number(img.rpsImageId))
        ? Number(img.rpsImageId)
        : null
    const formId =
      img?.rpsFormId != null && Number.isFinite(Number(img.rpsFormId))
        ? Number(img.rpsFormId)
        : ctx.ensureFormIdForRpsUpdate('prop', propIndex, formIndex)
    if (!(await ctx.tryUnuseFormImage({ imageId, formId }))) return
    images.splice(imageIndex, 1)
    ctx.propFormImages.set({ ...ctx.propFormImages.get() })
    // 同步清理资产级缓存，避免批量弹窗读到 propImages 旧图
    const assetImgs = [...(ctx.propImages.get()[propIndex] ?? [])]
    const nextAsset = assetImgs.filter((x) => {
      if (imageId != null && Number(x?.rpsImageId) === imageId) return false
      if (
        formId != null &&
        Number(x?.rpsFormId) === formId &&
        String(x?.url ?? '').trim() === String(img?.url ?? '').trim()
      ) {
        return false
      }
      return true
    })
    ctx.propImages.set({ ...ctx.propImages.get(), [propIndex]: nextAsset })
    message.success('已取消主图展示')
    ctx.syncStep3AssetsToCreationStore()
  }

  // 道具图片操作函数
  const startEditPropImageTitle = (propIndex: number, imageIndex: number, currentTitle: string) => {
    ctx.editingImageTitleIndex.set(`prop-${propIndex}-${imageIndex}`)
    ctx.editingImageTitle.set(currentTitle || `道具图${imageIndex + 1}`)
  }

  const handlePropImageTitleBlur = (propIndex: number, imageIndex: number) => {
    if (
      ctx.editingImageTitleIndex.get() === `prop-${propIndex}-${imageIndex}` &&
      ctx.editingImageTitle.get().trim()
    ) {
      const images = ctx.propImages.get()[propIndex]
      if (images && images[imageIndex]) {
        images[imageIndex].title = ctx.editingImageTitle.get().trim()
        ctx.propImages.set({ ...ctx.propImages.get() })
        message.success('道具图名称已更新')
      }
    }
    ctx.editingImageTitleIndex.set(null)
    ctx.editingImageTitle.set('')
  }

  const handlePreviewPropImageByIndex = (propIndex: number, imageIndex: number) => {
    const images = ctx.propImages.get()[propIndex]
    const item = images?.[imageIndex]
    if (item?.url) {
      openImagePreviewModal({
        url: item.url,
        title: item.title || `道具图${imageIndex + 1}`
      })
    }
  }

  const handleReplacePropImageByIndex = (propIndex: number, _imageIndex: number) => {
    ctx.currentImportPropIndex.set(propIndex)
    ctx.showImportPropImageModal.set(true)
    // TODO: 替换指定索引的图片
  }

  const handleDownloadPropImageByIndex = (propIndex: number, imageIndex: number) => {
    const images = ctx.propImages.get()[propIndex]
    const img = images && images[imageIndex]
    if (img && img.url) {
      void downloadMediaFileWithToast(
        img.url,
        img.title || `道具图${propIndex + 1}-${imageIndex + 1}.png`,
        '图片'
      )
    } else {
      message.warning('暂无图片可下载')
    }
  }

  const handleDeletePropImageByIndex = async (propIndex: number, imageIndex: number) => {
    const images = ctx.propImages.get()[propIndex]
    if (!images || images.length <= imageIndex) return
    const img = images[imageIndex]
    const imageId =
      img?.rpsImageId != null && Number.isFinite(Number(img.rpsImageId))
        ? Number(img.rpsImageId)
        : null
    const formId =
      img?.rpsFormId != null && Number.isFinite(Number(img.rpsFormId))
        ? Number(img.rpsFormId)
        : ((ctx.propFormIdsByIndex.get()[propIndex] ?? [])[imageIndex] ?? null)
    if (!(await ctx.tryUnuseFormImage({ imageId, formId }))) return
    images.splice(imageIndex, 1)
    ctx.propImages.set({ ...ctx.propImages.get() })
    message.success('已取消主图展示')
    ctx.syncStep3AssetsToCreationStore()
  }

  return {
    handleImportPropImage,
    handlePropImageImport,
    handleEditPropImage,
    handleEditPropImageWithIndex,
    handlePropImageUpdate,
    handleAutoGenerateProp,
    handleImportPropFormImage,
    handlePropFormImageImport,
    handleEditPropFormImage,
    handleEditPropFormImageWithIndex,
    handlePropFormImageUpdate,
    handleAutoGeneratePropForm,
    startEditPropFormImageTitle,
    handlePropFormImageTitleBlur,
    handlePreviewPropFormImageByIndex,
    handleReplacePropFormImageByIndex,
    handlePropFormImageMiddleActionByIndex,
    handleDownloadPropFormImageByIndex,
    handleDeletePropFormImageByIndex,
    startEditPropImageTitle,
    handlePropImageTitleBlur,
    handlePreviewPropImageByIndex,
    handleReplacePropImageByIndex,
    handleDownloadPropImageByIndex,
    handleDeletePropImageByIndex
  }
}
