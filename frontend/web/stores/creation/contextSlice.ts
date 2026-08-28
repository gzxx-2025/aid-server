import type { WorkData } from '~/types'
import type { UserProjectType } from '~/types/business-api'
import { plainDeep } from '~/utils/plainDeep'
import type { CreationGet,CreationSet } from './state'
import { liveGenScopeKeyFromIds } from './types'
export interface ContextActions {
  /** 更新作品标题 */
  setWorkTitle: (title: string) => void
  /** 设置当前步骤 */
  setCurrentStepIndex: (index: number) => void
  /** 设置当前创作上下文 */
  setCurrentProjectContext: (payload: {
    projectId?: number | null
    episodeId?: number | null
  }) => void
  /** 切换作品/剧集时清空各步骤表单与资产列表（formData 未按作品分桶，须主动清空避免串流） */
  resetStepFormDataForContextSwitch: () => void
  /**
   * 切换作品/剧集时清空第三步「进行中」全局 UI（提取遮罩、形态待生成列表、跟任务 SSE 等由页面侧关闭）。
   * 避免作品 A 提取中切到作品 B 仍显示 A 的提取状态。
   */
  resetLiveStep3TransientUiForContextSwitch: () => void
  setStep3AssetListSyncReady: (ready: boolean) => void
  setCurrentProjectType: (type: UserProjectType | null) => void
  setSeriesFlowEnteredStoryScript: (v: boolean) => void
  setSeriesEpisodeListTotal: (n: number | null) => void
  setCurrentMediaContext: (payload: {
    projectStatus?: number | null
    projectStatusReason?: string | null
    projectIsPublic?: string | null
    episodeEditorId?: number | null
    finalVideoUrl?: string | null
    pendingVideoUrl?: string | null
    exportStatus?: number | null
    episodeStatus?: number | null
    episodeStatusReason?: string | null
  }) => void
  setScriptServerHtmlBaseline: (html: string) => void
  setScriptComicVersion: (version: number) => void
  setExtractModalActionMode: (mode: 'start' | 'continueOrReextract') => void
  setScriptChangeLightBannerVisible: (visible: boolean) => void
  setPendingOpenContinueExtractModal: (pending: boolean) => void
  /** 更新表单数据 */
  updateFormData: (data: Partial<WorkData>) => void
  /** 更新素材准备数据 */
  updateSceneCharacterData: (data: Partial<WorkData['sceneCharacter']>) => void
}

