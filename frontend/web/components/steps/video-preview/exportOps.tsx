import { message } from 'antd'
import { LoadingOutlined } from '@ant-design/icons'
import { useCreationStore, liveGenScopeKeyFromIds } from '~/stores/creation'
import {
  exportEpisodeVideoFromTimeline,
  fetchEpisodeExportStatusForContext,
  EpisodeExportFollowPausedError,
  followEpisodeExportViaStatus,
  shouldKeepEpisodeExportFollowTask,
  type EpisodeVideoExportOutcome
} from '~/hooks/useEpisodeVideoExport'
import { downloadEpisodeSegmentsZipForContext } from '~/hooks/useEpisodeTimeline'
import { resolveEpisodeExportProgressDisplay } from '~/utils/episodeExportProgress'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { hasPendingReauditVideo } from '~/utils/projectAudit'
import { hasClipVideoUrl } from './layoutOps'
import { reloadEpisodeTimelineFromServer } from './timelineOps'
import type { VideoPreviewCtx } from './types'

/** 成片导出：进度 toast、状态跟进（export/status 轮询）、分段素材下载（原 setup 导出函数区） */

/** 业务接口 reject 多为 { code, msg }，优先用后端 msg */
export function exportApiErr(e: unknown, fallback: string): string {
  const x = e as { msg?: string; message?: string }
  const text = String(x?.msg || x?.message || '').trim()
  return text || fallback
}

export function showEpisodeExportProgress(
  ctx: VideoPreviewCtx,
  progress: { exportProgress?: number; exportStatus?: number },
  messageKey = 'export'
) {
  const S = ctx.state
  const store = useCreationStore.getState()
  const currentScopeKey = liveGenScopeKeyFromIds(store.currentProjectId, store.currentEpisodeId)
  if (currentScopeKey !== ctx.runtime.exportProgressScopeKey) {
    ctx.runtime.exportProgressScopeKey = currentScopeKey
    S.exportProgressPercent.set(0)
  }
  const exportStatus = Number(progress.exportStatus)
  const percent = resolveEpisodeExportProgressDisplay({
    progress: progress.exportProgress,
    exportStatus,
    previousPercent: S.exportProgressPercent.get()
  })
  S.exportProgressPercent.set(percent)
  const label = exportStatus === 2 ? '视频合成完成，正在准备下载' : '视频合成中'
  const displayPercent = Math.floor(percent)
  // 关闭 message 默认左侧 loading（会单独占一行），改放到文案后面
  message.open({
    key: messageKey,
    type: 'loading',
    duration: 0,
    className: 'episode-export-progress-toast-notice',
    icon: <span className="episode-export-progress-toast__hide-default-icon" aria-hidden />,
    content: (
      <div className="episode-export-progress-toast">
        <div className="episode-export-progress-toast__header">
          <span className="episode-export-progress-toast__label">
            {label}
            <LoadingOutlined spin className="episode-export-progress-toast__spin" />
          </span>
          <span className="episode-export-progress-toast__percent">{displayPercent}%</span>
        </div>
        <div
          className="episode-export-progress-toast__track"
          role="progressbar"
          aria-label={label}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={displayPercent}
        >
          <span
            className="episode-export-progress-toast__bar"
            style={{ width: `${percent}%` }}
          />
        </div>
      </div>
    )
  })
}

export async function refreshExportStatusFromServer(ctx: VideoPreviewCtx) {
  if (typeof window === 'undefined') return
  const S = ctx.state
  const store = useCreationStore.getState()
  const saveCtx = await resolveStoryScriptSaveContext(store, ctx.getRoute())
  if (!saveCtx) return
  const requestedScopeKey = liveGenScopeKeyFromIds(saveCtx.projectId, saveCtx.episodeId)
  try {
    const status = await fetchEpisodeExportStatusForContext({
      projectId: saveCtx.projectId,
      episodeId: saveCtx.episodeId,
      episodeEditorId: store.currentEpisodeEditorId
    })
    const latest = useCreationStore.getState()
    const currentScopeKey = liveGenScopeKeyFromIds(latest.currentProjectId, latest.currentEpisodeId)
    if (currentScopeKey !== requestedScopeKey) return
    latest.setCurrentMediaContext({
      episodeEditorId: status.episodeEditorId,
      finalVideoUrl: status.finalVideoUrl ?? null,
      pendingVideoUrl: status.pendingVideoUrl ?? null,
      exportStatus: status.exportStatus
    })
    S.exportNeedReaudit.set(Boolean(status.needReaudit))
    S.exportPendingVideoUrl.set(String(status.pendingVideoUrl || '').trim())
    S.exportFinalVideoUrl.set(String(status.finalVideoUrl || '').trim())
    if (Number(status.exportStatus) === 1) {
      latest.setEpisodeExportFollowTask(requestedScopeKey, {
        episodeEditorId: status.episodeEditorId,
        active: true
      })
      showEpisodeExportProgress(ctx, {
        exportProgress: status.exportProgress ?? undefined,
        exportStatus: 1
      })
      void resumeEpisodeExportFollowIfNeeded(ctx)
    }
  } catch {
    const latest = useCreationStore.getState()
    const currentScopeKey = liveGenScopeKeyFromIds(latest.currentProjectId, latest.currentEpisodeId)
    if (currentScopeKey !== requestedScopeKey) return
    S.exportNeedReaudit.set(
      hasPendingReauditVideo({
        pendingVideoUrl: latest.currentPendingVideoUrl
      })
    )
    S.exportPendingVideoUrl.set(String(latest.currentPendingVideoUrl || '').trim())
    S.exportFinalVideoUrl.set(String(latest.currentFinalVideoUrl || '').trim())
  }
}

