import { message } from 'antd'
import { userAssetRpsFormImageList } from '~/utils/businessApi'
import { uploadImageToOssWithToast } from '~/utils/ossUpload'
import type { EditSceneImageModalCtx } from './types'

// 本地上传图片
export function handleUploadLocalImageImpl(ctx: EditSceneImageModalCtx) {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/*'
  input.onchange = async (e: any) => {
    const file = e.target.files[0]
    if (file) {
      const url = await uploadImageToOssWithToast(file)
      if (!url) return
      // 新增待添加图片后 currentImageIndex 会切到末尾；
      // 为避免 formId 依赖索引取不到，先继承当前选中图片的 rpsFormId。
      const selectedImg = ctx.localSceneImages.get()[ctx.currentImageIndex.get()] as any
      const selectedFormId = Number(selectedImg?.rpsFormId)
      const maybeSelectedFormId = Number.isFinite(selectedFormId) ? selectedFormId : undefined
      const now = new Date()
      // 不直接添加到列表，只存储到待添加状态
      ctx.pendingImage.current = {
        id: Date.now().toString(),
        url: url,
        thumbnail: url,
        title: file.name.replace(/\.[^/.]+$/, '') || `场景图${ctx.currentSceneImages().length + 1}`,
        createdAt: now.toISOString(),
        source: '本地上传',
        importDate: now.toISOString(),
        _rpsSourceType: 'upload' as const,
        _isSet: false,
        ...(maybeSelectedFormId != null ? { rpsFormId: maybeSelectedFormId } : {}),
        angles: []
      }

      // 将待添加的图片添加到本地列表末尾（仅用于预览，标记为待添加）
      ctx.localSceneImages.set([
        ...ctx.localSceneImages.get(),
        { ...ctx.pendingImage.current, _pending: true }
      ])

      // 切换到待添加的图片
      ctx.currentImageIndex.set(ctx.localSceneImages.get().length - 1)

      // 不通知父组件更新，避免同步到外部场景列表
      // emit('update', currentSceneIndex.value, updatedScenes[currentSceneIndex.value])

      // 上传成功后立即同步到个人资产（不等待“确认添加”）
      const createdResult = await ctx.syncImageToRpsApi(
        url,
        ctx.pendingImage.current.title || '',
        'upload',
        ctx.pendingImage.current
      )
      if (createdResult) {
        const pendingIndex = ctx.localSceneImages.get().length - 1
        // 为了让弹窗/父级“左侧图片列表”立刻出现，需要在 create 成功后拉取 list，
        // 并把该 pending 图片直接标记为“已展示” (_isSet=true)。
        if (createdResult.formId != null && createdResult.imageId != null && pendingIndex >= 0) {
          try {
            const list = await userAssetRpsFormImageList({
              formId: Number(createdResult.formId),
              isUse: null
            })
            const hit =
              (Array.isArray(list) &&
                list.find((x: any) => Number.isFinite(Number(x?.id)) && Number(x.id) === Number(createdResult.imageId))) ||
              (Array.isArray(list) &&
                list.find((x: any) => String(x?.imageUrl || '').trim() === String(url).trim())) ||
              null

            if (hit) {
              const imageUrlFromList = String(hit.imageUrl || url)
              const nextTitle = String(hit.name || ctx.pendingImage.current.title || '')
              const arr = [...ctx.localSceneImages.get()]
              const row = {
                ...arr[pendingIndex],
                url: imageUrlFromList,
                thumbnail: imageUrlFromList,
                title: nextTitle || arr[pendingIndex].title,
                rpsFormId: Number(hit.formId ?? createdResult.formId),
                rpsImageId: Number(hit.id ?? createdResult.imageId),
                _isSet: Number(hit?.isUse) === 1
              }
              delete row._pending
              arr[pendingIndex] = row
              ctx.localSceneImages.set(arr)

              // 清掉 pendingImage 引用，避免后续“确认添加”逻辑重复执行
              ctx.pendingImage.current = null

              ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent())
              message.success('已添加到列表')
            } else {
              // list 未命中时退化：至少写回 id，保留 _pending 让用户可继续点“确认添加”
              ctx.pendingImage.current = {
                ...ctx.pendingImage.current,
                ...(createdResult.formId != null ? { rpsFormId: createdResult.formId } : {}),
                ...(createdResult.imageId != null ? { rpsImageId: createdResult.imageId } : {})
              }
              const arr = [...ctx.localSceneImages.get()]
              arr[pendingIndex] = {
                ...arr[pendingIndex],
                ...(createdResult.formId != null ? { rpsFormId: createdResult.formId } : {}),
                ...(createdResult.imageId != null ? { rpsImageId: createdResult.imageId } : {})
              }
              ctx.localSceneImages.set(arr)
              message.info(`图片已导入，请点击"${ctx.addImageButtonLabel()}"按钮确认添加`)
            }
          } catch {
            // list 请求失败不阻断体验：仍保留 pending，允许用户点确认添加
            ctx.pendingImage.current = {
              ...ctx.pendingImage.current,
              ...(createdResult.formId != null ? { rpsFormId: createdResult.formId } : {}),
              ...(createdResult.imageId != null ? { rpsImageId: createdResult.imageId } : {})
            }
            if (pendingIndex >= 0) {
              const arr = [...ctx.localSceneImages.get()]
              arr[pendingIndex] = {
                ...arr[pendingIndex],
                ...(createdResult.formId != null ? { rpsFormId: createdResult.formId } : {}),
                ...(createdResult.imageId != null ? { rpsImageId: createdResult.imageId } : {})
              }
              ctx.localSceneImages.set(arr)
            }
            message.info(`图片已导入，请点击"${ctx.addImageButtonLabel()}"按钮确认添加`)
          }
        } else {
          // create 成功但缺失返回字段：退化为旧行为（等待用户确认添加）
          ctx.pendingImage.current = {
            ...ctx.pendingImage.current,
            ...(createdResult.formId != null ? { rpsFormId: createdResult.formId } : {}),
            ...(createdResult.imageId != null ? { rpsImageId: createdResult.imageId } : {})
          }
          const pendingIndex2 = ctx.localSceneImages.get().length - 1
          if (pendingIndex2 >= 0) {
            const arr = [...ctx.localSceneImages.get()]
            arr[pendingIndex2] = {
              ...arr[pendingIndex2],
              ...(createdResult.formId != null ? { rpsFormId: createdResult.formId } : {}),
              ...(createdResult.imageId != null ? { rpsImageId: createdResult.imageId } : {})
            }
            ctx.localSceneImages.set(arr)
          }
          message.info(`图片已导入，请点击"${ctx.addImageButtonLabel()}"按钮确认添加`)
        }
      }
      // 成功命中 list 并自动添加后，message 已在 try/catch 内处理，无需重复提示

      // 滚动到待添加的图片
      setTimeout(() => {
        void ctx.switchImage(ctx.currentImageIndex.get())
      }, 0)
    }
  }
  input.click()
}

// 处理资源库导入
export async function handleAssetLibraryImportImpl(ctx: EditSceneImageModalCtx, asset: any) {
  // 不直接添加到列表，只存储到待添加状态，不更新场景列表
  const imageUrl = asset.url || asset.thumbnail || 'https://picsum.photos/800/450?random=' + Date.now()
  // 同步继承当前选中项的 rpsFormId，避免新增项 formId 推断越界。
  const selectedImg = ctx.localSceneImages.get()[ctx.currentImageIndex.get()] as any
  const selectedFormId = Number(selectedImg?.rpsFormId)
  const maybeSelectedFormId = Number.isFinite(selectedFormId) ? selectedFormId : undefined
  const now = new Date()
  ctx.pendingImage.current = {
    id: Date.now().toString(),
    url: imageUrl,
    thumbnail: imageUrl,
    title: asset.name || `场景图${ctx.currentSceneImages().length + 1}`,
    createdAt: now.toISOString(),
    source: '资源库导入',
    importDate: now.toISOString(),
    _rpsSourceType: 'official' as const,
    _isSet: false,
    ...(maybeSelectedFormId != null ? { rpsFormId: maybeSelectedFormId } : {}),
    angles: []
  }

  // 将待添加的图片添加到本地列表末尾（仅用于预览，标记为待添加）
  ctx.localSceneImages.set([
    ...ctx.localSceneImages.get(),
    { ...ctx.pendingImage.current, _pending: true }
  ])

  // 切换到待添加的图片
  ctx.currentImageIndex.set(ctx.localSceneImages.get().length - 1)

  // 不通知父组件更新，避免同步到外部场景列表
  // emit('update', currentSceneIndex.value, updatedScenes[currentSceneIndex.value])

  // 资产库导入成功后立即同步到个人资产（不等待“确认添加”）
  const createdResult = await ctx.syncImageToRpsApi(
    imageUrl,
    ctx.pendingImage.current?.title || '',
    'official',
    ctx.pendingImage.current
  )
  if (createdResult && ctx.pendingImage.current) {
    const pendingIndex = ctx.localSceneImages.get().length - 1
    if (createdResult.formId != null && createdResult.imageId != null && pendingIndex >= 0) {
      try {
        const list = await userAssetRpsFormImageList({
          formId: Number(createdResult.formId),
          isUse: null
        })
        const hit =
          (Array.isArray(list) &&
            list.find(
              (x: any) => Number.isFinite(Number(x?.id)) && Number(x.id) === Number(createdResult.imageId)
            )) ||
          (Array.isArray(list) &&
            list.find((x: any) => String(x?.imageUrl || '').trim() === String(imageUrl).trim())) ||
          null

        if (hit) {
          const imageUrlFromList = String(hit.imageUrl || imageUrl)
          const nextTitle = String(hit.name || ctx.pendingImage.current.title || '')
          const arr = [...ctx.localSceneImages.get()]
          const row = {
            ...arr[pendingIndex],
            url: imageUrlFromList,
            thumbnail: imageUrlFromList,
            title: nextTitle || arr[pendingIndex].title,
            rpsFormId: Number(hit.formId ?? createdResult.formId),
            rpsImageId: Number(hit.id ?? createdResult.imageId),
            _isSet: Number(hit?.isUse) === 1
          }
          delete row._pending
          arr[pendingIndex] = row
          ctx.localSceneImages.set(arr)

          ctx.pendingImage.current = null
          ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent())
          message.success('已添加到列表')
        } else {
          ctx.pendingImage.current = {
            ...ctx.pendingImage.current,
            ...(createdResult.formId != null ? { rpsFormId: createdResult.formId } : {}),
            ...(createdResult.imageId != null ? { rpsImageId: createdResult.imageId } : {})
          }
          const arr = [...ctx.localSceneImages.get()]
          arr[pendingIndex] = {
            ...arr[pendingIndex],
            ...(createdResult.formId != null ? { rpsFormId: createdResult.formId } : {}),
            ...(createdResult.imageId != null ? { rpsImageId: createdResult.imageId } : {})
          }
          ctx.localSceneImages.set(arr)
          message.info(`图片已导入，请点击"${ctx.addImageButtonLabel()}"按钮确认添加`)
        }
      } catch {
        ctx.pendingImage.current = {
          ...ctx.pendingImage.current,
          ...(createdResult.formId != null ? { rpsFormId: createdResult.formId } : {}),
          ...(createdResult.imageId != null ? { rpsImageId: createdResult.imageId } : {})
        }
        const arr = [...ctx.localSceneImages.get()]
        arr[pendingIndex] = {
          ...arr[pendingIndex],
          ...(createdResult.formId != null ? { rpsFormId: createdResult.formId } : {}),
          ...(createdResult.imageId != null ? { rpsImageId: createdResult.imageId } : {})
        }
        ctx.localSceneImages.set(arr)
        message.info(`图片已导入，请点击"${ctx.addImageButtonLabel()}"按钮确认添加`)
      }
    } else {
      ctx.pendingImage.current = {
        ...ctx.pendingImage.current,
        ...(createdResult.formId != null ? { rpsFormId: createdResult.formId } : {}),
        ...(createdResult.imageId != null ? { rpsImageId: createdResult.imageId } : {})
      }
      const arr = [...ctx.localSceneImages.get()]
      arr[pendingIndex] = {
        ...arr[pendingIndex],
        ...(createdResult.formId != null ? { rpsFormId: createdResult.formId } : {}),
        ...(createdResult.imageId != null ? { rpsImageId: createdResult.imageId } : {})
      }
      ctx.localSceneImages.set(arr)
      message.info(`图片已导入，请点击"${ctx.addImageButtonLabel()}"按钮确认添加`)
    }
  }

  ctx.showAssetLibraryModal.set(false)

  // 滚动到待添加的图片
  setTimeout(() => {
    void ctx.switchImage(ctx.currentImageIndex.get())
  }, 0)
}
