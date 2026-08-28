import type { CreationStep } from '~/types'
import {
EMPTY_COUNT_PROGRESS,
mergeCountProgressFromSse,
type CountProgressSnapshot,
type TaskSseProgressInput
} from '~/utils/taskSseProgressText'
import {
emptyExtractModelCodes,
migrateOptionalModelCodesFromPersist,
migrateStoryboardVideoSettingsFromPersist,
pausedTasksFollowSessionKey
} from './helpers'
import {
migrateLegacyLiveGenScopeKeys,
migrateStep3GenVisualMapsFromPersist,
migrateStep4PlusLiveGenAfterRestore
} from './liveGenMigrate'
import type { CreationGet,CreationSet } from './state'
import { scopeKeyLegacyAliases,type SceneGenerationStatus } from './types'

export interface VideoBatchActions {
  /** 原 Pinia getter currentStep：当前步骤标识（zustand 下以方法形式读取） */
  currentStep: () => CreationStep
  setGeneratingStoryboardVideo: (flag: boolean) => void
  setStoryboardVideoBatchProgress: (completed: number, total: number) => void
  applyStoryboardVideoBatchSseProgress: (p: TaskSseProgressInput) => void
  clearStoryboardVideoBatchProgress: () => void
  setStoryboardVideoBatchError: (msg: string | null) => void
  setStoryboardVideoBatchActivePromptTaskId: (taskId: number | null) => void
  setStoryboardVideoBatchActiveVideoTaskId: (taskId: number | null) => void
  setStoryboardVideoBatchTargetStoryboardIds: (storyboardIds: number[]) => void
  clearStoryboardVideoBatchTargetStoryboardIds: () => void
  isStoryboardVideoBatchTarget: (storyboardId: number) => boolean
  setStoryboardPanelVideoGenStatus: (storyboardId: number, status: SceneGenerationStatus) => void
  clearStoryboardPanelVideoGenStatus: (storyboardId: number) => void
  setStoryboardPanelVideoGenError: (storyboardId: number, message: string) => void
  clearStoryboardPanelVideoGenError: (storyboardId: number) => void
  /**
   * 分镜视频批量任务的统一终态迁移：成功、部分失败、失败、取消都必须从 running
   * 一次性进入 terminal，禁止遗留任一可被刷新恢复逻辑识别为工作的字段。
   */
  finalizeStoryboardVideoBatchGeneration: () => void
  stopStoryboardVideoBatchGeneration: () => void
  hydratePausedTaskFollowFromSession: (projectId: number | null) => void
  syncPausedTaskFollowSession: () => void
  addPausedTaskFollow: (taskId: number) => void
  removePausedTaskFollow: (taskId: number) => void
  /** 仅保留仍出现在当前任务列表中的 id（任务已从列表消失时清标记） */
  prunePausedTaskFollowKeepOnlyListed: (listedTaskIds: Set<number>) => void
  /** 重置所有数据 */
  reset: () => void
  /**
   * Pinia persist 的 afterRestore 在 Nuxt 刷新时可能晚于步骤页 watch(immediate)。
   * 步骤页 SSE 恢复前调用，确保 step4PlusLiveGenByScope 等已灌回扁平字段。
   */
  finalizeClientHydration: () => void
}

