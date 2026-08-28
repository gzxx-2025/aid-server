'use client'

import { message } from 'antd'
import {
  userAssetExtractFormGenerate,
  userAssetExtractFormGenerateImage,
  userAssetRpsDeleteBatchByIds,
} from '~/utils/businessApi'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
  isFormIdUnderActiveStep3FormImageTask,
  registerStep3FormImageTask
} from '~/utils/step3FormImageTaskRegistry'
import {
  extractFormGenerateImageSubmitTaskId,
  extractFormGenerateTextSubmitTaskId,
  isBenignStep3TaskAbortError
} from './scpTaskUtils'
import type { BillingQuoteRequest } from '~/types/business-api'
import type { PendingFormCardItem, ScpCtx, TabKey } from './types'
import { createScpFormGenerateOps } from './scpFormGenerateOps'

export interface ScpGenerateActionsApi {
  handleAutoGenerateScene: (index: number, preferredFormId?: number) => Promise<void>
  resolveSceneAutoGenerateBillingRequest: (
    index: number,
    preferredFormId?: number
  ) => Promise<BillingQuoteRequest | null>
  runFormImageGenerate: (payload: {
    formId: number
    formName: string
    tab: TabKey
  }) => Promise<number | null>
  runSingleFormGenerate: (payload: {
    assetId: number
    tab: 'character' | 'prop'
    formKey: string
    formName: string
    /** 调用方已解析的形态 id（如手动添加角色/道具后） */
    formId?: number | null
  }) => Promise<void>
  runBatchPendingFormGenerate: (selectedAssetIds?: number[]) => Promise<void>
  /**
   * 单个「生成形态」与批量共用同一条 startTrackTask 跟踪链路：
   * 相同的卡片 busy loading + step3TabTaskProgress 顶栏进度文案，终态由 finalize 统一收尾。
   */
  runPendingExtractFormGenerate: (item: PendingFormCardItem) => Promise<void>
  handleDeletePendingFormCard: (item: PendingFormCardItem) => void
  handleBatchDeleteForActiveTab: (selectedAssetIds: number[]) => Promise<void>
  /**
   * 批量弹窗打开时刷新：以 /api/user/asset/rps/list 为唯一数据源，
   * 覆盖本地 sceneImages / characterFormImages / propFormImages，避免外层取消主图后弹窗仍显示旧图。
   */
  refreshBatchGenerateAssetItems: () => Promise<void>
  /** 与外层列表同源：角色/道具取各形态「使用中」图，不用可能陈旧的 characterImages/propImages */
  collectInUseFormImagesForAsset: (kind: 'character' | 'prop', assetIndex: number) => any[]
  handleBatchGenerateConfirm: (payload: {
    type: 'scene' | 'character' | 'prop'
    mode: 'image' | 'setting-card' | 'form' | 'delete'
    agent: string
    model: string
    resolution: string
    selectedItemIds: Array<string | number>
  }) => Promise<void>
}

