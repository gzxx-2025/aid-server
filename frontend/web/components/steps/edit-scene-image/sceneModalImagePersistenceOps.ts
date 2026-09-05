'use client'

import { message } from 'antd'
import { useCreationStore } from '~/stores/creation'
import {
userAssetRpsFormImageCreate,
userAssetRpsFormImageList,
userAssetRpsFormImageUpdate
} from '~/utils/businessApi'
import { setFormImageInUse,unsetFormImageInUse } from '~/utils/formImageAutoUse'
import { isSinglePrimaryImageType } from './sceneModalTaskParsers'
import type { EditSceneImageModalCtx } from './types'

export function createSceneModalImagePersistenceOps(ctx: EditSceneImageModalCtx) {
  async function reserveSetRpsForm(payload: {
    imageId?: number
    formId?: number
    imageType: 'scene' | 'character' | 'prop' | 'form'
  }): Promise<boolean> {
    let imageId = payload.imageId
    if ((imageId == null || !Number.isFinite(Number(imageId))) && payload.formId != null && Number.isFinite(Number(payload.formId))) {
      try {
        const list = await userAssetRpsFormImageList({ formId: Number(payload.formId) })
        const preferred =
          (Array.isArray(list) ? list : []).find((x: any) => Number(x?.isUse) === 1 && Number.isFinite(Number(x?.id))) ??
          (Array.isArray(list) ? list : []).find((x: any) => Number.isFinite(Number(x?.id))) ??
          null
        imageId = preferred ? Number(preferred.id) : undefined
      } catch {
        imageId = undefined
      }
    }
    if (imageId == null || !Number.isFinite(Number(imageId))) return true
    const ok = await setFormImageInUse(Number(imageId), {
      projectId: Number(useCreationStore.getState().currentProjectId) || undefined
    })
    if (!ok) {
      message.error('设置主图失败')
      return false
    }
    return true
  }

  /** 取消从表形态使用（列表与 Tab 不再展示该主图） */
  async function resolveInUseImageIdByFormId(formId?: number): Promise<number | null> {
    if (formId == null || !Number.isFinite(Number(formId))) return null
    try {
      const list = await userAssetRpsFormImageList({ formId: Number(formId), isUse: null })
      const first = Array.isArray(list) ? list[0] : null
      const id = Number(first?.id)
      return Number.isFinite(id) ? id : null
    } catch {
      return null
    }
  }

  async function reserveUnsetRpsForm(payload: { imageId?: number; formId?: number }): Promise<boolean> {
    let targetImageId =
      payload.imageId != null && Number.isFinite(Number(payload.imageId)) ? Number(payload.imageId) : null
    if (targetImageId == null) {
      targetImageId = await resolveInUseImageIdByFormId(payload.formId)
    }
    if (targetImageId == null) return true
    const pid = Number(useCreationStore.getState().currentProjectId)
    const result = await unsetFormImageInUse(Number(targetImageId), {
      projectId: Number.isFinite(pid) && pid > 0 ? pid : undefined
    })
    if (!result.ok) {
      message.error(result.reason || '取消主图失败')
      return false
    }
    return true
  }

  /**
   * 通过 form-image/list 解析当前选中图对应的形态图实例 ID（imgId）。
   * 匹配优先级：rpsImageId > imageUrl > name > 首条可用记录。
   */
  async function resolveImageIdFromFormImageList(payload: {
    formId?: number
    imageId?: number
    imageUrl?: string
    imageTitle?: string
  }): Promise<number | null> {
    const formId =
      payload.formId != null && Number.isFinite(Number(payload.formId)) ? Number(payload.formId) : null
    if (formId == null) {
      return payload.imageId != null && Number.isFinite(Number(payload.imageId)) ? Number(payload.imageId) : null
    }

    try {
      const list = await userAssetRpsFormImageList({ formId })
      if (!Array.isArray(list) || list.length === 0) return null

      const normalizedInputId =
        payload.imageId != null && Number.isFinite(Number(payload.imageId)) ? Number(payload.imageId) : null
      if (normalizedInputId != null) {
        const hitById = list.find((x: any) => Number.isFinite(Number(x?.id)) && Number(x.id) === normalizedInputId)
        if (hitById?.id != null && Number.isFinite(Number(hitById.id))) return Number(hitById.id)
      }

      const normalizedUrl = String(payload.imageUrl || '').trim()
      if (normalizedUrl) {
        const hitByUrl = list.find((x: any) => String(x?.imageUrl || '').trim() === normalizedUrl)
        if (hitByUrl?.id != null && Number.isFinite(Number(hitByUrl.id))) return Number(hitByUrl.id)
      }

      const normalizedTitle = String(payload.imageTitle || '').trim()
      if (normalizedTitle) {
        const hitByName = list.find((x: any) => String(x?.name || '').trim() === normalizedTitle)
        if (hitByName?.id != null && Number.isFinite(Number(hitByName.id))) return Number(hitByName.id)
      }

      const fallback = list.find((x: any) => x?.id != null && Number.isFinite(Number(x.id)))
      return fallback?.id != null && Number.isFinite(Number(fallback.id)) ? Number(fallback.id) : null
    } catch {
      return null
    }
  }

  /** 只把“已设置”的图片同步给父组件（决定外部列表和顶部 tab 展示） */
  function buildVisibleImagesForParent() {
    const visibleImages = ctx.localSceneImages
      .get()
      .filter((img) => !img._pending && img?._isSet === true)
    const normalizedImages = isSinglePrimaryImageType(ctx.props().imageType)
      ? visibleImages.slice(-1)
      : visibleImages
    return normalizedImages
      .map((img) => {
        const { _pending, _rpsSourceType, _isSet, ...rest } = img
        return rest
      })
  }

  /** 编辑弹窗内：优先走 form-image 层（create/update）同步 */
  async function syncImageToRpsApi(
    imageUrl: string,
    imageTitle: string,
    sourceType: 'upload' | 'official' | 'ai',
    currentImage?: any
  ): Promise<{ formId?: number; imageId?: number } | null> {
    const assetId = ctx.activeRpsAssetId()
    if (assetId == null || !imageUrl?.trim()) return null

    const formIds = ctx.activeRpsFormIds() ?? []
    let existingFormId: number | undefined
    // 新增待添加图片时，currentImageIndex 可能已切到末尾导致 formIds 越界；
    // 如果 currentImage 自身已经带 rpsFormId，则直接使用它。
    const maybeCurrentFormId = Number(currentImage?.rpsFormId)

    if (ctx.props().imageType === 'form') {
      // 形态图编辑弹窗：无论当前正在编辑该形态下第几张图片
      // 创建形态图都应使用“当前形态（sceneIndex）”对应的 formId。
      const id = formIds[ctx.currentSceneIndex.get()]
      existingFormId = id != null && Number.isFinite(Number(id)) ? Number(id) : undefined
    } else {
      const id = formIds[ctx.currentImageIndex.get()]
      existingFormId = id != null && Number.isFinite(Number(id)) ? Number(id) : undefined
    }

    const resolvedFormId = Number.isFinite(maybeCurrentFormId) ? maybeCurrentFormId : existingFormId
    if (resolvedFormId == null) {
      message.warning(`缺少形态ID，无法创建形态图（imageType=${ctx.props().imageType}, sceneIndex=${ctx.currentSceneIndex.get()}, currentImageIndex=${ctx.currentImageIndex.get()}）`)
      return null
    }

    const maybeImageId = Number(currentImage?.rpsImageId)
    try {
      if (Number.isFinite(maybeImageId)) {
        const updated = await userAssetRpsFormImageUpdate({
          imageId: maybeImageId,
          imageUrl,
          name: imageTitle
        })
        return { formId: resolvedFormId, imageId: Number(updated?.id ?? maybeImageId) }
      }
      const created = await userAssetRpsFormImageCreate({
        formId: resolvedFormId,
        imageUrl,
        name: imageTitle,
        sourceType: sourceType === 'ai' ? 'ai_auto' : sourceType,
        asInUse: false
      })
      return { formId: resolvedFormId, imageId: Number(created?.id) }
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '同步个人资产形态失败')
      return null
    }
  }

  function emitSceneTabUpdate(images: any[], tabIndex?: number) {
    const ci = tabIndex ?? ctx.currentSceneIndex.get()
    const prev = ctx.props().scenes[ci] || { name: '', images: [] }
    ctx.props().onUpdate(ci, {
      ...prev,
      images
    })
  }

  function syncLocalSceneImagesFromSceneIndex(
    sceneIdx: number,
    opts?: { preservePending?: boolean }
  ) {
    const sceneImages = ctx.props().scenes[sceneIdx]?.images || []
    const sceneIds = new Set(sceneImages.map((img: any) => img.id).filter(Boolean))
    const pendingOnly = opts?.preservePending
      ? ctx.localSceneImages.get().filter((img: any) => img?._pending && img?.id && !sceneIds.has(img.id))
      : []
    const mainImageIndex = isSinglePrimaryImageType(ctx.props().imageType)
      ? sceneImages.length - 1
      : -1
    ctx.localSceneImages.set([
      ...sceneImages.map((img: any, index: number) => ({
        ...img,
        _isSet: mainImageIndex < 0 || index === mainImageIndex
      })),
      ...pendingOnly
    ])
    const n = ctx.localSceneImages.get().length
    if (n === 0) {
      ctx.currentImageIndex.set(0)
    } else if (ctx.currentImageIndex.get() >= n) {
      ctx.currentImageIndex.set(n - 1)
    }
  }

  /** 导入参考图弹窗：当前场景以左侧生成记录为准（含 isUse=0 等未同步到父级的图） */
  return {
    buildVisibleImagesForParent,
    emitSceneTabUpdate,
    reserveSetRpsForm,
    reserveUnsetRpsForm,
    resolveImageIdFromFormImageList,
    syncImageToRpsApi,
    syncLocalSceneImagesFromSceneIndex,
  }
}