export function createVideoBatchSlice(set: CreationSet, get: CreationGet): VideoBatchActions {
  return {
    currentStep(): CreationStep {
      const steps: CreationStep[] = [
        'global-setting',
        'story-script',
        'scene-character',
        'storyboard-script',
        'storyboard-video',
        'dubbing',
        'preview'
      ]
      return steps[get().currentStepIndex] || 'global-setting'
    },

    setGeneratingStoryboardVideo(flag: boolean) {
      set({ isGeneratingStoryboardVideo: flag })
      if (!flag) {
        set({
          storyboardVideoBatchError: null,
          storyboardVideoBatchActivePromptTaskId: null,
          storyboardVideoBatchActiveVideoTaskId: null
        })
      }
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardVideoBatchProgress(completed: number, total: number) {
      set({
        storyboardVideoBatchProgress: {
          ...get().storyboardVideoBatchProgress,
          completed,
          total
        }
      })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    applyStoryboardVideoBatchSseProgress(p: TaskSseProgressInput) {
      set({
        storyboardVideoBatchProgress: mergeCountProgressFromSse(
          get().storyboardVideoBatchProgress,
          p
        )
      })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    clearStoryboardVideoBatchProgress() {
      set({ storyboardVideoBatchProgress: { ...EMPTY_COUNT_PROGRESS } })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardVideoBatchError(msg: string | null) {
      set({ storyboardVideoBatchError: msg })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardVideoBatchActivePromptTaskId(taskId: number | null) {
      const n = Number(taskId)
      set({ storyboardVideoBatchActivePromptTaskId: Number.isFinite(n) && n > 0 ? n : null })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardVideoBatchActiveVideoTaskId(taskId: number | null) {
      const n = Number(taskId)
      set({ storyboardVideoBatchActiveVideoTaskId: Number.isFinite(n) && n > 0 ? n : null })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardVideoBatchTargetStoryboardIds(storyboardIds: number[]) {
      set({
        storyboardVideoBatchTargetStoryboardIds: (storyboardIds ?? [])
          .map((id) => Number(id))
          .filter((id) => Number.isFinite(id) && id > 0)
      })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    clearStoryboardVideoBatchTargetStoryboardIds() {
      set({ storyboardVideoBatchTargetStoryboardIds: [] })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    isStoryboardVideoBatchTarget(storyboardId: number): boolean {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return false
      return get().storyboardVideoBatchTargetStoryboardIds.includes(sid)
    },

    setStoryboardPanelVideoGenStatus(storyboardId: number, status: SceneGenerationStatus) {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return
      const key = String(sid)
      if (get().storyboardPanelVideoGenStatusByStoryboardId[key] === status) return
      set({
        storyboardPanelVideoGenStatusByStoryboardId: {
          ...get().storyboardPanelVideoGenStatusByStoryboardId,
          [key]: status
        }
      })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    clearStoryboardPanelVideoGenStatus(storyboardId: number) {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return
      const next = { ...get().storyboardPanelVideoGenStatusByStoryboardId }
      delete next[String(sid)]
      set({ storyboardPanelVideoGenStatusByStoryboardId: next })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardPanelVideoGenError(storyboardId: number, message: string) {
      const sid = Number(storyboardId)
      const text = String(message ?? '').trim()
      if (!Number.isFinite(sid) || sid <= 0 || !text) return
      const key = String(sid)
      if (get().storyboardPanelVideoGenErrorByStoryboardId[key] === text) return
      set({
        storyboardPanelVideoGenErrorByStoryboardId: {
          ...get().storyboardPanelVideoGenErrorByStoryboardId,
          [key]: text
        }
      })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    clearStoryboardPanelVideoGenError(storyboardId: number) {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return
      if (!get().storyboardPanelVideoGenErrorByStoryboardId[String(sid)]) return
      const next = { ...get().storyboardPanelVideoGenErrorByStoryboardId }
      delete next[String(sid)]
      set({ storyboardPanelVideoGenErrorByStoryboardId: next })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    /**
     * 分镜视频批量任务的统一终态迁移：成功、部分失败、失败、取消都必须从 running
     * 一次性进入 terminal，禁止遗留任一可被刷新恢复逻辑识别为工作的字段。
     */
    finalizeStoryboardVideoBatchGeneration() {
      const currentScopeKey = get().step3GenVisualScopeKey()
      const currentScope = get().step4PlusLiveGenByScope[currentScopeKey]
      const modalStoryboardIds = new Set(
        Object.keys(currentScope?.storyboardVideoGenTasksByStoryboardId || {})
      )
      const nextStatus: Record<string, SceneGenerationStatus> = {}
      for (const [key, status] of Object.entries(
        get().storyboardPanelVideoGenStatusByStoryboardId as Record<string, SceneGenerationStatus>
      )) {
        if (status === 'failed' || modalStoryboardIds.has(key)) nextStatus[key] = status
      }
      set({
        isGeneratingStoryboardVideo: false,
        storyboardVideoBatchError: null,
        storyboardVideoBatchActivePromptTaskId: null,
        storyboardVideoBatchActiveVideoTaskId: null,
        storyboardPanelVideoGenStatusByStoryboardId: nextStatus,
        storyboardVideoBatchTargetStoryboardIds: [],
        storyboardVideoBatchProgress: { completed: 0, total: 0 } as CountProgressSnapshot
      })
      get().syncStep4PlusLiveGenToCurrentScope()

      // null/0 旧 scope 与当前 scope 表示同一作品。它们只能作为兼容读源，不能在
      // 当前桶终态后继续保留 batch 工作凭证，否则刷新 hydrate 会把 loading 复活。
      for (const alias of scopeKeyLegacyAliases(currentScopeKey)) {
        if (alias === currentScopeKey) continue
        const legacy = get().step4PlusLiveGenByScope[alias]
        if (!legacy) continue
        const legacyModalStoryboardIds = new Set(
          Object.keys(legacy.storyboardVideoGenTasksByStoryboardId || {})
        )
        const legacyStatus = Object.fromEntries(
          Object.entries(legacy.storyboardPanelVideoGenStatusByStoryboardId || {}).filter(
            ([storyboardId, status]) =>
              status === 'failed' || legacyModalStoryboardIds.has(storyboardId)
          )
        ) as Record<string, SceneGenerationStatus>
        get().mergeStep4PlusLiveGenForScopeKey(alias, {
          isGeneratingStoryboardVideo: false,
          storyboardVideoBatchProgress: { completed: 0, total: 0 } as CountProgressSnapshot,
          storyboardVideoBatchError: null,
          storyboardVideoBatchActivePromptTaskId: null,
          storyboardVideoBatchActiveVideoTaskId: null,
          storyboardPanelVideoGenStatusByStoryboardId: legacyStatus,
          storyboardVideoBatchTargetStoryboardIds: []
        })
      }
    },

    stopStoryboardVideoBatchGeneration() {
      get().finalizeStoryboardVideoBatchGeneration()
    },

    hydratePausedTaskFollowFromSession(projectId: number | null) {
      if (
        typeof window === 'undefined' ||
        projectId == null ||
        !Number.isFinite(projectId) ||
        projectId <= 0
      ) {
        set({ taskIdsWithLocalFollowPaused: [] })
        return
      }
      try {
        const raw = window.sessionStorage.getItem(pausedTasksFollowSessionKey(projectId))
        const parsed = raw ? JSON.parse(raw) : []
        set({
          taskIdsWithLocalFollowPaused: Array.isArray(parsed)
            ? parsed.map((x: unknown) => Number(x)).filter((n) => Number.isFinite(n) && n > 0)
            : []
        })
      } catch {
        set({ taskIdsWithLocalFollowPaused: [] })
      }
    },

    syncPausedTaskFollowSession() {
      if (typeof window === 'undefined') return
      const pid = get().currentProjectId
      if (pid == null || !Number.isFinite(pid) || pid <= 0) return
      try {
        window.sessionStorage.setItem(
          pausedTasksFollowSessionKey(pid),
          JSON.stringify(get().taskIdsWithLocalFollowPaused)
        )
      } catch {
        /* ignore quota / private mode */
      }
    },

    addPausedTaskFollow(taskId: number) {
      const id = Number(taskId)
      if (!Number.isFinite(id) || id <= 0) return
      if (!get().taskIdsWithLocalFollowPaused.includes(id)) {
        set({ taskIdsWithLocalFollowPaused: [...get().taskIdsWithLocalFollowPaused, id] })
      }
      get().syncPausedTaskFollowSession()
    },

    removePausedTaskFollow(taskId: number) {
      const id = Number(taskId)
      set({
        taskIdsWithLocalFollowPaused: get().taskIdsWithLocalFollowPaused.filter((x) => x !== id)
      })
      get().syncPausedTaskFollowSession()
    },

    /** 仅保留仍出现在当前任务列表中的 id（任务已从列表消失时清标记） */
    prunePausedTaskFollowKeepOnlyListed(listedTaskIds: Set<number>) {
      set({
        taskIdsWithLocalFollowPaused: get().taskIdsWithLocalFollowPaused.filter((id) =>
          listedTaskIds.has(id)
        )
      })
      get().syncPausedTaskFollowSession()
    },

    // 重置所有数据
    reset() {
      set({
        workTitle: '未命名作品',
        currentStepIndex: 0,
        currentProjectId: null,
        currentEpisodeId: null,
        currentProjectType: null,
        currentProjectStatus: null,
        currentProjectStatusReason: null,
        currentProjectIsPublic: null,
        currentEpisodeEditorId: null,
        currentFinalVideoUrl: null,
        currentPendingVideoUrl: null,
        currentExportStatus: null,
        currentEpisodeStatus: null,
        currentEpisodeStatusReason: null,
        formData: {
          globalSetting: {
            title: '',
            genre: '',
            style: '',
            description: '',
            aspectRatio: '16:9',
            scriptType: 'plot',
            modelStrategy: 'economy',
            creationMode: 'pro',
            selectedStyle: null,
            styleSelectionTouched: false,
            styleLocked: false,
            myStyles: []
          },
          storyScript: {
            content: ''
          },
          sceneCharacter: {
            characters: [],
            scenes: [],
            props: []
          },
          storyboardScript: {
            panels: []
          },
          storyboardVideo: {
            panels: []
          },
          dubbing: {
            voiceActors: [],
            bgm: '',
            panels: []
          }
        },
        sceneImages: {},
        characterImages: {},
        propImages: {},
        characterFormImages: {},
        propFormImages: {},
        manualScenes: [],
        manualSceneAssetIds: [],
        manualCharacters: [],
        manualProps: [],
        manualStoryboardIds: [],
        characterForms: {},
        propForms: {},
        sceneGenerationStatus: {},
        characterFormGenerationStatus: {},
        propFormGenerationStatus: {},
        step3GenVisualByScope: {},
        extractUiByScope: {},
        assetExtractFollowByScope: {},
        assetExtractShellLiveTaskId: null,
        step4PlusLiveGenByScope: {},
        extractModelCodes: emptyExtractModelCodes(),
        extractImageModelCodes: emptyExtractModelCodes(),
        optionalModelCodesByScope: {},
        storyboardVideoSettingsByScope: {},
        storyboardGenerateSettings: { ...get().storyboardGenerateSettings, modelCode: '' },
        storyboardStylistGenerateSettings: {
          ...get().storyboardStylistGenerateSettings,
          modelCode: ''
        },
        dubbingBatchGeneratingIndices: [],
        isExtractingAssets: false,
        extractingStages: {
          scene: false,
          character: false,
          prop: false
        }
      })
      get().clearExtractingTaskProgress()
      set({
        storyboardVideoGenerateSettings: {
          agentId: '',
          videoModel: '',
          videoPromptModelCode: '',
          aspectRatio: '16:9',
          resolution: '720p',
          durationSeconds: undefined,
          soundEffects: 'with-sound'
        },
        storyboardVideoAgent: {
          id: '',
          name: '',
          desc: '',
          thumbnail: ''
        },
        isGeneratingStoryboard: false,
        storyboardGenerationProgress: { ...EMPTY_COUNT_PROGRESS },
        storyboardGenerationError: null,
        storyboardScriptActiveTaskId: null,
        storyboardScriptPartialFailedData: null,
        isGeneratingStoryboardImageBatch: false,
        storyboardImageBatchProgress: { ...EMPTY_COUNT_PROGRESS },
        storyboardImageBatchError: null,
        storyboardImageBatchActiveTaskId: null,
        storyboardImageBatchActiveImageTaskId: null,
        storyboardPanelImageGenStatusByStoryboardId: {},
        storyboardImageBatchTargetStoryboardIds: [],
        isGeneratingStoryboardVideo: false,
        storyboardVideoBatchProgress: { ...EMPTY_COUNT_PROGRESS },
        storyboardVideoBatchError: null,
        storyboardVideoBatchActivePromptTaskId: null,
        storyboardVideoBatchActiveVideoTaskId: null,
        storyboardPanelVideoGenStatusByStoryboardId: {},
        storyboardPanelVideoGenErrorByStoryboardId: {},
        storyboardVideoBatchTargetStoryboardIds: [],
        isGeneratingStep3Visual: false,
        step3FormImageTaskFollowCount: 0,
        step3FormImageTaskFollowTaskIds: [],
        scriptServerHtmlBaseline: '',
        scriptComicVersion: 0,
        extractModalActionMode: 'start',
        scriptChangeLightBannerVisible: false,
        pendingOpenContinueExtractModal: false,
        pendingExtractFormAssets: [],
        taskIdsWithLocalFollowPaused: [],
        step3AssetListSyncReady: false
      })
    },

    /**
     * Pinia persist 的 afterRestore 在 Nuxt 刷新时可能晚于步骤页 watch(immediate)。
     * 步骤页 SSE 恢复前调用，确保 step4PlusLiveGenByScope 等已灌回扁平字段。
     */
    finalizeClientHydration() {
      if (get().isHydrated) return
      // Pinia 直接改 this；zustand 下先构造可变 draft（克隆迁移函数会就地改写的容器），迁移后整体 set 回
      const draft = {
        ...get(),
        formData: { ...get().formData, storyScript: { ...get().formData.storyScript } },
        step3GenVisualByScope: { ...get().step3GenVisualByScope },
        step4PlusLiveGenByScope: { ...get().step4PlusLiveGenByScope },
        optionalModelCodesByScope: { ...get().optionalModelCodesByScope },
        storyboardVideoSettingsByScope: { ...get().storyboardVideoSettingsByScope },
        extractModelCodes: { ...get().extractModelCodes },
        extractImageModelCodes: { ...get().extractImageModelCodes },
        storyboardAgent: { ...get().storyboardAgent },
        storyboardGenerateSettings: { ...get().storyboardGenerateSettings },
        storyboardStylistGenerateSettings: { ...get().storyboardStylistGenerateSettings },
        storyboardVideoAgent: { ...get().storyboardVideoAgent },
        storyboardVideoGenerateSettings: { ...get().storyboardVideoGenerateSettings },
        dubbingBatchGeneratingIndices: [...get().dubbingBatchGeneratingIndices]
      }
      const raw: unknown = draft.formData?.storyScript?.content
      if (draft.formData?.storyScript && typeof raw !== 'string') {
        draft.formData.storyScript.content =
          raw == null ? '' : typeof raw === 'number' || typeof raw === 'boolean' ? String(raw) : ''
      }
      migrateStep3GenVisualMapsFromPersist(draft)
      migrateOptionalModelCodesFromPersist(draft)
      migrateStoryboardVideoSettingsFromPersist(draft)
      migrateLegacyLiveGenScopeKeys(draft)
      migrateStep4PlusLiveGenAfterRestore(draft)
      set(draft)
      get().refreshStep3VisualGeneratingFlag()
      if (typeof window !== 'undefined') {
        const pid = Number(get().currentProjectId)
        if (Number.isFinite(pid) && pid > 0) {
          get().hydratePausedTaskFollowFromSession(pid)
        }
      }
      set({ isHydrated: true })
    }
  }
}
