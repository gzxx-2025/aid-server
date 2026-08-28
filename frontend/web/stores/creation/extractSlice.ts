import {
applyExtractUiSnapshotToStore,
applyOptionalModelCodesToStore,
applyStoryboardVideoSettingsToStore,
emptyOptionalModelCodesScopeSnapshot,
emptyStoryboardVideoSettingsScopeSnapshot,
snapshotExtractUiFromStore,
snapshotOptionalModelCodesFromStore,
snapshotStoryboardVideoSettingsFromStore
} from './helpers'
import type { CreationGet,CreationSet } from './state'
import type {
CharacterImage,
ExtractAgents,
SceneImage
} from './types'

export interface ExtractActions {
  /** 更新提取状态 */
  setExtractingAssets: (isExtracting: boolean) => void
  setExtractingStage: (stage: 'scene' | 'character' | 'prop') => void
  setExtractingStages: (stages: { scene: boolean; character: boolean; prop: boolean }) => void
  setExtractingTaskProgress: (
    payload: Partial<{
      percent: number
      stepTitle: string
      message: string
      stepIndex: number | null
      stepTotal: number | null
    }>
  ) => void
  clearExtractingTaskProgress: () => void
  persistExtractUiForScopeKey: (scopeKey: string) => void
  applyExtractUiFromScopeKey: (scopeKey: string) => void
  clearExtractUiForScopeKey: (scopeKey: string) => void
  setAssetExtractFollowTask: (scopeKey: string, taskId: number | null) => void
  getAssetExtractFollowTask: (scopeKey: string) => number | null
  setAssetExtractShellLiveTaskId: (taskId: number | null) => void
  getAssetExtractShellLiveTaskId: () => number | null
  isAssetExtractSseLiveForTask: (taskId: number) => boolean
  syncExtractUiToCurrentScope: () => void
  finishAssetExtractUiForCurrentScope: () => void
  /** 更新智能体 */
  updateExtractAgents: (agents: ExtractAgents) => void
  updateExtractModelCodes: (codes: { scene?: string; character?: string; prop?: string }) => void
  updateExtractImageModelCodes: (codes: {
    scene?: string
    character?: string
    prop?: string
  }) => void
  persistOptionalModelCodesForScopeKey: (scopeKey: string) => void
  applyOptionalModelCodesFromScopeKey: (scopeKey: string) => void
  persistStoryboardVideoSettingsForScopeKey: (scopeKey: string) => void
  applyStoryboardVideoSettingsFromScopeKey: (scopeKey: string) => void
  syncStoryboardVideoSettingsToCurrentScope: () => void
  syncOptionalModelCodesToCurrentScope: () => void
  /** 场景图片相关 */
  setSceneImages: (sceneIndex: number, images: SceneImage[]) => void
  addSceneImage: (sceneIndex: number, image: SceneImage) => void
  /** 角色图片相关 */
  setCharacterImages: (characterIndex: number, images: CharacterImage[]) => void
  addCharacterImage: (characterIndex: number, image: CharacterImage) => void
  /** 道具图片相关 */
  setPropImages: (propIndex: number, images: CharacterImage[]) => void
  addPropImage: (propIndex: number, image: CharacterImage) => void
  /** 角色形态图片相关 */
  setCharacterFormImages: (key: string, images: CharacterImage[]) => void
  /** 道具形态图片相关 */
  setPropFormImages: (key: string, images: CharacterImage[]) => void
  /** 手动添加标记 */
  addManualScene: (index: number) => void
  removeManualScene: (index: number) => void
  addManualSceneAssetId: (assetId: number) => void
  removeManualSceneAssetId: (assetId: number) => void
  addManualCharacter: (index: number) => void
  removeManualCharacter: (index: number) => void
  addManualProp: (index: number) => void
  removeManualProp: (index: number) => void
  addManualStoryboard: (storyboardId: number) => void
  removeManualStoryboard: (storyboardId: number) => void
  pruneManualStoryboardIds: (validIds: number[]) => void
  isManualStoryboard: (storyboardId: number) => boolean
  /** 角色形态 */
  setCharacterForms: (
    characterIndex: number,
    forms: Array<{ name: string; voiceover?: string }>
  ) => void
  /** 道具形态 */
  setPropForms: (propIndex: number, forms: Array<{ name: string }>) => void
}

