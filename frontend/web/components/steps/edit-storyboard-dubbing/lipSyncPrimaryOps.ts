import { message,Modal } from 'antd'
import { useCreationStore } from '~/stores/creation'
import type { DubbingPanel } from '~/types'
import {
userStoryboardRecordDelete,
userStoryboardSetFinal
} from '~/utils/businessApi'
import { notifyEpisodeTimelineRebuildRequested } from '~/utils/episodeTimelineRebuildSignal'
import { htmlToPlainText } from '~/utils/htmlPlain'
import { clearProjectStoryboardRecordCache } from '~/utils/storyboardRecordBatch'
import { confirmDubbingGeneration } from '~/utils/confirmDubbingGeneration'
import { getPanelStoryboardVideoUrl } from '~/utils/storyboardVideoCover'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
canDeleteHistoryDubbing,
canSetMainFromHistory,
getGenHistoryForScene,
getSelectedNavKey,
getVideoUrl,
onRightNavClick,
resolveComposeRecordIdFromGenItem,
resolveStoryboardIdForIndex
} from './derived'
import { persistCurrentDraft } from './draftOps'
import { runDubbingGenerateForScene } from './generateOps'
import {
refreshServerVideoRecords,
removeLocalDubbingHistoryItem
} from './recordsOps'
import type { DubbingGenItem,DubbingModalCtx,DubbingNavEntry } from './types'
import { navKeyLoading,navKeySource } from './types'

export function patchDubbingPanelAsLipSync(
  ctx: DubbingModalCtx,
  sceneIdx: number,
  patch: Partial<DubbingPanel>,
  snapshot = true
) {
  const S = ctx.state
  const panel = ctx.props().dubbingPanels[sceneIdx]
  if (!panel) return
  if (snapshot) {
    S.preConfirmPanelByIndex.set({
      ...S.preConfirmPanelByIndex.get(),
      [sceneIdx]: JSON.parse(JSON.stringify(panel)) as DubbingPanel
    })
  }
  const history = S.genHistoryByIndex.get()[sceneIdx] || panel.dubbingGenHistory || []
  const next = ctx.props().dubbingPanels.map((p, idx) =>
    idx !== sceneIdx
      ? p
      : {
          ...p,
          ...patch,
          dubbingGenHistory: history,
          status: 'done' as const,
          storyboardDubbingConfirmed: true as const
        }
  )
  ctx.emitPanelsUpdate(next)
  S.confirmedDubbingThisSession.set(new Set([...S.confirmedDubbingThisSession.get(), sceneIdx]))
}

export function clearDubbingLipSyncLocal(ctx: DubbingModalCtx, sceneIdx: number) {
  const S = ctx.state
  const next = ctx.props().dubbingPanels.map((x, idx) =>
    idx === sceneIdx
      ? {
          ...x,
          dubbingLipSyncVideoUrl: undefined,
          dubbingLipSyncKey: undefined,
          storyboardDubbingConfirmed: false,
          status: (x.dialogue && String(x.dialogue).trim() ? 'done' : 'pending') as
            | 'done'
            | 'pending'
        }
      : x
  )
  ctx.emitPanelsUpdate(next)
  const ns = new Set(S.confirmedDubbingThisSession.get())
  ns.delete(sceneIdx)
  S.confirmedDubbingThisSession.set(ns)
  const pre = { ...S.preConfirmPanelByIndex.get() }
  delete pre[sceneIdx]
  S.preConfirmPanelByIndex.set(pre)
}

export async function onStartDubbingPrepare(
  ctx: DubbingModalCtx,
  payload: { mode: 'tts' | 'upload'; localFile: File | null }
) {
  const S = ctx.state
  persistCurrentDraft(ctx)
  const i = S.currentSceneIndex.get()
  if (payload.mode === 'upload') {
    S.pendingDubbingByIndex.set({ ...S.pendingDubbingByIndex.get(), [i]: true })
    S.pendingPayloadByIndex.set({ ...S.pendingPayloadByIndex.get(), [i]: payload })
    message.info('可在右侧点击「设置分镜音画同步结果」确认提交')
    return
  }
  const plain = htmlToPlainText(S.draftDialogue.get()).trim()
  if (!plain) {
    message.warning('请输入配音台词')
    return
  }
  if (S.draftVoiceLibraryId.get() <= 0 && S.draftVoiceModelId.get() <= 0) {
    message.warning('请选择音色')
    return
  }
  // MiniMax 前端预检（最终以后端为准）
  {
    const { checkMiniMaxTtsTextLength } = await import('~/utils/ttsTextLimit')
    const hints: Array<string | null | undefined> = [
      S.draftTimbreCode.get(),
      S.draftVoiceName.get(),
      S.draftVoiceProviderHint.get()
    ]
    if (S.draftVoiceModelId.get() > 0) {
      try {
        const { userModelList } = await import('~/utils/businessApi')
        const models = await userModelList({ modelType: 'audio' })
        const hit = models.find((m) => Number(m.id) === Number(S.draftVoiceModelId.get()))
        if (hit) hints.push(hit.providerName, hit.modelCode, hit.modelName)
      } catch {
        /* ignore */
      }
    }
    const tooLong = checkMiniMaxTtsTextLength(plain, hints)
    if (tooLong) {
      message.warning(tooLong)
      return
    }
  }
  const vp = ctx.props().storyboardVideoPanels
  const vPanel = vp[i]
  const src = getPanelStoryboardVideoUrl(vPanel) || getVideoUrl(ctx, i)
  if (!src) {
    message.warning('暂无分镜视频，请先在「视频生成」步骤生成或选定视频')
    return
  }
  confirmDubbingGeneration(() => runDubbingGenerateForScene(ctx, i))
}

