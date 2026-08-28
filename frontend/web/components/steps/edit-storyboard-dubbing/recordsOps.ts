import { message } from 'antd'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardRecordRow } from '~/types/business-api'
import {
resolveDubbingPreviewNavKey,
shouldPreserveDubbingNavSelection
} from '~/utils/resolveDubbingPreviewNavKey'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
isSameDubbingGenHistory,
mergeComposeRecordsIntoDubbingGenHistory
} from '~/utils/storyboardDubbingGenHistory'
import {
fetchProjectStoryboardRecords,
fetchStoryboardRecordsForStoryboard,
groupStoryboardRecordsByStoryboardId
} from '~/utils/storyboardRecordBatch'
import { isComposeStoryboardVideoRecord } from '~/utils/storyboardRecordRow'
import { getPanelStoryboardVideoUrl } from '~/utils/storyboardVideoCover'
import {
getVideoUrl,
resolveDubbingPanelKey,
resolveStoryboardIdForIndex
} from './derived'
import { mapRecordRowToVideoThumb } from './helpers'
import type { DubbingModalCtx } from './types'
import { navKeyLoading,navKeySource } from './types'

export function syncComposeGenHistoryForScene(
  ctx: DubbingModalCtx,
  sceneIdx: number,
  rows: StoryboardRecordRow[]
) {
  const S = ctx.state
  const panel = ctx.props().dubbingPanels[sceneIdx]
  const prev = S.genHistoryByIndex.get()[sceneIdx] ?? [...(panel?.dubbingGenHistory || [])]
  const merged = mergeComposeRecordsIntoDubbingGenHistory(prev, rows, panel)
  if (isSameDubbingGenHistory(prev, merged)) return

  S.genHistoryByIndex.set({ ...S.genHistoryByIndex.get(), [sceneIdx]: merged })
  ctx.emitPanelsUpdate(
    ctx.props().dubbingPanels.map((p, idx) =>
      idx !== sceneIdx ? p : { ...p, dubbingGenHistory: merged }
    )
  )
}

/** 打开弹窗 / 切换分镜后：根据 panel 或 compose 使用中记录恢复右侧选中项 */
export function reconcileSelectedNavKeyForScene(ctx: DubbingModalCtx, sceneIdx: number) {
  const S = ctx.state
  if (S.genLoadingByPanelKey.get()[resolveDubbingPanelKey(ctx, sceneIdx)]) return

  const panel = ctx.props().dubbingPanels[sceneIdx]
  const hist = S.genHistoryByIndex.get()[sceneIdx] || []
  // 用户点击左侧生成记录后保持选中，勿被刷新/使用中主视频强制盖回
  if (
    shouldPreserveDubbingNavSelection({
      currentKey: S.selectedNavKeyByIndex.get()[sceneIdx],
      hist,
      navKeySource,
      navKeyLoading
    })
  ) {
    return
  }
  const lipKey = panel?.dubbingLipSyncKey
  if (lipKey != null && String(lipKey).trim()) {
    const key = String(lipKey).trim()
    // lipKey 指向源视频时：无配音历史才停在源视频；有历史仍走下方兜底展示配音结果
    if ((key === navKeySource || key === '__source__') && !hist.length) {
      S.selectedNavKeyByIndex.set({ ...S.selectedNavKeyByIndex.get(), [sceneIdx]: navKeySource })
      return
    }
    if (hist.some((h) => h.id === key)) {
      S.selectedNavKeyByIndex.set({ ...S.selectedNavKeyByIndex.get(), [sceneIdx]: key })
      return
    }
  }

  const lipUrl = String(panel?.dubbingLipSyncVideoUrl ?? '').trim()
  if (lipUrl) {
    const byUrl = hist.find((h) => h.url === lipUrl)
    if (byUrl) {
      S.selectedNavKeyByIndex.set({ ...S.selectedNavKeyByIndex.get(), [sceneIdx]: byUrl.id })
      return
    }
    const sourceUrl =
      getPanelStoryboardVideoUrl(ctx.props().storyboardVideoPanels[sceneIdx]) ||
      getVideoUrl(ctx, sceneIdx)
    if (sourceUrl && sourceUrl === lipUrl && !hist.length) {
      S.selectedNavKeyByIndex.set({ ...S.selectedNavKeyByIndex.get(), [sceneIdx]: navKeySource })
      return
    }
  }

  const serverList = S.serverVideoRecordsByIndex.get()[sceneIdx] || []
  const activeCompose = serverList.find(
    (r) => isComposeStoryboardVideoRecord(r._serverRow) && r._serverRow?.isSelected === 1
  )
  if (activeCompose?.url) {
    const hit = hist.find((h) => h.url === activeCompose.url)
    if (hit) {
      S.selectedNavKeyByIndex.set({ ...S.selectedNavKeyByIndex.get(), [sceneIdx]: hit.id })
      return
    }
  }

  const nextKey = resolveDubbingPreviewNavKey({
    hist,
    currentKey: S.selectedNavKeyByIndex.get()[sceneIdx],
    navKeySource,
    navKeyLoading,
    hasSourceVideo: !!getVideoUrl(ctx, sceneIdx)
  })
  S.selectedNavKeyByIndex.set({ ...S.selectedNavKeyByIndex.get(), [sceneIdx]: nextKey })
}

