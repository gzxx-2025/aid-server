import { resolveStoryboardPanelCoverImage } from '~/utils/storyboardImageCover'
import { isComposeStoryboardVideoRecord } from '~/utils/storyboardRecordRow'
import { getPanelStoryboardVideoUrl } from '~/utils/storyboardVideoCover'
import { isPanelDubbingConfigured } from './helpers'
import type { DubbingGenItem,DubbingModalCtx,DubbingNavEntry } from './types'
import { navKeyLoading,navKeySource } from './types'

export function resolveDubbingPanelKey(ctx: DubbingModalCtx, index: number): string {
  const p = ctx.props().dubbingPanels[index]
  const id = String(p?.id || '').trim()
  return id || `idx-${index}`
}

export function resolveStoryboardIdForIndex(ctx: DubbingModalCtx, i: number): number | null {
  const { storyboardScriptPanels, storyboardVideoPanels } = ctx.props()
  const raw = storyboardScriptPanels[i]?.id ?? storyboardVideoPanels[i]?.id
  const id = Number(raw)
  return Number.isFinite(id) && id > 0 ? id : null
}

export function getVideoUrl(ctx: DubbingModalCtx, index: number): string {
  const panel = ctx.props().storyboardVideoPanels[index]
  const localUrl = getPanelStoryboardVideoUrl(panel)
  if (localUrl) return localUrl
  const scriptPanel = ctx.props().storyboardScriptPanels[index]
  const fromList = String(scriptPanel?.finalVideoUrl ?? '').trim()
  if (fromList) return fromList
  return ''
}

export function resolveDubbingCoverImageUrl(ctx: DubbingModalCtx, index: number): string {
  const sp = ctx.props().storyboardScriptPanels[index]
  const cover = resolveStoryboardPanelCoverImage({
    images: sp?.images,
    finalImageUrl: sp?.finalImageUrl
  })
  return String(cover?.thumbnail || cover?.url || '').trim()
}

export function getGenHistoryForScene(ctx: DubbingModalCtx): DubbingGenItem[] {
  const S = ctx.state
  return S.genHistoryByIndex.get()[S.currentSceneIndex.get()] || []
}

export function getGenLoadingForScene(ctx: DubbingModalCtx): boolean {
  const S = ctx.state
  return !!S.genLoadingByPanelKey.get()[resolveDubbingPanelKey(ctx, S.currentSceneIndex.get())]
}

export function getLipSyncProgressHint(ctx: DubbingModalCtx): string {
  const S = ctx.state
  return String(S.lipSyncProgressHintByIndex.get()[S.currentSceneIndex.get()] || '').trim()
}

/** 当前场景是否处于批量生成中（来自父组件），用于与弹窗内「开始配音」的 loading 一并展示 */
export function isCurrentSceneBatchGenerating(ctx: DubbingModalCtx): boolean {
  const list = ctx.props().batchGeneratingIndices || []
  return list.includes(ctx.state.currentSceneIndex.get())
}

/** 某分镜是否正在生成（弹窗内开始配音 or 父组件批量生成） */
export function isSceneGenerating(ctx: DubbingModalCtx, index: number): boolean {
  return (
    !!ctx.state.genLoadingByPanelKey.get()[resolveDubbingPanelKey(ctx, index)] ||
    (ctx.props().batchGeneratingIndices || []).includes(index)
  )
}

export function getGeneratingCardTitle(ctx: DubbingModalCtx): string {
  const S = ctx.state
  const m = S.generatingMetaByIndex.get()[S.currentSceneIndex.get()]
  if (!m) return '文本朗读 | 配音'
  return `文本朗读 | 配音 ${m.voice} ${m.emotion} ${m.timeLabel}`
}

/** 加载卡片标题：弹窗内生成用 generatingCardTitle，批量生成用「正在生成中...」 */
export function getLoadingCardTitle(ctx: DubbingModalCtx): string {
  const S = ctx.state
  if (
    isCurrentSceneBatchGenerating(ctx) &&
    !S.generatingMetaByIndex.get()[S.currentSceneIndex.get()]
  ) {
    return '正在生成中...'
  }
  return getGeneratingCardTitle(ctx)
}

export function getUploadPendingActive(ctx: DubbingModalCtx): boolean {
  const S = ctx.state
  const i = S.currentSceneIndex.get()
  return !!S.pendingDubbingByIndex.get()[i] && S.pendingPayloadByIndex.get()[i]?.mode === 'upload'
}

export function isCurrentPanelConfigured(ctx: DubbingModalCtx): boolean {
  return isPanelDubbingConfigured(ctx.props().dubbingPanels[ctx.state.currentSceneIndex.get()])
}

