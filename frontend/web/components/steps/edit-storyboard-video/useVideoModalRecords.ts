'use client'

import { message,Modal } from 'antd'
import {
userStoryboardRecordDelete,
userStoryboardSetFinalVideo,
userStoryboardUnSetFinalVideo,
userStoryboardUpload
} from '~/utils/businessApi'
import { uploadVideoToOssWithToast } from '~/utils/ossUpload'
import {
resolveStoryboardVideoRecordId,
resolveStoryboardVideoRecordIdFromRows
} from '~/utils/storyboardFinalRecordId'
import {
clearProjectStoryboardRecordCache
} from '~/utils/storyboardRecordBatch'
import { mergeVideoBatchSuccessItemsIntoVideos } from '~/utils/storyboardVideoSseFill'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import { readVideoDurationSeconds } from '~/utils/videoDuration'
import type { VideoModalCtx,VideoModalRecordsApi } from './types'
import { useVideoModalRecordRefreshOps } from './useVideoModalRecordRefreshOps'

/** 对齐 Vue nextTick */
function nextTick(fn: () => void) {
  setTimeout(fn, 0)
}

/** 生成记录列表拉取 / 主视频设置 / 删除 / 上传导入（原 setup 记录段逻辑） */
export function useVideoModalRecords(ctx: VideoModalCtx): void {
  const { fetchProjectRecordsForStoryboard, localStoryboardImagesForScene, mapRecordRowToVideoItem, refreshStepPanelImagesForReference, refreshVideoRecords, refreshVideoRecordsFresh } = useVideoModalRecordRefreshOps(ctx)
  /** 用 SSE complete 的 items 合并进当前分镜 videos（列表刷新后的兜底回填） */
  function applyTerminalVideoItemsToScene(sceneIdx: number, data: unknown) {
    const storyboardId = Number(ctx.props().scenes[sceneIdx]?.storyboardId)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return
    const title = String(ctx.props().scenes[sceneIdx]?.name || '').trim() || '分镜视频'
    const merged = mergeVideoBatchSuccessItemsIntoVideos(
      ctx.props().scenes[sceneIdx]?.videos,
      storyboardId,
      data,
      { title }
    )
    if (!merged.changed) return
    ctx.emitUpdate(sceneIdx, { videos: merged.videos })
    if (sceneIdx !== ctx.currentSceneIndex.get()) return
    if (merged.focusIndex != null && merged.focusIndex >= 0) {
      ctx.selectedVideoIdx.set(merged.focusIndex)
      ctx.scrollVideoCanvasToIndex(sceneIdx, merged.focusIndex)
    }
  }

  async function resolveStoryboardVideoRecordIdForUnset(
    video: any,
    storyboardId: number
  ): Promise<number | null> {
    const direct = resolveStoryboardVideoRecordId(video)
    if (direct) return direct
    const rows = await fetchProjectRecordsForStoryboard(storyboardId, 'video')
    return resolveStoryboardVideoRecordIdFromRows(video, rows)
  }

  async function persistManualStoryboardVideoUrl(videoUrl: string): Promise<number | null> {
    const url = videoUrl.trim()
    if (!url) return null

    const storyboardId = ctx.currentStoryboardId()
    if (!storyboardId) return null

    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    if (!saveCtx) return null

    const videoDuration = await readVideoDurationSeconds(url)

    const record = await userStoryboardUpload({
      projectId: saveCtx.projectId,
      episodeId: saveCtx.episodeId,
      storyboardId,
      imageUrl: url,
      mediaType: 'video',
      videoDuration
    })
    const recordId = Number(record?.id)
    return Number.isFinite(recordId) && recordId > 0 ? recordId : null
  }

  async function resolveOrPersistStoryboardVideoRecordId(video: any): Promise<number | null> {
    const existing = resolveStoryboardVideoRecordId(video)
    if (existing) return existing
    return persistManualStoryboardVideoUrl(String(video?.url || ''))
  }

  async function setAsStoryboardVideo(idx: number) {
    if (ctx.isSettingFinalVideo.get()) return

    const storyboardId = ctx.currentStoryboardId()
    if (!storyboardId) {
      message.warning('分镜信息异常，请刷新后重试')
      return
    }

    const video = ctx.currentSceneVideos()[idx]
    if (!video) {
      message.warning('没有可设置的视频')
      return
    }

    if (!String(video.url || '').trim()) {
      message.warning('产物未完成')
      return
    }

    ctx.isSettingFinalVideo.set(true)
    try {
      const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      if (!saveCtx) {
        message.warning('缺少项目信息，无法设置主视频')
        return
      }

      const recordId = await resolveOrPersistStoryboardVideoRecordId(video)
      if (!recordId) {
        message.warning('视频落库失败，无法设为分镜视频')
        return
      }

      await userStoryboardSetFinalVideo({
        projectId: saveCtx.projectId,
        episodeId: saveCtx.episodeId,
        storyboardId,
        recordId
      })
      clearProjectStoryboardRecordCache(saveCtx)
      await ctx.refreshHeaderTabs(true)
      ctx.store().clearStoryboardPanelVideoGenError(storyboardId)
      ctx.store().clearStoryboardPanelVideoGenStatus(storyboardId)
      await refreshVideoRecords(ctx.currentSceneIndex.get())
      message.success('确认成功')
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '设置分镜视频失败')
    } finally {
      ctx.isSettingFinalVideo.set(false)
    }
  }

  async function unsetAsStoryboardVideo(idx: number) {
    if (ctx.isSettingFinalVideo.get()) return

    const storyboardId = ctx.currentStoryboardId()
    if (!storyboardId) {
      message.warning('分镜信息异常，请刷新后重试')
      return
    }

    const video = ctx.currentSceneVideos()[idx]
    if (!video) return

    let recordId = resolveStoryboardVideoRecordId(video)
    if (!recordId) {
      recordId = await resolveStoryboardVideoRecordIdForUnset(video, storyboardId)
    }
    if (!recordId) {
      message.warning('分镜信息异常，请刷新后重试')
      return
    }

    ctx.isSettingFinalVideo.set(true)
    try {
      const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      await userStoryboardUnSetFinalVideo({
        ...(saveCtx ? { projectId: saveCtx.projectId, episodeId: saveCtx.episodeId } : {}),
        storyboardId,
        recordId
      })
      if (saveCtx) clearProjectStoryboardRecordCache(saveCtx)
      await ctx.refreshHeaderTabs(true)
      await refreshVideoRecords(ctx.currentSceneIndex.get())
      message.success('取消成功')
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '取消分镜视频失败')
    } finally {
      ctx.isSettingFinalVideo.set(false)
    }
  }

  async function handleSetMainFromHistory(videoIndex: number) {
    ctx.selectedVideoIdx.set(videoIndex)
    await setAsStoryboardVideo(videoIndex)
  }

  function canDeleteHistoryVideo(video: any): boolean {
    if (!video || video._generating || ctx.isDeletingRecord.get()) return false
    if (resolveStoryboardVideoRecordId(video)) return true
    return !!String(video?.url || '').trim()
  }

  function removeLocalVideo(index: number) {
    const scene = ctx.props().scenes[ctx.currentSceneIndex.get()]
    const videos = (scene?.videos || []).filter((_: any, i: number) => i !== index)
    ctx.emitUpdate(ctx.currentSceneIndex.get(), { videos })
    nextTick(() => {
      if (videos.length === 0) {
        ctx.selectedVideoIdx.set(0)
      } else if (ctx.selectedVideoIdx.get() >= videos.length) {
        ctx.selectedVideoIdx.set(videos.length - 1)
      }
    })
  }

  function handleDeleteVideo(videoIndex: number) {
    const video = ctx.currentSceneVideos()[videoIndex]
    if (!canDeleteHistoryVideo(video)) {
      message.warning('当前记录无法删除')
      return
    }

    Modal.confirm({
      title: '确认删除',
      content: '确定要删除这条生成记录吗？删除后不可恢复。',
      okText: '确定',
      cancelText: '取消',
      onOk: async () => {
        const recordId = resolveStoryboardVideoRecordId(video)
        const storyboardId = ctx.currentStoryboardId()

        if (recordId && storyboardId) {
          ctx.isDeletingRecord.set(true)
          try {
            const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
            await userStoryboardRecordDelete({ storyboardId, recordId })
            if (saveCtx) clearProjectStoryboardRecordCache(saveCtx)
            await refreshVideoRecords(ctx.currentSceneIndex.get(), { force: true })
            message.success('删除成功')
          } catch (e: unknown) {
            const err = e as { msg?: string; message?: string }
            message.error(err?.msg || err?.message || '删除失败')
            throw e
          } finally {
            ctx.isDeletingRecord.set(false)
          }
          return
        }

        removeLocalVideo(videoIndex)
        message.success('已删除')
      }
    })
  }

  function handleUploadLocalVideo() {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = 'video/*'
    input.onchange = async (e: Event) => {
      const file = (e.target as HTMLInputElement).files?.[0]
      if (!file) return

      const storyboardId = ctx.currentStoryboardId()
      if (!storyboardId) {
        message.warning('分镜信息异常，请刷新后重试')
        return
      }

      const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      if (!saveCtx) {
        message.warning('缺少项目信息，请从「我的作品」打开作品后再操作')
        return
      }

      const hideLoading = message.loading('正在上传视频...', 0)
      try {
        const videoDuration = await readVideoDurationSeconds(file)
        const url = await uploadVideoToOssWithToast(file)
        if (!url) return

        await userStoryboardUpload({
          projectId: saveCtx.projectId,
          episodeId: saveCtx.episodeId,
          storyboardId,
          imageUrl: url,
          mediaType: 'video',
          videoDuration
        })
        await refreshVideoRecordsFresh(ctx.currentSceneIndex.get(), { focusLatest: true })
        message.success('视频已添加')
      } catch (err: unknown) {
        const ax = err as { msg?: string; message?: string }
        message.error(ax?.msg || ax?.message || '视频上传失败，请重试')
      } finally {
        hideLoading()
      }
    }
    input.click()
  }

  function isVideoAsset(asset: any): boolean {
    if (!asset || typeof asset !== 'object') return false
    if (asset.type === 'video') return true
    const url = asset.url || asset.src || ''
    const name = asset.name || asset.title || ''
    if (
      /\.(mp4|webm|mov|avi|mkv|m4v)(\?|$)/i.test(url) ||
      /\.(mp4|webm|mov|avi|mkv|m4v)$/i.test(name)
    )
      return true
    return false
  }

  function handleOpenVideoLibrary() {
    ctx.showVideoLibraryModal.set(true)
  }

  function handleVideoLibraryImport(asset: any) {
    if (!isVideoAsset(asset)) {
      message.error('仅支持导入视频，请选择视频文件')
      return
    }
    const url = asset.url || asset.src
    if (!url) {
      message.error('视频地址无效')
      return
    }

    void (async () => {
      const storyboardId = ctx.currentStoryboardId()
      if (!storyboardId) {
        message.warning('分镜信息异常，请刷新后重试')
        return
      }

      const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      if (!saveCtx) {
        message.warning('缺少项目信息，请从「我的作品」打开作品后再操作')
        return
      }

      const hideLoading = message.loading('正在导入视频...', 0)
      try {
        const videoDuration = await readVideoDurationSeconds(String(url))
        await userStoryboardUpload({
          projectId: saveCtx.projectId,
          episodeId: saveCtx.episodeId,
          storyboardId,
          imageUrl: url,
          mediaType: 'video',
          videoDuration
        })
        await refreshVideoRecordsFresh(ctx.currentSceneIndex.get(), { focusLatest: true })
        message.success('视频已添加')
      } catch (err: unknown) {
        const ax = err as { msg?: string; message?: string }
        message.error(ax?.msg || ax?.message || '视频导入失败，请重试')
      } finally {
        hideLoading()
      }
    })()
  }

  const formatDate = (dateString: string) => {
    if (!dateString) return ''
    const date = new Date(dateString)
    return date.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    })
  }

  const api: VideoModalRecordsApi = {
    mapRecordRowToVideoItem,
    fetchProjectRecordsForStoryboard,
    refreshVideoRecords,
    refreshVideoRecordsFresh,
    applyTerminalVideoItemsToScene,
    refreshStepPanelImagesForReference,
    localStoryboardImagesForScene,
    setAsStoryboardVideo,
    unsetAsStoryboardVideo,
    handleSetMainFromHistory,
    canDeleteHistoryVideo,
    handleDeleteVideo,
    handleUploadLocalVideo,
    handleOpenVideoLibrary,
    handleVideoLibraryImport,
    formatDate
  }
  Object.assign(ctx, api)
}
