'use client'

import { message } from 'antd'
import type { AsyncPromptApplyTicket } from '~/utils/asyncPromptApplyGuard'
import type { ParamSettingsConfirmPayload } from '~/components/steps/StoryboardParamSettingsModal'
import {
buildModalTaskOverlayKey
} from '~/composables/useModalTaskScope'
import { isStoryboardImageTaskOngoing } from '~/composables/useStoryboardImageGenerateTask'
import { userStoryboardGenerateImagePrompt } from '~/utils/businessApi'
import { advanceGenerationToken } from '~/utils/generationToken'
import { openImagePreviewModal } from '~/utils/openImagePreviewModal'
import {
resolveStoryboardGenConfigLlmFields
} from '~/utils/projectGenConfig'
import {
collectStoryboardPromptAssets,
patchEmptyResolvedPromptAssets,
storyboardPromptHtmlToPlain,
type PromptAssetItem
} from '~/utils/storyboardPromptAssetRef'
import {
awaitStoryboardPromptGenerateTask,
resumeStoryboardPromptGenerateTask
} from '~/utils/storyboardPromptGenerateFlow'
import { confirmTaskResumeWithBilling } from '~/utils/taskResumeBilling'
import {
buildStoryboardPromptParamGroups
} from '~/utils/storyboardPromptParamRef'
import { pruneResolvedPromptAssetsForRemovedImage } from '~/utils/storyboardPromptAssetStripSync'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import type { EditStoryboardImageModalCtx,SelectAssetModalType,SettingKey } from './types'
import { useStoryboardModalPromptCore } from './useStoryboardModalPromptCore'

export interface StoryboardModalPromptApi {
  storyboardPromptAssets: () => PromptAssetItem[]
  storyboardPromptParamGroups: () => ReturnType<typeof buildStoryboardPromptParamGroups>
  storyboardPromptPlainText: () => string
  showGeneratingPromptForScene: () => boolean
  ensurePromptDictLoaded: () => Promise<void>
  applyParamSelectionsFromPlain: (plain: string) => void
  applyStoryboardPromptFromApi: (
    plain: string,
    ticket?: AsyncPromptApplyTicket
  ) => Promise<boolean>
  beginStoryboardPromptApply: (storyboardId: number) => AsyncPromptApplyTicket
  handleStoryboardPromptEditorChange: (value: string) => void
  storyboardBizErr: (e: unknown) => string
  fetchStoryboardImagePrompt: (storyboardId: number) => Promise<string>
  fetchStoryboardImagePromptAfterGenerate: (storyboardId: number) => Promise<string>
  resolveImagePromptSubmitFields: () => ReturnType<typeof resolveStoryboardGenConfigLlmFields>
  loadCurrentStoryboardPrompt: () => Promise<void>
  handleGeneratePrompt: () => Promise<void>
  restoreStoryboardPromptGenerateIfNeeded: (sceneIdx: number) => Promise<void>
  copyStoryboardPrompt: () => void
  copyCompositionDesc: () => void
  applyParamSettingsConfirm: (payload: ParamSettingsConfirmPayload) => void
  openSelectModal: (type: SelectAssetModalType) => void
  handleMaterialLibraryOtherImport: (assets: any[]) => void
  onSelectAssetConfirm: (items: any[]) => void
  previewAssetImage: (img: any) => void
  removeOtherImage: (index: number) => void
  removeStoryboardAssetReference: (index: number) => void
  openStoryboardScriptEditor: () => void
  handleSaveScriptInImageModal: (payload: { title: string; content: string }) => void
  handleScriptTitleInImageModal: (title: string) => void
}