/** 当前分镜已设为音画同步结果的条目 key：__source__ 表示原分镜视频，否则为生成项 id，用于仅一条显示「取消设置」 */
export function getCurrentPanelLipSyncKey(ctx: DubbingModalCtx): string | null {
  const S = ctx.state
  const i = S.currentSceneIndex.get()
  const p = ctx.props().dubbingPanels[i]
  const key = p?.dubbingLipSyncKey
  if (key != null && String(key).trim() !== '') {
    const k = String(key).trim()
    return k === '__source__' ? navKeySource : k
  }
  const url = p?.dubbingLipSyncVideoUrl
  if (url && String(url).trim()) {
    if (getCurrentVideoUrl(ctx) === url) return navKeySource
    const gen = getGenHistoryForScene(ctx).find((item) => item.url === url)
    if (gen) return gen.id
  }
  // 服务端配音轨 isSelected=1 的 compose 即「使用中的音画同步结果」
  const serverList = S.serverVideoRecordsByIndex.get()[i] || []
  const activeCompose = serverList.find(
    (r) => isComposeStoryboardVideoRecord(r._serverRow) && r._serverRow?.isSelected === 1
  )
  if (activeCompose?.url) {
    const hit = getGenHistoryForScene(ctx).find((item) => item.url === activeCompose.url)
    if (hit) return hit.id
    const rid = Number(activeCompose.id || activeCompose._serverRow?.id)
    if (Number.isFinite(rid) && rid > 0) return `compose-${rid}`
  }
  return null
}

/** 从生成历史项解析 compose 生成记录 id（setFinal / unSetFinalVideo 入参） */
export function resolveComposeRecordIdFromGenItem(
  ctx: DubbingModalCtx,
  item: DubbingGenItem | null | undefined,
  sceneIdx: number
): number | null {
  if (!item) return null
  const idStr = String(item.id || '').trim()
  const fromCompose = /^compose-(\d+)$/.exec(idStr)
  if (fromCompose) {
    const n = Number(fromCompose[1])
    if (Number.isFinite(n) && n > 0) return n
  }
  // 批量配音历史 id：batch-{dubbedVideoRecordId}-{index}
  const fromBatch = /^batch-(\d+)(?:-|$)/.exec(idStr)
  if (fromBatch) {
    const n = Number(fromBatch[1])
    if (Number.isFinite(n) && n > 0) return n
  }
  const plain = Number(idStr)
  if (Number.isFinite(plain) && plain > 0 && !idStr.includes('-')) return plain

  const url = String(item.url || '').trim()
  if (!url) return null
  const serverList = ctx.state.serverVideoRecordsByIndex.get()[sceneIdx] || []
  const hit = serverList.find(
    (r) => isComposeStoryboardVideoRecord(r._serverRow) && String(r.url || '').trim() === url
  )
  const rid = Number(hit?.id || hit?._serverRow?.id)
  return Number.isFinite(rid) && rid > 0 ? rid : null
}

export function resolveActiveComposeRecordId(
  ctx: DubbingModalCtx,
  sceneIdx: number
): number | null {
  const S = ctx.state
  const serverList = S.serverVideoRecordsByIndex.get()[sceneIdx] || []
  const active = serverList.find(
    (r) => isComposeStoryboardVideoRecord(r._serverRow) && r._serverRow?.isSelected === 1
  )
  const rid = Number(active?.id || active?._serverRow?.id)
  if (Number.isFinite(rid) && rid > 0) return rid

  const p = ctx.props().dubbingPanels[sceneIdx]
  const key = String(p?.dubbingLipSyncKey || '').trim()
  const fromKey = /^compose-(\d+)$/.exec(key)
  if (fromKey) {
    const n = Number(fromKey[1])
    if (Number.isFinite(n) && n > 0) return n
  }
  if (key && key !== navKeySource && key !== '__source__') {
    const hist = S.genHistoryByIndex.get()[sceneIdx] || []
    return resolveComposeRecordIdFromGenItem(
      ctx,
      hist.find((h) => h.id === key),
      sceneIdx
    )
  }
  const url = String(p?.dubbingLipSyncVideoUrl || '').trim()
  if (!url) return null
  return resolveComposeRecordIdFromGenItem(
    ctx,
    (S.genHistoryByIndex.get()[sceneIdx] || []).find((h) => h.url === url),
    sceneIdx
  )
}

export function resolveNavEntryUrl(
  ctx: DubbingModalCtx,
  nav: DubbingNavEntry | null | undefined
): string {
  if (!nav) return ''
  if (nav.url) return String(nav.url).trim()
  if (nav.key === navKeySource) return String(getCurrentVideoUrl(ctx) || '').trim()
  const item = getGenHistoryForScene(ctx).find((x) => x.id === nav.key)
  return String(item?.url || '').trim()
}

