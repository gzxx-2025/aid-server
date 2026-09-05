'use client'

import { message } from 'antd'
import { useCallback, useRef, useState } from 'react'
import { downloadExportedFinalVideo } from '~/composables/useEpisodeVideoExport'
import { useCreationStore } from '~/stores/creation'
import type { PreviewExportBridge } from '~/utils/createFlowInjection'
import {
  retainPreviewExportBusyState,
  type PreviewExportBusyState
} from '~/utils/reactUpdateGuards'

/** 创作预览页的纯导出编排，不包含任何内容发布或审核能力。 */
export function useCreateFlowExport() {
  const [exportMenuOpen, setExportMenuOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const savingRef = useRef(false)
  const exportedEpisodeEditorIdRef = useRef<number | null>(null)
  const bridgeRef = useRef<PreviewExportBridge | null>(null)
  const [busyState, setBusyState] = useState<PreviewExportBusyState>({
    exporting: false,
    segmentsDownloading: false
  })

  const previewExportBusy = busyState.exporting || busyState.segmentsDownloading || saving

  const registerPreviewExportBridge = useCallback((bridge: PreviewExportBridge | null) => {
    bridgeRef.current = bridge
    setBusyState((current) => retainPreviewExportBusyState(current, bridge))
    if (!bridge) setExportMenuOpen(false)
  }, [])

  const saveExportedVideoToLocal = useCallback(async () => {
    if (savingRef.current) return
    const state = useCreationStore.getState()
    const projectId = Number(state.currentProjectId)
    const episodeId = state.currentProjectType === 'movie' ? 0 : Number(state.currentEpisodeId)
    const editorId = Number(exportedEpisodeEditorIdRef.current ?? state.currentEpisodeEditorId)
    const hasEditor = Number.isFinite(editorId) && editorId > 0
    const hasProject = Number.isFinite(projectId) && projectId > 0
    if (!hasEditor && !hasProject) {
      message.warning('暂无可保存的成片')
      return
    }

    savingRef.current = true
    setSaving(true)
    try {
      message.loading({ content: '正在下载中...', key: 'export', duration: 0 })
      await downloadExportedFinalVideo({
        episodeEditorId: hasEditor ? editorId : null,
        projectId: hasProject ? projectId : null,
        episodeId: Number.isFinite(episodeId) && episodeId >= 0 ? episodeId : 0
      })
      message.success({ content: '下载成功', key: 'export', duration: 2 })
    } catch (error: unknown) {
      const detail = error as { msg?: string; message?: string }
      message.error({
        content: detail?.msg || detail?.message || '成片下载失败',
        key: 'export',
        duration: 4
      })
    } finally {
      savingRef.current = false
      setSaving(false)
    }
  }, [])

  const onExportFullVideo = useCallback(async () => {
    setExportMenuOpen(false)
    const bridge = bridgeRef.current
    if (!bridge) {
      message.warning('预览页尚未就绪，请稍后再试')
      return
    }
    const result = await bridge.exportFullVideo()
    if (!result?.videoUrl) return
    const editorId = Number(result.episodeEditorId)
    exportedEpisodeEditorIdRef.current = Number.isFinite(editorId) && editorId > 0
      ? editorId
      : useCreationStore.getState().currentEpisodeEditorId
    await saveExportedVideoToLocal()
  }, [saveExportedVideoToLocal])

  const onExportSegments = useCallback(async () => {
    setExportMenuOpen(false)
    const bridge = bridgeRef.current
    if (!bridge) {
      message.warning('预览页尚未就绪，请稍后再试')
      return
    }
    await bridge.exportSegments()
  }, [])

  const handlePreviewExportSuccess = useCallback(() => {
    exportedEpisodeEditorIdRef.current = useCreationStore.getState().currentEpisodeEditorId
    void saveExportedVideoToLocal()
  }, [saveExportedVideoToLocal])

  return {
    exportMenuOpen,
    onExportMenuOpenChange: setExportMenuOpen,
    previewExportBusy,
    registerPreviewExportBridge,
    onExportFullVideo,
    onExportSegments,
    handlePreviewExportSuccess
  }
}
