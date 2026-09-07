import type { StoryboardPanel } from '@/types'
import type { StudioFlowBinding } from '@/types/studio'
import {
  requestCreateFlowStepModal,
  type CreateFlowStepModalKind
} from '@/utils/createFlowStepModalIntent'
import { selectStudioFlowNodeByBindingKey } from '@/utils/studio/studioFlowNodeSelection'
import { STUDIO_FLOW_ADVANCED_MODAL_EVENT, type StudioFlowAdvancedModalDetail } from '@/utils/studio/studioFlowAdvancedModal'

export const STUDIO_FLOW_RPS_ASSET_INTENT_PREFIX = 'studio-flow-rps-asset:'
const SCP_ACTIVE_TAB_SESSION_PREFIX = 'scp-active-tab:'
const STUDIO_FLOW_RPS_ASSET_INTENT_TTL_MS = 2 * 60 * 1000

export interface StudioFlowEntityEditorAction {
  step: StudioFlowBinding['step']
  modalKind?: CreateFlowStepModalKind
  panelIndex?: number
  assetTab?: 'scene' | 'character' | 'prop'
  assetId?: number
}

export interface StudioFlowEntityEditorDispatchDeps {
  requestStepModal?: typeof requestCreateFlowStepModal
  requestAdvancedModal?: (detail: StudioFlowAdvancedModalDetail) => void
  selectNodeByBindingKey?: (bindingKey: string) => boolean
  writeSessionValue?: (key: string, value: string) => void
  now?: () => number
}

/**
 * 将流程绑定实体精确解析到原七步编辑入口。无法定位 serverId 时 fail-closed，
 * 禁止误开另一个分镜的弹窗。
 */
export function resolveStudioFlowEntityEditorAction(
  binding: StudioFlowBinding,
  storyboardPanels: StoryboardPanel[]
): StudioFlowEntityEditorAction | null {
  if (binding.role !== 'item') return { step: binding.step }
  if (binding.entityType === 'rps_asset') {
    const assetId = Number(binding.serverId)
    return binding.assetType && Number.isSafeInteger(assetId) && assetId > 0
      ? { step: 'scene-character', assetTab: binding.assetType, assetId }
      : null
  }
  const modalKind = binding.entityType === 'storyboard'
    ? 'storyboard-image'
    : binding.entityType === 'storyboard_video'
      ? 'storyboard-video'
      : binding.entityType === 'dubbing'
        ? 'storyboard-dubbing'
        : null
  if (!modalKind) return { step: binding.step }
  const serverId = Number(binding.serverId)
  if (!Number.isFinite(serverId) || serverId <= 0) return null
  const panelIndex = storyboardPanels.findIndex((panel) => Number(panel.id) === serverId)
  if (panelIndex < 0) return null
  return { step: binding.step, modalKind, panelIndex }
}

/**
 * 流程节点所有入口共用的精确派发器：选中节点并在画布内打开高级 Modal，
 * 不再打开整步 Drawer。
 */