export function createContextSlice(set: CreationSet, get: CreationGet): ContextActions {
  return {
    // 更新作品标题
    setWorkTitle(title: string) {
      set({ workTitle: title })
    },

    // 设置当前步骤
    setCurrentStepIndex(index: number) {
      set({ currentStepIndex: index })
    },

    // 设置当前创作上下文
    setCurrentProjectContext(payload: { projectId?: number | null; episodeId?: number | null }) {
      const prevProjectId = get().currentProjectId
      const prevEpisodeId = get().currentEpisodeId

      const normEp = (e: unknown): number | null => {
        if (e === null || e === undefined) return null
        const n = Number(e)
        return Number.isFinite(n) && n >= 0 ? n : null
      }

      let nextProjectId = get().currentProjectId
      if (payload.projectId !== undefined) {
        const pid = Number(payload.projectId)
        nextProjectId = Number.isFinite(pid) && pid > 0 ? pid : null
      }
      let nextEpisodeId = get().currentEpisodeId
      if (payload.episodeId !== undefined) {
        if (payload.episodeId === null) {
          nextEpisodeId = null
        } else {
          const eid = Number(payload.episodeId)
          nextEpisodeId = Number.isFinite(eid) && eid >= 0 ? eid : null
        }
      }

      const projectChanged =
        payload.projectId !== undefined && Number(prevProjectId ?? 0) !== Number(nextProjectId ?? 0)
      // 切作品未显式带 episodeId 时清空旧集，避免剧集 episodeId 串进电影/新作品请求
      if (projectChanged && payload.episodeId === undefined) {
        nextEpisodeId = null
      }
      const episodeChanged = normEp(prevEpisodeId) !== normEp(nextEpisodeId)

      const prevScopeKey = liveGenScopeKeyFromIds(prevProjectId, prevEpisodeId)
      const nextScopeKey = liveGenScopeKeyFromIds(
        payload.projectId !== undefined ? nextProjectId : prevProjectId,
        nextEpisodeId
      )
      const scopeWillChange = (projectChanged || episodeChanged) && prevScopeKey !== nextScopeKey

      /** 须在改写 currentProjectId 之前落盘：此时内存里的分镜/视频/配音 loading 仍属上一作品 */
      if (scopeWillChange) {
        get().syncStep3GenVisualToCurrentScope()
        get().syncStep4PlusLiveGenToCurrentScope()
        get().persistExtractUiForScopeKey(prevScopeKey)
        get().persistOptionalModelCodesForScopeKey(prevScopeKey)
        get().persistStoryboardVideoSettingsForScopeKey(prevScopeKey)
        get().persistStep4PlusLiveGenForScopeKey(prevScopeKey)
      }

      if (payload.projectId !== undefined) {
        if (nextProjectId !== get().currentProjectId) {
          set({ seriesFlowEnteredStoryScript: false, seriesEpisodeListTotal: null })
          get().hydratePausedTaskFollowFromSession(nextProjectId)
          // 作品已变，类型须等 project/detail hydrate；避免沿用上一作品 series/movie
          set({ currentProjectType: null })
        }
        set({ currentProjectId: nextProjectId })
      }
      if (payload.episodeId !== undefined || (projectChanged && payload.episodeId === undefined)) {
        set({ currentEpisodeId: nextEpisodeId })
      }

      if (projectChanged) {
        set({
          currentProjectStatus: null,
          currentProjectStatusReason: null,
          currentProjectIsPublic: null
        })
      }
      if (scopeWillChange) {
        set({
          currentEpisodeEditorId: null,
          currentFinalVideoUrl: null,
          currentPendingVideoUrl: null,
          currentExportStatus: null,
          currentEpisodeStatus: null,
          currentEpisodeStatusReason: null
        })
      }

      if (scopeWillChange) {
        get().resetLiveStep3TransientUiForContextSwitch()
        get().resetStepFormDataForContextSwitch()
        get().restoreStep4PlusLiveGenForScopeKey(nextScopeKey)
        get().applyStep3GenVisualFromScopeKey(nextScopeKey)
        get().applyOptionalModelCodesFromScopeKey(nextScopeKey)
        get().applyStoryboardVideoSettingsFromScopeKey(nextScopeKey)
        get().applyExtractUiFromScopeKey(nextScopeKey)
        get().refreshStep3VisualGeneratingFlag()
      }
    },

    /**
     * 切换作品/剧集时清空各步骤表单与资产列表（formData 未按作品分桶，须主动清空避免串流）。
     */
    resetStepFormDataForContextSwitch() {
      set({
        formData: {
          ...get().formData,
          storyScript: { content: '' },
          sceneCharacter: { characters: [], scenes: [], props: [] },
          storyboardScript: { panels: [] },
          storyboardVideo: { panels: [] },
          dubbing: { voiceActors: [], bgm: '', panels: [] }
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
        scriptServerHtmlBaseline: '',
        scriptComicVersion: 0,
        extractModalActionMode: 'start',
        scriptChangeLightBannerVisible: false,
        pendingOpenContinueExtractModal: false
      })
    },

    /**
     * 切换作品/剧集时清空第三步「进行中」全局 UI（提取遮罩、形态待生成列表、跟任务 SSE 等由页面侧关闭）。
     * 避免作品 A 提取中切到作品 B 仍显示 A 的提取状态。
     */
    resetLiveStep3TransientUiForContextSwitch() {
      set({
        isExtractingAssets: false,
        extractingStage: 'scene',
        extractingStages: {
          scene: false,
          character: false,
          prop: false
        }
      })
      get().clearExtractingTaskProgress()
      set({
        isGeneratingStep3Visual: false,
        step3FormImageTaskFollowCount: 0,
        step3FormImageTaskFollowTaskIds: [],
        pendingExtractFormAssets: [],
        showExtractAgentModal: false,
        extractModalActionMode: 'start',
        scriptChangeLightBannerVisible: false,
        pendingOpenContinueExtractModal: false,
        step3AssetListSyncReady: false
      })
    },

    setStep3AssetListSyncReady(ready: boolean) {
      set({ step3AssetListSyncReady: ready })
    },

    setCurrentProjectType(type: UserProjectType | null) {
      set({ currentProjectType: type })
    },

    setSeriesFlowEnteredStoryScript(v: boolean) {
      set({ seriesFlowEnteredStoryScript: v })
    },

    setSeriesEpisodeListTotal(n: number | null) {
      set({ seriesEpisodeListTotal: n })
    },

    setCurrentMediaContext(payload: {
      projectStatus?: number | null
      projectStatusReason?: string | null
      projectIsPublic?: string | null
      episodeEditorId?: number | null
      finalVideoUrl?: string | null
      pendingVideoUrl?: string | null
      exportStatus?: number | null
      episodeStatus?: number | null
      episodeStatusReason?: string | null
    }) {
      if (payload.projectStatus !== undefined) {
        set({ currentProjectStatus: payload.projectStatus })
      }
      if (payload.projectStatusReason !== undefined) {
        set({
          currentProjectStatusReason: String(payload.projectStatusReason || '').trim() || null
        })
      }
      if (payload.projectIsPublic !== undefined) {
        set({ currentProjectIsPublic: payload.projectIsPublic })
      }
      if (payload.episodeEditorId !== undefined) {
        const id = Number(payload.episodeEditorId)
        set({ currentEpisodeEditorId: Number.isFinite(id) && id > 0 ? id : null })
      }
      if (payload.finalVideoUrl !== undefined) {
        set({ currentFinalVideoUrl: String(payload.finalVideoUrl || '').trim() || null })
      }
      if (payload.pendingVideoUrl !== undefined) {
        set({ currentPendingVideoUrl: String(payload.pendingVideoUrl || '').trim() || null })
      }
      if (payload.exportStatus !== undefined) {
        const st = Number(payload.exportStatus)
        set({ currentExportStatus: Number.isFinite(st) ? st : null })
      }
      if (payload.episodeStatus !== undefined) {
        const st = Number(payload.episodeStatus)
        set({ currentEpisodeStatus: Number.isFinite(st) ? st : null })
      }
      if (payload.episodeStatusReason !== undefined) {
        set({
          currentEpisodeStatusReason: String(payload.episodeStatusReason || '').trim() || null
        })
      }
    },

    setScriptServerHtmlBaseline(html: string) {
      set({ scriptServerHtmlBaseline: html })
    },

    setScriptComicVersion(version: number) {
      const n = Number(version)
      set({ scriptComicVersion: Number.isFinite(n) && n >= 0 ? n : 0 })
    },

    setExtractModalActionMode(mode: 'start' | 'continueOrReextract') {
      set({
        extractModalActionMode: mode === 'continueOrReextract' ? 'continueOrReextract' : 'start'
      })
    },

    setScriptChangeLightBannerVisible(visible: boolean) {
      set({ scriptChangeLightBannerVisible: !!visible })
    },

    setPendingOpenContinueExtractModal(pending: boolean) {
      set({ pendingOpenContinueExtractModal: !!pending })
    },

    // 更新表单数据
    updateFormData(data: Partial<WorkData>) {
      set({ formData: { ...get().formData, ...plainDeep(data) } })
    },

    // 更新素材准备数据
    updateSceneCharacterData(data: Partial<WorkData['sceneCharacter']>) {
      set({
        formData: {
          ...get().formData,
          sceneCharacter: { ...get().formData.sceneCharacter, ...data }
        }
      })
    }
  }
}