export function useScpGenerateActions(ctx: ScpCtx): ScpGenerateActionsApi {
  const {
    runFormImageGenerate,
    runSingleFormGenerate,
    runBatchPendingFormGenerate,
    handleDeletePendingFormCard
  } = createScpFormGenerateOps(ctx)

  async function resolveSceneAutoGenerateFormIds(
    index: number,
    assetId: number,
    preferredFormId?: number
  ): Promise<number[]> {
    const latestRow = await ctx.fetchRpsRowByAssetIdWithLocalFallback({
      tab: 'scene',
      assetId,
      sceneIndex: index
    })
    const allFormIds = (latestRow?.forms ?? [])
      .filter((form) => form?.id != null && Number.isFinite(Number(form.id)))
      .map((form) => Number(form.id))
    const preferred =
      preferredFormId != null && Number.isFinite(Number(preferredFormId))
        ? Number(preferredFormId)
        : null
    const formsNeedingImage = (latestRow?.forms ?? [])
      .filter((form) => form?.id != null && Number.isFinite(Number(form.id)))
      .filter((form) => !String(form.imageUrl ?? '').trim())
      .map((form) => Number(form.id))
    return preferred != null && allFormIds.includes(preferred)
      ? [preferred]
      : formsNeedingImage.length > 0
        ? formsNeedingImage
        : allFormIds.slice(0, 1)
  }

  async function resolveSceneAutoGenerateBillingRequest(
    index: number,
    preferredFormId?: number
  ): Promise<BillingQuoteRequest | null> {
    const assetId = Number(ctx.sceneAssetIds.get()[index])
    if (!Number.isFinite(assetId) || assetId <= 0) return null
    const formIds = await resolveSceneAutoGenerateFormIds(index, assetId, preferredFormId)
    if (!formIds.length) return null
    const fields = await ctx.resolveFormImageApiSubmitFields('scene')
    return {
      quoteType: 'FORM_IMAGE',
      payload: {
        formIds,
        agentCode: fields.agentCode,
        ...(fields.modelCode ? { modelCode: fields.modelCode } : {}),
        ...(fields.resolution ? { resolution: fields.resolution } : {}),
        ...(fields.aspectRatio ? { aspectRatio: fields.aspectRatio } : {})
      }
    }
  }

  const handleAutoGenerateScene = async (index: number, preferredFormId?: number) => {
    if (!ctx.canAutoGenerateSceneImage(index)) {
      message.info('手动添加的场景请使用「图片导入」上传配图')
      return
    }
    const sceneName = ctx.localValue.get().scenes[index]
    const aid = ctx.sceneAssetIds.get()[index]
    if (aid == null) {
      message.warning('缺少场景资产ID，请先完成智能提取')
      return
    }

    ctx.sceneGenerationStatus.set({ ...ctx.sceneGenerationStatus.get(), [index]: 'generating' })
    ctx.store().setSceneGenerationStatus(index, 'generating')
    await (async () => {
      const routeCtx = ctx.captureStep3RouteContext()
      try {
        const targetFormIds = await resolveSceneAutoGenerateFormIds(
          index,
          Number(aid),
          preferredFormId
        )
        if (!ctx.matchesStep3RouteContext(routeCtx)) return
        if (targetFormIds.length === 0) {
          throw new Error('未找到可生成的场景形态，请先完成形态生成')
        }
        for (const formId of targetFormIds) {
          ctx.applyFormIdToStep3GeneratingSlots(formId)
        }
        for (let i = 0; i < targetFormIds.length; i++) {
          const formId = targetFormIds[i]!
          const displayName =
            targetFormIds.length === 1
              ? sceneName
              : `${sceneName}（${i + 1}/${targetFormIds.length}）`
          const createdImageId = await runFormImageGenerate({
            formId,
            formName: displayName,
            tab: 'scene'
          })
          if (!ctx.matchesStep3RouteContext(routeCtx)) {
            ctx.finalizeSceneGenerateSuccessOffPage(index, sceneName, routeCtx)
            return
          }
          await ctx.tryUseFormImage({ imageId: createdImageId, formId })
          if (!ctx.matchesStep3RouteContext(routeCtx)) {
            ctx.finalizeSceneGenerateSuccessOffPage(index, sceneName, routeCtx)
            return
          }
        }
        if (ctx.matchesStep3RouteContext(routeCtx)) {
          await ctx.loadPersonalAssetsForTab('scene')
          ctx.sanitizeStep3SceneImagesState()
          ctx.reconcileStep3GeneratingWithLoadedImages()
        } else {
          ctx.finalizeSceneGenerateSuccessOffPage(index, sceneName, routeCtx)
          return
        }
        ctx.patchSceneGenStatus(index, 'success', routeCtx)
        message.success(`「${sceneName}」场景图生成成功`)
      } catch (e: unknown) {
        if (!ctx.matchesStep3RouteContext(routeCtx)) {
          if (isBenignStep3TaskAbortError(e)) {
            ctx.resolveAllStep3GeneratingStatusesIfNoOngoingTasks('idle')
          }
          return
        }
        if (isBenignStep3TaskAbortError(e)) {
          ctx.patchSceneGenStatus(index, 'idle', routeCtx)
        } else {
          ctx.patchSceneGenStatus(index, 'failed', routeCtx)
          const err = e as { msg?: string; message?: string }
          message.error(err?.msg || err?.message || `「${sceneName}」场景图生成失败，请重试`)
        }
      } finally {
        if (ctx.matchesStep3RouteContext(routeCtx)) {
          ctx.clearStep3ExtractingTaskProgressIfIdle()
        }
      }
    })()
  }

  /**
   * 单个「生成形态」与批量共用同一条 startTrackTask 跟踪链路：
   * 相同的卡片 busy loading + step3TabTaskProgress 顶栏进度文案，终态由 finalize 统一收尾。
   */
  async function runPendingExtractFormGenerate(item: PendingFormCardItem) {
    if (ctx.props().isExtracting) {
      message.warning('资产提取进行中，请提取完成后再生成形态')
      return
    }
    const { assetId, assetType: tab, title: formName } = item
    if (ctx.pendingFormGenBusy.get()[assetId]) {
      message.warning('该资产形态正在生成中，请稍候')
      return
    }
    ctx.applyAssetIdToPendingFormTextGeneratingBusy(assetId)
    try {
      const textFields = await ctx.resolveFormTextSubmitFields(tab)
      const submitBody: { assetIds: number[]; agentCode: string; modelCode?: string } = {
        assetIds: [assetId],
        agentCode: textFields.agentCode,
        ...(textFields.modelCode ? { modelCode: textFields.modelCode } : {})
      }
      const submit = await userAssetExtractFormGenerate(submitBody)
      const taskId = extractFormGenerateTextSubmitTaskId(submit)
      if (!taskId) throw new Error('形态生成任务提交失败：未返回任务ID')

      message.success(`已提交「${formName}」的形态生成任务`)
      ctx.noteStep3TaskSubmitted()
      void ctx.startTrackTask({
        taskId,
        taskType: 'form_generate_batch',
        tab,
        assetIds: [assetId]
      })
    } catch (e: unknown) {
      ctx.resetPendingFormGenerateSlotsForAssetIds([assetId], tab)
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '形态生成失败，请重试')
    }
  }

  async function handleBatchDeleteForActiveTab(selectedAssetIds: number[]) {
    const tab = ctx.activeTab.get()
    const typeLabel = tab === 'scene' ? '场景' : tab === 'character' ? '角色' : '道具'
    const assetIds = [...new Set(selectedAssetIds.filter((id) => Number.isFinite(id) && id > 0))]
    if (!assetIds.length || ctx.batchDeleteSubmitting.get()) return

    ctx.batchDeleteSubmitting.set(true)
    let errorNotified = false
    try {
      const { successIds, failCount, failures } = await userAssetRpsDeleteBatchByIds(assetIds)
      if (tab === 'scene') {
        for (const id of successIds) ctx.store().removeManualSceneAssetId(id)
      }
      const deleted = new Set(successIds)
      ctx.store().setPendingExtractFormAssets(
        ctx.store().pendingExtractFormAssets.filter(
          (x) => x.assetType !== tab || !deleted.has(Number(x.assetId))
        )
      )
      await ctx.loadPersonalAssetsForTab(tab)

      const failDetail = failures
        .map((f) => String(f?.reason || '').trim())
        .filter(Boolean)
        .slice(0, 2)
        .join('；')
      if (failCount > 0 && successIds.length > 0) {
        message.warning(
          failDetail
            ? `已删除 ${successIds.length} 个${typeLabel}，${failCount} 个失败：${failDetail}`
            : `已删除 ${successIds.length} 个${typeLabel}，${failCount} 个失败`
        )
        return
      }
      if (failCount > 0) {
        errorNotified = true
        const msg = failDetail || `批量删除${typeLabel}失败`
        message.error(msg)
        throw new Error(msg)
      }
      message.success(`已删除 ${successIds.length} 个${typeLabel}`)
    } catch (e: unknown) {
      if (!errorNotified) {
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || `批量删除${typeLabel}失败`)
      }
      throw e
    } finally {
      ctx.batchDeleteSubmitting.set(false)
    }
  }

  /**
   * 批量弹窗打开时刷新：以 /api/user/asset/rps/list 为唯一数据源，
   * 覆盖本地 sceneImages / characterFormImages / propFormImages，避免外层取消主图后弹窗仍显示旧图。
   */
  async function refreshBatchGenerateAssetItems() {
    const tab =
      ctx.batchGenerateMode.get() === 'setting-card' ? 'character' : ctx.batchGenerateType.get()
    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    if (!saveCtx) {
      throw new Error('缺少项目信息，请从「我的作品」打开作品后再操作')
    }
    const ok = await ctx.loadPersonalAssetsForTab(tab, {
      background: true,
      allowWhenExtracting: true
    })
    if (!ok) {
      throw new Error('刷新资产列表失败')
    }
  }

  /** 与外层列表同源：角色/道具取各形态「使用中」图，不用可能陈旧的 characterImages/propImages */
  function collectInUseFormImagesForAsset(
    kind: 'character' | 'prop',
    assetIndex: number
  ): any[] {
    const forms =
      kind === 'character'
        ? (ctx.characterForms.get()[assetIndex] ?? [])
        : (ctx.propForms.get()[assetIndex] ?? [])
    const formImagesMap =
      kind === 'character' ? ctx.characterFormImages.get() : ctx.propFormImages.get()
    const out: any[] = []
    for (let fi = 0; fi < Math.max(forms.length, 1); fi++) {
      const slot = formImagesMap[`${assetIndex}-${fi}`] ?? []
      for (const img of slot) {
        if (String(img?.url ?? '').trim()) out.push(img)
      }
    }
    return out
  }

  const handleBatchGenerateConfirm = async (payload: {
    type: 'scene' | 'character' | 'prop'
    mode: 'image' | 'setting-card' | 'form' | 'delete'
    agent: string
    model: string
    resolution: string
    selectedItemIds: Array<string | number>
  }) => {
    if (payload.mode === 'form') {
      const selectedAssetIds = payload.selectedItemIds
        .map(Number)
        .filter((id) => Number.isFinite(id) && id > 0)
      await runBatchPendingFormGenerate(selectedAssetIds)
      return
    }
    if (payload.mode === 'delete') {
      const selectedAssetIds = payload.selectedItemIds
        .map(Number)
        .filter((id) => Number.isFinite(id) && id > 0)
      await handleBatchDeleteForActiveTab(selectedAssetIds)
      return
    }
    if (payload.mode === 'setting-card') {
      const selectedCharacterIndexes: number[] = []
      for (const rawId of payload.selectedItemIds) {
        const s = String(rawId)
        if (!s.startsWith('character-')) continue
        const idx = Number(s.slice('character-'.length))
        if (!Number.isFinite(idx) || idx < 0 || ctx.isManualCharacter(idx)) continue
        selectedCharacterIndexes.push(idx)
      }
      if (!selectedCharacterIndexes.length) {
        message.warning('请至少选择一个角色')
        return
      }
      const { imageIds, skippedCount } = ctx.collectCharacterWhiteBaseImageIdsForSelectedCharacters(
        selectedCharacterIndexes
      )
      if (skippedCount > 0) {
        message.info(`已跳过 ${skippedCount} 个尚未生成白底图的角色形态`)
      }
      await ctx.runBatchSettingCardGenerateForImageIds(imageIds, {
        agent: payload.agent,
        model: payload.model,
        resolution: payload.resolution
      })
      return
    }

    if (ctx.props().isExtracting) {
      message.warning('资产提取进行中，请完成后再批量生图')
      return
    }
    const tab: TabKey = payload.type
    const prefix = tab === 'scene' ? 'scene' : tab === 'character' ? 'character' : 'prop'
    const idMap =
      tab === 'scene'
        ? ctx.sceneFormIdsByIndex.get()
        : tab === 'character'
          ? ctx.characterFormIdsByIndex.get()
          : ctx.propFormIdsByIndex.get()

    const formIds: number[] = []
    const seenForm = new Set<number>()
    const seenAssetIdx = new Set<number>()
    for (const rawId of payload.selectedItemIds) {
      const s = String(rawId)
      if (!s.startsWith(`${prefix}-`)) continue
      const idx = Number(s.slice(prefix.length + 1))
      if (!Number.isFinite(idx) || idx < 0) continue
      if (tab === 'scene' && ctx.isManualScene(idx)) continue
      if (tab === 'character' && ctx.isManualCharacter(idx)) continue
      if (tab === 'prop' && ctx.isManualProp(idx)) continue
      seenAssetIdx.add(idx)
      for (const id of idMap[idx] ?? []) {
        const n = Number(id)
        if (!Number.isFinite(n) || n <= 0) continue
        if (seenForm.has(n)) continue
        seenForm.add(n)
        formIds.push(n)
      }
    }

    if (!formIds.length) {
      message.warning('所选条目下没有可生成的形态，请先完成形态提取或添加形态')
      return
    }

    const modelFromModal = String(payload.model || '').trim()
    if (modelFromModal) {
      ctx.store().updateExtractImageModelCodes({ [tab]: modelFromModal })
    }

    const imageFields = await ctx.resolveFormImageApiSubmitFields(tab, {
      modelFromModal: payload.model,
      resolutionFromModal: payload.resolution,
      agentFromModal: payload.agent
    })

    for (const fid of formIds) {
      ctx.applyFormIdToStep3GeneratingSlots(fid)
    }

    try {
      const submit = await userAssetExtractFormGenerateImage({
        formIds,
        agentCode: imageFields.agentCode,
        ...(imageFields.modelCode ? { modelCode: imageFields.modelCode } : {}),
        ...(imageFields.resolution ? { resolution: imageFields.resolution } : {}),
        ...(imageFields.aspectRatio ? { aspectRatio: imageFields.aspectRatio } : {})
      })
      const taskId = extractFormGenerateImageSubmitTaskId(submit)
      if (!taskId) {
        for (const fid of formIds) ctx.resolveFormIdGeneratingSlotAfterCancel(fid)
        message.error('批量生图任务提交失败：未返回任务ID')
        return
      }
      registerStep3FormImageTask({
        taskId,
        tab,
        formIds,
        taskType: 'form_image_batch'
      })
      const typeLabel = tab === 'scene' ? '场景图' : tab === 'character' ? '角色图' : '道具图'
      message.success(`已提交 ${formIds.length} 个形态的${typeLabel}批量生成任务`)
      ctx.noteStep3TaskSubmitted()
      void ctx.startTrackTask({
        taskId,
        taskType: 'form_image_batch',
        tab
      })
    } catch (e: unknown) {
      for (const fid of formIds) {
        if (isFormIdUnderActiveStep3FormImageTask(fid)) continue
        ctx.resolveFormIdGeneratingSlotAfterCancel(fid)
      }
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '批量生图提交失败')
    }
  }

  return {
    handleAutoGenerateScene,
    resolveSceneAutoGenerateBillingRequest,
    runFormImageGenerate,
    runSingleFormGenerate,
    runBatchPendingFormGenerate,
    runPendingExtractFormGenerate,
    handleDeletePendingFormCard,
    handleBatchDeleteForActiveTab,
    refreshBatchGenerateAssetItems,
    collectInUseFormImagesForAsset,
    handleBatchGenerateConfirm
  }
}
