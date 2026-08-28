import { message } from 'antd'
import { useCreationStore } from '~/stores/creation'
import type { DubbingPanel } from '~/types'
import {
userStoryboardUnSetFinalVideo
} from '~/utils/businessApi'
import { clearProjectStoryboardRecordCache } from '~/utils/storyboardRecordBatch'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
getCurrentPanelLipSyncKey,
getGenHistoryForScene,
getRightNavEntries,
getSelectedNavKey,
resolveActiveComposeRecordId,
resolveComposeRecordIdFromGenItem,
resolveStoryboardIdForIndex
} from './derived'
import { loadDraftForIndex } from './draftOps'
import { isPanelDubbingConfigured } from './helpers'
import {
refreshServerVideoRecords
} from './recordsOps'
import type { DubbingModalCtx } from './types'
import { navKeyLoading,navKeySource } from './types'

import { clearDubbingLipSyncLocal } from './lipSyncPrimaryOps'

export function cancelPendingDubbing(ctx: DubbingModalCtx) {
  const S = ctx.state
  const i = S.currentSceneIndex.get()
  const np = { ...S.pendingDubbingByIndex.get() }
  delete np[i]
  S.pendingDubbingByIndex.set(np)
  const pp = { ...S.pendingPayloadByIndex.get() }
  delete pp[i]
  S.pendingPayloadByIndex.set(pp)
  loadDraftForIndex(ctx, i)
  message.success('已取消设置')
}

/** 取消音画同步结果：配音轨 compose 走 unSetFinalVideo（与取消分镜视频对称） */
export async function onCancelDubbingSetting(ctx: DubbingModalCtx) {
  const S = ctx.state
  if (S.isSettingFinalDubbing.get()) return
  const i = S.currentSceneIndex.get()
  if (S.pendingDubbingByIndex.get()[i]) {
    cancelPendingDubbing(ctx)
    return
  }

  const storyboardId = resolveStoryboardIdForIndex(ctx, i)
  const lipKey = getCurrentPanelLipSyncKey(ctx)
  const keepNavKey = S.selectedNavKeyByIndex.get()[i] || getSelectedNavKey(ctx)
  // 优先用当前预览项解析 recordId，避免 key 不一致时误清/漏调接口
  const selectedItem =
    keepNavKey && keepNavKey !== navKeySource && keepNavKey !== navKeyLoading
      ? getGenHistoryForScene(ctx).find((x) => x.id === keepNavKey)
      : null
  const composeRecordId =
    resolveComposeRecordIdFromGenItem(ctx, selectedItem, i) ?? resolveActiveComposeRecordId(ctx, i)

  const restoreNavSelection = () => {
    if (!keepNavKey || keepNavKey === navKeyLoading) return
    const entries = getRightNavEntries(ctx)
    if (entries.some((e) => e.key === keepNavKey)) {
      S.selectedNavKeyByIndex.set({ ...S.selectedNavKeyByIndex.get(), [i]: keepNavKey })
      return
    }
    // key 可能在刷新后变为 compose-xxx，按 URL 回落
    const url = selectedItem?.url || entries.find((e) => e.key === keepNavKey)?.url || ''
    if (url) {
      const hit = entries.find((e) => e.url === url)
      if (hit) {
        S.selectedNavKeyByIndex.set({ ...S.selectedNavKeyByIndex.get(), [i]: hit.key })
      }
    }
  }

  // 当前为 compose「使用中」：先调 unSetFinalVideo，再清本地；保持当前生成记录选中
  if (storyboardId && composeRecordId != null && lipKey && lipKey !== navKeySource) {
    S.isSettingFinalDubbing.set(true)
    try {
      const saveCtx = await resolveStoryScriptSaveContext(useCreationStore.getState(), ctx.route())
      await userStoryboardUnSetFinalVideo({
        ...(saveCtx ? { projectId: saveCtx.projectId, episodeId: saveCtx.episodeId } : {}),
        storyboardId,
        recordId: composeRecordId
      })
      if (saveCtx) clearProjectStoryboardRecordCache(saveCtx)
      clearDubbingLipSyncLocal(ctx, i)
      await refreshServerVideoRecords(ctx, i, { force: true })
      restoreNavSelection()
      message.success('取消成功')
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '取消音画同步失败')
    } finally {
      S.isSettingFinalDubbing.set(false)
    }
    return
  }

  if (lipKey === navKeySource || S.confirmedDubbingThisSession.get().has(i)) {
    // 原视频「使用中」：若仍有 compose 选中则一并取消
    if (storyboardId && composeRecordId != null) {
      S.isSettingFinalDubbing.set(true)
      try {
        const saveCtx = await resolveStoryScriptSaveContext(
          useCreationStore.getState(),
          ctx.route()
        )
        await userStoryboardUnSetFinalVideo({
          ...(saveCtx ? { projectId: saveCtx.projectId, episodeId: saveCtx.episodeId } : {}),
          storyboardId,
          recordId: composeRecordId
        })
        if (saveCtx) clearProjectStoryboardRecordCache(saveCtx)
        await refreshServerVideoRecords(ctx, i, { force: true })
      } catch (e: unknown) {
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || '取消音画同步失败')
        S.isSettingFinalDubbing.set(false)
        return
      } finally {
        S.isSettingFinalDubbing.set(false)
      }
    }
    const prev = S.preConfirmPanelByIndex.get()[i]
    if (prev) {
      const next = ctx.props().dubbingPanels.map((p, idx) =>
        idx === i ? ({ ...prev } as DubbingPanel) : p
      )
      ctx.emitPanelsUpdate(next)
    } else {
      clearDubbingLipSyncLocal(ctx, i)
    }
    const ns = new Set(S.confirmedDubbingThisSession.get())
    ns.delete(i)
    S.confirmedDubbingThisSession.set(ns)
    const pre = { ...S.preConfirmPanelByIndex.get() }
    delete pre[i]
    S.preConfirmPanelByIndex.set(pre)
    restoreNavSelection()
    message.success('取消成功')
    return
  }

  if (isPanelDubbingConfigured(ctx.props().dubbingPanels[i])) {
    clearDubbingLipSyncLocal(ctx, i)
    restoreNavSelection()
    message.success('取消成功')
    return
  }
  message.info('当前无可取消的设置')
}
