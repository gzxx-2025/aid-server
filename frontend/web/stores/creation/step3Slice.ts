import type { CreationGet,CreationSet } from './state'
import {
liveGenScopeKeyFromIds,
scopeKeyLegacyAliases,
type PendingExtractFormAssetItem,
type SceneGenerationStatus,
type SceneModalSseTaskSnapshot,
type Step3GenVisualScopeMaps
} from './types'

export interface Step3Actions {
  /** `${projectId}:${episodeId|null}`；切换路由前应用 lastStep3VisualScopeKey 写入上一作品快照 */
  step3GenVisualScopeKey: () => string
  /** 将当前内存中的第三步生成 UI 同步进当前作品 scope（供持久化与切换恢复） */
  syncStep3GenVisualToCurrentScope: () => void
  applyStep3GenVisualFromScopeKey: (scopeKey: string) => void
  writeStep3GenVisualScopeKey: (scopeKey: string, maps: Step3GenVisualScopeMaps) => void
  refreshStep3VisualGeneratingFlag: () => void
  /**
   * @param taskId 传入时按任务幂等：同一 taskId 多次 begin 不计两次；切 Tab 断线重连不会叠高计数。
   */
  beginStep3FormImageTaskFollow: (taskId?: number | null) => void
  /**
   * @param taskId 传入时按任务幂等：未 begin 过或已 end 的 taskId 不再递减。
   */
  endStep3FormImageTaskFollow: (taskId?: number | null) => void
  /** 形态图任务结束且不在第三步页面时，将仍标为 generating 的卡片回落为 success/idle */
  resolveAllStep3GeneratingStatuses: (target: SceneGenerationStatus) => void
  /** 场景生成状态 */
  setSceneGenerationStatus: (sceneIndex: number, status: SceneGenerationStatus) => void
  /** 角色形态生成状态 */
  setCharacterFormGenerationStatus: (formKey: string, status: SceneGenerationStatus) => void
  /** 道具形态生成状态 */
  setPropFormGenerationStatus: (formKey: string, status: SceneGenerationStatus) => void
  setSceneModalSseTask: (
    editorScopeKey: string,
    snapshot: SceneModalSseTaskSnapshot,
    scopeKey?: string
  ) => void
  clearSceneModalSseTask: (editorScopeKey: string, scopeKey?: string) => void
  getSceneModalSseTask: (
    editorScopeKey: string,
    scopeKey?: string
  ) => SceneModalSseTaskSnapshot | null
  /**
   * 当前 scope 未命中时按 null/0 历史别名查找（刷新后 scope 键短暂不一致的兜底）。
   * 剧集隔离：editorScopeKey 是索引型键（scene-0 / 0-1），不全局唯一，
   * 禁止跨 episode/作品桶枚举，否则会把他集同名键的弹窗任务恢复到本集。
   */
  findSceneModalSseTaskAcrossScopes: (editorScopeKey: string) => SceneModalSseTaskSnapshot | null
  setGeneratingStep3Visual: (flag: boolean) => void
  setPendingExtractFormAssets: (items: PendingExtractFormAssetItem[]) => void
  mergePendingExtractFormAssets: (items: PendingExtractFormAssetItem[]) => void
  removePendingExtractFormAsset: (
    assetId: number,
    assetType?: PendingExtractFormAssetItem['assetType']
  ) => void
  patchPendingExtractFormAssetTitle: (
    assetId: number,
    assetType: PendingExtractFormAssetItem['assetType'],
    title: string
  ) => void
  clearPendingExtractFormAssets: () => void
}

