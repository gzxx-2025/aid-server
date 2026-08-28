'use client'

import { message } from 'antd'
import { submitFormImageGenerateCardBatchTask } from '~/hooks/useFormImageGenerateCardTask'
import { routePathToCreationStep } from '~/utils/createFlowRoutes'
import { isFormCardImageTaskType,parseImageIdsFromTaskInputSnapshot } from '~/utils/formImageAutoUse'
import { CHARACTER_CARD_SCENE_CODE,resolveProjectGenImageSubmitFields } from '~/utils/projectGenConfig'
import { isSettingCardBaseImage } from '~/utils/settingCardBaseImage'
import { isOngoingUserTaskStatus } from './scpTaskUtils'
import type { ScpCtx } from './types'

export interface ScpSettingCardApi {
  isSettingCardBatchBusy: () => boolean
  markSettingCardGenBusy: (imageIds: number[]) => void
  clearSettingCardGenBusyForImageIds: (imageIds: number[]) => void
  findCharacterSlotKeyByRpsImageId: (imageId: number) => string | null
  collectRpsImageIdsForCharacterSlotKey: (slotKey: string) => number[]
  /** 设定卡批量生成进行中：白底主图已存在，不能按「槽位已有图」清掉 generating */
  isCharacterSlotSettingCardGenerating: (slotKey: string) => boolean
  applySettingCardGeneratingFromImageIds: (imageIds: number[]) => void
  /** 角色列表就绪后补恢复设定卡 generating（仅用 list 行 inputSnapshot，不打 detail） */
  reconcileSettingCardGeneratingUiForOngoingTasks: () => Promise<void>
  applyRpsImageIdToCharacterSettingCardGeneratingSlots: (imageId: number) => boolean
  resolveCharacterSettingCardGeneratingToIdle: (imageIds: number[]) => void
  characterFormGeneratingLabel: (slotKey: string) => string
  /** 批量设定卡：平台生成（含弹窗生图 ai_manual）或上传角色图 */
  isCharacterWhiteBaseImageForSettingCard: (img: unknown) => boolean
  collectCharacterWhiteBaseImageIdsForBatch: () => { imageIds: number[]; skippedCount: number }
  collectCharacterWhiteBaseImageIds: () => number[]
  collectCharacterWhiteBaseImageIdsForSelectedCharacters: (
    selectedCharacterIndexes: number[]
  ) => { imageIds: number[]; skippedCount: number }
  runBatchSettingCardGenerateForImageIds: (
    imageIds: number[],
    options?: { agent?: string; model?: string; resolution?: string }
  ) => Promise<void>
  characterHasWhiteBaseImageForSettingCard: (characterIndex: number) => boolean
  findFirstWhiteBaseImageForCharacter: (characterIndex: number) => any | null
}

