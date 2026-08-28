'use client'

import type * as React from 'react'
import { createScpDerivedActionOps } from './scpDerivedActionOps'
import { createScpDerivedViewOps } from './scpDerivedViewOps'
import type { PendingFormCardItem, ScpCtx, TabKey } from './types'

export const tabs = [
  { key: 'scene' as const, label: '场景' },
  { key: 'character' as const, label: '角色' },
  { key: 'prop' as const, label: '道具' }
]

export interface ScpDerivedApi {
  isManualScene: (index: number) => boolean
  isManualCharacter: (index: number) => boolean
  isManualProp: (index: number) => boolean
  canAutoGenerateCharacterFormImage: (characterIndex: number, formIndex: number) => boolean
  canAutoGeneratePropFormImage: (propIndex: number, formIndex: number) => boolean
  isCharacterFormEditImageDisabled: (characterIndex: number, formIndex: number) => boolean
  isPropFormEditImageDisabled: (propIndex: number, formIndex: number) => boolean
  canAutoGenerateSceneImage: (sceneIndex: number) => boolean
  resolveSingleExtractTabLock: (
    stages?: { scene: boolean; character: boolean; prop: boolean } | null
  ) => TabKey | null
  shouldFollowExtractStageForActiveTab: () => boolean
  hasStory: () => boolean
  extractingStageLabel: () => string
  extractingLiveTitle: () => string
  isExtracting: () => boolean
  extractingProgressText: () => string
  extractingStages: () => { scene: boolean; character: boolean; prop: boolean }
  emptyAssetTitle: () => string
  emptyAssetAddLabel: () => string
  emptyExtractTips: () => string
  topbarAddDisabled: () => boolean
  activeTabPendingFormCards: () => PendingFormCardItem[]
  showBatchGenerateTopbarBtn: () => boolean
  batchFormGenerateMenuLabel: () => string
  batchImageGenerateMenuLabel: () => string
  batchFormGenerateAlreadyGenerated: () => boolean
  batchFormGenerateBusyDisabled: () => boolean
  batchFormGenerateMenuDisabled: () => boolean
  batchImageGenerateMenuDisabled: () => boolean
  batchDeleteMenuDisabled: () => boolean
  batchFormGenerateDisabledTooltip: () => string
  batchGenerateTopbarLoading: () => boolean
  batchCardGenerateMenuDisabled: () => boolean
  autoExtractEmptyButtonLabel: () => string
  manualScenesList: () => Array<{ name: string; index: number }>
  manualCharactersList: () => Array<{ name: string; index: number }>
  manualPropsList: () => Array<{ name: string; index: number }>
  pendingSceneFormCards: () => PendingFormCardItem[]
  pendingCharacterFormCards: () => PendingFormCardItem[]
  pendingPropFormCards: () => PendingFormCardItem[]
  visibleManualScenesList: () => Array<{ name: string; index: number }>
  visibleManualCharactersList: () => Array<{ name: string; index: number }>
  visibleManualPropsList: () => Array<{ name: string; index: number }>
  isGeneratingScene: () => boolean
  isGeneratingCharacterForm: () => boolean
  isGeneratingPropForm: () => boolean
  isAnyStep3VisualGenerating: () => boolean
  showAssetBootstrapMask: () => boolean
  showActiveTabAssetLoading: () => boolean
  activeTabAssetLoadingText: () => string
  assetLoadingMaskText: () => string
  batchGenerateDefaultModelCode: () => string
  batchGenerateItems: () => Array<{
    id: string | number
    name: string
    images: any[]
    hasSetting?: boolean
    settingCardReady?: boolean
    selectable?: boolean
    disabledTooltip?: string
  }>
  currentSceneName: () => string
  currentCharacterName: () => string
  currentPropName: () => string
  isTabLoading: (key: TabKey) => boolean
  onScriptChangeGoExtract: () => void
  onScriptChangeDismissBanner: () => void
  blurScpAutoExtractButton: (event: React.MouseEvent) => void
  onClickAutoExtract: (scope: TabKey, event: React.MouseEvent) => void
  handleAutoExtract: (scope: TabKey) => void
  handleEmptyAssetAddClick: () => void
  handleBatchFormGenerateClick: () => void
  handleBatchImageGenerateClick: () => void
  handleBatchCardGenerateClick: () => void
  handleBatchDeleteClick: () => void
  pendingFormCardEditKey: (card: { assetId: number; assetType: TabKey }) => string
  pendingFormCardLocalIndex: (card: { assetId: number; assetType: TabKey }) => number
  pendingFormCardPrefix: (card: { assetId: number; assetType: TabKey }) => string
  pendingFormCardEditableSuffix: (card: PendingFormCardItem) => string
}

export function useScpDerived(ctx: ScpCtx): ScpDerivedApi {
  const view = createScpDerivedViewOps(ctx)
  return { ...view, ...createScpDerivedActionOps(ctx, view) }
}
