import { message } from 'antd'
import { userAssetRpsUpdateMain } from '~/utils/businessApi'
import { getSceneName, getScenePrefix } from './scpRowUtils'
import { syncImageTitleToRps } from './useScpRpsOps'
import type { ScpCtx } from './types'

export function createScpSceneNameOps(ctx: ScpCtx) {
  const startEditSceneName = (index: number, currentName: string) => {
    ctx.editingSceneIndex.set(index)
    ctx.editingSceneName.set(getSceneName(currentName))
  }

  const handleSceneNameBlur = async (index: number) => {
    const editedName = ctx.editingSceneName.get().trim()
    if (ctx.editingSceneIndex.get() !== index || !editedName) {
      resetSceneNameEdit(ctx)
      return
    }
    const prefix = getScenePrefix(ctx.localValue.get().scenes[index])
    const nextName = prefix ? `${prefix} ${editedName}` : editedName
    const previousName = ctx.localValue.get().scenes[index]
    if (nextName === previousName) {
      resetSceneNameEdit(ctx)
      return
    }

    const assetId = ctx.sceneAssetIds.get()[index]
    if (assetId != null) {
      try {
        await userAssetRpsUpdateMain({ id: assetId, name: nextName })
      } catch (error: unknown) {
        const value = error as { msg?: string; message?: string }
        message.error(value?.msg || value?.message || '场景名称同步失败')
        resetSceneNameEdit(ctx)
        return
      }
    }
    const scenes = [...ctx.localValue.get().scenes]
    scenes[index] = nextName
    ctx.update({ ...ctx.localValue.get(), scenes })
    if (ctx.sceneSettings.get()[previousName]) {
      const settings = { ...ctx.sceneSettings.get() }
      settings[nextName] = settings[previousName]
      delete settings[previousName]
      ctx.sceneSettings.set(settings)
    }
    message.success(assetId != null ? '场景名称已更新' : '场景名称已更新（仅本地）')
    resetSceneNameEdit(ctx)
  }

  const startEditImageTitle = (sceneIndex: number, imageIndex: number, currentTitle: string) => {
    ctx.editingImageTitleIndex.set(`${sceneIndex}-${imageIndex}`)
    ctx.editingImageTitle.set(currentTitle || `场景图${imageIndex + 1}`)
  }

  const handleImageTitleBlur = async (sceneIndex: number, imageIndex: number) => {
    const isCurrent = ctx.editingImageTitleIndex.get() === `${sceneIndex}-${imageIndex}`
    const nextTitle = ctx.editingImageTitle.get().trim()
    if (isCurrent && nextTitle) {
      const image = ctx.sceneImages.get()[sceneIndex]?.[imageIndex]
      if (image) {
        const saved = await syncImageTitleToRps(image, nextTitle)
        if (!saved) {
          resetImageTitleEdit(ctx)
          return
        }
        image.title = nextTitle
        ctx.sceneImages.set({ ...ctx.sceneImages.get() })
        message.success('场景图名称已更新')
      }
    }
    resetImageTitleEdit(ctx)
  }

  return { startEditSceneName, handleSceneNameBlur, startEditImageTitle, handleImageTitleBlur }
}

function resetSceneNameEdit(ctx: ScpCtx) {
  ctx.editingSceneIndex.set(null)
  ctx.editingSceneName.set('')
}

function resetImageTitleEdit(ctx: ScpCtx) {
  ctx.editingImageTitleIndex.set(null)
  ctx.editingImageTitle.set('')
}
