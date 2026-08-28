'use client'

import { message } from 'antd'
import {
buildModalTaskOverlayKey,
matchesModalTaskOverlayKey
} from '~/composables/useModalTaskScope'
import {
userStoryboardUpload
} from '~/utils/businessApi'
import { uploadImageToOssWithToast } from '~/utils/ossUpload'
import { clearProjectStoryboardRecordCache } from '~/utils/storyboardRecordBatch'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import type { EditStoryboardImageModalCtx } from './types'
import { nextTick } from './useMirrored'

const TAB_SWITCH_SKELETON_MS = 380

export function createStoryboardModalImportOps(ctx: EditStoryboardImageModalCtx) {
  const isUploadingLocalImage = () =>
    matchesModalTaskOverlayKey(
      ctx.uploadingLocalImageAtKey.get(),
      ctx.overlayKeyParts(ctx.currentSceneIndex.get(), ctx.currentImageIndex.get(), 'local-upload')
    )

  const switchScene = async (index: number) => {
    if (index === ctx.currentSceneIndex.get()) return

    const keepSid = ctx.sceneStoryboardIdNum(index)
    ctx.suspendOtherStoryboardImageModalFollows(keepSid)

    ctx.showStoryboardScriptModal.set(false)
    ctx.leftPanelLoading.set(true)
    ctx.rightPanelLoading.set(true)
    ctx.currentSceneIndex.set(index)
    ctx.currentImageIndex.set(0)
    ctx.syncLocalSceneImagesFromSceneIndex(index)

    await nextTick()
    ctx.scrollActiveSceneTabIntoView()
    ctx.clearStaleModalGeneratingPlaceholders()
    void ctx.syncSceneDetailAndRestore(index)
    setTimeout(() => {
      ctx.leftPanelLoading.set(false)
      ctx.rightPanelLoading.set(false)
    }, TAB_SWITCH_SKELETON_MS)
  }

  const switchImage = (imageIndex: number) => {
    if (imageIndex === ctx.currentImageIndex.get()) return
    ctx.currentImageIndex.set(imageIndex)
  }

  const handleUploadLocalImage = () => {
    if (isUploadingLocalImage()) return
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = 'image/*'
    input.onchange = async (e: Event) => {
      const file = (e.target as HTMLInputElement).files?.[0]
      if (!file) return

      const storyboardId = ctx.currentStoryboardId()
      if (!storyboardId) {
        message.warning('分镜信息异常，请刷新后重试')
        return
      }

      const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      if (!saveCtx) {
        message.warning('缺少项目信息，请从「我的作品」打开作品后再操作')
        return
      }

      const sceneIdx = ctx.currentSceneIndex.get()
      const imgIdx = ctx.currentImageIndex.get()
      ctx.uploadingLocalImageAtKey.set(
        buildModalTaskOverlayKey(ctx.overlayKeyParts(sceneIdx, imgIdx, 'local-upload'))
      )
      const hideLoading = message.loading('正在上传图片...', 0)
      try {
        const url = await uploadImageToOssWithToast(file)
        if (!url) return

        const record = await userStoryboardUpload({
          projectId: saveCtx.projectId,
          episodeId: saveCtx.episodeId,
          storyboardId,
          imageUrl: url,
          mediaType: 'image'
        })

        const recordId = Number(record?.id)
        if (!Number.isFinite(recordId) || recordId <= 0) {
          throw new Error('上传落库失败：未返回记录ID')
        }

        ctx.pendingImage.current = null
        const uploadCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
        if (uploadCtx) clearProjectStoryboardRecordCache(uploadCtx)
        await ctx.refreshSceneRecords(ctx.currentSceneIndex.get(), recordId, undefined, {
          force: true
        })
        message.success('图片已上传，请点击「添加分镜图」设为主图')
      } catch (err: unknown) {
        const ax = err as { msg?: string; message?: string }
        message.error(ax?.msg || ax?.message || '分镜图上传失败，请重试')
      } finally {
        hideLoading()
        if (
          ctx.uploadingLocalImageAtKey.get() ===
          buildModalTaskOverlayKey(
            ctx.overlayKeyParts(
              ctx.currentSceneIndex.get(),
              ctx.currentImageIndex.get(),
              'local-upload'
            )
          )
        ) {
          ctx.uploadingLocalImageAtKey.set('')
        }
      }
    }
    input.click()
  }

  const handleOpenAssetLibrary = () => {
    ctx.showAssetLibraryModal.set(true)
  }

  const handleAssetLibraryImport = (asset: any) => {
    const imageUrl = String(asset?.url || asset?.thumbnail || '').trim()
    if (!imageUrl) {
      message.error('图片地址无效')
      return
    }
    if (ctx.assetLibraryImportInFlight.current) return

    ctx.showAssetLibraryModal.set(false)
    ctx.assetLibraryImportInFlight.current = true

    void (async () => {
      const sceneIdx = ctx.currentSceneIndex.get()
      const imgIdx = ctx.currentImageIndex.get()
      ctx.uploadingLocalImageAtKey.set(
        buildModalTaskOverlayKey(ctx.overlayKeyParts(sceneIdx, imgIdx, 'asset-import'))
      )
      const hideLoading = message.loading('正在导入图片...', 0)
      try {
        const storyboardId = ctx.currentStoryboardId()
        if (!storyboardId) {
          message.warning('分镜信息异常，请刷新后重试')
          return
        }

        const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
        if (!saveCtx) {
          message.warning('缺少项目信息，请从「我的作品」打开作品后再操作')
          return
        }

        // 当前分镜已有相同 URL 的生成记录时不再 upload，避免同图重复落库
        const existingRows = await ctx.fetchImageRecordsForStoryboard(storyboardId, {
          force: true
        })
        const existing = existingRows.find(
          (r) => String(r?.fileUrl ?? '').trim() === imageUrl && Number(r?.id) > 0
        )
        if (existing) {
          ctx.pendingImage.current = null
          await ctx.refreshSceneRecords(sceneIdx, Number(existing.id), undefined, { force: true })
          message.success('该图片已在生成记录中')
          return
        }

        // 与本地上传、编辑分镜视频「资产库导入」对齐：先 upload 落库，再刷新记录列表
        const record = await userStoryboardUpload({
          projectId: saveCtx.projectId,
          episodeId: saveCtx.episodeId,
          storyboardId,
          imageUrl,
          mediaType: 'image'
        })

        const recordId = Number(record?.id)
        if (!Number.isFinite(recordId) || recordId <= 0) {
          throw new Error('导入落库失败：未返回记录ID')
        }

        ctx.pendingImage.current = null
        clearProjectStoryboardRecordCache(saveCtx)
        await ctx.refreshSceneRecords(sceneIdx, recordId, undefined, { force: true })
        message.success('图片已导入，请点击「添加分镜图」设为主图')
      } catch (err: unknown) {
        const ax = err as { msg?: string; message?: string }
        message.error(ax?.msg || ax?.message || '资产库导入失败，请重试')
      } finally {
        hideLoading()
        ctx.assetLibraryImportInFlight.current = false
        if (
          ctx.uploadingLocalImageAtKey.get() ===
          buildModalTaskOverlayKey(
            ctx.overlayKeyParts(
              ctx.currentSceneIndex.get(),
              ctx.currentImageIndex.get(),
              'asset-import'
            )
          )
        ) {
          ctx.uploadingLocalImageAtKey.set('')
        }
      }
    })()
  }

  return {
    handleAssetLibraryImport,
    handleOpenAssetLibrary,
    handleUploadLocalImage,
    isUploadingLocalImage,
    switchImage,
    switchScene,
  }
}