/** 当前页跟进导出进度（export/status 轮询）；切步骤时 abort（暂停），回到预览再恢复 */
export function pauseEpisodeExportFollow(ctx: VideoPreviewCtx) {
  const R = ctx.runtime
  R.exportFollowGeneration += 1
  try {
    R.exportFollowAbort?.abort()
  } catch {
    /* ignore */
  }
  R.exportFollowAbort = null
  R.exportFollowInFlight = null
}

function applyExportOutcomeToUi(ctx: VideoPreviewCtx, result: EpisodeVideoExportOutcome) {
  const S = ctx.state
  useCreationStore.getState().setCurrentMediaContext({
    episodeEditorId: result.episodeEditorId,
    finalVideoUrl: result.finalVideoUrl ?? null,
    pendingVideoUrl: result.pendingVideoUrl ?? null,
    exportStatus: 2
  })
  S.exportNeedReaudit.set(Boolean(result.needReaudit))
  S.exportPendingVideoUrl.set(String(result.pendingVideoUrl || '').trim())
  S.exportFinalVideoUrl.set(String(result.finalVideoUrl || '').trim())
}

function notifyExportSuccess(
  ctx: VideoPreviewCtx,
  result: EpisodeVideoExportOutcome,
  messageKey: string,
  options?: { openModal?: boolean }
) {
  applyExportOutcomeToUi(ctx, result)
  // 合成成功不等于下载成功；先关闭进度提示，下载阶段由壳层接管同一个 message key。
  message.destroy(messageKey)
  if (options?.openModal !== false) {
    ctx.getShell()?.notifyPreviewExportSuccess(result.videoUrl)
  }
}

export async function resumeEpisodeExportFollowIfNeeded(ctx: VideoPreviewCtx) {
  if (typeof window === 'undefined') return
  const S = ctx.state
  const R = ctx.runtime
  const store = useCreationStore.getState()
  const scopeKey = liveGenScopeKeyFromIds(store.currentProjectId, store.currentEpisodeId)
  const persisted = store.getEpisodeExportFollowTask(scopeKey)
  if (!persisted) return
  if (R.exportFollowInFlight) return

  const gen = ++R.exportFollowGeneration
  const abort = new AbortController()
  R.exportFollowAbort = abort
  const key = 'export'
  S.exporting.set(true)
  showEpisodeExportProgress(ctx, { exportProgress: S.exportProgressPercent.get(), exportStatus: 1 }, key)

  const run = (async () => {
    try {
      const saveCtx = await resolveStoryScriptSaveContext(useCreationStore.getState(), ctx.getRoute())
      if (gen !== R.exportFollowGeneration || abort.signal.aborted) {
        throw new EpisodeExportFollowPausedError()
      }
      const result = await followEpisodeExportViaStatus({
        episodeEditorId: persisted.episodeEditorId ?? useCreationStore.getState().currentEpisodeEditorId,
        projectId: saveCtx?.projectId,
        episodeId: saveCtx?.episodeId,
        signal: abort.signal,
        onProgress: (progress) => {
          if (gen !== R.exportFollowGeneration) return
          showEpisodeExportProgress(ctx, progress, key)
        }
      })
      // 切步竞态：结果已出仍弹窗，避免丢成功态且无法 resume
      useCreationStore.getState().clearEpisodeExportFollowTask(scopeKey)
      await reloadEpisodeTimelineFromServer(ctx)
      notifyExportSuccess(ctx, result, key, { openModal: true })
    } catch (e: unknown) {
      if (shouldKeepEpisodeExportFollowTask(e) || gen !== R.exportFollowGeneration) {
        message.destroy(key)
        return
      }
      useCreationStore.getState().clearEpisodeExportFollowTask(scopeKey)
      message.error({
        content: exportApiErr(e, '导出失败'),
        key,
        duration: 4
      })
    } finally {
      if (gen === R.exportFollowGeneration) {
        S.exporting.set(false)
        if (R.exportFollowAbort === abort) R.exportFollowAbort = null
        R.exportFollowInFlight = null
      }
    }
  })()

  R.exportFollowInFlight = run
  await run
}

