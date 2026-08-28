'use client'

import { createScpGenerationStatusActions } from './scpGenerationStatusActions'
import { createScpGenerationStatusCore } from './scpGenerationStatusCore'
import type { FormGenStatus, ScpCtx, TabKey } from './types'

type Step3RouteContext = {
  scopeKey: string
  projectId: number | null
  episodeId: number | null
}

export interface ScpGenStatusApi {
  purgeStaleStep3FormImageGeneratingMarks: (coverFormIds: Set<number>) => void
  clearStep3ActiveFormImageGeneratingIds: () => void
  isFormIdUnderActiveFormImageGeneration: (formId: number) => boolean
  resolveAssetListReconcileGenStatus: (input: {
    hasImage: boolean
    underFormImageGen: boolean
    previousStatus: FormGenStatus | undefined
  }) => FormGenStatus
  clearStep3ExtractingTaskProgressIfIdle: () => void
  ensureStep3FormImageTaskRegistered: (payload: {
    taskId: number
    tab: TabKey
    taskType?: string | null
    formIds?: number[]
  }) => Promise<void>
  resolveFormIdForCharacterSlotKey: (slotKey: string) => number | null
  resolveFormIdForPropSlotKey: (slotKey: string) => number | null
  characterSlotHasActiveFormImageGeneration: (slotKey: string) => boolean
  propSlotHasActiveFormImageGeneration: (slotKey: string) => boolean
  sceneIndexHasActiveFormImageGeneration: (sceneIndex: number) => boolean
  collectFormIdsFromFormImageTaskDetail: (detail: {
    inputSnapshot?: string | null
    taskType?: string | null
  }) => number[]
  forceSettleTabGeneratingSlots: (tab: TabKey, options?: { force?: boolean }) => void
  tabHasStep3FormImageGenerating: (tab: TabKey) => boolean
  hasPersistedStep3GeneratingWork: () => boolean
  resolveFormIdGeneratingSlotAfterCancel: (
    formId: number,
    nextStatus?: FormGenStatus
  ) => void
  setStep3GeneratingSlotForFormId: (formId: number) => boolean
  reapplyFormImageGeneratingSlotsFromActiveIds: (tab?: TabKey) => void
  captureStep3RouteContext: () => Step3RouteContext
  matchesStep3RouteContext: (routeContext: Step3RouteContext) => boolean
  patchSceneGenStatus: (
    index: number,
    status: FormGenStatus,
    routeContext: Step3RouteContext
  ) => void
  patchCharacterFormGenStatus: (
    formKey: string,
    status: FormGenStatus,
    routeContext: Step3RouteContext
  ) => void
  patchPropFormGenStatus: (
    formKey: string,
    status: FormGenStatus,
    routeContext: Step3RouteContext
  ) => void
  resolveAllStep3GeneratingStatusesIfNoOngoingTasks: (target: FormGenStatus) => void
  resolveAllLocalStep3GeneratingTo: (status: FormGenStatus) => void
  sceneSlotHasLoadedImages: (sceneIndex: number) => boolean
  characterFormSlotHasLoadedImages: (formKey: string) => boolean
  propFormSlotHasLoadedImages: (formKey: string) => boolean
  reconcileStep3GeneratingWithLoadedImages: () => void
  markStep3SlotSuccessByFormId: (formId: number) => boolean
  applyFormIdToStep3GeneratingSlots: (formId: number) => boolean
  applyAssetIdToPendingFormTextGeneratingBusy: (assetId: number) => boolean
  clearStep3ImageGeneratingSlotsForFormTextAssetId: (assetId: number) => void
  clearPendingFormGenBusyForAssetIds: (assetIds: number[]) => void
  resetPendingFormGenerateSlotsForAssetIds: (assetIds: number[], tab: TabKey) => void
  hasOngoingStep3VisualWork: () => boolean
}

export function useScpGenStatus(ctx: ScpCtx): ScpGenStatusApi {
  const core = createScpGenerationStatusCore(ctx)
  return { ...core, ...createScpGenerationStatusActions(ctx, core) }
}
