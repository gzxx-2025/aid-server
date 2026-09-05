'use client'

import { message } from 'antd'
import { useRef } from 'react'
import type { StoryboardRecordRow } from '~/types/business-api'
import {
clearProjectStoryboardRecordCache,
fetchStoryboardRecordsForStoryboard
} from '~/utils/storyboardRecordBatch'
import { isPendingStoryboardRecord } from '~/utils/storyboardRecordPending'
import {
isOriginalStoryboardVideoRecord,
resolveStoryboardRecordDisplayName,
resolveStoryboardVideoSourceLabel
} from '~/utils/storyboardRecordRow'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import type { VideoModalCtx } from './types'

export function useVideoModalRecordRefreshOps(ctx: VideoModalCtx) {
  function mapRecordRowToImageItem(r: StoryboardRecordRow): any {
    const url = (r.fileUrl || '').trim()
    return {
      id: String(r.id ?? ''),
      url,
      thumbnail: url,
      title: '分镜图',
      source: '生成记录',
      importDate: r.createTime || undefined,
      _fromServer: true,
      _serverRow: r
    }
  }

  function localStoryboardImagesForScene(sceneIdx: number): any[] {
    const sp = ctx.resolveScriptPanelForSceneIndex(sceneIdx)
    const raw = sp?.images || ctx.props().scenes[sceneIdx]?.storyboardImages || []
    if (!Array.isArray(raw)) return []
    return raw.filter((img) => String(img?.url || img?.thumbnail || '').trim())
  }

  async function refreshStepPanelImagesForReference(sceneIdx = ctx.currentSceneIndex.get()) {
    const local = localStoryboardImagesForScene(sceneIdx)
    if (local.length) {
      ctx.stepPanelImagesCache.set({ ...ctx.stepPanelImagesCache.get(), [sceneIdx]: local })
      return
    }
    const storyboardId = Number(ctx.props().scenes[sceneIdx]?.storyboardId)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) {
      ctx.stepPanelImagesCache.set({ ...ctx.stepPanelImagesCache.get(), [sceneIdx]: [] })
      return
    }
    try {
      const rows = await fetchProjectRecordsForStoryboard(storyboardId, 'image')
      const mapped = rows
        .filter((r) => String(r?.fileUrl ?? '').trim())
        .map(mapRecordRowToImageItem)
      ctx.stepPanelImagesCache.set({ ...ctx.stepPanelImagesCache.get(), [sceneIdx]: mapped })
    } catch {
      ctx.stepPanelImagesCache.set({ ...ctx.stepPanelImagesCache.get(), [sceneIdx]: [] })
    }
  }

  function mapRecordRowToVideoItem(r: StoryboardRecordRow): any {
    const url = (r.fileUrl || '').trim()
    const label = resolveStoryboardRecordDisplayName(r) || '分镜视频'
    return {
      id: String(r.id ?? ''),
      url,
      title: label,
      source: resolveStoryboardVideoSourceLabel({ _fromServer: true, _serverRow: r }),
      importDate: r.createTime || undefined,
      isStoryboardVideo: r.isSelected === 1 && isOriginalStoryboardVideoRecord(r),
      _generating: isPendingStoryboardRecord(r),
      _fromServer: true,
      _serverRow: r
    }
  }

  async function fetchProjectRecordsForStoryboard(
    storyboardId: number,
    type: 'image' | 'video',
    options?: { force?: boolean }
  ): Promise<StoryboardRecordRow[]> {
    const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
    if (!saveCtx) return []
    const rows = await fetchStoryboardRecordsForStoryboard(saveCtx, storyboardId, type, options)
    if (type === 'video') {
      return rows.filter((r) => isOriginalStoryboardVideoRecord(r))
    }
    return rows
  }

  function isSameVideoRecordList(a: any[] | undefined, b: any[] | undefined): boolean {
    const left = Array.isArray(a) ? a : []
    const right = Array.isArray(b) ? b : []
    if (left.length !== right.length) return false
    return left.every((item, index) => {
      const other = right[index]
      return (
        String(item?.id ?? item?.url ?? item?.thumbnail ?? '') ===
          String(other?.id ?? other?.url ?? other?.thumbnail ?? '') &&
        !!item?._generating === !!other?._generating &&
        !!item?._localGeneratingPlaceholder === !!other?._localGeneratingPlaceholder &&
        !!item?.isStoryboardVideo === !!other?.isStoryboardVideo
      )
    })
  }

  async function refreshVideoRecords(
    sceneIdx: number,
    options?: { focusLatest?: boolean; force?: boolean }
  ) {
    const raw = ctx.props().scenes[sceneIdx]?.storyboardId
    const id = Number(raw)
    if (!Number.isFinite(id) || id <= 0) return
    try {
      const rows = await fetchProjectRecordsForStoryboard(id, 'video', { force: options?.force })
      const mapped = ctx.finalizeMappedVideosWhileGenerating(
        sceneIdx,
        rows
          .filter((r) => !!String(r?.fileUrl ?? '').trim() || isPendingStoryboardRecord(r))
          .map(mapRecordRowToVideoItem)
      )
      const prevVideos = ctx.props().scenes[sceneIdx]?.videos
      if (isSameVideoRecordList(mapped, prevVideos)) {
        if (sceneIdx !== ctx.currentSceneIndex.get()) return
        if (options?.focusLatest && mapped.length > 0) {
          const latestIdx = mapped.length - 1
          ctx.selectedVideoIdx.set(latestIdx)
          ctx.scrollVideoCanvasToIndex(sceneIdx, latestIdx)
        }
        return
      }
      ctx.emitUpdate(sceneIdx, { videos: mapped })

      if (sceneIdx !== ctx.currentSceneIndex.get()) return

      const pendingIdx = mapped.findIndex((m: any) => m._generating)
      if (pendingIdx >= 0) {
        ctx.selectedVideoIdx.set(pendingIdx)
        ctx.scrollVideoCanvasToIndex(sceneIdx, pendingIdx)
        return
      }

      if (options?.focusLatest && mapped.length > 0) {
        const latestIdx = mapped.length - 1
        ctx.selectedVideoIdx.set(latestIdx)
        ctx.scrollVideoCanvasToIndex(sceneIdx, latestIdx)
      }
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '获取生成记录失败')
    }
  }

  /** 生成/删除等写操作后：清缓存并强制重拉；同场景并发调用合并为一次请求 */
  const freshInflightRef = useRef<Promise<void> | null>(null)
  const freshInflightSceneIdxRef = useRef(-1)
  const freshWantFocusLatestRef = useRef(false)

  async function refreshVideoRecordsFresh(sceneIdx: number, options?: { focusLatest?: boolean }) {
    if (options?.focusLatest) freshWantFocusLatestRef.current = true

    if (freshInflightRef.current && freshInflightSceneIdxRef.current === sceneIdx) {
      await freshInflightRef.current
      return
    }

    freshInflightSceneIdxRef.current = sceneIdx
    freshInflightRef.current = (async () => {
      const saveCtx = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      if (saveCtx) clearProjectStoryboardRecordCache(saveCtx)
      await refreshVideoRecords(sceneIdx, {
        focusLatest: freshWantFocusLatestRef.current,
        force: true
      })
    })().finally(() => {
      freshInflightRef.current = null
      freshInflightSceneIdxRef.current = -1
      freshWantFocusLatestRef.current = false
    })

    await freshInflightRef.current
  }

  return {
    fetchProjectRecordsForStoryboard,
    localStoryboardImagesForScene,
    mapRecordRowToVideoItem,
    refreshStepPanelImagesForReference,
    refreshVideoRecords,
    refreshVideoRecordsFresh,
  }
}
