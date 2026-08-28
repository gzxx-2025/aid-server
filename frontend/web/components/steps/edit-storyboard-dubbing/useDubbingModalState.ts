'use client'

import type { DubbingPanel } from '~/types'
import type {
DubbingDraft,
DubbingGenItem,
DubbingModalState,
DubbingPendingPayload,
ServerVideoThumb
} from './types'
import { useMirrored } from './useMirrored'

/** 集中创建弹窗全部可变状态（对应原 Vue setup 中的 ref 声明区） */
export function useDubbingModalState(): DubbingModalState {
  const currentSceneIndex = useMirrored(0)
  const leftPanelLoading = useMirrored(false)
  const rightPanelLoading = useMirrored(false)

  const pendingDubbingByIndex = useMirrored<Record<number, boolean>>({})
  const pendingPayloadByIndex = useMirrored<Record<number, DubbingPendingPayload>>({})
  const preConfirmPanelByIndex = useMirrored<Record<number, DubbingPanel>>({})
  const confirmedDubbingThisSession = useMirrored<Set<number>>(() => new Set())

  const genHistoryByIndex = useMirrored<Record<number, DubbingGenItem[]>>({})
  const genLoadingByPanelKey = useMirrored<Record<string, boolean>>({})
  const selectedNavKeyByIndex = useMirrored<Record<number, string>>({})
  const generatingMetaByIndex = useMirrored<
    Record<number, { voice: string; emotion: string; timeLabel: string }>
  >({})
  const lipSyncProgressHintByIndex = useMirrored<Record<number, string>>({})

  const isSettingFinalDubbing = useMirrored(false)
  const isDeletingDubbingRecord = useMirrored(false)
  const serverVideoRecordsByIndex = useMirrored<Record<number, ServerVideoThumb[]>>({})

  const draftDialogue = useMirrored('')
  const draftEmotion = useMirrored('中性')
  const draftLipSync = useMirrored(false)
  const draftVoiceName = useMirrored('')
  const draftVoiceAvatarUrl = useMirrored('')
  const draftVoiceLibraryId = useMirrored(0)
  const draftVoiceModelId = useMirrored(0)
  const draftTimbreCode = useMirrored('')
  const draftVoiceProviderHint = useMirrored('')
  const draftByIndex = useMirrored<Record<number, DubbingDraft>>({})

  const voicePickerOpen = useMirrored(false)
  const emotionNameToCode = useMirrored<Map<string, string>>(() => new Map())
  const emotionLabelOptions = useMirrored<string[]>([])

  return {
    currentSceneIndex,
    leftPanelLoading,
    rightPanelLoading,
    pendingDubbingByIndex,
    pendingPayloadByIndex,
    preConfirmPanelByIndex,
    confirmedDubbingThisSession,
    genHistoryByIndex,
    genLoadingByPanelKey,
    selectedNavKeyByIndex,
    generatingMetaByIndex,
    lipSyncProgressHintByIndex,
    isSettingFinalDubbing,
    isDeletingDubbingRecord,
    serverVideoRecordsByIndex,
    draftDialogue,
    draftEmotion,
    draftLipSync,
    draftVoiceName,
    draftVoiceAvatarUrl,
    draftVoiceLibraryId,
    draftVoiceModelId,
    draftTimbreCode,
    draftVoiceProviderHint,
    draftByIndex,
    voicePickerOpen,
    emotionNameToCode,
    emotionLabelOptions
  }
}
