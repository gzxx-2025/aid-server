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
import { createScpCharacterPrimaryImageOps } from './scpCharacterPrimaryImageOps'
import type { ScpCharacterImagesApi } from './scpCharacterImagesTypes'
import type { ScpCtx } from './types'

export type { ScpCharacterImagesApi } from './scpCharacterImagesTypes'

export function useScpCharacterImages(ctx: ScpCtx): ScpCharacterImagesApi {
  const {
    handleImportCharacterImage,
    handleCharacterImageImport,
    handleEditCharacterImage,
    handleEditCharacterImageWithIndex,
    handleCharacterImageUpdate
  } = createScpCharacterPrimaryImageOps(ctx)

  const handleAutoGenerateCharacter = (index: number) => {
    const characterName = ctx.localValue.get().characters[index]
    message.info(`正在为「${characterName}」自动生成角色图（待接入实际技能/接口）`)
  }

  const handleImportCharacterFormImage = (characterIndex: number, formIndex: number) => {
    ctx.currentImportCharacterFormKey.set(`${characterIndex}-${formIndex}`)
    ctx.showImportCharacterFormImageModal.set(true)
  }

  const handleCharacterFormImageImport = async (fileOrAsset: File | string | any) => {
    const now = new Date()
    let imageUrl: string
    let imageTitle: string
    let source: string
    const [characterIndex, formIndex] = ctx.currentImportCharacterFormKey.get().split('-').map(Number)
    const formKey = ctx.currentImportCharacterFormKey.get()

    // 添加图片到角色形态
    if (!ctx.characterFormImages.get()[formKey]) {
      ctx.characterFormImages.set({ ...ctx.characterFormImages.get(), [formKey]: [] })
    }

    // 处理不同类型的导入
    if (fileOrAsset instanceof File) {
      const uploaded = await uploadImageToOssWithToast(fileOrAsset)
      if (!uploaded) return
      imageUrl = uploaded
      imageTitle =
        fileOrAsset.name.replace(/\.[^/.]+$/, '') ||
        `形态图${ctx.characterFormImages.get()[formKey].length + 1}`
      source = '本地上传'
    } else if (typeof fileOrAsset === 'string') {
      imageUrl = fileOrAsset
      imageTitle = `形态图${ctx.characterFormImages.get()[formKey].length + 1}`
      source = '资源库导入'
    } else if (fileOrAsset && typeof fileOrAsset === 'object') {
      imageUrl =
        fileOrAsset.url ||
        fileOrAsset.thumbnail ||
        'https://picsum.photos/800/450?random=' + Date.now()
      imageTitle =
        fileOrAsset.name?.replace(/\.[^/.]+$/, '') ||
        `形态图${ctx.characterFormImages.get()[formKey].length + 1}`
      source = '资源库导入'
    } else {
      message.error('导入失败：无效的图片数据')
      return
    }

    const formId = ctx.ensureFormIdForRpsUpdate('character', characterIndex, formIndex)
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

    const nextList = [...(ctx.characterFormImages.get()[formKey] ?? [])]
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

    ctx.characterFormImages.set({ ...ctx.characterFormImages.get(), [formKey]: nextList })

    const formName = ctx.characterForms.get()[characterIndex][formIndex].name
    message.success(`已为「${formName}」导入形态图片`)
    ctx.showImportCharacterFormImageModal.set(false)
  }

  const handleEditCharacterFormImage = (characterIndex: number, formIndex: number) => {
    if (ctx.isCharacterFormEditImageDisabled(characterIndex, formIndex)) return
    void preloadEditSceneImageModal()
    ctx.currentEditCharacterFormKey.set(`${characterIndex}-${formIndex}`)
    ctx.currentEditCharacterFormImageIndex.set(null)
    ctx.showEditCharacterFormImageModal.set(true)
  }

  const handleEditCharacterFormImageWithIndex = (
    characterIndex: number,
    formIndex: number,
    imageIndex: number
  ) => {
    if (ctx.isCharacterFormEditImageDisabled(characterIndex, formIndex)) return
    void preloadEditSceneImageModal()
    ctx.currentEditCharacterFormKey.set(`${characterIndex}-${formIndex}`)
    ctx.currentEditCharacterFormImageIndex.set(imageIndex)
    ctx.showEditCharacterFormImageModal.set(true)
  }

  const handleCharacterFormImageUpdate = (formKey: string, formData: any) => {
    if (formData && formData.images) {
      ctx.characterFormImages.set({
        ...ctx.characterFormImages.get(),
        [formKey]: Array.isArray(formData.images)
          ? formData.images.map((img: any) => ({ ...img }))
          : []
      })
    }
    if (formData?.rpsRow) {
      const ci = Number(formKey.split('-')[0])
      if (Number.isFinite(ci)) ctx.applyRpsRowFormIds('character', ci, formData.rpsRow)
    }
  }

  const handleAutoGenerateCharacterForm = async (characterIndex: number, formIndex: number) => {
    if (!ctx.canAutoGenerateCharacterFormImage(characterIndex, formIndex)) {
      message.info('手动新增的形态请使用「图片导入」上传配图')
      return
    }
    const form = ctx.characterForms.get()[characterIndex]?.[formIndex]
    if (!form) return
    const formKey = `${characterIndex}-${formIndex}`
    const formName = form.name
    const routeCtx = ctx.captureStep3RouteContext()
    // 先标 generating，立刻禁用「编辑形态图」（对齐场景卡；避免提交前窗口内仍可点）
    ctx.characterFormGenerationStatus.set({
      ...ctx.characterFormGenerationStatus.get(),
      [formKey]: 'generating'
    })
    ctx.store().setCharacterFormGenerationStatus(formKey, 'generating')
    let aid = ctx.characterAssetIds.get()[characterIndex]
    if (aid == null) {
      await ctx.loadPersonalAssetsForTab('character')
      if (!ctx.matchesStep3RouteContext(routeCtx)) return
      aid = ctx.characterAssetIds.get()[characterIndex]
    }
    if (aid == null) {
      ctx.patchCharacterFormGenStatus(formKey, 'idle', routeCtx)
      message.warning('缺少角色资产ID，请刷新页面后重试')
      return
    }
    const formId = await ctx.resolveFormIdForAssetForm('character', characterIndex, formIndex)
    if (!ctx.matchesStep3RouteContext(routeCtx)) return
    if (formId == null) {
      ctx.patchCharacterFormGenStatus(formKey, 'idle', routeCtx)
      message.warning('未找到该形态的 ID，请稍后重试或先点击「新增形态」保存后再生成')
      return
    }
    await ctx.runSingleFormGenerate({
      assetId: Number(aid),
      tab: 'character',
      formKey,
      formName,
      formId
    })
  }

  // 角色图片操作函数
  const startEditCharacterImageTitle = (
    characterIndex: number,
    imageIndex: number,
    currentTitle: string
  ) => {
    ctx.editingImageTitleIndex.set(`character-${characterIndex}-${imageIndex}`)
    ctx.editingImageTitle.set(currentTitle || `角色图${imageIndex + 1}`)
  }

  const handleCharacterImageTitleBlur = async (characterIndex: number, imageIndex: number) => {
    if (
      ctx.editingImageTitleIndex.get() === `character-${characterIndex}-${imageIndex}` &&
      ctx.editingImageTitle.get().trim()
    ) {
      const images = ctx.characterImages.get()[characterIndex]
      if (images && images[imageIndex]) {
        const nextTitle = ctx.editingImageTitle.get().trim()
        const ok = await syncImageTitleToRps(images[imageIndex], nextTitle)
        if (!ok) {
          ctx.editingImageTitleIndex.set(null)
          ctx.editingImageTitle.set('')
          return
        }
        images[imageIndex].title = nextTitle
        ctx.characterImages.set({ ...ctx.characterImages.get() })
        message.success('角色图名称已更新')
      }
    }
    ctx.editingImageTitleIndex.set(null)
    ctx.editingImageTitle.set('')
  }

  const handlePreviewCharacterImageByIndex = (characterIndex: number, imageIndex: number) => {
    const images = ctx.characterImages.get()[characterIndex]
    const item = images?.[imageIndex]
    if (item?.url) {
      openImagePreviewModal({
        url: item.url,
        title: item.title || `角色图${imageIndex + 1}`
      })
    }
  }

  const handleReplaceCharacterImageByIndex = (characterIndex: number, _imageIndex: number) => {
    ctx.currentImportCharacterIndex.set(characterIndex)
    ctx.showImportCharacterImageModal.set(true)
    // TODO: 替换指定索引的图片
  }

  const handleDownloadCharacterImageByIndex = (characterIndex: number, imageIndex: number) => {
    const images = ctx.characterImages.get()[characterIndex]
    const img = images && images[imageIndex]
    if (img && img.url) {
      void downloadMediaFileWithToast(
        img.url,
        img.title || `角色图${characterIndex + 1}-${imageIndex + 1}.png`,
        '图片'
      )
    } else {
      message.warning('暂无图片可下载')
    }
  }

  const handleDeleteCharacterImageByIndex = async (characterIndex: number, imageIndex: number) => {
    const images = ctx.characterImages.get()[characterIndex]
    if (!images || images.length <= imageIndex) return
    const img = images[imageIndex]
    const imageId =
      img?.rpsImageId != null && Number.isFinite(Number(img.rpsImageId))
        ? Number(img.rpsImageId)
        : null
    const formId =
      img?.rpsFormId != null && Number.isFinite(Number(img.rpsFormId))
        ? Number(img.rpsFormId)
        : ((ctx.characterFormIdsByIndex.get()[characterIndex] ?? [])[imageIndex] ?? null)
    if (!(await ctx.tryUnuseFormImage({ imageId, formId }))) return
    images.splice(imageIndex, 1)
    ctx.characterImages.set({ ...ctx.characterImages.get() })
    message.success('已取消主图展示')
    ctx.syncStep3AssetsToCreationStore()
  }

  // 角色形态图片操作函数
  const startEditCharacterFormImageTitle = (
    characterIndex: number,
    formIndex: number,
    imageIndex: number,
    currentTitle: string
  ) => {
    ctx.editingImageTitleIndex.set(`character-form-${characterIndex}-${formIndex}-${imageIndex}`)
    ctx.editingImageTitle.set(currentTitle || `形态图${imageIndex + 1}`)
  }

  const handleCharacterFormImageTitleBlur = async (
    characterIndex: number,
    formIndex: number,
    imageIndex: number
  ) => {
    const formKey = `${characterIndex}-${formIndex}`
    if (
      ctx.editingImageTitleIndex.get() ===
        `character-form-${characterIndex}-${formIndex}-${imageIndex}` &&
      ctx.editingImageTitle.get().trim()
    ) {
      const images = ctx.characterFormImages.get()[formKey]
      if (images && images[imageIndex]) {
        const nextTitle = ctx.editingImageTitle.get().trim()
        const ok = await syncImageTitleToRps(images[imageIndex], nextTitle)
        if (!ok) {
          ctx.editingImageTitleIndex.set(null)
          ctx.editingImageTitle.set('')
          return
        }
        images[imageIndex].title = nextTitle
        ctx.characterFormImages.set({ ...ctx.characterFormImages.get() })
        message.success('形态图名称已更新')
      }
    }
    ctx.editingImageTitleIndex.set(null)
    ctx.editingImageTitle.set('')
  }

  const handlePreviewCharacterFormImageByIndex = (
    characterIndex: number,
    formIndex: number,
    imageIndex: number
  ) => {
    const formKey = `${characterIndex}-${formIndex}`
    const images = ctx.characterFormImages.get()[formKey]
    const item = images?.[imageIndex]
    if (item?.url) {
      openImagePreviewModal({
        url: item.url,
        title: item.title || `形态图${imageIndex + 1}`
      })
    }
  }

  const handleReplaceCharacterFormImageByIndex = (
    characterIndex: number,
    formIndex: number,
    _imageIndex: number
  ) => {
    ctx.currentImportCharacterFormKey.set(`${characterIndex}-${formIndex}`)
    ctx.showImportCharacterFormImageModal.set(true)
    // TODO: 替换指定索引的图片
  }

  const handleCharacterFormImageMiddleActionByIndex = (
    characterIndex: number,
    formIndex: number,
    imageIndex: number
  ) => {
    const img = ctx.characterFormImages.get()[`${characterIndex}-${formIndex}`]?.[imageIndex]
    if (
      shouldShowAssetImageRegenerateAction(
        img,
        ctx.canAutoGenerateCharacterFormImage(characterIndex, formIndex)
      )
    ) {
      handleAutoGenerateCharacterForm(characterIndex, formIndex)
      return
    }
    handleReplaceCharacterFormImageByIndex(characterIndex, formIndex, imageIndex)
  }

  const handleDownloadCharacterFormImageByIndex = (
    characterIndex: number,
    formIndex: number,
    imageIndex: number
  ) => {
    const formKey = `${characterIndex}-${formIndex}`
    const images = ctx.characterFormImages.get()[formKey]
    const img = images && images[imageIndex]
    if (img && img.url) {
      void downloadMediaFileWithToast(
        img.url,
        img.title || `形态图${characterIndex + 1}-${formIndex + 1}-${imageIndex + 1}.png`,
        '图片'
      )
    } else {
      message.warning('暂无图片可下载')
    }
  }

  const handleDeleteCharacterFormImageByIndex = async (
    characterIndex: number,
    formIndex: number,
    imageIndex: number
  ) => {
    const formKey = `${characterIndex}-${formIndex}`
    const images = ctx.characterFormImages.get()[formKey]
    if (!images || images.length <= imageIndex) return
    const img = images[imageIndex]
    const imageId =
      img?.rpsImageId != null && Number.isFinite(Number(img.rpsImageId))
        ? Number(img.rpsImageId)
        : null
    const formId =
      img?.rpsFormId != null && Number.isFinite(Number(img.rpsFormId))
        ? Number(img.rpsFormId)
        : ctx.ensureFormIdForRpsUpdate('character', characterIndex, formIndex)
    if (!(await ctx.tryUnuseFormImage({ imageId, formId }))) return
    images.splice(imageIndex, 1)
    ctx.characterFormImages.set({ ...ctx.characterFormImages.get() })
    // 同步清理资产级缓存，避免批量弹窗读到 characterImages 旧图
    const assetImgs = [...(ctx.characterImages.get()[characterIndex] ?? [])]
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
    ctx.characterImages.set({ ...ctx.characterImages.get(), [characterIndex]: nextAsset })
    message.success('已取消主图展示')
    ctx.syncStep3AssetsToCreationStore()
  }

  return {
    handleImportCharacterImage,
    handleCharacterImageImport,
    handleEditCharacterImage,
    handleEditCharacterImageWithIndex,
    handleCharacterImageUpdate,
    handleAutoGenerateCharacter,
    handleImportCharacterFormImage,
    handleCharacterFormImageImport,
    handleEditCharacterFormImage,
    handleEditCharacterFormImageWithIndex,
    handleCharacterFormImageUpdate,
    handleAutoGenerateCharacterForm,
    startEditCharacterImageTitle,
    handleCharacterImageTitleBlur,
    handlePreviewCharacterImageByIndex,
    handleReplaceCharacterImageByIndex,
    handleDownloadCharacterImageByIndex,
    handleDeleteCharacterImageByIndex,
    startEditCharacterFormImageTitle,
    handleCharacterFormImageTitleBlur,
    handlePreviewCharacterFormImageByIndex,
    handleReplaceCharacterFormImageByIndex,
    handleCharacterFormImageMiddleActionByIndex,
    handleDownloadCharacterFormImageByIndex,
    handleDeleteCharacterFormImageByIndex
  }
}
