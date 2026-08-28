'use client'

import type { UserAssetRpsRow } from '~/types/business-api'
import { resolveProjectIdFromRouteAndStore } from '~/utils/storyScriptSaveContext'
import type { Mirrored } from './mirrored'
import { createScpAssetTabLoader } from './scpAssetTabLoader'
import {
assetHasPersistedForm,
} from './scpRowUtils'
import type { CharacterFormItem,PropFormItem,ScpCtx,TabKey } from './types'
import {
  settingEditorStateFromRpsRow,
  type RpsSettingEditorState
} from './scpSettingPromptUtils'

export interface ScpAssetLoadApi {
  resolveCurrentProjectId: () => number | null
  /** 刷新/重拉列表前：合并 Pinia 与本地 ref 中已记录的手动索引 */
  collectPersistedManualIndices: (kind: 'scene' | 'character' | 'prop', listLength: number) => Set<number>
  /** 切换项目/剧集后清空本地资产，避免与其它 Tab 未刷新数据混用 */
  clearPersonalAssetPayload: () => void
  setTabAssetLoading: (tab: TabKey, loading: boolean) => void
  /**
   * 按当前 Tab 的 rps + 形态列表同步「待生成形态」小卡片：
   * - 刷新/重新进入第三步后从服务端恢复无形态资产；
   * - 某资产已生成形态后自动从 pending 移除；
   * - 保留用户侧已有标题（与 merge 行为一致）。
   */
  reconcilePendingExtractForTabFromRps: (tab: TabKey, sortedRps: UserAssetRpsRow[]) => void
  applyRpsSettingsToLocalMaps: (
    variant: TabKey,
    names: string[],
    sortedRps: UserAssetRpsRow[],
    mapRef: Mirrored<Record<string, RpsSettingEditorState>>
  ) => void
  /** 仅请求当前 Tab 对应 assetType，与其它 Tab 的本地数据合并 */
  loadPersonalAssetsForTab: (
    tab: TabKey,
    options?: { allowWhenExtracting?: boolean; background?: boolean }
  ) => Promise<boolean>
  /**
   * 连续切换 Tab 时，较早的 loadPersonalAssetsForTab 会因 loadAssetTabGeneration 不匹配被整段丢弃，
   * 形态 ref 可能短时间与列表行数不一致，先补占位与接口成功后的结构一致，避免只出现「新增形态」的空壳闪烁。
   */
  ensureCharacterFormsPlaceholderForList: () => void
  ensurePropFormsPlaceholderForList: () => void
  ensureFormsPlaceholdersForActiveTab: () => void
  /**
   * 主栏图 → 形态区：仅在服务端已有至少一条形态 id 时迁移，避免 forms=[] 仍出现「形态1 + 图」的幽灵数据。
   * 不在这里注入默认形态（与 rps/list 的 forms 一致，由 addCharacter/addProp 等创建）。
   */
  ensureCharacterPropFormsAndMigrateMainImages: () => void
}

