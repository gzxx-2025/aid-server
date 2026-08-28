'use client'

import { message,Modal } from 'antd'
import {
userStoryboardRecordDelete,
userStoryboardSetFinalImage,
userStoryboardUnSetFinalImage,
userStoryboardUpload
} from '~/utils/businessApi'
import { resolveStoryboardImageModalCloseDismiss } from '~/utils/storyboardImageModalAutoReopen'
import {
clearModalImageGenSession,
markModalImageGenUserDismissed,
readModalImageGenSession
} from '~/utils/storyboardImageModalGenSession'
import { releaseStoryboardImageModalLiveOwned } from '~/utils/storyboardImageModalOwnedFollow'
import { clearProjectStoryboardRecordCache } from '~/utils/storyboardRecordBatch'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { downloadMediaFileWithToast } from '~/utils/mediaFileDownload'
import { createStoryboardModalImportOps } from './storyboardModalImportOps'
import type { EditStoryboardImageModalCtx } from './types'
import { STORYBOARD_GENERATED_IMAGE_DEFAULT_TITLE } from './types'
import { nextTick } from './useMirrored'

const TAB_SWITCH_SKELETON_MS = 380

export interface StoryboardModalImageActionsApi {
  isUploadingLocalImage: () => boolean
  switchScene: (index: number) => Promise<void>
  switchImage: (imageIndex: number) => void
  handleUploadLocalImage: () => void
  handleOpenAssetLibrary: () => void
  handleAssetLibraryImport: (asset: any) => void
  startEditImageTitle: (index: number) => void
  handleImageTitleBlur: (index: number) => void
  canDeleteHistoryImage: (img: any) => boolean
  removeLocalPendingImage: (index: number) => void
  handleDownloadImage: (imageIndex: number) => void
  handleDeleteImage: (imageIndex: number) => void
  persistManualStoryboardImageUrl: (imageUrl: string) => Promise<number | null>
  resolveOrPersistStoryboardImageRecordId: (img: any) => Promise<number | null>
  handleConfirmAddImage: (index: number) => Promise<void>
  handleAddStoryboardImage: () => Promise<void>
  handleCancelAddImage: (index: number) => Promise<void>
  handleSetMainFromHistory: (imageIndex: number) => Promise<void>
  handleCancel: () => void
}