export function openStudioFlowBoundEntity(
  binding: StudioFlowBinding,
  storyboardPanels: StoryboardPanel[],
  projectId: number | null | undefined,
  deps: StudioFlowEntityEditorDispatchDeps = {}
): boolean {
  const action = resolveStudioFlowEntityEditorAction(binding, storyboardPanels)
  if (!action) return false
  const requestStepModal = deps.requestStepModal ?? requestCreateFlowStepModal
  const requestAdvancedModal = deps.requestAdvancedModal ?? ((detail) => {
    if (typeof window === 'undefined') return
    window.dispatchEvent(new CustomEvent(STUDIO_FLOW_ADVANCED_MODAL_EVENT, { detail }))
  })
  const selectNodeByBindingKey = deps.selectNodeByBindingKey ?? selectStudioFlowNodeByBindingKey
  const writeSessionValue = deps.writeSessionValue ?? ((key: string, value: string) => {
    if (typeof window !== 'undefined') window.sessionStorage.setItem(key, value)
  })

  selectNodeByBindingKey(binding.bindingKey)

  if (action.assetTab && action.assetId) {
    const resolvedProjectId = Number(projectId)
    if (!Number.isSafeInteger(resolvedProjectId) || resolvedProjectId <= 0) return false
    try {
      writeSessionValue(`${SCP_ACTIVE_TAB_SESSION_PREFIX}${resolvedProjectId}`, action.assetTab)
      writeSessionValue(studioFlowRpsAssetIntentKey(resolvedProjectId, binding.episodeId), JSON.stringify({
        assetType: action.assetTab,
        assetId: action.assetId,
        createdAt: (deps.now ?? Date.now)()
      }))
    } catch {
      return false
    }
    requestAdvancedModal({
      kind: 'scene-image',
      assetType: action.assetTab,
      assetId: action.assetId
    })
    return true
  }

  if (action.modalKind && action.panelIndex != null) {
    requestStepModal(action.modalKind, action.panelIndex)
    requestAdvancedModal({ kind: action.modalKind, panelIndex: action.panelIndex })
    return true
  }

  if (binding.step === 'global-setting') {
    requestAdvancedModal({ kind: 'global-setting' })
    return true
  }
  if (binding.step === 'story-script' || binding.entityType === 'story_script') {
    requestAdvancedModal({ kind: 'story-script' })
    return true
  }

  return true
}

export function studioFlowRpsAssetIntentKey(projectId: number, episodeId: number | null | undefined): string {
  const resolvedEpisodeId = Number(episodeId)
  return `${STUDIO_FLOW_RPS_ASSET_INTENT_PREFIX}${projectId}:episode-${
    Number.isSafeInteger(resolvedEpisodeId) && resolvedEpisodeId > 0 ? resolvedEpisodeId : 0
  }`
}

export function readStudioFlowRpsAssetIntent(projectId: number, episodeId: number | null | undefined): {
  assetType: 'scene' | 'character' | 'prop'
  assetId: number
} | null {
  if (typeof window === 'undefined') return null
  const key = studioFlowRpsAssetIntentKey(projectId, episodeId)
  try {
    const raw = window.sessionStorage.getItem(key)
    if (!raw) return null
    const parsed = JSON.parse(raw) as { assetType?: unknown; assetId?: unknown; createdAt?: unknown }
    const assetId = Number(parsed.assetId)
    const createdAt = Number(parsed.createdAt)
    if (
      (parsed.assetType !== 'scene' && parsed.assetType !== 'character' && parsed.assetType !== 'prop') ||
      !Number.isSafeInteger(assetId) || assetId <= 0 ||
      !Number.isFinite(createdAt) || Date.now() - createdAt > STUDIO_FLOW_RPS_ASSET_INTENT_TTL_MS
    ) {
      window.sessionStorage.removeItem(key)
      return null
    }
    return { assetType: parsed.assetType, assetId }
  } catch {
    window.sessionStorage.removeItem(key)
    return null
  }
}

export function clearStudioFlowRpsAssetIntent(projectId: number, episodeId: number | null | undefined): void {
  if (typeof window === 'undefined') return
  try {
    window.sessionStorage.removeItem(studioFlowRpsAssetIntentKey(projectId, episodeId))
  } catch {
    // sessionStorage 不可用时由页面正常展示兜底。
  }
}

export function resolveStudioFlowRpsAssetIndex(
  intent: { assetType: 'scene' | 'character' | 'prop'; assetId: number },
  activeTab: 'scene' | 'character' | 'prop',
  assetIds: Record<number, number>
): number | null {
  if (intent.assetType !== activeTab) return null
  const entry = Object.entries(assetIds).find(([, assetId]) => Number(assetId) === intent.assetId)
  if (!entry) return null
  const index = Number(entry[0])
  return Number.isSafeInteger(index) && index >= 0 ? index : null
}