export async function confirmSetLipSync(ctx: DubbingModalCtx) {
  const S = ctx.state
  const i = S.currentSceneIndex.get()
  const panel = ctx.props().dubbingPanels[i]
  if (!panel) return

  const payload = S.pendingPayloadByIndex.get()[i]
  if (!payload || payload.mode !== 'upload') {
    message.warning('请使用文本朗读生成后，在卡片上点击「设置为音画同步结果」')
    return
  }

  let dubbingUploadedAudioUrl: string | undefined
  if (payload.localFile) {
    const { uploadAudioToOssWithToast } = await import('~/utils/ossUpload')
    const url = await uploadAudioToOssWithToast(payload.localFile)
    if (!url) return
    // /storyboard/upload 新版仅支持 image/video 落库；配音音频只走 OSS，URL 直接用于对口型
    dubbingUploadedAudioUrl = url
  }

  S.preConfirmPanelByIndex.set({
    ...S.preConfirmPanelByIndex.get(),
    [i]: JSON.parse(JSON.stringify(panel)) as DubbingPanel
  })

  const next = ctx.props().dubbingPanels.map((p, idx) => {
    if (idx !== i) return p
    return {
      ...p,
      dubbingEmotion: S.draftEmotion.get(),
      dubbingLipSync: S.draftLipSync.get(),
      dubbingVoiceName: S.draftVoiceName.get() || '无音色',
      dubbingVoiceAvatarUrl: S.draftVoiceAvatarUrl.get() || undefined,
      dubbingUploadedAudioUrl,
      status: 'done' as const,
      storyboardDubbingConfirmed: true as const,
      dialogue: p.dialogue || `本地配音：${payload.localFile?.name || '音频'}`
    }
  })
  ctx.emitPanelsUpdate(next)
  const np = { ...S.pendingDubbingByIndex.get() }
  delete np[i]
  S.pendingDubbingByIndex.set(np)
  const pp = { ...S.pendingPayloadByIndex.get() }
  delete pp[i]
  S.pendingPayloadByIndex.set(pp)
  S.confirmedDubbingThisSession.set(new Set([...S.confirmedDubbingThisSession.get(), i]))
  message.success('已记录本地配音，对口型任务开发中')
}

export async function applyLipSyncFromPreview(ctx: DubbingModalCtx) {
  const k = getSelectedNavKey(ctx)
  if (!k || k === navKeyLoading || k === navKeySource) return
  const item = getGenHistoryForScene(ctx).find((x) => x.id === k)
  if (item) await applyGeneratedLipSync(ctx, item)
}