export function createStep3Slice(set: CreationSet, get: CreationGet): Step3Actions {
  return {
    /** `${projectId}:${episodeId|null}`；切换路由前应用 lastStep3VisualScopeKey 写入上一作品快照 */
    step3GenVisualScopeKey(): string {
      return liveGenScopeKeyFromIds(get().currentProjectId, get().currentEpisodeId)
    },

    /** 将当前内存中的第三步生成 UI 同步进当前作品 scope（供持久化与切换恢复） */
    syncStep3GenVisualToCurrentScope() {
      const pid =
        get().currentProjectId != null && Number.isFinite(Number(get().currentProjectId))
          ? Number(get().currentProjectId)
          : 0
      if (!(pid > 0)) return
      const key = get().step3GenVisualScopeKey()
      const prev = get().step3GenVisualByScope[key]
      set({
        step3GenVisualByScope: {
          ...get().step3GenVisualByScope,
          [key]: {
            scene: { ...get().sceneGenerationStatus },
            character: { ...get().characterFormGenerationStatus },
            prop: { ...get().propFormGenerationStatus },
            modalSseTasks: { ...(prev?.modalSseTasks || {}) }
          }
        }
      })
    },

    applyStep3GenVisualFromScopeKey(scopeKey: string) {
      const blob = get().step3GenVisualByScope[scopeKey]
      set({
        sceneGenerationStatus: blob?.scene ? { ...blob.scene } : {},
        characterFormGenerationStatus: blob?.character ? { ...blob.character } : {},
        propFormGenerationStatus: blob?.prop ? { ...blob.prop } : {}
      })
    },

    writeStep3GenVisualScopeKey(scopeKey: string, maps: Step3GenVisualScopeMaps) {
      if (!scopeKey) return
      const prev = get().step3GenVisualByScope[scopeKey]
      set({
        step3GenVisualByScope: {
          ...get().step3GenVisualByScope,
          [scopeKey]: {
            scene: { ...(maps.scene || {}) },
            character: { ...(maps.character || {}) },
            prop: { ...(maps.prop || {}) },
            modalSseTasks: {
              ...(maps.modalSseTasks || prev?.modalSseTasks || {})
            }
          }
        }
      })
    },

    refreshStep3VisualGeneratingFlag() {
      const fromMaps =
        Object.values(get().sceneGenerationStatus).some((s) => s === 'generating') ||
        Object.values(get().characterFormGenerationStatus).some((s) => s === 'generating') ||
        Object.values(get().propFormGenerationStatus).some((s) => s === 'generating')
      const scopeKey = get().step3GenVisualScopeKey()
      const fromModal = scopeKeyLegacyAliases(scopeKey).some((alias) => {
        const blob = get().step3GenVisualByScope[alias]
        return Object.keys(blob?.modalSseTasks || {}).length > 0
      })
      // follow 计数由 begin/endStep3FormImageTaskFollow 成对维护。
      // 形态文案（form_generate）故意不写 generating map，只靠计数驱动角标/流程条；
      // 不可在此处因 !fromMaps 强行清零，否则 SSE 一开始图标就不会出现。
      // 卡住清理请走 finish/reset 等显式入口。
      set({
        isGeneratingStep3Visual:
          fromMaps || get().step3FormImageTaskFollowCount > 0 || fromModal
      })
    },

    /**
     * @param taskId 传入时按任务幂等：同一 taskId 多次 begin 不计两次；切 Tab 断线重连不会叠高计数。
     */
    beginStep3FormImageTaskFollow(taskId?: number | null) {
      const tid = Number(taskId)
      if (Number.isFinite(tid) && tid > 0) {
        if (get().step3FormImageTaskFollowTaskIds.includes(tid)) {
          get().refreshStep3VisualGeneratingFlag()
          return
        }
        set({
          step3FormImageTaskFollowTaskIds: [...get().step3FormImageTaskFollowTaskIds, tid]
        })
      }
      set({ step3FormImageTaskFollowCount: get().step3FormImageTaskFollowCount + 1 })
      get().refreshStep3VisualGeneratingFlag()
    },

    /**
     * @param taskId 传入时按任务幂等：未 begin 过或已 end 的 taskId 不再递减。
     */
    endStep3FormImageTaskFollow(taskId?: number | null) {
      const tid = Number(taskId)
      if (Number.isFinite(tid) && tid > 0) {
        if (!get().step3FormImageTaskFollowTaskIds.includes(tid)) {
          get().refreshStep3VisualGeneratingFlag()
          return
        }
        set({
          step3FormImageTaskFollowTaskIds: get().step3FormImageTaskFollowTaskIds.filter(
            (id) => id !== tid
          )
        })
      }
      if (get().step3FormImageTaskFollowCount > 0) {
        set({ step3FormImageTaskFollowCount: get().step3FormImageTaskFollowCount - 1 })
      }
      get().refreshStep3VisualGeneratingFlag()
    },

    /** 形态图任务结束且不在第三步页面时，将仍标为 generating 的卡片回落为 success/idle */
    resolveAllStep3GeneratingStatuses(target: SceneGenerationStatus) {
      let changed = false
      const sceneNext = { ...get().sceneGenerationStatus }
      for (const [k, st] of Object.entries(sceneNext)) {
        if (st !== 'generating') continue
        const idx = Number(k)
        if (!Number.isFinite(idx)) continue
        sceneNext[idx] = target
        changed = true
      }
      const charNext = { ...get().characterFormGenerationStatus }
      for (const [key, st] of Object.entries(charNext)) {
        if (st !== 'generating') continue
        charNext[key] = target
        changed = true
      }
      const propNext = { ...get().propFormGenerationStatus }
      for (const [key, st] of Object.entries(propNext)) {
        if (st !== 'generating') continue
        propNext[key] = target
        changed = true
      }
      if (changed) {
        set({
          sceneGenerationStatus: sceneNext,
          characterFormGenerationStatus: charNext,
          propFormGenerationStatus: propNext
        })
        get().syncStep3GenVisualToCurrentScope()
      }
      get().refreshStep3VisualGeneratingFlag()
    },

    // 场景生成状态
    setSceneGenerationStatus(sceneIndex: number, status: SceneGenerationStatus) {
      set({
        sceneGenerationStatus: { ...get().sceneGenerationStatus, [sceneIndex]: status }
      })
      get().syncStep3GenVisualToCurrentScope()
      get().refreshStep3VisualGeneratingFlag()
    },

    // 角色形态生成状态
    setCharacterFormGenerationStatus(formKey: string, status: SceneGenerationStatus) {
      set({
        characterFormGenerationStatus: {
          ...get().characterFormGenerationStatus,
          [formKey]: status
        }
      })
      get().syncStep3GenVisualToCurrentScope()
      get().refreshStep3VisualGeneratingFlag()
    },

    // 道具形态生成状态
    setPropFormGenerationStatus(formKey: string, status: SceneGenerationStatus) {
      set({
        propFormGenerationStatus: { ...get().propFormGenerationStatus, [formKey]: status }
      })
      get().syncStep3GenVisualToCurrentScope()
      get().refreshStep3VisualGeneratingFlag()
    },

    setSceneModalSseTask(
      editorScopeKey: string,
      snapshot: SceneModalSseTaskSnapshot,
      scopeKey?: string
    ) {
      const scope = String(editorScopeKey || '').trim()
      const tid = Number(snapshot.taskId)
      if (!scope || !Number.isFinite(tid) || tid <= 0) return
      const key = scopeKey || get().step3GenVisualScopeKey()
      const prev = get().step3GenVisualByScope[key] || {
        scene: {},
        character: {},
        prop: {},
        modalSseTasks: {}
      }
      set({
        step3GenVisualByScope: {
          ...get().step3GenVisualByScope,
          [key]: {
            scene: { ...(prev.scene || {}) },
            character: { ...(prev.character || {}) },
            prop: { ...(prev.prop || {}) },
            modalSseTasks: {
              ...(prev.modalSseTasks || {}),
              [scope]: { ...snapshot, editorScopeKey: scope }
            }
          }
        }
      })
      get().refreshStep3VisualGeneratingFlag()
    },

    clearSceneModalSseTask(editorScopeKey: string, scopeKey?: string) {
      const scope = String(editorScopeKey || '').trim()
      if (!scope) return
      const key = scopeKey || get().step3GenVisualScopeKey()
      const prev = get().step3GenVisualByScope[key]
      if (!prev?.modalSseTasks?.[scope]) return
      const next = { ...(prev.modalSseTasks || {}) }
      delete next[scope]
      set({
        step3GenVisualByScope: {
          ...get().step3GenVisualByScope,
          [key]: {
            scene: { ...(prev.scene || {}) },
            character: { ...(prev.character || {}) },
            prop: { ...(prev.prop || {}) },
            modalSseTasks: next
          }
        }
      })
      get().refreshStep3VisualGeneratingFlag()
    },

    getSceneModalSseTask(
      editorScopeKey: string,
      scopeKey?: string
    ): SceneModalSseTaskSnapshot | null {
      const scope = String(editorScopeKey || '').trim()
      if (!scope) return null
      const key = scopeKey || get().step3GenVisualScopeKey()
      const hit = get().step3GenVisualByScope[key]?.modalSseTasks?.[scope]
      if (!hit) return null
      const tid = Number(hit.taskId)
      if (!Number.isFinite(tid) || tid <= 0) return null
      return hit
    },

    /**
     * 当前 scope 未命中时按 null/0 历史别名查找（刷新后 scope 键短暂不一致的兜底）。
     * 剧集隔离：editorScopeKey 是索引型键（scene-0 / 0-1），不全局唯一，
     * 禁止跨 episode/作品桶枚举，否则会把他集同名键的弹窗任务恢复到本集。
     */
    findSceneModalSseTaskAcrossScopes(editorScopeKey: string): SceneModalSseTaskSnapshot | null {
      const scope = String(editorScopeKey || '').trim()
      if (!scope) return null
      const direct = get().getSceneModalSseTask(scope)
      if (direct) return direct
      for (const alias of scopeKeyLegacyAliases(get().step3GenVisualScopeKey())) {
        const hit = get().step3GenVisualByScope[alias]?.modalSseTasks?.[scope]
        const tid = Number(hit?.taskId)
        if (hit && Number.isFinite(tid) && tid > 0) return hit
      }
      return null
    },

    setGeneratingStep3Visual(flag: boolean) {
      if (flag) {
        get().beginStep3FormImageTaskFollow()
      } else {
        set({ step3FormImageTaskFollowCount: 0, step3FormImageTaskFollowTaskIds: [] })
        get().refreshStep3VisualGeneratingFlag()
      }
    },

    setPendingExtractFormAssets(items: PendingExtractFormAssetItem[]) {
      set({ pendingExtractFormAssets: Array.isArray(items) ? [...items] : [] })
    },

    mergePendingExtractFormAssets(items: PendingExtractFormAssetItem[]) {
      const key = (x: PendingExtractFormAssetItem) => `${x.assetType}:${x.assetId}`
      const map = new Map<string, PendingExtractFormAssetItem>()
      for (const x of get().pendingExtractFormAssets) map.set(key(x), x)
      for (const x of items ?? []) map.set(key(x), x)
      set({ pendingExtractFormAssets: Array.from(map.values()) })
    },

    removePendingExtractFormAsset(
      assetId: number,
      assetType?: PendingExtractFormAssetItem['assetType']
    ) {
      set({
        pendingExtractFormAssets: get().pendingExtractFormAssets.filter(
          (x) => !(x.assetId === assetId && (!assetType || x.assetType === assetType))
        )
      })
    },

    patchPendingExtractFormAssetTitle(
      assetId: number,
      assetType: PendingExtractFormAssetItem['assetType'],
      title: string
    ) {
      const idx = get().pendingExtractFormAssets.findIndex(
        (x) => x.assetId === assetId && x.assetType === assetType
      )
      if (idx < 0) return
      const next = [...get().pendingExtractFormAssets]
      next[idx] = { ...next[idx], title }
      set({ pendingExtractFormAssets: next })
    },

    clearPendingExtractFormAssets() {
      set({ pendingExtractFormAssets: [] })
    }
  }
}
