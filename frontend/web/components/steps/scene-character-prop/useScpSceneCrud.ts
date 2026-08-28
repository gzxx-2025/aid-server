'use client'

import { message, Modal } from 'antd'
import {
  userAssetRpsCreate,
  userAssetRpsDelete,
  userAssetRpsFormCreate,
  rpsRowToUserAssetRow
} from '~/utils/businessApi'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { isStep3ListEditImageDisabled } from '~/utils/step3EditImageGate'
import {
  buildImagesFromAssetRow,
  reindexAssetIdMap,
  reindexFormIdsByIndexMap,
  reindexSceneGenerationStatusMap
} from './scpRowUtils'
import { preloadEditSceneImageModal } from './editSceneImageModalLoader'
import type { ScpCtx, TabKey } from './types'
import { createScpSceneImageCrudOps } from './scpSceneImageCrudOps'
import { createScpSceneNameOps } from './scpSceneNameOps'
import {
  saveRpsSettingPrompt,
  settingEditorStateFromRpsRow
} from './scpSettingPromptUtils'

export interface ScpSceneCrudApi {
  getScpAssetListItemEl: (tab: TabKey, index: number) => HTMLElement | null
  /** 手动添加场景/角色/道具后，列表超出一屏时滚动到新项 */
  scrollToManualAddedAsset: (tab: TabKey, index: number, behavior?: ScrollBehavior) => void
  addScene: () => Promise<void>
  removeScene: (idx: number) => void
  // 开始编辑场景名称
  startEditSceneName: (index: number, currentName: string) => void
  // 完成编辑场景名称
  handleSceneNameBlur: (index: number) => Promise<void>
  // 开始编辑场景图名称
  startEditImageTitle: (sceneIndex: number, imageIndex: number, currentTitle: string) => void
  // 完成编辑场景图名称
  handleImageTitleBlur: (sceneIndex: number, imageIndex: number) => Promise<void>
  handleEditSceneSetting: (index: number) => void
  handleEditSceneImage: (index: number) => void
  handleEditSceneImageWithIndex: (sceneIndex: number, imageIndex: number) => void
  // 处理场景图片更新（从编辑弹窗返回）
  handleSceneImageUpdate: (sceneIndex: number, sceneData: any) => Promise<void>
  handleCopyScene: (index: number) => void
  // 保存场景设定
  handleSaveSceneSetting: (content: string) => Promise<void>
  // 保存并更新场景图
  handleSaveAndUpdateSceneImage: (content: string) => Promise<void>
  handleImportSceneImage: (index: number) => void
  handleSceneImageImport: (fileOrAsset: File | string | any) => Promise<void>
  // 获取场景的第一张图片（用于显示）
  getSceneImage: (index: number) => any | null
  // 场景图片操作
  handlePreviewSceneImage: (index: number) => void
  handleReplaceSceneImage: (index: number) => void
  handleDownloadSceneImage: (index: number) => void
  handleDeleteSceneImage: (index: number) => void
  // 按索引操作场景图片（支持多张图片）
  handlePreviewSceneImageByIndex: (sceneIndex: number, imageIndex: number) => void
  handleReplaceSceneImageByIndex: (sceneIndex: number, imageIndex: number) => void
  /** 有图卡片底部中间按钮：ai_auto → 重新生成（复用自动生成）；否则 → 替换（导入） */
  handleSceneImageMiddleActionByIndex: (sceneIndex: number, imageIndex: number) => void
  handleDownloadSceneImageByIndex: (sceneIndex: number, imageIndex: number) => void
  handleDeleteSceneImageByIndex: (sceneIndex: number, imageIndex: number) => Promise<void>
}