export function useStoryboardModalImageActions(
  ctx: EditStoryboardImageModalCtx
): StoryboardModalImageActionsApi {
  const { handleAssetLibraryImport, handleOpenAssetLibrary, handleUploadLocalImage, isUploadingLocalImage, switchImage, switchScene } = createStoryboardModalImportOps(ctx)
  const startEditImageTitle = (index: number) => {
    ctx.editingImageTitleIndex.set(index)
    ctx.editingImageTitle.set(
      ctx.currentSceneImages()[index]?.title || STORYBOARD_GENERATED_IMAGE_DEFAULT_TITLE
    )
  }

  const handleImageTitleBlur = (index: number) => {
    if (ctx.editingImageTitleIndex.get() === index) {
      const nextTitle =
        ctx.editingImageTitle.get().trim() || STORYBOARD_GENERATED_IMAGE_DEFAULT_TITLE
      const updatedScenes = [...ctx.props().scenes]
      if (updatedScenes[ctx.currentSceneIndex.get()].images?.[index]) {
        updatedScenes[ctx.currentSceneIndex.get()].images![index].title = nextTitle
        ctx.emitUpdate(ctx.currentSceneIndex.get(), {
          images: updatedScenes[ctx.currentSceneIndex.get()].images
        })
        message.success('标题已更新')
      }
    }
    ctx.editingImageTitleIndex.set(null)
    ctx.editingImageTitle.set('')
  }

  function canDeleteHistoryImage(img: any): boolean {
    if (!img || img._generating || ctx.isDeletingRecord.get()) return false
    if (ctx.resolveStoryboardRecordId(img)) return true
    return !!img._pending
  }

  function removeLocalPendingImage(index: number) {
    const img = ctx.localSceneImages.get()[index] as Record<string, unknown> | undefined
    if (!img) return

    const imgId = String(img.id || '')
    const nextAddedIds = new Set(ctx.addedImageIds.get())
    nextAddedIds.delete(imgId)
    ctx.addedImageIds.set(nextAddedIds)

    const ci = ctx.currentSceneIndex.get()
    const parentImages = ctx.props().scenes[ci]?.images || []
    const inParent = parentImages.some((x: any) => x.id === img.id)

    if (inParent) {
      const nextImages = parentImages.filter((x: any) => x.id !== img.id)
      ctx.emitUpdate(ci, { ...ctx.props().scenes[ci], images: nextImages })
    }

    ctx.localSceneImages.set(ctx.localSceneImages.get().filter((_, i) => i !== index))
    if (ctx.pendingImage.current?.id === img.id) ctx.pendingImage.current = null

    void nextTick(() => {
      const n = ctx.localSceneImages.get().length
      if (n === 0) {
        ctx.currentImageIndex.set(0)
      } else if (ctx.currentImageIndex.get() >= n) {
        ctx.currentImageIndex.set(n - 1)
      }
    })
  }

  const handleDownloadImage = (imageIndex: number) => {
    const img = ctx.currentSceneImages()[imageIndex]
    if (img && img.url) {
      void downloadMediaFileWithToast(img.url, img.title || `场景图${imageIndex + 1}.png`, '图片')
    } else {
      message.warning('暂无图片可下载')
    }
  }

  const handleDeleteImage = (imageIndex: number) => {
    const img = ctx.currentSceneImages()[imageIndex]
    if (!canDeleteHistoryImage(img)) {
      message.warning('当前记录无法删除')
      return
    }

    Modal.confirm({
      title: '确认删除',
      content: '确定要删除这条生成记录吗？删除后不可恢复。',
      okText: '确定',
      cancelText: '取消',
      onOk: async () => {
        const recordId = ctx.resolveStoryboardRecordId(img)
        const storyboardId = ctx.currentStoryboardId()

        if (recordId && storyboardId) {
          ctx.isDeletingRecord.set(true)
          try {
            const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
            await userStoryboardRecordDelete({ storyboardId, recordId })
            if (saveCtx) clearProjectStoryboardRecordCache(saveCtx)
            await ctx.refreshSceneRecords(ctx.currentSceneIndex.get(), undefined, undefined, {
              force: true
            })
            message.success('删除成功')
          } catch (e: unknown) {
            const err = e as { msg?: string; message?: string }
            message.error(err?.msg || err?.message || '删除失败')
            throw e
          } finally {
            ctx.isDeletingRecord.set(false)
          }
          return
        }

        removeLocalPendingImage(imageIndex)
        message.success('已删除')
      }
    })
  }

  /** 无生成记录时，按 URL 落库为分镜图片记录（资产库导入 / 历史 pending 兜底） */
  async function persistManualStoryboardImageUrl(imageUrl: string): Promise<number | null> {
    const url = String(imageUrl || '').trim()
    if (!url) return null

    const storyboardId = ctx.currentStoryboardId()
    if (!storyboardId) return null

    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    if (!saveCtx) return null

    const record = await userStoryboardUpload({
      projectId: saveCtx.projectId,
      episodeId: saveCtx.episodeId,
      storyboardId,
      imageUrl: url,
      mediaType: 'image'
    })
    const recordId = Number(record?.id)
    return Number.isFinite(recordId) && recordId > 0 ? recordId : null
  }

  async function resolveOrPersistStoryboardImageRecordId(img: any): Promise<number | null> {
    const existing = ctx.resolveStoryboardRecordId(img)
    if (existing) return existing
    return persistManualStoryboardImageUrl(String(img?.url || ''))
  }

  const handleConfirmAddImage = async (index: number) => {
    if (ctx.isSettingFinalImage.get()) return

    const storyboardId = ctx.currentStoryboardId()
    if (!storyboardId) {
      message.warning('分镜信息异常，请刷新后重试')
      return
    }

    const img = ctx.localSceneImages.get()[index] as any
    if (!img) {
      message.warning('没有可设置的图片')
      return
    }

    if (!String(img.url || '').trim()) {
      message.warning('产物未完成')
      return
    }

    ctx.isSettingFinalImage.set(true)
    try {
      const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      if (!saveCtx) {
        message.warning('缺少项目信息，无法设置主图')
        return
      }

      // 与编辑分镜视频一致：无 recordId 时先 upload 落库再 setFinal
      const recordId = await resolveOrPersistStoryboardImageRecordId(img)
      if (!recordId) {
        message.warning('图片落库失败，无法设为分镜图')
        return
      }

      await userStoryboardSetFinalImage({
        projectId: saveCtx.projectId,
        episodeId: saveCtx.episodeId,
        storyboardId,
        recordId
      })
      clearProjectStoryboardRecordCache(saveCtx)
      await ctx.refreshHeaderTabs(true)

      if (img._pending) {
        delete img._pending
        ctx.pendingImage.current = null
      }

      await ctx.refreshSceneRecords(ctx.currentSceneIndex.get(), recordId, undefined, {
        force: true
      })
      const imgId = String(recordId)
      ctx.addedImageIds.set(new Set([...ctx.addedImageIds.get(), imgId]))
      message.success('设置成功')
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '设置分镜主图失败')
    } finally {
      ctx.isSettingFinalImage.set(false)
    }
  }

  /** 将当前图设为分镜主图（调用 setFinalImage）；无记录时先 upload 落库 */
  const handleAddStoryboardImage = async () => {
    const idx = ctx.currentImageIndex.get()
    const img = ctx.localSceneImages.get()[idx] as any
    if (!img) {
      message.warning('请先选择一张图片')
      return
    }
    if (img?._pending) {
      await handleConfirmAddImage(idx)
      return
    }
    if (ctx.pendingImage.current?.id) {
      const pendingIndex = ctx.localSceneImages
        .get()
        .findIndex((x: any) => x?.id === ctx.pendingImage.current?.id && x?._pending)
      if (pendingIndex >= 0) {
        ctx.currentImageIndex.set(pendingIndex)
        await handleConfirmAddImage(pendingIndex)
        return
      }
    }
    if (ctx.resolveStoryboardRecordId(img) || String(img.url || '').trim()) {
      await handleConfirmAddImage(idx)
      return
    }
    message.warning('请先通过「选择本地文件」「资产库导入」导入图片，或选择已生成的分镜图记录')
  }

  const handleCancelAddImage = async (index: number) => {
    if (ctx.isSettingFinalImage.get()) return

    const img = ctx.localSceneImages.get()[index] as Record<string, unknown> | undefined
    if (!img) return

    const imgId = String(img.id || '')
    const nextAddedIds = new Set(ctx.addedImageIds.get())
    nextAddedIds.delete(imgId)
    ctx.addedImageIds.set(nextAddedIds)

    // 服务端生成记录：调用 unSetFinalImage 取消最终图选中
    if (img._fromServer) {
      const storyboardId = ctx.currentStoryboardId()
      const recordId = ctx.resolveStoryboardRecordId(img)
      if (!storyboardId || !recordId) {
        message.warning('分镜信息异常，请刷新后重试')
        return
      }

      ctx.isSettingFinalImage.set(true)
      try {
        const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
        await userStoryboardUnSetFinalImage({
          ...(saveCtx ? { projectId: saveCtx.projectId, episodeId: saveCtx.episodeId } : {}),
          storyboardId,
          recordId
        })
        if (saveCtx) clearProjectStoryboardRecordCache(saveCtx)
        await ctx.refreshSceneRecords(ctx.currentSceneIndex.get(), undefined, undefined, {
          force: true
        })
        message.success('已取消添加')
      } catch (e: unknown) {
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || '取消分镜主图失败')
      } finally {
        ctx.isSettingFinalImage.set(false)
      }
      return
    }

    const ci = ctx.currentSceneIndex.get()
    const parentImages = ctx.props().scenes[ci]?.images || []
    const inParent = parentImages.some((x: any) => x.id === img.id)

    if (inParent) {
      const nextImages = parentImages.filter((x: any) => x.id !== img.id)
      ctx.emitUpdate(ci, { ...ctx.props().scenes[ci], images: nextImages })
    }

    ctx.localSceneImages.set(ctx.localSceneImages.get().filter((_, i) => i !== index))
    if (ctx.pendingImage.current?.id === img.id) ctx.pendingImage.current = null

    void nextTick(() => {
      const n = ctx.localSceneImages.get().length
      if (n === 0) {
        ctx.currentImageIndex.set(0)
      } else if (ctx.currentImageIndex.get() >= n) {
        ctx.currentImageIndex.set(n - 1)
      }
    })

    message.success('已取消添加，请重新点击「添加分镜图」确认添加')
  }

  async function handleSetMainFromHistory(imageIndex: number) {
    if (ctx.isSettingFinalImage.get()) return
    ctx.currentImageIndex.set(imageIndex)
    await handleConfirmAddImage(imageIndex)
  }

  const handleCancel = () => {
    const sceneIdx = ctx.currentSceneIndex.get()

    if (ctx.isSelectingSceneImage.get()) {
      if (ctx.selectedSceneImageIndex.get() !== null) {
        const selectedSceneIndex = ctx.selectedSceneImageIndex.get()!
        const firstImage = ctx.getFirstSceneImage(selectedSceneIndex)

        if (firstImage) {
          const now = new Date()
          const newImage = {
            ...firstImage,
            id: Date.now().toString() + Math.random().toString(36).substr(2, 9),
            source: '场景关联',
            importDate: now.toISOString(),
            createdAt: now.toISOString()
          }

          const updatedScenes = [...ctx.props().scenes]
          if (!updatedScenes[ctx.currentSceneIndex.get()].images) {
            updatedScenes[ctx.currentSceneIndex.get()].images = []
          }

          if (ctx.addingAfterIndex.current !== null) {
            updatedScenes[ctx.currentSceneIndex.get()].images!.splice(
              ctx.addingAfterIndex.current + 1,
              0,
              newImage
            )
            ctx.currentImageIndex.set(ctx.addingAfterIndex.current + 1)
          } else {
            updatedScenes[ctx.currentSceneIndex.get()].images!.push(newImage)
            ctx.currentImageIndex.set(updatedScenes[ctx.currentSceneIndex.get()].images!.length - 1)
          }

          ctx.emitUpdate(ctx.currentSceneIndex.get(), updatedScenes[ctx.currentSceneIndex.get()])
          message.success('分镜图已添加')
        }
      }

      ctx.isSelectingSceneImage.set(false)
      ctx.selectedSceneImageIndex.set(null)
      ctx.addingAfterIndex.current = null
    } else {
      const updatedScenes = [...ctx.props().scenes]
      if (!updatedScenes[ctx.currentSceneIndex.get()].images) {
        updatedScenes[ctx.currentSceneIndex.get()].images = []
      }
      updatedScenes[ctx.currentSceneIndex.get()].images = ctx.localSceneImages
        .get()
        .filter((img) => !img._pending)
        .map((img) => {
          const { _pending, ...rest } = img
          void _pending
          return rest
        })

      if (updatedScenes[ctx.currentSceneIndex.get()].images!.length > 0) {
        ctx.emitUpdate(ctx.currentSceneIndex.get(), updatedScenes[ctx.currentSceneIndex.get()])
      }
    }

    // 生成中返回：先 mark dismissed，避免外层 tryReopen 因仍有 gen session 再次打开弹窗
    const closeDismiss = resolveStoryboardImageModalCloseDismiss({
      session: readModalImageGenSession(ctx.storyboardImageModalSessionScope()),
      currentSceneIdx: sceneIdx,
      currentImageIdx: ctx.currentImageIndex.get(),
      currentStoryboardId: ctx.sceneStoryboardIdNum(sceneIdx),
      hasPendingForCurrent: ctx.isAnyModalGenerationPendingForScene(sceneIdx)
    })
    if (closeDismiss?.type === 'dialogue') {
      ctx.dismissModalDialogueUi(
        closeDismiss.storyboardId,
        closeDismiss.sceneIdx,
        closeDismiss.imageIdx
      )
    } else if (closeDismiss?.type === 'overlay') {
      markModalImageGenUserDismissed(
        closeDismiss.storyboardId,
        ctx.storyboardImageModalSessionScope()
      )
      clearModalImageGenSession(ctx.storyboardImageModalSessionScope())
      releaseStoryboardImageModalLiveOwned(closeDismiss.storyboardId)
    } else if (closeDismiss?.type === 'storyboard') {
      ctx.dismissModalStoryboardImageUi(closeDismiss.storyboardId, closeDismiss.sceneIdx)
    } else {
      const sid = ctx.sceneStoryboardIdNum(sceneIdx)
      if (sid != null) releaseStoryboardImageModalLiveOwned(sid)
    }

    ctx.emitOpenChange(false)
  }

  return {
    isUploadingLocalImage,
    switchScene,
    switchImage,
    handleUploadLocalImage,
    handleOpenAssetLibrary,
    handleAssetLibraryImport,
    startEditImageTitle,
    handleImageTitleBlur,
    canDeleteHistoryImage,
    removeLocalPendingImage,
    handleDownloadImage,
    handleDeleteImage,
    persistManualStoryboardImageUrl,
    resolveOrPersistStoryboardImageRecordId,
    handleConfirmAddImage,
    handleAddStoryboardImage,
    handleCancelAddImage,
    handleSetMainFromHistory,
    handleCancel
  }
}
