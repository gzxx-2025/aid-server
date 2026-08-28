'use client'

import type * as React from 'react'
import { message } from 'antd'
import {
  getCharacterName,
  getCharacterPrefix,
  getPropName,
  getPropPrefix,
  getSceneName,
  getScenePrefix
} from './scpRowUtils'
import type { PendingFormCardItem, ScpCtx, TabKey } from './types'
import type { createScpDerivedViewOps } from './scpDerivedViewOps'

type ScpDerivedViewOps = ReturnType<typeof createScpDerivedViewOps>

export function createScpDerivedActionOps(ctx: ScpCtx, view: ScpDerivedViewOps) {
  const currentSceneName = () => {
    const index = ctx.currentSceneIndex.get()
    return index >= 0 && index < ctx.localValue.get().scenes.length
      ? ctx.localValue.get().scenes[index]
      : ''
  }

  const currentCharacterName = () => {
    const index = ctx.currentCharacterIndex.get()
    return index >= 0 && index < ctx.localValue.get().characters.length
      ? ctx.localValue.get().characters[index]
      : ''
  }

  const currentPropName = () => {
    const index = ctx.currentPropIndex.get()
    return index >= 0 && index < ctx.localValue.get().props.length
      ? ctx.localValue.get().props[index]
      : ''
  }

  const isTabLoading = (key: TabKey) => {
    if (ctx.props().isExtracting && ctx.props().extractingStages?.[key]) return true
    if (Object.values(ctx.step3TaskIdToTab.get()).includes(key)) return true
    if (
      key === 'scene' &&
      (view.isGeneratingScene() || ctx.tabHasStep3FormImageGenerating('scene'))
    ) {
      return true
    }
    if (
      key === 'character' &&
      (view.isGeneratingCharacterForm() || ctx.tabHasStep3FormImageGenerating('character'))
    ) {
      return true
    }
    return (
      key === 'prop' &&
      (view.isGeneratingPropForm() || ctx.tabHasStep3FormImageGenerating('prop'))
    )
  }

  function onScriptChangeGoExtract() {
    ctx.createFlowShell()?.openContinueExtractModal()
  }

  function onScriptChangeDismissBanner() {
    ctx.createFlowShell()?.dismissScriptChangeLightBanner()
  }

  const blurScpAutoExtractButton = (event: React.MouseEvent) => {
    const button = (event.target as HTMLElement | null)?.closest?.(
      'button.ant-btn'
    ) as HTMLButtonElement | null
    if (!button || button.matches(':focus-visible')) return
    requestAnimationFrame(() => button.blur())
  }

  function handleAutoExtract(scope: TabKey) {
    if (!view.hasStory()) {
      message.warning('请先添加剧本故事')
      return
    }
    ctx.emitOpenExtractModal(scope)
  }

  function onClickAutoExtract(scope: TabKey, event: React.MouseEvent) {
    handleAutoExtract(scope)
    blurScpAutoExtractButton(event)
  }

  const handleEmptyAssetAddClick = () => {
    if (view.topbarAddDisabled()) return
    if (ctx.activeTab.get() === 'scene') ctx.addScene()
    else if (ctx.activeTab.get() === 'character') ctx.addCharacter()
    else ctx.addProp()
  }

  function handleBatchFormGenerateClick() {
    if (view.batchFormGenerateMenuDisabled()) return
    ctx.batchOpsDropdownOpen.set(false)
    ctx.batchGenerateMode.set('form')
    ctx.batchGenerateType.set(ctx.activeTab.get())
    ctx.showBatchGenerateModal.set(true)
  }

  function handleBatchImageGenerateClick() {
    if (view.batchImageGenerateMenuDisabled()) return
    ctx.batchOpsDropdownOpen.set(false)
    ctx.batchGenerateMode.set('image')
    ctx.batchGenerateType.set(ctx.activeTab.get())
    ctx.showBatchGenerateModal.set(true)
  }

  function handleBatchCardGenerateClick() {
    if (view.batchCardGenerateMenuDisabled() || ctx.batchCardGenerateSubmitting.get()) return
    ctx.batchOpsDropdownOpen.set(false)
    ctx.batchGenerateMode.set('setting-card')
    ctx.batchGenerateType.set('character')
    ctx.showBatchGenerateModal.set(true)
  }

  function handleBatchDeleteClick() {
    if (view.batchDeleteMenuDisabled() || ctx.batchDeleteSubmitting.get()) return
    ctx.batchOpsDropdownOpen.set(false)
    ctx.batchGenerateMode.set('delete')
    ctx.batchGenerateType.set(ctx.activeTab.get())
    ctx.showBatchGenerateModal.set(true)
  }

  function pendingFormCardEditKey(card: { assetId: number; assetType: TabKey }) {
    return `${card.assetType}-${card.assetId}`
  }

  function pendingFormCardLocalIndex(card: { assetId: number; assetType: TabKey }): number {
    if (card.assetType === 'scene') return ctx.findSceneIndexByAssetId(card.assetId)
    if (card.assetType === 'character') return ctx.findCharacterIndexByAssetId(card.assetId)
    return ctx.findPropIndexByAssetId(card.assetId)
  }

  function pendingFormCardPrefix(card: { assetId: number; assetType: TabKey }): string {
    const index = pendingFormCardLocalIndex(card)
    if (index < 0) return ''
    if (card.assetType === 'scene') return getScenePrefix(ctx.localValue.get().scenes[index])
    if (card.assetType === 'character') {
      return getCharacterPrefix(ctx.localValue.get().characters[index])
    }
    return getPropPrefix(ctx.localValue.get().props[index])
  }

  function pendingFormCardEditableSuffix(card: PendingFormCardItem): string {
    const index = pendingFormCardLocalIndex(card)
    if (card.assetType === 'scene') {
      return getSceneName(index >= 0 ? ctx.localValue.get().scenes[index] : card.title)
    }
    if (card.assetType === 'character') {
      return getCharacterName(index >= 0 ? ctx.localValue.get().characters[index] : card.title)
    }
    return getPropName(index >= 0 ? ctx.localValue.get().props[index] : card.title)
  }

  return {
    currentSceneName,
    currentCharacterName,
    currentPropName,
    isTabLoading,
    onScriptChangeGoExtract,
    onScriptChangeDismissBanner,
    blurScpAutoExtractButton,
    onClickAutoExtract,
    handleAutoExtract,
    handleEmptyAssetAddClick,
    handleBatchFormGenerateClick,
    handleBatchImageGenerateClick,
    handleBatchCardGenerateClick,
    handleBatchDeleteClick,
    pendingFormCardEditKey,
    pendingFormCardLocalIndex,
    pendingFormCardPrefix,
    pendingFormCardEditableSuffix
  }
}