export function createExtractSlice(set: CreationSet, get: CreationGet): ExtractActions {
  return {
    // 更新提取状态
    setExtractingAssets(isExtracting: boolean) {
      set({ isExtractingAssets: isExtracting })
    },

    setExtractingStage(stage: 'scene' | 'character' | 'prop') {
      set({ extractingStage: stage })
    },

    setExtractingStages(stages: { scene: boolean; character: boolean; prop: boolean }) {
      set({ extractingStages: stages })
    },

    setExtractingTaskProgress(
      payload: Partial<{
        percent: number
        stepTitle: string
        message: string
        stepIndex: number | null
        stepTotal: number | null
      }>
    ) {
      set({
        extractingTaskProgress: {
          ...get().extractingTaskProgress,
          ...payload
        }
      })
    },

    clearExtractingTaskProgress() {
      set({
        extractingTaskProgress: {
          percent: 0,
          stepTitle: '',
          message: '',
          stepIndex: null,
          stepTotal: null
        }
      })
    },

    persistExtractUiForScopeKey(scopeKey: string) {
      if (!scopeKey || scopeKey.startsWith('0:')) return
      const snap = snapshotExtractUiFromStore(get())
      const hasLiveExtract =
        snap.isExtractingAssets ||
        snap.extractingStages.scene ||
        snap.extractingStages.character ||
        snap.extractingStages.prop ||
        String(snap.extractingTaskProgress.stepTitle || '').trim() ||
        String(snap.extractingTaskProgress.message || '').trim() ||
        (typeof snap.extractingTaskProgress.percent === 'number' &&
          snap.extractingTaskProgress.percent > 0)
      if (hasLiveExtract) {
        set({ extractUiByScope: { ...get().extractUiByScope, [scopeKey]: snap } })
      } else {
        const next = { ...get().extractUiByScope }
        delete next[scopeKey]
        set({ extractUiByScope: next })
      }
    },

    applyExtractUiFromScopeKey(scopeKey: string) {
      const draft = {
        isExtractingAssets: get().isExtractingAssets,
        extractingStage: get().extractingStage,
        extractingStages: get().extractingStages,
        extractingTaskProgress: get().extractingTaskProgress
      }
      applyExtractUiSnapshotToStore(draft, get().extractUiByScope[scopeKey])
      set(draft)
    },

    clearExtractUiForScopeKey(scopeKey: string) {
      if (!scopeKey) return
      const nextUi = { ...get().extractUiByScope }
      delete nextUi[scopeKey]
      const nextFollow = { ...get().assetExtractFollowByScope }
      delete nextFollow[scopeKey]
      set({ extractUiByScope: nextUi, assetExtractFollowByScope: nextFollow })
    },

    setAssetExtractFollowTask(scopeKey: string, taskId: number | null) {
      if (!scopeKey) return
      if (taskId != null && Number.isFinite(taskId) && taskId > 0) {
        set({
          assetExtractFollowByScope: { ...get().assetExtractFollowByScope, [scopeKey]: taskId }
        })
      } else {
        const next = { ...get().assetExtractFollowByScope }
        delete next[scopeKey]
        set({ assetExtractFollowByScope: next })
      }
    },

    getAssetExtractFollowTask(scopeKey: string): number | null {
      const id = get().assetExtractFollowByScope[scopeKey]
      return id != null && Number.isFinite(id) && id > 0 ? id : null
    },

    setAssetExtractShellLiveTaskId(taskId: number | null) {
      set({
        assetExtractShellLiveTaskId:
          taskId != null && Number.isFinite(taskId) && taskId > 0 ? taskId : null
      })
    },

    getAssetExtractShellLiveTaskId(): number | null {
      const id = get().assetExtractShellLiveTaskId
      return id != null && Number.isFinite(id) && id > 0 ? id : null
    },

    isAssetExtractSseLiveForTask(taskId: number): boolean {
      const id = Number(taskId)
      if (!Number.isFinite(id) || id <= 0) return false
      return get().getAssetExtractShellLiveTaskId() === id
    },

    syncExtractUiToCurrentScope() {
      get().persistExtractUiForScopeKey(get().step3GenVisualScopeKey())
    },

    finishAssetExtractUiForCurrentScope() {
      const key = get().step3GenVisualScopeKey()
      get().setExtractingAssets(false)
      set({
        extractingStage: 'scene',
        extractingStages: { scene: false, character: false, prop: false }
      })
      get().clearExtractingTaskProgress()
      get().setAssetExtractFollowTask(key, null)
      get().clearExtractUiForScopeKey(key)
    },

    // 更新智能体
    updateExtractAgents(agents: ExtractAgents) {
      set({ extractAgents: agents })
    },

    updateExtractModelCodes(codes: { scene?: string; character?: string; prop?: string }) {
      set({
        extractModelCodes: {
          scene: String(codes.scene ?? get().extractModelCodes.scene ?? '').trim(),
          character: String(codes.character ?? get().extractModelCodes.character ?? '').trim(),
          prop: String(codes.prop ?? get().extractModelCodes.prop ?? '').trim()
        }
      })
      get().syncOptionalModelCodesToCurrentScope()
    },

    updateExtractImageModelCodes(codes: { scene?: string; character?: string; prop?: string }) {
      set({
        extractImageModelCodes: {
          scene: String(codes.scene ?? get().extractImageModelCodes.scene ?? '').trim(),
          character: String(codes.character ?? get().extractImageModelCodes.character ?? '').trim(),
          prop: String(codes.prop ?? get().extractImageModelCodes.prop ?? '').trim()
        }
      })
      get().syncOptionalModelCodesToCurrentScope()
    },

    persistOptionalModelCodesForScopeKey(scopeKey: string) {
      if (!scopeKey || scopeKey.startsWith('0:')) return
      set({
        optionalModelCodesByScope: {
          ...get().optionalModelCodesByScope,
          [scopeKey]: snapshotOptionalModelCodesFromStore(get())
        }
      })
    },

    applyOptionalModelCodesFromScopeKey(scopeKey: string) {
      const snap =
        get().optionalModelCodesByScope[scopeKey] ?? emptyOptionalModelCodesScopeSnapshot()
      const draft = {
        extractModelCodes: { ...get().extractModelCodes },
        extractImageModelCodes: { ...get().extractImageModelCodes },
        storyboardAgent: { ...get().storyboardAgent },
        storyboardGenerateSettings: { ...get().storyboardGenerateSettings },
        storyboardStylistGenerateSettings: { ...get().storyboardStylistGenerateSettings }
      }
      applyOptionalModelCodesToStore(draft, snap)
      set(draft)
    },

    persistStoryboardVideoSettingsForScopeKey(scopeKey: string) {
      if (!scopeKey || scopeKey.startsWith('0:')) return
      set({
        storyboardVideoSettingsByScope: {
          ...get().storyboardVideoSettingsByScope,
          [scopeKey]: snapshotStoryboardVideoSettingsFromStore(get())
        }
      })
    },

    applyStoryboardVideoSettingsFromScopeKey(scopeKey: string) {
      const snap =
        get().storyboardVideoSettingsByScope[scopeKey] ??
        emptyStoryboardVideoSettingsScopeSnapshot()
      const draft = {
        storyboardVideoAgent: { ...get().storyboardVideoAgent },
        storyboardVideoGenerateSettings: { ...get().storyboardVideoGenerateSettings }
      }
      applyStoryboardVideoSettingsToStore(draft, snap)
      set(draft)
    },

    syncStoryboardVideoSettingsToCurrentScope() {
      const pid =
        get().currentProjectId != null && Number.isFinite(Number(get().currentProjectId))
          ? Number(get().currentProjectId)
          : 0
      if (!(pid > 0)) return
      get().persistStoryboardVideoSettingsForScopeKey(get().step3GenVisualScopeKey())
    },

    syncOptionalModelCodesToCurrentScope() {
      const pid =
        get().currentProjectId != null && Number.isFinite(Number(get().currentProjectId))
          ? Number(get().currentProjectId)
          : 0
      if (!(pid > 0)) return
      get().persistOptionalModelCodesForScopeKey(get().step3GenVisualScopeKey())
    },

    // 场景图片相关
    setSceneImages(sceneIndex: number, images: SceneImage[]) {
      set({ sceneImages: { ...get().sceneImages, [sceneIndex]: images } })
    },

    addSceneImage(sceneIndex: number, image: SceneImage) {
      const list = get().sceneImages[sceneIndex] ? [...get().sceneImages[sceneIndex]] : []
      list.push(image)
      set({ sceneImages: { ...get().sceneImages, [sceneIndex]: list } })
    },

    // 角色图片相关
    setCharacterImages(characterIndex: number, images: CharacterImage[]) {
      set({ characterImages: { ...get().characterImages, [characterIndex]: images } })
    },

    addCharacterImage(characterIndex: number, image: CharacterImage) {
      const list = get().characterImages[characterIndex]
        ? [...get().characterImages[characterIndex]]
        : []
      list.push(image)
      set({ characterImages: { ...get().characterImages, [characterIndex]: list } })
    },

    // 道具图片相关
    setPropImages(propIndex: number, images: CharacterImage[]) {
      set({ propImages: { ...get().propImages, [propIndex]: images } })
    },

    addPropImage(propIndex: number, image: CharacterImage) {
      const list = get().propImages[propIndex] ? [...get().propImages[propIndex]] : []
      list.push(image)
      set({ propImages: { ...get().propImages, [propIndex]: list } })
    },

    // 角色形态图片相关
    setCharacterFormImages(key: string, images: CharacterImage[]) {
      set({ characterFormImages: { ...get().characterFormImages, [key]: images } })
    },

    // 道具形态图片相关
    setPropFormImages(key: string, images: CharacterImage[]) {
      set({ propFormImages: { ...get().propFormImages, [key]: images } })
    },

    // 手动添加标记
    addManualScene(index: number) {
      if (!get().manualScenes.includes(index)) {
        set({ manualScenes: [...get().manualScenes, index] })
      }
    },

    removeManualScene(index: number) {
      set({ manualScenes: get().manualScenes.filter((i) => i !== index) })
    },

    addManualSceneAssetId(assetId: number) {
      const id = Number(assetId)
      if (!Number.isFinite(id) || id <= 0) return
      if (!get().manualSceneAssetIds.includes(id)) {
        set({ manualSceneAssetIds: [...get().manualSceneAssetIds, id] })
      }
    },

    removeManualSceneAssetId(assetId: number) {
      const id = Number(assetId)
      if (!Number.isFinite(id)) return
      set({ manualSceneAssetIds: get().manualSceneAssetIds.filter((x) => x !== id) })
    },

    addManualCharacter(index: number) {
      if (!get().manualCharacters.includes(index)) {
        set({ manualCharacters: [...get().manualCharacters, index] })
      }
    },

    removeManualCharacter(index: number) {
      set({ manualCharacters: get().manualCharacters.filter((i) => i !== index) })
    },

    addManualProp(index: number) {
      if (!get().manualProps.includes(index)) {
        set({ manualProps: [...get().manualProps, index] })
      }
    },

    removeManualProp(index: number) {
      set({ manualProps: get().manualProps.filter((i) => i !== index) })
    },

    addManualStoryboard(storyboardId: number) {
      const id = Number(storyboardId)
      if (!Number.isFinite(id) || id <= 0) return
      if (!get().manualStoryboardIds.includes(id)) {
        set({ manualStoryboardIds: [...get().manualStoryboardIds, id] })
      }
    },

    removeManualStoryboard(storyboardId: number) {
      const id = Number(storyboardId)
      if (!Number.isFinite(id)) return
      set({ manualStoryboardIds: get().manualStoryboardIds.filter((x) => x !== id) })
    },

    pruneManualStoryboardIds(validIds: number[]) {
      const set_ = new Set(validIds.filter((id) => Number.isFinite(id) && id > 0))
      set({ manualStoryboardIds: get().manualStoryboardIds.filter((id) => set_.has(id)) })
    },

    isManualStoryboard(storyboardId: number): boolean {
      const id = Number(storyboardId)
      if (!Number.isFinite(id) || id <= 0) return false
      return get().manualStoryboardIds.includes(id)
    },

    // 角色形态
    setCharacterForms(characterIndex: number, forms: Array<{ name: string; voiceover?: string }>) {
      set({ characterForms: { ...get().characterForms, [characterIndex]: forms } })
    },

    // 道具形态
    setPropForms(propIndex: number, forms: Array<{ name: string }>) {
      set({ propForms: { ...get().propForms, [propIndex]: forms } })
    }
  }
}
