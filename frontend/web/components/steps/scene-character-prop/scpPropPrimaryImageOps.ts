'use client'

import { message } from 'antd'
import {
userAssetRpsFormCreate,
userAssetRpsFormImageCreate
} from '~/utils/businessApi'
import { uploadImageToOssWithToast } from '~/utils/ossUpload'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { preloadEditSceneImageModal } from './editSceneImageModalLoader'
import { mapImportSourceType,resolveCreatedFormImageId } from './scpRowUtils'
import type { ScpCtx } from './types'
import { saveRpsSettingPrompt } from './scpSettingPromptUtils'

export function createScpPropPrimaryImageOps(ctx: ScpCtx) {
  const handleImportPropImage = (index: number) => {
    ctx.currentImportPropIndex.set(index)
    ctx.showImportPropImageModal.set(true)
  }

  const handlePropImageImport = async (fileOrAsset: File | string | any) => {
    const now = new Date()
    let imageUrl: string
    let imageTitle: string
    let source: string
    const targetIndex = ctx.currentImportPropIndex.get()

    // 添加图片到道具
    if (!ctx.propImages.get()[targetIndex]) {
      ctx.propImages.set({ ...ctx.propImages.get(), [targetIndex]: [] })
    }

    // 处理不同类型的导入
    if (fileOrAsset instanceof File) {
      const uploaded = await uploadImageToOssWithToast(fileOrAsset)
      if (!uploaded) return
      imageUrl = uploaded
      imageTitle =
        fileOrAsset.name.replace(/\.[^/.]+$/, '') ||
        `道具图${ctx.propImages.get()[targetIndex].length + 1}`
      source = '本地上传'
    } else if (typeof fileOrAsset === 'string') {
      // 字符串URL
      imageUrl = fileOrAsset
      imageTitle = `道具图${ctx.propImages.get()[targetIndex].length + 1}`
      source = '资源库导入'
    } else if (fileOrAsset && typeof fileOrAsset === 'object') {
      // 资产对象
      imageUrl =
        fileOrAsset.url ||
        fileOrAsset.thumbnail ||
        'https://picsum.photos/800/450?random=' + Date.now()
      imageTitle =
        fileOrAsset.name?.replace(/\.[^/.]+$/, '') ||
        `道具图${ctx.propImages.get()[targetIndex].length + 1}`
      source = '资源库导入'
    } else {
      message.error('导入失败：无效的图片数据')
      return
    }

    let rpsFormId: number | undefined
    let rpsImageId: number | undefined
    const propAssetId = ctx.propAssetIds.get()[targetIndex]
    if (propAssetId != null && Number.isFinite(Number(propAssetId))) {
      const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      if (saveCtx) {
        try {
          const currentImageCount = ctx.propImages.get()[targetIndex]?.length ?? 0
          const existingFormId = ctx.ensureFormIdForRpsUpdate('prop', targetIndex, currentImageCount)
          if (existingFormId != null) {
            rpsFormId = existingFormId
          } else {
            const row = await userAssetRpsFormCreate({
              projectId: saveCtx.projectId,
              episodeId: saveCtx.episodeId,
              assetId: Number(propAssetId),
              imageUrl,
              name: imageTitle,
              sourceType: mapImportSourceType(source)
            })
            ctx.applyRpsRowFormIds('prop', targetIndex, row)
            const ids = ctx.propFormIdsByIndex.get()[targetIndex] ?? []
            const last = ids[ids.length - 1]
            if (last != null && Number.isFinite(Number(last))) rpsFormId = Number(last)
          }
          if (rpsFormId != null) {
            const created = await userAssetRpsFormImageCreate({
              formId: rpsFormId,
              imageUrl,
              name: imageTitle,
              sourceType: mapImportSourceType(source)
            })
            rpsImageId = resolveCreatedFormImageId(created) ?? undefined
            if (rpsImageId != null) {
              await ctx.tryUseFormImage({ imageId: rpsImageId })
            }
          }
        } catch {
          // 不阻断本地回显
        }
      }
    }

    // 添加图片到道具
    const nextList = [...(ctx.propImages.get()[targetIndex] ?? [])]
    nextList.push({
      id: Date.now().toString(),
      ...(rpsFormId != null ? { rpsFormId } : {}),
      ...(rpsImageId != null ? { rpsImageId } : {}),
      url: imageUrl,
      thumbnail: imageUrl,
      title: imageTitle,
      createdAt: now.toISOString(),
      source: source,
      importDate: now.toISOString(),
      angles: []
    })

    // 强制触发响应式更新
    ctx.propImages.set({ ...ctx.propImages.get(), [targetIndex]: nextList })

    const propName = ctx.localValue.get().props[targetIndex]
    message.success(`已为「${propName}」导入道具图片`)
    ctx.showImportPropImageModal.set(false)
  }

  const handleEditPropImage = (index: number) => {
    void preloadEditSceneImageModal()
    ctx.currentEditPropIndex.set(index)
    ctx.currentEditPropImageIndex.set(null)
    ctx.showEditPropImageModal.set(true)
  }

  const handleEditPropImageWithIndex = (propIndex: number, imageIndex: number) => {
    void preloadEditSceneImageModal()
    ctx.currentEditPropIndex.set(propIndex)
    ctx.currentEditPropImageIndex.set(imageIndex)
    ctx.showEditPropImageModal.set(true)
  }

  const handlePropImageUpdate = async (propIndex: number, propData: any) => {
    const propName = ctx.localValue.get().props[propIndex]
    if (propData && propData.setting !== undefined && propName) {
      try {
        const updatedSetting = await saveRpsSettingPrompt(
          'prop',
          ctx.propSettings.get()[propName],
          String(propData.setting ?? '')
        )
        ctx.propSettings.set({
          ...ctx.propSettings.get(),
          [propName]: updatedSetting
        })
      } catch (e: unknown) {
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || '道具提示词同步失败')
        return
      }
    }
    if (propData && propData.images) {
      ctx.propImages.set({
        ...ctx.propImages.get(),
        [propIndex]: Array.isArray(propData.images)
          ? propData.images.map((img: any) => ({ ...img }))
          : []
      })
    }
    if (propData?.rpsRow) {
      ctx.applyRpsRowFormIds('prop', propIndex, propData.rpsRow)
    }
  }

  return {
    handleEditPropImage,
    handleEditPropImageWithIndex,
    handleImportPropImage,
    handlePropImageImport,
    handlePropImageUpdate,
  }
}