/** 当前选中预览项是否已是音画同步结果（按 key 或同 URL 判定） */
export function isNavLipSyncMain(ctx: DubbingModalCtx, navKey: string): boolean {
  const main = getCurrentPanelLipSyncKey(ctx)
  if (!main) return false
  if (main === navKey) return true
  const entries = getRightNavEntries(ctx)
  const mainEntry = entries.find((e) => e.key === main)
  const navEntry = entries.find((e) => e.key === navKey)
  const mainUrl =
    resolveNavEntryUrl(ctx, mainEntry) ||
    String(
      ctx.props().dubbingPanels[ctx.state.currentSceneIndex.get()]?.dubbingLipSyncVideoUrl || ''
    ).trim()
  const navUrl = resolveNavEntryUrl(ctx, navEntry)
  return !!(mainUrl && navUrl && mainUrl === navUrl)
}

export function isHistoryDubbingMain(ctx: DubbingModalCtx, nav: DubbingNavEntry): boolean {
  if (nav.type === 'loading') return false
  return isNavLipSyncMain(ctx, nav.key)
}

export function canSetMainFromHistory(ctx: DubbingModalCtx, nav: DubbingNavEntry): boolean {
  if (nav.type !== 'gen') return false
  if (!String(nav.url || '').trim()) return false
  return !isHistoryDubbingMain(ctx, nav)
}

export function canDeleteHistoryDubbing(ctx: DubbingModalCtx, nav: DubbingNavEntry): boolean {
  if (nav.type !== 'gen' || ctx.state.isDeletingDubbingRecord.get()) return false
  const item = getGenHistoryForScene(ctx).find((x) => x.id === nav.key)
  if (!item) return false
  if (resolveComposeRecordIdFromGenItem(ctx, item, ctx.state.currentSceneIndex.get())) return true
  return !!String(item.url || '').trim()
}

export function isSelectedNavLipSyncMain(ctx: DubbingModalCtx): boolean {
  const k = getSelectedNavKey(ctx)
  if (!k || k === navKeyLoading) return false
  return isNavLipSyncMain(ctx, k)
}

export function getCurrentVideoUrl(ctx: DubbingModalCtx): string {
  return getVideoUrl(ctx, ctx.state.currentSceneIndex.get())
}

/** 当前场景是否显示「正在生成中」卡片（弹窗内开始配音 or 父组件批量生成） */
export function getShowLoadingCardForScene(ctx: DubbingModalCtx): boolean {
  return !!getGenLoadingForScene(ctx) || !!isCurrentSceneBatchGenerating(ctx)
}

export function getRightNavEntries(ctx: DubbingModalCtx): DubbingNavEntry[] {
  const entries: DubbingNavEntry[] = []
  for (const item of getGenHistoryForScene(ctx)) {
    if (!String(item.url || '').trim()) continue
    entries.push({ key: item.id, type: 'gen', url: item.url })
  }
  if (getShowLoadingCardForScene(ctx)) {
    entries.push({ key: navKeyLoading, type: 'loading' })
  }
  return entries
}

/** 中间预览为配音合成/对口型记录时，展示「设置为音画同步结果」操作区 */
export function getShowSetLipSyncActions(ctx: DubbingModalCtx): boolean {
  const k = getSelectedNavKey(ctx)
  if (!k || k === navKeyLoading || k === navKeySource) return false
  if (!String(getDubbingPreviewUrl(ctx) || '').trim()) return false
  return getGenHistoryForScene(ctx).some((item) => item.id === k)
}

export function getSelectedNavKey(ctx: DubbingModalCtx): string {
  const S = ctx.state
  const k = S.selectedNavKeyByIndex.get()[S.currentSceneIndex.get()]
  return k || navKeySource
}

/** 中间栏预览：与左侧「生成记录」选中项一致 */
export function getDubbingCanvasMode(ctx: DubbingModalCtx): 'empty' | 'loading' | 'preview' {
  if (!getCurrentVideoUrl(ctx)) return 'empty'
  if (getSelectedNavKey(ctx) === navKeyLoading && getShowLoadingCardForScene(ctx)) return 'loading'
  return 'preview'
}

export function getDubbingPreviewUrl(ctx: DubbingModalCtx): string {
  const k = getSelectedNavKey(ctx)
  if (k === navKeyLoading) return ''
  if (k === navKeySource) return getCurrentVideoUrl(ctx) || ''
  const item = getGenHistoryForScene(ctx).find((x) => x.id === k)
  return item?.url || ''
}

export function getDubbingPreviewTitle(ctx: DubbingModalCtx): string {
  const k = getSelectedNavKey(ctx)
  if (k === navKeyLoading) return getLoadingCardTitle(ctx)
  if (k === navKeySource) return '分镜视频'
  const item = getGenHistoryForScene(ctx).find((x) => x.id === k)
  return item?.title || '配音生成'
}

export function onRightNavClick(ctx: DubbingModalCtx, key: string) {
  const S = ctx.state
  const i = S.currentSceneIndex.get()
  ctx.resetHeroVideoPreviewState()
  S.selectedNavKeyByIndex.set({ ...S.selectedNavKeyByIndex.get(), [i]: key })
}
