'use client'

import { message,Modal } from 'antd'
import {
userAssetExtractFormGenerate,
userAssetExtractFormGenerateImage,
userAssetRpsDelete,
userAssetRpsFormImageList
} from '~/utils/businessApi'
import {
registerStep3FormImageTask,
waitForStep3FormImageTaskDone
} from '~/utils/step3FormImageTaskRegistry'
import {
extractFormGenerateImageSubmitTaskId,
extractFormGenerateTextSubmitTaskId,
isBenignStep3TaskAbortError,
parseTaskId
} from './scpTaskUtils'
import type { PendingFormCardItem,ScpCtx,TabKey } from './types'

export function createScpFormGenerateOps(ctx: ScpCtx) {
  async function runFormImageGenerate(payload: {
    formId: number
    formName: string
    tab: TabKey
  }): Promise<number | null> {
    const beforeIds = new Set<number>()
    try {
      const beforeList = await userAssetRpsFormImageList({ formId: payload.formId })
      for (const row of beforeList) {
        if (row?.id != null && Number.isFinite(Number(row.id))) beforeIds.add(Number(row.id))
      }
    } catch {
      // 忽略前置查询异常，避免阻断生图主流程
    }

    const imageFields = await ctx.resolveFormImageApiSubmitFields(payload.tab)
    const submit = await userAssetExtractFormGenerateImage({
      formIds: [payload.formId],
      agentCode: imageFields.agentCode,
      ...(imageFields.modelCode ? { modelCode: imageFields.modelCode } : {}),
      ...(imageFields.resolution ? { resolution: imageFields.resolution } : {}),
      ...(imageFields.aspectRatio ? { aspectRatio: imageFields.aspectRatio } : {})
    })
    const taskRow = submit.tasks?.find((t) => Number(t.formId) === Number(payload.formId))
    const taskId =
      extractFormGenerateImageSubmitTaskId(submit) ??
      parseTaskId(taskRow?.taskId) ??
      (submit.taskIds?.length === 1 ? parseTaskId(submit.taskIds[0]) : null)
    if (!taskId) throw new Error('形态图生成任务提交失败：未返回任务ID')

    registerStep3FormImageTask({
      taskId,
      tab: payload.tab,
      formIds: [payload.formId],
      taskType: 'form_image'
    })
    ctx.applyFormIdToStep3GeneratingSlots(payload.formId)
    ctx.noteStep3TaskSubmitted()

    const donePromise = waitForStep3FormImageTaskDone(taskId)
    void ctx.startTrackTask({
      taskId,
      taskType: 'form_image',
      tab: payload.tab,
      skipPreSseHydrate: true
    })
    const outcome = await donePromise
    if (!outcome.ok) throw new Error(outcome.errorMessage || '形态图生成失败，请稍后重试')

    try {
      const list = await userAssetRpsFormImageList({ formId: payload.formId })
      const normalized = (Array.isArray(list) ? list : [])
        .filter((x) => x?.id != null && Number.isFinite(Number(x.id)))
        .map((x) => ({ id: Number(x.id), isUse: Number(x?.isUse) === 1 }))
      const created = normalized.find((x) => !beforeIds.has(x.id))
      if (created) return created.id
      const inUse = normalized.find((x) => x.isUse)
      if (inUse) return inUse.id
      const last = normalized[normalized.length - 1]
      return last?.id ?? null
    } catch {
      return null
    }
  }

  async function runSingleFormGenerate(payload: {
    assetId: number
    tab: 'character' | 'prop'
    formKey: string
    formName: string
    /** 调用方已解析的形态 id（如手动添加角色/道具后） */
    formId?: number | null
  }) {
    const { tab, formKey, formName } = payload
    if (ctx.props().isExtracting) {
      message.warning('资产提取进行中，请提取完成后再生成形态图')
      return
    }
    const routeCtx = ctx.captureStep3RouteContext()
    const parts = formKey.split('-')
    const assetIdx = Number(parts[0])
    const formIdx = Number(parts[1])
    if (!Number.isFinite(assetIdx) || !Number.isFinite(formIdx)) {
      message.error('形态索引无效')
      return
    }
    let formId: number | null =
      payload.formId != null && Number.isFinite(Number(payload.formId))
        ? Number(payload.formId)
        : null
    if (formId == null) {
      formId = await ctx.resolveFormIdForAssetForm(tab, assetIdx, formIdx)
      if (!ctx.matchesStep3RouteContext(routeCtx)) return
    }
    if (formId == null) {
      message.warning('未找到该形态的 ID，请稍后重试或先点击「新增形态」保存后再生成')
      return
    }

    if (tab === 'character') {
      ctx.characterFormGenerationStatus.set({
        ...ctx.characterFormGenerationStatus.get(),
        [formKey]: 'generating'
      })
      ctx.store().setCharacterFormGenerationStatus(formKey, 'generating')
    } else {
      ctx.propFormGenerationStatus.set({ ...ctx.propFormGenerationStatus.get(), [formKey]: 'generating' })
      ctx.store().setPropFormGenerationStatus(formKey, 'generating')
    }
    try {
      const createdImageId = await runFormImageGenerate({ formId, formName, tab })
      if (!ctx.matchesStep3RouteContext(routeCtx)) {
        ctx.resolveAllStep3GeneratingStatusesIfNoOngoingTasks('success')
        if (tab === 'character') ctx.patchCharacterFormGenStatus(formKey, 'success', routeCtx)
        else ctx.patchPropFormGenStatus(formKey, 'success', routeCtx)
        message.success(`「${formName}」形态图生成成功`)
        return
      }
      await ctx.tryUseFormImage({ imageId: createdImageId, formId })
      if (!ctx.matchesStep3RouteContext(routeCtx)) {
        ctx.resolveAllStep3GeneratingStatusesIfNoOngoingTasks('success')
        if (tab === 'character') ctx.patchCharacterFormGenStatus(formKey, 'success', routeCtx)
        else ctx.patchPropFormGenStatus(formKey, 'success', routeCtx)
        message.success(`「${formName}」形态图生成成功`)
        return
      }
      await ctx.loadPersonalAssetsForTab(tab)
      ctx.sanitizeStep3SceneImagesState()
      ctx.reconcileStep3GeneratingWithLoadedImages()
      if (tab === 'character') {
        ctx.patchCharacterFormGenStatus(formKey, 'success', routeCtx)
      } else {
        ctx.patchPropFormGenStatus(formKey, 'success', routeCtx)
      }
      message.success(`「${formName}」形态图生成成功`)
    } catch (e: unknown) {
      if (!ctx.matchesStep3RouteContext(routeCtx)) {
        if (isBenignStep3TaskAbortError(e)) {
          ctx.store().resolveAllStep3GeneratingStatuses('idle')
        }
        return
      }
      if (isBenignStep3TaskAbortError(e)) {
        if (tab === 'character') {
          ctx.patchCharacterFormGenStatus(formKey, 'idle', routeCtx)
        } else {
          ctx.patchPropFormGenStatus(formKey, 'idle', routeCtx)
        }
      } else {
        if (tab === 'character') {
          ctx.patchCharacterFormGenStatus(formKey, 'failed', routeCtx)
        } else {
          ctx.patchPropFormGenStatus(formKey, 'failed', routeCtx)
        }
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || '形态图生成失败，请重试')
      }
    } finally {
      if (ctx.matchesStep3RouteContext(routeCtx)) {
        ctx.clearStep3ExtractingTaskProgressIfIdle()
      }
    }
  }

  async function runBatchPendingFormGenerate(selectedAssetIds?: number[]) {
    if (ctx.props().isExtracting) {
      message.warning('资产提取进行中，请提取完成后再生成形态')
      return
    }
    const tab = ctx.activeTab.get()
    const cards = ctx.activeTabPendingFormCards()
    if (!cards.length) {
      message.warning('暂无待生成形态的资产')
      return
    }
    const selectedSet = selectedAssetIds?.length ? new Set(selectedAssetIds) : null
    const assetIds = cards
      .map((c) => c.assetId)
      .filter(
        (id) =>
          Number.isFinite(id) &&
          id > 0 &&
          (!selectedSet || selectedSet.has(id)) &&
          !ctx.pendingFormGenBusy.get()[id]
      )
    if (!assetIds.length) {
      message.warning('资产形态正在生成中，请稍候')
      return
    }
    ctx.batchFormGenerateSubmitting.set(true)
    for (const aid of assetIds) {
      ctx.applyAssetIdToPendingFormTextGeneratingBusy(aid)
    }
    try {
      const textFields = await ctx.resolveFormTextSubmitFields(tab)
      const submitBody: { assetIds: number[]; agentCode: string; modelCode?: string } = {
        assetIds,
        agentCode: textFields.agentCode,
        ...(textFields.modelCode ? { modelCode: textFields.modelCode } : {})
      }
      const submit = await userAssetExtractFormGenerate(submitBody)
      const taskId = extractFormGenerateTextSubmitTaskId(submit)
      if (!taskId) throw new Error('批量形态生成任务提交失败：未返回任务ID')

      const typeLabel = tab === 'scene' ? '场景' : tab === 'character' ? '角色' : '道具'
      message.success(`已提交 ${assetIds.length} 个${typeLabel}的批量形态生成任务`)
      ctx.noteStep3TaskSubmitted()
      void ctx.startTrackTask({
        taskId,
        taskType: 'form_generate_batch',
        tab,
        assetIds
      })
    } catch (e: unknown) {
      ctx.resetPendingFormGenerateSlotsForAssetIds(assetIds, tab)
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '批量形态生成失败，请重试')
    } finally {
      ctx.batchFormGenerateSubmitting.set(false)
    }
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

  function handleDeletePendingFormCard(item: PendingFormCardItem) {
    Modal.confirm({
      title: '确认删除该资产？',
      content: `将删除「${item.title}」及其相关内容，且不可恢复。`,
      okText: '删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await userAssetRpsDelete({ id: item.assetId })
          ctx.store().removePendingExtractFormAsset(item.assetId, item.assetType)
          await ctx.loadPersonalAssetsForTab(item.assetType)
          message.success('已删除')
        } catch (e: unknown) {
          const err = e as { msg?: string; message?: string }
          message.error(err?.msg || err?.message || '删除失败')
        }
      }
    })
  }

  return {
    handleDeletePendingFormCard,
    runBatchPendingFormGenerate,
    runFormImageGenerate,
    runPendingExtractFormGenerate,
    runSingleFormGenerate,
  }
}