export async function handleExport(ctx: VideoPreviewCtx): Promise<{
  videoUrl: string
  needReaudit?: boolean
  episodeEditorId?: number
} | null> {
  if (typeof window === 'undefined') return null
  const S = ctx.state
  const R = ctx.runtime
  if (!S.videoClips.get().length) {
    message.warning('暂无可导出视频，请先同步前面步骤')
    return null
  }
  if (!S.videoClips.get().some((clip) => hasClipVideoUrl(clip))) {
    message.warning('时间轴上暂无有效视频片段，请先同步前面步骤')
    return null
  }
  if (R.exportFollowInFlight) {
    message.info('视频正在合成中，请稍候')
    return null
  }

  pauseEpisodeExportFollow(ctx)
  S.exportProgressPercent.set(0)
  const gen = ++R.exportFollowGeneration
  const abort = new AbortController()
  R.exportFollowAbort = abort
  S.exporting.set(true)
  const key = 'export'
  try {
    message.loading({ content: '保存时间轴并准备导出…', key, duration: 0 })
    try {
      const saved = await R.timelineSaver.flushNow()
      if (saved?.timeline) S.serverTimelineBaseline.set(saved.timeline)
    } catch {
      /* 导出仍继续，timelineJson 会随 export 一并提交 */
    }
    if (gen !== R.exportFollowGeneration || abort.signal.aborted) {
      message.destroy(key)
      return null
    }
    const store = useCreationStore.getState()
    const scopeKey = liveGenScopeKeyFromIds(store.currentProjectId, store.currentEpisodeId)
    const followPromise = exportEpisodeVideoFromTimeline({
      store,
      route: ctx.getRoute(),
      timeline: {
        videoClips: S.videoClips.get(),
        voiceItems: S.voiceItems.get(),
        subtitleItems: S.subtitleItems.get(),
        musicItems: S.musicItems.get(),
        videoVolumePreset: S.videoVolumePreset.get(),
        globalBgm: ctx.getProps().bgm
      },
      resolution: (S.timelineResolution.get() as 'FHD') || 'FHD',
      // 不主动传 forceRecompose，统一由后端权威指纹决定复用或重新合成。
      signal: abort.signal,
      onProgress: (progress) => {
        if (gen !== R.exportFollowGeneration) return
        showEpisodeExportProgress(ctx, progress, key)
      }
    }).then(async (result) => {
      useCreationStore.getState().clearEpisodeExportFollowTask(scopeKey)
      // 导出会在服务端补齐 ASR/文本回落检查点，成功后以服务端工程覆盖本地旧快照。
      await reloadEpisodeTimelineFromServer(ctx)
      if (gen !== R.exportFollowGeneration) {
        // 离开瞬间刚好成功：壳层仍在，直接弹窗
        notifyExportSuccess(ctx, result, key, { openModal: true })
        return {
          videoUrl: result.videoUrl,
          needReaudit: Boolean(result.needReaudit),
          episodeEditorId: result.episodeEditorId
        }
      }
      // 顶栏导出由壳层根据返回值弹窗；此处只更新 UI，避免重复打开
      notifyExportSuccess(ctx, result, key, { openModal: false })
      return {
        videoUrl: result.videoUrl,
        needReaudit: Boolean(result.needReaudit),
        episodeEditorId: result.episodeEditorId
      }
    })
    R.exportFollowInFlight = followPromise.then(() => undefined).catch(() => undefined)
    const result = await followPromise
    return result
  } catch (e: unknown) {
    if (shouldKeepEpisodeExportFollowTask(e) || gen !== R.exportFollowGeneration) {
      message.destroy(key)
      return null
    }
    message.error({ content: exportApiErr(e, '导出失败'), key, duration: 4 })
    return null
  } finally {
    if (gen === R.exportFollowGeneration) {
      S.exporting.set(false)
      if (R.exportFollowAbort === abort) R.exportFollowAbort = null
      R.exportFollowInFlight = null
    }
  }
}

export async function handleDownloadSegments(ctx: VideoPreviewCtx) {
  if (typeof window === 'undefined') return
  const S = ctx.state
  if (S.segmentsDownloading.get()) return
  S.segmentsDownloading.set(true)
  const key = 'export-segments'
  try {
    message.loading({ content: '正在打包分段素材…', key, duration: 0 })
    const { filename } = await downloadEpisodeSegmentsZipForContext(
      { store: useCreationStore.getState(), route: ctx.getRoute() },
      (msg) => {
        message.loading({ content: msg, key, duration: 0 })
      }
    )
    message.success({
      content: `已开始下载 ${filename || '分段素材.zip'}`,
      key,
      duration: 3
    })
  } catch (e: unknown) {
    message.error({ content: exportApiErr(e, '分段导出失败'), key, duration: 3 })
  } finally {
    S.segmentsDownloading.set(false)
  }
}
