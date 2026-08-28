'use client'

import { createScpFinalizerBaseOps } from './scpFinalizerBaseOps'
import { createScpFinalizerRecoveryOps } from './scpFinalizerRecoveryOps'
import { createScpFormFinalizerOps } from './scpFormFinalizerOps'
import type { ScpCtx, TabKey, UserTaskSseOutcome } from './types'

export interface ScpFinalizersApi {
  removeTaskIdFromOngoingList: (taskId: number) => void
  notifyGlobalGenerateTaskListUpdated: (taskId?: number) => void
  settleStep3FormImageTaskTerminalUi: (
    tab: TabKey,
    options?: {
      completeData?: unknown
      taskInputSnapshot?: { inputSnapshot?: string | null; taskType?: string | null }
      taskId?: number
    }
  ) => void
  isStep3TerminalSseOutcome: (
    result: UserTaskSseOutcome | undefined
  ) => result is UserTaskSseOutcome
  settleStep3TaskFlowLoadingOnTerminalSse: (
    tab: TabKey,
    taskType: string | null | undefined,
    result: UserTaskSseOutcome
  ) => void
  shouldSkipStep3LoadingSettleForOngoingTask: (taskId: number) => Promise<boolean>
  collectFormIdsToUnmarkAfterFormImageTask: (
    tab: TabKey,
    options?: {
      completeData?: unknown
      taskInputSnapshot?: { inputSnapshot?: string | null; taskType?: string | null }
      taskId?: number
    }
  ) => number[]
  fetchFormImageTaskInputSnapshot: (
    taskId: number
  ) => Promise<{ inputSnapshot?: string | null; taskType?: string | null } | null>
  finalizeSceneGenerateSuccessOffPage: (
    index: number,
    sceneName: string,
    routeContext: { scopeKey: string; projectId: number | null; episodeId: number | null }
  ) => void
  finalizeStep3FormGenerateTaskOutcome: (
    tab: TabKey,
    taskId: number,
    completeData?: unknown
  ) => Promise<void>
  finalizeStep3FormGenerateTaskFailure: (
    tab: TabKey,
    taskId: number,
    errorMessage: string
  ) => Promise<void>
  finalizeStep3FormImageTaskOutcome: (
    tab: TabKey,
    options?: {
      partialFailMessages?: string[]
      completeData?: unknown
      taskType?: string | null
      taskId?: number
    }
  ) => Promise<void>
  finalizeStep3FormImageTaskFailure: (
    tab: TabKey,
    errorMessage: string,
    options?: { completeData?: unknown; taskId?: number; taskType?: string | null }
  ) => Promise<void>
  handleFormImageTaskSettledFromModal: (event: Event) => void
  settleSettingCardBatchLoadingState: (sourceImageIds?: number[]) => void
  handleFormCardBatchSettledEvent: (event: Event) => void
  claimFormImagesFromMatchingTerminalTasks: (generatingFormIds: Set<number>) => Promise<void>
  recoverStaleGeneratingAfterCompletedFormImageTasks: () => Promise<void>
  shouldDeferStep3TaskFailureForBenignDisconnect: (
    taskId: number,
    errorMessage: string
  ) => Promise<boolean>
  notifyStep3FormImageTaskDoneFromTrack: (payload: {
    taskId: number
    taskType?: string | null
    didFinalizeStep3Task: boolean
    res: UserTaskSseOutcome | undefined
    wasTabSwitchClose: boolean
  }) => void
}

export function useScpFinalizers(ctx: ScpCtx): ScpFinalizersApi {
  const base = createScpFinalizerBaseOps(ctx)
  const form = createScpFormFinalizerOps(ctx, base)
  const recovery = createScpFinalizerRecoveryOps(ctx, base, form)
  return { ...base, ...form, ...recovery }
}
