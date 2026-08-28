import { create } from 'zustand'
import { persist, type PersistStorage, type StorageValue } from 'zustand/middleware'
import type { Mutate, StoreApi } from 'zustand'
import type { CreationStep } from '~/types'
import { createInitialCreationState, type CreationStoreState } from './creation/state'
import { createContextSlice } from './creation/contextSlice'
import { createExtractSlice } from './creation/extractSlice'
import { createStep3Slice } from './creation/step3Slice'
import { createStep4Slice } from './creation/step4Slice'
import { createModalTaskSlice } from './creation/modalTaskSlice'
import { createVideoBatchSlice } from './creation/videoBatchSlice'

/** 与原 Pinia persist.paths 一一对应：只持久化需要的数据，排除临时状态 */
const CREATION_PERSIST_PATHS = [
  'workTitle',
  'currentStepIndex',
  'currentProjectId',
  'currentEpisodeId',
  'currentProjectType',
  'formData',
  'extractAgents',
  'optionalModelCodesByScope',
  'storyboardVideoSettingsByScope',
  // 大图 URL 列表由项目详情 / 步骤 hydrate 从服务端恢复，不再进 localStorage，降低堆与配额压力
  'manualScenes',
  'manualSceneAssetIds',
  'manualCharacters',
  'manualProps',
  'manualStoryboardIds',
  'characterForms',
  'propForms',
  'step3GenVisualByScope',
  // 刷新恢复生成中态仍依赖本地快照（服务端任务列表会二次对齐）
  'step4PlusLiveGenByScope',
  // 与 scoped 同步写入，兼容旧版仅扁平持久化的数据（afterRestore 会迁入 scoped）
  'sceneGenerationStatus',
  'characterFormGenerationStatus',
  'propFormGenerationStatus',
  'storyboardAgent',
  'storyboardGenerateSettings',
  'storyboardStylistAgent',
  'storyboardStylistGenerateSettings',
  'storyboardVideoAgent',
  'storyboardVideoGenerateSettings'
] as const

export type CreationPersistedState = Pick<
  CreationStoreState,
  (typeof CREATION_PERSIST_PATHS)[number]
>

function partializeCreationState(state: CreationStoreState): CreationPersistedState {
  const out: Record<string, unknown> = {}
  for (const key of CREATION_PERSIST_PATHS) {
    out[key] = state[key]
  }
  return out as CreationPersistedState
}

/**
 * 兼容旧版 pinia-plugin-persistedstate 的自定义 storage：
 * 旧数据为扁平 JSON（无 zustand 的 `{ state, version }` 包装），getItem 时检测并包装为
 * `{ state: parsed, version: 0 }`，保证老用户 localStorage 无损迁移；SSR 下所有访问均短路。
 */
const creationPersistStorage: PersistStorage<CreationPersistedState> = {
  getItem: (name) => {
    if (typeof window === 'undefined') return null
    const raw = window.localStorage.getItem(name)
    if (!raw) return null
    try {
      const parsed = JSON.parse(raw) as unknown
      if (
        parsed &&
        typeof parsed === 'object' &&
        'state' in (parsed as Record<string, unknown>)
      ) {
        return parsed as StorageValue<CreationPersistedState>
      }
      return { state: parsed as CreationPersistedState, version: 0 }
    } catch {
      return null
    }
  },
  setItem: (name, value) => {
    if (typeof window === 'undefined') return
    window.localStorage.setItem(name, JSON.stringify(value))
  },
  removeItem: (name) => {
    if (typeof window === 'undefined') return
    window.localStorage.removeItem(name)
  }
}

type CreationStoreApi = Mutate<
  StoreApi<CreationStoreState>,
  [['zustand/persist', CreationPersistedState]]
>

/**
 * 显式声明 callable 签名（无参重载放最后）：保证已平移 utils 中
 * `ReturnType<typeof useCreationStore>` 恒等于 CreationStoreState。
 */
type UseCreationStoreHook = CreationStoreApi & {
  <U>(selector: (state: CreationStoreState) => U): U
  (): CreationStoreState
}

export const useCreationStore: UseCreationStoreHook = create<CreationStoreState>()(
  persist(
    (set, get) => ({
      ...createInitialCreationState(),
      ...createContextSlice(set, get),
      ...createExtractSlice(set, get),
      ...createStep3Slice(set, get),
      ...createStep4Slice(set, get),
      ...createModalTaskSlice(set, get),
      ...createVideoBatchSlice(set, get)
    }),
    {
      name: 'creation-store',
      storage: creationPersistStorage,
      partialize: partializeCreationState,
      // 持久化数据恢复完成后，标记已就绪，页面可从骨架屏切换到实际内容（对齐原 afterRestore）
      onRehydrateStorage: () => (state) => {
        if (typeof window === 'undefined') return
        state?.finalizeClientHydration()
      }
    }
  )
) as UseCreationStoreHook

/** 原 Pinia getter currentStep 的 selector 版本：useCreationStore(selectCurrentStep) */
export const selectCurrentStep = (state: CreationStoreState): CreationStep => {
  const steps: CreationStep[] = [
    'global-setting',
    'story-script',
    'scene-character',
    'storyboard-script',
    'storyboard-video',
    'dubbing',
    'preview'
  ]
  return steps[state.currentStepIndex] || 'global-setting'
}

export type { CreationStoreState, CreationStateData, CreationActions } from './creation/state'

export {
  liveGenScopeKeyFromIds,
  type ExtractAgentOption,
  type ExtractAgents,
  type PendingExtractFormAssetItem,
  type SceneImage,
  type CharacterImage,
  type SceneGenerationStatus,
  type ExtractUiScopeSnapshot,
  type Step3GenVisualScopeMaps,
  type SceneModalSseTaskKind,
  type SceneModalSseTaskSnapshot,
  type StoryboardModalImageGenKind,
  type StoryboardImageGenTaskSnapshot,
  type StoryboardVideoGenTaskSnapshot,
  type StoryboardDubbingGenTaskSnapshot,
  type StoryboardVideoPromptGenTaskKind,
  type StoryboardVideoPromptGenTaskSnapshot,
  type Step4PlusLiveGenSnapshot,
  type ExtractModelCodesMap,
  type OptionalModelCodesScopeSnapshot,
  type StoryboardVideoSettingsScopeSnapshot
} from './creation/types'