export function useStoryboardModalPrompt(
  ctx: EditStoryboardImageModalCtx
): StoryboardModalPromptApi {
  const { applyParamSelectionsFromPlain, applyStoryboardPromptFromApi, beginStoryboardPromptApply, ensurePromptDictLoaded, fetchStoryboardImagePrompt, fetchStoryboardImagePromptAfterGenerate, handleStoryboardPromptEditorChange, loadCurrentStoryboardPrompt, resolveImagePromptSubmitFields, showGeneratingPromptForScene, storyboardBizErr, storyboardPromptAssets, storyboardPromptParamGroups, storyboardPromptPlainText } = useStoryboardModalPromptCore(ctx)
  const handleGeneratePrompt = async () => {
    if (showGeneratingPromptForScene()) return
    const storyboardId = ctx.currentStoryboardId()
    if (!storyboardId) {
      message.warning('分镜ID缺失，无法生成提示词')
      return
    }
    const promptApplyTicket = beginStoryboardPromptApply(storyboardId)

    const sceneIdx = ctx.currentSceneIndex.get()
    ctx.promptGenerateTargetKey.set(
      buildModalTaskOverlayKey(ctx.overlayKeyParts(sceneIdx, -1, 'prompt-gen'))
    )
    ctx.isGeneratingPrompt.set(true)
    const hideLoading = message.loading('正在生成提示词...', 0)
    let keepPendingUi = false

    try {
      const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      if (!saveCtx) {
        message.warning('缺少项目信息，请从「我的作品」打开作品后再操作')
        return
      }

      const llmFields = await resolveImagePromptSubmitFields()
      const submitted = await userStoryboardGenerateImagePrompt({
        projectId: saveCtx.projectId,
        episodeId: saveCtx.episodeId,
        storyboardIds: [storyboardId],
        ...llmFields,
        overwrite: true
      })

      const taskId = Number(submitted.taskId)
      if (!Number.isFinite(taskId) || taskId <= 0) {
        message.error('提交失败：未返回任务ID')
        return
      }

      ctx.store().setStoryboardImagePromptGenTask(storyboardId, { taskId, sceneIdx })
      ctx.activePromptFollowStoryboardIds.add(storyboardId)

      let taskOutcome = await awaitStoryboardPromptGenerateTask(taskId)
      if (taskOutcome.ok === false) {
        if ('deferred' in taskOutcome && taskOutcome.deferred) {
          keepPendingUi = true
          return
        }
        const errMsg = taskOutcome.errorMessage
        if (errMsg.includes('取消')) {
          message.warning(errMsg)
        } else {
          message.error(errMsg)
        }
        return
      }
      if (taskOutcome.ok && 'partial' in taskOutcome && taskOutcome.partial) {
        const partialWarning = taskOutcome.partialWarning
        message.warning(partialWarning)
        const shouldResume = await confirmTaskResumeWithBilling(taskId, {
          title: '部分生成失败',
          content: partialWarning,
          okText: '续生',
          cancelText: '暂不续生'
        })
        if (shouldResume) {
          taskOutcome = await resumeStoryboardPromptGenerateTask(taskId, 'image')
          if (taskOutcome.ok === false) {
            if ('deferred' in taskOutcome && taskOutcome.deferred) {
              keepPendingUi = true
              return
            }
            message.error(taskOutcome.errorMessage)
            return
          }
          if (taskOutcome.ok && 'partial' in taskOutcome && taskOutcome.partial) {
            message.warning(taskOutcome.partialWarning)
          }
        }
      }

      const prompt = await fetchStoryboardImagePromptAfterGenerate(storyboardId)
      if (!prompt) {
        message.warning('生成完成，但未获取到提示词内容')
        return
      }

      const applied = await applyStoryboardPromptFromApi(prompt, promptApplyTicket)
      if (!applied) {
        message.warning('检测到分镜切换或手动修改，已保留当前提示词')
        return
      }
      message.success('提示词生成成功')
    } catch (e: unknown) {
      message.error(storyboardBizErr(e))
    } finally {
      hideLoading()
      ctx.activePromptFollowStoryboardIds.delete(storyboardId)
      if (!keepPendingUi) {
        ctx.isGeneratingPrompt.set(false)
        ctx.promptGenerateTargetKey.set('')
        ctx.store().clearStoryboardImagePromptGenTask(storyboardId)
      }
    }
  }

  /** 刷新或重新打开弹窗后，恢复当前分镜的提示词生成 loading 与 SSE 追踪（按 storyboardId 隔离） */
  async function restoreStoryboardPromptGenerateIfNeeded(sceneIdx: number) {
    const storyboardId = Number(ctx.props().scenes[sceneIdx]?.storyboardId)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return
    if (ctx.activePromptFollowStoryboardIds.has(storyboardId)) return

    const persisted = ctx.store().getStoryboardImagePromptGenTask(storyboardId)
    const taskId = persisted?.taskId ?? null
    if (!taskId) return
    const promptApplyTicket = beginStoryboardPromptApply(storyboardId)

    const gen = advanceGenerationToken(ctx.resumeStoryboardPromptFollowGen)
    const ongoing = await isStoryboardImageTaskOngoing(taskId)
    if (gen !== ctx.resumeStoryboardPromptFollowGen.current) return

    if (!ongoing) {
      ctx.store().clearStoryboardImagePromptGenTask(storyboardId)
      return
    }

    ctx.promptGenerateTargetKey.set(
      buildModalTaskOverlayKey(ctx.overlayKeyParts(sceneIdx, -1, 'prompt-gen'))
    )
    ctx.isGeneratingPrompt.set(true)
    ctx.activePromptFollowStoryboardIds.add(storyboardId)
    let keepPendingUi = false

    try {
      let taskOutcome = await awaitStoryboardPromptGenerateTask(taskId)
      if (gen !== ctx.resumeStoryboardPromptFollowGen.current) {
        keepPendingUi = true
        return
      }
      if (taskOutcome.ok === false) {
        keepPendingUi = 'deferred' in taskOutcome && taskOutcome.deferred === true
        return
      }

      if (taskOutcome.ok && 'partial' in taskOutcome && taskOutcome.partial) {
        const partialWarning =
          'partialWarning' in taskOutcome && taskOutcome.partialWarning
            ? taskOutcome.partialWarning
            : '部分生成失败'
        const shouldResume = await confirmTaskResumeWithBilling(taskId, {
          title: '部分生成失败',
          content: partialWarning,
          okText: '续生',
          cancelText: '暂不续生'
        })
        if (shouldResume) {
          taskOutcome = await resumeStoryboardPromptGenerateTask(taskId, 'image')
          if (taskOutcome.ok === false && 'deferred' in taskOutcome && taskOutcome.deferred) {
            keepPendingUi = true
            return
          }
        }
      }

      if (taskOutcome.ok !== false && sceneIdx === ctx.currentSceneIndex.get()) {
        const prompt = await fetchStoryboardImagePromptAfterGenerate(storyboardId)
        if (prompt) await applyStoryboardPromptFromApi(prompt, promptApplyTicket)
      }
    } catch {
      /* ignore */
    } finally {
      ctx.activePromptFollowStoryboardIds.delete(storyboardId)
      if (
        !keepPendingUi &&
        ctx.promptGenerateTargetKey.get() ===
          buildModalTaskOverlayKey(ctx.overlayKeyParts(sceneIdx, -1, 'prompt-gen'))
      ) {
        ctx.promptGenerateTargetKey.set('')
      }
      if (!keepPendingUi) {
        ctx.isGeneratingPrompt.set(false)
        ctx.store().clearStoryboardImagePromptGenTask(storyboardId)
      }
    }
  }

  function copyStoryboardPrompt() {
    const plain = storyboardPromptPlainText()
    if (plain) {
      navigator.clipboard.writeText(plain)
      message.success('已复制')
    }
  }

  function copyCompositionDesc() {
    if (ctx.compositionDesc.get()) {
      navigator.clipboard.writeText(ctx.compositionDesc.get())
      message.success('已复制')
    }
  }

  function applyParamSettingsConfirm(payload: ParamSettingsConfirmPayload) {
    ctx.sceneImages.set(payload.sceneImages)
    ctx.characterImages.set(payload.characterImages)
    ctx.propImages.set(payload.propImages)
    ctx.otherImages.set(payload.otherImages)
    ctx.selectedComposition.set(payload.selectedComposition)
    ctx.selectedShotSize.set(payload.selectedShotSize)
    ctx.selectedCameraAngle.set(payload.selectedCameraAngle)
    ctx.selectedFocalLength.set(payload.selectedFocalLength)
    ctx.selectedColorTone.set(payload.selectedColorTone)
    ctx.selectedLighting.set(payload.selectedLighting)
    ctx.selectedTechnique.set(payload.selectedTechnique)
    ctx.compositionDesc.set(payload.compositionDesc)
    ctx.activeSettingKey.set(payload.activeSettingKey as SettingKey | null)

    const localAssets = collectStoryboardPromptAssets(
      payload.sceneImages,
      payload.characterImages,
      payload.propImages,
      payload.otherImages
    )
    ctx.resolvedPromptAssets.set(
      patchEmptyResolvedPromptAssets(ctx.resolvedPromptAssets.get(), localAssets)
    )
  }

  // 打开选择场景/角色/道具/其他弹窗
  function openSelectModal(type: SelectAssetModalType) {
    if (type === 'pose' || type === 'expression' || type === 'effect' || type === 'draft') {
      ctx.materialImportAppendToStoryPrompt.set(false)
      ctx.materialLibraryCategoryKey.set(type)
      ctx.showMaterialFromLibraryModal.set(true)
      return
    }
    if (type === 'other') {
      ctx.materialImportAppendToStoryPrompt.set(true)
      ctx.materialLibraryCategoryKey.set('misc')
      ctx.showMaterialFromLibraryModal.set(true)
      return
    }
    ctx.selectAssetModalType.set(type)
    ctx.selectAssetModalOpen.set(true)
  }

  function handleMaterialLibraryOtherImport(assets: any[]) {
    if (!assets?.length) return
    ctx.materialImportAppendToStoryPrompt.set(false)
    const list = assets.map((item) => ({
      ...item,
      url: item.url || item.thumbnail,
      thumbnail: item.thumbnail || item.url,
      title: item.title || item.name || '参考图',
      id: item.id || `img-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
    }))
    if (ctx.storyboardGeneratePanelRef.current?.isParamSettingsOpen?.()) {
      const type =
        ctx.materialLibraryCategoryKey.get() === 'misc'
          ? 'other'
          : ctx.materialLibraryCategoryKey.get()
      ctx.storyboardGeneratePanelRef.current.applyParamDraftAssets(type as any, list)
      message.success(`已添加 ${list.length} 项`)
      ctx.showMaterialFromLibraryModal.set(false)
      return
    }
    ctx.otherImages.set([...ctx.otherImages.get(), ...list])
    message.success(`已添加 ${list.length} 项`)
    ctx.showMaterialFromLibraryModal.set(false)
  }

  // 选择弹窗确认：将选中的图片写入对应列表
  function onSelectAssetConfirm(items: any[]) {
    if (!items?.length) return
    const list = items.map((item) => ({
      ...item,
      id: item.id || `img-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
    }))
    if (ctx.storyboardGeneratePanelRef.current?.isParamSettingsOpen?.()) {
      ctx.storyboardGeneratePanelRef.current.applyParamDraftAssets(
        ctx.selectAssetModalType.get(),
        list
      )
      message.success(`已添加 ${list.length} 项`)
      return
    }
    if (ctx.selectAssetModalType.get() === 'scene') {
      ctx.sceneImages.set([...ctx.sceneImages.get(), ...list])
    } else if (ctx.selectAssetModalType.get() === 'character') {
      ctx.characterImages.set([...ctx.characterImages.get(), ...list])
    } else if (ctx.selectAssetModalType.get() === 'prop') {
      ctx.propImages.set([...ctx.propImages.get(), ...list])
    } else {
      ctx.otherImages.set([...ctx.otherImages.get(), ...list])
    }
    message.success(`已添加 ${list.length} 项`)
  }

  // 预览单张资产图
  function previewAssetImage(img: any) {
    const url = img?.url || img?.thumbnail
    if (!url) return
    openImagePreviewModal({
      url,
      title: img?.title || img?.name || '预览'
    })
  }

  // 从「其他」列表中移除一项
  function removeOtherImage(index: number) {
    ctx.otherImages.set(ctx.otherImages.get().filter((_, i) => i !== index))
  }

  /** 描述框删除 @图片 后，按上方四类素材条的扁平顺序移除对应图片。 */
  function removeStoryboardAssetReference(index: number) {
    const buckets = [ctx.sceneImages, ctx.characterImages, ctx.propImages, ctx.otherImages]
    let localIndex = index
    for (const bucket of buckets) {
      const items = bucket.get()
      for (let itemIndex = 0; itemIndex < items.length; itemIndex += 1) {
        const target = items[itemIndex]
        // StoryboardGeneratePanel 的引用顺序只统计可展示图片，这里必须保持同一索引口径。
        if (!target?.url && !target?.thumbnail) continue
        if (localIndex > 0) {
          localIndex -= 1
          continue
        }
        bucket.set(items.filter((_, currentIndex) => currentIndex !== itemIndex))
        ctx.resolvedPromptAssets.set(
          pruneResolvedPromptAssetsForRemovedImage(ctx.resolvedPromptAssets.get(), target)
        )
        return
      }
    }
  }

  function openStoryboardScriptEditor() {
    ctx.scriptEditorKey.set(ctx.scriptEditorKey.get() + 1)
    ctx.showStoryboardScriptModal.set(true)
  }

  function handleSaveScriptInImageModal(payload: { title: string; content: string }) {
    const content = payload?.content ?? ''
    const title = payload?.title ?? ''
    ctx.emitUpdate(ctx.currentSceneIndex.get(), {
      scriptContent: content,
      ...(title.trim() ? { title } : {})
    })
    ctx.showStoryboardScriptModal.set(false)
    message.success('分镜脚本已保存')
  }

  function handleScriptTitleInImageModal(title: string) {
    const t = title?.trim()
    if (!t) return
    ctx.emitUpdate(ctx.currentSceneIndex.get(), { title: t })
  }

  return {
    storyboardPromptAssets,
    storyboardPromptParamGroups,
    storyboardPromptPlainText,
    showGeneratingPromptForScene,
    ensurePromptDictLoaded,
    applyParamSelectionsFromPlain,
    applyStoryboardPromptFromApi,
    beginStoryboardPromptApply,
    handleStoryboardPromptEditorChange,
    storyboardBizErr,
    fetchStoryboardImagePrompt,
    fetchStoryboardImagePromptAfterGenerate,
    resolveImagePromptSubmitFields,
    loadCurrentStoryboardPrompt,
    handleGeneratePrompt,
    restoreStoryboardPromptGenerateIfNeeded,
    copyStoryboardPrompt,
    copyCompositionDesc,
    applyParamSettingsConfirm,
    openSelectModal,
    handleMaterialLibraryOtherImport,
    onSelectAssetConfirm,
    previewAssetImage,
    removeOtherImage,
    removeStoryboardAssetReference,
    openStoryboardScriptEditor,
    handleSaveScriptInImageModal,
    handleScriptTitleInImageModal
  }
}