export function applyComposeRowsFromProject(ctx: DubbingModalCtx, rows: StoryboardRecordRow[]) {
  const S = ctx.state
  const byStoryboardId = groupStoryboardRecordsByStoryboardId(rows)
  const nextServerRecords = { ...S.serverVideoRecordsByIndex.get() }

  ctx.props().dubbingPanels.forEach((_, sceneIdx) => {
    const sid = resolveStoryboardIdForIndex(ctx, sceneIdx)
    if (!sid) return
    const sceneRows = byStoryboardId.get(sid) ?? []
    nextServerRecords[sceneIdx] = sceneRows
      .filter((r) => !!String(r?.fileUrl ?? '').trim())
      .map(mapRecordRowToVideoThumb)
    syncComposeGenHistoryForScene(ctx, sceneIdx, sceneRows)
  })

  S.serverVideoRecordsByIndex.set(nextServerRecords)
}

/** 弹窗打开：一次拉取项目 compose 记录，为全部分镜同步配音生成历史 */
export async function prefetchComposeGenHistoryForAllScenes(
  ctx: DubbingModalCtx,
  options?: {
    force?: boolean
    rows?: StoryboardRecordRow[]
  }
) {
  if (options?.rows?.length) {
    applyComposeRowsFromProject(ctx, options.rows)
    return
  }

  if (ctx.prefetchComposeGenHistoryInflight.current && !options?.force) {
    return ctx.prefetchComposeGenHistoryInflight.current
  }

  const request = (async () => {
    const saveCtx = await resolveStoryScriptSaveContext(useCreationStore.getState(), ctx.route())
    if (!saveCtx) return

    let rows: StoryboardRecordRow[] = []
    try {
      rows = await fetchProjectStoryboardRecords(saveCtx, 'compose', { force: options?.force })
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '获取配音生成记录失败')
      return
    }

    applyComposeRowsFromProject(ctx, rows)
  })()

  ctx.prefetchComposeGenHistoryInflight.current = request
  try {
    await request
  } finally {
    if (ctx.prefetchComposeGenHistoryInflight.current === request) {
      ctx.prefetchComposeGenHistoryInflight.current = null
    }
  }
}

export async function refreshServerVideoRecords(
  ctx: DubbingModalCtx,
  i: number,
  opts?: { force?: boolean }
) {
  const S = ctx.state
  const storyboardId = resolveStoryboardIdForIndex(ctx, i)
  if (!storyboardId) return
  if (!opts?.force && S.serverVideoRecordsByIndex.get()[i] != null) {
    reconcileSelectedNavKeyForScene(ctx, i)
    return
  }

  const inflight = ctx.serverVideoRecordsInflightByIndex.get(i)
  if (inflight) return inflight

  const request = (async () => {
    try {
      const saveCtx = await resolveStoryScriptSaveContext(useCreationStore.getState(), ctx.route())
      if (!saveCtx) return
      const rows = await fetchStoryboardRecordsForStoryboard(saveCtx, storyboardId, 'compose', {
        force: opts?.force
      })
      const mapped = rows
        .filter((r) => !!String(r?.fileUrl ?? '').trim())
        .map(mapRecordRowToVideoThumb)
      S.serverVideoRecordsByIndex.set({ ...S.serverVideoRecordsByIndex.get(), [i]: mapped })
      syncComposeGenHistoryForScene(ctx, i, rows)
      reconcileSelectedNavKeyForScene(ctx, i)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '获取生成记录失败')
    } finally {
      ctx.serverVideoRecordsInflightByIndex.delete(i)
    }
  })()

  ctx.serverVideoRecordsInflightByIndex.set(i, request)
  return request
}

export function removeLocalDubbingHistoryItem(
  ctx: DubbingModalCtx,
  sceneIdx: number,
  itemId: string
) {
  const S = ctx.state
  const prev = S.genHistoryByIndex.get()[sceneIdx] || []
  const deleted = prev.find((h) => h.id === itemId)
  const next = prev.filter((h) => h.id !== itemId)
  S.genHistoryByIndex.set({ ...S.genHistoryByIndex.get(), [sceneIdx]: next })

  const panel = ctx.props().dubbingPanels[sceneIdx]
  const clearLip =
    !!panel &&
    (String(panel.dubbingLipSyncKey || '') === itemId ||
      (!!deleted?.url && String(panel.dubbingLipSyncVideoUrl || '') === deleted.url))

  ctx.emitPanelsUpdate(
    ctx.props().dubbingPanels.map((p, idx) => {
      if (idx !== sceneIdx) return p
      const base = { ...p, dubbingGenHistory: next }
      if (!clearLip) return base
      return {
        ...base,
        dubbingLipSyncVideoUrl: undefined,
        dubbingLipSyncKey: undefined,
        storyboardDubbingConfirmed: false,
        status: (base.dialogue && String(base.dialogue).trim() ? 'done' : 'pending') as
          | 'done'
          | 'pending'
      }
    })
  )
  if (clearLip) {
    const ns = new Set(S.confirmedDubbingThisSession.get())
    ns.delete(sceneIdx)
    S.confirmedDubbingThisSession.set(ns)
    const pre = { ...S.preConfirmPanelByIndex.get() }
    delete pre[sceneIdx]
    S.preConfirmPanelByIndex.set(pre)
  }
  reconcileSelectedNavKeyForScene(ctx, sceneIdx)
}