/** 设配音轨 compose 为使用中：POST /api/user/storyboard/setFinal（recordType=video） */
export async function applyGeneratedLipSync(
  ctx: DubbingModalCtx,
  item: DubbingGenItem,
  opts?: { silent?: boolean }
) {
  const S = ctx.state
  const i = S.currentSceneIndex.get()
  const panel = ctx.props().dubbingPanels[i]
  if (!panel) return

  const storyboardId = resolveStoryboardIdForIndex(ctx, i)
  if (!storyboardId) {
    if (!opts?.silent) message.warning('分镜信息异常，请刷新后重试')
    return
  }

  if (!String(item.url || '').trim()) {
    if (!opts?.silent) message.warning('产物未完成')
    return
  }

  if (S.isSettingFinalDubbing.get() && !opts?.silent) return

  const run = async () => {
    let recordId = resolveComposeRecordIdFromGenItem(ctx, item, i)
    if (recordId == null) {
      await refreshServerVideoRecords(ctx, i, { force: true })
      const hist = S.genHistoryByIndex.get()[i] || []
      const refreshed =
        hist.find((h) => h.id === item.id) || hist.find((h) => h.url === item.url) || item
      recordId = resolveComposeRecordIdFromGenItem(ctx, refreshed, i)
    }
    if (recordId == null) {
      if (opts?.silent) {
        // 合成刚完成、记录尚未可查时，仅同步本地展示；不假装已 setFinal
        patchDubbingPanelAsLipSync(ctx, i, {
          dialogue: item.dialogue,
          dubbingVoiceName: item.voiceName,
          dubbingEmotion: item.emotion,
          dubbingLipSync: S.draftLipSync.get(),
          dubbingVoiceAvatarUrl: S.draftVoiceAvatarUrl.get() || undefined,
          dubbingLipSyncVideoUrl: item.url,
          dubbingLipSyncKey: item.id
        })
        return
      }
      message.warning('未找到配音视频记录，请稍后重试')
      return
    }

    await userStoryboardSetFinal({
      storyboardId,
      recordId,
      recordType: 'video'
    })
    const saveCtx = await resolveStoryScriptSaveContext(useCreationStore.getState(), ctx.route())
    if (saveCtx) clearProjectStoryboardRecordCache(saveCtx)
    void ctx.refreshHeaderTabs(true)

    const keepKey = S.selectedNavKeyByIndex.get()[i] || item.id
    await refreshServerVideoRecords(ctx, i, { force: true })

    const hist = S.genHistoryByIndex.get()[i] || []
    const synced =
      hist.find((h) => h.id === `compose-${recordId}`) ||
      hist.find((h) => h.url === item.url) ||
      item
    const nextKey = synced.id.startsWith('compose-') ? synced.id : `compose-${recordId}`
    patchDubbingPanelAsLipSync(ctx, i, {
      dialogue: synced.dialogue || item.dialogue,
      dubbingVoiceName: synced.voiceName || item.voiceName,
      dubbingEmotion: synced.emotion || item.emotion,
      dubbingLipSync: S.draftLipSync.get(),
      dubbingVoiceAvatarUrl: S.draftVoiceAvatarUrl.get() || undefined,
      dubbingLipSyncVideoUrl: synced.url || item.url,
      dubbingLipSyncKey: nextKey
    })
    // 刷新后 reconcile 可能改选中项：保持用户当前预览项
    const still =
      hist.some((h) => h.id === keepKey) || keepKey === nextKey || keepKey === navKeySource
    S.selectedNavKeyByIndex.set({
      ...S.selectedNavKeyByIndex.get(),
      [i]: still ? (hist.some((h) => h.id === nextKey) ? nextKey : keepKey) : nextKey
    })
    if (!opts?.silent) message.success('确认成功')
    notifyEpisodeTimelineRebuildRequested()
  }

  if (opts?.silent) {
    try {
      await run()
    } catch {
      patchDubbingPanelAsLipSync(ctx, i, {
        dialogue: item.dialogue,
        dubbingVoiceName: item.voiceName,
        dubbingEmotion: item.emotion,
        dubbingLipSync: S.draftLipSync.get(),
        dubbingVoiceAvatarUrl: S.draftVoiceAvatarUrl.get() || undefined,
        dubbingLipSyncVideoUrl: item.url,
        dubbingLipSyncKey: item.id
      })
    }
    return
  }

  S.isSettingFinalDubbing.set(true)
  try {
    await run()
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    message.error(err?.msg || err?.message || '设置音画同步失败')
  } finally {
    S.isSettingFinalDubbing.set(false)
  }
}

export async function handleSetMainFromHistory(ctx: DubbingModalCtx, nav: DubbingNavEntry) {
  if (!canSetMainFromHistory(ctx, nav) || ctx.state.isSettingFinalDubbing.get()) return
  onRightNavClick(ctx, nav.key)
  await new Promise((resolve) => setTimeout(resolve, 0))
  await applyLipSyncFromPreview(ctx)
}

export function handleDeleteHistoryDubbing(ctx: DubbingModalCtx, nav: DubbingNavEntry) {
  const S = ctx.state
  if (!canDeleteHistoryDubbing(ctx, nav)) {
    message.warning('当前记录无法删除')
    return
  }
  const sceneIdx = S.currentSceneIndex.get()
  const item = getGenHistoryForScene(ctx).find((x) => x.id === nav.key)
  if (!item) return

  Modal.confirm({
    title: '确认删除',
    content: '确定要删除这条生成记录吗？删除后不可恢复。',
    okText: '确定',
    cancelText: '取消',
    onOk: async () => {
      const recordId = resolveComposeRecordIdFromGenItem(ctx, item, sceneIdx)
      const storyboardId = resolveStoryboardIdForIndex(ctx, sceneIdx)

      if (recordId && storyboardId) {
        S.isDeletingDubbingRecord.set(true)
        try {
          const saveCtx = await resolveStoryScriptSaveContext(
            useCreationStore.getState(),
            ctx.route()
          )
          await userStoryboardRecordDelete({ storyboardId, recordId })
          if (saveCtx) clearProjectStoryboardRecordCache(saveCtx)
          removeLocalDubbingHistoryItem(ctx, sceneIdx, item.id)
          await refreshServerVideoRecords(ctx, sceneIdx, { force: true })
          void ctx.refreshHeaderTabs(true)
          message.success('删除成功')
        } catch (e: unknown) {
          const err = e as { msg?: string; message?: string }
          message.error(err?.msg || err?.message || '删除失败')
          throw e
        } finally {
          S.isDeletingDubbingRecord.set(false)
        }
        return
      }

      removeLocalDubbingHistoryItem(ctx, sceneIdx, item.id)
      message.success('已删除')
    }
  })
}