export function useScpSettingCard(ctx: ScpCtx): ScpSettingCardApi {
  function isSettingCardBatchBusy(): boolean {
    return Object.values(ctx.settingCardGenBusyByImageId.get()).some(Boolean)
  }

  function markSettingCardGenBusy(imageIds: number[]) {
    const next = { ...ctx.settingCardGenBusyByImageId.get() }
    for (const id of imageIds) {
      if (Number.isFinite(id) && id > 0) next[id] = true
    }
    ctx.settingCardGenBusyByImageId.set(next)
  }

  function clearSettingCardGenBusyForImageIds(imageIds: number[]) {
    if (!imageIds.length) return
    const next = { ...ctx.settingCardGenBusyByImageId.get() }
    let changed = false
    for (const id of imageIds) {
      if (next[id]) {
        delete next[id]
        changed = true
      }
    }
    if (changed) ctx.settingCardGenBusyByImageId.set(next)
  }

  function findCharacterSlotKeyByRpsImageId(imageId: number): string | null {
    const id = Number(imageId)
    if (!Number.isFinite(id) || id <= 0) return null
    for (const [slotKey, imgs] of Object.entries(ctx.characterFormImages.get())) {
      if (!Array.isArray(imgs)) continue
      for (const img of imgs) {
        const rid = Number(
          (img as { rpsImageId?: number; id?: number })?.rpsImageId ?? (img as { id?: number })?.id
        )
        if (rid === id) return slotKey
      }
    }
    return null
  }

  function collectRpsImageIdsForCharacterSlotKey(slotKey: string): number[] {
    const imgs = ctx.characterFormImages.get()[slotKey] ?? []
    if (!Array.isArray(imgs)) return []
    return imgs
      .map((img) =>
        Number(
          (img as { rpsImageId?: number; id?: number })?.rpsImageId ?? (img as { id?: number })?.id
        )
      )
      .filter((n) => Number.isFinite(n) && n > 0)
  }

  /** 设定卡批量生成进行中：白底主图已存在，不能按「槽位已有图」清掉 generating */
  function isCharacterSlotSettingCardGenerating(slotKey: string): boolean {
    return collectRpsImageIdsForCharacterSlotKey(slotKey).some(
      (id) => !!ctx.settingCardGenBusyByImageId.get()[id]
    )
  }

  function applySettingCardGeneratingFromImageIds(imageIds: number[]) {
    let any = false
    for (const imageId of imageIds) {
      if (!Number.isFinite(imageId) || imageId <= 0) continue
      markSettingCardGenBusy([imageId])
      if (applyRpsImageIdToCharacterSettingCardGeneratingSlots(imageId)) any = true
    }
    if (any) ctx.store().refreshStep3VisualGeneratingFlag()
  }

  /** 角色列表就绪后补恢复设定卡 generating（仅用 list 行 inputSnapshot，不打 detail） */
  async function reconcileSettingCardGeneratingUiForOngoingTasks() {
    if (routePathToCreationStep(ctx.route().path) !== 'scene-character') return
    if (!ctx.ongoingTasks.get().length) {
      await ctx.reloadOngoingTasks()
    }
    for (const t of ctx.ongoingTasks.get()) {
      if (!t || !isFormCardImageTaskType(t.taskType) || !isOngoingUserTaskStatus(t.status)) continue
      const imageIds = parseImageIdsFromTaskInputSnapshot(t.inputSnapshot)
      if (imageIds.length) applySettingCardGeneratingFromImageIds(imageIds)
    }
  }

  function applyRpsImageIdToCharacterSettingCardGeneratingSlots(imageId: number): boolean {
    const slotKey = findCharacterSlotKeyByRpsImageId(imageId)
    if (!slotKey) return false
    ctx.characterFormGenerationStatus.set({
      ...ctx.characterFormGenerationStatus.get(),
      [slotKey]: 'generating'
    })
    ctx.store().setCharacterFormGenerationStatus(slotKey, 'generating')
    return true
  }

  function resolveCharacterSettingCardGeneratingToIdle(imageIds: number[]) {
    let changed = false
    for (const imageId of imageIds) {
      const slotKey = findCharacterSlotKeyByRpsImageId(imageId)
      if (!slotKey) continue
      if (ctx.characterFormGenerationStatus.get()[slotKey] === 'generating') {
        ctx.characterFormGenerationStatus.set({
          ...ctx.characterFormGenerationStatus.get(),
          [slotKey]: 'idle'
        })
        ctx.store().setCharacterFormGenerationStatus(slotKey, 'idle')
        changed = true
      }
    }
    if (changed) ctx.store().refreshStep3VisualGeneratingFlag()
  }

  function characterFormGeneratingLabel(slotKey: string): string {
    const ids = collectRpsImageIdsForCharacterSlotKey(slotKey)
    if (ids.some((id) => ctx.settingCardGenBusyByImageId.get()[id])) {
      return '正在生成角色设定卡…'
    }
    return '正在生成角色形态图…'
  }

  /** 批量设定卡：平台生成（含弹窗生图 ai_manual）或上传角色图 */
  function isCharacterWhiteBaseImageForSettingCard(img: unknown): boolean {
    return isSettingCardBaseImage(img)
  }

  function collectCharacterWhiteBaseImageIdsForBatch(): { imageIds: number[]; skippedCount: number } {
    const imageIds: number[] = []
    const seen = new Set<number>()
    let skippedCount = 0

    for (const [ciKey, forms] of Object.entries(ctx.characterForms.get())) {
      const ci = Number(ciKey)
      if (!Number.isFinite(ci)) continue
      for (let fi = 0; fi < (forms?.length ?? 0); fi++) {
        const slotKey = `${ci}-${fi}`
        const imgs = ctx.characterFormImages.get()[slotKey]
        if (!Array.isArray(imgs) || !imgs.length) {
          skippedCount++
          continue
        }
        const whiteBase = imgs.find((img) => isCharacterWhiteBaseImageForSettingCard(img))
        if (!whiteBase) {
          skippedCount++
          continue
        }
        const id = Number((whiteBase as { rpsImageId?: number }).rpsImageId)
        if (!Number.isFinite(id) || id <= 0 || seen.has(id)) {
          skippedCount++
          continue
        }
        if (ctx.settingCardGenBusyByImageId.get()[id]) continue
        seen.add(id)
        imageIds.push(id)
      }
    }
    return { imageIds, skippedCount }
  }

  function collectCharacterWhiteBaseImageIds(): number[] {
    return collectCharacterWhiteBaseImageIdsForBatch().imageIds
  }

  function collectCharacterWhiteBaseImageIdsForSelectedCharacters(
    selectedCharacterIndexes: number[]
  ): { imageIds: number[]; skippedCount: number } {
    const imageIds: number[] = []
    const seen = new Set<number>()
    let skippedCount = 0
    const selectedSet = new Set(selectedCharacterIndexes)

    for (const [ciKey, forms] of Object.entries(ctx.characterForms.get())) {
      const ci = Number(ciKey)
      if (!Number.isFinite(ci) || !selectedSet.has(ci)) continue
      for (let fi = 0; fi < (forms?.length ?? 0); fi++) {
        const slotKey = `${ci}-${fi}`
        const imgs = ctx.characterFormImages.get()[slotKey]
        if (!Array.isArray(imgs) || !imgs.length) {
          skippedCount++
          continue
        }
        const whiteBase = imgs.find((img) => isCharacterWhiteBaseImageForSettingCard(img))
        if (!whiteBase) {
          skippedCount++
          continue
        }
        const id = Number((whiteBase as { rpsImageId?: number }).rpsImageId)
        if (!Number.isFinite(id) || id <= 0 || seen.has(id)) {
          skippedCount++
          continue
        }
        if (ctx.settingCardGenBusyByImageId.get()[id]) continue
        seen.add(id)
        imageIds.push(id)
      }
    }
    return { imageIds, skippedCount }
  }

  async function runBatchSettingCardGenerateForImageIds(
    imageIds: number[],
    options?: {
      agent?: string
      model?: string
      resolution?: string
    }
  ) {
    if (!imageIds.length) {
      message.warning('所选角色下暂无可生成设定卡的角色图，请先生成或上传角色图')
      return
    }
    ctx.batchCardGenerateSubmitting.set(true)
    markSettingCardGenBusy(imageIds)
    for (const imageId of imageIds) {
      applyRpsImageIdToCharacterSettingCardGeneratingSlots(imageId)
    }
    ctx.store().setExtractingTaskProgress({
      percent: 0,
      stepTitle: '批量生成角色设定卡',
      message: ''
    })
    try {
      const projectId = ctx.store().currentProjectId
      const genFields = await resolveProjectGenImageSubmitFields(projectId, CHARACTER_CARD_SCENE_CODE, {
        agentCode: options?.agent,
        modelCode: options?.model,
        resolution: options?.resolution
      })
      const agentCode = genFields.agentCode
      if (!agentCode) {
        clearSettingCardGenBusyForImageIds(imageIds)
        resolveCharacterSettingCardGeneratingToIdle(imageIds)
        ctx.clearStep3TabTaskProgress('character')
        message.error('请先在「生成配置」中为「角色设定卡」配置智能体')
        return
      }
      const submit = await submitFormImageGenerateCardBatchTask({
        imageIds,
        projectId,
        agentCode,
        modelCode: genFields.modelCode,
        resolution: genFields.resolution,
        aspectRatio: genFields.aspectRatio
      })
      if (submit.ok === false) {
        clearSettingCardGenBusyForImageIds(imageIds)
        resolveCharacterSettingCardGeneratingToIdle(imageIds)
        ctx.clearStep3TabTaskProgress('character')
        message.error(submit.errorMessage)
        return
      }
      message.success(`已提交 ${imageIds.length} 张角色设定卡批量生成任务`)
      ctx.noteStep3TaskSubmitted()
      void ctx.startTrackTask({
        taskId: submit.taskId,
        taskType: 'form_card_image_batch',
        tab: 'character'
      })
    } catch (e: unknown) {
      clearSettingCardGenBusyForImageIds(imageIds)
      resolveCharacterSettingCardGeneratingToIdle(imageIds)
      ctx.clearStep3TabTaskProgress('character')
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '批量设定卡生成失败')
    } finally {
      ctx.batchCardGenerateSubmitting.set(false)
    }
  }

  function characterHasWhiteBaseImageForSettingCard(characterIndex: number): boolean {
    const forms = ctx.characterForms.get()[characterIndex] ?? []
    for (let fi = 0; fi < forms.length; fi++) {
      const slotKey = `${characterIndex}-${fi}`
      const imgs = ctx.characterFormImages.get()[slotKey] ?? []
      if (imgs.some((img) => isCharacterWhiteBaseImageForSettingCard(img))) return true
    }
    return false
  }

  function findFirstWhiteBaseImageForCharacter(characterIndex: number) {
    const forms = ctx.characterForms.get()[characterIndex] ?? []
    for (let fi = 0; fi < forms.length; fi++) {
      const slotKey = `${characterIndex}-${fi}`
      const imgs = ctx.characterFormImages.get()[slotKey] ?? []
      const whiteBase = imgs.find((img) => isCharacterWhiteBaseImageForSettingCard(img))
      if (whiteBase) return whiteBase
    }
    return null
  }

  return {
    isSettingCardBatchBusy,
    markSettingCardGenBusy,
    clearSettingCardGenBusyForImageIds,
    findCharacterSlotKeyByRpsImageId,
    collectRpsImageIdsForCharacterSlotKey,
    isCharacterSlotSettingCardGenerating,
    applySettingCardGeneratingFromImageIds,
    reconcileSettingCardGeneratingUiForOngoingTasks,
    applyRpsImageIdToCharacterSettingCardGeneratingSlots,
    resolveCharacterSettingCardGeneratingToIdle,
    characterFormGeneratingLabel,
    isCharacterWhiteBaseImageForSettingCard,
    collectCharacterWhiteBaseImageIdsForBatch,
    collectCharacterWhiteBaseImageIds,
    collectCharacterWhiteBaseImageIdsForSelectedCharacters,
    runBatchSettingCardGenerateForImageIds,
    characterHasWhiteBaseImageForSettingCard,
    findFirstWhiteBaseImageForCharacter
  }
}