export function useScpAssetLoad(ctx: ScpCtx): ScpAssetLoadApi {
  function resolveCurrentProjectId(): number | null {
    return resolveProjectIdFromRouteAndStore(ctx.store(), ctx.route())
  }

  /** 刷新/重拉列表前：合并 Pinia 与本地 ref 中已记录的手动索引 */
  function collectPersistedManualIndices(
    kind: 'scene' | 'character' | 'prop',
    listLength: number
  ): Set<number> {
    const localSet =
      kind === 'scene'
        ? ctx.manualScenes.get()
        : kind === 'character'
          ? ctx.manualCharacters.get()
          : ctx.manualProps.get()
    const fromStore =
      kind === 'scene'
        ? ctx.store().manualScenes
        : kind === 'character'
          ? ctx.store().manualCharacters
          : ctx.store().manualProps
    const merged = new Set<number>()
    localSet.forEach((i) => {
      if (i >= 0 && i < listLength) merged.add(i)
    })
    ;(fromStore || []).forEach((i) => {
      if (i >= 0 && i < listLength) merged.add(i)
    })
    return merged
  }

  /** 切换项目/剧集后清空本地资产，避免与其它 Tab 未刷新数据混用 */
  function clearPersonalAssetPayload() {
    ctx.manualScenes.set(new Set())
    ctx.manualCharacters.set(new Set())
    ctx.manualProps.set(new Set())
    ctx.patchStore({
      manualScenes: [],
      manualCharacters: [],
      manualProps: [],
      manualSceneAssetIds: []
    })
    ctx.sceneImages.set({})
    ctx.characterImages.set({})
    ctx.propImages.set({})
    ctx.sceneAssetIds.set({})
    ctx.characterAssetIds.set({})
    ctx.propAssetIds.set({})
    ctx.sceneFormIdsByIndex.set({})
    ctx.characterFormIdsByIndex.set({})
    ctx.propFormIdsByIndex.set({})
    ctx.characterForms.set({})
    ctx.propForms.set({})
    ctx.sceneGenerationStatus.set({})
    ctx.characterFormGenerationStatus.set({})
    ctx.propFormGenerationStatus.set({})
    // 勿清空 Pinia generation maps：切集后由 applyStep3GenVisualFromRoute 按 scope 灌回；
    // 直接赋 {} 会在后续 list reconcile 前丢失「仍在生成」态，导致卡片 loading 不恢复。
    ctx.store().clearPendingExtractFormAssets()
    ctx.update({ scenes: [], characters: [], props: [] })
  }

  function setTabAssetLoading(tab: TabKey, loading: boolean) {
    ctx.tabAssetLoading.set({ ...ctx.tabAssetLoading.get(), [tab]: loading })
  }

  /**
   * 按当前 Tab 的 rps + 形态列表同步「待生成形态」小卡片：
   * - 刷新/重新进入第三步后从服务端恢复无形态资产；
   * - 某资产已生成形态后自动从 pending 移除；
   * - 保留用户侧已有标题（与 merge 行为一致）。
   */
  function reconcilePendingExtractForTabFromRps(tab: TabKey, sortedRps: UserAssetRpsRow[]) {
    const need = new Map<number, string>()
    for (const raw of sortedRps) {
      const id = Number(raw.id)
      if (!Number.isFinite(id) || id <= 0) continue
      if (assetHasPersistedForm(raw)) continue
      need.set(id, String(raw.assetName || '').trim() || '未命名')
    }
    const otherTabs = ctx.store().pendingExtractFormAssets.filter((x) => x.assetType !== tab)
    const prevThisTab = ctx.store().pendingExtractFormAssets.filter((x) => x.assetType === tab)
    const nextThisTab: Array<{ assetId: number; assetType: TabKey; title: string }> = []
    for (const [assetId, serverTitle] of need) {
      const prev = prevThisTab.find((x) => x.assetId === assetId)
      nextThisTab.push({
        assetId,
        assetType: tab,
        title: (prev?.title && prev.title.trim()) || serverTitle
      })
    }
    ctx.store().setPendingExtractFormAssets([...otherTabs, ...nextThisTab])
  }

  function applyRpsSettingsToLocalMaps(
    variant: TabKey,
    names: string[],
    sortedRps: UserAssetRpsRow[],
    mapRef: Mirrored<Record<string, RpsSettingEditorState>>
  ) {
    const next = { ...mapRef.get() }
    names.forEach((name, i) => {
      const raw = sortedRps[i]
      if (!raw) return
      next[name] = settingEditorStateFromRpsRow(raw, variant)
    })
    mapRef.set(next)
  }

  /** 仅请求当前 Tab 对应 assetType，与其它 Tab 的本地数据合并 */
  const { loadPersonalAssetsForTab } = createScpAssetTabLoader(ctx, {
    applyRpsSettingsToLocalMaps,
    collectPersistedManualIndices,
    reconcilePendingExtractForTabFromRps,
    setTabAssetLoading
  })

  /**
   * 连续切换 Tab 时，较早的 loadPersonalAssetsForTab 会因 loadAssetTabGeneration 不匹配被整段丢弃，
   * 形态 ref 可能短时间与列表行数不一致，先补占位与接口成功后的结构一致，避免只出现「新增形态」的空壳闪烁。
   */
  function ensureCharacterFormsPlaceholderForList() {
    const n = ctx.localValue.get().characters.length
    if (n === 0) return
    const cur = ctx.characterForms.get()
    let changed = false
    const next: Record<number, CharacterFormItem[]> = { ...cur }
    const blankForm = (): CharacterFormItem => ({
      name: '形态1: 未命名',
      voiceover: undefined,
      voiceoverId: undefined,
      voiceoverAvatarUrl: undefined,
      voiceoverPreviewUrl: undefined,
      canAutoGenerateImage: false,
      createSource: 'manual'
    })
    for (let i = 0; i < n; i++) {
      if (!next[i]?.length) {
        next[i] = [blankForm()]
        changed = true
      }
    }
    if (changed) ctx.characterForms.set(next)
  }

  function ensurePropFormsPlaceholderForList() {
    const n = ctx.localValue.get().props.length
    if (n === 0) return
    const cur = ctx.propForms.get()
    let changed = false
    const next: Record<number, PropFormItem[]> = { ...cur }
    for (let i = 0; i < n; i++) {
      if (!next[i]?.length) {
        next[i] = [{ name: '形态1: 未命名', canAutoGenerateImage: false, createSource: 'manual' }]
        changed = true
      }
    }
    if (changed) ctx.propForms.set(next)
  }

  function ensureFormsPlaceholdersForActiveTab() {
    const tab = ctx.activeTab.get()
    if (tab === 'character') ensureCharacterFormsPlaceholderForList()
    else if (tab === 'prop') ensurePropFormsPlaceholderForList()
  }

  /**
   * 主栏图 → 形态区：仅在服务端已有至少一条形态 id 时迁移，避免 forms=[] 仍出现「形态1 + 图」的幽灵数据。
   * 不在这里注入默认形态（与 rps/list 的 forms 一致，由 addCharacter/addProp 等创建）。
   */
  function ensureCharacterPropFormsAndMigrateMainImages() {
    const nc = ctx.localValue.get().characters.length
    const nextCharFormImgs = { ...ctx.characterFormImages.get() }
    let changed = false
    for (let i = 0; i < nc; i++) {
      const formIds = ctx.characterFormIdsByIndex.get()[i] ?? []
      if (formIds.length === 0) continue
      const key = `${i}-0`
      const main = ctx.characterImages.get()[i]
      const formImgs = ctx.characterFormImages.get()[key]
      if (main?.length && (!formImgs || formImgs.length === 0)) {
        nextCharFormImgs[key] = main.map((x: any) => ({ ...x }))
        changed = true
      }
    }
    const np = ctx.localValue.get().props.length
    const nextPropFormImgs = { ...ctx.propFormImages.get() }
    for (let i = 0; i < np; i++) {
      const formIds = ctx.propFormIdsByIndex.get()[i] ?? []
      if (formIds.length === 0) continue
      const key = `${i}-0`
      const main = ctx.propImages.get()[i]
      const formImgs = ctx.propFormImages.get()[key]
      if (main?.length && (!formImgs || formImgs.length === 0)) {
        nextPropFormImgs[key] = main.map((x: any) => ({ ...x }))
        changed = true
      }
    }
    if (!changed) return
    ctx.characterFormImages.set(nextCharFormImgs)
    ctx.propFormImages.set(nextPropFormImgs)
  }

  return {
    resolveCurrentProjectId,
    collectPersistedManualIndices,
    clearPersonalAssetPayload,
    setTabAssetLoading,
    reconcilePendingExtractForTabFromRps,
    applyRpsSettingsToLocalMaps,
    loadPersonalAssetsForTab,
    ensureCharacterFormsPlaceholderForList,
    ensurePropFormsPlaceholderForList,
    ensureFormsPlaceholdersForActiveTab,
    ensureCharacterPropFormsAndMigrateMainImages
  }
}
