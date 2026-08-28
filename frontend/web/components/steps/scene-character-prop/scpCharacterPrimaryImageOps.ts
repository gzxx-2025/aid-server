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

export function createScpCharacterPrimaryImageOps(ctx: ScpCtx) {
  const handleImportCharacterImage = (index: number) => {
    ctx.currentImportCharacterIndex.set(index)
    ctx.showImportCharacterImageModal.set(true)
  }

  const handleCharacterImageImport = async (fileOrAsset: File | string | any) => {
    const now = new Date()
    let imageUrl: string
    let imageTitle: string
    let source: string
    const targetIndex = ctx.currentImportCharacterIndex.get()

    // 添加图片到角色
    if (!ctx.characterImages.get()[targetIndex]) {
      ctx.characterImages.set({ ...ctx.characterImages.get(), [targetIndex]: [] })
    }

    // 处理不同类型的导入
    if (fileOrAsset instanceof File) {
      const uploaded = await uploadImageToOssWithToast(fileOrAsset)
      if (!uploaded) return
      imageUrl = uploaded
      imageTitle =
        fileOrAsset.name.replace(/\.[^/.]+$/, '') ||
        `角色图${ctx.characterImages.get()[targetIndex].length + 1}`
      source = '本地上传'
    } else if (typeof fileOrAsset === 'string') {
      // 字符串URL
      imageUrl = fileOrAsset
      imageTitle = `角色图${ctx.characterImages.get()[targetIndex].length + 1}`
      source = '资源库导入'
    } else if (fileOrAsset && typeof fileOrAsset === 'object') {
      // 资产对象
      imageUrl =
        fileOrAsset.url ||
        fileOrAsset.thumbnail ||
        'https://picsum.photos/800/450?random=' + Date.now()
      imageTitle =
        fileOrAsset.name?.replace(/\.[^/.]+$/, '') ||
        `角色图${ctx.characterImages.get()[targetIndex].length + 1}`
      source = '资源库导入'
    } else {
      message.error('导入失败：无效的图片数据')
      return
    }

    let rpsFormId: number | undefined
    let rpsImageId: number | undefined
    const characterAssetId = ctx.characterAssetIds.get()[targetIndex]
    if (characterAssetId != null && Number.isFinite(Number(characterAssetId))) {
      const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      if (saveCtx) {
        try {
          const currentImageCount = ctx.characterImages.get()[targetIndex]?.length ?? 0
          const existingFormId = ctx.ensureFormIdForRpsUpdate('character', targetIndex, currentImageCount)
          if (existingFormId != null) {
            rpsFormId = existingFormId
          } else {
            const row = await userAssetRpsFormCreate({
              projectId: saveCtx.projectId,
              episodeId: saveCtx.episodeId,
              assetId: Number(characterAssetId),
              imageUrl,
              name: imageTitle,
              sourceType: mapImportSourceType(source)
            })
            ctx.applyRpsRowFormIds('character', targetIndex, row)
            const ids = ctx.characterFormIdsByIndex.get()[targetIndex] ?? []
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

    // 添加图片到角色
    const nextList = [...(ctx.characterImages.get()[targetIndex] ?? [])]
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
    ctx.characterImages.set({ ...ctx.characterImages.get(), [targetIndex]: nextList })

    const characterName = ctx.localValue.get().characters[targetIndex]
    message.success(`已为「${characterName}」导入角色图片`)
    ctx.showImportCharacterImageModal.set(false)
  }

  const handleEditCharacterImage = (index: number) => {
    void preloadEditSceneImageModal()
    ctx.currentEditCharacterIndex.set(index)
    ctx.currentEditCharacterImageIndex.set(null)
    ctx.showEditCharacterImageModal.set(true)
  }

  const handleEditCharacterImageWithIndex = (characterIndex: number, imageIndex: number) => {
    void preloadEditSceneImageModal()
    ctx.currentEditCharacterIndex.set(characterIndex)
    ctx.currentEditCharacterImageIndex.set(imageIndex)
    ctx.showEditCharacterImageModal.set(true)
  }

  const handleCharacterImageUpdate = async (characterIndex: number, characterData: any) => {
    const characterName = ctx.localValue.get().characters[characterIndex]
    if (characterData && characterData.setting !== undefined && characterName) {
      try {
        const updatedSetting = await saveRpsSettingPrompt(
          'character',
          ctx.characterSettings.get()[characterName],
          String(characterData.setting ?? '')
        )
        ctx.characterSettings.set({
          ...ctx.characterSettings.get(),
          [characterName]: updatedSetting
        })
      } catch (e: unknown) {
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || '角色提示词同步失败')
        return
      }
    }
    if (characterData && characterData.images) {
      ctx.characterImages.set({
        ...ctx.characterImages.get(),
        [characterIndex]: Array.isArray(characterData.images)
          ? characterData.images.map((img: any) => ({ ...img }))
          : []
      })
    }
    if (characterData?.rpsRow) {
      ctx.applyRpsRowFormIds('character', characterIndex, characterData.rpsRow)
    }
  }

  return {
    handleCharacterImageImport,
    handleCharacterImageUpdate,
    handleEditCharacterImage,
    handleEditCharacterImageWithIndex,
    handleImportCharacterImage,
  }
}