export function useScpSceneCrud(ctx: ScpCtx): ScpSceneCrudApi {
  const sceneImageOps = createScpSceneImageCrudOps(ctx)
  const sceneNameOps = createScpSceneNameOps(ctx)
  function getScpAssetListItemEl(tab: TabKey, index: number): HTMLElement | null {
    const root = ctx.scpContentRef.current
    if (!root) return null
    return root.querySelector(
      `[data-scp-asset-tab="${tab}"][data-scp-asset-index="${index}"]`
    ) as HTMLElement | null
  }

  /** 手动添加场景/角色/道具后，列表超出一屏时滚动到新项 */
  function scrollToManualAddedAsset(tab: TabKey, index: number, behavior: ScrollBehavior = 'smooth') {
    let attempts = 0
    const maxAttempts = 8

    const run = () => {
      attempts += 1
      const container = ctx.scpContentRef.current
      if (!container) {
        if (attempts < maxAttempts) requestAnimationFrame(run)
        return
      }

      const maxScroll = Math.max(0, container.scrollHeight - container.clientHeight)
      if (maxScroll <= 0) return

      const target = getScpAssetListItemEl(tab, index)
      if (!target) {
        if (attempts < maxAttempts) requestAnimationFrame(run)
        return
      }

      const containerRect = container.getBoundingClientRect()
      const targetRect = target.getBoundingClientRect()
      const topOffset = 12
      const nextTop = container.scrollTop + (targetRect.top - containerRect.top) - topOffset
      container.scrollTo({ top: Math.max(0, Math.min(nextTop, maxScroll)), behavior })
    }

    // 原 nextTick(nextTick(requestAnimationFrame))：等两轮渲染后再滚动
    setTimeout(() => {
      setTimeout(() => {
        requestAnimationFrame(run)
      }, 0)
    }, 0)
  }

  // 手动增删
  const addScene = async () => {
    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    if (!saveCtx) {
      message.warning('缺少项目或剧集信息，请从「我的作品」进入创作流程后再添加')
      return
    }
    try {
      const pendingName = `场景${ctx.localValue.get().scenes.length + 1}: 未命名`
      let row = await userAssetRpsCreate({
        projectId: saveCtx.projectId,
        episodeId: saveCtx.episodeId,
        name: pendingName,
        assetType: 'scene'
      })
      const mainAssetId = row.id != null && Number.isFinite(Number(row.id)) ? Number(row.id) : 0
      // 创建主资产后，若后端未返回任何形态，则立即补建一条默认形态。
      if ((row.forms ?? []).length === 0 && mainAssetId > 0) {
        try {
          row = await userAssetRpsFormCreate({
            projectId: saveCtx.projectId,
            episodeId: saveCtx.episodeId,
            assetId: mainAssetId,
            imageUrl: '',
            name: '形态1: 未命名',
            sourceType: 'official'
          })
        } catch (e: unknown) {
          const err = e as { msg?: string; message?: string }
          message.warning(err?.msg || err?.message || '场景形态初始化失败，可在后续导图时自动补齐')
        }
      }
      const newIndex = ctx.localValue.get().scenes.length
      const name = row.assetName || pendingName
      ctx.update({ ...ctx.localValue.get(), scenes: [...ctx.localValue.get().scenes, name] })
      const nextManualScenes = new Set(ctx.manualScenes.get())
      nextManualScenes.add(newIndex)
      ctx.manualScenes.set(nextManualScenes)
      ctx.store().addManualScene(newIndex)
      ctx.sceneGenerationStatus.set({ ...ctx.sceneGenerationStatus.get(), [newIndex]: 'idle' })
      ctx.store().setSceneGenerationStatus(newIndex, 'idle')
      ctx.sceneSettings.set({
        ...ctx.sceneSettings.get(),
        [name]: {
          ...settingEditorStateFromRpsRow(row, 'scene'),
          isNew: true
        }
      })
      const imgs = buildImagesFromAssetRow(rpsRowToUserAssetRow(row))
      if (imgs.length) {
        ctx.sceneImages.set({ ...ctx.sceneImages.get(), [newIndex]: imgs })
      }
      if (mainAssetId > 0) {
        ctx.sceneAssetIds.set({ ...ctx.sceneAssetIds.get(), [newIndex]: mainAssetId })
        ctx.store().addManualSceneAssetId(mainAssetId)
      }
      const formIds = (row.forms ?? [])
        .map((f) => f.id)
        .filter((n): n is number => n != null && Number.isFinite(Number(n)))
      ctx.sceneFormIdsByIndex.set({ ...ctx.sceneFormIdsByIndex.get(), [newIndex]: formIds })
      scrollToManualAddedAsset('scene', newIndex)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '创建场景失败')
    }
  }

  const removeScene = (idx: number) => {
    const sceneName = ctx.localValue.get().scenes[idx]

    // 显示确认删除弹窗
    Modal.confirm({
      title: '确认删除场景及相关剧情?',
      content: '将同时删除场景图与相关剧情内容。',
      okText: '确认删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        const aid = ctx.sceneAssetIds.get()[idx]
        try {
          // 删除场景时仅传主资产 id，后端级联删除该场景全部形态与图片
          if (aid != null) {
            await userAssetRpsDelete({ id: aid })
            ctx.store().removeManualSceneAssetId(Number(aid))
          }
        } catch (e: unknown) {
          const err = e as { msg?: string; message?: string }
          message.error(err?.msg || err?.message || '删除场景失败')
          throw e
        }

        ctx.update({
          ...ctx.localValue.get(),
          scenes: ctx.localValue.get().scenes.filter((_, i) => i !== idx)
        })

        // 更新手动场景索引
        const newManualScenes = new Set<number>()
        ctx.manualScenes.get().forEach((index) => {
          if (index < idx) {
            newManualScenes.add(index)
          } else if (index > idx) {
            newManualScenes.add(index - 1)
          }
        })
        ctx.manualScenes.set(newManualScenes)
        // 同步到 store：删除 idx 并调整大于 idx 的索引
        ctx.patchStore({ manualScenes: Array.from(newManualScenes) })

        // 删除场景设置
        const nextSettings = { ...ctx.sceneSettings.get() }
        delete nextSettings[sceneName]
        ctx.sceneSettings.set(nextSettings)

        // 删除场景图片
        if (ctx.sceneImages.get()[idx]) {
          const cur = { ...ctx.sceneImages.get() }
          delete cur[idx]
          // 重新索引场景图片
          const newSceneImages: Record<number, any[]> = {}
          Object.keys(cur).forEach((key) => {
            const oldIndex = Number(key)
            if (oldIndex < idx) {
              newSceneImages[oldIndex] = cur[oldIndex]
            } else if (oldIndex > idx) {
              newSceneImages[oldIndex - 1] = cur[oldIndex]
            }
          })
          ctx.sceneImages.set(newSceneImages)
        }

        ctx.sceneAssetIds.set(reindexAssetIdMap(ctx.sceneAssetIds.get(), idx))
        ctx.sceneFormIdsByIndex.set(reindexFormIdsByIndexMap(ctx.sceneFormIdsByIndex.get(), idx))
        ctx.sceneGenerationStatus.set(
          reindexSceneGenerationStatusMap(ctx.sceneGenerationStatus.get(), idx)
        )
        ctx.patchStore({ sceneGenerationStatus: { ...ctx.sceneGenerationStatus.get() } })
        ctx.store().syncStep3GenVisualToCurrentScope()

        message.success('场景及相关内容已删除')
      }
    })
  }

  const handleEditSceneSetting = (index: number) => {
    ctx.currentSceneIndex.set(index)
    const sceneName = ctx.localValue.get().scenes[index]
    // 如果没有保存过内容，初始化为空
    if (!ctx.sceneSettings.get()[sceneName]) {
      ctx.sceneSettings.set({ ...ctx.sceneSettings.get(), [sceneName]: { content: '', isNew: true } })
    }
    ctx.showSceneSettingModal.set(true)
  }

  const handleEditSceneImage = (index: number) => {
    if (isStep3ListEditImageDisabled(ctx.sceneGenerationStatus.get()[index])) return
    void preloadEditSceneImageModal()
    ctx.currentEditSceneIndex.set(index)
    ctx.currentEditImageIndex.set(null) // 不指定图片索引，默认选中第一张
    ctx.showEditSceneImageModal.set(true)
  }

  // 带图片索引的编辑场景图（点击图片时调用）
  const handleEditSceneImageWithIndex = (sceneIndex: number, imageIndex: number) => {
    void preloadEditSceneImageModal()
    ctx.currentEditSceneIndex.set(sceneIndex)
    ctx.currentEditImageIndex.set(imageIndex)
    ctx.showEditSceneImageModal.set(true)
  }

  // 处理场景图片更新（从编辑弹窗返回）
  const handleSceneImageUpdate = async (sceneIndex: number, sceneData: any) => {
    const sceneName = ctx.localValue.get().scenes[sceneIndex]
    if (sceneData && sceneData.setting !== undefined && sceneName) {
      try {
        const updatedSetting = await saveRpsSettingPrompt(
          'scene',
          ctx.sceneSettings.get()[sceneName],
          String(sceneData.setting ?? '')
        )
        ctx.sceneSettings.set({
          ...ctx.sceneSettings.get(),
          [sceneName]: updatedSetting
        })
      } catch (e: unknown) {
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || '场景提示词同步失败')
        return
      }
    }
    if (sceneData && sceneData.images) {
      // 确保使用数组的深拷贝，避免引用问题
      const nextImages = {
        ...ctx.sceneImages.get(),
        [sceneIndex]: Array.isArray(sceneData.images)
          ? sceneData.images.map((img: any) => ({ ...img }))
          : []
      }
      // 强制触发响应式更新
      ctx.sceneImages.set(nextImages)
      // 弹窗生图成功回写图片后：若该槽已无进行中任务，立即清掉列表卡片 loading
      if (
        ctx.sceneGenerationStatus.get()[sceneIndex] === 'generating' &&
        !ctx.sceneIndexHasActiveFormImageGeneration(sceneIndex) &&
        ctx.sceneSlotHasLoadedImages(sceneIndex)
      ) {
        ctx.sceneGenerationStatus.set({ ...ctx.sceneGenerationStatus.get(), [sceneIndex]: 'success' })
        ctx.store().setSceneGenerationStatus(sceneIndex, 'success')
        ctx.store().refreshStep3VisualGeneratingFlag()
      }
    }
    if (sceneData?.rpsRow) {
      ctx.applyRpsRowFormIds('scene', sceneIndex, sceneData.rpsRow)
    }
    ctx.syncLocalStep3GeneratingFromPinia()
  }

  const handleCopyScene = (index: number) => {
    const sceneName = ctx.localValue.get().scenes[index]
    const newIndex = ctx.localValue.get().scenes.length

    // 生成新的场景名称（保持场景编号格式）
    const sceneNameMatch = sceneName.match(/^(场景\d+):\s*(.+)$/)
    let newSceneName: string

    if (sceneNameMatch) {
      // 如果有场景编号格式，生成新的编号
      const newSceneNumber = newIndex + 1
      newSceneName = `场景${newSceneNumber}: ${sceneNameMatch[2]}`
    } else {
      // 如果没有场景编号格式，直接添加副本后缀
      newSceneName = `${sceneName}_副本`
    }

    // 添加新场景
    ctx.update({
      ...ctx.localValue.get(),
      scenes: [...ctx.localValue.get().scenes, newSceneName]
    })

    // 复制场景设定
    if (ctx.sceneSettings.get()[sceneName]) {
      ctx.sceneSettings.set({
        ...ctx.sceneSettings.get(),
        [newSceneName]: {
          content: ctx.sceneSettings.get()[sceneName].content,
          isNew: true
        }
      })
    }

    // 复制场景图片（深拷贝）
    if (ctx.sceneImages.get()[index] && ctx.sceneImages.get()[index].length > 0) {
      ctx.sceneImages.set({
        ...ctx.sceneImages.get(),
        [newIndex]: ctx.sceneImages.get()[index].map((img: any) => ({
          ...img,
          id: Date.now().toString() + Math.random().toString(36).substr(2, 9),
          createdAt: new Date().toISOString()
        }))
      })
    }

    // 标记为手动添加的场景
    const nextManualScenes = new Set(ctx.manualScenes.get())
    nextManualScenes.add(newIndex)
    ctx.manualScenes.set(nextManualScenes)
    ctx.store().addManualScene(newIndex)

    message.success('场景已复制')
  }

  // 保存场景设定
  const handleSaveSceneSetting = async (content: string) => {
    const sceneName = ctx.currentSceneName()
    if (!sceneName) return
    try {
      const updatedSetting = await saveRpsSettingPrompt(
        'scene',
        ctx.sceneSettings.get()[sceneName],
        content
      )
      ctx.sceneSettings.set({
        ...ctx.sceneSettings.get(),
        [sceneName]: updatedSetting
      })
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '场景提示词同步失败')
      return
    }
    ctx.showSceneSettingModal.set(false)
    message.success('场景提示词已保存并同步')
  }

  // 保存并更新场景图
  const handleSaveAndUpdateSceneImage = async (content: string) => {
    const sceneName = ctx.currentSceneName()
    if (!sceneName) return
    try {
      const updatedSetting = await saveRpsSettingPrompt(
        'scene',
        ctx.sceneSettings.get()[sceneName],
        content
      )
      ctx.sceneSettings.set({
        ...ctx.sceneSettings.get(),
        [sceneName]: updatedSetting
      })
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '场景提示词同步失败')
      return
    }
    ctx.showSceneSettingModal.set(false)
    message.info('场景提示词已同步，可在编辑场景图中发起新生成')
  }

  return {
    getScpAssetListItemEl,
    scrollToManualAddedAsset,
    addScene,
    removeScene,
    ...sceneNameOps,
    handleEditSceneSetting,
    handleEditSceneImage,
    handleEditSceneImageWithIndex,
    handleSceneImageUpdate,
    handleCopyScene,
    handleSaveSceneSetting,
    handleSaveAndUpdateSceneImage,
    ...sceneImageOps
  }
}
